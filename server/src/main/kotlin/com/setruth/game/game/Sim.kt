package com.setruth.game.game

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 服务端权威游戏模拟：web/src/game/mockWorld.ts 的忠实移植（数值零改动、无逻辑省略）。
 * - createMockWorld → [createWorld]（出生点逻辑照抄 mockWorld.ts:202-269）
 * - updateMockWorld → [updateWorld]（mockWorld.ts:270-702 全量：真人输入 / bot NPC 逻辑 /
 *   怪物 / 子弹 / 道具 / 缩圈 / GC / 轰炸区 / 表情 / 结算）
 *
 * 联机化适配（非数值改动）：
 * - mock 的单一本地玩家扩展为全部真人实体（player 槽 + npcs 中 isBot=false 者），输入来自 [inputs]；
 * - bot 走 mock 中 NPC 的 aiTarget/aiRetargetAt/attackCd 随机游走逻辑，不收玩家输入；
 * - mock 模块级共享的 nextId/manaWarnAt 改为全局 AtomicInteger / 每实体字段（多房间并行）；
 * - player 槽实体死亡且仍有存活者时，npcs 队首递补进 player 槽（实体 id 不变），避免尸体残留快照。
 */

/** 加入对局的实体信息（真人或 bot），实体 id 一律用 [id] 原样 */
data class JoinInfo(val id: String, val username: String, val color: String, val isBot: Boolean)

/** 真人玩家一帧的输入（移动向量 / 攻击 / 瞄准 / 表情） */
data class Input(
    val dx: Float,
    val dy: Float,
    val attack: Boolean,
    val aimX: Float?,
    val aimY: Float?,
    val emote: Boolean,
)

/** bot 名册（D14：mockWorld.ts NPC_ROSTER 的服务端内嵌拷贝，禁止改动）。Pair = name to color */
val NPC_ROSTER: List<Pair<String, String>> = listOf(
    "码农小王" to "#e24462",
    "Kotlin高手" to "#0095d5",
    "NPE_Killer" to "#2ecc71",
    "Compose萌新" to "#f1c40f",
    "协程少女" to "#e67e22",
    "空指针大师" to "#1abc9c",
    "数据类" to "#ff6b9d",
    "老高" to "#9b59b6",
    "JetBrain" to "#3498db",
    "尾递归" to "#e74c3c",
)

/**
 * 生成 [count] 个 bot 的 JoinInfo（D14：超过名册 10 个时循环取名加序号后缀）。
 * id 为 "b1".."bN"（可用 [idPrefix] 覆盖）。
 */
fun botJoinInfos(count: Int, idPrefix: String = "b"): List<JoinInfo> =
    (0 until count).map { i ->
        val (name, color) = NPC_ROSTER[i % NPC_ROSTER.size]
        val suffix = if (i < NPC_ROSTER.size) "" else "${i / NPC_ROSTER.size + 1}"
        JoinInfo(id = "$idPrefix${i + 1}", username = name + suffix, color = color, isBot = true)
    }

/** 道具定义（items.ts ITEM_META 的内嵌拷贝：kind/icon/label） */
private data class ItemDef(val kind: ItemKind, val icon: String, val label: String)

private val ITEM_DEFS = listOf(
    ItemDef(ItemKind.shield, "?:", "Elvis"),
    ItemDef(ItemKind.`val`, "val", "Immutable"),
    ItemDef(ItemKind.coroutines, "⚡", "Coroutines"),
    ItemDef(ItemKind.flow, "~", "Flow"),
    ItemDef(ItemKind.range, "!!", "NotNull"),
    ItemDef(ItemKind.heal, "var", "var"),
    ItemDef(ItemKind.haste, "⏩", "launch"),
)

private val OOM_TEXTS = listOf("OOM!", "OutOfMemoryError", "heap dump", "GC overhead", "OOM!", "java.lang.OOM")
private val OOM_COLORS = listOf("#ff5a3c", "#ffb340", "#ff8a5c")

/** 按 settings.itemWeights 加权随机选一种道具（权重和 ≤0 或缺省回退均匀） */
private fun pickItemDef(settings: GameSettings): ItemDef {
    val weights = ITEM_DEFS.map { settings.itemWeights[it.kind.name] ?: 0 }
    val total = weights.sum()
    if (total <= 0) return ITEM_DEFS[(Random.nextFloat() * ITEM_DEFS.size).toInt()]
    var r = Random.nextInt(total)
    ITEM_DEFS.forEachIndexed { i, d ->
        if (r < weights[i]) return d
        r -= weights[i]
    }
    return ITEM_DEFS.last()
}

private val PI_F = PI.toFloat()

/** 全局递增 id（mock 的模块级 nextId；FeedEntry/子弹/怪物/道具/障碍物/轰炸区共用，多房间并行安全） */
private val nextId = AtomicInteger(1000)

private fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v

private fun dist(a: Vec2, b: Vec2): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

private fun normalize(v: Vec2): Vec2 {
    val len = sqrt(v.x * v.x + v.y * v.y)
    return if (len == 0f) Vec2(0f, 0f) else Vec2(v.x / len, v.y / len)
}

/** 追加一条击杀播报（最新在前，封顶 8 条） */
private fun pushFeed(world: World, text: String, color: String = "#e6e2f7") {
    world.feed.add(0, FeedEntry(nextId.getAndIncrement(), text, color))
    if (world.feed.size > 8) world.feed.removeAt(world.feed.size - 1)
}

/** 追加一条对局详细日志（时间序，封顶 400 条；快照增量下发 + 结算全量入库） */
private fun pushLog(world: World, text: String, color: String = "#9a93b8") {
    world.logs.add(LogEntry(nextId.getAndIncrement(), world.time, text, color))
    if (world.logs.size > 400) world.logs.removeAt(0)
}

