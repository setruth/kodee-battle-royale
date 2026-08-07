<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NModal, useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'
import { GameEngine } from '@/game/engine'
import type { WorldDriver } from '@/game/driver'
import type { Vec2 } from '@/game/types'
import VirtualJoystick from '@/components/VirtualJoystick.vue'
import AttackJoystick from '@/components/AttackJoystick.vue'
import ActionButtons from '@/components/ActionButtons.vue'
import RulesContent from '@/components/RulesContent.vue'
import { useFullscreen } from '@/utils/fullscreen'
import { ITEM_META } from '@/game/items'

const router = useRouter()
const auth = useAuthStore()
const roomStore = useRoomStore()
const message = useMessage()
const { isFullscreen, toggleFullscreen } = useFullscreen()

const canvasRef = ref<HTMLCanvasElement | null>(null)
/** driver 就绪（收到 gameStart） */
const ready = ref(false)

const driverRef = computed<WorldDriver | null>(() => roomStore.driver)
/** 本端实体已阵亡（联机快照连续缺失判定，stats 轮询同步） */
const selfDead = ref(false)
/** 出局覆盖层是否已被【观战】关闭（死亡状态保持，仅收起提示） */
const deadDismissed = ref(false)
/** 观战模式（D20）：旁观者，或自己阵亡后（HUD/个人面板同样不渲染） */
const spectating = computed(() => ready.value && (driverRef.value?.selfId === null || selfDead.value))

/** ?dev=1：显示当前数据通道（DC/WS），便于自证是否走 WebRTC */
const devMode = new URLSearchParams(location.search).has('dev')
const netMode = ref<'dc' | 'ws' | null>(null)
/** 最近一次 ping-pong 往返时延（ms）；null = 尚未测得 */
const rtt = ref<number | null>(null)

/** 对局内查看规则（按本局房间配置动态渲染） */
const showRules = ref(false)
const gameSettings = computed(() => driverRef.value?.settings)

/** 触屏设备才显示摇杆与按键；?touch=1 可在桌面强制开启调试 */
const isTouch =
  window.matchMedia('(pointer: coarse)').matches ||
  'ontouchstart' in window ||
  new URLSearchParams(location.search).has('touch')
const showTouchControls = computed(() => ready.value && isTouch && !spectating.value)

/** 竖屏检测：手机竖屏时提示横屏游玩 */
const isPortrait = ref(window.innerHeight > window.innerWidth)
const onViewportChange = () => {
  isPortrait.value = window.innerHeight > window.innerWidth
}
const showRotatePrompt = computed(() => isTouch && isPortrait.value)

/** HUD 数值（150ms 轮询引擎，避免把引擎状态塞进响应式） */
const stats = ref({
  hp: 100,
  maxHp: 100,
  mana: 300,
  maxMana: 300,
  pos: { x: 0, y: 0 },
  speed: 6,
  defense: 0,
  attackCd: 1,
  range: 9,
  leaderboard: [] as { id: string; name: string; color: string; hp: number; score: number; self: boolean }[],
  alive: [] as { id: string; name: string; color: string; hp: number; score: number; self: boolean }[],
  feed: [] as { id: number; text: string; color: string }[],
  outside: false,
  circle: { stage: 0, total: 6, phase: 'idle' as 'idle' | 'shrinking', remaining: 15, isFinal: false },
})
let statsTimer = 0

let engine: GameEngine | null = null
/** 观战当前跟随的实体 id（null = 全局自由视角） */
const followId = ref<string | null>(null)

function initEngine(d: WorldDriver) {
  if (engine || !canvasRef.value) return
  engine = new GameEngine({ canvas: canvasRef.value, driver: d, isMobile: isTouch })
  engine.start()
  engine.enableInput()
  ready.value = true
  statsTimer = window.setInterval(() => {
    if (!engine) return
    stats.value = engine.playerStats()
    // 被跟随者死亡引擎自动切回全局，同步高亮
    followId.value = engine.following
    // 本端阵亡：停输入进观战（默认全局视角），弹出『你已出局』
    if (engine.selfDead && !selfDead.value) {
      selfDead.value = true
      engine.enterSpectate()
    }
    netMode.value = roomStore.rtc?.mode ?? null
    rtt.value = roomStore.rtc?.rtt ?? null
  }, 150)
  // 触屏：首次点按（用户手势）时尽力请求全屏 + 横屏锁定
  if (isTouch) {
    const tryFullscreen = async () => {
      window.removeEventListener('pointerdown', tryFullscreen)
      try {
        if (!document.fullscreenElement) await document.documentElement.requestFullscreen()
        await (screen.orientation as ScreenOrientation & { lock: (o: string) => Promise<void> }).lock('landscape')
      } catch {
        /* 不支持就由横屏提示层兜底 */
      }
    }
    window.addEventListener('pointerdown', tryFullscreen)
  }
}

