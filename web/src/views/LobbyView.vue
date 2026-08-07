<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCollapse, NCollapseItem, NInput, NModal, NSpin, NSwitch, useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'
import type { RoleName } from '@/stores/room'
import { request } from '@/api/http'
import { COLORS } from '@/theme/palette'
import SettingsForm from '@/components/SettingsForm.vue'
import { useFullscreen } from '@/utils/fullscreen'
import { DEFAULT_SETTINGS, cloneSettings } from '@/game/settings'
import type { GameSettings } from '@/game/settings'

const { isFullscreen, toggleFullscreen } = useFullscreen()

interface HistoryItem {
  matchId: number
  startedAt: string
  durationSec: number
  playerCount: number
  myRank: number
}

interface HistoryDetail {
  startedAt: string
  durationSec: number
  /** 服务端原样返回的 JSON 字符串：{board, feed} */
  result: string
}

interface ResultJson {
  board: { name: string; color: string; hp: number; score?: number; rank: number; isBot: boolean }[]
  feed: { text: string; color: string }[]
  /** 对局详细日志（积分制版本起记录；旧对局缺省） */
  logs?: { tm: number; t: string; c: string }[]
}

const router = useRouter()
const auth = useAuthStore()
const roomStore = useRoomStore()
const message = useMessage()

// 公共颜色选择器（D6：创建/加入共用 8 色盘）
const color = ref(COLORS[0])
const createRole = ref<RoleName>('player')
const roomCode = ref('')
const creating = ref(false)
const joining = ref(false)
/** 创建房间的可选规则配置（缺省即服务端默认值） */
const createSettings = ref<GameSettings>(cloneSettings(DEFAULT_SETTINGS))

async function onCreate() {
  creating.value = true
  try {
    await roomStore.createRoom(color.value, createRole.value, createSettings.value)
    router.replace('/room')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    creating.value = false
  }
}

async function onJoin() {
  if (!/^[A-Z2-9]{6}$/.test(roomCode.value)) {
    message.warning('请输入 6 位房间码')
    return
  }
  joining.value = true
  try {
    // 加入者固定玩家角色（旁观者选项仅创建房间可选）；POST 响应即含 room 数据，先渲染再连 WS
    await roomStore.joinRoom(roomCode.value, color.value, 'player')
    router.replace('/room')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加入失败')
  } finally {
    joining.value = false
  }
}

async function onLogout() {
  await auth.logout()
  router.replace('/login')
}

// ── 历史战绩（D21）──
const history = ref<HistoryItem[]>([])
const historyLoading = ref(false)
const showDetail = ref(false)
const detailLoading = ref(false)
const detail = ref<ResultJson | null>(null)
const detailMeta = ref<{ startedAt: string; durationSec: number } | null>(null)

/** 积分显示：整数不带小数点，否则保留 1 位 */
const fmtScore = (v: number) => (Number.isInteger(v) ? `${v}` : v.toFixed(1))

