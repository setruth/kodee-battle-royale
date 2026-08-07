<script setup lang="ts">
import { ref } from 'vue'
import type { Vec2 } from '@/game/types'

const emit = defineEmits<{
  move: [v: Vec2]
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
    emit('move', { x: 0, y: 0 })
  } else {
    emit('move', { x: dx / maxRadius, y: dy / maxRadius })
  }
}

function onPointerUp(e: PointerEvent) {
  if (e.pointerId !== pointerId) return
  pointerId = null
  active.value = false
  knobX.value = 0
  knobY.value = 0
  emit('move', { x: 0, y: 0 })
}
</script>

<template>
  <div
    ref="zoneEl"
    class="joystick"
    :class="{ 'joystick--active': active }"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
  >
    <div class="joystick__knob" :style="{ transform: `translate(${knobX}px, ${knobY}px)` }" />
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;
@use '@/styles/mixins' as *;

.joystick {
  position: fixed;
  left: 36px;
  bottom: 36px;
  z-index: 40;
  width: 110px;
  height: 110px;
  border-radius: 50%;
  background: rgba(127, 82, 255, 0.08);
  border: 1.5px solid rgba(127, 82, 255, 0.35);
  @include flex-center;
  touch-action: none;
  transition: background 0.15s ease;

  &--active {
    background: rgba(127, 82, 255, 0.18);
  }

  &__knob {
    width: 46px;
    height: 46px;
    border-radius: 50%;
    background: rgba(127, 82, 255, 0.75);
    box-shadow: 0 0 12px rgba(127, 82, 255, 0.4);
    pointer-events: none;
  }

  @include mobile {
    left: calc(24px + env(safe-area-inset-left, 0px));
    bottom: calc(24px + env(safe-area-inset-bottom, 0px));
    width: 88px;
    height: 88px;

    &__knob {
      width: 36px;
      height: 36px;
    }
  }
}
</style>
