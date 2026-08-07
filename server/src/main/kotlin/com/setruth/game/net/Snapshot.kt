package com.setruth.game.net

import com.setruth.game.game.World
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.roundToInt

private fun q(v: Float): Int = (v * 100).roundToInt()

/**
 * 快照序列化（单点量化：坐标/速度/时间 ×100 取整，积分 ×10 取整）。
 * DC 与 WS 降级共用同一 JSON；每 tick 只调用一次，同一字符串发所有连接。
 * 返回 (json, 新的 lastFeedId, 新的 lastLogId)。
 */
fun serializeSnapshot(world: World, tick: Long, lastFeedId: Long, lastLogId: Long): Triple<String, Long, Long> {
    var newLast = lastFeedId
    var newLastLog = lastLogId
    val json = buildJsonObject {
        put("t", "s")
        put("k", tick)
        put("tm", q(world.time))
        putJsonArray("ps") {
            (listOf(world.player) + world.npcs).forEach { p ->
                add(buildJsonObject {
                    put("i", p.id); put("n", p.name); put("c", p.color)
                    put("x", q(p.pos.x)); put("y", q(p.pos.y))
                    put("fx", q(p.face.x)); put("fy", q(p.face.y))
                    put("hp", p.hp.roundToInt()); put("mh", p.maxHp.roundToInt())
                    put("mp", p.mana.roundToInt()); put("mm", p.maxMana.roundToInt())
                    put("sp", q(p.speed)); put("df", p.defense)
                    put("iv", if (p.invincibleUntil > world.time) 1 else 0)
                    put("sq", q(p.squash)); put("mv", if (p.moving) 1 else 0)
                    put("rb", q(p.rangeBonus)); put("hb", q(p.hasteBonus))
                    put("sc", (p.score * 10).roundToInt())
                })
            }
        }
        putJsonArray("ms") {
            world.monsters.forEach { m ->
                add(buildJsonObject {
                    put("i", m.id); put("k", m.kind.toString())
                    put("x", q(m.pos.x)); put("y", q(m.pos.y))
                    put("dx", q(m.dir.x)); put("dy", q(m.dir.y))
                    put("hp", m.hp.roundToInt()); put("mh", m.maxHp.roundToInt())
                })
            }
        }
        putJsonArray("pr") {
            world.projectiles.forEach { pr ->
                add(buildJsonObject {
                    put("i", pr.id); put("x", q(pr.pos.x)); put("y", q(pr.pos.y))
                    put("dx", q(pr.dir.x)); put("dy", q(pr.dir.y))
                })
            }
        }
        putJsonArray("it") {
            world.items.forEach { item ->
                add(buildJsonObject {
                    put("i", item.id); put("k", item.kind.toString())
                    put("x", q(item.pos.x)); put("y", q(item.pos.y))
                })
            }
        }
        put("ci", buildJsonObject {
            val c = world.circle
            put("cx", q(c.center.x)); put("cy", q(c.center.y)); put("r", q(c.radius))
            val tc = c.targetCenter
            if (tc != null) { put("tx", q(tc.x)); put("ty", q(tc.y)) } else { put("tx", JsonNull); put("ty", JsonNull) }
            val tr = c.targetRadius
            if (tr != null) put("tr", q(tr)) else put("tr", JsonNull)
            put("st", c.stage); put("ph", c.phase.toString()); put("ps", q(c.phaseStart))
        })
        putJsonArray("ft") {
            world.floats.forEach { f ->
                add(buildJsonObject {
                    put("x", q(f.pos.x)); put("y", q(f.pos.y))
                    put("tx", f.text); put("c", f.color); put("l", q(f.life))
                })
            }
        }
        putJsonArray("bz") {
            world.bombs.forEach { b ->
                add(buildJsonObject {
                    put("i", b.id); put("x", q(b.pos.x)); put("y", q(b.pos.y)); put("r", q(b.radius))
                    put("ea", q(b.explodeAt)); put("au", q(b.animUntil))
                })
            }
        }
        putJsonArray("ev") {
            world.feed.forEach { f ->
                val fid = f.id.toLong()
                if (fid > lastFeedId) {
                    newLast = maxOf(newLast, fid)
                    add(buildJsonObject { put("id", fid); put("t", f.text); put("c", f.color) })
                }
            }
        }
        // 对局详细日志（增量，同 ev 机制；结算消息带全量）
        putJsonArray("lg") {
            world.logs.forEach { l ->
                val lid = l.id.toLong()
                if (lid > lastLogId) {
                    newLastLog = maxOf(newLastLog, lid)
                    add(buildJsonObject { put("id", lid); put("tm", q(l.time)); put("t", l.text); put("c", l.color) })
                }
            }
        }
    }
    return Triple(json.toString(), newLast, newLastLog)
}
