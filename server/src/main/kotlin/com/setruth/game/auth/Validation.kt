package com.setruth.game.auth

private val USERNAME_RE = Regex("^[A-Za-z0-9_]+$")
private val PASSWORD_RE = Regex("^[\\x21-\\x7E]+$")
private val RESERVED = setOf("admin", "root", "null")

/** D3：去空格后 3–20 字符，字母/数字/下划线；忽略大小写禁用保留字。返回错误文案或 null。 */
fun validateUsername(u: String): String? {
    val t = u.replace("\\s".toRegex(), "")
    if (t.isEmpty()) return "用户名不能为空"
    if (t.length !in 3..20) return "用户名长度需为 3-20 个字符"
    if (!USERNAME_RE.matches(t)) return "用户名只能包含字母、数字、下划线"
    if (t.lowercase() in RESERVED) return "该用户名为保留字，不可用"
    return null
}

/** D4：6–64 位 ASCII 可见字符（无空格无中文）。返回错误文案或 null。 */
fun validatePassword(p: String): String? {
    if (p.length !in 6..64) return "密码长度需为 6-64 位"
    if (!PASSWORD_RE.matches(p)) return "密码只能包含 ASCII 可见字符（字母、数字、符号，不含空格）"
    return null
}

/** 入库存储用的归一化用户名（去空格） */
fun normalizeUsername(u: String): String = u.replace("\\s".toRegex(), "")
