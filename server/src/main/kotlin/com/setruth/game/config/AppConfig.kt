package com.setruth.game.config

import com.setruth.game.game.GameSettings
import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("AppConfig")

data class AppConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val rtcMinPort: Int,
    val rtcMaxPort: Int,
    /** 游戏规则默认值（TTK 等写死层）：yaml app.game.* 可调，房间创建/修改时再覆盖 */
    val game: GameSettings,
) {
    companion object {
        private const val DEFAULT_SECRET = "dev-secret-change-me"

        /** 从 application.yaml 的 app.* 节读取（连接自己的数据库就改 yaml 里这三行） */
        fun from(config: ApplicationConfig): AppConfig {
            fun opt(path: String): String? = config.propertyOrNull(path)?.getString()
            val secret = opt("app.jwt.secret") ?: DEFAULT_SECRET.also {
                log.warn("application.yaml 未配置 app.jwt.secret，使用开发默认值 dev-secret-change-me，生产环境必须配置！")
            }
            return AppConfig(
                dbUrl = opt("app.db.url") ?: "jdbc:postgresql://localhost:5432/npe_game",
                dbUser = opt("app.db.user") ?: "npe",
                dbPassword = opt("app.db.password") ?: "npe",
                jwtSecret = secret,
                rtcMinPort = opt("app.webrtc.minPort")?.toIntOrNull() ?: 50000,
                rtcMaxPort = opt("app.webrtc.maxPort")?.toIntOrNull() ?: 50100,
                game = gameSettings(config),
            )
        }

        /** 读 app.game.* 平铺标量，缺省回退 GameSettings 默认值（shrinkTargets/itemWeights 仅代码默认） */
        private fun gameSettings(config: ApplicationConfig): GameSettings {
            val d = GameSettings()
            fun f(path: String): Float? = config.propertyOrNull("app.game.$path")?.getString()?.toFloatOrNull()
            fun i(path: String): Int? = config.propertyOrNull("app.game.$path")?.getString()?.toIntOrNull()
            fun b(path: String): Boolean? = config.propertyOrNull("app.game.$path")?.getString()?.toBooleanStrictOrNull()
            return d.copy(
                shrinkTime = f("shrinkTime") ?: d.shrinkTime,
                shrinkCooldown = f("shrinkCooldown") ?: d.shrinkCooldown,
                firstIdle = f("firstIdle") ?: d.firstIdle,
                friendlyFire = b("friendlyFire") ?: d.friendlyFire,
                monsterTouchDamage = f("monsterTouchDamage") ?: d.monsterTouchDamage,
                monsterInitNpe = i("monsterInitNpe") ?: d.monsterInitNpe,
                monsterInitSoe = i("monsterInitSoe") ?: d.monsterInitSoe,
                monsterWaveNpe = i("monsterWaveNpe") ?: d.monsterWaveNpe,
                monsterWaveSoe = i("monsterWaveSoe") ?: d.monsterWaveSoe,
                bombsEnabled = b("bombsEnabled") ?: d.bombsEnabled,
                itemCount = i("itemCount") ?: d.itemCount,
            )
        }
    }
}

/** 全局持有：由最早加载的模块（configureAuth / configureDb）初始化一次 */
object AppConfigHolder {
    private var instance: AppConfig? = null

    fun init(config: ApplicationConfig) {
        if (instance == null) instance = AppConfig.from(config)
    }

    val current: AppConfig
        get() = instance ?: error("AppConfig 未初始化（模块未加载）")
}

/** 各模块共享入口 */
val appConfig: AppConfig get() = AppConfigHolder.current
