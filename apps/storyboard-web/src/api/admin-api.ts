import { useMemo } from 'react'
import { useAuth } from '../auth'
export type PageResult<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type AdminRole = { id: number; name: string; system: boolean; permissions: string[] }
export type Permission = { code: string; label: string; description: string }
export type AdminUser = { id: number; email: string; fullName: string; enabled: boolean; emailVerified: boolean; roles: Array<{ id: number; name: string }>; self: boolean }
export type Usage = { mode: 'live' | 'mock' | 'unknown'; providerRequests: number; inputTokens: number | null; outputTokens: number | null; totalTokens: number | null; estimatedCostUsd: number | null; costStatus: 'estimated' | 'partial' | 'unavailable' | 'not_applicable' }
export type AdminRun = { id: string; status: string; taskType: string; userId: number; userName: string; userEmail: string; projectId: number; projectName: string; scriptId: number; createdAt: string; startedAt: string | null; endedAt: string | null; errorCode: string | null; resultType: string | null; resultId: string | null; resultStoryboardId: string | null; usage: Usage }
export type UsageSummary = Omit<Usage, 'mode'> & { totalRuns: number; liveRuns: number; mockRuns: number; unknownRuns: number }

export function useAdminApi() {
  const { request } = useAuth()
  return useMemo(() => ({
    users: (page: number, query: string) => request<PageResult<AdminUser>>(`/admin/users?${new URLSearchParams({ page: String(page), size: '20', q: query })}`),
    setUserStatus: (id: number, enabled: boolean) => request<AdminUser>(`/admin/users/${id}/status`, { method: 'PATCH', body: { enabled } }),
    setUserRoles: (id: number, roleIds: number[]) => request<AdminUser>(`/admin/users/${id}/roles`, { method: 'PUT', body: { roleIds } }),
    roles: () => request<AdminRole[]>('/admin/roles'),
    permissions: () => request<Permission[]>('/admin/permissions'),
    saveRole: (id: number | null, input: { name: string; permissions: string[] }) => request<AdminRole>(id === null ? '/admin/roles' : `/admin/roles/${id}`, { method: id === null ? 'POST' : 'PUT', body: input }),
    runs: (page: number, status: string, taskType: string) => request<PageResult<AdminRun>>(`/admin/runs?${new URLSearchParams({ page: String(page), size: '20', ...(status ? { status } : {}), ...(taskType ? { taskType } : {}) })}`),
    run: (id: string) => request<AdminRun>(`/admin/runs/${encodeURIComponent(id)}`),
    usage: () => request<UsageSummary>('/admin/usage')
  }), [request])
}