/** 积分格式化：整数不带小数点，否则保留 1 位（如 2 / 7.3） */
private fun fmtScore(v: Float): String {
    val r = (v * 10).roundToInt()
    return if (r % 10 == 0) (r / 10).toString() else (r / 10f).toString()
}

private fun makePlayer(id: String, name: String, color: String, pos: Vec2, face: Vec2, isBot: Boolean) =
    PlayerEntity(
        id = id,
        pos = pos,
        face = face,
        name = name,
        color = color,
        radius = 0.7f,
        hp = 100f,
        maxHp = 100f,
        speed = GameConfig.BASE_SPEED,
        mana = GameConfig.MAX_MANA,
        maxMana = GameConfig.MAX_MANA,
        isBot = isBot,
    )

/** 边缘随机一点 + 指向对侧的直线方向（怪物穿越轨迹） */
private fun edgeSpawn(w: Float, h: Float): Pair<Vec2, Vec2> {
    val side = (Random.nextFloat() * 4).toInt()
    val pos = when (side) {
        0 -> Vec2(Random.nextFloat() * w, -2f)
        1 -> Vec2(Random.nextFloat() * w, h + 2)
        2 -> Vec2(-2f, Random.nextFloat() * h)
        else -> Vec2(w + 2, Random.nextFloat() * h)
    }
    val target = Vec2(20 + Random.nextFloat() * (w - 40), 15 + Random.nextFloat() * (h - 30))
    return pos to normalize(Vec2(target.x - pos.x, target.y - pos.y))
}

/** 下一圈圆心：在当前圈内随机偏移（不超过半径差 ×0.6），并钳在地图内 */
private fun nextCenter(center: Vec2, radius: Float, targetR: Float, w: Float, h: Float): Vec2 {
    val maxOff = max(0f, (radius - targetR) * 0.6f)
    val a = Random.nextFloat() * PI_F * 2
    val d = Random.nextFloat() * maxOff
    return Vec2(
        clamp(center.x + cos(a) * d, targetR * 0.4f, w - targetR * 0.4f),
        clamp(center.y + sin(a) * d, targetR * 0.4f, h - targetR * 0.4f),
    )
}

/** 圆形实体推出障碍物（移动阻挡） */
private fun resolveObstacles(pos: Vec2, r: Float, obstacles: List<Obstacle>) {
    for (o in obstacles) {
        if (o.kind == ObstacleKind.pillar) {
            val d = dist(pos, o.pos)
            val minD = r + o.radius
            if (d < minD && d > 1e-6f) {
                pos.x = o.pos.x + ((pos.x - o.pos.x) / d) * minD
                pos.y = o.pos.y + ((pos.y - o.pos.y) / d) * minD
            }
        } else {
            val hw = o.w / 2 + r
            val hh = o.h / 2 + r
            val dx = pos.x - o.pos.x
            val dy = pos.y - o.pos.y
            if (abs(dx) < hw && abs(dy) < hh) {
                // 沿最浅穿透轴推出
                if (hw - abs(dx) < hh - abs(dy)) pos.x = o.pos.x + sign(if (dx != 0f) dx else 1f) * hw
                else pos.y = o.pos.y + sign(if (dy != 0f) dy else 1f) * hh
            }
        }
    }
}

/** 点/小圆是否撞上障碍物（子弹判定用） */
private fun hitsObstacle(pos: Vec2, r: Float, obstacles: List<Obstacle>): Boolean {
    for (o in obstacles) {
        if (o.kind == ObstacleKind.pillar) {
            if (dist(pos, o.pos) < r + o.radius) return true
        } else if (abs(pos.x - o.pos.x) < o.w / 2 + r && abs(pos.y - o.pos.y) < o.h / 2 + r) {
            return true
        }
    }
    return false
}

/**
 * 怪物撞障碍物 = 撞墙反弹：推出后按法线反射移动方向（石柱用法线反射，巨石按最浅穿透轴镜像）。
 * 通用 [resolveObstacles] 只修位置不改方向，怪物会一直顶在障碍物上卡住。
 */
private fun bounceMonsterOffObstacles(m: MonsterEntity, obstacles: List<Obstacle>) {
    for (o in obstacles) {
        if (o.kind == ObstacleKind.pillar) {
            val d = dist(m.pos, o.pos)
            val minD = m.radius + o.radius
            if (d < minD && d > 1e-6f) {
                val nx = (m.pos.x - o.pos.x) / d
                val ny = (m.pos.y - o.pos.y) / d
                m.pos.x = o.pos.x + nx * minD
                m.pos.y = o.pos.y + ny * minD
                val dot = m.dir.x * nx + m.dir.y * ny
                if (dot < 0f) { // 仅当朝障碍物飞时反射（防推出后二次反弹）
                    m.dir.x -= 2 * dot * nx
                    m.dir.y -= 2 * dot * ny
                }
            }
        } else {
            val hw = o.w / 2 + m.radius
            val hh = o.h / 2 + m.radius
            val dx = m.pos.x - o.pos.x
            val dy = m.pos.y - o.pos.y
            if (abs(dx) < hw && abs(dy) < hh) {
                // 沿最浅穿透轴推出并镜像该轴方向
                if (hw - abs(dx) < hh - abs(dy)) {
                    m.pos.x = o.pos.x + sign(if (dx != 0f) dx else 1f) * hw
                    m.dir.x = -m.dir.x
                } else {
                    m.pos.y = o.pos.y + sign(if (dy != 0f) dy else 1f) * hh
                    m.dir.y = -m.dir.y
                }
            }
        }
    }
}

/**
 * 开局生成 ~12 个永久障碍物：巨石/石柱各半，避让出生点与彼此。
 * （mock 只避让本地玩家一点；联机版避让全部出生点，属联机化适配，数值不变）
 */
