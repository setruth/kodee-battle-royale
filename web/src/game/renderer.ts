import { UNIT, Camera } from './camera'
import { SOE_AURA } from './driver'
import { ITEM_META } from './items'
import type { PlayerEntity, World } from './types'

const COLORS = {
  floor: '#17142a',
  grid: 'rgba(127, 82, 255, 0.10)',
  border: '#7f52ff',
  shadow: 'rgba(0, 0, 0, 0.35)',
  npe: '#e24462',
  soe: '#0095d5',
  name: '#ece9f7',
  bullet: '#ff5a6e',
  outside: 'rgba(127, 82, 255, 0.16)', // 毒圈扫过：轻微紫色半透明遮罩
}

/** 道具配色（主地图图标 + 小地图点共用） */
const ITEM_COLORS: Record<string, string> = Object.fromEntries(ITEM_META.map((m) => [m.kind, m.color]))

/**
 * Canvas 2D 渲染器：地板网格 / 缩圈 / 道具 / 怪物 / 子弹 / 玩家（伪 2.5D）/ 小地图。
 * 每帧清空重画整个世界；相机只是坐标变换，小地图是同一份数据按小比例画第二遍。
 */
export class Renderer {
  private ctx: CanvasRenderingContext2D
  private bg: HTMLImageElement | null = null
  private npe: HTMLImageElement | null = null
  private soe: HTMLImageElement | null = null
  private playerImg: HTMLImageElement | null = null
  /** 渲染动画时钟（暂停时世界冻结，但浮动/闪烁等视觉动画继续走） */
  private animT = 0

  constructor(private canvas: HTMLCanvasElement) {
    this.ctx = canvas.getContext('2d')!
  }

  /** 设置地板背景图（未加载完成前用纯色兜底） */
  setBackground(img: HTMLImageElement) {
    this.bg = img
  }

  /** 设置 NPE 怪物形象图 */
  setNpeImage(img: HTMLImageElement) {
    this.npe = img
  }

  /** 设置 SOE 怪物形象图 */
  setSoeImage(img: HTMLImageElement) {
    this.soe = img
  }

  /** 设置玩家形象图（Kotlin 吉祥物） */
  setPlayerImage(img: HTMLImageElement) {
    this.playerImg = img
  }

  /** 按 devicePixelRatio 适配，返回 CSS 像素尺寸 */
  resize(): { w: number; h: number } {
    const dpr = window.devicePixelRatio || 1
    const w = this.canvas.clientWidth || window.innerWidth
    const h = this.canvas.clientHeight || window.innerHeight
    if (w <= 0 || h <= 0) return { w: 0, h: 0 }

    const targetW = Math.floor(w * dpr)
    const targetH = Math.floor(h * dpr)

    if (this.canvas.width !== targetW || this.canvas.height !== targetH) {
      this.canvas.width = targetW
      this.canvas.height = targetH
    }
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    return { w, h }
  }

  render(world: World, cam: Camera, animT: number) {
    this.animT = animT
    const { ctx } = this
    const w = this.canvas.clientWidth
    const h = this.canvas.clientHeight

    ctx.save()
    ctx.clearRect(0, 0, w, h)
    ctx.fillStyle = COLORS.floor
    ctx.fillRect(0, 0, w, h)

    cam.apply(ctx)
    this.drawFloor(world)
    this.drawCircle(world)
    this.drawObstacles(world)
    this.drawBombs(world)
    this.drawItems(world)
    this.drawMonsters(world)
    this.drawProjectiles(world)
    this.drawNpcs(world)
    this.drawPlayer(world)
    this.drawFloats(world)
    ctx.restore()

    // 圈外红晕（屏幕空间）
    if (world.playerOutside && !world.gameOver) this.drawVignette(w, h)
    this.drawMinimap(world, cam)
  }

