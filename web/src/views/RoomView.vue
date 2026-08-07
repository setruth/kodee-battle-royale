<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { NButton, NCollapse, NCollapseItem, NInputNumber, NModal, NSwitch, NTooltip, useDialog, useMessage } from 'naive-ui'
import { useRoomStore } from '@/stores/room'
import type { RoleName } from '@/stores/room'
import SettingsForm from '@/components/SettingsForm.vue'
import RulesContent from '@/components/RulesContent.vue'
import { useFullscreen } from '@/utils/fullscreen'
import { DEFAULT_SETTINGS, cloneSettings } from '@/game/settings'
import type { GameSettings } from '@/game/settings'
import { COLORS } from '@/theme/palette'
import playerUrl from '@/assets/player.png'

const router = useRouter()
const roomStore = useRoomStore()
const message = useMessage()
const dialog = useDialog()
const { isFullscreen, toggleFullscreen } = useFullscreen()

const room = computed(() => roomStore.room)
const me = computed(() => roomStore.myMember)
const isHost = computed(() => roomStore.isHost)

onMounted(async () => {
  // WS 挂载时重连（D12：服务端按 userId 重挂 session 并补发 room/gameStart）
  roomStore.connect()
  if (!roomStore.room) {
    try {
      const state = await roomStore.fetchCurrent()
      if (!state) router.replace('/lobby')
    } catch {
      message.error('网络异常，请稍后重试')
      router.replace('/lobby')
    }
  }
})

// ── 浏览器退回（回 /lobby）确认：确认则调 leave 放行，取消留房 ──
// 程序内主动跳转不触发：gameStart→/game（目标非 /lobby）；kicked/closed/离开按钮/探针失败→已清 room（roomStore.room 为 null）
onBeforeRouteLeave(async (to) => {
  if (to.path !== '/lobby' || !roomStore.room) return true
  const ok = await new Promise<boolean>((resolve) => {
    let settled = false
    const done = (v: boolean) => {
      if (!settled) {
        settled = true
        resolve(v)
      }
    }
    dialog.warning({
      title: '离开房间？',
      content: isHost.value ? '房主离开将解散房间' : '确定离开房间？',
      positiveText: '离开',
      negativeText: '取消',
      onPositiveClick: () => done(true),
      onNegativeClick: () => done(false),
      onClose: () => done(false),
    })
  })
  if (!ok) return false
  try {
    await roomStore.leaveRoom()
  } catch {
    /* 服务端状态可能已变，本地照常放行 */
  }
  return true
})

// ── 规则弹窗（复用 RulesContent，按当前房间 settings 动态渲染）──
const showRules = ref(false)

/** Kodee 小人物按成员颜色染色（multiply 保留明暗 + destination-in 还原透明边），8 色预生成 dataURL */
const avatars = ref<Record<string, string>>({})
{
  const img = new Image()
  img.onload = () => {
    const map: Record<string, string> = {}
    for (const color of COLORS) {
      const c = document.createElement('canvas')
      c.width = img.width
      c.height = img.height
      const g = c.getContext('2d')!
      g.drawImage(img, 0, 0)
      g.globalCompositeOperation = 'multiply'
      g.fillStyle = color
      g.fillRect(0, 0, c.width, c.height)
      g.globalCompositeOperation = 'destination-in'
      g.drawImage(img, 0, 0)
      map[color] = c.toDataURL()
    }
    avatars.value = map
  }
  img.src = playerUrl
}

// ── 房间码复制 ──
async function copyCode() {
  if (!room.value) return
  try {
    await navigator.clipboard.writeText(room.value.code)
    message.success('房间码已复制')
  } catch {
    message.warning(`房间码：${room.value.code}`)
  }
}

// ── 房主：bot 数量（按 WS 广播同步 + 3 秒超时 Loading 保底）──
const botsDisplay = ref<number | null>(null)
const botsLoading = ref(false)
let botsTimer = 0
let botTimeoutTimer = 0
let botsSeq = 0

watch(
  () => room.value?.bots,
  (v) => {
    if (v !== undefined) {
      if (botsLoading.value) {
        window.clearTimeout(botTimeoutTimer)
        botsLoading.value = false
      }
      botsDisplay.value = v
    }
  },
  { immediate: true },
)

