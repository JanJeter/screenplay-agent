import type { AgentRun, GenerationInput, Project, RunAccepted, RunProgress, RunStatus, Scene, Script, ShotProposal, ShotRegenerationInput, Storyboard, StoryboardSaveInput, StoryboardSummary } from '../types'
import { ApiError, type ScreenplayApi } from './contract'
import { runErrorMessage } from './run-errors'
import { isLegacyWorkbench, javaApiBase as apiBase, legacyReadOnlyMessage } from '../environment'
import type { AuthorizedRequest } from './http'

type Tokens = { accessToken: string; refreshToken: string }
type JavaApiOptions = { getTokens: () => Tokens | null; updateTokens: (tokens: Tokens) => void; onExpired: () => void; request?: AuthorizedRequest }
type RequestOptions = { method?: string; body?: unknown; retry?: boolean; expectJson?: boolean; expectText?: boolean }

// Existing screenplay endpoints expose database Long values as JSON numbers. The
// workbench keeps IDs as strings, as required by the storyboard contract.
function normalizeProject(value: Omit<Project, 'id'> & { id: string | number }): Project { return { ...value, id: String(value.id) } }
function normalizeScript(value: Omit<Script, 'id' | 'projectId'> & { id: string | number; projectId: string | number }): Script { return { ...value, id: String(value.id), projectId: String(value.projectId) } }
function normalizeScene(value: Omit<Scene, 'id' | 'scriptVersionId' | 'sceneNo'> & { id: string | number; scriptVersionId: string | number; sceneNo: string | number }): Scene { return { ...value, id: String(value.id), scriptVersionId: String(value.scriptVersionId), sceneNo: String(value.sceneNo) } }
function normalizeRun<T extends { status: string }>(value: T): T & { status: RunStatus } { return { ...value, status: value.status.toUpperCase() as RunStatus } }

function errorFromResponse(response: Response): Promise<ApiError> {
  return response.json().catch(() => null).then((body: { code?: string; message?: string } | null) => new ApiError(response.status, runErrorMessage(body?.code) ?? body?.message ?? '请求未完成，请稍后重试。', body?.code))
}

