import { Camera, UNIT } from './camera'
import { InputController } from './input'
import { Renderer } from './renderer'
import {
  FINAL_IDLE,
  FINAL_SHRINK_TIME,
} from './driver'
import type { WorldDriver } from './driver'
import type { PlayerEntity, World } from './types'
import bgUrl from '@/assets/bg.jpg'
import npeUrl from '@/assets/npe.png'
import soeUrl from '@/assets/SOE.png'
import playerUrl from '@/assets/player.png'

export interface EngineOptions {
  canvas: HTMLCanvasElement
  /** 世界驱动：NetDriver（快照插值） */
  driver: WorldDriver
  isMobile: boolean
}

/**
 * 游戏引擎：rAF 循环 + driver 世界更新 + 相机 + 渲染。
 * 观战模式（D20，driver.selfId === null）：
 * - 默认全局自由视角：整图可见，滚轮以指针为中心缩放（0.5×–3× 整图比例），拖拽平移
 * - followEntity(id) 切跟随视角；被跟随者死亡/离场自动切回全局
 */
export class GameEngine {
  readonly input = new InputController()

  private driver: WorldDriver
  private world: World
  private canvas: HTMLCanvasElement
  private camera = new Camera()
  private renderer: Renderer
  private rafId = 0
  private lastTs = 0
  private isMobile: boolean
  private resizeObserver: ResizeObserver | null = null
  /** 观战跟随目标实体 id；null = 全局自由视角 */
  private followTarget: string | null = null
  private dragging = false
  private dragStart = { x: 0, y: 0, camX: 0, camY: 0 }
  /** 观战滚轮/拖拽监听是否已挂载（旁观者构造即挂；玩家阵亡后 enterSpectate 补挂） */
  private spectateInput = false
  /** 退出跟随回全局视角的过渡动画（0.4s ease-out） */
  private camTransition: { t: number; fromX: number; fromY: number; fromScale: number } | null = null

  /** 观战模式标志（HUD 据此隐藏个人面板）：旁观者，或本端实体已阵亡 */
  get spectating() {
    return this.driver.selfId === null || this.driver.selfDead
  }

  /** 本端实体是否已阵亡（GameView 轮询此值触发出局 UI 与观战切换） */
  get selfDead() {
    return this.driver.selfDead
  }

  constructor(opts: EngineOptions) {
    this.isMobile = opts.isMobile
    this.driver = opts.driver
    this.world = opts.driver.world
    this.canvas = opts.canvas
    this.renderer = new Renderer(opts.canvas)
    const bg = new Image()
    bg.onload = () => this.renderer.setBackground(bg)
    bg.src = bgUrl
    const npe = new Image()
    npe.onload = () => this.renderer.setNpeImage(npe)
    npe.src = npeUrl
    const soe = new Image()
    soe.onload = () => this.renderer.setSoeImage(soe)
    soe.src = soeUrl
    const playerImg = new Image()
    playerImg.onload = () => this.renderer.setPlayerImage(playerImg)
    playerImg.src = playerUrl
    window.addEventListener('resize', this.handleResize)
    document.addEventListener('fullscreenchange', this.handleResizeDelayed)
    if (typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(() => {
        this.handleResize()
      })
      this.resizeObserver.observe(opts.canvas)
    }
    this.camera.setWorldBounds(this.world.width * UNIT, this.world.height * UNIT)
    // 相机立即就位：玩家对准自己，观战对准地图中心（handleResize 内套整图缩放）
    this.camera.x = this.world.player.pos.x * UNIT
    this.camera.y = this.world.player.pos.y * UNIT
    this.handleResize()
    if (this.spectating) this.attachSpectateInput()
    this.input.attach(opts.canvas)
  }

  /** 挂载观战滚轮缩放/拖拽平移监听（幂等） */
  private attachSpectateInput() {
    if (this.spectateInput) return
    this.spectateInput = true
    this.canvas.addEventListener('wheel', this.onWheel, { passive: false })
    this.canvas.addEventListener('pointerdown', this.onPointerDown)
    this.canvas.addEventListener('pointermove', this.onPointerMove)
    this.canvas.addEventListener('pointerup', this.onPointerUp)
    this.canvas.addEventListener('pointercancel', this.onPointerUp)
  }

  /** 玩家阵亡后切入观战（D2 出局 UI 的【观战】/重连死亡默认进此）：停输入 + 平滑拉到全局视角 */
  enterSpectate() {
    if (this.driver.selfId === null) return // 本来就是旁观者
    this.input.enabled = false
    this.followTarget = null
    this.attachSpectateInput()
    this.startGlobalTransition()
  }