function onBotsChange(v: number | null) {
  if (v === null || !room.value || botsLoading.value) return
  botsDisplay.value = v
  botsLoading.value = true

  window.clearTimeout(botsTimer)
  window.clearTimeout(botTimeoutTimer)

  // 3 秒超时保底：若 3 秒内未收到 WS 响应，解除 loading 并提示超时
  botTimeoutTimer = window.setTimeout(() => {
    if (botsLoading.value) {
      botsLoading.value = false
      botsDisplay.value = room.value?.bots ?? 0
      message.error('设置 Bot 超时，请重试')
    }
  }, 3000)

  botsTimer = window.setTimeout(() => {
    const seq = ++botsSeq
    roomStore
      .setBots(v)
      .catch((e) => {
        window.clearTimeout(botTimeoutTimer)
        botsLoading.value = false
        message.error(e instanceof Error ? e.message : '设置失败')
        if (seq === botsSeq) botsDisplay.value = room.value?.bots ?? 0
      })
  }, 100)
}

// ── 快捷 Bot 操控逻辑 ──
const showBotModal = ref(false)
const modalBotValue = ref(0)

function addOneBot() {
  const current = botsDisplay.value ?? 0
  onBotsChange(current + 1)
}

function openBotModal() {
  modalBotValue.value = botsDisplay.value ?? 0
  showBotModal.value = true
}

function confirmBotModal() {
  onBotsChange(modalBotValue.value)
  showBotModal.value = false
}

// ── 房间规则面板：房主可编辑保存；其余人只读跟随 WS 广播 ──
/** 当前生效配置（广播缺省时用默认值兜底） */
const currentSettings = computed(() => room.value?.settings ?? DEFAULT_SETTINGS)
/** 房主本地编辑草稿；dirty = 有未保存修改（编辑中不被广播覆盖） */
const settingsDraft = ref<GameSettings>(cloneSettings(DEFAULT_SETTINGS))
const settingsDirty = ref(false)
const settingsSaving = ref(false)
watch(
  currentSettings,
  (s) => {
    if (!settingsDirty.value) settingsDraft.value = cloneSettings(s)
  },
  { immediate: true },
)
function onSettingsDraft(s: GameSettings) {
  settingsDraft.value = s
  settingsDirty.value = true
}
async function onSaveSettings() {
  settingsSaving.value = true
  try {
    await roomStore.saveSettings(settingsDraft.value)
    settingsDirty.value = false
    message.success('规则已保存')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
    // 失败回滚：草稿恢复为广播的实际配置
    settingsDraft.value = cloneSettings(currentSettings.value)
    settingsDirty.value = false
  } finally {
    settingsSaving.value = false
  }
}

// ── 角色切换（D18：任何成员变动全员 ready 重置，服务端处理）──
function onRoleChange(v: RoleName) {
  roomStore.setRole(v).catch((e) => message.error(e instanceof Error ? e.message : '切换失败'))
}

// ── 准备 ──
const readyBusy = ref(false)
async function toggleReady() {
  if (!me.value) return
  readyBusy.value = true
  try {
    await roomStore.setReady(!me.value.ready)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    readyBusy.value = false
  }
}

// ── 踢人（房主，二次确认弹窗防误触）──
async function onKick(userId: number) {
  const target = room.value?.members.find((m) => m.id === userId)
  const name = target?.name || '该玩家'
  dialog.warning({
    title: '移出房间',
    content: `确定要将玩家 "${name}" 移出房间吗？`,
    positiveText: '移出',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await roomStore.kick(userId)
        message.success(`已移出玩家 "${name}"`)
      } catch (e) {
        message.error(e instanceof Error ? e.message : '踢出失败')
      }
    },
  })
}

// ── 开始游戏（D11：全员 PLAYER 已准备 + 玩家角色人数 + bot 数 ≥ 1）──
const startDisabledReason = computed(() => {
  const r = room.value
  if (!r) return '房间加载中'
  if (r.state !== 'waiting') return '对局已开始'
  const players = r.members.filter((m) => m.role === 'player')
  if (!players.every((m) => m.ready)) return '还有玩家未准备'
  if (players.length + r.bots < 1) return '至少需要 1 名玩家或 bot'
  return ''
})

const startBusy = ref(false)
async function onStart() {
  startBusy.value = true
  try {
    await roomStore.start()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '开始失败')
  } finally {
    startBusy.value = false
  }
}



// ── 离开房间 ──
async function onLeave() {
  try {
    await roomStore.leaveRoom()
  } catch {
    /* 服务端状态可能已变，本地照常退出 */
  }
  router.replace('/lobby')
}

