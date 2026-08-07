package com.setruth.game.room

import com.setruth.game.game.GameSettings
import com.setruth.game.game.Input
import com.setruth.game.game.JoinInfo
import com.setruth.game.game.NPC_ROSTER
import com.setruth.game.game.World
import com.setruth.game.game.createWorld
import com.setruth.game.game.updateWorld
import com.setruth.game.history.MatchPlayerRow
import com.setruth.game.history.MatchRepository
import com.setruth.game.net.PeerManager
import com.setruth.game.net.serializeSnapshot
import com.setruth.game.net.trySendText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil

object RoomManager {
    private val log = LoggerFactory.getLogger("RoomManager")
    private val rooms = ConcurrentHashMap<String, Room>()
    private val userRoom = ConcurrentHashMap<Long, String>()
    /** 用户级 WS 会话（session 属于用户而非房间：离房/换房不断连，建房/加房时绑进新 Member） */
    private val sessions = ConcurrentHashMap<Long, WebSocketSession>()
    private val lock = ReentrantLock()
    private val random = SecureRandom()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 序列化房间配置（encodeDefaults 保证客户端总是拿到完整字段） */
    private val settingsJson = Json { encodeDefaults = true }

    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // D7：剔除 0/O/1/I/L
    private const val MAX_CONN = 999      // 人数不限（内网活动场景）
    private const val MAX_ENTITIES = 999  // 对局实体（玩家+bot）不限
    private const val COUNTDOWN_SEC = 3  // D19

    // ---------- 查询 ----------

    fun currentRoom(userId: Long): Room? = userRoom[userId]?.let(rooms::get)

    private fun roomOf(userId: Long): Room =
        currentRoom(userId) ?: throw RoomError(HttpStatusCode.NotFound, "不在任何房间")

    // ---------- 建房 / 加房 ----------

    fun create(userId: Long, username: String, color: String, role: Role, settings: GameSettings = GameSettings()): Room = lock.withLock {
        if (userRoom.containsKey(userId)) throw RoomError(HttpStatusCode.Conflict, "先离开当前房间")
        var code: String
        do {
            code = (1..6).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        } while (rooms.containsKey(code))
        val room = Room(code = code, hostId = userId)
        room.settings = settings
        room.members[userId] = Member(userId, username, color, role, session = sessions[userId])
        rooms[code] = room
        userRoom[userId] = code
        room
    }

    fun join(userId: Long, username: String, code: String, color: String, role: Role): Room = lock.withLock {
        if (userRoom.containsKey(userId)) throw RoomError(HttpStatusCode.Conflict, "先离开当前房间")
        val room = rooms[code.uppercase()] ?: throw RoomError(HttpStatusCode.NotFound, "房间不存在")
        if (room.state != RoomState.WAITING) throw RoomError(HttpStatusCode.Conflict, "对局已开始，无法加入")
        if (room.members.size >= MAX_CONN) throw RoomError(HttpStatusCode.Conflict, "房间已满")
        // 加入者一律作为玩家进入（旁观者身份仅房主创建房间时可选）
        room.members[userId] = Member(userId, username, color, Role.PLAYER, session = sessions[userId])
        userRoom[userId] = room.code
        resetReady(room) // D18
        broadcastRoom(room)
        room
    }

    fun leave(userId: Long) = lock.withLock {
        val room = currentRoom(userId) ?: return@withLock
        if (room.hostId == userId) {
            destroyRoom(room, notify = true) // D9：房主离开即解散
        } else {
            room.members.remove(userId)
            userRoom.remove(userId)
            PeerManager.closePeer(userId)
            if (room.members.isEmpty()) {
                destroyRoom(room, notify = false)
            } else {
                resetReady(room) // D18
                broadcastRoom(room)
            }
        }
    }

    // ---------- 房主操作 ----------

    fun setBots(hostId: Long, count: Int) = lock.withLock {
        val room = roomOf(hostId)
        requireHost(room, hostId)
        if (room.state != RoomState.WAITING) throw RoomError(HttpStatusCode.Conflict, "对局已开始，无法调整 bot")
        val players = room.members.values.count { it.role == Role.PLAYER }
        if (count !in 0..(MAX_ENTITIES - players)) {
            throw RoomError(HttpStatusCode.BadRequest, "bot 数量需在 0-${MAX_ENTITIES - players} 之间")
        }
        room.bots = count
        broadcastRoom(room)
    }

    fun kick(hostId: Long, targetId: Long) = lock.withLock {
        val room = roomOf(hostId)
        requireHost(room, hostId)
        if (targetId == hostId) throw RoomError(HttpStatusCode.BadRequest, "不能踢出自己")
        val target = room.members.remove(targetId) ?: throw RoomError(HttpStatusCode.NotFound, "目标不在房间中")
        userRoom.remove(targetId)
        scope.launch { target.session?.trySendText("""{"t":"kicked"}""") }
        PeerManager.closePeer(targetId)
        resetReady(room) // D18
        broadcastRoom(room)
    }