  private drawFloor(world: World) {
    const { ctx } = this
    const w = world.width * UNIT
    const h = world.height * UNIT

    if (this.bg) {
      // 矩形世界 16:9，整张背景图完整映射
      ctx.drawImage(this.bg, 0, 0, w, h)
    } else {
      ctx.strokeStyle = COLORS.grid
      ctx.lineWidth = 1
      ctx.beginPath()
      for (let x = 0; x <= world.width; x += 4) {
        ctx.moveTo(x * UNIT, 0)
        ctx.lineTo(x * UNIT, h)
      }
      for (let y = 0; y <= world.height; y += 4) {
        ctx.moveTo(0, y * UNIT)
        ctx.lineTo(w, y * UNIT)
      }
      ctx.stroke()
    }
  }

  /** 安全圈：圈外压暗 + 当前圈实线 + 收缩目标虚线 */
  private drawCircle(world: World) {
    const { ctx } = this
    const c = world.circle
    const cx = c.center.x * UNIT
    const cy = c.center.y * UNIT
    const mapW = world.width * UNIT
    const mapH = world.height * UNIT

    // 圈外区域压暗（外方内圆，evenodd 挖洞）
    ctx.save()
    ctx.beginPath()
    ctx.rect(0, 0, mapW, mapH)
    ctx.arc(cx, cy, c.radius * UNIT, 0, Math.PI * 2, true)
    ctx.fillStyle = COLORS.outside
    ctx.fill('evenodd')
    ctx.restore()

    // 当前圈
    ctx.strokeStyle = COLORS.border
    ctx.lineWidth = 3
    ctx.beginPath()
    ctx.arc(cx, cy, c.radius * UNIT, 0, Math.PI * 2)
    ctx.stroke()

    // 收缩目标预警（虚线圆；最终塌缩画中心点标记）
    if (c.targetRadius !== null && c.targetCenter !== null) {
      ctx.strokeStyle = 'rgba(255, 90, 110, 0.8)'
      ctx.lineWidth = 2
      if (c.targetRadius > 0) {
        ctx.setLineDash([10, 8])
        ctx.beginPath()
        ctx.arc(c.targetCenter.x * UNIT, c.targetCenter.y * UNIT, c.targetRadius * UNIT, 0, Math.PI * 2)
        ctx.stroke()
        ctx.setLineDash([])
      } else {
        const tx = c.targetCenter.x * UNIT
        const ty = c.targetCenter.y * UNIT
        ctx.beginPath()
        ctx.moveTo(tx - 10, ty)
        ctx.lineTo(tx + 10, ty)
        ctx.moveTo(tx, ty - 10)
        ctx.lineTo(tx, ty + 10)
        ctx.stroke()
      }
    }
  }

  /** 轰炸区：预警（虚线圆 + 扇形充能）→ 爆炸（扩散环 + 光） */
  private drawBombs(world: World) {
    const { ctx } = this
    for (const b of world.bombs) {
      const x = b.pos.x * UNIT
      const y = b.pos.y * UNIT
      const r = b.radius * UNIT
      if (b.animUntil === 0) {
        // 预警：2.5s 扇形充能
        const progress = Math.min(1, 1 - (b.explodeAt - world.time) / 2.5)
        ctx.fillStyle = 'rgba(255, 90, 60, 0.10)'
        ctx.beginPath()
        ctx.arc(x, y, r, 0, Math.PI * 2)
        ctx.fill()
        ctx.fillStyle = 'rgba(255, 90, 60, 0.25)'
        ctx.beginPath()
        ctx.moveTo(x, y)
        ctx.arc(x, y, r, -Math.PI / 2, -Math.PI / 2 + progress * Math.PI * 2)
        ctx.closePath()
        ctx.fill()
        ctx.strokeStyle = 'rgba(255, 90, 60, 0.85)'
        ctx.lineWidth = 2
        ctx.setLineDash([8, 6])
        ctx.beginPath()
        ctx.arc(x, y, r, 0, Math.PI * 2)
        ctx.stroke()
        ctx.setLineDash([])
      } else {
        // 爆炸动画 0.6s：扩散环 + 衰减光晕
        const k = Math.min(1, 1 - (b.animUntil - world.time) / 0.6)
        ctx.save()
        ctx.globalCompositeOperation = 'lighter'
        ctx.fillStyle = `rgba(255, 160, 64, ${0.5 * (1 - k)})`
        ctx.beginPath()
        ctx.arc(x, y, r, 0, Math.PI * 2)
        ctx.fill()
        ctx.strokeStyle = `rgba(255, 200, 120, ${1 - k})`
        ctx.lineWidth = 3
        ctx.beginPath()
        ctx.arc(x, y, r * (0.4 + k), 0, Math.PI * 2)
        ctx.stroke()
        ctx.restore()
      }
    }
  }

