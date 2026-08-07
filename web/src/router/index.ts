import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

export const router = createRouter({
  // hash 模式：兼容 gh-pages 等无 SPA fallback 的静态托管
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/lobby' },
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    {
      path: '/lobby',
      name: 'lobby',
      component: () => import('@/views/LobbyView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/room',
      name: 'room',
      component: () => import('@/views/RoomView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/game',
      name: 'game',
      component: () => import('@/views/GameView.vue'),
      meta: { requiresAuth: true },
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.token) return { name: 'login' }
  if (to.name === 'login' && auth.token) return { name: 'lobby' }
  return true
})
