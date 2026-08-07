package com.setruth.game.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

/**
 * 开发期跨域：vite dev(:5173) → ktor(:8080)。
 * 认证走 Authorization header（非 cookie），不需要 allowCredentials；
 * 生产同域部署时浏览器不做 CORS 检查，此配置无副作用。
 */
fun Application.configureCORS() {
    install(CORS) {
        anyHost()
        HttpMethod.DefaultMethods.forEach { allowMethod(it) }
        allowHeaders { true }
        allowNonSimpleContentTypes = true
        maxAgeInSeconds = 86400
    }
}
