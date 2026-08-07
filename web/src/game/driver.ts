import type { RtcChannel } from '@/net/rtc'
import type { InputController } from './input'
import { ITEM_META } from './items'
import { DEFAULT_SETTINGS } from './settings'
import type { GameSettings } from './settings'
import type { ItemKind, PlayerEntity, Vec2, World } from './types'

// 全局写死常量（服务端 GameConfig 镜像；引擎/渲染器从这里取）
/** 最后一轮结束后，最终圈塌缩前的缓冲（秒） */
export const FINAL_IDLE = 10
/** 最终圈缩到中心点的时长（秒） */
export const FINAL_SHRINK_TIME = 15
/** SOE 减速光环半径（格） */
export const SOE_AURA = 3

/**
 * 世界驱动接口（D15）：NetDriver（快照插值）注入 GameEngine。
 * selfId = 本端控制的实体 id；旁观者恒为 null（D20）。
 */
export interface WorldDriver {
  readonly world: World
  readonly selfId: string | null
  /** 本端实体已阵亡（快照连续缺失判定） */
  readonly selfDead: boolean
  /** 本局房间规则（取 gameStart.st，缺省用 DEFAULT_SETTINGS） */
  readonly settings: GameSettings
  update(dt: number, input: InputController): void
  destroy(): void
}

// ── 协议消息类型（快照数值约定：坐标/速度/time/phaseStart ×100 取整，hp/mana/defense 原值取整）──

export interface GameStartMsg {
  t: 'gameStart'
  /** 本端实体 id（真人 "u{userId}" / bot "b{n}"）；旁观者为 null */
  you: string | null
  map: { w: number; h: number }
  /** 静态障碍物，坐标与尺寸均 ×100（r/w/h 缺省为 0） */
  ob: { id: number; kind: 'boulder' | 'pillar'; x: number; y: number; r?: number; w?: number; h?: number }[]
  seed: number
  /** 本局房间规则（缺省用 DEFAULT_SETTINGS） */
  st?: GameSettings
}

interface SnapPlayer {
  i: string
  n: string
  c: string
  x: number
  y: number
  fx: number
  fy: number
  hp: number
  mh: number
  mp: number
  mm: number
  sp: number
  df: number
  iv: number
  sq: number
  mv: number
  rb: number
  hb: number
  /** 积分 ×10 取整 */
  sc: number
}

interface SnapMonster {
  i: number
  k: 'npe' | 'soe'
  x: number
  y: number
  dx: number
  dy: number
  hp: number
  mh: number
}

interface SnapProjectile {
  i: number
  x: number
  y: number
  dx: number
  dy: number
}

interface SnapItem {
  i: number
  k: ItemKind
  x: number
  y: number
}

interface SnapCircle {
  cx: number
  cy: number
  r: number
  tx: number | null
  ty: number | null
  tr: number | null
  st: number
  ph: 'idle' | 'shrinking'
  ps: number
}

interface SnapFloat {
  x: number
  y: number
  tx: string
  c: string
  l: number
}

interface SnapBomb {
  i: number
  x: number
  y: number
  r: number
  ea: number
  au: number
}

export interface SnapshotMsg {
  t: 's'
  k: number
  tm: number
  ps: SnapPlayer[]
  ms: SnapMonster[]
  pr: SnapProjectile[]
  it: SnapItem[]
  ci: SnapCircle
  /** 增量击杀播报 */
  ev?: { id: number; t: string; c: string }[]
  /** 增量对局详细日志（tm = 发生时刻秒 ×100） */
  lg?: { id: number; tm: number; t: string; c: string }[]
  /** 浮动文字（整组覆盖） */
  ft?: SnapFloat[]
  /** 轰炸区（整组覆盖） */
  bz?: SnapBomb[]
}

/** 渲染延迟：以最新帧 −60ms 的时刻做线性插值（20Hz 快照间隔 50ms，留 10ms 抖动余量） */
const INTERP_DELAY_MS = 60
/** 输入发送节流（30Hz） */
const INPUT_HZ = 30

function clamp(v: number, min: number, max: number) {
  return v < min ? min : v > max ? max : v
}

function makeEntity(id: string, name: string, color: string, pos: Vec2): PlayerEntity {
  return {
    id,
    pos,
    face: { x: 1, y: 0 },
    name,
    color,
    radius: 0.7,
    hp: 100,
    maxHp: 100,
    speed: 6,
    mana: 300,
    maxMana: 300,
    rangeBonus: 0,
    hasteBonus: 0,
    defense: 0,
    invincibleUntil: 0,
    squash: 1,
    moving: false,
    score: 0,
  }
}