    fun close(hostId: Long) = lock.withLock {
        val room = roomOf(hostId)
        requireHost(room, hostId)
        destroyRoom(room, notify = true)
    }

    fun updateSettings(hostId: Long, settings: GameSettings) = lock.withLock {
        val room = roomOf(hostId)
        requireHost(room, hostId)
        if (room.state != RoomState.WAITING) throw RoomError(HttpStatusCode.Conflict, "对局已开始，无法修改规则")
        room.settings = settings
        broadcastRoom(room) // 全员实时可见
    }

    // ---------- 成员操作 ----------

    fun setReady(userId: Long, ready: Boolean) = lock.withLock {
        val room = roomOf(userId)
        val member = room.members[userId]!!
        if (member.role != Role.PLAYER) throw RoomError(HttpStatusCode.BadRequest, "旁观者无需准备")
        if (room.state != RoomState.WAITING) throw RoomError(HttpStatusCode.Conflict, "当前状态不可切换准备")
        member.ready = ready
        broadcastRoom(room)
    }

    fun setRole(userId: Long, role: Role) = lock.withLock {
        val room = roomOf(userId)
        if (room.state != RoomState.WAITING) throw RoomError(HttpStatusCode.Conflict, "对局已开始，无法切换角色")
        if (room.hostId != userId) throw RoomError(HttpStatusCode.Forbidden, "仅房主可切换为旁观者") // 加入者固定为玩家
        room.members[userId]!!.role = role
        resetReady(room) // D18：切换角色重置全员 ready
        broadcastRoom(room)
    }

    // ---------- 开局 / 再来一局 ----------

    fun start(hostId: Long) = lock.withLock {
        val room = roomOf(hostId)
        requireHost(room, hostId)
        if (room.state != RoomState.WAITING) throw RoomError(HttpStatusCode.Conflict, "当前状态不可开始")
        val players = room.members.values.filter { it.role == Role.PLAYER }
        if (players.any { !it.ready }) throw RoomError(HttpStatusCode.Conflict, "还有玩家未准备") // D11
        if (players.size + room.bots < 1) throw RoomError(HttpStatusCode.Conflict, "至少需要一个参战实体")
        beginCountdown(room)
    }

    /** 进入 COUNTDOWN 并广播，3s 后开局（D19）。调用前需已完成 D11 校验 */
    private fun beginCountdown(room: Room) {
        room.state = RoomState.COUNTDOWN
        broadcast(room, """{"t":"countdown","n":$COUNTDOWN_SEC}""")
        room.countdownJob = scope.launch {
            delay(COUNTDOWN_SEC * 1000L)
            lock.withLock {
                if (room.state == RoomState.COUNTDOWN) beginPlayLocked(room)
            }
        }
    }

    fun again(hostId: Long) = lock.withLock {
        val room = roomOf(hostId)
        requireHost(room, hostId)
        if (room.state != RoomState.RESULT) throw RoomError(HttpStatusCode.Conflict, "当前状态不可再来一局")
        room.world = null
        room.inputs.clear()
        room.entityMeta = emptyMap()
        room.startedAt = null
        room.lastFeedId = 0
        room.lastLogId = 0
        room.state = RoomState.WAITING
        resetReady(room) // D18
        broadcastRoom(room)
    }

    // ---------- WS 会话挂接（D12：断线不踢出，重挂恢复） ----------

    fun attachSession(userId: Long, session: WebSocketSession): Boolean {
        // 无论是否在房间都先登记用户级会话（换房不断连的场景靠它把广播送进新房间）
        sessions[userId] = session
        val resend: Pair<Room, Member>?
        lock.withLock {
            val room = currentRoom(userId) ?: return false
            val member = room.members[userId] ?: return false
            member.session = session
            resend = if (room.state == RoomState.PLAYING) room to member else null
            // WS 一连上就补发当前房间快照（修加入后等下一次广播的盲等窗口）
            if (room.state != RoomState.PLAYING) {
                val payload = buildRoomPayload(room)
                val json = buildJsonObject {
                    put("t", "room")
                    payload.forEach { (k, v) -> put(k, v) }
                }.toString()
                scope.launch { session.trySendText(json) }
            }
        }
        // D12：对局中重连 → 重发 gameStart，重新协商 DC
        resend?.let { (room, member) ->
            scope.launch { session.trySendText(buildGameStart(room, member)) }
        }
        PeerManager.ensurePeer(userId) { msg ->
            scope.launch { currentRoom(userId)?.members?.get(userId)?.session?.trySendText(msg) }
        }
        return true
    }

