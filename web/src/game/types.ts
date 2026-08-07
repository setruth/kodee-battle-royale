/** 游戏世界坐标单位：格。地图 120×120 格，见 design/README.md */
export interface Vec2 {
  x: number
  y: number
}

export interface PlayerEntity {
  id: string
  pos: Vec2
  /** 面向（移动方向），用于瞄准与眼睛朝向 */
  face: Vec2
  name: string
  color: string
  radius: number
  hp: number
  maxHp: number
  /** 当前实际移速（格/s，含 buff/debuff 结算后） */
  speed: number
  /** 蓝条：每次攻击消耗 2 */
  mana: number
  maxMana: number
  /** !! 增益：攻击距离加成（格，永久累加） */
  rangeBonus: number
  /** launch 增益：攻击冷却减免（秒，永久累加，下限 0.4s） */
  hasteBonus: number
  /** 防御（护盾层数），抵挡下一次伤害 */
  defense: number
  /** 无敌截止时间（val / 受击保护），world.time 秒 */
  invincibleUntil: number
  /** 挤压系数：受击/移动时的卡通弹性，1 = 正常 */
  squash: number
  /** 是否在移动（跳跃浮动动画用） */
  moving: boolean
  /** 积分：命中怪物每发 +1 / 补刀击杀 +2 / 结算存活血量 ×10% */
  score: number
}

export type MonsterKind = 'npe' | 'soe'

export interface MonsterEntity {
  id: number
  kind: MonsterKind
  pos: Vec2
  dir: Vec2
  radius: number
  hp: number
  maxHp: number
}

export type ItemKind = 'shield' | 'coroutines' | 'val' | 'flow' | 'range' | 'heal' | 'haste'

export interface ItemEntity {
  id: number
  kind: ItemKind
  pos: Vec2
  icon: string
  label: string
}

export interface Projectile {
  id: number
  /** 射击者 id（'local' 或 npc id），子弹不伤 shooter 本人 */
  owner: string
  /** 射击者显示名（击杀播报用） */
  ownerName: string
  pos: Vec2
  dir: Vec2
  /** 飞行速度（格/s），支持计算继承移速分量 */
  speed?: number
  /** 剩余寿命（秒），射程 = 弹速 × 寿命 */
  life: number
}

/** 安全圈状态：缩圈即倒计时，4 段收缩，段间冷却 30s，圆心随机偏移 */
export interface CircleState {
  center: Vec2
  radius: number
  /** 下一圈（常显虚线），null = 已是最终圈 */
  targetCenter: Vec2 | null
  targetRadius: number | null
  /** 已完成收缩次数 */
  stage: number
  phase: 'idle' | 'shrinking'
  phaseStart: number
  shrinkFromRadius: number
  shrinkFromCenter: Vec2
}

/** 浮动文字特效（伤害、表情、道具提示等） */
export interface FloatText {
  pos: Vec2
  text: string
  color: string
  life: number
}

/** 静态障碍物（永久掩体，见设计 05）：巨石挡移动挡子弹、石柱同 */
export interface Obstacle {
  id: number
  kind: 'boulder' | 'pillar'
  pos: Vec2
  /** pillar 半径（格）；boulder 为 0 */
  radius: number
  /** boulder 宽（格）；pillar 为 0 */
  w: number
  /** boulder 高（格）；pillar 为 0 */
  h: number
}

/** 随机轰炸区 */
export interface BombZone {
  id: number
  pos: Vec2
  radius: number
  /** 爆炸时刻（world.time 秒） */
  explodeAt: number
  /** 爆炸动画截止时间，0 = 还在预警期 */
  animUntil: number
}

/** 击杀播报条目 */
export interface FeedEntry {
  id: number
  text: string
  color: string
}

/** 对局详细日志条目（快照增量下发 + 结算全量；离线 mock 同结构写入） */
export interface LogEntry {
  id: number
  /** 事件发生时刻（world.time 秒） */
  time: number
  text: string
  color: string
}

export type GameOverKind = 'dead' | 'survived'

export interface World {
  /** 矩形世界尺寸（格），16:9 对应背景图 */
  width: number
  height: number
  player: PlayerEntity
  /** NPC 玩家（多人预览用，简单 AI） */
  npcs: PlayerEntity[]
  /** 静态障碍物（开局生成，不消失） */
  obstacles: Obstacle[]
  /** 实时击杀播报（最新在前，最多 8 条） */
  feed: FeedEntry[]
  /** 对局详细日志（时间序；联机由快照增量追加） */
  logs: LogEntry[]
  monsters: MonsterEntity[]
  items: ItemEntity[]
  projectiles: Projectile[]
  floats: FloatText[]
  circle: CircleState
  bombs: BombZone[]
  /** 下一波轰炸时刻 */
  nextBombAt: number
  /** 玩家是否在圈外（渲染红晕 / 毒圈提示用，客户端自行判定） */
  playerOutside: boolean
  gameOver: GameOverKind | null
  time: number
}
