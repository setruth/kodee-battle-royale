package com.setruth.game.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.v1.jdbc.Database

fun Application.configureDb() {
    AppConfigHolder.init(environment.config)
    val cfg = appConfig
    val ds = HikariDataSource(HikariConfig().apply {
        jdbcUrl = cfg.dbUrl
        username = cfg.dbUser
        password = cfg.dbPassword
        maximumPoolSize = 8
    })

    // 自动加载并执行 db/init.sql 初始化表结构
    val initSql = object {}.javaClass.getResourceAsStream("/db/init.sql")?.bufferedReader()?.use { it.readText() }
    if (!initSql.isNullOrBlank()) {
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(initSql)
            }
        }
    }

    Database.connect(ds)
    environment.monitor.subscribe(ApplicationStopped) { ds.close() }
}
