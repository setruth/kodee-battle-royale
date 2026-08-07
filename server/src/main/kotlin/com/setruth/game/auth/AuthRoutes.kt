package com.setruth.game.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.setruth.game.config.appConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

@Serializable
data class ErrorRes(val error: String)

@Serializable
data class AuthReq(val username: String, val password: String)

@Serializable
data class UserDto(val userId: Long, val username: String, val name: String)

@Serializable
data class AuthRes(val token: String, val user: UserDto, val created: Boolean = false)

fun issueToken(userId: Long, username: String): String =
    JWT.create()
        .withIssuer("npe-game")
        .withSubject(username)
        .withClaim("uid", userId)
        .withExpiresAt(Date.from(Instant.now().plus(7, ChronoUnit.DAYS)))
        .sign(Algorithm.HMAC256(appConfig.jwtSecret))

fun Route.authRoutes() {
    route("/auth") {
        // 注册登录一体：账号存在 → 校验密码登录；不存在 → 自动注册并登录
        post("/enter") {
            val req = call.receive<AuthReq>()
            val username = normalizeUsername(req.username)
            val existing = UserRepository.findByCi(username.lowercase())
            if (existing != null) {
                if (existing.password != md5Hex(req.password)) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorRes("密码错误"))
                }
                return@post call.respond(
                    AuthRes(issueToken(existing.userId, existing.username), UserDto(existing.userId, existing.username, existing.name))
                )
            }
            // 账号不存在：走注册校验后自动开户
            validateUsername(req.username)?.let { return@post call.respond(HttpStatusCode.BadRequest, ErrorRes(it)) }
            validatePassword(req.password)?.let { return@post call.respond(HttpStatusCode.BadRequest, ErrorRes(it)) }
            val created = UserRepository.insert(username, md5Hex(req.password))
            if (created == null) {
                // 并发撞名：按登录再试一次
                val again = UserRepository.findByCi(username.lowercase())
                    ?: return@post call.respond(HttpStatusCode.Conflict, ErrorRes("用户名已被注册"))
                if (again.password != md5Hex(req.password)) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorRes("密码错误"))
                }
                return@post call.respond(
                    AuthRes(issueToken(again.userId, again.username), UserDto(again.userId, again.username, again.name))
                )
            }
            call.respond(AuthRes(issueToken(created.userId, created.username), UserDto(created.userId, created.username, created.name), created = true))
        }
        authenticate("auth-jwt") {
            get("/me") {
                val p = call.principal<JWTPrincipal>()!!
                val user = UserRepository.findById(p.payload.getClaim("uid").asLong())
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorRes("用户不存在"))
                call.respond(UserDto(user.userId, user.username, user.name))
            }
        }
    }
}
