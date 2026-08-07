import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { request, ApiError } from '@/api/http'
import { ControlSocket } from '@/net/ws'
import { RtcChannel } from '@/net/rtc'
import { NetDriver } from '@/game/driver'
import type { GameStartMsg, SnapshotMsg } from '@/game/driver'
import type { GameSettings } from '@/game/settings'
import { useAuthStore } from './auth'

export type RoomStateName = 'waiting' | 'countdown' | 'playing' | 'result'
export type RoleName = 'player' | 'spectator'

export interface RoomMember {
  id: number
  name: string
  color: string
  role: RoleName
  ready: boolean
}

export interface RoomInfo {
  code: string
  hostId: number
  state: RoomStateName
  bots: number
  members: RoomMember[]
  /** 房间规则配置（服务端下发；旧服务端缺省视为 undefined，视图层用 DEFAULT_SETTINGS 兜底） */
  settings?: GameSettings
}

/** WS room 消息 / HTTP room 字段的原始形状 */
interface RawRoom {
  code: string
  hostId: number
  state: RoomStateName
  bots: number
  members: RoomMember[]
  settings?: GameSettings
}

export interface ResultBoardEntry {
  name: string
  color: string
  hp: number
  /** 积分（积分制；旧记录缺省为 0） */
  score?: number
  rank: number
  isBot: boolean
}

/** 对局详细日志条目（result 消息全量下发） */
export interface ResultLogEntry {
  /** 发生时刻（秒，对局内时间） */
  tm: number
  t: string
  c: string
}

export interface ResultData {
  board: ResultBoardEntry[]
  feed: { text: string; color: string }[]
  logs: ResultLogEntry[]
}

/**
 * 房间/对局控制面：持有 ControlSocket 单例与 RtcChannel，
 * 所有 WS 消息（room/countdown/gameStart/result/kicked/closed/rtc*）在此归一处理。
 */