/**
 * 快照驱动：保留最近 2+ 帧，渲染时刻 = 最新帧到达时间 −100ms，两帧间线性插值写入 world。
 * 输入每帧采样、30Hz 节流经 RtcChannel 发送（DC open 走 DC，否则 WS 降级）。
 */
export class NetDriver implements WorldDriver {
  readonly world: World
  readonly selfId: string | null
  readonly settings: GameSettings

  private channel: RtcChannel
  private entities = new Map<string, PlayerEntity>()
  private buf: { at: number; s: SnapshotMsg }[] = []
  private lastEvId = 0
  private lastLgId = 0
  private sendAcc = 0
  private pendingEmote = false
  /** 快照连续缺失 selfId 的帧数（≥2 判定阵亡） */
  private selfMissing = 0
  private _selfDead = false
  /** 客户端预测的本端位置（格）；null = 未初始化/已死亡 */
  private predicted: Vec2 | null = null

  /** 本端实体是否已阵亡（攻击/血量/死亡以服务器为准，此标记由快照缺失驱动） */
  get selfDead() {
    return this._selfDead
  }

  constructor(payload: GameStartMsg, channel: RtcChannel) {
    this.channel = channel
    this.selfId = payload.you ?? null
    this.settings = payload.st ?? DEFAULT_SETTINGS
    const w = payload.map.w
    const h = payload.map.h
    const center = { x: w / 2, y: h / 2 }
    // 旁观者：world.player 填充地图中心哨兵实体（id='spectate'，仅满足类型，渲染层跳过绘制）
    const sentinel = makeEntity(this.selfId ?? 'spectate', '', '#ffffff', center)
    this.world = {
      width: w,
      height: h,
      player: sentinel,
      npcs: [],
      obstacles: payload.ob.map((o) => ({
        id: o.id,
        kind: o.kind,
        pos: { x: o.x / 100, y: o.y / 100 },
        radius: (o.r ?? 0) / 100,
        w: (o.w ?? 0) / 100,
        h: (o.h ?? 0) / 100,
      })),
      feed: [],
      logs: [],
      monsters: [],
      items: [],
      projectiles: [],
      floats: [],
      circle: {
        center,
        radius: 60,
        targetCenter: null,
        targetRadius: null,
        stage: 0,
        phase: 'idle',
        phaseStart: 0,
        shrinkFromRadius: 60,
        shrinkFromCenter: { ...center },
      },
      playerOutside: false,
      bombs: [],
      nextBombAt: Number.POSITIVE_INFINITY,
      gameOver: null,
      time: 0,
    }
    // 开局启动 2s 心跳测延迟（destroy 停）
    channel.startPing()
  }

  /** 收快照（DC 或 WS 降级同一 JSON）：入缓冲 + 处理增量击杀播报 + 本端阵亡判定 */
  pushSnapshot(msg: SnapshotMsg) {
    this.buf.push({ at: performance.now(), s: msg })
    while (this.buf.length > 3) this.buf.shift()
    // 自己实体从 ps 中消失 = 已被服务器摘除：连续 2 帧缺失判定阵亡（D12 死后重连同样走这里）
    if (this.selfId && !this._selfDead) {
      if (msg.ps.some((p) => p.i === this.selfId)) {
        this.selfMissing = 0
      } else if (++this.selfMissing >= 2) {
        this._selfDead = true
        this.predicted = null
        // world.player 保留最后已知状态，仅血量置 0（不再用满血假实体占位）
        this.world.player.hp = 0
        this.world.player.moving = false
      }
    }
    for (const ev of msg.ev ?? []) {
      if (ev.id <= this.lastEvId) continue
      this.lastEvId = ev.id
      this.world.feed.unshift({ id: ev.id, text: ev.t, color: ev.c })
    }
    if (this.world.feed.length > 8) this.world.feed.length = 8
    // 增量对局日志：时间序追加（封顶 400，与服务端一致）
    for (const lg of msg.lg ?? []) {
      if (lg.id <= this.lastLgId) continue
      this.lastLgId = lg.id
      this.world.logs.push({ id: lg.id, time: lg.tm / 100, text: lg.t, color: lg.c })
    }
    if (this.world.logs.length > 400) this.world.logs.splice(0, this.world.logs.length - 400)
  }

  update(dt: number, input: InputController) {
    this.interpolate()
    // 旁观者无输入；阵亡后停发输入（攻击/血量/死亡完全以服务器为准）
    if (!this.selfId || this._selfDead) return
    this.predict(dt, input)
    this.pendingEmote = input.consumeEmote() || this.pendingEmote
    this.sendAcc += dt
    if (this.sendAcc >= 1 / INPUT_HZ) {
      this.sendAcc = 0
      const mv = input.moveVector()
      const aim = input.aimVector()
      this.channel.send({
        t: 'in',
        d: [Math.round(mv.x * 100), Math.round(mv.y * 100)],
        a: input.firing(),
        aim: aim ? [Math.round(aim.x * 100), Math.round(aim.y * 100)] : null,
        e: this.pendingEmote,
      })
      this.pendingEmote = false
    }
  }

