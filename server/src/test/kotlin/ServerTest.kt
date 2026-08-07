package com.setruth.game

import com.setruth.game.auth.configureAuth
import com.setruth.game.plugins.configureSerialization
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `health endpoint returns ok`() = testApplication {
        application {
            configureSerialization()
            configureAuth()
            configureHttpRoutes()
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
    }
}
