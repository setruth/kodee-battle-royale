package com.setruth.game.room

import com.setruth.game.auth.ErrorRes
import com.setruth.game.config.appConfig
import com.setruth.game.game.GameSettings
import com.setruth.game.game.clamped
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@kotlinx.serialization.Serializable
data class CreateRoomReq(val color: String, val role: String, val settings: GameSettings? = null)
@kotlinx.serialization.Serializable
data class JoinRoomReq(val roomCode: String, val color: String, val role: String)
@kotlinx.serialization.Serializable
data class BotsReq(val count: Int)
@kotlinx.serialization.Serializable
data class KickReq(val userId: Long)
@kotlinx.serialization.Serializable
data class ReadyReq(val ready: Boolean)
@kotlinx.serialization.Serializable
data class RoleReq(val role: String)

private fun parseRole(s: String): Role = when (s.lowercase()) {
    "player" -> Role.PLAYER
    "spectator" -> Role.SPECTATOR
    else -> throw RoomError(HttpStatusCode.BadRequest, "role 只能是 player 或 spectator")
}

private fun ApplicationCall.uid(): Long = principal<JWTPrincipal>()!!.payload.getClaim("uid").asLong()
private fun ApplicationCall.uname(): String = principal<JWTPrincipal>()!!.payload.subject

private suspend fun ApplicationCall.guard(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: RoomError) {
        respond(e.status, ErrorRes(e.message ?: "操作失败"))
    }
}

fun Route.roomRoutes() {
    route("/rooms") {
        post {
            call.guard {
                val req = call.receive<CreateRoomReq>()
                val settings = (req.settings ?: appConfig.game).clamped()
                val room = RoomManager.create(call.uid(), call.uname(), req.color, parseRole(req.role), settings)
                call.respond(buildJsonObject {
                    put("roomCode", room.code); put("room", RoomManager.buildRoomPayload(room))
                })
            }
        }
        post("/join") {
            call.guard {
                val req = call.receive<JoinRoomReq>()
                val room = RoomManager.join(call.uid(), call.uname(), req.roomCode, req.color, parseRole(req.role))
                call.respond(buildJsonObject {
                    put("roomCode", room.code); put("room", RoomManager.buildRoomPayload(room))
                })
            }
        }
        post("/leave") {
            call.guard { RoomManager.leave(call.uid()); call.respond(HttpStatusCode.OK) }
        }
        post("/bots") {
            call.guard { RoomManager.setBots(call.uid(), call.receive<BotsReq>().count); call.respond(HttpStatusCode.OK) }
        }
        post("/settings") {
            call.guard {
                RoomManager.updateSettings(call.uid(), call.receive<GameSettings>().clamped())
                call.respond(HttpStatusCode.OK)
            }
        }
        post("/kick") {
            call.guard { RoomManager.kick(call.uid(), call.receive<KickReq>().userId); call.respond(HttpStatusCode.OK) }
        }
        post("/start") {
            call.guard { RoomManager.start(call.uid()); call.respond(HttpStatusCode.OK) }
        }
        post("/again") {
            call.guard { RoomManager.again(call.uid()); call.respond(HttpStatusCode.OK) }
        }
        post("/close") {
            call.guard { RoomManager.close(call.uid()); call.respond(HttpStatusCode.OK) }
        }
        get("/current") {
            call.guard {
                val room = RoomManager.currentRoom(call.uid())
                    ?: throw RoomError(HttpStatusCode.NotFound, "不在任何房间")
                call.respond(buildJsonObject {
                    put("roomCode", room.code)
                    put("room", RoomManager.buildRoomPayload(room))
                    put("state", room.state.name.lowercase())
                })
            }
        }
        post("/ready") {
            call.guard { RoomManager.setReady(call.uid(), call.receive<ReadyReq>().ready); call.respond(HttpStatusCode.OK) }
        }
        post("/role") {
            call.guard { RoomManager.setRole(call.uid(), parseRole(call.receive<RoleReq>().role)); call.respond(HttpStatusCode.OK) }
        }
    }
}
