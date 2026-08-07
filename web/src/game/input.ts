import type { Vec2 } from './types'

const KEY_DIRS: Record<string, Vec2> = {
  KeyW: { x: 0, y: -1 },
  KeyS: { x: 0, y: 1 },
  KeyA: { x: -1, y: 0 },
  KeyD: { x: 1, y: 0 },
  ArrowUp: { x: 0, y: -1 },
  ArrowDown: { x: 0, y: 1 },
  ArrowLeft: { x: -1, y: 0 },
  ArrowRight: { x: 1, y: 0 },
}

/**
 * 输入汇总：
 * - 移动：虚拟摇杆（移动端）/ WASD（桌面）
 * - 瞄准：攻击摇杆（移动端，推住即瞄准+连发）/ 鼠标朝向（桌面）
 * - 开火：摇杆推住 / 鼠标按住左键 / 空格按住
 */
export class InputController {
  private _enabled = false

  get enabled() {
    return this._enabled
  }

  set enabled(val: boolean) {
    this._enabled = val
    if (!val) {
      this.resetInputState()
    }
  }

  private keys = new Set<string>()
  private joystick: Vec2 = { x: 0, y: 0 }
  private aim: Vec2 = { x: 0, y: 0 }
  /** 桌面鼠标瞄准（引擎每帧按鼠标世界坐标写入；不参与 firing 判定） */
  private desktopAim: Vec2 | null = null
  private mouse: Vec2 | null = null
  private mouseDown = false
  private emoteQueued = false
  private canvas: HTMLCanvasElement | null = null

  /** 重置所有输入状态，防止切屏/失焦/禁用导致按键或鼠标连发挂起 */
  resetInputState = () => {
    this.keys.clear()
    this.mouseDown = false
    this.joystick = { x: 0, y: 0 }
    this.aim = { x: 0, y: 0 }
    this.desktopAim = null
    this.emoteQueued = false
  }

  private onKeyDown = (e: KeyboardEvent) => {
    if (!this.enabled) return
    if (e.code in KEY_DIRS) this.keys.add(e.code)
    if (e.code === 'KeyE') this.emoteQueued = true
  }

  private onKeyUp = (e: KeyboardEvent) => {
    this.keys.delete(e.code)
  }

  private onWindowBlur = () => {
    this.resetInputState()
  }

  private onWindowFocus = () => {
    this.resetInputState()
  }

  private onVisibilityChange = () => {
    if (document.hidden) {
      this.resetInputState()
    }
  }

  private onContextMenu = (e: MouseEvent) => {
    e.preventDefault()
  }

  private onMouseMove = (e: MouseEvent) => {
    if (!this.canvas) return
    const rect = this.canvas.getBoundingClientRect()
    this.mouse = { x: e.clientX - rect.left, y: e.clientY - rect.top }
  }

  private onMouseDown = (e: MouseEvent) => {
    if (!this.enabled || e.button !== 0) return
    this.mouseDown = true
  }

  private onMouseUp = (e: MouseEvent) => {
    if (e.button === 0) this.mouseDown = false
  }

  attach(canvas?: HTMLCanvasElement) {
    window.addEventListener('keydown', this.onKeyDown)
    window.addEventListener('keyup', this.onKeyUp)
    window.addEventListener('blur', this.onWindowBlur)
    window.addEventListener('focus', this.onWindowFocus)
    document.addEventListener('visibilitychange', this.onVisibilityChange)
    window.addEventListener('contextmenu', this.onContextMenu)
    if (canvas) {
      this.canvas = canvas
      window.addEventListener('mousemove', this.onMouseMove)
      canvas.addEventListener('mousedown', this.onMouseDown)
      window.addEventListener('mouseup', this.onMouseUp)
    }
  }

  detach() {
    this.resetInputState()
    window.removeEventListener('keydown', this.onKeyDown)
    window.removeEventListener('keyup', this.onKeyUp)
    window.removeEventListener('blur', this.onWindowBlur)
    window.removeEventListener('focus', this.onWindowFocus)
    document.removeEventListener('visibilitychange', this.onVisibilityChange)
    window.removeEventListener('contextmenu', this.onContextMenu)
    window.removeEventListener('mousemove', this.onMouseMove)
    this.canvas?.removeEventListener('mousedown', this.onMouseDown)
    window.removeEventListener('mouseup', this.onMouseUp)
  }

  setJoystick(v: Vec2) {
    this.joystick = v
  }

  /** 攻击摇杆：非零 = 瞄准中（同时触发连发） */
  setAim(v: Vec2) {
    this.aim = v
  }

  /** 桌面鼠标瞄准：引擎在摇杆无输入时写入，driver 经 aimVector() 统一读取 */
  setDesktopAim(v: Vec2 | null) {
    this.desktopAim = v
  }

  /** 仅攻击摇杆的瞄准（引擎判定优先级用，不含桌面鼠标） */
  stickAimVector(): Vec2 | null {
    if (!this.enabled) return null
    if (this.aim.x !== 0 || this.aim.y !== 0) return this.aim
    return null
  }

  queueEmote() {
    if (this.enabled) this.emoteQueued = true
  }

  /** 当前移动向量（已归一化），摇杆优先 */
  moveVector(): Vec2 {
    if (!this.enabled) return { x: 0, y: 0 }
    const j = this.joystick
    if (j.x !== 0 || j.y !== 0) return j
    let x = 0
    let y = 0
    for (const code of this.keys) {
      const d = KEY_DIRS[code]
      if (d) {
        x += d.x
        y += d.y
      }
    }
    if (x === 0 && y === 0) return { x: 0, y: 0 }
    const len = Math.hypot(x, y)
    return { x: x / len, y: y / len }
  }

  /** 当前瞄准方向：攻击摇杆优先，其次桌面鼠标（由引擎写入） */
  aimVector(): Vec2 | null {
    if (!this.enabled) return null
    if (this.aim.x !== 0 || this.aim.y !== 0) return this.aim
    return this.desktopAim
  }

  /** 鼠标在画布内的 CSS 坐标（未移动过 = null） */
  get mousePos(): Vec2 | null {
    return this.mouse
  }

  /** 是否在开火（按住语义，冷却由世界模拟控制）：摇杆推住 / 鼠标左键 */
  firing(): boolean {
    if (!this.enabled) return false
    return this.aim.x !== 0 || this.aim.y !== 0 || this.mouseDown
  }

  consumeEmote(): boolean {
    const v = this.emoteQueued
    this.emoteQueued = false
    return v
  }
}