private fun genObstacles(spawnPts: List<Vec2>): MutableList<Obstacle> {
    val obstacles = mutableListOf<Obstacle>()
    var tries = 0
    while (obstacles.size < 12 && tries < 400) {
        tries++
        val boulder = Random.nextFloat() < 0.5f
        // 大号掩体：巨石 2×2 / 3×2 格，石柱半径 1.0~1.4 格
        val w = if (boulder) (if (Random.nextFloat() < 0.5f) 3f else 2f) else 0f
        val h = if (boulder) 2f else 0f
        val radius = if (boulder) 0f else 1.0f + Random.nextFloat() * 0.4f
        val pos = Vec2(
            3 + Random.nextFloat() * (GameConfig.MAP_W - 6),
            3 + Random.nextFloat() * (GameConfig.MAP_H - 6),
        )
        if (spawnPts.any { dist(pos, it) < 3 }) continue
        val ext = (if (boulder) max(w, h) / 2 else radius) + 3.5f
        if (obstacles.any { o ->
                dist(pos, o.pos) < ext + (if (o.kind == ObstacleKind.boulder) max(o.w, o.h) / 2 else o.radius)
            }
        ) continue
        obstacles.add(
            Obstacle(nextId.getAndIncrement(), if (boulder) ObstacleKind.boulder else ObstacleKind.pillar, pos, radius, w, h),
        )
    }
    return obstacles
}

private fun spawnMonster(id: Int, w: Float, h: Float, kind: MonsterKind): MonsterEntity {
    val (pos, dir) = edgeSpawn(w, h)
    val hp = if (kind == MonsterKind.soe) 125f else 200f
    return MonsterEntity(id, kind, pos, dir, if (kind == MonsterKind.soe) 0.8f else 0.6f, hp, hp)
}

/** 一波怪物入场（仅开局调用一次；此后由 [topUpMonsters] 维持数量） */
private fun spawnWave(world: World, npe: Int, soe: Int) {
    repeat(npe) { world.monsters.add(spawnMonster(nextId.getAndIncrement(), world.width, world.height, MonsterKind.npe)) }
    repeat(soe) { world.monsters.add(spawnMonster(nextId.getAndIncrement(), world.width, world.height, MonsterKind.soe)) }
}

/**
 * 怪物补足：被杀/飞离的怪物立刻补上来。
 * 目标数量 = 初始 + 已完成缩圈轮数 × 每波（默认 NPE 10+7/轮，SOE 5+3/轮）。
 */
private fun topUpMonsters(world: World) {
    val targetNpe = world.settings.monsterInitNpe + world.circle.stage * world.settings.monsterWaveNpe
    val targetSoe = world.settings.monsterInitSoe + world.circle.stage * world.settings.monsterWaveSoe
    val npe = world.monsters.count { it.kind == MonsterKind.npe }
    val soe = world.monsters.count { it.kind == MonsterKind.soe }
    repeat(maxOf(0, targetNpe - npe)) {
        world.monsters.add(spawnMonster(nextId.getAndIncrement(), world.width, world.height, MonsterKind.npe))
    }
    repeat(maxOf(0, targetSoe - soe)) {
        world.monsters.add(spawnMonster(nextId.getAndIncrement(), world.width, world.height, MonsterKind.soe))
    }
}

/**
 * 创建对局世界（移植 createMockWorld，mockWorld.ts:202-269）。
 * world.player = players[0]，其余进 npcs；实体 id 一律用 JoinInfo.id 原样。
 * [settings] 为房间级规则（默认值 = 原 mock 写死常量），调用方负责 clamped()。
 */
fun createWorld(players: List<JoinInfo>, settings: GameSettings = GameSettings()): World {
    require(players.isNotEmpty()) { "players 不能为空" }
    val center = Vec2(GameConfig.MAP_W / 2, GameConfig.MAP_H / 2)
    // 出生点：第一安全区内均匀分散（两两间距 > 8 格），
    // 且内缩到相机始终能居中的区域（视野半宽 ~20 格 / 半高 ~12 格），避免出生在屏幕边缘
    val spawnPts = mutableListOf<Vec2>()
    var guard = 0
    while (spawnPts.size < players.size && guard < 600) {
        guard++
        val pt = Vec2(
            22 + Random.nextFloat() * (GameConfig.MAP_W - 44),
            13 + Random.nextFloat() * (GameConfig.MAP_H - 26),
        )
        if (spawnPts.all { dist(pt, it) > 8 }) spawnPts.add(pt)
    }
    while (spawnPts.size < players.size) {
        // 采样不足兜底：沿环均布
        val i = spawnPts.size
        val a = (i.toFloat() / players.size) * PI_F * 2
        spawnPts.add(Vec2(center.x + cos(a) * 30, center.y + sin(a) * 30))
    }

    // 照抄 mock：首个实体面向地图中心，其余面向 (0,0)
    val entities = players.mapIndexed { i, p ->
        val pos = spawnPts[i]
        val face = if (i == 0) normalize(Vec2(center.x - pos.x, center.y - pos.y)) else Vec2(0f, 0f)
        makePlayer(p.id, p.username, p.color, pos, face, p.isBot)
    }

    val world = World(
        width = GameConfig.MAP_W,
        height = GameConfig.MAP_H,
        settings = settings,
        player = entities.first(),
        npcs = entities.drop(1).toMutableList(),
        obstacles = genObstacles(spawnPts),
        feed = mutableListOf(),
        placements = mutableListOf(),
        logs = mutableListOf(),
        monsters = mutableListOf(),
        items = mutableListOf(),
        projectiles = mutableListOf(),
        floats = mutableListOf(),
        circle = CircleState(
            center = center.copy(),
            radius = GameConfig.INITIAL_RADIUS,
            targetCenter = nextCenter(center, GameConfig.INITIAL_RADIUS, settings.shrinkTargets[0], GameConfig.MAP_W, GameConfig.MAP_H),
            targetRadius = settings.shrinkTargets[0],
            stage = 0,
            phase = CirclePhase.idle,
            phaseStart = 0f,
            shrinkFromRadius = GameConfig.INITIAL_RADIUS,
            shrinkFromCenter = center.copy(),
        ),
        bombs = mutableListOf(),
        nextBombAt = 20f,
        gameOver = null,
        time = 0f,
    )

    repeat(settings.itemCount) { i ->
        val d = pickItemDef(settings)
        var ipos = Vec2(
            15 + Random.nextFloat() * (GameConfig.MAP_W - 30),
            12 + Random.nextFloat() * (GameConfig.MAP_H - 24),
        )
        var j = 0
        while (j < 5 && hitsObstacle(ipos, 0.6f, world.obstacles)) {
            j++
            ipos = Vec2(
                15 + Random.nextFloat() * (GameConfig.MAP_W - 30),
                12 + Random.nextFloat() * (GameConfig.MAP_H - 24),
            )
        }
        world.items.add(ItemEntity(i, d.kind, ipos, d.icon, d.label))
    }

    spawnWave(world, settings.monsterInitNpe, settings.monsterInitSoe) // 初始一波（默认 NPE 10 + SOE 5）
    pushLog(world, "对局开始：${players.size} 名玩家入场，怪物 NPE ${settings.monsterInitNpe} + SOE ${settings.monsterInitSoe}", "#e6e2f7")
    return world
}