// ── 倒计时覆盖层：收 countdown 后本地 3→1 递减 ──
const count = ref<number | null>(null)
let countTimer = 0
watch(
  () => roomStore.countdown,
  (n) => {
    if (n === null) return
    window.clearInterval(countTimer)
    count.value = n
    countTimer = window.setInterval(() => {
      if (count.value !== null && count.value > 1) count.value -= 1
      else window.clearInterval(countTimer)
    }, 1000)
  },
)

// ── gameStart → 进对局（无缝替换当前路由，不新增历史记录/不另开标签页） ──
watch(
  () => roomStore.gameStartPayload,
  (p) => {
    if (p) {
      count.value = null
      router.replace('/game')
    }
  },
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
</script>

<template>
  <div class="room-page">
    <div class="bg-glow bg-glow--top" />
    <div class="bg-glow bg-glow--bottom" />

    <main v-if="room" class="room">
      <header class="room__header">
        <p class="room__label">房间码</p>
        <button type="button" class="room__code" title="点击复制" @click="copyCode">
          {{ room.code }} 📋
        </button>
        <p class="room__hint">分享给好友加入 · {{ room.members.length }} 人</p>
      </header>

      <!-- 成员列表（卡片网格/横向滚动） -->
      <section class="members-container">
        <div class="members-header">
          <span class="members-header__title">
            👥 成员大厅 ({{ room.members.length }} 玩家{{ room.bots > 0 ? ` + ${room.bots} Bot` : '' }})
          </span>
        </div>

        <div class="members">
          <!-- 玩家/旁观者成员卡片（两行高度） -->
          <div
            v-for="m in room.members"
            :key="m.id"
            class="member-card"
            :class="{
              'member-card--host-ready': m.id === room.hostId && m.ready,
              'member-card--ready': m.id !== room.hostId && m.ready && m.role === 'player',
              'member-card--spectator': m.role === 'spectator'
            }"
          >
            <!-- 左侧：形象头像 -->
            <div class="member-card__avatar-wrap" :style="{ borderColor: m.color }">
              <img v-if="avatars[m.color]" :src="avatars[m.color]" class="member-card__avatar" alt="" />
              <i v-else class="member-card__dot" :style="{ background: m.color }" />
            </div>

            <!-- 右侧：两行内容（名字 + 准备状态） -->
            <div class="member-card__info">
              <div class="member-card__row-top">
                <span v-if="m.id === room.hostId" class="member-card__host-crown" title="房主">👑</span>
                <span class="member-card__name" :title="m.name">{{ m.name }}</span>
                <button
                  v-if="isHost && m.id !== room.hostId"
                  type="button"
                  class="member-card__kick"
                  title="移出房间"
                  @click="onKick(m.id)"
                >
                  ✕
                </button>
              </div>

              <div class="member-card__row-bottom">
                <span
                  v-if="m.id === room.hostId"
                  class="status-pill"
                  :class="m.ready ? 'status-pill--host-ready' : 'status-pill--waiting'"
                >
                  {{ m.ready ? '👑 房主 (已准备)' : '👑 房主' }}
                </span>
                <span v-else-if="m.role === 'spectator'" class="status-pill status-pill--spec">👁 旁观</span>
                <span v-else-if="m.ready" class="status-pill status-pill--ready">✓ 已准备</span>
                <span v-else class="status-pill status-pill--waiting">… 未准备</span>
              </div>
            </div>
          </div>

          <!-- Bot 机器人卡片（默认准备激活效果） -->
          <div
            v-for="i in room.bots"
            :key="'bot-' + i"
            class="member-card member-card--bot-ready"
          >
            <div class="member-card__avatar-wrap member-card__avatar-wrap--bot">
              <img
                v-if="avatars[COLORS[(i - 1) % COLORS.length]]"
                :src="avatars[COLORS[(i - 1) % COLORS.length]]"
                class="member-card__avatar"
                alt=""
              />
              <span v-else class="member-card__bot-icon">🤖</span>
            </div>

            <div class="member-card__info">
              <div class="member-card__row-top">
                <span class="member-card__name">Bot #{{ i }}</span>
              </div>
              <div class="member-card__row-bottom">
                <span class="status-pill status-pill--bot">🤖 机器人 (已就绪)</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 房间规则面板（WAITING 可见；房主可编辑，其余人只读跟随广播） -->
      <section v-if="room.state === 'waiting'" class="rules-panel">
        <NCollapse>
          <NCollapseItem title="房间规则" name="settings">
            <template v-if="isHost">
              <SettingsForm :settings="settingsDraft" @update:settings="onSettingsDraft" />
              <div class="rules-panel__actions">
                <NButton
                  type="primary"
                  size="small"
                  :loading="settingsSaving"
                  :disabled="!settingsDirty"
                  @click="onSaveSettings"
                >
                  保存规则
                </NButton>
                <span v-if="settingsDirty" class="rules-panel__dirty">● 有未保存修改</span>
              </div>
            </template>
            <RulesContent v-else :settings="currentSettings" />
          </NCollapseItem>
        </NCollapse>
      </section>

      <!-- 控制区 -->
      <section v-if="room.state === 'waiting'" class="controls">
        <div v-if="isHost" class="controls__row controls__row--bot">
          <div class="controls__bot-info">
            <span class="controls__label">🤖 Bot 机器人</span>
            <span class="controls__bot-count">
              {{ botsDisplay ?? 0 }} 个
              <span v-if="botsLoading" class="controls__bot-loading-text">（同步中…）</span>
            </span>
          </div>
          <div class="controls__bot-actions">
            <NButton
              type="primary"
              size="small"
              secondary
              :loading="botsLoading"
              :disabled="botsLoading"
              @click="addOneBot"
            >
              +1 Bot
            </NButton>
            <NButton
              size="small"
              quaternary
              :loading="botsLoading"
              :disabled="botsLoading"
              @click="openBotModal"
            >
              ⚙️ 设置总 Bot 数
            </NButton>
            <NButton
              v-if="(botsDisplay ?? 0) > 0"
              size="small"
              quaternary
              type="error"
              :loading="botsLoading"
              :disabled="botsLoading"
              @click="onBotsChange(0)"
            >
              清空
            </NButton>
          </div>
        </div>
        <!-- 角色切换仅房主可用（服务端 403 兜底；加入者固定玩家） -->
        <div v-if="isHost" class="controls__row">
          <span class="controls__label">我的角色</span>
          <NSwitch
            :value="me?.role ?? 'player'"
            checked-value="player"
            unchecked-value="spectator"
            @update:value="onRoleChange"
          >
            <template #checked>玩家</template>
            <template #unchecked>旁观</template>
          </NSwitch>
        </div>
        <NButton
          v-if="me?.role === 'player'"
          :type="me.ready ? 'default' : 'primary'"
          size="large"
          block
          :loading="readyBusy"
          class="controls__ready"
          @click="toggleReady"
        >
          {{ me.ready ? '取消准备' : '准备' }}
        </NButton>
      </section>

      <section v-else-if="room.state === 'result'" class="controls">
        <p class="controls__result-text">对局已结束</p>
        <NButton type="primary" size="large" block @click="onLeave">返回大厅</NButton>
      </section>

      <footer class="room__footer">
        <NButton size="large" quaternary @click="showRules = true">游戏规则</NButton>
        <NButton size="large" quaternary @click="toggleFullscreen">
          {{ isFullscreen ? '↙↘ 窗口' : '⛶ 全屏' }}
        </NButton>
        <NButton size="large" class="room__leave" @click="onLeave">离开房间</NButton>
        <NTooltip v-if="isHost && room.state === 'waiting'" trigger="hover" :disabled="!startDisabledReason">
          <template #trigger>
            <span class="room__start-wrap">
              <NButton
                type="primary"
                size="large"
                class="room__start"
                :disabled="!!startDisabledReason"
                :loading="startBusy"
                @click="onStart"
              >
                开始游戏
              </NButton>
            </span>
          </template>
          {{ startDisabledReason }}
        </NTooltip>
      </footer>
    </main>

    <div v-else class="room-loading">房间加载中…</div>

    <!-- 规则弹窗：按当前房间 settings 动态渲染 -->
    <NModal
      v-model:show="showRules"
      preset="card"
      title="游戏规则与道具"
      class="rules-modal"
      style="width: 580px; max-width: calc(100vw - 32px); margin: auto;"
    >
      <RulesContent :settings="currentSettings" />
    </NModal>

    <!-- 快捷设置 Bot 数量弹窗（小弹窗 + 确认按钮） -->
    <NModal
      v-model:show="showBotModal"
      preset="card"
      title="设置 Bot 数量"
      class="bot-modal"
      style="width: 320px; max-width: calc(100vw - 32px); margin: auto;"
    >
      <div class="bot-modal__body">
        <p class="bot-modal__desc">请输入房间所需填补的 Bot 机器人总数：</p>
        <div class="bot-modal__input-wrap">
          <NInputNumber
            v-model:value="modalBotValue"
            :min="0"
            :max="999"
            size="medium"
            style="width: 100%"
            placeholder="请输入数量"
          />
        </div>
        <div class="bot-modal__actions">
          <NButton size="small" @click="showBotModal = false">取消</NButton>
          <NButton type="primary" size="small" @click="confirmBotModal">确认</NButton>
        </div>
      </div>
    </NModal>

    <!-- 3s 倒计时覆盖层 -->
    <Transition name="fade">
      <div v-if="count !== null" class="countdown">
        <span class="countdown__num">{{ count }}</span>
        <p class="countdown__text">游戏即将开始</p>
      </div>
    </Transition>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;
