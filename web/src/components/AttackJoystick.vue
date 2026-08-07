<script setup lang="ts">
import { ref } from 'vue'
import type { Vec2 } from '@/game/types'

const emit = defineEmits<{
  aim: [v: Vec2]
}>()

const knobX = ref(0)
const knobY = ref(0)
const active = ref(false)

const zoneEl = ref<HTMLElement | null>(null)

let pointerId: number | null = null
let baseCx = 0
let baseCy = 0
let maxRadius = 36

function onPointerDown(e: PointerEvent) {
  if (pointerId !== null) return
  pointerId = e.pointerId
  const rect = zoneEl.value!.getBoundingClientRect()
  baseCx = rect.left + rect.width / 2
  baseCy = rect.top + rect.height / 2
  maxRadius = Math.max(24, rect.width / 2 - 10)
  active.value = true
  ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
  onPointerMove(e)
}

function onPointerMove(e: PointerEvent) {
  if (e.pointerId !== pointerId) return
  let dx = e.clientX - baseCx
  let dy = e.clientY - baseCy
  const len = Math.hypot(dx, dy)
  if (len > maxRadius) {
    dx = (dx / len) * maxRadius
    dy = (dy / len) * maxRadius
  }
  knobX.value = dx
  knobY.value = dy
  if (len < maxRadius * 0.15) {
    emit('aim', { x: 0, y: 0 })
  } else {
    emit('aim', { x: dx / len, y: dy / len })
  }
}

function onPointerUp(e: PointerEvent) {
  if (e.pointerId !== pointerId) return
  pointerId = null
  active.value = false
  knobX.value = 0
  knobY.value = 0
  emit('aim', { x: 0, y: 0 })
}
</script>

<template>
  <div
    ref="zoneEl"
    class="attack-stick"
    :class="{ 'attack-stick--active': active }"
    @pointerdown.prevent="onPointerDown"
    @pointermove.prevent="onPointerMove"
    @pointerup.prevent="onPointerUp"
    @pointercancel.prevent="onPointerUp"
  >
    <span class="attack-stick__icon">⚔️</span>
    <div
      class="attack-stick__knob"
      :style="{ transform: `translate(calc(-50% + ${knobX}px), calc(-50% + ${knobY}px))` }"
    />
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;
@use '@/styles/mixins' as *;

.attack-stick {
  position: fixed;
  right: 36px;
  bottom: 36px;
  z-index: 40;
  width: 110px;
  height: 110px;
  border-radius: 50%;
  background: rgba(226, 68, 98, 0.08);
  border: 1.5px solid rgba(226, 68, 98, 0.35);
  touch-action: none;
  user-select: none;

  &--active {
    background: rgba(226, 68, 98, 0.18);
    border-color: rgba(226, 68, 98, 0.6);
  }

  &__icon {
    position: absolute;
    left: 50%;
    top: 50%;
    transform: translate(-50%, -50%);
    font-size: 20px;
    opacity: 0.5;
    pointer-events: none;
  }

  &__knob {
    position: absolute;
    left: 50%;
    top: 50%;
    width: 46px;
    height: 46px;
    border-radius: 50%;
    background: radial-gradient(circle at 35% 30%, #ff7a8a, $accent-orange);
    box-shadow: 0 0 12px rgba(226, 68, 98, 0.4);
    pointer-events: none;
  }

  @include mobile {
    right: calc(24px + env(safe-area-inset-right, 0px));
    bottom: calc(24px + env(safe-area-inset-bottom, 0px));
    width: 88px;
    height: 88px;

    &__icon {
      font-size: 16px;
    }

    &__knob {
      width: 36px;
      height: 36px;
    }
  }
}
</style>