/** 推进世界一帧（移植 updateMockWorld，mockWorld.ts:270-702，数值零改动） */
fun updateWorld(world: World, dt: Float, inputs: Map<String, Input>) {
    if (world.gameOver != null) return
    world.time += dt
    val t = world.time

    // ── 真人玩家：移动（加速/减速独立叠乘：⚡永久 +30%，SOE 减速至 45%）+ 攻击 + 表情 ──
    for (p in world.allPlayers()) {
        if (p.isBot || p.hp <= 0) continue
        val input = inputs[p.id] ?: continue
        val move = Vec2(input.dx, input.dy)

        val speedMul = (if (t < p.speedBuffUntil) 1.3f else 1f) * (if (t < p.slowUntil) 0.45f else 1f)
        p.speed = GameConfig.BASE_SPEED * speedMul
        p.pos.x = clamp(p.pos.x + move.x * p.speed * dt, p.radius, world.width - p.radius)
        p.pos.y = clamp(p.pos.y + move.y * p.speed * dt, p.radius, world.height - p.radius)
        resolveObstacles(p.pos, p.radius, world.obstacles)
        if (move.x != 0f || move.y != 0f) {
            p.face = normalize(move)
            p.squash = 0.92f
        }
        p.moving = move.x != 0f || move.y != 0f
        p.squash += (1 - p.squash) * min(1f, 10 * dt)
        // 朝向只跟随移动；瞄准只影响弹道，不转人物

        // ── 攻击：耗蓝生成子弹，弹道 = 瞄准方向（无瞄准则用朝向），攻速 0.6s - hasteBonus，下限 0.3s ──
        // （mock 用 world.attackCooldown 剩余秒数递减；此处用每实体绝对时刻 attackCd，语义等价）
        val attackCdDur = max(0.3f, GameConfig.ATTACK_CD - p.hasteBonus)
        val aim = if (input.aimX != null && input.aimY != null) Vec2(input.aimX, input.aimY) else null
        if (input.attack && (p.attackCd ?: 0f) <= t && p.mana >= GameConfig.ATTACK_MANA) {
            p.attackCd = t + attackCdDur
            p.mana -= GameConfig.ATTACK_MANA
            val rawDir = if (aim != null && (aim.x != 0f || aim.y != 0f)) aim else p.face
            val normDir = normalize(rawDir)
            val fireDir = if (normDir.x == 0f && normDir.y == 0f) Vec2(0f, -1f) else normDir
            // 叠加玩家在发射方向上的移速分量，解决移动射击时子弹相对角色"变慢/拖拽"的错觉
            val moveVelX = move.x * p.speed
            val moveVelY = move.y * p.speed
            val forwardProj = max(0f, moveVelX * fireDir.x + moveVelY * fireDir.y)
            val bulletSpeed = GameConfig.BULLET_SPEED + forwardProj

            world.projectiles.add(
                Projectile(
                    id = nextId.getAndIncrement(),
                    owner = p.id,
                    ownerName = p.name,
                    pos = Vec2(p.pos.x + fireDir.x * (p.radius + 0.2f), p.pos.y - 0.35f + fireDir.y * (p.radius + 0.2f)),
                    dir = fireDir,
                    speed = bulletSpeed,
                    life = (GameConfig.BASE_RANGE + p.rangeBonus) / bulletSpeed,
                ),
            )
        } else if (input.attack && (p.attackCd ?: 0f) <= t && p.mana < GameConfig.ATTACK_MANA && t - p.manaWarnAt > 1) {
            // 没蓝提示（1s 节流）
            p.manaWarnAt = t
            world.floats.add(FloatText(p.pos.copy(), "没子弹了！吃 ~ Flow 补子弹", "#55c8ff", 1f))
        }

        // ── 表情（engine.ts consumeEmote 同款：头顶 'GG Kotlin!' 飘字）──
        if (input.emote) {
            world.floats.add(FloatText(Vec2(p.pos.x, p.pos.y - 1.5f), "GG Kotlin!", "#a78bfa", 1.5f))
        }
    }

    // ── bot：简单 AI（向下一圈安全区靠拢 / 圈外回圈 / 见怪就打，照抄 mock NPC 逻辑）──
    val c0 = world.circle
    val home = c0.targetCenter ?: c0.center
    val homeR = c0.targetRadius ?: c0.radius
    for (npc in world.allPlayers()) {
        if (!npc.isBot || npc.hp <= 0) continue
        var dir = Vec2(0f, 0f)
        if (dist(npc.pos, c0.center) > c0.radius - 2 || dist(npc.pos, home) > homeR) {
            // 圈外或不在下一圈范围内：朝下一圈圆心靠拢
            dir = normalize(Vec2(home.x - npc.pos.x, home.y - npc.pos.y))
        } else {
            if (npc.aiTarget == null || t >= (npc.aiRetargetAt ?: 0f)) {
                val a = Random.nextFloat() * PI_F * 2
                val rr = Random.nextFloat() * max(4f, homeR * 0.7f)
                npc.aiTarget = Vec2(
                    clamp(home.x + cos(a) * rr, 2f, world.width - 2),
                    clamp(home.y + sin(a) * rr, 2f, world.height - 2),
                )
                npc.aiRetargetAt = t + 2 + Random.nextFloat() * 3
            }
            val at = npc.aiTarget!!
            if (dist(npc.pos, at) > 1) dir = normalize(Vec2(at.x - npc.pos.x, at.y - npc.pos.y))
        }
        npc.pos.x = clamp(npc.pos.x + dir.x * GameConfig.BASE_SPEED * dt, npc.radius, world.width - npc.radius)
        npc.pos.y = clamp(npc.pos.y + dir.y * GameConfig.BASE_SPEED * dt, npc.radius, world.height - npc.radius)
        resolveObstacles(npc.pos, npc.radius, world.obstacles)
        if (dir.x != 0f || dir.y != 0f) {
            npc.face = dir
            npc.squash = 0.92f
        }
        npc.moving = dir.x != 0f || dir.y != 0f
        npc.squash += (1 - npc.squash) * min(1f, 10 * dt)
        // 攻击 9 格内最近怪物（与真人同一规范：耗 1 发子弹，没子弹不开火）
        if ((npc.attackCd ?: 0f) <= t && npc.mana >= GameConfig.ATTACK_MANA) {
            var best: MonsterEntity? = null
            var bestD = 9f
            for (m in world.monsters) {
                val md = dist(m.pos, npc.pos)
                if (md < bestD) {
                    best = m
                    bestD = md
                }
            }
            if (best != null) {
                npc.attackCd = t + 1 + Random.nextFloat() * 0.5f
                npc.mana -= GameConfig.ATTACK_MANA
                val bdir = normalize(Vec2(best.pos.x - npc.pos.x, best.pos.y - npc.pos.y))
                npc.face = bdir
                world.projectiles.add(
                    Projectile(
                        id = nextId.getAndIncrement(),
                        owner = npc.id,
                        ownerName = npc.name,
                        pos = Vec2(npc.pos.x + bdir.x, npc.pos.y - 0.35f + bdir.y),
                        dir = bdir,
                        speed = GameConfig.BULLET_SPEED,
                        life = GameConfig.BASE_RANGE / GameConfig.BULLET_SPEED,
                    ),
                )
            }
        }
    }

    // ── 子弹：命中造成伤害 + 改变怪物移动方向 ──
    world.projectiles.removeAll { pr ->
        pr.pos.x += pr.dir.x * pr.speed * dt
        pr.pos.y += pr.dir.y * pr.speed * dt
        pr.life -= dt
        if (pr.life <= 0) return@removeAll true
        // 子弹被障碍物挡住（巨石/石柱挡子弹）
        if (hitsObstacle(pr.pos, 0.15f, world.obstacles)) return@removeAll true
        for (m in world.monsters) {
            if (dist(pr.pos, m.pos) < m.radius + 0.25f) {
                m.hp -= GameConfig.BULLET_DAMAGE
                m.dir = pr.dir.copy() // 被击退偏转
                // 积分：命中每发 +1；补刀（这一发打死）+2
                val shooter = world.allPlayers().firstOrNull { it.id == pr.owner }
                if (m.hp <= 0) {
                    val kindName = if (m.kind == MonsterKind.npe) "NPE" else "SOE"
                    if (shooter != null) {
                        shooter.score += 2f
                        world.floats.add(FloatText(shooter.pos.copy(), "+2 分", "#ffd166", 1f))
                        pushLog(world, "${shooter.name} 补刀 $kindName +2 分（累计 ${fmtScore(shooter.score)}）", "#ffd166")
                    }
                    pushFeed(world, "${pr.ownerName} 消灭了 $kindName", "#a78bfa")
                } else if (shooter != null) {
                    shooter.score += 1f
                }
                world.floats.add(FloatText(m.pos.copy(), "-25", "#e24462", 0.6f))
                return@removeAll true
            }
        }
        // PvP：子弹命中玩家/bot（不伤射击者，伤害为 settings.effectivePvpDamage，默认 10）
        // （照抄 mock 不对称判定：真人有 0.5s 受击保护，bot 无；friendlyFire=false 时互伤为 0，子弹直接穿过）
        val pvpDamage = world.settings.effectivePvpDamage
        if (pvpDamage > 0f) {
            for (e in world.allPlayers()) {
                if (e.hp <= 0 || pr.owner == e.id || dist(pr.pos, e.pos) >= e.radius + 0.25f) continue
                if (!e.isBot && t < e.invincibleUntil) continue
                if (e.defense > 0) {
                    e.defense -= 1f
                    world.floats.add(FloatText(e.pos.copy(), "🛡 护盾抵挡!", "#7fb8ff", 0.8f))
                } else {
                    e.hp -= pvpDamage
                    e.lastHitBy = pr.ownerName
                    world.floats.add(FloatText(e.pos.copy(), "-${pvpDamage.toInt()}", "#ff5a6e", 0.8f))
                }
                e.pos.x = clamp(e.pos.x + pr.dir.x * 0.8f, e.radius, world.width - e.radius)
                e.pos.y = clamp(e.pos.y + pr.dir.y * 0.8f, e.radius, world.height - e.radius)
                if (!e.isBot) e.invincibleUntil = t + 0.5f
                e.squash = 0.7f
                return@removeAll true
            }
        }
        false
    }
    // 怪物死亡：飘字（积分在子弹命中处结算），随后由 topUpMonsters 补足数量
    world.monsters.removeAll { m ->
        if (m.hp > 0) return@removeAll false
        world.floats.add(FloatText(m.pos.copy(), if (m.kind == MonsterKind.npe) "NPE 消灭!" else "SOE 消灭!", "#a78bfa", 1f))
        true
    }
    // 怪物死亡/飞离后立刻补上来（目标 = 初始 + 已完成轮数 × 每波）
    topUpMonsters(world)

    // 真人无敌快照（照抄 mock：怪物碰撞 / GC / 轰炸统一用进入怪物循环前计算的 invincible）
    val invincibleIds = world.allPlayers()
        .filter { !it.isBot && t < it.invincibleUntil }
        .mapTo(HashSet()) { it.id }

    // ── 怪物：直线穿越 + 碰撞伤害 ──
    for (m in world.monsters) {
        val speed = if (m.kind == MonsterKind.npe) 4f else 2f
        m.pos.x += m.dir.x * speed * dt
        m.pos.y += m.dir.y * speed * dt

        // 怪物撞墙：朝当前安全圈内随机一点反弹（保证弹回有效区域）
        if (m.pos.x < m.radius || m.pos.x > world.width - m.radius || m.pos.y < m.radius || m.pos.y > world.height - m.radius) {
            m.pos.x = clamp(m.pos.x, m.radius, world.width - m.radius)
            m.pos.y = clamp(m.pos.y, m.radius, world.height - m.radius)
            val c = world.circle
            val a = Random.nextFloat() * PI_F * 2
            val rr = Random.nextFloat() * max(2f, c.radius * 0.8f)
            val target = Vec2(c.center.x + cos(a) * rr, c.center.y + sin(a) * rr)
            m.dir = normalize(Vec2(target.x - m.pos.x, target.y - m.pos.y))
        }
        bounceMonsterOffObstacles(m, world.obstacles) // 撞障碍物 = 撞墙反弹（不用 resolveObstacles：它推出后反弹检测会落空）

        for (e in world.allPlayers()) {
            if (e.hp <= 0) continue
            val de = dist(m.pos, e.pos)
            // SOE 减速光环（bot 移速恒定 BASE_SPEED 不受影响，与 mock NPC 一致）
            if (m.kind == MonsterKind.soe && de < GameConfig.SOE_AURA && !e.isBot) {
                e.slowUntil = t + 0.5f
            }
            // 碰撞伤害（真人：护盾 > 扣血，1s 受击保护，用无敌快照；bot：无护盾判定，1s 受击保护）
            if (e.isBot) {
                if (de < e.radius + m.radius && t >= e.invincibleUntil) {
                    e.hp -= world.settings.monsterTouchDamage
                    e.lastHitBy = "怪物"
                    val away = normalize(Vec2(e.pos.x - m.pos.x, e.pos.y - m.pos.y))
                    e.pos.x = clamp(e.pos.x + away.x * 1.5f, e.radius, world.width - e.radius)
                    e.pos.y = clamp(e.pos.y + away.y * 1.5f, e.radius, world.height - e.radius)
                    e.invincibleUntil = t + 1
                    e.squash = 0.6f
                }
            } else if (e.id !in invincibleIds && de < e.radius + m.radius && t >= e.invincibleUntil) {
                if (e.defense > 0) {
                    e.defense -= 1f
                    world.floats.add(FloatText(e.pos.copy(), "🛡 护盾抵挡!", "#7fb8ff", 0.8f))
                } else {
                    e.hp -= world.settings.monsterTouchDamage
                    e.lastHitBy = "怪物"
                    world.floats.add(FloatText(e.pos.copy(), "-${world.settings.monsterTouchDamage.toInt()}", "#ff5a6e", 0.8f))
                }
                val away = normalize(Vec2(e.pos.x - m.pos.x, e.pos.y - m.pos.y))
                e.pos.x = clamp(e.pos.x + away.x * 1.5f, e.radius, world.width - e.radius)
                e.pos.y = clamp(e.pos.y + away.y * 1.5f, e.radius, world.height - e.radius)
                e.invincibleUntil = t + 1
                e.squash = 0.6f
            }
        }
    }
    // 穿出地图的怪物直接离场（保持"穿过"语义，不补充）
    world.monsters.removeAll { m ->
        !(m.pos.x > -3 && m.pos.x < world.width + 3 && m.pos.y > -3 && m.pos.y < world.height + 3)
    }

    // ── 道具过期（刷出 ITEM_TTL 秒未拾取即消失，防毒区道具烂掉）──
    world.items.removeAll { t - it.spawnAt > GameConfig.ITEM_TTL }

    // ── 道具拾取（真人与 bot 同一规范：先到先得，拾取判定一致）──
    world.items.removeAll { item ->
        val p = world.allPlayers().firstOrNull { it.hp > 0 && dist(item.pos, it.pos) <= GameConfig.PICKUP_RADIUS }
            ?: return@removeAll false
        pushLog(world, "${p.name} 拾取 ${item.icon} ${item.label}", "#7fb8ff")
        when (item.kind) {
            ItemKind.shield -> {
                p.defense += 1f
                world.floats.add(FloatText(p.pos.copy(), "?: +1 防御", "#7fb8ff", 1f))
            }
            ItemKind.`val` -> {
                p.invincibleUntil = t + 3
                world.floats.add(FloatText(p.pos.copy(), "val 无敌 3s!", "#2ecc71", 1f))
            }
            ItemKind.coroutines -> {
                p.speedBuffUntil = Float.POSITIVE_INFINITY // 永久加速
                world.floats.add(FloatText(p.pos.copy(), "⚡ 永久加速!", "#f1c40f", 1f))
            }
            ItemKind.flow -> {
                p.mana = min(p.maxMana, p.mana + 12)
                world.floats.add(FloatText(p.pos.copy(), "~ +12 子弹", "#0095d5", 1f))
            }
            ItemKind.range -> {
                p.rangeBonus += 3f
                world.floats.add(FloatText(p.pos.copy(), "!! 射程 +3", "#e24462", 1f))
            }
            ItemKind.haste -> {
                p.hasteBonus = min(0.6f, p.hasteBonus + 0.1f)
                world.floats.add(FloatText(p.pos.copy(), "⏩ 攻速提升!", "#f1c40f", 1f))
            }
            ItemKind.heal -> {
                p.hp = min(p.maxHp, p.hp + 5)
                world.floats.add(FloatText(p.pos.copy(), "var +5 HP", "#2ecc71", 1f))
            }
        }
        true
    }
    // 道具补充（上限 settings.itemCount，种类按 settings.itemWeights 加权；
    // 只刷安全区内——圈外毒区道具捡不到，过期道具由 TTL 清理轮换）
    if (world.items.size < world.settings.itemCount && floor(t).toInt() % GameConfig.ITEM_RESPAWN == 0) {
        val d = pickItemDef(world.settings)
        val c = world.circle
        val ang = Random.nextFloat() * PI_F * 2
        val r = Random.nextFloat() * max(4f, min(c.radius, 40f) - 4)
        val ipos = Vec2(
            clamp(c.center.x + cos(ang) * r, 2f, world.width - 2),
            clamp(c.center.y + sin(ang) * r, 2f, world.height - 2),
        )
        // 别刷在障碍里，本轮跳过（照抄 mock：此处 return 会跳过本 tick 剩余逻辑，保留原行为）
        if (hitsObstacle(ipos, 0.6f, world.obstacles)) return
        world.items.add(ItemEntity(nextId.getAndIncrement(), d.kind, ipos, d.icon, d.label, spawnAt = t))
    }

    // ── 缩圈：收缩时长/冷却/轮数由 settings 决定（默认 10s + 45s × 6 轮）；最终圈塌缩到中心点决生死 ──
    val c = world.circle
    if (c.phase == CirclePhase.idle) {
        val idleDur = if (c.stage == 0) world.settings.firstIdle else if (c.targetRadius == 0f) GameConfig.FINAL_IDLE else world.settings.shrinkCooldown
        if (c.targetRadius != null && t - c.phaseStart >= idleDur) {
            c.phase = CirclePhase.shrinking
            c.phaseStart = t
            c.shrinkFromRadius = c.radius
            c.shrinkFromCenter = c.center.copy()
            pushLog(
                world,
                if (c.targetRadius == 0f) "最终圈开始塌缩，活到最后！" else "第 ${c.stage + 1} 轮缩圈开始",
                "#ffb340",
            )
        }
    } else {
        val dur = if (c.targetRadius == 0f) GameConfig.FINAL_SHRINK_TIME else world.settings.shrinkTime
        val k = min(1f, (t - c.phaseStart) / dur)
        c.radius = c.shrinkFromRadius + (c.targetRadius!! - c.shrinkFromRadius) * k
        c.center.x = c.shrinkFromCenter.x + (c.targetCenter!!.x - c.shrinkFromCenter.x) * k
        c.center.y = c.shrinkFromCenter.y + (c.targetCenter!!.y - c.shrinkFromCenter.y) * k
        if (k >= 1f) {
            if (c.targetRadius == 0f) {
                // 最终圈塌缩完成：仍存活 = 最后赢家
                pushLog(world, "最终圈塌缩完成，对局结束", "#2ecc71")
                applySurvivorBonus(world)
                world.gameOver = GameOverKind.survived
                return
            }
            c.stage += 1
            pushLog(world, "第 ${c.stage} 轮缩圈完成，安全区半径 ${c.targetRadius?.toInt() ?: 0}", "#ffb340")
            if (c.stage < world.settings.shrinkTargets.size) {
                c.targetRadius = world.settings.shrinkTargets[c.stage]
                c.targetCenter = nextCenter(c.center, c.radius, c.targetRadius!!, world.width, world.height)
            } else {
                // 全部轮次完成：进入最终塌缩阶段（缩到中心点）
                c.targetRadius = 0f
                c.targetCenter = c.center.copy()
            }
            c.phase = CirclePhase.idle
            c.phaseStart = t
            topUpMonsters(world) // 轮次推进 → 怪物目标数量上升，立刻补足
        }
    }

    // ── GC 掉血（真人 1s 宽限 + 无敌免疫；bot 直接掉）──
    val gcRate = if (c.radius > 19) 6f else if (c.radius > 10) 10f else 20f
    world.playerOutside = dist(world.player.pos, c.center) > c.radius
    for (e in world.allPlayers()) {
        if (e.hp <= 0) continue
        val outside = dist(e.pos, c.center) > c.radius
        if (e.isBot) {
            if (outside) {
                e.hp -= gcRate * dt
                e.lastHitBy = "GC"
            }
        } else if (outside && e.id !in invincibleIds) {
            if (e.outsideSince == null) e.outsideSince = t
            if (t - e.outsideSince!! > 1) {
                e.hp -= gcRate * dt
                // mock 本地玩家 GC 掉血漏记 lastHitBy（死亡播报 '被 GC 回收' 分支成死代码），此处补上
                e.lastHitBy = "GC"
            }
        } else {
            e.outsideSince = null
        }
    }

    // ── 随机轰炸区：预警 2.5s → 爆炸（圈内随机 3–5 个）。bombsEnabled=false 不再生成新轰炸区；
    //    场上遗留炸弹照常走爆炸/动画结算 ──
    if (world.settings.bombsEnabled && t >= world.nextBombAt) {
        val n = 3 + (Random.nextFloat() * 3).toInt()
        pushLog(world, "出现 $n 个轰炸区（OOM 预警）", "#ff8a5c")
        repeat(n) {
            val bc = world.circle
            val a = Random.nextFloat() * PI_F * 2
            val rr = Random.nextFloat() * max(4f, min(bc.radius, 30f) - 2)
            world.bombs.add(
                BombZone(
                    id = nextId.getAndIncrement(),
                    pos = Vec2(
                        clamp(bc.center.x + cos(a) * rr, 4f, world.width - 4),
                        clamp(bc.center.y + sin(a) * rr, 4f, world.height - 4),
                    ),
                    radius = 3 + Random.nextFloat() * 2,
                    explodeAt = t + 2.5f,
                    animUntil = 0f,
                ),
            )
        }
        world.nextBombAt = t + 12 + Random.nextFloat() * 6
    }
    for (b in world.bombs) {
        if (b.animUntil == 0f && t >= b.explodeAt) {
            b.animUntil = t + 0.6f
            // OOM 爆炸：漫天 OutOfMemory 文本粒子
            for (i in 0 until 8) {
                val a = Random.nextFloat() * PI_F * 2
                val rr = Random.nextFloat() * b.radius * 0.8f
                world.floats.add(
                    FloatText(
                        Vec2(b.pos.x + cos(a) * rr, b.pos.y + sin(a) * rr),
                        OOM_TEXTS[(Random.nextFloat() * OOM_TEXTS.size).toInt()],
                        OOM_COLORS[i % 3],
                        1 + Random.nextFloat() * 0.5f,
                    ),
                )
            }
            // 真人判定（无敌 > 护盾 > 扣 30 血）；bot 判定（直接 -30）
            for (e in world.allPlayers()) {
                if (e.hp <= 0 || dist(b.pos, e.pos) >= b.radius + e.radius) continue
                if (e.isBot) {
                    e.hp -= 30f
                    e.lastHitBy = "轰炸区"
                } else if (e.id !in invincibleIds) {
                    if (e.defense > 0) {
                        e.defense -= 1f
                        world.floats.add(FloatText(e.pos.copy(), "🛡 护盾抵挡轰炸!", "#7fb8ff", 0.8f))
                    } else {
                        e.hp -= 30f
                        e.invincibleUntil = t + 1
                        e.squash = 0.6f
                        world.floats.add(FloatText(e.pos.copy(), "-30", "#ff5a6e", 0.8f))
                    }
                }
            }
            // 怪物判定
            for (m in world.monsters) {
                if (dist(b.pos, m.pos) < b.radius + m.radius) m.hp -= 50f
            }
            world.floats.add(FloatText(b.pos.copy(), "💥", "#ffa040", 0.6f))
        }
    }
    world.bombs.removeAll { b -> !(b.animUntil == 0f || t < b.animUntil) }

    // 实体死亡清理 + 击杀播报（mock 分 NPC/玩家两路文案，联机版统一用名称版）
    world.npcs.removeAll { e ->
        if (e.hp > 0) return@removeAll false
        reportDeath(world, e)
        true
    }
    // player 槽实体死亡：有存活者则由 npcs 队首递补（id 不变，快照无尸体）；无存活者本 tick 即结算
    if (world.player.hp <= 0) {
        reportDeath(world, world.player)
        if (world.npcs.isNotEmpty()) {
            world.player = world.npcs.removeAt(0)
        }
    }

    // ── 结算：最后存活（其余全部淘汰）。单人 + 0bot 调试局（无淘汰记录）不触发，等最终圈或死亡 ──
    val alive = (if (world.player.hp > 0) 1 else 0) + world.npcs.size
    if (alive <= 1 && world.placements.isNotEmpty() && world.gameOver == null) {
        val winner = world.allPlayers().firstOrNull { it.hp > 0 }
        if (winner != null) pushFeed(world, "全场只剩 ${winner.name}，活到最后！", "#2ecc71")
        pushLog(world, "对局结束${winner?.let { "，${it.name} 活到最后" } ?: ""}", "#2ecc71")
        applySurvivorBonus(world)
        world.gameOver = GameOverKind.survived
    }

    world.floats.removeAll { f ->
        f.life -= dt
        f.life <= 0
    }
}

