<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NInput, NModal, useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import RulesContent from '@/components/RulesContent.vue'

const router = useRouter()
const auth = useAuthStore()
const message = useMessage()

const username = ref('')
const password = ref('')
const loading = ref(false)
const showRules = ref(false)

// 前端预校验（D3/D4，与服务端 Validation 同规则）
const USERNAME_RE = /^[A-Za-z0-9_]+$/
const PASSWORD_RE = /^[\x21-\x7E]+$/

function validate(): string | null {
  const u = username.value.trim()
  if (u.length < 3 || u.length > 20) return '用户名需要 3–20 个字符'
  if (!USERNAME_RE.test(u)) return '用户名只能包含字母、数字、下划线'
  if (['admin', 'root', 'null'].includes(u.toLowerCase())) return '该用户名为保留字，请换一个'
  const p = password.value
  if (p.length < 6 || p.length > 64) return '密码需要 6–64 位'
  if (!PASSWORD_RE.test(p)) return '密码只能是 ASCII 可见字符（不含空格与中文）'
  return null
}

async function submit() {
  const err = validate()
  if (err) {
    message.warning(err)
    return
  }
  // 进入按钮点击 = 用户手势：趁此请求全屏，SPA 跳转不刷新页面，全屏带进游戏
  document.documentElement.requestFullscreen?.().catch(() => {})
  loading.value = true
  try {
    const created = await auth.enter(username.value.trim(), password.value)
    if (created) message.success('已自动注册并登录')
    router.push('/lobby')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '网络异常，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 背景动态粒子/光晕装饰 -->
    <div class="bg-glow bg-glow--top" />
    <div class="bg-glow bg-glow--bottom" />

    <main class="login">
      <header class="login__brand">
        <img src="@/assets/player.png" class="login__mascot" alt="Kodee" />
        <h1 class="login__title">Kodee大逃杀</h1>
        <p class="login__subtitle">Kodee · Battle Royale</p>
      </header>

      <div class="login__card">
        <form class="login__form" @submit.prevent="submit">
          <div class="form-group">
            <label class="form-label">用户名</label>
            <NInput
              v-model:value="username"
              placeholder="3–20 位字母、数字、下划线"
              maxlength="20"
              size="large"
              clearable
              @keydown.enter.prevent="submit"
            />
          </div>

          <div class="form-group">
            <label class="form-label">密码</label>
            <NInput
              v-model:value="password"
              type="password"
              show-password-on="click"
              placeholder="6–64 位 ASCII 可见字符"
              maxlength="64"
              size="large"
              @keydown.enter.prevent="submit"
            />
          </div>

          <NButton
            type="primary"
            size="large"
            block
            class="login__submit-btn"
            :loading="loading"
            @click="submit"
          >
            ⚡ 进入游戏
          </NButton>
          <p class="login__hint">账号不存在将自动注册</p>

          <button type="button" class="login__rules-link" @click="showRules = true">
            📜 游戏规则 & 道具说明
          </button>
        </form>
      </div>
    </main>

    <NModal
      v-model:show="showRules"
      preset="card"
      title="游戏规则与道具"
      class="rules-modal"
      style="width: 580px; max-width: calc(100vw - 32px); margin: auto;"
    >
      <RulesContent />
    </NModal>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;
@use '@/styles/mixins' as *;

.login-page {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  @include flex-center;
  padding: 24px;
  background: radial-gradient(circle at 50% 30%, #1e1838 0%, #0d0b18 80%);
  overflow: hidden;

  @media (max-height: 520px) {
    overflow-y: auto;
  }

  @include mobile {
    padding: 16px;
  }
}

.bg-glow {
  position: absolute;
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

.login {
  position: relative;
  z-index: 10;
  width: 100%;
  max-width: 420px;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__brand {
    text-align: center;
    margin-bottom: 28px;
  }

  &__mascot {
    width: 64px;
    height: 64px;
    object-fit: contain;
    margin-bottom: 10px;
    filter: drop-shadow(0 4px 16px rgba(127, 82, 255, 0.45));
  }

  &__title {
    font-size: 28px;
    font-weight: 700;
    letter-spacing: 3px;
    background: linear-gradient(135deg, #ffffff 30%, #a78bfa 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    text-shadow: 0 4px 20px rgba(127, 82, 255, 0.3);
  }

  &__subtitle {
    margin-top: 8px;
    font-size: 12px;
    letter-spacing: 4px;
    color: rgba(236, 233, 247, 0.6);
    text-transform: uppercase;
  }

  &__card {
    width: 100%;
    border: 1px solid rgba(127, 82, 255, 0.22);
    border-radius: 16px;
    background: rgba(24, 20, 44, 0.75);
    backdrop-filter: blur(20px);
    box-shadow: 0 24px 48px rgba(0, 0, 0, 0.45), 0 0 32px rgba(127, 82, 255, 0.12);
    padding: 24px;
  }

  &__form {
    display: flex;
    flex-direction: column;
    gap: 20px;
    padding-top: 8px;
  }

  &__hint {
    margin: -10px 0 0;
    text-align: center;
    font-size: 12px;
    color: rgba(236, 233, 247, 0.55);
    letter-spacing: 1px;
  }

  &__submit-btn {
    margin-top: 6px;
    height: 46px;
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 2px;
    border-radius: 10px;
    background: linear-gradient(135deg, #7f52ff 0%, #6366f1 100%);
    box-shadow: 0 6px 20px rgba(127, 82, 255, 0.4);
    transition: transform 0.15s ease, box-shadow 0.15s ease;

    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 8px 25px rgba(127, 82, 255, 0.6);
    }

    &:active {
      transform: translateY(1px);
    }
  }

  &__rules-link {
    margin-top: 4px;
    padding: 8px;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 8px;
    color: rgba(236, 233, 247, 0.7);
    font-size: 13px;
    letter-spacing: 1px;
    cursor: pointer;
    transition: all 0.2s ease;
    text-align: center;

    &:hover {
      background: rgba(127, 82, 255, 0.15);
      color: #a78bfa;
      border-color: rgba(127, 82, 255, 0.3);
    }
  }
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 13px;
  font-weight: 500;
  color: rgba(236, 233, 247, 0.85);
  letter-spacing: 1px;
}

:deep(.rules-modal) {
  width: min(560px, calc(100vw - 48px));
  margin: auto;
  border-radius: 16px;
  border: 1px solid rgba(127, 82, 255, 0.25);
  background: rgba(22, 18, 40, 0.95) !important;
  backdrop-filter: blur(24px);
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.65), 0 0 30px rgba(127, 82, 255, 0.2);

  .n-card-header {
    text-align: center;
    padding: 20px 24px 12px;

    .n-card-header__main {
      font-size: 18px;
      font-weight: 700;
      color: #ffffff;
      letter-spacing: 2px;
    }
  }

  .n-card__content {
    padding: 12px 24px 24px;
    max-height: 70vh;
    overflow-y: auto;

    &::-webkit-scrollbar {
      width: 6px;
    }

    &::-webkit-scrollbar-thumb {
      background: rgba(127, 82, 255, 0.35);
      border-radius: 3px;
    }
  }
}
</style>
