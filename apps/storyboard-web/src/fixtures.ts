import detailFixture from '../../../docs/storyboard-mvp/fixtures/storyboard-detail-6-shots.json'
import listFixture from '../../../docs/storyboard-mvp/fixtures/storyboard-list-response.json'
import queuedFixture from '../../../docs/storyboard-mvp/fixtures/run-queued.json'
import failedFixture from '../../../docs/storyboard-mvp/fixtures/run-failed.json'
import completedFixture from '../../../docs/storyboard-mvp/fixtures/run-completed-storyboard.json'
import type { AgentRun, Project, Scene, Script, Storyboard, StoryboardSummary } from './types'

export const fixtureProject: Project = { id: 'project_10', name: '雨夜试镜', description: '一场围绕未拆信件的悬疑对话。', genre: '悬疑短片', status: 'ACTIVE', updatedAt: '2026-10-06T08:00:00Z' }
export const fixtureScripts: Script[] = [{ id: 'script_101', projectId: 'project_10', versionName: '第一稿', originalFilename: '雨夜试镜.md', status: 'ANALYZED', createdAt: '2026-10-06T07:48:00Z' }]
export const fixtureScenes: Scene[] = [{ id: 'scene_12', scriptVersionId: 'script_101', sceneNo: '1', heading: 'INT. 旧公寓客厅 - 夜', rawText: detailFixture.sourceSnapshot.sceneText, summary: '许晴取走林舟放下的一封未拆信。', sortOrder: 0 }]
export const fixtureStoryboard = detailFixture as Storyboard
export const fixtureSummaries = listFixture.items as StoryboardSummary[]
export const fixtureRuns: Record<string, AgentRun> = {
  queued: queuedFixture as AgentRun,
  failed: failedFixture as AgentRun,
  completed: completedFixture as AgentRun
}
