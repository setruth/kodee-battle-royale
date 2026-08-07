import { ref } from 'vue'
import { defineStore } from 'pinia'
import { request } from '@/api/http'
import { useRoomStore } from './room'

export interface AuthUser {
  userId: number
  username: string
  name: string
}

interface Session {
  token: string
  user: AuthUser
}

const STORAGE_KEY = 'npe-royale-auth'

function loadSession(): Session | null {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null')
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const saved = loadSession()
  const token = ref<string | null>(saved?.token ?? null)
  const user = ref<AuthUser | null>(saved?.user ?? null)

  function setSession(s: Session) {
    token.value = s.token
    user.value = s.user
    localStorage.setItem(STORAGE_KEY, JSON.stringify(s))
  }

  /**
   * 登录/注册合并入口：账号存在且密码对 → 登录；不存在 → 自动注册并登录。
   * 返回 created（true = 本次为自动注册），供 UI 提示。
   */
  async function enter(username: string, password: string): Promise<boolean> {
    const r = await request<Session & { created: boolean }>('/auth/enter', {
      method: 'POST',
      body: { username, password },
    })
    setSession({ token: r.token, user: r.user })
    return r.created
  }

  /** 清 token 并断 WS */
  function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem(STORAGE_KEY)
    useRoomStore().disconnect()
  }

  return { token, user, enter, logout }
})
