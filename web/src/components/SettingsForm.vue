<script setup lang="ts">
import { ref, watch } from 'vue'
import { NInputNumber, NSwitch } from 'naive-ui'
import { DEFAULT_SETTINGS, cloneSettings } from '@/game/settings'
import type { GameSettings } from '@/game/settings'
import { ITEM_META } from '@/game/items'
import type { ItemKind } from '@/game/types'

/**
 * 房间规则表单（受控 v-model:settings，深色 HUD 风格）。
 * 圈数只编辑轮数（1–6），映射为默认半径序列前缀；半径本身不可自定义。
 */
const props = defineProps<{ settings: GameSettings }>()
const emit = defineEmits<{ 'update:settings': [GameSettings] }>()

/** 本地草稿：所有控件直接改它，变更后整体 emit 给父级 */
const local = ref<GameSettings>(cloneSettings(props.settings))

// 父级外部更新（如 WS 广播刷新/保存失败回滚）→ 同步草稿；与本地 emit 的往返用内容比对防环
watch(
  () => props.settings,
  (s) => {
    if (JSON.stringify(s) !== JSON.stringify(local.value)) local.value = cloneSettings(s)
  },
)
watch(
  local,
  (s) => {
    if (JSON.stringify(s) !== JSON.stringify(props.settings)) emit('update:settings', cloneSettings(s))
  },
  { deep: true },
)

/** 圈数（1–6）：改轮数时取默认半径序列前缀 */
const stageCount = ref(local.value.shrinkTargets.length)
watch(stageCount, (n) => {
  local.value.shrinkTargets = DEFAULT_SETTINGS.shrinkTargets.slice(0, Math.max(1, Math.min(6, n ?? 1)))
})
// 外部同步草稿时回填圈数显示
watch(
  () => local.value.shrinkTargets.length,
  (n) => {
    if (n !== stageCount.value) stageCount.value = n
  },
)

const itemKinds = ITEM_META.map((m) => ({ kind: m.kind, label: m.label, desc: m.desc }))
</script>

<template>
  <div class="settings-form">
    <div class="settings-form__grid">
      <div class="settings-form__row">
        <span class="settings-form__label">缩圈轮数</span>
        <NInputNumber v-model:value="stageCount" :min="1" :max="6" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">每圈收缩（秒）</span>
        <NInputNumber v-model:value="local.shrinkTime" :min="3" :max="60" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">收缩冷却（秒）</span>
        <NInputNumber v-model:value="local.shrinkCooldown" :min="5" :max="180" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">首轮静置（秒）</span>
        <NInputNumber v-model:value="local.firstIdle" :min="0" :max="120" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">队友伤害（固定 2 点）</span>
        <NSwitch v-model:value="local.friendlyFire" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">怪物触碰伤害</span>
        <NInputNumber v-model:value="local.monsterTouchDamage" :min="0" :max="100" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">初始 NPE 数</span>
        <NInputNumber v-model:value="local.monsterInitNpe" :min="0" :max="30" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">初始 SOE 数</span>
        <NInputNumber v-model:value="local.monsterInitSoe" :min="0" :max="30" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">每波 NPE 补充</span>
        <NInputNumber v-model:value="local.monsterWaveNpe" :min="0" :max="20" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">每波 SOE 补充</span>
        <NInputNumber v-model:value="local.monsterWaveSoe" :min="0" :max="20" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">轰炸区（OOM）</span>
        <NSwitch v-model:value="local.bombsEnabled" size="small" />
      </div>
      <div class="settings-form__row">
        <span class="settings-form__label">道具上限</span>
        <NInputNumber v-model:value="local.itemCount" :min="0" :max="30" size="small" />
      </div>
    </div>

    <p class="settings-form__sub">道具刷新权重</p>
    <div class="settings-form__grid settings-form__grid--weights">
      <div v-for="it in itemKinds" :key="it.kind" class="settings-form__row settings-form__row--weight">
        <span class="settings-form__label">
          {{ it.label }}
          <em class="settings-form__desc">{{ it.desc }}</em>
        </span>
        <NInputNumber
          :value="local.itemWeights[it.kind as ItemKind]"
          :min="0"
          :max="10"
          size="small"
          @update:value="(v: number | null) => (local.itemWeights[it.kind as ItemKind] = v ?? 0)"
        />
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;
@use '@/styles/mixins' as *;

.settings-form {
  text-align: left;

  &__grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px 16px;

    @include mobile {
      grid-template-columns: 1fr;
    }

    &--weights {
      grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    }
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  &__label {
    font-size: 13px;
    color: $text-dim;
    white-space: nowrap;
  }

  // 权重行：道具名 + 效果解释（允许换行）
  &__row--weight {
    align-items: flex-start;
  }

  &__row--weight &__label {
    white-space: normal;
    display: flex;
    flex-direction: column;
    gap: 2px;
    color: $text-main;
  }

  &__desc {
    font-style: normal;
    font-size: 11px;
    color: $text-dim;
    line-height: 1.4;
  }

  &__sub {
    margin: 14px 0 8px;
    font-size: 13px;
    color: $primary-hover;
    letter-spacing: 1px;
  }
}
</style>