  private drawObstacles(world: World) {
    const { ctx } = this
    for (const o of world.obstacles) {
      const x = o.pos.x * UNIT
      const y = o.pos.y * UNIT
      if (o.kind === 'boulder') {
        // 代码块巨石：圆角矩形 + { } 符号
        const w = o.w * UNIT
        const h = o.h * UNIT
        this.drawShadow(x, y + h * 0.1, Math.max(w, h) * 0.45)
        ctx.fillStyle = '#3a3550'
        ctx.beginPath()
        ctx.roundRect(x - w / 2, y - h / 2, w, h, 6)
        ctx.fill()
        ctx.fillStyle = '#4d4668' // 顶面高光
        ctx.beginPath()
        ctx.roundRect(x - w / 2 + 3, y - h / 2 + 3, w - 6, h * 0.4, 4)
        ctx.fill()
        ctx.fillStyle = 'rgba(230, 226, 247, 0.5)'
        ctx.font = `bold ${Math.min(w, h) * 0.5}px monospace`
        ctx.textAlign = 'center'
        ctx.textBaseline = 'middle'
        ctx.fillText('{ }', x, y + 1)
      } else {
        // TODO() 石柱：圆柱 + TODO 字样
        const r = o.radius * UNIT
        this.drawShadow(x, y, r)
        ctx.fillStyle = '#4a3f5e'
        ctx.beginPath()
        ctx.arc(x, y, r, 0, Math.PI * 2)
        ctx.fill()
        ctx.fillStyle = '#5d5078' // 柱顶椭圆高光
        ctx.beginPath()
        ctx.ellipse(x, y - r * 0.25, r * 0.85, r * 0.5, 0, 0, Math.PI * 2)
        ctx.fill()
        ctx.fillStyle = 'rgba(230, 226, 247, 0.55)'
        ctx.font = `bold ${Math.max(8, r * 0.45)}px monospace`
        ctx.textAlign = 'center'
        ctx.textBaseline = 'middle'
        ctx.fillText('TODO', x, y - r * 0.1)
      }
    }
  }

  private drawItems(world: World) {
    const { ctx } = this
    for (const it of world.items) {
      const x = it.pos.x * UNIT
      const y = it.pos.y * UNIT
      const bob = Math.sin(this.animT * 3 + it.id) * 3
      const color = ITEM_COLORS[it.kind] ?? '#a78bfa'
      ctx.save()
      ctx.globalCompositeOperation = 'lighter'
      ctx.fillStyle = color + '40' // 25% 透明光晕
      ctx.beginPath()
      ctx.arc(x, y + bob, 16, 0, Math.PI * 2)
      ctx.fill()
      ctx.restore()
      ctx.fillStyle = color
      ctx.font = 'bold 14px sans-serif'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText(it.icon, x, y + bob)
    }
  }