export function createJavaApi(options: JavaApiOptions): ScreenplayApi {
  async function request<T>(path: string, requestOptions: RequestOptions = {}): Promise<T> {
    if (options.request) return options.request<T>(path, requestOptions)
    if (isLegacyWorkbench && ['POST', 'PUT', 'PATCH', 'DELETE'].includes((requestOptions.method ?? 'GET').toUpperCase())) {
      throw new ApiError(403, legacyReadOnlyMessage, 'LEGACY_WORKBENCH_READ_ONLY')
    }
    const tokens = options.getTokens()
    const response = await fetch(`${apiBase}/api/v1${path}`, {
      method: requestOptions.method ?? 'GET',
      headers: { ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}), ...(requestOptions.body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: requestOptions.body === undefined ? undefined : JSON.stringify(requestOptions.body)
    })
    if (response.status === 401 && requestOptions.retry !== false && tokens?.refreshToken) {
      const refreshed = await fetch(`${apiBase}/api/v1/auth/refresh`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: tokens.refreshToken }) })
      if (refreshed.ok) { options.updateTokens(await refreshed.json() as Tokens); return request(path, { ...requestOptions, retry: false }) }
      options.onExpired()
    }
    if (!response.ok) throw await errorFromResponse(response)
    return (requestOptions.expectJson === false ? undefined : requestOptions.expectText ? await response.text() : await response.json()) as T
  }

  async function streamRun(runId: string, onProgress: (progress: RunProgress) => void, signal: AbortSignal) {
    const tokens = options.getTokens()
    const response = await fetch(`${apiBase}/api/v1/screenplay/agent/runs/${encodeURIComponent(runId)}/events`, { headers: { Accept: 'text/event-stream', ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}) }, signal })
    if (response.status === 401) options.onExpired()
    if (!response.ok) throw await errorFromResponse(response)
    if (!response.body) return
    const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''; let event = 'message'; let data: string[] = []
    const dispatch = () => {
      if (data.length === 0) return
      const raw = data.join('\n'); let detail = ''
      try {
        const payload = JSON.parse(raw) as { data?: { delta?: string; message?: string }; message?: string; status?: string }
        detail = payload.data?.delta ?? payload.data?.message ?? payload.message ?? payload.status ?? event
      } catch { detail = raw || event }
      onProgress({ event, detail }); event = 'message'; data = []
    }
    try {
      while (true) {
        const result = await reader.read(); if (result.done) break
        buffer += decoder.decode(result.value, { stream: true })
        let lineEnd = buffer.indexOf('\n')
        while (lineEnd >= 0) {
          const line = buffer.slice(0, lineEnd).replace(/\r$/, ''); buffer = buffer.slice(lineEnd + 1)
          if (line === '') dispatch()
          else if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
          lineEnd = buffer.indexOf('\n')
        }
      }
      dispatch()
    } finally { reader.releaseLock() }
  }

  return {
    listProjects: async () => (await request<(Omit<Project, 'id'> & { id: string | number })[]>('/screenplay/projects')).map(normalizeProject),
    createProject: async (input) => normalizeProject(await request<Omit<Project, 'id'> & { id: string | number }>('/screenplay/projects', { method: 'POST', body: input })),
    listScripts: async (projectId) => (await request<(Omit<Script, 'id' | 'projectId'> & { id: string | number; projectId: string | number })[]>(`/screenplay/projects/${encodeURIComponent(projectId)}/scripts`)).map(normalizeScript),
    createScript: async (projectId, input) => normalizeScript(await request<Omit<Script, 'id' | 'projectId'> & { id: string | number; projectId: string | number }>(`/screenplay/projects/${encodeURIComponent(projectId)}/scripts`, { method: 'POST', body: input })),
    analyzeScript: (scriptId) => request<void>(`/screenplay/scripts/${encodeURIComponent(scriptId)}/analyze`, { method: 'POST', expectJson: false }),
    listScenes: async (scriptId) => (await request<(Omit<Scene, 'id' | 'scriptVersionId' | 'sceneNo'> & { id: string | number; scriptVersionId: string | number; sceneNo: string | number })[]>(`/screenplay/scripts/${encodeURIComponent(scriptId)}/scenes`)).map(normalizeScene),
    listStoryboards: async (projectId, scriptId) => (await request<{ items: StoryboardSummary[] }>(`/screenplay/projects/${encodeURIComponent(projectId)}/storyboards?scriptId=${encodeURIComponent(scriptId)}&page=0&size=20`)).items,
    getStoryboard: (id) => request<Storyboard>(`/screenplay/storyboards/${encodeURIComponent(id)}`),
    generateStoryboard: async (projectId, input) => normalizeRun(await request<RunAccepted>(`/screenplay/projects/${encodeURIComponent(projectId)}/storyboards/generations`, { method: 'POST', body: input })),
    regenerateShot: async (storyboardId, shotId, input) => normalizeRun(await request<RunAccepted>(`/screenplay/storyboards/${encodeURIComponent(storyboardId)}/shots/${encodeURIComponent(shotId)}/regenerations`, { method: 'POST', body: input })),
    saveStoryboard: (id, input) => request<Storyboard>(`/screenplay/storyboards/${encodeURIComponent(id)}`, { method: 'PUT', body: input }),
    getShotProposal: (storyboardId, proposalId) => request<ShotProposal>(`/screenplay/storyboards/${encodeURIComponent(storyboardId)}/shot-proposals/${encodeURIComponent(proposalId)}`),
    acceptShotProposal: (storyboardId, proposalId, expectedRevision) => request<Storyboard>(`/screenplay/storyboards/${encodeURIComponent(storyboardId)}/shot-proposals/${encodeURIComponent(proposalId)}/accept`, { method: 'POST', body: { expectedRevision } }),
    rejectShotProposal: (storyboardId, proposalId) => request<ShotProposal>(`/screenplay/storyboards/${encodeURIComponent(storyboardId)}/shot-proposals/${encodeURIComponent(proposalId)}/reject`, { method: 'POST' }),
    exportStoryboardMarkdown: (id) => request<string>(`/screenplay/storyboards/${encodeURIComponent(id)}/export?format=markdown`, { expectText: true }),
    getRun: async (id) => normalizeRun(await request<AgentRun>(`/screenplay/agent/runs/${encodeURIComponent(id)}`)),
    cancelRun: async (id) => normalizeRun(await request<AgentRun>(`/screenplay/agent/runs/${encodeURIComponent(id)}/cancel`, { method: 'POST' })),
    streamRun
  }
}
