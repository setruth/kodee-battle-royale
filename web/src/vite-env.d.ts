/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端服务器地址，如 http://localhost:8080 */
  readonly VITE_API_BASE_URL?: string
  /** WebRTC STUN 服务器（D17） */
  readonly VITE_STUN_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