@use '@/styles/mixins' as *;

.room-page {
  position: absolute;
  inset: 0;
  overflow-y: auto;
  background: radial-gradient(circle at 50% 30%, #1e1838 0%, #0d0b18 80%);
  padding: 24px;
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

.room {
  position: relative;
  z-index: 10;
  max-width: min(780px, 100%);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 18px;

  &__header {
    text-align: center;
  }

  &__label {
    font-size: 13px;
    color: $text-dim;
    letter-spacing: 2px;
  }

  &__code {
    margin-top: 6px;
    padding: 6px 22px;
    border: 1px dashed rgba(127, 82, 255, 0.5);
    border-radius: 12px;
    background: rgba(127, 82, 255, 0.12);
    color: #fff;
    font-size: 34px;
    font-weight: 800;
    letter-spacing: 10px;
    cursor: pointer;
    user-select: text;
    transition: background 0.15s ease;

    &:hover {
      background: rgba(127, 82, 255, 0.25);
    }
  }

  &__hint {
    margin-top: 8px;
    font-size: 12px;
    color: $text-dim;
  }

  &__footer {
    display: flex;
    gap: 12px;
    justify-content: center;
  }

  &__start-wrap {
    display: inline-block;
  }

  &__start {
    min-width: 180px;
  }
}

/* 成员大厅卡片布局 */
.members-container {
  @include glass-panel;
  padding: 16px;
  width: 100%;
}

.members-header {
  margin-bottom: 12px;

  &__title {
    font-size: 13px;
    color: $text-dim;
    letter-spacing: 1px;
  }
}

.members {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 10px;
  max-height: 48vh;
  overflow-y: auto;
  padding: 4px;

  @include mobile {
    display: flex;
    flex-wrap: nowrap;
    overflow-x: auto;
    overflow-y: hidden;
    padding-bottom: 8px;
    gap: 10px;
    max-height: none;
  }
}

.member-card {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 64px;
  padding: 8px 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  transition: all 0.2s ease;
  position: relative;

  @include mobile {
    width: 160px;
  }

  &:hover {
    transform: translateY(-2px);
    background: rgba(255, 255, 255, 0.07);
  }

  // 玩家准备状态：绿色亮边
  &--ready {
    border-color: rgba(46, 204, 113, 0.7);
    background: rgba(46, 204, 113, 0.08);
    box-shadow: 0 0 14px rgba(46, 204, 113, 0.3);
  }

  // 房主准备状态：金色亮边
  &--host-ready {
    border-color: rgba(255, 209, 102, 0.85);
    background: rgba(255, 209, 102, 0.1);
    box-shadow: 0 0 16px rgba(255, 209, 102, 0.35);
  }

  // 机器人准备就绪：蓝/青色亮边激活效果
  &--bot-ready {
    border-color: rgba(0, 149, 213, 0.6);
    background: rgba(0, 149, 213, 0.08);
    box-shadow: 0 0 12px rgba(0, 149, 213, 0.25);
  }

  &--spectator {
    opacity: 0.65;
  }

  &__avatar-wrap {
    width: 42px;
    height: 42px;
    flex-shrink: 0;
    border-radius: 50%;
    background: rgba(18, 16, 28, 0.6);
    border: 2px solid rgba(127, 82, 255, 0.4);
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);

    &--bot {
      border-color: rgba(0, 149, 213, 0.4);
    }
  }

  &__avatar {
    width: 34px;
    height: 34px;
    object-fit: contain;
    filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.4));
  }

  &__bot-icon {
    font-size: 22px;
  }

  &__dot {
    width: 18px;
    height: 18px;
    border-radius: 50%;
  }

  &__info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 4px;
  }

  &__row-top {
    display: flex;
    align-items: center;
    gap: 4px;
    position: relative;
  }

  &__host-crown {
    font-size: 12px;
    flex-shrink: 0;
  }

  &__name {
    font-size: 13px;
    font-weight: 600;
    color: $text-main;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__kick {
    margin-left: auto;
    border: none;
    background: rgba(255, 90, 110, 0.2);
    color: $hp-red;
    cursor: pointer;
    font-size: 11px;
    width: 18px;
    height: 18px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    transition: background 0.15s ease;

    &:hover {
      background: $hp-red;
      color: #fff;
    }
  }

  &__row-bottom {
    display: flex;
    align-items: center;
  }
}

