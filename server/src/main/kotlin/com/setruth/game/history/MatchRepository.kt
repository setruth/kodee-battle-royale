package com.setruth.game.history

import com.setruth.game.auth.Users
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

object Matches : Table("matches") {
    val matchId = long("match_id").autoIncrement()
    val roomCode = varchar("room_code", 6)
    val startedAt = timestamp("started_at")
    val durationSec = integer("duration_sec")
    val result = text("result")
    /** 当局房间规则（JSON），V3 新增 */
    val settings = text("settings").default("{}")

    override val primaryKey = PrimaryKey(matchId)
}

object MatchPlayers : Table("match_players") {
    val matchId = long("match_id").references(Matches.matchId, onDelete = ReferenceOption.CASCADE)
    val userId = long("user_id").references(Users.userId, onDelete = ReferenceOption.SET_NULL).nullable()
    val username = varchar("username", 20)
    val rank = integer("rank")
    val isBot = bool("is_bot").default(false)

    override val primaryKey = PrimaryKey(matchId, username)
}

data class MatchPlayerRow(val userId: Long?, val username: String, val rank: Int, val isBot: Boolean)

data class HistoryItem(
    val matchId: Long, val startedAt: Instant, val durationSec: Int,
    val playerCount: Int, val myRank: Int,
)

data class MatchDetail(val startedAt: Instant, val durationSec: Int, val result: String, val settings: String)

object MatchRepository {

    /** D21：一局进 RESULT 即落库，返回 match id */
    fun insertMatch(
        roomCode: String, startedAt: Instant, durationSec: Int,
        resultJson: String, players: List<MatchPlayerRow>, settingsJson: String = "{}",
    ): Long = transaction {
        val matchId = Matches.insert {
            it[Matches.roomCode] = roomCode
            it[Matches.startedAt] = startedAt
            it[Matches.durationSec] = durationSec
            it[Matches.result] = resultJson
            it[Matches.settings] = settingsJson
        } get Matches.matchId
        players.forEach { p ->
            MatchPlayers.insert {
                it[MatchPlayers.matchId] = matchId
                it[MatchPlayers.userId] = p.userId
                it[MatchPlayers.username] = p.username
                it[MatchPlayers.rank] = p.rank
                it[MatchPlayers.isBot] = p.isBot
            }
        }
        matchId
    }

    fun myHistory(userId: Long, limit: Int): List<HistoryItem> = transaction {
        val rows = (MatchPlayers innerJoin Matches)
            .selectAll()
            .where { MatchPlayers.userId eq userId }
            .orderBy(Matches.startedAt to SortOrder.DESC)
            .limit(limit)
            .toList()
        val counts = if (rows.isEmpty()) emptyMap() else MatchPlayers
            .selectAll()
            .where { MatchPlayers.matchId inList rows.map { it[Matches.matchId] } }
            .map { it[MatchPlayers.matchId] }
            .groupingBy { it }
            .eachCount()
        rows.map {
            HistoryItem(
                matchId = it[Matches.matchId],
                startedAt = it[Matches.startedAt],
                durationSec = it[Matches.durationSec],
                playerCount = counts[it[Matches.matchId]] ?: 0,
                myRank = it[MatchPlayers.rank],
            )
        }
    }
    fun detail(matchId: Long): MatchDetail? = transaction {
        Matches.selectAll().where { Matches.matchId eq matchId }.firstOrNull()?.let {
            MatchDetail(it[Matches.startedAt], it[Matches.durationSec], it[Matches.result], it[Matches.settings])
        }
    }
}