  private drawMonsters(world: World) {
    const { ctx } = this
    for (const m of world.monsters) {
      const x = m.pos.x * UNIT
      const y = m.pos.y * UNIT
      const r = m.radius * UNIT

      // SOE 减速光环（常驻 telegraph）
      if (m.kind === 'soe') {
        ctx.fillStyle = 'rgba(0, 149, 213, 0.12)'
        ctx.beginPath()
        ctx.arc(x, y, SOE_AURA * UNIT, 0, Math.PI * 2)
        ctx.fill()
      }

      this.drawShadow(x, y, r)
      if (m.kind === 'npe' && this.npe) {
        // NPE 形象图（透明背景，700:920）
        const iw = r * 2.2
        const ih = (iw * 920) / 700
        ctx.drawImage(this.npe, x - iw / 2, y - ih / 2 - r * 0.2, iw, ih)
      } else if (m.kind === 'soe' && this.soe) {
        // SOE 形象图（透明背景，正方形）
        const iw = r * 2.4
        ctx.drawImage(this.soe, x - iw / 2, y - iw / 2 - r * 0.2, iw, iw)
      } else {
        ctx.fillStyle = m.kind === 'npe' ? COLORS.npe : COLORS.soe
        ctx.beginPath()
        ctx.arc(x, y, r, 0, Math.PI * 2)
        ctx.fill()
      }

      if (m.kind === 'npe') {
        if (!this.npe) {
          ctx.fillStyle = '#fff'
          ctx.font = 'bold 10px sans-serif'
          ctx.textAlign = 'center'
          ctx.textBaseline = 'middle'
          ctx.fillText('NPE', x, y)
        }
      } else if (!this.soe) {
        // SOE 无图兜底：旋转的递归漩涡（阿基米德螺线）
        ctx.strokeStyle = 'rgba(255, 255, 255, 0.9)'
        ctx.lineWidth = 1.5
        ctx.beginPath()
        const steps = 36
        for (let i = 0; i <= steps; i++) {
          const th = (i / steps) * Math.PI * 5 + this.animT * 2
          const rr = (i / steps) * r * 0.7
          const px = x + Math.cos(th) * rr
          const py = y + Math.sin(th) * rr
          if (i === 0) ctx.moveTo(px, py)
          else ctx.lineTo(px, py)
        }
        ctx.stroke()
      }

      // 头顶血条
      const bw = Math.max(26, r * 1.8)
      const bh = 3
      const bx = x - bw / 2
      const by = y - r - 9
      const ratio = Math.max(0, m.hp / m.maxHp)
      ctx.fillStyle = 'rgba(0, 0, 0, 0.55)'
      ctx.fillRect(bx, by, bw, bh)
      ctx.fillStyle = ratio > 0.4 ? '#2ecc71' : '#ff5a6e'
      ctx.fillRect(bx, by, bw * ratio, bh)
    }
  }

  private drawProjectiles(world: World) {
    const { ctx } = this
    for (const pr of world.projectiles) {
      const x = pr.pos.x * UNIT
      const y = pr.pos.y * UNIT
      // 发光弹丸：外层光晕 + 白色弹芯
      ctx.save()
      ctx.globalCompositeOperation = 'lighter'
      const g = ctx.createRadialGradient(x, y, 0, x, y, 10)
      g.addColorStop(0, 'rgba(255, 90, 110, 0.9)')
      g.addColorStop(1, 'rgba(255, 90, 110, 0)')
      ctx.fillStyle = g
      ctx.beginPath()
      ctx.arc(x, y, 10, 0, Math.PI * 2)
      ctx.fill()
      ctx.restore()
      ctx.fillStyle = '#fff'
      ctx.beginPath()
      ctx.arc(x, y, 3, 0, Math.PI * 2)
      ctx.fill()
    }
  }