watch(
  [driverRef, canvasRef],
  ([d, c]) => {
    if (d && c) initEngine(d)
  },
  { immediate: true, flush: 'post' },
)

let gameStartTimeoutTimer = 0

onMounted(async () => {
  // WS 挂载时重连（D12：整页重开进对局时，服务端重挂 session 并补发 gameStart）
  roomStore.connect()
  let roomObj = roomStore.room
  if (!roomObj) {
    try {
      const currentStateName = await roomStore.fetchCurrent()
      if (!currentStateName) {
        router.replace('/lobby')
        return
      }
      roomObj = roomStore.room
    } catch {
      router.replace('/lobby')
      return
    }
  }

  // 修复 F5 刷新或对局已结束重进卡在“同步对局中”的 Bug：
  // 若房间状态非 'playing' / 'countdown'（例如已被结算为 'result' 或 'waiting' 或已解散），直接返回大厅！
  if (roomObj && roomObj.state !== 'playing' && roomObj.state !== 'countdown') {
    router.replace('/lobby')
    return
  }

  // 5 秒同步对局超时保底：若因网络异常等未收到 gameStart，解除卡死自动退回大厅
  gameStartTimeoutTimer = window.setTimeout(() => {
    if (!ready.value && !showGameover.value) {
      message.warning('同步对局超时，已为您返回大厅')
      router.replace('/lobby')
    }
  }, 5000)

  window.addEventListener('resize', onViewportChange)
  document.addEventListener('fullscreenchange', onViewportChange)
})

onBeforeUnmount(() => {
  window.clearTimeout(gameStartTimeoutTimer)
  window.clearInterval(statsTimer)
  window.removeEventListener('resize', onViewportChange)
  document.removeEventListener('fullscreenchange', onViewportChange)
  engine?.destroy()
  engine = null
})

/** 道具图例点按弹出的介绍（手机折叠图标模式用） */
const legendTip = ref<(typeof ITEM_META)[number] | null>(null)

