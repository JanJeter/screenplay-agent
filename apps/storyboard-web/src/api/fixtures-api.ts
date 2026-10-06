import { fixtureProject, fixtureRuns, fixtureScenes, fixtureScripts, fixtureStoryboard, fixtureSummaries } from '../fixtures'
import type { AgentRun, GenerationInput, Project, RunAccepted, RunProgress, Scene, Script, ShotProposal, ShotRegenerationInput, Storyboard, StoryboardSaveInput, StoryboardSummary } from '../types'
import type { ScreenplayApi } from './contract'

const pause = () => new Promise((resolve) => window.setTimeout(resolve, 180))
const clone = <T>(value: T) => structuredClone(value)
let projects = [clone(fixtureProject)]
let scripts = clone(fixtureScripts)
let scenes = clone(fixtureScenes)
let storyboards = [clone(fixtureStoryboard)]
let summaries = clone(fixtureSummaries)
const runs = clone(fixtureRuns)
const proposals: Record<string, ShotProposal> = {}

function nextId(prefix: string) { return `${prefix}_${Date.now()}` }
function markdownCell(value: string | number) { return String(value).replace(/\r\n?/g, '\n').replace(/\\/g, '\\\\').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\|/g, '\\|').replace(/\*/g, '\\*').replace(/_/g, '\\_').replace(/`/g, '\\`').replace(/\[/g, '\\[').replace(/\]/g, '\\]').replace(/#/g, '\\#').replace(/\n/g, '<br>') }
function markdown(storyboard: Storyboard) { const source = storyboard.sourceSnapshot; return `# 分镜表\n\n- 项目：${markdownCell(storyboard.projectId)}\n- 来源剧本版本：${markdownCell(source.scriptId)}（修订 ${source.scriptRevision}）\n- 来源场景：场 ${markdownCell(source.sceneNo)} · ${markdownCell(source.heading)}\n- 分镜修订：r${storyboard.revision}\n\n| 镜号 | 镜头 ID | 景别 | 运镜 | 画面描述 | 对白 | 声音 | 预计时长（秒） | 图像 Prompt | 视频 Prompt | 来源引文 |\n| --- | --- | --- | --- | --- | --- | --- | ---: | --- | --- | --- |\n${storyboard.shots.map((shot) => `| ${shot.orderIndex + 1} | ${markdownCell(shot.id)} | ${shot.shotSize} | ${shot.cameraMovement} | ${markdownCell(shot.visualDescription)} | ${markdownCell(shot.dialogue)} | ${markdownCell(shot.sound)} | ${shot.durationSeconds} | ${markdownCell(shot.imagePrompt)} | ${markdownCell(shot.videoPrompt)} | ${markdownCell(shot.sourceQuote)} |`).join('\n')}\n` }

export const fixtureApi: ScreenplayApi = {
  async listProjects() { await pause(); return clone(projects) },
  async createProject(input) { await pause(); const project: Project = { id: nextId('project'), ...input, status: 'ACTIVE', updatedAt: new Date().toISOString() }; projects = [project, ...projects]; return clone(project) },
  async listScripts(projectId) { await pause(); return clone(scripts.filter((script) => script.projectId === projectId)) },
  async createScript(projectId, input) { await pause(); const script: Script = { id: nextId('script'), projectId, ...input, status: 'DRAFT', createdAt: new Date().toISOString() }; scripts = [script, ...scripts]; return clone(script) },
  async analyzeScript(scriptId) { await pause(); scripts = scripts.map((script) => script.id === scriptId ? { ...script, status: 'ANALYZED' } : script); const script = scripts.find((item) => item.id === scriptId); if (script && !scenes.some((scene) => scene.scriptVersionId === scriptId)) scenes = [...scenes, { ...clone(fixtureScenes[0]), id: nextId('scene'), scriptVersionId: scriptId }] },
  async listScenes(scriptId) { await pause(); return clone(scenes.filter((scene) => scene.scriptVersionId === scriptId)) },
  async listStoryboards(projectId, scriptId) { await pause(); return clone(summaries.filter((summary) => summary.projectId === projectId && summary.scriptId === scriptId)) },
  async getStoryboard(id) { await pause(); const storyboard = storyboards.find((item) => item.id === id); return clone(storyboard ?? fixtureStoryboard) },
  async generateStoryboard(projectId, input: GenerationInput) {
    await pause(); const runId = nextId('run_generate'); runs[runId] = { id: runId, status: 'QUEUED', errorCode: null, resultRef: null }
    window.setTimeout(() => {
      const scene = scenes.find((item) => item.id === input.sceneId)
      const storyboardId = nextId('sb')
      const next = clone(fixtureStoryboard); next.id = storyboardId; next.projectId = projectId; next.sourceRunId = runId; next.sourceSnapshot = { ...next.sourceSnapshot, scriptId: input.scriptId, sourceSceneId: input.sceneId, heading: scene?.heading ?? next.sourceSnapshot.heading, sceneText: scene?.rawText ?? next.sourceSnapshot.sceneText }; next.shots = next.shots.slice(0, input.targetShotCount).map((shot, index) => ({ ...shot, id: `shot_${storyboardId}_${index + 1}`, orderIndex: index }))
      storyboards = [next, ...storyboards]; summaries = [{ id: storyboardId, projectId, scriptId: input.scriptId, scriptRevision: 1, sceneNo: Number(scene?.sceneNo ?? 1), heading: scene?.heading ?? next.sourceSnapshot.heading, revision: 1, shotCount: next.shots.length, createdAt: next.createdAt, updatedAt: next.updatedAt }, ...summaries]
      runs[runId] = { id: runId, status: 'COMPLETED', errorCode: null, resultRef: { type: 'storyboard', id: storyboardId, storyboardId } }
    }, 700)
    return { runId, status: 'QUEUED' } satisfies RunAccepted
  },
  async regenerateShot(storyboardId, shotId, input: ShotRegenerationInput) {
    await pause(); const storyboard = storyboards.find((item) => item.id === storyboardId)
    if (!storyboard || storyboard.revision !== input.expectedRevision) throw new Error('分镜已被更新，请重新加载后比较。')
    if (!storyboard.shots.some((shot) => shot.id === shotId)) throw new Error('目标镜头不存在。')
    const runId = nextId('run_rewrite'); runs[runId] = { id: runId, status: 'QUEUED', errorCode: null, resultRef: null }
    window.setTimeout(() => {
      const current = storyboards.find((item) => item.id === storyboardId)
      const target = current?.shots.find((shot) => shot.id === shotId)
      if (!current || !target) { runs[runId] = { id: runId, status: 'FAILED', errorCode: 'RESOURCE_NOT_FOUND', resultRef: null }; return }
      const proposalId = nextId('proposal'); const { id: _id, orderIndex: _orderIndex, ...candidateShot } = target
      const proposal: ShotProposal = { id: proposalId, storyboardId, targetShotId: shotId, sourceRunId: runId, baseStoryboardRevision: current.revision, status: 'PENDING', createdAt: new Date().toISOString(), resolvedAt: null, candidateShot: { ...candidateShot, visualDescription: `${candidateShot.visualDescription}\n修改方向：${input.instruction}` } }
      proposals[proposalId] = proposal
      storyboards = storyboards.map((item) => item.id === storyboardId ? { ...item, proposalSummaries: [{ id: proposalId, targetShotId: shotId, baseStoryboardRevision: current.revision, status: 'PENDING', createdAt: proposal.createdAt }, ...item.proposalSummaries] } : item)
      runs[runId] = { id: runId, status: 'COMPLETED', errorCode: null, resultRef: { type: 'shot_proposal', id: proposalId, storyboardId } }
    }, 700)
    return { runId, status: 'QUEUED' } satisfies RunAccepted
  },
  async saveStoryboard(id, input: StoryboardSaveInput) {
    await pause(); const current = storyboards.find((item) => item.id === id); if (!current) return clone(fixtureStoryboard)
    if (current.revision !== input.expectedRevision) throw new Error('分镜已被更新，请重新加载后比较。')
    const updated: Storyboard = { ...current, revision: current.revision + 1, updatedAt: new Date().toISOString(), shots: input.shots.map((shot, orderIndex) => ({ ...shot, orderIndex })) }
    storyboards = storyboards.map((item) => item.id === id ? updated : item); summaries = summaries.map((item) => item.id === id ? { ...item, revision: updated.revision, updatedAt: updated.updatedAt } : item); return clone(updated)
  },
  async getShotProposal(storyboardId, proposalId) { await pause(); const proposal = proposals[proposalId]; if (!proposal || proposal.storyboardId !== storyboardId) throw new Error('候选镜头不存在。'); return clone(proposal) },
  async acceptShotProposal(storyboardId, proposalId, expectedRevision) {
    await pause(); const proposal = proposals[proposalId]; const current = storyboards.find((item) => item.id === storyboardId)
    if (!proposal || !current) throw new Error('候选镜头不存在。')
    if (proposal.status !== 'PENDING') throw new Error('该候选已处理，不能再次采纳。')
    if (current.revision !== expectedRevision || proposal.baseStoryboardRevision !== current.revision) throw new Error('分镜已更新，请重新加载后比较。')
    const target = current.shots.find((shot) => shot.id === proposal.targetShotId); if (!target) throw new Error('目标镜头不存在。')
    const updated: Storyboard = { ...current, revision: current.revision + 1, updatedAt: new Date().toISOString(), shots: current.shots.map((shot) => shot.id === target.id ? { ...proposal.candidateShot, id: shot.id, orderIndex: shot.orderIndex } : shot), proposalSummaries: current.proposalSummaries.map((summary) => summary.id === proposalId ? { ...summary, status: 'ACCEPTED' } : summary) }
    proposals[proposalId] = { ...proposal, status: 'ACCEPTED', resolvedAt: updated.updatedAt }; storyboards = storyboards.map((item) => item.id === storyboardId ? updated : item); summaries = summaries.map((item) => item.id === storyboardId ? { ...item, revision: updated.revision, updatedAt: updated.updatedAt } : item); return clone(updated)
  },
  async rejectShotProposal(storyboardId, proposalId) {
    await pause(); const proposal = proposals[proposalId]; if (!proposal || proposal.storyboardId !== storyboardId) throw new Error('候选镜头不存在。'); if (proposal.status === 'ACCEPTED') throw new Error('该候选已处理，不能放弃。')
    const rejected = proposal.status === 'REJECTED' ? proposal : { ...proposal, status: 'REJECTED' as const, resolvedAt: new Date().toISOString() }; proposals[proposalId] = rejected; storyboards = storyboards.map((item) => item.id === storyboardId ? { ...item, proposalSummaries: item.proposalSummaries.map((summary) => summary.id === proposalId ? { ...summary, status: 'REJECTED' as const } : summary) } : item); return clone(rejected)
  },
  async exportStoryboardMarkdown(storyboardId) { await pause(); return markdown(storyboards.find((item) => item.id === storyboardId) ?? fixtureStoryboard) },
  async getRun(runId) { await pause(); return clone(runs[runId] ?? runs.completed) },
  async cancelRun(runId) { await pause(); const current = runs[runId] ?? runs.queued; runs[runId] = { ...current, status: 'CANCELLED', resultRef: null }; return clone(runs[runId]) },
  async streamRun(runId, onProgress: (progress: RunProgress) => void, signal) {
    onProgress({ event: 'run.queued', detail: '排队中' }); await pause(); if (signal.aborted) return
    onProgress({ event: 'run.running', detail: '正在生成分镜' }); await pause(); if (!signal.aborted) onProgress({ event: 'run.completed', detail: '正在保存' })
  }
}
