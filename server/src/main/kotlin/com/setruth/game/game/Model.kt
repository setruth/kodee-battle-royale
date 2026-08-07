package com.setruth.game.game

/**
 * 游戏世界模型：镜像 web/src/game/types.ts 的全部接口，字段名保持一致便于对照移植。
 * 数值统一用 Float（联机契约）；枚举条目全小写，toString 直接用于快照序列化。
 *
 * 与 types.ts 的联机化差异（仅限少量字段）：
 * - `player` / `npcs` 结构保留（player = 首个实体，其余进 npcs；真人+bot 混合，用 isBot 区分）；
 * - 单玩家专属的 `attackCooldown` / `outsideSince` 下放为每实体字段：
 *   攻击冷却用 `attackCd`（绝对时刻，等价 mock 的 world.attackCooldown 语义），
 *   出圈宽限用 `outsideSince`（仅真人使用）；
 * - 增加 `isBot` / `manaWarnAt` 联机必需字段；`playerOutside` 保留（对 player 槽实体每 tick 更新）。
 */

data class Vec2(var x: Float, var y: Float)

enum class MonsterKind { npe, soe }

enum class ItemKind { shield, coroutines, `val`, flow, range, heal, haste }

enum class ObstacleKind { boulder, pillar }

enum class CirclePhase { idle, shrinking }

enum class GameOverKind { dead, survived }

class PlayerEntity(
    val id: String,
    val pos: Vec2,
    var face: Vec2,
    val name: String,
    val color: String,
    var radius: Float,
    var hp: Float,
    var maxHp: Float,
    /** 当前实际移速（格/s，含 buff/debuff 结算后） */
    var speed: Float,
    /** 蓝条 = 子弹数：每发子弹消耗 1，满蓝 300 发 */
    var mana: Float,
    var maxMana: Float,
    /** !! 增益：攻击距离加成（格，永久累加） */
    var rangeBonus: Float = 0f,
    /** launch 增益：攻击冷却减免（秒，永久累加，下限 0.3s） */
    var hasteBonus: Float = 0f,
    /** 防御（护盾层数），抵挡下一次伤害 */
    var defense: Float = 0f,
    /** 无敌截止时间（val / 受击保护），world.time 秒 */
    var invincibleUntil: Float = 0f,
    /** Coroutines 加速截止时间 */
    var speedBuffUntil: Float = 0f,
    /** StackOverflow 减速截止时间 */
    var slowUntil: Float = 0f,
    /** 挤压系数：受击/移动时的卡通弹性，1 = 正常 */
    var squash: Float = 1f,
    /** 是否在移动（跳跃浮动动画用） */
    var moving: Boolean = false,
    /** 最近一次伤害来源（击杀播报用）：玩家名 / '怪物' / 'GC' / '轰炸区' */
    var lastHitBy: String? = null,
    /** bot AI：当前目标点（仅 bot 使用） */
    var aiTarget: Vec2? = null,
    /** bot AI：下次换目标时刻（仅 bot 使用） */
    var aiRetargetAt: Float? = null,
    /** 攻击冷却截止时刻（bot 与真人共用，绝对时刻语义等价 mock 的 world.attackCooldown） */
    var attackCd: Float? = null,
    /** 是否 bot（服务端演算的输入发生器） */
    val isBot: Boolean = false,
    /** 出圈起始时间（真人 1s 宽限用；bot 无宽限直接掉血） */
    var outsideSince: Float? = null,
    /** 缺蓝提示节流时刻（每实体独立，mock 为模块级单例） */
    var manaWarnAt: Float = -10f,
    /** 积分：命中怪物每发 +1 / 补刀击杀 +2 / 结算存活血量 ×10% */
    var score: Float = 0f,
)

class MonsterEntity(
    val id: Int,
    val kind: MonsterKind,
    val pos: Vec2,
    var dir: Vec2,
    var radius: Float,
    var hp: Float,
    var maxHp: Float,
)

class ItemEntity(
    val id: Int,
    val kind: ItemKind,
    val pos: Vec2,
    val icon: String,
    val label: String,
    /** 刷出时刻（world.time 秒）：超过 GameConfig.ITEM_TTL 未拾取即消失 */
    val spawnAt: Float = 0f,
)

