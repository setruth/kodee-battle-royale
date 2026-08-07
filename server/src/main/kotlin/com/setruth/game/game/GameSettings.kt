package com.setruth.game.game

import kotlinx.serialization.Serializable

/**
 * 房间级规则配置（默认值 = 原 mockWorld 写死常量）。
 * 三层来源：yaml `app.game.*` 服务端默认 ← 创建房间请求覆盖 ← 房主 WAITING 中修改。
 * 未列出的常量（移速/弹速/对怪伤害/蓝耗/道具 respawn 等）继续写死在 GameConfig。
 */
@Serializable
data class GameSettings(
    /** 缩圈目标半径序列（长度 = 圈数），最后到 0 为最终塌缩 */
    val shrinkTargets: List<Float> = listOf(30f, 24f, 19f, 14f, 10f, 6f),
    /** 每轮收缩时长（秒） */
    val shrinkTime: Float = 10f,
    /** 每轮收缩后冷却（秒） */
    val shrinkCooldown: Float = 45f,
    /** 首轮收缩前静置（秒） */
    val firstIdle: Float = 15f,
    /** 队友伤害开关：false = 玩家互伤为 0（对怪伤害不受影响）；PvP 伤害写死 GameConfig.PVP_DAMAGE=2 */
    val friendlyFire: Boolean = true,
    /** 怪物触碰伤害 */
    val monsterTouchDamage: Float = 25f,
    /** 初始怪物数量 */
    val monsterInitNpe: Int = 10,
    val monsterInitSoe: Int = 5,
    /** 每轮收缩完成后新增波次 */
    val monsterWaveNpe: Int = 7,
    val monsterWaveSoe: Int = 3,
    /** 轰炸区开关 */
    val bombsEnabled: Boolean = true,
    /** 场上道具数量上限 */
    val itemCount: Int = 14,
    /** 道具刷新权重（7 种，缺省均匀；全 0 时回退均匀） */
    val itemWeights: Map<String, Int> = mapOf(
        "shield" to 1, "coroutines" to 1, "val" to 1,
        "flow" to 1, "range" to 1, "heal" to 1, "haste" to 1,
    ),
) {
    /** 实际 PvP 伤害（友好火力关闭时为 0；数值写死 2，不可房间自定义） */
    val effectivePvpDamage: Float get() = if (friendlyFire) GameConfig.PVP_DAMAGE else 0f
}

/** 服务端侧边界钳制（防离谱配置打爆房间），返回钳制后的副本 */
fun GameSettings.clamped(): GameSettings {
    val targets = shrinkTargets.map { it.coerceIn(1f, 60f) }.take(10).ifEmpty { listOf(6f) }
    return copy(
        shrinkTargets = targets,
        shrinkTime = shrinkTime.coerceIn(3f, 60f),
        shrinkCooldown = shrinkCooldown.coerceIn(5f, 300f),
        firstIdle = firstIdle.coerceIn(0f, 120f),
        monsterTouchDamage = monsterTouchDamage.coerceIn(0f, 100f),
        monsterInitNpe = monsterInitNpe.coerceIn(0, 30),
        monsterInitSoe = monsterInitSoe.coerceIn(0, 30),
        monsterWaveNpe = monsterWaveNpe.coerceIn(0, 20),
        monsterWaveSoe = monsterWaveSoe.coerceIn(0, 20),
        itemCount = itemCount.coerceIn(0, 30),
        itemWeights = itemWeights.filterKeys { it in ITEM_KINDS }.mapValues { (_, v) -> v.coerceIn(0, 100) },
    )
}

private val ITEM_KINDS = setOf("shield", "coroutines", "val", "flow", "range", "heal", "haste")