export const useRoomStore = defineStore('room', () => {
  const auth = useAuthStore()

  const socket = shallowRef<ControlSocket | null>(null)
  const rtc = shallowRef<RtcChannel | null>(null)
  const room = ref<RoomInfo | null>(null)
  /** 开局倒计时剩余秒（countdown 消息触发，RoomView 本地递减展示） */
  const countdown = ref<number | null>(null)
  const gameStartPayload = shallowRef<GameStartMsg | null>(null)
  const driver = shallowRef<NetDriver | null>(null)
  const result = shallowRef<ResultData | null>(null)
  /** 被踢/房间解散通知（视图消费后调 clearLeaveReason 复位） */
  const leaveReason = ref<'kicked' | 'closed' | null>(null)

  const isHost = computed(() => room.value !== null && room.value.hostId === auth.user?.userId)
  const myMember = computed(() => room.value?.members.find((m) => m.id === auth.user?.userId) ?? null)

  // ── WS 连接与消息归一处理 ──

  function connect() {
    if (socket.value || !auth.token) return
    const s = new ControlSocket()
    const r = new RtcChannel(s)
    r.onMessage = (msg) => {
      if (msg.t === 's') driver.value?.pushSnapshot(msg as unknown as SnapshotMsg)
    }
    s.on('room', (msg) => applyRoom(msg as unknown as RawRoom))
    s.on('countdown', (msg) => {
      countdown.value = typeof msg.n === 'number' ? msg.n : 3
    })
    s.on('gameStart', (msg) => onGameStart(msg as unknown as GameStartMsg))
    // WS 降级快照（与 DC 同一 JSON）
    s.on('s', (msg) => driver.value?.pushSnapshot(msg as unknown as SnapshotMsg))
    s.on('result', (msg) => onResult(msg))
    s.on('rtcOffer', (msg) => {
      if (typeof msg.sdp === 'string') void r.handleOffer(msg.sdp)
    })
    s.on('rtcCand', (msg) => r.handleCand(msg.cand as RTCIceCandidateInit))
    s.on('kicked', () => onForcedLeave('kicked'))
    s.on('closed', () => onForcedLeave('closed'))
    socket.value = s
    rtc.value = r
    s.connect(auth.token)
  }

  function disconnect() {
    rtc.value?.close()
    socket.value?.close()
    rtc.value = null
    socket.value = null
    clearRoom()
  }

  function applyRoom(r: RawRoom) {
    room.value = {
      code: r.code,
      hostId: r.hostId,
      state: r.state,
      bots: r.bots,
      members: r.members,
      settings: r.settings,
    }
    // 回到等待态（再来一局）：清掉上一局的对局状态
    if (r.state === 'waiting') clearMatch()
  }

  function clearMatch() {
    result.value = null
    countdown.value = null
    gameStartPayload.value = null
    driver.value?.destroy()
    driver.value = null
  }

  function clearRoom() {
    room.value = null
    clearMatch()
  }

  function onGameStart(payload: GameStartMsg) {
    result.value = null
    countdown.value = null
    gameStartPayload.value = payload
    driver.value?.destroy()
    driver.value = new NetDriver(payload, rtc.value!)
  }

  function onResult(msg: Record<string, unknown>) {
    const data: ResultData = {
      board: (msg.board as ResultBoardEntry[]) ?? [],
      feed: (msg.feed as { text: string; color: string }[]) ?? [],
      logs: (msg.logs as ResultLogEntry[]) ?? [],
    }
    result.value = data
    const d = driver.value
    if (d) {
      if (d.selfId === null) {
        d.markGameOver('survived') // 旁观者：仅触发结算覆盖层，文案由 GameView 区分
      } else {
        const me = data.board.find((b) => !b.isBot && b.name === auth.user?.username)
        d.markGameOver(me && me.hp > 0 ? 'survived' : 'dead')
      }
    }
  }

  function onForcedLeave(reason: 'kicked' | 'closed') {
    clearRoom()
    leaveReason.value = reason
  }

  function clearLeaveReason() {
    leaveReason.value = null
  }

  // ── HTTP 房间操作（全部 authenticate，服务端随后经 WS 广播最新 room） ──

  async function createRoom(color: string, role: RoleName, settings?: GameSettings) {
    const r = await request<{ roomCode: string; room: RawRoom }>('/rooms', {
      method: 'POST',
      token: auth.token,
      body: settings ? { color, role, settings } : { color, role },
    })
    applyRoom(r.room)
    connect()
  }

  async function joinRoom(roomCode: string, color: string, role: RoleName) {
    const r = await request<{ roomCode: string; room: RawRoom }>('/rooms/join', {
      method: 'POST',
      token: auth.token,
      body: { roomCode, color, role },
    })
    applyRoom(r.room)
    connect()
  }

  async function leaveRoom() {
    await request('/rooms/leave', { method: 'POST', token: auth.token })
    clearRoom()
  }

  async function setBots(count: number) {
    await request('/rooms/bots', { method: 'POST', token: auth.token, body: { count } })
  }

  /** 房主保存房间规则（WAITING 限定）；成功后服务端经 WS room 广播全员可见 */
  async function saveSettings(settings: GameSettings) {
    await request('/rooms/settings', { method: 'POST', token: auth.token, body: { settings } })
  }

  async function kick(userId: number) {
    await request('/rooms/kick', { method: 'POST', token: auth.token, body: { userId } })
  }

  async function setReady(ready: boolean) {
    await request('/rooms/ready', { method: 'POST', token: auth.token, body: { ready } })
  }

  async function setRole(role: RoleName) {
    await request('/rooms/role', { method: 'POST', token: auth.token, body: { role } })
  }

  async function start() {
    await request('/rooms/start', { method: 'POST', token: auth.token })
  }

  async function again() {
    await request('/rooms/again', { method: 'POST', token: auth.token })
  }

  /** D12 重连恢复探针：404 = 不在任何房间（返回 null） */
  async function fetchCurrent(): Promise<RoomStateName | null> {
    try {
      const r = await request<{ roomCode: string; room: RawRoom; state: RoomStateName }>('/rooms/current', {
        token: auth.token,
      })
      applyRoom(r.room)
      return r.state
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) return null
      throw e
    }
  }

  return {
    socket,
    rtc,
    room,
    countdown,
    gameStartPayload,
    driver,
    result,
    leaveReason,
    isHost,
    myMember,
    connect,
    disconnect,
    createRoom,
    joinRoom,
    leaveRoom,
    setBots,
    saveSettings,
    kick,
    setReady,
    setRole,
    start,
    again,
    fetchCurrent,
    clearLeaveReason,
  }
})