.status-pill {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 10px;
  letter-spacing: 0.5px;
  font-weight: 600;
  white-space: nowrap;

  &--host-ready {
    background: linear-gradient(135deg, rgba(255, 209, 102, 0.25), rgba(255, 170, 0, 0.15));
    color: #ffd166;
    border: 1px solid rgba(255, 209, 102, 0.4);
  }

  &--ready {
    background: linear-gradient(135deg, rgba(46, 204, 113, 0.25), rgba(39, 174, 96, 0.15));
    color: #2ecc71;
    border: 1px solid rgba(46, 204, 113, 0.4);
  }

  &--waiting {
    background: rgba(255, 255, 255, 0.08);
    color: $text-dim;
    border: 1px solid rgba(255, 255, 255, 0.12);
  }

  &--spec {
    background: rgba(167, 139, 250, 0.15);
    color: #a78bfa;
    border: 1px solid rgba(167, 139, 250, 0.3);
  }

  &--bot {
    background: rgba(0, 149, 213, 0.15);
    color: #55c8ff;
    border: 1px solid rgba(0, 149, 213, 0.3);
  }
}

.rules-panel {
  width: min(960px, calc(100vw - 48px));
  align-self: center;

  &__actions {
    margin-top: 12px;
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__dirty {
    font-size: 12px;
    color: #f0a020;
  }
}

.controls {
  @include glass-panel;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 18px;

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    min-height: 34px;

    &--bot {
      flex-wrap: wrap;
    }
  }

  &__bot-info {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__bot-count {
    font-size: 13px;
    font-weight: 700;
    color: #55c8ff;
  }

  &__bot-loading-text {
    font-size: 12px;
    font-weight: 400;
    color: #f0a020;
  }

  &__bot-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__label {
    font-size: 13px;
    color: rgba(236, 233, 247, 0.85);
  }

  &__ready {
    height: 48px;
    font-size: 16px;
    font-weight: 700;
    letter-spacing: 2px;
  }

  &__result-text {
    text-align: center;
    color: $text-dim;
  }
}

:deep(.rules-modal) {
  width: min(720px, calc(100vw - 48px));
  margin: auto;
}

:deep(.bot-modal) {
  width: min(320px, calc(100vw - 48px));
  margin: auto;
}

.bot-modal {
  &__body {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  &__desc {
    font-size: 13px;
    color: $text-dim;
  }

  &__input-wrap {
    width: 100%;
  }

  &__actions {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 10px;
    padding-top: 4px;
  }
}

.room-loading {
  position: relative;
  z-index: 10;
  text-align: center;
  color: $text-dim;
  margin-top: 40vh;
}

.countdown {
  position: fixed;
  inset: 0;
  z-index: 60;
  @include flex-center;
  flex-direction: column;
  gap: 12px;
  background: rgba(18, 16, 28, 0.85);
  backdrop-filter: blur(6px);

  &__num {
    font-size: 120px;
    font-weight: 800;
    color: #a78bfa;
    text-shadow: 0 0 60px rgba(127, 82, 255, 0.8);
    animation: countdown-pop 1s ease infinite;
  }

  &__text {
    font-size: 16px;
    color: $text-dim;
    letter-spacing: 4px;
  }
}

@keyframes countdown-pop {
  0% {
    transform: scale(1.3);
    opacity: 0;
  }
  20% {
    transform: scale(1);
    opacity: 1;
  }
  100% {
    transform: scale(0.95);
    opacity: 0.8;
  }
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.25s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
