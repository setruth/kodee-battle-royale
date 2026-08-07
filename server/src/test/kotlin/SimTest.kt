package com.setruth.game.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 服务端模拟快进测试（对应计划 E5）。
 * 注意：本测试不做数值校验（数值以 mockWorld.ts 为唯一事实源），只验证子系统闭环：
 * 缩圈按 SHRINK_TARGETS 推进、gameOver 终触发、placements/播报语义正确、player 槽递补。
 */
class SimTest {

    /** 2 个 bot 无输入快进：缩圈 6 轮按序推进 → 最终圈阶段 → gameOver 触发 → placements 非空 */
    @Test
    fun twoBotsFastForwardToGameOver() {
        val world = createWorld(botJoinInfos(2))
        // 无敌化（只测流程，不让怪物/轰炸/GC 提前终结对局）
        world.allPlayers().forEach {
            it.hp = 1e9f
            it.maxHp = 1e9f
        }

        val stageSeq = mutableListOf(0)
        var sawFinalPhase = false
        var culled = false
        var ticks = 0
        val maxTicks = 12000 // ≈600 秒游戏时间，防死循环（理论终局 ≈325s）
        while (world.gameOver == null && ticks < maxTicks) {
            updateWorld(world, 0.05f, emptyMap())
            ticks++
            val c = world.circle
            if (c.stage != stageSeq.last()) stageSeq.add(c.stage)
            if (c.targetRadius == 0f) {
                sawFinalPhase = true
                // 6 轮全部推进完后淘汰一名 bot，验证 placements 与最后存活结算
                if (!culled && world.npcs.isNotEmpty()) {
                    world.npcs[0].hp = 0f
                    culled = true
                }
            }
        }

        assertNotNull(world.gameOver, "gameOver 应在 $maxTicks tick 内触发（实际 $ticks tick，t=${world.time}）")
        assertEquals((0..world.settings.shrinkTargets.size).toList(), stageSeq, "缩圈 stage 应按 settings.shrinkTargets 逐轮推进")
        assertTrue(sawFinalPhase, "应进入最终圈塌缩阶段（targetRadius = 0）")
        assertTrue(culled, "应执行过淘汰注入")
        assertTrue(world.placements.isNotEmpty(), "placements 非空")
        assertEquals(1, world.placements.size)
        // 幸存者仍是最后存活者，结算 survived
        assertEquals(GameOverKind.survived, world.gameOver)
        assertEquals(1, world.allPlayers().count { it.hp > 0 })
    }

    /** friendlyFire=false：两名真人玩家互射不掉血（子弹穿过），对怪伤害仍按 BULLET_DAMAGE 正常结算 */
    @Test
    fun friendlyFireOffDisablesPvpDamage() {
        val world = createWorld(
            listOf(
                JoinInfo("u1", "甲", "#2ecc71", isBot = false),
                JoinInfo("u2", "乙", "#3498db", isBot = false),
            ),
            GameSettings(friendlyFire = false),
        )
        // 清场：排除游走怪物/初始怪物/障碍物挡弹干扰，摆好对射阵型
        world.monsters.clear()
        world.obstacles.clear()
        val p1 = world.allPlayers().first { it.id == "u1" }
        val p2 = world.allPlayers().first { it.id == "u2" }
        p1.pos.x = 10f; p1.pos.y = 10f
        p2.pos.x = 12f; p2.pos.y = 10f // p2 在弹道上、怪物之前：子弹先穿过 p2 再命中怪物，穿过语义才可被验证
        val monster = MonsterEntity(999999, MonsterKind.npe, Vec2(14f, 10f), Vec2(0f, 0f), 0.6f, 200f, 200f)
        world.monsters.add(monster)

        val shoot = mapOf("u1" to Input(0f, 0f, attack = true, aimX = 1f, aimY = 0f, emote = false))
        repeat(40) { updateWorld(world, 0.05f, shoot) } // 2 秒，约 3~4 发子弹

        assertTrue(p1.mana < GameConfig.MAX_MANA, "应实际开出枪（蓝条已消耗），防假绿")
        assertEquals(100f, p2.hp, "friendlyFire=false 时互射不掉血")
        assertTrue(monster.hp < 200f, "对怪伤害仍正常（BULLET_DAMAGE 不受 friendlyFire 影响）")
    }

    /** friendlyFire=true（默认）：同样的对射应正常掉血，验证开关语义不是恒 0 */
    @Test
    fun friendlyFireOnKeepsPvpDamage() {
        val world = createWorld(
            listOf(
                JoinInfo("u1", "甲", "#2ecc71", isBot = false),
                JoinInfo("u2", "乙", "#3498db", isBot = false),
            ),
        )
        world.monsters.clear()
        world.obstacles.clear()
        val p1 = world.allPlayers().first { it.id == "u1" }
        val p2 = world.allPlayers().first { it.id == "u2" }
        p1.pos.x = 10f; p1.pos.y = 10f
        p2.pos.x = 13f; p2.pos.y = 10f

        val shoot = mapOf("u1" to Input(0f, 0f, attack = true, aimX = 1f, aimY = 0f, emote = false))
        repeat(40) { updateWorld(world, 0.05f, shoot) }

        assertTrue(p1.mana < GameConfig.MAX_MANA, "应实际开出枪（蓝条已消耗），防假绿")
        assertTrue(p2.hp < 100f, "friendlyFire=true（默认）时互射应掉血")
        assertEquals(GameSettings().effectivePvpDamage, 2f, "PvP 伤害写死 2")
    }
    @Test
    fun playerSlotDeathPromotesFirstNpc() {
        val world = createWorld(botJoinInfos(2))
        val deadId = world.player.id
        val deadName = world.player.name
        val survivorId = world.npcs[0].id

        world.player.hp = 0f
        updateWorld(world, 0.05f, emptyMap())

        assertEquals(1, world.placements.size, "死亡应记录 placements（先死在前）")
        assertEquals(deadName, world.placements[0].name)
        assertEquals(survivorId, world.player.id, "npcs 队首应递补进 player 槽")
        assertTrue(world.npcs.isEmpty())
        assertTrue(world.allPlayers().none { it.id == deadId }, "尸体不应残留（快照 ps 无死者）")
        assertEquals(GameOverKind.survived, world.gameOver, "只剩一名存活者应触发结算")
        assertTrue(world.feed.any { it.text.contains(deadName) }, "击杀播报应包含死者名称")
    }
}
