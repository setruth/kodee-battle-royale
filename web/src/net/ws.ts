import { wsUrl } from '@/api/config'

type Handler = (msg: Record<string, unknown>) => void

/**
 * 控制面 WebSocket 单通道：房间广播 / 开局 / 结算 / RTC 信令 / WS 降级快照都走这里。
 * 地址 `wsUrl('/ws?token='+token)`（浏览器 WS 不能设 header，D5）。
 * 非主动断开时 2s 重连（token 有效期内服务端按 userId 重挂 session，D12）。
 */
export class ControlSocket {
  private ws: WebSocket | null = null
  private handlers = new Map<string, Set<Handler>>()
  private token = ''
  /** 主动关闭标志：close() 后不再重连 */
  private closedByUser = false
  private reconnectTimer = 0

  connect(token: string) {
    this.token = token
    this.closedByUser = false
    this.open()
  }

  private open() {
    const ws = new WebSocket(wsUrl(`/ws?token=${encodeURIComponent(this.token)}`))
    this.ws = ws
    ws.onopen = () => this.emit('open', {})
    ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(String(e.data)) as Record<string, unknown>
        this.emit(typeof msg.t === 'string' ? msg.t : '', msg)
      } catch {
        /* 非 JSON 帧忽略 */
      }
    }
    ws.onclose = () => {
      this.emit('close', {})
      if (!this.closedByUser) {
        window.clearTimeout(this.reconnectTimer)
        this.reconnectTimer = window.setTimeout(() => this.open(), 2000)
      }
    }
    ws.onerror = () => ws.close()
  }

  /** 订阅某类消息（msg.t）；'open'/'close' 为本地连接事件 */
  on(type: string, handler: Handler) {
    let set = this.handlers.get(type)
    if (!set) {
      set = new Set()
      this.handlers.set(type, set)
    }
    set.add(handler)
  }

  private emit(type: string, msg: Record<string, unknown>) {
    this.handlers.get(type)?.forEach((h) => h(msg))
  }

  send(obj: Record<string, unknown>) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(obj))
  }

  get connected() {
    return this.ws?.readyState === WebSocket.OPEN
  }

  close() {
    this.closedByUser = true
    window.clearTimeout(this.reconnectTimer)
    this.ws?.close()
    this.ws = null
  }
}