/** 淘汰播报 + placements 记录（先死在前；积分制：记录死亡时刻积分，排名由结算侧按积分降序组装） */
private fun reportDeath(world: World, e: PlayerEntity) {
    world.placements.add(Placement(e.name, e.color, e.score))
    val text = when (val by = e.lastHitBy) {
        "GC" -> "${e.name} 被 GC 回收了"
        "轰炸区" -> "${e.name} 被轰炸区炸飞了"
        "怪物" -> "${e.name} 被怪物撞死了"
        else -> if (by == null) "${e.name} 被淘汰了" else "$by 击杀了 ${e.name}"
    }
    pushFeed(world, text, "#9a93b8")
    pushLog(world, "$text（积分 ${fmtScore(e.score)}）")
    world.floats.add(FloatText(e.pos.copy(), "${e.name} 倒下了", "#9a93b8", 1.2f))
}

/** 结算存活奖励：存活者血量 ×10% 计入积分（两处 gameOver 入口共用，仅调用一次） */
private fun applySurvivorBonus(world: World) {
    for (e in world.allPlayers()) {
        if (e.hp <= 0) continue
        val bonus = e.hp * 0.1f
        e.score += bonus
        pushLog(world, "${e.name} 存活结算：血量 ${ceil(e.hp).toInt()} ×10% = +${fmtScore(bonus)} 分（总分 ${fmtScore(e.score)}）", "#2ecc71")
    }
}