  private drawCharacter(world: World, p: PlayerEntity) {
    const { ctx } = this
    const x = p.pos.x * UNIT
    const y = p.pos.y * UNIT
    const r = p.radius * UNIT
    const invincible = world.time < p.invincibleUntil

    this.drawShadow(x, y, r)

    ctx.save()
    // 受击保护/无敌时闪烁
    if (invincible) ctx.globalAlpha = 0.55 + 0.45 * Math.abs(Math.sin(this.animT * 12))

    if (this.playerImg) {
      // Kotlin 吉祥物（全员）：移动时上下跳跃浮动；默认朝左，向右走时水平翻转
      const img = this.playerImg
      const w = r * 2.6
      const h = (w * img.height) / img.width
      const bob = p.moving ? Math.abs(Math.sin(world.time * 10)) * r * 0.35 : 0
      ctx.translate(x, y + r * 0.8 - bob)
      ctx.scale(2 - p.squash, p.squash)
      if (p.face.x > 0.05) ctx.scale(-1, 1) // 图片默认朝左，向右走翻转
      ctx.drawImage(img, -w / 2, -h, w, h)
      ctx.restore()
    } else {
      ctx.translate(x, y)
      ctx.scale(2 - p.squash, p.squash)
      ctx.fillStyle = p.color
      ctx.beginPath()
      ctx.roundRect(-r * 0.75, -r * 1.4, r * 1.5, r * 2.1, r * 0.6)
      ctx.fill()

      const ex = p.face.x * r * 0.28
      const ey = p.face.y * r * 0.28 - r * 0.6
      ctx.fillStyle = '#fff'
      ctx.beginPath()
      ctx.arc(ex - r * 0.22, ey, r * 0.2, 0, Math.PI * 2)
      ctx.arc(ex + r * 0.22, ey, r * 0.2, 0, Math.PI * 2)
      ctx.fill()
      ctx.fillStyle = '#1b1829'
      ctx.beginPath()
      ctx.arc(ex - r * 0.22 + p.face.x * 2, ey + p.face.y * 2, r * 0.1, 0, Math.PI * 2)
      ctx.arc(ex + r * 0.22 + p.face.x * 2, ey + p.face.y * 2, r * 0.1, 0, Math.PI * 2)
      ctx.fill()
      ctx.restore()
    }

    // 护盾：每层一圈
    for (let i = 0; i < p.defense; i++) {
      ctx.strokeStyle = 'rgba(127, 184, 255, 0.8)'
      ctx.lineWidth = 2
      ctx.beginPath()
      ctx.arc(x, y - r * 0.4, r * 1.5 + i * 5, 0, Math.PI * 2)
      ctx.stroke()
    }

    // 名字：玩家颜色 + 深色描边，全员吉祥物时靠它区分（加大字号保证远处可读）
    ctx.font = 'bold 16px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'bottom'
    ctx.lineWidth = 4
    ctx.strokeStyle = 'rgba(10, 8, 18, 0.95)'
    ctx.strokeText(p.name, x, y - r * 1.7)
    ctx.fillStyle = p.color
    ctx.fillText(p.name, x, y - r * 1.7)
  }

  private drawNpcs(world: World) {
    for (const npc of world.npcs) this.drawCharacter(world, npc)
  }

  private drawPlayer(world: World) {
    // 观战哨兵实体（id='spectate'）不绘制
    if (world.player.id === 'spectate') return
    this.drawCharacter(world, world.player)
  }

  private drawFloats(world: World) {
    const { ctx } = this
    for (const f of world.floats) {
      ctx.save()
      ctx.globalAlpha = Math.min(1, f.life)
      ctx.fillStyle = f.color
      ctx.font = 'bold 15px sans-serif'
      ctx.textAlign = 'center'
      ctx.fillText(f.text, f.pos.x * UNIT, f.pos.y * UNIT - (1.5 - f.life) * 20)
      ctx.restore()
    }
  }

  private drawVignette(w: number, h: number) {
    const { ctx } = this
    const g = ctx.createRadialGradient(w / 2, h / 2, Math.min(w, h) * 0.35, w / 2, h / 2, Math.max(w, h) * 0.7)
    g.addColorStop(0, 'rgba(255, 40, 60, 0)')
    g.addColorStop(1, 'rgba(255, 40, 60, 0.35)')
    ctx.fillStyle = g
    ctx.fillRect(0, 0, w, h)
  }

