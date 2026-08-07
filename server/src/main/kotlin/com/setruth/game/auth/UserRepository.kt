package com.setruth.game.auth

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException

object Users : Table("users") {
    val userId = long("user_id").autoIncrement()
    val username = varchar("username", 20)
    val name = varchar("name", 20)
    val password = varchar("password", 32) // MD5 hex
    val usernameCi = varchar("username_ci", 20)

    override val primaryKey = PrimaryKey(userId)
}

data class UserRow(val userId: Long, val username: String, val name: String, val password: String)

object UserRepository {

    /** 注册；唯一索引冲突返回 null（用户名已存在）。name 默认 = username */
    fun insert(username: String, passwordMd5: String): UserRow? = try {
        transaction {
            val id = Users.insert {
                it[Users.username] = username
                it[Users.name] = username
                it[Users.password] = passwordMd5
                it[usernameCi] = username.lowercase()
            } get Users.userId
            UserRow(id, username, username, passwordMd5)
        }
    } catch (e: Exception) {
        if (isUniqueViolation(e)) null else throw e
    }

    fun findByCi(usernameCi: String): UserRow? = transaction {
        Users.selectAll().where { Users.usernameCi eq usernameCi.lowercase() }.firstOrNull()?.toRow()
    }

    fun findById(id: Long): UserRow? = transaction {
        Users.selectAll().where { Users.userId eq id }.firstOrNull()?.toRow()
    }

    private fun ResultRow.toRow() =
        UserRow(this[Users.userId], this[Users.username], this[Users.name], this[Users.password])

    private fun isUniqueViolation(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is PSQLException && cur.sqlState == "23505") return true
            cur = cur.cause
        }
        return false
    }
}
