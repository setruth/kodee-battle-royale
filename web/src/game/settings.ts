import type { ItemKind } from './types'

/**
 * 房间规则配置（服务端 GameSettings 的 TS 镜像）：
 * 创建房间可选提交、room 广播实时下发、gameStart.st 随开局载荷定型。
 */
export interface GameSettings {
  /** 各轮缩圈目标半径（格）；圈数 = 数组长度 */
  shrinkTargets: number[]
  /** 每圈收缩时长（秒） */
  shrinkTime: number
  /** 收缩完成后的冷却间隔（秒） */
  shrinkCooldown: number
  /** 首轮缩圈前的静置时间（秒） */
  firstIdle: number
  /** 队友伤害开关（PvP 伤害写死 2，不可自定义） */
  friendlyFire: boolean
  /** 怪物触碰伤害 */
  monsterTouchDamage: number
  /** 初始怪物数量（NPE 红眼死神 / SOE 蓝色漩涡） */
  monsterInitNpe: number
  monsterInitSoe: number
  /** 每波补充怪物数量（NPE/SOE） */
  monsterWaveNpe: number
  monsterWaveSoe: number
  /** 轰炸区（OOM 红圈）开关 */
  bombsEnabled: boolean
  /** 场上同时存在的道具上限 */
  itemCount: number
  /** 各道具刷新权重（相对值，默认全 1 = 均匀） */
  itemWeights: Record<ItemKind, number>
}

/** 服务端默认值镜像：表单初始值与 gameStart 缺 st 时的兜底 */
export const DEFAULT_SETTINGS: GameSettings = {
  shrinkTargets: [30, 24, 19, 14, 10, 6],
  shrinkTime: 10,
  shrinkCooldown: 45,
  firstIdle: 15,
  friendlyFire: true,
  monsterTouchDamage: 25,
  monsterInitNpe: 10,
  monsterInitSoe: 5,
  monsterWaveNpe: 7,
  monsterWaveSoe: 3,
  bombsEnabled: true,
  itemCount: 14,
  itemWeights: { shield: 1, val: 1, coroutines: 1, flow: 1, range: 1, heal: 1, haste: 1 },
}

/** 深拷贝一份设置（表单草稿用，itemWeights 是嵌套对象必须随拷） */
export function cloneSettings(s: GameSettings): GameSettings {
  return { ...s, shrinkTargets: [...s.shrinkTargets], itemWeights: { ...s.itemWeights } }
}