  /** HUD 读取用：当前玩家数值、局面与生命排行榜（缩圈节奏读 driver.settings，随房间规则动态化） */
  playerStats() {
    const p = this.world.player
    const c = this.world.circle
    const t = this.world.time
    const s = this.driver.settings
    const idleDur = c.stage === 0 ? s.firstIdle : c.targetRadius === 0 ? FINAL_IDLE : s.shrinkCooldown
    const remaining =
      c.phase === 'idle'
        ? c.targetRadius === null
          ? 0
          : Math.max(0, idleDur - (t - c.phaseStart))
        : Math.max(0, (c.targetRadius === 0 ? FINAL_SHRINK_TIME : s.shrinkTime) - (t - c.phaseStart))
    // 观战时哨兵实体不进榜单；积分制：榜单按积分降序
    const selfEntries = this.spectating
      ? []
      : [
          {
            id: p.id,
            name: p.name,
            color: p.color,
            hp: Math.max(0, Math.ceil(p.hp)),
            score: p.score ?? 0,
            self: true,
          },
        ]
    const others = this.world.npcs.map((n) => ({
      id: n.id,
      name: n.name,
      color: n.color,
      hp: Math.max(0, Math.ceil(n.hp)),
      score: n.score ?? 0,
      self: false,
    }))
    const leaderboard = [...selfEntries, ...others]
      .sort((a, b) => b.score - a.score || b.hp - a.hp)
      .slice(0, 10)
    const alive = [...selfEntries, ...others].filter((e) => e.hp > 0).sort((a, b) => b.score - a.score || b.hp - a.hp)
    return {
      hp: p.hp,
      maxHp: p.maxHp,
      mana: p.mana,
      maxMana: p.maxMana,
      /** 本端实时坐标（格，右下角 HUD 展示） */
      pos: { x: p.pos.x, y: p.pos.y },
      speed: p.speed,
      defense: p.defense,
      attackCd: Math.max(0.3, 0.6 - p.hasteBonus),
      range: 9 + p.rangeBonus,
      leaderboard,
      alive,
      feed: this.world.feed.slice(0, 6),
      outside: this.world.playerOutside,
      circle: {
        stage: c.stage,
        total: s.shrinkTargets.length,
        phase: c.phase,
        remaining,
        isFinal: c.targetRadius === 0,
      },
    }
  }

  /** 开启操作（玩家）；观战无输入可开 */
  enableInput() {
    if (this.driver.selfId !== null) this.input.enabled = true
  }

  /** 观战视角切换（D20）：传实体 id 跟随；传 null 平滑过渡回全局自由视角 */
  followEntity(id: string | null) {
    if (id === this.followTarget) return
    if (id) {
      this.followTarget = id
      this.camTransition = null
      // 跟随恢复正常玩家视野缩放——全局 fit 缩放下视口≥整张地图，
      // 相机被边界钳制钉在地图中心，follow 横向平移完全无效
      const viewWidth = (this.isMobile ? 26 : 40) * UNIT
      const vw = this.canvas.clientWidth || window.innerWidth
      this.camera.setFree(this.camera.x, this.camera.y, vw / viewWidth)
    } else if (this.followTarget) {
      this.followTarget = null
      this.startGlobalTransition()
    }
  }

  /** 当前跟随的实体 id（GameView 高亮用） */
  get following(): string | null {
    return this.followTarget
  }

  start() {
    this.lastTs = performance.now()
    const tick = (ts: number) => {
      const dt = Math.min((ts - this.lastTs) / 1000, 0.05) // 钳制大跳帧
      this.lastTs = ts

      if (this.driver.selfId !== null) {
        // 桌面：攻击摇杆无输入时，用鼠标世界坐标相对玩家位置算瞄准（修复屏幕中心假设造成的偏移）
        let aim = this.input.stickAimVector()
        if (!aim) {
          const m = this.input.mousePos
          if (m) {
            const wp = this.camera.screenToWorld(m.x, m.y)
            const dx = wp.x - this.world.player.pos.x * UNIT
            const dy = wp.y - this.world.player.pos.y * UNIT
            const len = Math.hypot(dx, dy)
            if (len > 8) aim = { x: dx / len, y: dy / len }
          }
        }
        this.input.setDesktopAim(aim)
      }
      this.driver.update(dt, this.input)

      if (this.spectating) {
        if (this.followTarget) {
          const e = this.findEntity(this.followTarget)
          if (e) {
            this.camera.follow({ x: e.pos.x * UNIT, y: e.pos.y * UNIT }, dt)
          } else {
            // 被跟随者死亡/离场 → 平滑过渡回全局
            this.followTarget = null
            this.startGlobalTransition()
          }
        } else if (this.camTransition) {
          // 退出跟随：0.4s ease-out 过渡回全局 fit 视角
          const tr = this.camTransition
          tr.t = Math.min(1, tr.t + dt / 0.4)
          const k = 1 - Math.pow(1 - tr.t, 3)
          this.camera.setFree(
            tr.fromX + ((this.world.width * UNIT) / 2 - tr.fromX) * k,
            tr.fromY + ((this.world.height * UNIT) / 2 - tr.fromY) * k,
            tr.fromScale + (this.fitScale() - tr.fromScale) * k,
          )
          if (tr.t >= 1) this.camTransition = null
        }
      } else {
        this.camera.follow({ x: this.world.player.pos.x * UNIT, y: this.world.player.pos.y * UNIT }, dt)
      }
      this.renderer.render(this.world, this.camera, ts / 1000)
      this.rafId = requestAnimationFrame(tick)
    }
    this.rafId = requestAnimationFrame(tick)
  }

