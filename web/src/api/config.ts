/**
 * 后端接入配置：服务器地址走环境变量，见 .env.development / .env.production。
 * VITE_API_BASE_URL 含 /api 前缀（代码里的请求路径不再写 /api）；更换服务器只改环境变量。
 */
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? ''

/** WS 地址（必填，如 ws://localhost:8011 / wss://域名，只到源站不带路径；/ws 由代码拼接） */
export const WS_BASE_URL: string = import.meta.env.VITE_WS_URL ?? ''

/** HTTP 接口拼接 */
export function apiUrl(path: string): string {
  return `${API_BASE_URL}${path}`
}

/** WS 地址：直接使用 VITE_WS_URL，不做推导 */
export function wsUrl(path: string): string {
  return `${WS_BASE_URL}${path}`
}
