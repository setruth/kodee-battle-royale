<script setup lang="ts">
const emit = defineEmits<{
  emote: []
}>()
</script>

<template>
  <button class="emote-btn" @pointerdown.prevent="emit('emote')">💬</button>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;
@use '@/styles/mixins' as *;

// 表情小圆钮：挂在攻击摇杆上方
.emote-btn {
  position: fixed;
  right: 52px;
  bottom: 160px;
  z-index: 40;
  width: 48px;
  height: 48px;
  border: none;
  border-radius: 50%;
  @include flex-center;
  font-size: 20px;
  color: #fff;
  cursor: pointer;
  user-select: none;
  touch-action: none;
  background: radial-gradient(circle at 35% 30%, $primary-hover, $primary-pressed);
  box-shadow: 0 4px 16px rgba(127, 82, 255, 0.5);
  transition: transform 0.08s ease;

  @include mobile {
    right: calc(34px + env(safe-area-inset-right, 0px));
    bottom: calc(122px + env(safe-area-inset-bottom, 0px));
    width: 38px;
    height: 38px;
    font-size: 16px;
  }
}
</style>