    fun detachSession(userId: Long, session: WebSocketSession) = lock.withLock {
        sessions.remove(userId, session)
        val member = currentRoom(userId)?.members?.get(userId) ?: return@withLock
        if (member.session === session) member.session = null
    }

    // ---------- 输入注入（WS 降级与 DC 共用） ----------

    fun handleInput(userId: Long, dx: Float, dy: Float, attack: Boolean, aimX: Float?, aimY: Float?, emote: Boolean) {
        val room = currentRoom(userId) ?: return
        if (room.state != RoomState.PLAYING) return
        val member = room.members[userId] ?: return
        if (member.role != Role.PLAYER) return
        room.inputs["u$userId"] = Input(dx = dx, dy = dy, attack = attack, aimX = aimX, aimY = aimY, emote = emote)
    }

    // ---------- 内部：开局与 tick ----------

    private fun beginPlayLocked(room: Room) {
        val players = room.members.values.filter { it.role == Role.PLAYER }
        val joins = mutableListOf<JoinInfo>()
        val meta = mutableMapOf<String, EntityMeta>()
        players.forEach { m ->
            val id = "u${m.userId}"
            joins += JoinInfo(id, m.username, m.color, false)
            meta[id] = EntityMeta(false, m.userId, m.username)
        }
        repeat(room.bots) { i ->
            val (name, color) = NPC_ROSTER[i % NPC_ROSTER.size] // D14
            val suffix = if (i >= NPC_ROSTER.size) "·${i / NPC_ROSTER.size + 1}" else ""
            val id = "b${i + 1}"
            joins += JoinInfo(id, name + suffix, color, true)
            meta[id] = EntityMeta(true, null, name + suffix)
        }
        room.entityMeta = meta
        room.inputs.clear()
        room.lastFeedId = 0
        room.lastLogId = 0
        room.startedAt = Instant.now()
        room.world = createWorld(joins, room.settings)
        room.state = RoomState.PLAYING
        room.members.values.forEach { m ->
            val s = m.session ?: return@forEach
            val json = buildGameStart(room, m)
            scope.launch { s.trySendText(json) }
        }
        startTick(room)
    }

