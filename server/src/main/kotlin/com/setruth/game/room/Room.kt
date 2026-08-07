package com.setruth.game.room

import com.setruth.game.game.GameSettings
import com.setruth.game.game.Input
import com.setruth.game.game.World
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.Job
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class Role { PLAYER, SPECTATOR }
enum class RoomState { WAITING, COUNTDOWN, PLAYING, RESULT }

data class Member(
    val userId: Long,
    val username: String,
    val color: String,
    var role: Role,
    var ready: Boolean = false,
    var session: WebSocketSession? = null,
)

/** 开局时建立的实体档案：entityId → (isBot, userId?, username) */
data class EntityMeta(val isBot: Boolean, val userId: Long?, val username: String)

class Room(
    val code: String,
    var hostId: Long,
    val members: MutableMap<Long, Member> = LinkedHashMap(),
    var bots: Int = 0,
    var state: RoomState = RoomState.WAITING,
    var world: World? = null,
    var countdownJob: Job? = null,
    var tickJob: Job? = null,
) {
    /** 房间规则配置：创建时确定，WAITING 中房主可改（全员广播实时可见） */
    var settings: GameSettings = GameSettings()
    /** 输入缓冲（D 步 WS 降级与 F 步 DC 共用同一管道） */
    val inputs = ConcurrentHashMap<String, Input>()
    var entityMeta: Map<String, EntityMeta> = emptyMap()
    var startedAt: Instant? = null
    var lastFeedId: Long = 0
    var lastLogId: Long = 0
}

class RoomError(val status: io.ktor.http.HttpStatusCode, override val message: String) : RuntimeException(message)
