package com.setruth.game

import io.ktor.server.engine.*
import io.ktor.server.application.*
import java.io.File

fun main(args: Array<String>) {
    val configFile = File("src/main/resources/application.yaml")
    val defaultConfigFile = File("src/main/resources/application.default.yaml")
    if (!configFile.exists() && defaultConfigFile.exists()) {
        try {
            defaultConfigFile.copyTo(configFile, overwrite = false)
            println("[Server Config] Created src/main/resources/application.yaml from default template.")
        } catch (e: Exception) {
            println("[Server Config] Note: application.yaml not found, using default configuration.")
        }
    }
    io.ktor.server.netty.EngineMain.main(args)
}
