package com.setruth.game.net

import com.setruth.game.auth.verifyWsToken
import com.setruth.game.room.RoomManager
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WsRoutes")

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 15_000
        maxFrameSize = Long.MAX_VALUE
        masking = false
        extensions {
            install(WebSocketDeflateExtension)
        }
    }
    // DC 收到的输入与 WS 降级输入走同一管道
    PeerManager.onInputMessage = { userId, text -> dispatch(userId, text) }

    routing {
        webSocket("/ws") {
            val jwt = verifyWsToken(call.request.queryParameters["token"])
            if (jwt == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }
            val userId = jwt.getClaim("uid").asLong()
            RoomManager.attachSession(userId, this)
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> dispatch(userId, frame.readText())
                        else -> {}
                    }
                }
            } finally {
                RoomManager.detachSession(userId, this) // D12：断线不踢出房间
            }
        }
    }
}

private fun dispatch(userId: Long, text: String) {
    try {
        val obj = Json.parseToJsonElement(text).jsonObject
        when (obj["t"]?.jsonPrimitive?.content) {
            "in" -> {
                val d = obj["d"]?.jsonArray ?: return
                val aim = obj["aim"]?.takeIf { it !is JsonNull }?.jsonArray
                RoomManager.handleInput(
                    userId,
                    d[0].jsonPrimitive.int / 100f,
                    d[1].jsonPrimitive.int / 100f,
                    obj["a"]?.jsonPrimitive?.booleanOrNull == true,
                    aim?.get(0)?.jsonPrimitive?.int?.div(100f),
                    aim?.get(1)?.jsonPrimitive?.int?.div(100f),
                    obj["e"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
            "rtcAnswer" -> obj["sdp"]?.jsonPrimitive?.content?.let { PeerManager.handleAnswer(userId, it) }
            "rtcCand" -> obj["cand"]?.jsonObject?.let { PeerManager.addCandidate(userId, it) }
            "rtcFail" -> PeerManager.markFallback(userId)
            "ping" -> PeerManager.pong(userId, obj["ts"])
        }
    } catch (e: Exception) {
        log.warn("WS 消息解析失败 user=$userId: ${e.message}")
    }
}