/** 日志时间戳格式化：mm:ss */
const fmtLogTime = (sec: number) => {
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${`${m}`.padStart(2, '0')}:${`${s}`.padStart(2, '0')}`
}

function formatTime(iso: string) {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

async function loadHistory() {
  historyLoading.value = true
  try {
    history.value = await request<HistoryItem[]>('/history/me?limit=20', { token: auth.token })
  } catch {
    /* 历史加载失败不阻塞大厅 */
  } finally {
    historyLoading.value = false
  }
}

async function openDetail(item: HistoryItem) {
  showDetail.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const d = await request<HistoryDetail>(`/history/${item.matchId}`, { token: auth.token })
    detailMeta.value = { startedAt: d.startedAt, durationSec: d.durationSec }
    detail.value = JSON.parse(d.result) as ResultJson
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载失败')
    showDetail.value = false
  } finally {
    detailLoading.value = false
  }
}

onMounted(async () => {
  loadHistory()
  // 校验当前活跃房间/对局重连恢复状态（如换浏览器/设备登录 / 重新打开 App）
  try {
    const currentState = await roomStore.fetchCurrent()
    if (currentState === 'waiting') {
      router.replace('/room')
    } else if (currentState === 'playing' || currentState === 'countdown') {
      router.replace('/game')
    }
  } catch {
    /* 探针失败或不在房间，保留在大厅 */
  }
})
</script>

<template>
  <div class="lobby-page">
    <div class="bg-glow bg-glow--top" />
    <div class="bg-glow bg-glow--bottom" />

    <header class="lobby-header">
      <div class="lobby-header__brand">
        <img src="@/assets/player.png" class="lobby-header__mascot" alt="Kodee" />
        <h1 class="lobby-header__title">Kodee大逃杀</h1>
        <span class="lobby-header__hello">你好，{{ auth.user?.username }}</span>
      </div>
      <div class="lobby-header__actions">
        <NButton quaternary size="small" @click="toggleFullscreen">
          {{ isFullscreen ? '↙↘ 窗口' : '⛶ 全屏' }}
        </NButton>
        <NButton quaternary size="small" @click="onLogout">退出登录</NButton>
      </div>
    </header>

    <main class="lobby-main">
      <!-- 公共颜色选择器 -->
      <section class="color-section">
        <label class="form-label">选择你的角色颜色</label>
        <div class="color-picker__list">
          <button
            v-for="c in COLORS"
            :key="c"
            type="button"
            class="color-picker__dot"
            :class="{ 'color-picker__dot--active': color === c }"
            :style="{ background: c, '--dot-color': c }"
            :aria-label="`颜色 ${c}`"
            @click="color = c"
          />
        </div>
      </section>

      <div class="lobby-cards">
        <!-- 创建房间 -->
        <section class="lobby-card">
          <h2 class="lobby-card__title">创建房间</h2>
          <p class="lobby-card__desc">生成 6 位房间码，邀请好友加入</p>
          <div class="role-switch">
            <span class="role-switch__label">我的角色</span>
            <NSwitch v-model:value="createRole" checked-value="player" unchecked-value="spectator">
              <template #checked>玩家</template>
              <template #unchecked>旁观</template>
            </NSwitch>
          </div>
          <NCollapse class="create-settings">
            <NCollapseItem title="房间规则（可选）" name="settings">
              <div class="create-settings__scroll">
                <SettingsForm v-model:settings="createSettings" />
              </div>
            </NCollapseItem>
          </NCollapse>
          <NButton type="primary" size="large" block :loading="creating" @click="onCreate">
            创建房间
          </NButton>
        </section>

        <!-- 加入房间 -->
        <section class="lobby-card">
          <h2 class="lobby-card__title">加入房间</h2>
          <p class="lobby-card__desc">输入好友分享的 6 位房间码</p>
          <NInput
            v-model:value="roomCode"
            placeholder="房间码"
            maxlength="6"
            size="large"
            class="room-code-input"
            @update:value="(v: string) => (roomCode = v.toUpperCase())"
            @keydown.enter.prevent="onJoin"
          />
          <NButton type="primary" size="large" block :loading="joining" @click="onJoin">
            {{ joining ? '正在加入…' : '加入房间' }}
          </NButton>
        </section>
      </div>

      <!-- 历史战绩 -->
      <section class="history">
        <h2 class="history__title">历史战绩</h2>
        <NSpin :show="historyLoading">
          <p v-if="!historyLoading && history.length === 0" class="history__empty">还没有对局记录，来一局吧！</p>
          <div v-else class="history__list">
            <button
              v-for="h in history"
              :key="h.matchId"
              type="button"
              class="history__row"
              @click="openDetail(h)"
            >
              <span class="history__rank" :class="{ 'history__rank--top': h.myRank === 1 }">#{{ h.myRank }}</span>
              <span class="history__time">{{ formatTime(h.startedAt) }}</span>
              <span class="history__meta">{{ h.playerCount }} 人局 · {{ Math.round(h.durationSec / 60) }} 分钟</span>
            </button>
          </div>
        </NSpin>
      </section>
    </main>

    <!-- 战绩详情：积分排名榜 + 击杀日志 + 详细日志 -->
    <NModal
      v-model:show="showDetail"
      preset="card"
      title="对局详情"
      class="history-modal"
      style="width: 480px; max-width: calc(100vw - 32px); margin: auto;"
    >
      <NSpin :show="detailLoading">
        <template v-if="detail">
          <p class="history-modal__meta">
            {{ detailMeta ? formatTime(detailMeta.startedAt) : '' }} · {{ detailMeta?.durationSec }}s
          </p>
          <div class="history-modal__board">
            <div v-for="b in detail.board" :key="b.name" class="history-modal__row">
              <span class="history-modal__rank">{{ b.rank }}</span>
              <i class="history-modal__dot" :style="{ background: b.color }" />
              <span class="history-modal__name">{{ b.name }}{{ b.isBot ? ' 🤖' : '' }}</span>
              <span v-if="b.score !== undefined" class="history-modal__score">{{ fmtScore(b.score) }} 分</span>
              <span class="history-modal__hp">{{ b.hp > 0 ? b.hp + ' HP' : '淘汰' }}</span>
            </div>
          </div>
          <div v-if="detail.feed.length" class="history-modal__feed">
            <p class="history-modal__sub">击杀日志</p>
            <p v-for="(f, i) in detail.feed" :key="i" class="history-modal__feed-item" :style="{ color: f.color }">
              {{ f.text }}
            </p>
          </div>
          <div v-if="detail.logs?.length" class="history-modal__feed history-modal__feed--detail">
            <p class="history-modal__sub">详细日志</p>
            <p
              v-for="(l, i) in detail.logs"
              :key="i"
              class="history-modal__feed-item"
              :style="{ color: l.c }"
            >
              <span class="history-modal__log-time">{{ fmtLogTime(l.tm) }}</span> {{ l.t }}
            </p>
          </div>
        </template>
      </NSpin>
    </NModal>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;
@use '@/styles/mixins' as *;

.lobby-page {
  position: absolute;
  inset: 0;
  // 桌面一屏放下不滚动，内部区域各自滚动；移动端内容多允许整页滚动
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: radial-gradient(circle at 50% 30%, #1e1838 0%, #0d0b18 80%);
  padding: 20px 24px 24px;

  @include mobile {
    overflow-y: auto;
  }
}

.bg-glow {
  position: fixed;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
  opacity: 0.45;

  &--top {
    top: -10%;
    left: 20%;
    width: 450px;
    height: 450px;
    background: radial-gradient(circle, rgba(127, 82, 255, 0.6) 0%, rgba(0, 0, 0, 0) 70%);
  }

  &--bottom {
    bottom: -15%;
    right: 15%;
    width: 500px;
    height: 500px;
    background: radial-gradient(circle, rgba(0, 149, 213, 0.4) 0%, rgba(0, 0, 0, 0) 70%);
  }
}

.lobby-header {
  position: relative;
  z-index: 10;
  max-width: 960px;
  width: 100%;
  flex-shrink: 0;
  margin: 0 auto 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;

  &__brand {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__mascot {
    width: 36px;
    height: 36px;
    object-fit: contain;
  }

  &__title {
    font-size: 24px;
    font-weight: 700;
    letter-spacing: 3px;
    background: linear-gradient(135deg, #ffffff 30%, #a78bfa 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
  }

  &__hello {
    font-size: 13px;
    color: $text-dim;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }
}

.lobby-main {
  position: relative;
  z-index: 10;
  max-width: 960px;
  width: 100%;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
  flex: 1;
  min-height: 0;
}

.form-label {
  font-size: 13px;
  font-weight: 500;
  color: rgba(236, 233, 247, 0.85);
  letter-spacing: 1px;
}

.color-section {
  @include glass-panel;
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.color-picker__list {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 2px 4px;

  @include mobile {
    flex-wrap: wrap;
  }
}

.color-picker__dot {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: 2px solid transparent;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;

  &:hover {
    transform: scale(1.15);
  }

  &--active {
    transform: scale(1.25);
    box-shadow: 0 0 0 3px rgba(24, 20, 44, 1), 0 0 0 5px var(--dot-color), 0 0 12px var(--dot-color);
  }
}

.lobby-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;

  @include mobile {
    grid-template-columns: 1fr;
  }
}

.lobby-card {
  @include glass-panel;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;

  &__title {
    font-size: 18px;
    font-weight: 700;
    letter-spacing: 2px;
  }

  &__desc {
    font-size: 12px;
    color: $text-dim;
  }
}

.role-switch {
  display: flex;
  align-items: center;
  gap: 10px;

  &__label {
    font-size: 13px;
    color: rgba(236, 233, 247, 0.85);
  }
}

.create-settings {
  margin: 12px 0 16px;

  // 表单区域固定最大高度 + 内部滚动，卡片不被撑高
  &__scroll {
    max-height: 300px;
    overflow-y: auto;
    padding-right: 6px;
  }
}

.room-code-input {
  :deep(input) {
    letter-spacing: 6px;
    text-transform: uppercase;
    font-weight: 700;
    text-align: center;
  }
}

.history {
  @include glass-panel;
  padding: 16px 18px;
  // 占满剩余高度，战绩列表内部滚动
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;

  &__title {
    font-size: 15px;
    font-weight: 700;
    margin-bottom: 10px;
  }

  &__empty {
    font-size: 13px;
    color: $text-dim;
    text-align: center;
    padding: 12px 0;
  }

  &__list {
    display: flex;
    flex-direction: column;
    gap: 4px;
    overflow-y: auto;
    min-height: 0;
  }

  &__row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 10px;
    border: none;
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.04);
    color: $text-main;
    font-size: 13px;
    cursor: pointer;
    transition: background 0.15s ease;
    text-align: left;

    &:hover {
      background: rgba(127, 82, 255, 0.15);
    }
  }

  &__rank {
    min-width: 34px;
    font-weight: 700;
    color: $text-dim;

    &--top {
      color: #f1c40f;
    }
  }

  &__time {
    flex: 1;
  }

  &__meta {
    color: $text-dim;
    font-size: 12px;
  }
}

:deep(.history-modal) {
  width: min(480px, calc(100vw - 48px));
  margin: auto;
}

.history-modal {
  &__meta {
    font-size: 12px;
    color: $text-dim;
    margin-bottom: 10px;
  }

  &__board {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 3px 6px;
    font-size: 13px;
  }

  &__rank {
    width: 20px;
    color: $text-dim;
    font-variant-numeric: tabular-nums;
  }

  &__dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
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

  &__log-time {
    color: $text-dim;
    font-variant-numeric: tabular-nums;
    margin-right: 4px;
  }

  &__feed {
    margin-top: 12px;
    border-top: 1px solid rgba(255, 255, 255, 0.08);
    padding-top: 8px;

    &--detail {
      max-height: 32vh;
      overflow-y: auto;
    }
  }

  &__sub {
    font-size: 12px;
    color: $text-dim;
    margin-bottom: 4px;
  }

  &__feed-item {
    font-size: 12px;
    line-height: 1.6;
    opacity: 0.9;
  }
}
</style>
