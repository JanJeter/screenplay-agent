import { ApiError } from './contract'
import { runErrorMessage } from './run-errors'
import { isLegacyWorkbench, javaApiBase, legacyReadOnlyMessage } from '../environment'
import type { Session } from '../types'

export type RequestOptions = { method?: string; body?: unknown; retry?: boolean; expectJson?: boolean; expectText?: boolean; signal?: AbortSignal }
export type AuthorizedRequest = <T>(path: string, options?: RequestOptions) => Promise<T>
const messages: Record<string, string> = {
  email_not_verified: '请先验证邮箱，再登录工作台。', account_disabled: '账号已停用，请联系团队管理员。',
  invalid_credentials: '邮箱或密码不正确，请检查后重试。', invalid_token: '这个链接已过期或已使用，请重新申请邮件。',
  rate_limited: '操作过于频繁，请稍后再试。', mail_unavailable: '邮件暂时无法发送，请稍后重试。',
  forbidden: '你没有执行此操作的权限。', access_denied: '你没有执行此操作的权限。'
}
export async function responseError(response: Response): Promise<ApiError> {
  const body = await response.json().catch(() => null) as { code?: string; message?: string } | null
  const code = body?.code?.toLowerCase()
  const message = runErrorMessage(code) ?? (code ? messages[code] : undefined) ?? body?.message
    ?? (response.status === 401 ? '登录已过期，请重新登录。' : response.status === 403 ? '你没有执行此操作的权限。' : '请求未完成，请稍后重试。')
  return new ApiError(response.status, message, body?.code)
}
function guardWrite(path: string, method: string) {
  if (isLegacyWorkbench && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method.toUpperCase()) && !['/auth/login', '/auth/refresh', '/auth/logout'].includes(path)) {
    throw new ApiError(403, legacyReadOnlyMessage, 'LEGACY_WORKBENCH_READ_ONLY')
  }
}
async function send(path: string, options: RequestOptions, session: Session | null) {
  const method = options.method ?? 'GET'; guardWrite(path, method)
  try {
    return await fetch(`${javaApiBase}/api/v1${path}`, {
      method, signal: options.signal,
      headers: { ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}), ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: options.body === undefined ? undefined : JSON.stringify(options.body)
    })
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error
    throw new Error('无法连接工作台，请检查网络和服务状态。')
  }
}
export async function publicAuthRequest<T>(path: string, body: unknown): Promise<T> {
  const response = await send(`/auth/${path}`, { method: 'POST', body }, null)
  if (!response.ok) throw await responseError(response)
  return await response.json() as T
}
export function createAuthorizedRequest(options: { getSession: () => Session | null; updateSession: (session: Session) => void; onExpired: () => void }): AuthorizedRequest {
  let refresh: Promise<boolean> | null = null
  const refreshSession = () => {
    if (refresh) return refresh
    refresh = (async () => {
      const session = options.getSession(); if (!session?.refreshToken) return false
      const response = await send('/auth/refresh', { method: 'POST', body: { refreshToken: session.refreshToken } }, null)
      if (!response.ok) return false
      const tokens = await response.json() as Session
      if (options.getSession() !== session) return options.getSession() !== null
      options.updateSession(tokens); return true
    })().finally(() => { refresh = null })
    return refresh
  }
  const request: AuthorizedRequest = async <T>(path: string, requestOptions: RequestOptions = {}) => {
    const sentSession = options.getSession(); const response = await send(path, requestOptions, sentSession)
    if (response.status === 401) {
      if (requestOptions.retry !== false && sentSession?.refreshToken) {
        const updated = options.getSession()
        const refreshed = updated && updated.accessToken !== sentSession.accessToken || await refreshSession().catch(() => false)
        if (refreshed) return request<T>(path, { ...requestOptions, retry: false })
      }
      options.onExpired()
    }
    if (!response.ok) throw await responseError(response)
    return (requestOptions.expectJson === false || response.status === 204 ? undefined : requestOptions.expectText ? await response.text() : await response.json()) as T
  }
  return request
}
