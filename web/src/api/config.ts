/**
 * 后端接入配置：服务器地址走环境变量，见 .env.development / .env.production。
 * VITE_API_BASE_URL 含 /api 前缀（代码里的请求路径不再写 /api）；更换服务器只改环境变量。
 */
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? ''

/**
 * WS 与 API 保持同源：/kt15/api -> wss://当前域名/kt15。
 * 本地开发使用完整 API 地址时也会自动得到对应的 ws://localhost 地址。
 */
function inferWsBaseUrl(): string {
  const url = new URL(API_BASE_URL || '/api', window.location.origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = url.pathname.replace(/\/api\/?$/, '')
  url.search = ''
  url.hash = ''
  return url.toString().replace(/\/$/, '')
}

export const WS_BASE_URL: string = inferWsBaseUrl()

/** HTTP 接口拼接 */
export function apiUrl(path: string): string {
  return `${API_BASE_URL}${path}`
}

/** WS 地址：与 API 使用相同域名和部署路径。 */
export function wsUrl(path: string): string {
  return `${WS_BASE_URL}${path}`
}
