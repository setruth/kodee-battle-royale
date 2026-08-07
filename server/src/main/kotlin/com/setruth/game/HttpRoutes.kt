package com.setruth.game

import com.setruth.game.auth.authRoutes
import com.setruth.game.history.historyRoutes
import com.setruth.game.room.roomRoutes
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** 所有 HTTP 接口统一挂在 /api 前缀下；各路由文件只声明自己的子路径 */
fun Application.configureHttpRoutes() {
    routing {
        route("/api") {
            get("/health") { call.respond(mapOf("status" to "ok")) }
            authRoutes()
            authenticate("auth-jwt") {
                roomRoutes()
                historyRoutes()
            }
        }
    }
}
