import type { ControlSocket } from './ws'

/**
 * STUN 服务器（D17）：VITE_STUN_URL 留空 = 不带 STUN。
 * 客户端↔服务端拓扑下，服务端有公网 IP（或同机/局域网）时 NAT 会由出站 UDP 自动打洞，
 * 无需 STUN；仅当两端都在刁钻 NAT 后才需要（自建 coturn 填 stun:你的VPS:3478）。
 */
const STUN_URL: string = import.meta.env.VITE_STUN_URL ?? ''
const ICE_SERVERS: RTCIceServer[] = STUN_URL ? [{ urls: STUN_URL }] : []

/**
 * 数据面 WebRTC DataChannel（D1）：
 * - 服务端为 offer 方：收 `rtcOffer` → answer 回 `rtcAnswer`；ICE candidate 经 `rtcCand` trickle 互转
 * - `ondatachannel` 接服务端创建的 'game' channel（unreliable + unordered）
 * - 3s 未 open（或 ICE failed）→ 发 `{t:"rtcFail"}`，降级 WS
 * - send()：DC open 走 DC，否则走 WS（降级时快照/输入与 DC 同一 JSON 格式）
 */
export class RtcChannel {
  /** DC 数据消息（{t:"s",...} 快照） */
  onMessage: ((msg: Record<string, unknown>) => void) | null = null
  /** DC 首次 open 回调 */
  onOpen: (() => void) | null = null

  private pc: RTCPeerConnection | null = null
  private dc: RTCDataChannel | null = null
  private failTimer = 0
  private pingTimer = 0
  /** 最近一次 ping-pong 往返时延（ms）；null = 尚未测得 */
  private _rtt: number | null = null

  constructor(private socket: ControlSocket) {
    // WS 降级通道的测速回包：算 rtt 后拦截，不转发给房间消息处理
    socket.on('pong', (msg) => {
      if (typeof msg.ts === 'number') this._rtt = Date.now() - msg.ts
    })
  }

  /** 最近一次往返时延（ms）；GameView 延迟指示轮询此值 */
  get rtt() {
    return this._rtt
  }

  /** 开局后启动 2s 心跳测延迟（ping 走当前最优通道：DC open 走 DC，否则 WS） */
  startPing() {
    this.stopPing()
    this.send({ t: 'ping', ts: Date.now() })
    this.pingTimer = window.setInterval(() => this.send({ t: 'ping', ts: Date.now() }), 2000)
  }

  stopPing() {
    window.clearInterval(this.pingTimer)
    this.pingTimer = 0
  }

  get dcOpen() {
    return this.dc?.readyState === 'open'
  }

  /** 当前数据通道（?dev=1 overlay 自证用）：DC open = 'dc'，协商中/降级 = 'ws' */
  get mode(): 'dc' | 'ws' {
    return this.dcOpen ? 'dc' : 'ws'
  }

  /** 处理服务端 offer（WS 重连后服务端会重新 offer，先拆除旧 PC） */
  async handleOffer(sdp: string) {
    this.teardownPc()
    const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS })
    this.pc = pc
    pc.ondatachannel = (e) => {
      if (e.channel.label === 'game') this.setupDc(e.channel)
    }
    pc.onicecandidate = (e) => {
      if (e.candidate) this.socket.send({ t: 'rtcCand', cand: e.candidate.toJSON() })
    }
    pc.oniceconnectionstatechange = () => {
      if (pc.iceConnectionState === 'failed') this.markFail()
    }
    try {
      await pc.setRemoteDescription({ type: 'offer', sdp })
      const answer = await pc.createAnswer()
      await pc.setLocalDescription(answer)
      this.socket.send({ t: 'rtcAnswer', sdp: answer.sdp ?? '' })
    } catch {
      this.markFail()
      return
    }
    // 3s 未 open → 主动告知服务端降级（D1）
    window.clearTimeout(this.failTimer)
    this.failTimer = window.setTimeout(() => this.markFail(), 3000)
  }

  handleCand(cand: RTCIceCandidateInit) {
    this.pc?.addIceCandidate(cand).catch(() => {})
  }

  private setupDc(dc: RTCDataChannel) {
    this.dc = dc
    dc.onopen = () => {
      window.clearTimeout(this.failTimer)
      this.onOpen?.()
    }
    dc.onmessage = (e) => {
      try {
        const msg = JSON.parse(String(e.data)) as Record<string, unknown>
        // 测速回包：算 rtt 后拦截，不转发给 driver
        if (msg.t === 'pong' && typeof msg.ts === 'number') {
          this._rtt = Date.now() - msg.ts
          return
        }
        this.onMessage?.(msg)
      } catch {
        /* 非 JSON 帧忽略 */
      }
    }
    dc.onclose = () => {
      if (this.dc === dc) this.dc = null
    }
  }

  /** 降级 WS：告知服务端快照改走 WS，拆除 PC */
  private markFail() {
    if (this.pc === null && this.dc === null) return
    this.socket.send({ t: 'rtcFail' })
    this.teardownPc()
  }

  private teardownPc() {
    window.clearTimeout(this.failTimer)
    if (this.dc) {
      this.dc.onopen = null
      this.dc.onmessage = null
      this.dc.onclose = null
      try {
        this.dc.close()
      } catch {
        /* 忽略 */
      }
      this.dc = null
    }
    if (this.pc) {
      this.pc.onicecandidate = null
      this.pc.ondatachannel = null
      this.pc.oniceconnectionstatechange = null
      try {
        this.pc.close()
      } catch {
        /* 忽略 */
      }
      this.pc = null
    }
  }

  /** 输入消息：DC open 走 DC，否则走 WS 降级通道（均为 {t:"in",...}） */
  send(obj: Record<string, unknown>) {
    if (this.dcOpen) this.dc!.send(JSON.stringify(obj))
    else this.socket.send(obj)
  }

  close() {
    this.stopPing()
    this.teardownPc()
  }
}
