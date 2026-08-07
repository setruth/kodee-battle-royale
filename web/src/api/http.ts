import { apiUrl } from './config'

/** 带 HTTP 状态码的 API 错误：message 为服务端 {error} 文案 */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  /** JWT；HTTP 走 Authorization: Bearer（D5） */
  token?: string | null
}

/**
 * fetch 封装：base 用 api/config.ts 的 apiUrl；
 * 非 2xx 解析 {error} 抛 ApiError；2xx 空体返回 null。
 */
export async function request<T = unknown>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (opts.token) headers['Authorization'] = `Bearer ${opts.token}`

  const res = await fetch(apiUrl(path), {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })

  const text = await res.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = null
    }
  }
  if (!res.ok) {
    const err = (data as { error?: string } | null)?.error
    throw new ApiError(res.status, err ?? `请求失败（${res.status}）`)
  }
  return data as T
}