  destroy() {
    cancelAnimationFrame(this.rafId)
    this.input.detach()
    if (this.spectateInput) {
      this.canvas.removeEventListener('wheel', this.onWheel)
      this.canvas.removeEventListener('pointerdown', this.onPointerDown)
      this.canvas.removeEventListener('pointermove', this.onPointerMove)
      this.canvas.removeEventListener('pointerup', this.onPointerUp)
      this.canvas.removeEventListener('pointercancel', this.onPointerUp)
      this.spectateInput = false
    }
    window.removeEventListener('resize', this.handleResize)
    document.removeEventListener('fullscreenchange', this.handleResizeDelayed)
    this.resizeObserver?.disconnect()
    this.resizeObserver = null
  }

  private findEntity(id: string): PlayerEntity | null {
    if (this.world.player.id === id) return this.world.player
    return this.world.npcs.find((n) => n.id === id) ?? null
  }

  /** 退出跟随回全局：从当前相机状态起 0.4s ease-out 过渡到整图 fit 视角 */
  private startGlobalTransition() {
    this.camTransition = { t: 0, fromX: this.camera.x, fromY: this.camera.y, fromScale: this.camera.scale }
  }

  /** 整图可见的缩放比例 */
  private fitScale() {
    const vw = this.canvas.clientWidth || window.innerWidth
    const vh = this.canvas.clientHeight || window.innerHeight
    return Math.min(vw / (this.world.width * UNIT), vh / (this.world.height * UNIT))
  }

  /** 观战滚轮缩放：以指针为中心，0.5×–3× 整图比例 */
  private onWheel = (e: WheelEvent) => {
    if (this.followTarget) return
    e.preventDefault()
    this.camTransition = null
    const rect = this.canvas.getBoundingClientRect()
    const mx = e.clientX - rect.left
    const my = e.clientY - rect.top
    const fit = this.fitScale()
    const next = Math.min(fit * 3, Math.max(fit * 0.5, this.camera.scale * (e.deltaY < 0 ? 1.12 : 1 / 1.12)))
    const before = this.camera.screenToWorld(mx, my)
    this.camera.setFree(this.camera.x, this.camera.y, next)
    const after = this.camera.screenToWorld(mx, my)
    this.camera.setFree(this.camera.x + before.x - after.x, this.camera.y + before.y - after.y, next)
  }

  /** 观战拖拽平移（全局自由视角） */
  private onPointerDown = (e: PointerEvent) => {
    if (this.followTarget || e.button !== 0) return
    this.camTransition = null
    this.dragging = true
    this.dragStart = { x: e.clientX, y: e.clientY, camX: this.camera.x, camY: this.camera.y }
    this.canvas.setPointerCapture(e.pointerId)
  }

  private onPointerMove = (e: PointerEvent) => {
    if (!this.dragging) return
    this.camera.setFree(
      this.dragStart.camX - (e.clientX - this.dragStart.x) / this.camera.scale,
      this.dragStart.camY - (e.clientY - this.dragStart.y) / this.camera.scale,
      this.camera.scale,
    )
  }

  private onPointerUp = () => {
    this.dragging = false
  }

  private handleResize = () => {
    const { w, h } = this.renderer.resize()
    if (w > 0 && h > 0) {
      this.camera.resize(w, h, this.isMobile)
      // 观战全局视角：整图可见（滚轮缩放以此为 1× 基准）
      if (this.spectating && !this.followTarget) {
        this.camTransition = null
        this.camera.setFree(this.camera.x, this.camera.y, this.fitScale())
      }
    }
  }

  private handleResizeDelayed = () => {
    this.handleResize()
    requestAnimationFrame(() => this.handleResize())
  }
}
