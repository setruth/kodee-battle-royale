package com.setruth.game.game

/**
 * 游戏数值配置：写死常量部分，与 web/src/game/mockWorld.ts:17-42 逐一对齐。
 * 房间级可调数值（缩圈序列与节奏/怪物伤害与波次/轰炸开关/道具数量与权重）
 * 已移至 [GameSettings]，按房间生效；此处只保留全局写死常量。
 * PvP 伤害写死 2（积分制后不再允许房间自定义）。
 */
object GameConfig {
    const val MAP_W = 96f
    const val MAP_H = 54f

    const val BASE_SPEED = 6f
    const val BULLET_SPEED = 28f // 提升基准弹速 28（原 22 偏慢）
    const val BULLET_DAMAGE = 25f // 攻击怪物伤害
    const val PVP_DAMAGE = 2f // PvP 命中伤害（写死，friendlyFire=false 时为 0）
    const val ATTACK_CD = 0.6f
    const val ATTACK_MANA = 1f // 每发子弹消耗 1 点蓝条（满蓝 300 = 300 发）
    const val MAX_MANA = 300f // 蓝条上限 = 子弹数
    const val BASE_RANGE = 9f
    const val PICKUP_RADIUS = 1.8f
    const val SOE_AURA = 3f // SOE 减速光环半径（格）

    const val INITIAL_RADIUS = 60f // 覆盖矩形地图对角（≈55）
    const val FINAL_IDLE = 10f // 最后一轮结束后，最终圈塌缩前的缓冲
    const val FINAL_SHRINK_TIME = 15f // 最终圈缩到中心点的时长

    const val ITEM_RESPAWN = 4 // 秒
    const val ITEM_TTL = 5f // 道具生命周期（秒）：刷出 5s 未拾取即消失（防毒区道具烂掉）

    /** 服务端 tick 频率（计划 F 步 20Hz 快照） */
    const val TICK_HZ = 20
}
