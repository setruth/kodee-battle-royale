package com.setruth.game

import com.setruth.game.auth.validatePassword
import com.setruth.game.auth.validateUsername
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ValidationTest {

    @Test
    fun `username rules D3`() {
        // 保留字（忽略大小写）
        assertNotNull(validateUsername("admin"))
        assertNotNull(validateUsername("Admin"))
        assertNotNull(validateUsername("ROOT"))
        assertNotNull(validateUsername("null"))
        // 非法字符 / 长度
        assertNotNull(validateUsername("中文名"))
        assertNotNull(validateUsername("ab"))
        assertNotNull(validateUsername("a".repeat(21)))
        assertNotNull(validateUsername("name-with-dash"))
        assertNotNull(validateUsername("   "))
        // 合法；空格会被去掉
        assertNull(validateUsername("tester_1"))
        assertNull(validateUsername(" Tester2 "))
        assertNull(validateUsername("ABC"))
    }

    @Test
    fun `password rules D4`() {
        assertNotNull(validatePassword("abc12"))          // 5 位
        assertNotNull(validatePassword("a".repeat(65)))    // 65 位
        assertNotNull(validatePassword("密码abc123"))      // 含中文
        assertNotNull(validatePassword("has space1"))      // 含空格
        assertNull(validatePassword("abc123"))
        assertNull(validatePassword("!@#$%^&*()_+-=[]{}"))
        assertNull(validatePassword("a".repeat(64)))
    }
}
