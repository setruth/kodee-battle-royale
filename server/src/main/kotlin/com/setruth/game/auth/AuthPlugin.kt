package com.setruth.game.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.setruth.game.config.AppConfigHolder
import com.setruth.game.config.appConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureAuth() {
    AppConfigHolder.init(environment.config)
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "npe-game"
            verifier(
                JWT.require(Algorithm.HMAC256(appConfig.jwtSecret))
                    .withIssuer("npe-game")
                    .build()
            )
            validate { cred ->
                if (cred.payload.getClaim("uid").asLong() != null) JWTPrincipal(cred.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorRes("未登录或登录已过期"))
            }
        }
    }
}

/** WS 握手手动验 token（浏览器 WebSocket 不能设 Authorization header，走 ?token=） */
fun verifyWsToken(token: String?): DecodedJWT? {
    if (token.isNullOrBlank()) return null
    return try {
        JWT.require(Algorithm.HMAC256(appConfig.jwtSecret))
            .withIssuer("npe-game")
            .build()
            .verify(token)
    } catch (e: Exception) {
        null
    }
}
