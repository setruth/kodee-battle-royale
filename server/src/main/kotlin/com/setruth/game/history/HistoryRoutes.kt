package com.setruth.game.history

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Route.historyRoutes() {
    route("/history") {
        // D21：我的历史（最近 N 条）
        get("/me") {
            val uid = call.principal<JWTPrincipal>()!!.payload.getClaim("uid").asLong()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
            val items = MatchRepository.myHistory(uid, limit)
            call.respond(buildJsonArray {
                items.forEach { i ->
                    add(buildJsonObject {
                        put("matchId", i.matchId)
                        put("startedAt", i.startedAt.toString())
                        put("durationSec", i.durationSec)
                        put("playerCount", i.playerCount)
                        put("myRank", i.myRank)
                    })
                }
            })
        }
        // 对局详情：result 原样返回（JSON string，服务端不解析不重序列化）
        get("/{matchId}") {
            val matchId = call.parameters["matchId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, com.setruth.game.auth.ErrorRes("matchId 非法"))
            val detail = MatchRepository.detail(matchId)
                ?: return@get call.respond(HttpStatusCode.NotFound, com.setruth.game.auth.ErrorRes("对局不存在"))
            call.respond(buildJsonObject {
                put("startedAt", detail.startedAt.toString())
                put("durationSec", detail.durationSec)
                put("result", detail.result)
                put("settings", detail.settings)
            })
        }
    }
}
