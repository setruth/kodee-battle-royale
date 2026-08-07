import type { Vec2 } from './types'

/** 1 格 = 32px（scale 1 时） */
export const UNIT = 32

/**
 * 跟随相机：指数平滑跟随目标 + 世界边界钳制。
 * 纯 2D 平移 + 缩放。
 */
export class Camera {
  x = 0
  y = 0
  scale = 1

  private vw = 0
  private vh = 0
  private worldW = Number.POSITIVE_INFINITY
  private worldH = Number.POSITIVE_INFINITY

  resize(vw: number, vh: number, isMobile: boolean) {
    this.vw = vw
    this.vh = vh
    if (isMobile) {
      // 移动端/横屏：兼顾宽高，确保垂直视野至少 18 格、水平视野至少 30 格，避免画面放大过大
      const scaleH = vh / (18 * UNIT)
      const scaleW = vw / (30 * UNIT)
      this.scale = Math.min(scaleH, scaleW)
    } else {
      const viewWidth = 40 * UNIT
      this.scale = vw / viewWidth
    }
  }

  /** 世界像素尺寸（用于边界钳制） */
  setWorldBounds(wPx: number, hPx: number) {
    this.worldW = wPx
    this.worldH = hPx
  }

  /** 指数平滑跟随，dt 秒；钳制在世界边界内（视口大于世界时居中） */
  follow(target: Vec2, dt: number) {
    const k = 1 - Math.exp(-8 * dt)
    this.x += (target.x - this.x) * k
    this.y += (target.y - this.y) * k
    if (this.vw > 0) {
      const halfW = this.viewportWorldW / 2
      const halfH = this.viewportWorldH / 2
      this.x = this.worldW <= this.viewportWorldW ? this.worldW / 2 : clamp(this.x, halfW, this.worldW - halfW)
      this.y = this.worldH <= this.viewportWorldH ? this.worldH / 2 : clamp(this.y, halfH, this.worldH - halfH)
    }
  }

  /** 自由视角直接定位（观战拖拽/缩放用）：设缩放与位置，沿用世界边界钳制 */
  setFree(x: number, y: number, scale: number) {
    this.scale = scale
    this.x = x
    this.y = y
    if (this.vw > 0) {
      const halfW = this.viewportWorldW / 2
      const halfH = this.viewportWorldH / 2
      this.x = this.worldW <= this.viewportWorldW ? this.worldW / 2 : clamp(this.x, halfW, this.worldW - halfW)
      this.y = this.worldH <= this.viewportWorldH ? this.worldH / 2 : clamp(this.y, halfH, this.worldH - halfH)
    }
  }

  /** 视口在世界坐标（px）下的尺寸 */
  get viewportWorldW() {
    return this.vw / this.scale
  }

  /** 屏幕 CSS 坐标 → 世界像素坐标（鼠标瞄准用） */
  screenToWorld(sx: number, sy: number): Vec2 {
    return {
      x: this.x + (sx - this.vw / 2) / this.scale,
      y: this.y + (sy - this.vh / 2) / this.scale,
    }
  }

  get viewportWorldH() {
    return this.vh / this.scale
  }

  /** 将 ctx 变换到世界坐标系（此后按世界坐标绘制即可） */
  apply(ctx: CanvasRenderingContext2D) {
    ctx.translate(this.vw / 2, this.vh / 2)
    ctx.scale(this.scale, this.scale)
    ctx.translate(-this.x, -this.y)
  }
}

function clamp(v: number, min: number, max: number) {
  return v < min ? min : v > max ? max : v
}
