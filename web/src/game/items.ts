import type { ItemKind } from './types'

/** 道具元数据唯一来源：模拟生成、渲染配色、图例说明都从这里取 */
export const ITEM_META: { kind: ItemKind; icon: string; label: string; desc: string; color: string }[] = [
  { kind: 'shield', icon: '?:', label: 'Elvis', desc: '+1 护盾，抵挡一次伤害', color: '#55c8ff' },
  { kind: 'val', icon: 'val', label: 'Immutable', desc: '3 秒免疫所有伤害', color: '#55c8ff' },
  { kind: 'coroutines', icon: '⚡', label: 'Coroutines', desc: '永久加速 30%', color: '#55c8ff' },
  { kind: 'flow', icon: '~', label: 'Flow', desc: '子弹 +12', color: '#55c8ff' },
  { kind: 'range', icon: '!!', label: 'NotNull', desc: '射程 +3 格', color: '#55c8ff' },
  { kind: 'heal', icon: 'var', label: 'var', desc: '回血 +5', color: '#55c8ff' },
  { kind: 'haste', icon: '⏩', label: 'launch', desc: '攻速：冷却 -0.1s', color: '#55c8ff' },
]
