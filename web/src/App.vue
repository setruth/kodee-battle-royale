<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { NConfigProvider, NDialogProvider, NGlobalStyle, NMessageProvider, zhCN, dateZhCN, darkTheme } from 'naive-ui'
import { themeOverrides } from '@/theme'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'

const router = useRouter()

// D12 启动恢复：JWT 还在就问服务端自己所在房间与状态
// WAITING/COUNTDOWN → 回房间页；PLAYING/RESULT → 重建对局（服务端 WS 重挂后补发 gameStart）
onMounted(async () => {
  window.addEventListener('contextmenu', (e) => e.preventDefault())
  const auth = useAuthStore()
  if (!auth.token) return
  const roomStore = useRoomStore()
  try {
    const state = await roomStore.fetchCurrent()
    if (!state) return // 404：不在任何房间，正常进大厅
    roomStore.connect()
    if (state === 'waiting' || state === 'countdown') router.push('/room')
    else router.push('/game')
  } catch {
    /* 网络异常：留在大厅 */
  }
})
</script>

<template>
  <n-config-provider :theme="darkTheme" :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <n-global-style />
    <n-message-provider>
      <n-dialog-provider>
        <router-view />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