// ── 结算覆盖层：读 result 消息 board ──
const netResult = computed(() => roomStore.result)
const showGameover = computed(() => !!netResult.value)
const board = computed(() => {
  if (!netResult.value) return []
  return netResult.value.board
    .slice()
    .sort((a, b) => a.rank - b.rank)
    .map((b) => ({
      name: b.name,
      color: b.color,
      hp: b.hp,
      score: b.score ?? 0,
      self: !b.isBot && b.name === auth.user?.username,
      isBot: b.isBot,
    }))
})
/** 积分显示：整数不带小数点，否则保留 1 位 */
const fmtScore = (v: number) => (Number.isInteger(v) ? `${v}` : v.toFixed(1))
/** 对局详细日志（result 消息全量，结算覆盖层展示） */
const logRows = computed(
  () => netResult.value?.logs.map((l, i) => ({ id: i + 1, time: l.tm, text: l.t, color: l.c })) ?? [],
)
/** 日志时间戳格式化：mm:ss */
const fmtLogTime = (sec: number) => {
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${`${m}`.padStart(2, '0')}:${`${s}`.padStart(2, '0')}`
}
const feedRows = computed(() =>
  netResult.value?.feed?.length
    ? netResult.value.feed.map((f, i) => ({ id: i + 100000, text: f.text, color: f.color }))
    : stats.value.feed,
)
const survived = computed(() => (board.value.find((b) => b.self)?.hp ?? 0) > 0)
// 阵亡玩家结算文案同死亡语义；仅纯旁观者显示『对局结束』
const pureSpectator = computed(() => driverRef.value?.selfId === null)
const gameoverEmoji = computed(() => (pureSpectator.value ? '🏁' : survived.value ? '🎉' : '💀'))
const gameoverText = computed(() =>
  pureSpectator.value ? '对局结束' : survived.value ? '你活到了最后！' : '你被 GC 回收了…',
)



// ── 被踢 / 房间解散 ──
watch(
  () => roomStore.leaveReason,
  (r) => {
    if (!r) return
    message.warning(r === 'kicked' ? '你已被房主移出房间' : '房间已解散')
    roomStore.clearLeaveReason()
    router.replace('/lobby')
  },
)

function onJoystickMove(v: Vec2) {
  engine?.input.setJoystick(v)
}

function onAim(v: Vec2) {
  engine?.input.setAim(v)
}

/** 观战视角切换（D20） */
function follow(id: string | null) {
  followId.value = id
  engine?.followEntity(id)
}

/** 出局覆盖层【观战】：收起提示，留在观战全局视角（enterSpectate 已在检测时调用） */
function dismissDead() {
  deadDismissed.value = true
}

async function leaveMatch() {
  try {
    await roomStore.leaveRoom()
  } catch {
    /* 服务端状态可能已变，本地照常退出 */
  }
  router.replace('/lobby')
}
</script>

<template>
  <div class="game-page">
    <canvas ref="canvasRef" class="game-page__canvas" />

    <!-- 联机等待 gameStart 的加载层 -->
    <div v-if="!ready" class="game-loading">正在同步对局…</div>

    <!-- 观战中角标（D20：HUD 隐藏个人数值面板）；联机附带延迟小字 -->
    <div v-if="spectating" class="spectate-badge">
      👁 观战中{{ ` · ${rtt === null ? '--ms' : `${rtt}ms`}` }}
    </div>

    <!-- ?dev=1：当前数据通道指示（DC = WebRTC DataChannel，WS = 降级） -->
    <div v-if="devMode && ready" class="dev-channel">
      {{ netMode === 'dc' ? '⚡ DataChannel' : '🔌 WebSocket 降级' }}
    </div>

    <!-- HUD：延迟 + 昵称 + 血量数值 + 速度 + 防御（观战不渲染） -->
    <div v-if="ready && !spectating" class="hud">
      <div class="hud__panel">
        <span class="hud__ping">{{ rtt === null ? '--ms' : `${rtt}ms` }}</span>
        <b class="hud__name">{{ auth.user?.username }}</b>
        <div class="hud__stat">
          <span class="hud__label">HP</span>
          <div class="hud__bar">
            <i :style="{ width: `${(stats.hp / stats.maxHp) * 100}%` }" />
          </div>
          <span class="hud__value hud__value--hp">{{ Math.ceil(stats.hp) }}</span>
        </div>
        <div class="hud__stat">
          <span class="hud__bullet" title="子弹">🔫</span>
          <div class="hud__bar hud__bar--mp">
            <i :style="{ width: `${((stats.mana || 0) / stats.maxMana) * 100}%` }" />
          </div>
          <span class="hud__value hud__value--mp">{{ Math.floor(stats.mana || 0) }} 发</span>
        </div>
        <!-- 次级数值：手机端隐藏，避免遮挡操作区 -->
        <div class="hud__stat hud__stat--minor">
          <span class="hud__label">速度</span>
          <span class="hud__value">{{ stats.speed.toFixed(1) }}</span>
        </div>
        <div class="hud__stat hud__stat--minor">
          <span class="hud__label">防御</span>
          <span class="hud__value">{{ stats.defense }}</span>
        </div>
        <div class="hud__stat hud__stat--minor">
          <span class="hud__label">攻速</span>
          <span class="hud__value">{{ stats.attackCd.toFixed(1) }}s</span>
        </div>
        <div class="hud__stat hud__stat--minor">
          <span class="hud__label">射程</span>
          <span class="hud__value">{{ stats.range }}</span>
        </div>
      </div>
      <p v-if="!isTouch" class="hud__hint">WASD 移动 · 左键 攻击 · E 表情</p>
    </div>

    <!-- 左侧：积分排行榜 TOP10（玩家） -->
    <div v-if="ready && !spectating" class="leaderboard">
      <p class="leaderboard__title">🏆 积分排行</p>
      <div
        v-for="(e, i) in stats.leaderboard"
        :key="e.id"
        class="leaderboard__row"
        :class="{ 'leaderboard__row--self': e.self }"
      >
        <span class="leaderboard__rank">{{ i + 1 }}</span>
        <i class="leaderboard__dot" :style="{ background: e.color }" />
        <span class="leaderboard__name">{{ e.name }}</span>
        <span class="leaderboard__score">{{ fmtScore(e.score) }} 分</span>
        <span class="leaderboard__hp">{{ e.hp }}</span>
      </div>
    </div>

    <!-- 左侧：观战存活玩家列表（点击跟随，D20） -->
    <div v-if="spectating" class="leaderboard follow-list">
      <p class="leaderboard__title">👁 存活玩家（点击跟随）</p>
      <div
        v-for="(e, i) in stats.alive"
        :key="e.id"
        class="leaderboard__row follow-list__row"
        :class="{ 'follow-list__row--active': followId === e.id }"
        @click="follow(e.id)"
      >
        <span class="leaderboard__rank">{{ i + 1 }}</span>
        <i class="leaderboard__dot" :style="{ background: e.color }" />
        <span class="leaderboard__name">{{ e.name }}</span>
        <span class="leaderboard__score">{{ fmtScore(e.score) }} 分</span>
        <span class="leaderboard__hp">{{ e.hp }}</span>
      </div>
    </div>

    <!-- 观战：全局视角切换按钮 -->
    <button v-if="spectating" type="button" class="global-view-btn" :disabled="followId === null" @click="follow(null)">
      🌐 全局视角
    </button>

    <!-- 右上角小地图左侧：击杀战报播报 -->
    <div v-if="ready && stats.feed.length" class="kill-feed">
      <p v-for="f in stats.feed" :key="f.id" class="kill-feed__item" :style="{ color: f.color }">
        {{ f.text }}
      </p>
    </div>

    <!-- 毒圈常驻提示（观战不渲染） -->
    <div v-if="ready && !spectating && !showGameover && stats.outside" class="poison-banner">
      ☠️ 你在毒圈外 — 正在被 GC 回收！
    </div>

    <!-- 右下角：本端实时坐标（观战不渲染） -->
    <div v-if="ready && !spectating" class="coord-hud">📍 {{ stats.pos.x.toFixed(1) }}, {{ stats.pos.y.toFixed(1) }}</div>

    <!-- 右侧竖排：道具图例（手机折叠为图标，点图标弹介绍） -->
    <div v-if="ready" class="item-legend">
      <div
        v-for="it in ITEM_META"
        :key="it.kind"
        class="item-legend__item"
        @click="legendTip = legendTip?.kind === it.kind ? null : it"
      >
        <span class="item-legend__icon" :style="{ color: it.color }">{{ it.icon }}</span>
        <div class="item-legend__text">
          <b>{{ it.label }}</b>
          <p>{{ it.desc }}</p>
        </div>
      </div>
    </div>

    <!-- 道具介绍弹层（手机点图标弹出，点空白关闭） -->
    <div v-if="legendTip" class="legend-tip" @click="legendTip = null">
      <div class="legend-tip__card">
        <span class="legend-tip__icon" :style="{ color: legendTip.color }">{{ legendTip.icon }}</span>
        <b>{{ legendTip.label }}</b>
        <p>{{ legendTip.desc }}</p>
      </div>
    </div>

    <!-- 底部中央：缩圈轮次与倒计时 -->
    <div v-if="ready && !showGameover" class="shrink-status" :class="{ 'shrink-status--urgent': stats.circle.phase === 'shrinking' }">
      <template v-if="stats.circle.isFinal">最终圈 · 活到最后！</template>
      <template v-else-if="stats.circle.phase === 'shrinking'">
        第 {{ stats.circle.stage + 1 }}/{{ stats.circle.total }} 轮 · 收缩中 {{ Math.ceil(stats.circle.remaining) }}s
      </template>
      <template v-else>
        第 {{ stats.circle.stage + 1 }}/{{ stats.circle.total }} 轮 · 收缩倒计时 {{ Math.ceil(stats.circle.remaining) }}s
      </template>
    </div>

    <!-- 出局覆盖层：自己阵亡（对局未结束时），复用结算覆盖层风格 -->
    <div v-if="selfDead && !showGameover && !deadDismissed" class="gameover">
      <div class="gameover__panel">
        <p class="gameover__emoji">💀</p>
        <p class="gameover__text">你已出局</p>
        <NButton type="primary" size="large" @click="dismissDead">观战</NButton>
        <NButton size="large" quaternary @click="leaveMatch">返回大厅</NButton>
      </div>
    </div>

    <!-- 结算覆盖层：结果 + 最终排名 + 日志 -->
    <div v-if="showGameover" class="gameover">
      <div class="gameover__panel">
        <p class="gameover__emoji">{{ gameoverEmoji }}</p>
        <p class="gameover__text">{{ gameoverText }}</p>

        <div class="gameover__board">
          <p class="gameover__sub">🏆 最终排名</p>
          <div
            v-for="(e, i) in board"
            :key="e.name"
            class="gameover__row"
            :class="{ 'gameover__row--self': e.self }"
          >
            <span class="gameover__rank">{{ i + 1 }}</span>
            <i class="gameover__dot" :style="{ background: e.color }" />
            <span class="gameover__name">{{ e.name }}{{ e.isBot ? ' 🤖' : '' }}</span>
            <span class="gameover__score">{{ fmtScore(e.score) }} 分</span>
            <span class="gameover__hp">{{ e.hp > 0 ? e.hp + ' HP' : '淘汰' }}</span>
          </div>
        </div>

        <div v-if="feedRows.length" class="gameover__log">
          <p class="gameover__sub">📜 战报</p>
          <p v-for="f in feedRows" :key="f.id" class="gameover__log-item" :style="{ color: f.color }">
            {{ f.text }}
          </p>
        </div>

        <div v-if="logRows.length" class="gameover__log gameover__log--detail">
          <p class="gameover__sub">🗒 详细日志</p>
          <p v-for="l in logRows" :key="l.id" class="gameover__log-item" :style="{ color: l.color }">
            <span class="gameover__log-time">{{ fmtLogTime(l.time) }}</span> {{ l.text }}
          </p>
        </div>

        <NButton type="primary" size="large" @click="leaveMatch">返回大厅</NButton>
      </div>
    </div>

    <!-- 右上角小地图左侧：竖向大图标操作按钮组（1.全屏 2.规则 3.返回大厅） -->
    <div class="game-page__action-column">
      <button
        type="button"
        class="action-icon-btn action-icon-btn--fullscreen"
        :title="isFullscreen ? '退出全屏' : '全屏'"
        @click="toggleFullscreen"
      >
        <span class="action-icon-btn__symbol">{{ isFullscreen ? '↙' : '⛶' }}</span>
      </button>

      <button
        v-if="ready"
        type="button"
        class="action-icon-btn action-icon-btn--rules"
        title="游戏规则"
        @click="showRules = true"
      >
        <span class="action-icon-btn__symbol">📜</span>
      </button>

      <button
        type="button"
        class="action-icon-btn action-icon-btn--logout"
        title="返回大厅"
        @click="leaveMatch"
      >
        <span class="action-icon-btn__symbol">🚪</span>
      </button>
    </div>

    <!-- 规则弹层：缩圈/怪物/道具文案按本局房间 settings 动态渲染 -->
    <NModal
      v-model:show="showRules"
      preset="card"
      title="游戏规则与道具"
      class="rules-modal"
      style="width: 680px; max-width: calc(100vw - 32px); margin: auto;"
    >
      <RulesContent :settings="gameSettings" />
    </NModal>

    <!-- 移动端控件：左移动摇杆 + 右攻击摇杆（推住=瞄准+连发；观战无操作） -->
    <VirtualJoystick v-if="showTouchControls" @move="onJoystickMove" />
    <AttackJoystick v-if="showTouchControls" @aim="onAim" />
    <ActionButtons v-if="showTouchControls" @emote="engine?.input.queueEmote()" />

    <!-- 手机竖屏提示 -->
    <div v-if="showRotatePrompt" class="rotate-prompt">
      <span class="rotate-prompt__icon">📱↻</span>
      <p>请横屏游玩</p>
    </div>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;
@use '@/styles/mixins' as *;

.game-page {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;

  &__canvas {
    width: 100%;
    height: 100%;
    display: block;
    touch-action: none;
  }

  // 右上角小地图左侧：竖向 3 个大图标操作按钮组（1.全屏 2.规则 3.返回大厅）
  &__action-column {
    position: fixed;
    top: 12px;
    right: calc(22vw + 24px);
    z-index: 35;
    display: flex;
    flex-direction: column;
    gap: 8px;

    @media (min-width: 910px) {
      right: 224px; // PC端小地图 200px + 12px 边距 + 12px 间隔
    }

    @include mobile {
      top: 8px;
      right: 140px;
      gap: 6px;
    }
  }
}

:deep(.rules-modal) {
  // 规则弹层：限宽并留边，移动端不贴屏
  width: min(720px, calc(100vw - 48px));
  margin: auto;
}

.game-loading {
  position: fixed;
  inset: 0;
  z-index: 40;
  @include flex-center;
  color: $text-dim;
  font-size: 15px;
  letter-spacing: 2px;
}

.spectate-badge {
  position: fixed;
  top: 12px;
  left: 12px;
  z-index: 30;
  @include glass-panel;
  padding: 6px 14px;
  font-size: 13px;
  color: #a78bfa;
  letter-spacing: 1px;
  pointer-events: none;

  @include mobile {
    top: 6px;
    left: 6px;
    padding: 3px 8px;
    font-size: 11px;
  }
}

.dev-channel {
  position: fixed;
  bottom: 12px;
  left: 12px;
  z-index: 30;
  @include glass-panel;
  padding: 4px 12px;
  font-size: 12px;
  color: $text-dim;
  letter-spacing: 1px;
  pointer-events: none;

  @include mobile {
    bottom: 6px;
    left: 6px;
    padding: 2px 6px;
    font-size: 10px;
  }
}

.follow-list {
  // 观战跟随列表必须可点：.leaderboard 基础样式 pointer-events:none 且定义在后，
  // 同优先级会被覆盖（桌面端点击失效的根因），故用双类选择器提权
  &.leaderboard {
    pointer-events: auto;
  }

  &__row {
    cursor: pointer;

    &:hover {
      background: rgba(127, 82, 255, 0.18);
    }

    &--active {
      background: rgba(127, 82, 255, 0.28);
    }
  }
}

.coord-hud {
  position: fixed;
  right: 12px;
  bottom: 12px;
  z-index: 30;
  @include glass-panel;
  padding: 4px 12px;
  font-size: 12px;
  color: $text-dim;
  letter-spacing: 1px;
  font-variant-numeric: tabular-nums;
  pointer-events: none;

  @include mobile {
    bottom: 8px;
    right: 8px;
    font-size: 10px;
    padding: 3px 8px;
  }
}

.global-view-btn {
  position: fixed;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 35;
  @include glass-panel;
  padding: 6px 18px;
  color: $text-main;
  font-size: 13px;
  letter-spacing: 1px;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover:not(:disabled) {
    background: rgba(127, 82, 255, 0.3);
  }

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
}

.hud {
  position: fixed;
  top: 12px;
  left: 12px;
  z-index: 30;
  pointer-events: none;

  &__panel {
    @include glass-panel;
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 8px 16px;
    font-size: 14px;
  }

  &__name {
    font-size: 14px;
  }

  &__ping {
    font-size: 12px;
    color: $text-dim;
    font-variant-numeric: tabular-nums;
    opacity: 0.8;
  }

  &__stat {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  &__label {
    font-size: 11px;
    color: $text-dim;
    letter-spacing: 1px;
  }

  &__bullet {
    font-size: 13px;
    line-height: 1;
  }

  &__value {
    font-weight: 700;
    font-variant-numeric: tabular-nums;

    &--hp {
      color: $hp-red;
    }

    &--mp {
      color: #55c8ff;
    }
  }

  &__bar {
    width: 72px;
    height: 6px;
    border-radius: 3px;
    background: rgba(255, 255, 255, 0.1);
    overflow: hidden;

    i {
      display: block;
      height: 100%;
      border-radius: 3px;
      background: linear-gradient(90deg, $hp-red, #ff8a5c);
      transition: width 0.2s ease;
    }

    &--mp i {
      background: linear-gradient(90deg, #0095d5, #55c8ff);
    }
  }

  &__hint {
    margin-top: 8px;
    font-size: 12px;
    color: rgba(236, 233, 247, 0.4);
  }

  @include mobile {
    top: 6px;
    left: 6px;

    &__panel {
      gap: 6px;
      padding: 3px 6px;
      font-size: 10px;
    }

    &__name {
      font-size: 11px;
      max-width: 60px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &__ping {
      font-size: 10px;
    }

    &__bar {
      width: 36px;
      height: 5px;
    }

    // 手机端隐藏次级数值（速度/防御/攻速/射程），最大化归还屏幕空间
    &__stat--minor {
      display: none;
    }
  }
}

.leaderboard {
  position: fixed;
  left: 12px;
  top: 100px; // HUD 下方
  z-index: 30;
  @include glass-panel;
  padding: 8px 10px;
  min-width: 150px;
  pointer-events: none;

  &__title {
    font-size: 12px;
    color: $text-dim;
    letter-spacing: 1px;
    margin-bottom: 6px;
  }

  &__row {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 2px 4px;
    border-radius: 6px;
    font-size: 12px;

    &--self {
      background: rgba(167, 139, 250, 0.15);
    }
  }

  &__rank {
    width: 14px;
    color: $text-dim;
    font-variant-numeric: tabular-nums;
  }

  &__dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex-shrink: 0;
  }

  &__name {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__hp {
    color: $hp-red;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }

  &__score {
    color: #ffd166;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }

  @include mobile {
    top: 38px; // 紧贴 HUD 下方
    left: 6px;
    min-width: 0;
    max-width: 130px;
    padding: 3px 5px;
    max-height: 28vh;
    overflow-y: auto;
    pointer-events: auto;

    &__title {
      font-size: 9px;
      margin-bottom: 2px;
    }

    &__row {
      font-size: 9px;
      padding: 1px 3px;
      gap: 3px;
    }

    &__row:nth-child(n + 6) {
      display: none;
    }
  }
}

// 快捷大图标操作按钮样式（三大专属主题配色）
.action-icon-btn {
  @include glass-panel;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.4);
  transition: all 0.2s cubic-bezier(0.34, 1.56, 0.64, 1);
  position: relative;

  &__symbol {
    font-size: 18px;
    line-height: 1;
    transition: transform 0.15s ease;
  }

  // 1. 全屏：科技蓝
  &--fullscreen {
    background: radial-gradient(135deg, rgba(0, 180, 216, 0.3) 0%, rgba(20, 18, 38, 0.85) 100%);
    border: 1px solid rgba(0, 180, 216, 0.5);
    color: #55c8ff;

    &:hover {
      transform: scale(1.1);
      background: radial-gradient(135deg, rgba(0, 180, 216, 0.5) 0%, rgba(20, 18, 38, 0.9) 100%);
      border-color: #55c8ff;
      box-shadow: 0 0 16px rgba(0, 180, 216, 0.6);
    }
  }

  // 2. 规则：琥珀金
  &--rules {
    background: radial-gradient(135deg, rgba(255, 209, 102, 0.3) 0%, rgba(20, 18, 38, 0.85) 100%);
    border: 1px solid rgba(255, 209, 102, 0.5);
    color: #ffd166;

    &:hover {
      transform: scale(1.1);
      background: radial-gradient(135deg, rgba(255, 209, 102, 0.5) 0%, rgba(20, 18, 38, 0.9) 100%);
      border-color: #ffd166;
      box-shadow: 0 0 16px rgba(255, 209, 102, 0.6);
    }
  }

  // 3. 退出：珊瑚红
  &--logout {
    background: radial-gradient(135deg, rgba(255, 90, 110, 0.3) 0%, rgba(20, 18, 38, 0.85) 100%);
    border: 1px solid rgba(255, 90, 110, 0.5);
    color: #ff5a6e;

    &:hover {
      transform: scale(1.1);
      background: radial-gradient(135deg, rgba(255, 90, 110, 0.5) 0%, rgba(20, 18, 38, 0.9) 100%);
      border-color: #ff5a6e;
      box-shadow: 0 0 16px rgba(255, 90, 110, 0.6);
    }
  }

  &:active {
    transform: scale(0.92);
  }

  @include mobile {
    width: 36px;
    height: 36px;
    border-radius: 8px;

    &__symbol {
      font-size: 16px;
    }
  }
}

// 击杀战报播报：定位在竖向操作按钮组左侧
.kill-feed {
  position: fixed;
  right: calc(22vw + 72px);
  top: 12px;
  z-index: 30;
  @include glass-panel;
  padding: 6px 12px;
  max-width: 240px;
  pointer-events: none;
  text-align: right;

  &__item {
    font-size: 11px;
    line-height: 1.6;
    opacity: 0.9;
    white-space: nowrap;
    animation: feed-in 0.25s ease;
  }

  @media (min-width: 910px) {
    right: 272px;
  }

  @include mobile {
    right: 184px;
    top: 8px;
    padding: 4px 8px;
    max-width: 170px;

    &__item {
      font-size: 10px;
    }

    &__item:nth-child(n + 4) {
      display: none;
    }
  }
}

@keyframes feed-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
}

// 毒圈常驻提示
.poison-banner {
  position: fixed;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 35;
  padding: 6px 16px;
  border-radius: 10px;
  background: rgba(226, 68, 98, 0.18);
  border: 1px solid rgba(255, 90, 110, 0.55);
  color: $hp-red;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 1px;
  animation: poison-blink 1s ease-in-out infinite;
  pointer-events: none;
  white-space: nowrap;

  @include mobile {
    font-size: 11px;
    padding: 4px 10px;
  }
}

@keyframes poison-blink {
  50% {
    opacity: 0.55;
  }
}

// 道具介绍弹层（手机点图标）
.legend-tip {
  position: fixed;
  inset: 0;
  z-index: 45;
  @include flex-center;
  background: rgba(18, 16, 28, 0.5);

  &__card {
    @include glass-panel;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 18px 24px;
    max-width: 220px;
    text-align: center;
  }

  &__icon {
    font-size: 26px;
    font-weight: 700;
  }
}

.item-legend {
  position: fixed;
  right: 12px;
  top: 155px; // 小地图下方
  z-index: 30;
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 45vh;
  overflow-y: auto;
  pointer-events: auto;

  &__item {
    @include glass-panel;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 4px 10px;
  }

  &__icon {
    min-width: 22px;
    font-weight: 700;
    font-size: 13px;
    text-align: center;
  }

  &__text {
    b {
      font-size: 11px;
      color: $text-main;
    }

    p {
      font-size: 10px;
      color: $text-dim;
    }
  }

  // 窄屏/矮屏折叠为纯图标竖条（不再直接隐藏）
  @media (max-width: 1100px), (max-height: 620px) {
    top: 125px;
    gap: 2px;

    &__item {
      padding: 3px 6px;
      pointer-events: auto; // 折叠模式可点：点图标弹介绍
      cursor: pointer;
    }

    &__icon {
      min-width: 0;
      font-size: 12px;
    }

    &__text {
      display: none;
    }
  }

  @include mobile {
    top: 86px; // 避开右上角小地图（小地图高度约 68px + 12px 边距）
    right: 8px;
    max-height: 32vh;
    overflow-y: auto;
    pointer-events: auto;

    &__item {
      padding: 2px 5px;
    }

    &__icon {
      font-size: 11px;
    }
  }
}

.shrink-status {
  position: fixed;
  bottom: 14px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 30;
  @include glass-panel;
  padding: 6px 18px;
  font-size: 15px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: 1px;
  pointer-events: none;
  white-space: nowrap;

  &--urgent {
    color: $hp-red;
    border-color: rgba(255, 90, 110, 0.5);
  }

  @include mobile {
    bottom: 6px;
    font-size: 11px;
    padding: 3px 10px;
  }
}

.gameover {
  position: fixed;
  inset: 0;
  z-index: 55;
  @include flex-center;
  background: rgba(18, 16, 28, 0.85);
  padding: 16px;

  &__panel {
    @include glass-panel;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    width: 100%;
    max-width: 360px;
    max-height: 88vh;
    overflow-y: auto;
    padding: 20px 22px;
  }

  &__emoji {
    font-size: 44px;
  }

  &__text {
    font-size: 18px;
    letter-spacing: 2px;
  }

  &__sub {
    font-size: 12px;
    color: $text-dim;
    letter-spacing: 1px;
    margin-bottom: 6px;
    text-align: center;
  }

  &__board {
    width: 100%;
  }

  &__row {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 2px 6px;
    border-radius: 6px;
    font-size: 12px;

    &--self {
      background: rgba(167, 139, 250, 0.15);
    }
  }

  &__rank {
    width: 16px;
    color: $text-dim;
    font-variant-numeric: tabular-nums;
  }

  &__dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex-shrink: 0;
  }

  &__name {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__hp {
    color: $text-dim;
    font-variant-numeric: tabular-nums;
  }

  &__score {
    color: #ffd166;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }

  &__log {
    width: 100%;
    border-top: 1px solid rgba(255, 255, 255, 0.08);
    padding-top: 8px;

    &--detail {
      max-height: 30vh;
      overflow-y: auto;
    }
  }

  &__log-time {
    color: $text-dim;
    font-variant-numeric: tabular-nums;
    margin-right: 4px;
  }

  &__log-item {
    font-size: 11px;
    line-height: 1.6;
    opacity: 0.85;
  }

  &__waiting {
    font-size: 12px;
    color: $text-dim;
  }
}

.rotate-prompt {
  position: fixed;
  inset: 0;
  z-index: 60;
  @include flex-center;
  flex-direction: column;
  gap: 12px;
  background: rgba(18, 16, 28, 0.92);
  color: $text-main;
  font-size: 16px;
  letter-spacing: 2px;

  &__icon {
    font-size: 42px;
    animation: rotate-hint 1.6s ease-in-out infinite;
  }
}

@keyframes rotate-hint {
  0%,
  20% {
    transform: rotate(0deg);
  }
  60%,
  100% {
    transform: rotate(90deg);
  }
}
</style>