class Projectile(
    val id: Int,
    /** 射击者 id，子弹不伤 shooter 本人 */
    val owner: String,
    /** 射击者显示名（击杀播报用） */
    val ownerName: String,
    val pos: Vec2,
    val dir: Vec2,
    /** 飞行速度（格/s），支持计算继承移速分量 */
    var speed: Float,
    /** 剩余寿命（秒），射程 = 弹速 × 寿命 */
    var life: Float,
)

/** 安全圈状态：缩圈即倒计时，6 段收缩，段间冷却，圆心随机偏移 */
class CircleState(
    var center: Vec2,
    var radius: Float,
    /** 下一圈（常显虚线），null = 已是最终圈 */
    var targetCenter: Vec2?,
    var targetRadius: Float?,
    /** 已完成收缩次数 */
    var stage: Int,
    var phase: CirclePhase,
    var phaseStart: Float,
    var shrinkFromRadius: Float,
    var shrinkFromCenter: Vec2,
)

/** 浮动文字特效（伤害、表情、道具提示等） */
class FloatText(
    val pos: Vec2,
    val text: String,
    val color: String,
    var life: Float,
)

/** 静态障碍物（永久掩体，见设计 05）：巨石挡移动挡子弹、石柱同 */
class Obstacle(
    val id: Int,
    val kind: ObstacleKind,
    val pos: Vec2,
    /** pillar 半径（格）；boulder 为 0 */
    val radius: Float,
    /** boulder 宽（格）；pillar 为 0 */
    val w: Float,
    /** boulder 高（格）；pillar 为 0 */
    val h: Float,
)

/** 随机轰炸区 */
class BombZone(
    val id: Int,
    val pos: Vec2,
    val radius: Float,
    /** 爆炸时刻（world.time 秒） */
    val explodeAt: Float,
    /** 爆炸动画截止时间，0 = 还在预警期 */
    var animUntil: Float,
)

/** 击杀播报条目（id 全局递增） */
class FeedEntry(
    val id: Int,
    val text: String,
    val color: String,
)

/** 淘汰记录（先死在前），结算页最终排名用（积分制：记录死亡时刻积分） */
data class Placement(val name: String, val color: String, val score: Float)

/** 对局详细日志条目（id 全局递增；快照增量下发，结算全量入库） */
class LogEntry(
    val id: Int,
    /** 事件发生时刻（world.time 秒） */
    val time: Float,
    val text: String,
    val color: String,
)

class World(
    /** 矩形世界尺寸（格），16:9 对应背景图 */
    val width: Float,
    val height: Float,
    /** 房间级规则配置（默认值 = 原 mock 写死常量），本局所有可调数值的唯一来源 */
    val settings: GameSettings,
    /** 首个实体（镜像 types.ts 的 player；槽内实体死亡且有存活者时由 npcs 队首递补，id 不变） */
    var player: PlayerEntity,
    /** 其余对局实体（真人 + bot，对应 types.ts 的 npcs） */
    val npcs: MutableList<PlayerEntity>,
    /** 静态障碍物（开局生成，不消失） */
    val obstacles: MutableList<Obstacle>,
    /** 实时击杀播报（最新在前，最多 8 条） */
    val feed: MutableList<FeedEntry>,
    /** 淘汰顺序（先死在前），结算页最终排名用 */
    val placements: MutableList<Placement>,
    /** 对局详细日志（时间序，快照增量下发 + 结算全量入库；封顶 400 条） */
    val logs: MutableList<LogEntry>,
    val monsters: MutableList<MonsterEntity>,
    val items: MutableList<ItemEntity>,
    val projectiles: MutableList<Projectile>,
    val floats: MutableList<FloatText>,
    val circle: CircleState,
    val bombs: MutableList<BombZone>,
    /** 下一波轰炸时刻 */
    var nextBombAt: Float,
    /** player 槽实体是否在圈外（渲染红晕用，mock 原字段保留） */
    var playerOutside: Boolean = false,
    var gameOver: GameOverKind?,
    var time: Float,
) {
    /** 全部对局实体（player 槽 + npcs），遍历逻辑统一走这里 */
    fun allPlayers(): List<PlayerEntity> = listOf(player) + npcs
}
