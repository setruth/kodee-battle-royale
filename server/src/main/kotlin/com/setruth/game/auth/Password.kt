package com.setruth.game.auth

import java.security.MessageDigest

/**
 * 密码散列：MD5 hex（32 字符小写）。
 * ⚠️ MD5 是快速哈希，抗撞库能力弱；若日后要升级，把此处换成 BCrypt/Argon2 即可，表结构不用动。
 */
fun md5Hex(s: String): String =
    MessageDigest.getInstance("MD5")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