    private fun startTick(room: Room) {
        val world = room.world ?: return
        var last = System.nanoTime()
        var tick = 0L
        room.tickJob = scope.launch {
            while (isActive && room.state == RoomState.PLAYING) {
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9).toFloat().coerceAtMost(0.1f)
                last = now
                tick++
                val inputSnapshot = HashMap(room.inputs)
                room.inputs.replaceAll { _, v -> v.copy(emote = false) }
                updateWorld(world, dt, inputSnapshot)
                val (json, lastId, lastLog) = serializeSnapshot(world, tick, room.lastFeedId, room.lastLogId)
                room.lastFeedId = lastId
                room.lastLogId = lastLog
                PeerManager.broadcastSnapshot(room, json)
                if (world.gameOver != null) {
                    finishMatch(room)
                    break
                }
                val elapsedMs = (System.nanoTime() - now) / 1_000_000
                delay((50 - elapsedMs).coerceAtLeast(1))
            }
        }
    }

    private fun finishMatch(room: Room) {
        lock.withLock {
            if (room.state != RoomState.PLAYING) return
            room.state = RoomState.RESULT
        }
        val world = room.world ?: return
        val board = buildBoard(room, world)
        val resultJson = buildJsonObject {
            put("t", "result")
            putJsonArray("board") {
                board.forEach { b ->
                    add(buildJsonObject {
                        put("name", b.name); put("color", b.color); put("hp", b.hp)
                        put("score", (b.score * 10).toInt() / 10.0); put("rank", b.rank); put("isBot", b.isBot)
                    })
                }
            }
            putJsonArray("feed") {
                world.feed.forEach { f ->
                    add(buildJsonObject { put("text", f.text); put("color", f.color) })
                }
            }
            // 对局详细日志全量（时间序）：结算页与历史详情展示
            putJsonArray("logs") {
                world.logs.forEach { l ->
                    add(buildJsonObject {
                        put("tm", (l.time * 10).toInt() / 10.0); put("t", l.text); put("c", l.color)
                    })
                }
            }
        }.toString()
        broadcast(room, resultJson)
        // D21：战绩落库，失败仅记日志
        scope.launch(Dispatchers.IO) {
            try {
                val startedAt = room.startedAt ?: Instant.now()
                val duration = (Instant.now().epochSecond - startedAt.epochSecond).toInt()
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(resultJson).jsonObject
                val dbPayload = buildJsonObject {
                    put("board", parsed["board"]!!)
                    put("feed", parsed["feed"]!!)
                    put("logs", parsed["logs"]!!)
                }.toString()
                MatchRepository.insertMatch(
                    roomCode = room.code,
                    startedAt = startedAt,
                    durationSec = duration,
                    resultJson = dbPayload,
                    settingsJson = settingsJson.encodeToString(GameSettings.serializer(), room.settings),
                    players = board.map { b ->
                        val meta = room.entityMeta[b.entityId]
                        MatchPlayerRow(meta?.userId, b.name, b.rank, b.isBot)
                    },
                )
            } catch (e: Exception) {
                log.error("战绩落库失败 room=${room.code}", e)
            }
        }
    }

    private data class BoardEntry(
        val entityId: String, val name: String, val color: String,
        val hp: Int, val score: Float, val rank: Int, val isBot: Boolean,
    )

    /** 积分制排名：积分降序；同分存活者优先，再按血量降序，再按死亡时间靠后者优先 */
    private fun buildBoard(room: Room, world: World): List<BoardEntry> {
        val entities = listOf(world.player) + world.npcs
        data class Row(val entityId: String, val name: String, val color: String, val hp: Int, val score: Float, val isBot: Boolean, val alive: Boolean, val deathOrder: Int)
        val rows = mutableListOf<Row>()
        entities.filter { it.hp > 0 }.forEach { e ->
            rows += Row(e.id, e.name, e.color, ceil(e.hp).toInt(), e.score, e.id.startsWith("b"), true, -1)
        }
        // placements 先死在前 → deathOrder 越大死得越晚
        world.placements.forEachIndexed { i, p ->
            val meta = room.entityMeta.entries.firstOrNull { it.value.username == p.name }
            rows += Row(meta?.key ?: "", p.name, p.color, 0, p.score, meta?.value?.isBot ?: false, false, i)
        }
        rows.sortWith(
            compareByDescending<Row> { it.score }
                .thenByDescending { if (it.alive) 1 else 0 }
                .thenByDescending { it.hp }
                .thenByDescending { it.deathOrder },
        )
        return rows.mapIndexed { i, r -> BoardEntry(r.entityId, r.name, r.color, r.hp, r.score, i + 1, r.isBot) }
    }

    // ---------- 内部：消息构造 ----------

    fun buildRoomPayload(room: Room): JsonObject = buildJsonObject {
        put("code", room.code)
        put("hostId", room.hostId)
        put("state", room.state.name.lowercase())
        put("bots", room.bots)
        put("settings", settingsJson.encodeToJsonElement(GameSettings.serializer(), room.settings))
        putJsonArray("members") {
            room.members.values.forEach { m ->
                add(buildJsonObject {
                    put("id", m.userId); put("name", m.username); put("color", m.color)
                    put("role", m.role.name.lowercase()); put("ready", m.ready)
                })
            }
        }
    }

    private fun buildGameStart(room: Room, member: Member): String {
        val world = room.world ?: return "{}"
        val you = if (member.role == Role.PLAYER) "u${member.userId}" else null
        return buildJsonObject {
            put("t", "gameStart")
            if (you != null) put("you", you) else put("you", JsonNull)
            put("st", settingsJson.encodeToJsonElement(GameSettings.serializer(), room.settings))
            put("map", buildJsonObject { put("w", world.width); put("h", world.height) })
            putJsonArray("ob") {
                world.obstacles.forEach { o ->
                    add(buildJsonObject {
                        put("id", o.id); put("kind", o.kind.toString())
                        put("x", q(o.pos.x)); put("y", q(o.pos.y))
                        put("r", q(o.radius)); put("w", q(o.w)); put("h", q(o.h))
                    })
                }
            }
            put("seed", 0)
        }.toString()
    }

    private fun q(v: Float): Int = (v * 100).toInt()

    private fun broadcastRoom(room: Room) {
        val payload = buildRoomPayload(room)
        val json = buildJsonObject {
            put("t", "room")
            payload.forEach { (k, v) -> put(k, v) }
        }.toString()
        broadcast(room, json)
    }

    private fun broadcast(room: Room, text: String) {
        room.members.values.forEach { m -> m.session?.trySendText(text) }
    }

    private fun resetReady(room: Room) = room.members.values.forEach { it.ready = false }

    private fun requireHost(room: Room, userId: Long) {
        if (room.hostId != userId) throw RoomError(HttpStatusCode.Forbidden, "仅房主可操作")
    }

    private fun destroyRoom(room: Room, notify: Boolean) {
        room.countdownJob?.cancel()
        room.tickJob?.cancel()
        if (notify) broadcast(room, """{"t":"closed"}""")
        room.members.keys.forEach { userRoom.remove(it); PeerManager.closePeer(it) }
        rooms.remove(room.code)
    }
}