  // ── 客户端预测（仅移动；攻击/血量/死亡不预测）──

  /** 每帧用当前输入与快照给的最新速度本地推进自己 pos，含地图边界 clamp 与障碍物碰撞（与服务端 Sim 一致） */
  private predict(dt: number, input: InputController) {
    const self = this.entities.get(this.selfId!)
    const pred = this.predicted
    if (!self || !pred) return
    const mv = input.moveVector()
    if (mv.x !== 0 || mv.y !== 0) {
      pred.x = clamp(pred.x + mv.x * self.speed * dt, self.radius, this.world.width - self.radius)
      pred.y = clamp(pred.y + mv.y * self.speed * dt, self.radius, this.world.height - self.radius)
      this.resolveObstacles(pred, self.radius)
      self.moving = true
    } else {
      self.moving = false
    }
    self.pos.x = pred.x
    self.pos.y = pred.y
  }

  /** 圆形实体推出障碍物（与服务端 Sim.resolveObstacles 一致，作用于预测坐标） */
  private resolveObstacles(pos: Vec2, r: number) {
    for (const o of this.world.obstacles) {
      if (o.kind === 'pillar') {
        const d = Math.hypot(pos.x - o.pos.x, pos.y - o.pos.y)
        const min = r + o.radius
        if (d < min && d > 1e-6) {
          pos.x = o.pos.x + ((pos.x - o.pos.x) / d) * min
          pos.y = o.pos.y + ((pos.y - o.pos.y) / d) * min
        }
      } else {
        const hw = o.w / 2 + r
        const hh = o.h / 2 + r
        const dx = pos.x - o.pos.x
        const dy = pos.y - o.pos.y
        if (Math.abs(dx) < hw && Math.abs(dy) < hh) {
          // 沿最浅穿透轴推出
          if (hw - Math.abs(dx) < hh - Math.abs(dy)) pos.x = o.pos.x + Math.sign(dx || 1) * hw
          else pos.y = o.pos.y + Math.sign(dy || 1) * hh
        }
      }
    }
  }

  /** 结算：room store 收到 result 消息后调用 */
  markGameOver(kind: 'dead' | 'survived') {
    this.world.gameOver = kind
  }

  destroy() {
    this.channel.stopPing()
    this.buf.length = 0
  }

  // ── 快照插值 ──

  private interpolate() {
    const buf = this.buf
    if (buf.length === 0) return
    const renderAt = performance.now() - INTERP_DELAY_MS
    // f0 = 最后一个不晚于渲染时刻的帧，f1 = 其后一帧；更早的旧帧直接丢弃，缓冲只留插值窗口不囤积
    let f0 = buf[0]
    let f1 = buf[buf.length - 1]
    for (let i = buf.length - 1; i >= 0; i--) {
      if (buf[i].at <= renderAt) {
        f0 = buf[i]
        f1 = buf[Math.min(i + 1, buf.length - 1)]
        if (i > 0) buf.splice(0, i)
        break
      }
    }
    const span = f1.at - f0.at
    const alpha = f0 === f1 || span <= 0 ? 1 : clamp((renderAt - f0.at) / span, 0, 1)
    this.applySnapshot(f0.s, f1.s, alpha)
  }