  /** 右上角小地图：矩形全图总览（背景图 + 安全圈 + 实体点 + 视口框） */
  private drawMinimap(world: World, cam: Camera) {
    const { ctx } = this
    const vw = this.canvas.clientWidth
    const aspect = world.height / world.width
    const w = Math.max(120, Math.min(200, vw * 0.22))
    const h = w * aspect
    const pad = 12
    const x0 = vw - w - pad
    const y0 = pad
    const kx = w / (world.width * UNIT)
    const ky = h / (world.height * UNIT)

    ctx.save()
    ctx.beginPath()
    ctx.roundRect(x0, y0, w, h, 6)
    ctx.clip()
    if (this.bg) {
      ctx.globalAlpha = 0.85
      ctx.drawImage(this.bg, x0, y0, w, h)
      ctx.globalAlpha = 1
    } else {
      ctx.fillStyle = 'rgba(18, 16, 28, 0.85)'
      ctx.fillRect(x0, y0, w, h)
    }

    // 安全圈（真实半径）+ 目标圈
    ctx.strokeStyle = COLORS.border
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.arc(x0 + world.circle.center.x * UNIT * kx, y0 + world.circle.center.y * UNIT * ky, world.circle.radius * UNIT * kx, 0, Math.PI * 2)
    ctx.stroke()
    if (world.circle.targetRadius !== null && world.circle.targetCenter !== null) {
      ctx.strokeStyle = 'rgba(255, 90, 110, 0.9)'
      ctx.setLineDash([4, 3])
      ctx.beginPath()
      ctx.arc(x0 + world.circle.targetCenter.x * UNIT * kx, y0 + world.circle.targetCenter.y * UNIT * ky, world.circle.targetRadius * UNIT * kx, 0, Math.PI * 2)
      ctx.stroke()
      ctx.setLineDash([])
    }

    // 障碍物（灰点）
    ctx.fillStyle = 'rgba(120, 112, 150, 0.8)'
    for (const o of world.obstacles) {
      const s = o.kind === 'boulder' ? Math.max(o.w, o.h) : o.radius * 2
      ctx.fillRect(x0 + o.pos.x * UNIT * kx - s / 2, y0 + o.pos.y * UNIT * ky - s / 2, s, s)
    }

    // 轰炸区（红圈：预警 / 爆炸）
    for (const b of world.bombs) {
      ctx.strokeStyle = b.animUntil === 0 ? 'rgba(255, 90, 110, 0.9)' : '#ffb340'
      ctx.lineWidth = 1
      ctx.beginPath()
      ctx.arc(x0 + b.pos.x * UNIT * kx, y0 + b.pos.y * UNIT * ky, b.radius * UNIT * kx, 0, Math.PI * 2)
      ctx.stroke()
    }

    for (const it of world.items) {
      ctx.fillStyle = ITEM_COLORS[it.kind] ?? '#a78bfa'
      ctx.fillRect(x0 + it.pos.x * UNIT * kx - 1, y0 + it.pos.y * UNIT * ky - 1, 2.5, 2.5)
    }

    for (const m of world.monsters) {
      ctx.fillStyle = '#e24462' // 怪物统一红：与彩色玩家、蓝色道具区分
      ctx.fillRect(x0 + m.pos.x * UNIT * kx - 1, y0 + m.pos.y * UNIT * ky - 1, 2.5, 2.5)
    }

    // NPC（自身颜色点）
    for (const npc of world.npcs) {
      ctx.fillStyle = npc.color
      ctx.fillRect(x0 + npc.pos.x * UNIT * kx - 1.5, y0 + npc.pos.y * UNIT * ky - 1.5, 3, 3)
    }

    const p = world.player
    if (p.id !== 'spectate') {
      ctx.fillStyle = '#fff'
      ctx.beginPath()
      ctx.arc(x0 + p.pos.x * UNIT * kx, y0 + p.pos.y * UNIT * ky, 3, 0, Math.PI * 2)
      ctx.fill()
    }

    ctx.strokeStyle = 'rgba(255, 255, 255, 0.5)'
    ctx.strokeRect(
      x0 + (cam.x - cam.viewportWorldW / 2) * kx,
      y0 + (cam.y - cam.viewportWorldH / 2) * ky,
      cam.viewportWorldW * kx,
      cam.viewportWorldH * ky,
    )
    ctx.restore()

    ctx.strokeStyle = 'rgba(127, 82, 255, 0.5)'
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.roundRect(x0, y0, w, h, 6)
    ctx.stroke()
  }

  private drawShadow(x: number, y: number, r: number) {
    const { ctx } = this
    ctx.fillStyle = COLORS.shadow
    ctx.beginPath()
    ctx.ellipse(x, y + r * 0.85, r * 0.9, r * 0.35, 0, 0, Math.PI * 2)
    ctx.fill()
  }
}