  private applySnapshot(a: SnapshotMsg, b: SnapshotMsg, alpha: number) {
    const world = this.world
    world.time = (a.tm + (b.tm - a.tm) * alpha) / 100

    // ── 玩家 + bot ──
    const prevPlayers = new Map(a.ps.map((p) => [p.i, p]))
    const seen = new Set<string>()
    for (const p of b.ps) {
      seen.add(p.i)
      let e = this.entities.get(p.i)
      if (!e) {
        e = makeEntity(p.i, p.n, p.c, { x: p.x / 100, y: p.y / 100 })
        this.entities.set(p.i, e)
      }
      const q = prevPlayers.get(p.i)
      e.pos.x = (q ? q.x + (p.x - q.x) * alpha : p.x) / 100
      e.pos.y = (q ? q.y + (p.y - q.y) * alpha : p.y) / 100
      e.face = { x: p.fx / 100, y: p.fy / 100 }
      e.name = p.n
      e.color = p.c
      e.hp = p.hp
      e.maxHp = p.mh
      e.mana = p.mp
      e.maxMana = p.mm
      e.speed = p.sp / 100
      e.defense = p.df
      e.squash = p.sq / 100
      e.moving = p.mv === 1
      e.rangeBonus = p.rb / 100
      e.hasteBonus = p.hb / 100
      e.score = (p.sc ?? 0) / 10
      e.invincibleUntil = p.iv === 1 ? world.time + 0.5 : 0
      // 本端实体：服务器位置与预测位置对账——偏差 <1 格平滑收敛，>1 格（击退/位移）硬纠正
      if (p.i === this.selfId && !this._selfDead && this.predicted) {
        const dx = e.pos.x - this.predicted.x
        const dy = e.pos.y - this.predicted.y
        if (Math.hypot(dx, dy) > 1) {
          this.predicted.x = e.pos.x
          this.predicted.y = e.pos.y
        } else {
          this.predicted.x += dx * 0.2
          this.predicted.y += dy * 0.2
        }
        e.pos.x = this.predicted.x
        e.pos.y = this.predicted.y
      }
    }
    // 离场实体清理（保留本端实体：阵亡后世界仍可读）
    for (const id of [...this.entities.keys()]) {
      if (!seen.has(id) && id !== this.selfId) this.entities.delete(id)
    }
    if (this.selfId) {
      const self = this.entities.get(this.selfId)
      if (self) {
        world.player = self
        // 首帧快照初始化预测起点（此后由 predict 推进、上面分支对账）
        if (!this.predicted && !this._selfDead) this.predicted = { x: self.pos.x, y: self.pos.y }
      }
      // 圈外判定（红晕 / 毒圈提示用，快照不带该字段）
      world.playerOutside =
        Math.hypot(world.player.pos.x - world.circle.center.x, world.player.pos.y - world.circle.center.y) >
        world.circle.radius
    }
    world.npcs = [...this.entities.values()].filter((e) => e.id !== this.selfId)

    // ── 怪物 ──
    const prevMonsters = new Map(a.ms.map((m) => [m.i, m]))
    world.monsters = b.ms.map((m) => {
      const q = prevMonsters.get(m.i)
      return {
        id: m.i,
        kind: m.k,
        pos: { x: (q ? q.x + (m.x - q.x) * alpha : m.x) / 100, y: (q ? q.y + (m.y - q.y) * alpha : m.y) / 100 },
        dir: { x: m.dx / 100, y: m.dy / 100 },
        radius: m.k === 'soe' ? 0.8 : 0.6,
        hp: m.hp,
        maxHp: m.mh,
      }
    })

    // ── 子弹 ──
    const prevProjectiles = new Map(a.pr.map((p) => [p.i, p]))
    world.projectiles = b.pr.map((p) => {
      const q = prevProjectiles.get(p.i)
      return {
        id: p.i,
        owner: '',
        ownerName: '',
        pos: { x: (q ? q.x + (p.x - q.x) * alpha : p.x) / 100, y: (q ? q.y + (p.y - q.y) * alpha : p.y) / 100 },
        dir: { x: p.dx / 100, y: p.dy / 100 },
        life: 1,
      }
    })

    // ── 道具 ──
    world.items = b.it.map((it) => {
      const meta = ITEM_META.find((m) => m.kind === it.k)
      return {
        id: it.i,
        kind: it.k,
        pos: { x: it.x / 100, y: it.y / 100 },
        icon: meta?.icon ?? '?',
        label: meta?.label ?? '',
      }
    })

    // ── 安全圈 ──
    const ci = b.ci
    const qa = a.ci
    const c = world.circle
    c.center.x = (qa.cx + (ci.cx - qa.cx) * alpha) / 100
    c.center.y = (qa.cy + (ci.cy - qa.cy) * alpha) / 100
    c.radius = (qa.r + (ci.r - qa.r) * alpha) / 100
    c.targetCenter = ci.tx !== null && ci.ty !== null ? { x: ci.tx / 100, y: ci.ty / 100 } : null
    c.targetRadius = ci.tr !== null ? ci.tr / 100 : null
    c.stage = ci.st
    c.phase = ci.ph
    c.phaseStart = ci.ps / 100
    c.shrinkFromRadius = c.radius
    c.shrinkFromCenter = { ...c.center }

    // ── 浮动文字 / 轰炸区（整组覆盖，取新帧）──
    world.floats = (b.ft ?? []).map((f) => ({
      pos: { x: f.x / 100, y: f.y / 100 },
      text: f.tx,
      color: f.c,
      life: f.l / 100,
    }))
    world.bombs = (b.bz ?? []).map((z) => ({
      id: z.i,
      pos: { x: z.x / 100, y: z.y / 100 },
      radius: z.r / 100,
      explodeAt: z.ea / 100,
      animUntil: z.au / 100,
    }))
  }
}
