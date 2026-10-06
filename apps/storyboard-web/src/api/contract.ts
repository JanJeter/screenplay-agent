import type { AgentRun, GenerationInput, Project, RunAccepted, RunProgress, Scene, Script, ShotProposal, ShotRegenerationInput, Storyboard, StoryboardSaveInput, StoryboardSummary } from '../types'

export interface ScreenplayApi {
  listProjects(): Promise<Project[]>
  createProject(input: Pick<Project, 'name' | 'description' | 'genre'>): Promise<Project>
  listScripts(projectId: string): Promise<Script[]>
  createScript(projectId: string, input: Pick<Script, 'versionName' | 'originalFilename' | 'rawText'>): Promise<Script>
  analyzeScript(scriptId: string): Promise<void>
  listScenes(scriptId: string): Promise<Scene[]>
  listStoryboards(projectId: string, scriptId: string): Promise<StoryboardSummary[]>
  getStoryboard(storyboardId: string): Promise<Storyboard>
  generateStoryboard(projectId: string, input: GenerationInput): Promise<RunAccepted>
  regenerateShot(storyboardId: string, shotId: string, input: ShotRegenerationInput): Promise<RunAccepted>
  saveStoryboard(storyboardId: string, input: StoryboardSaveInput): Promise<Storyboard>
  getShotProposal(storyboardId: string, proposalId: string): Promise<ShotProposal>
  acceptShotProposal(storyboardId: string, proposalId: string, expectedRevision: number): Promise<Storyboard>
  rejectShotProposal(storyboardId: string, proposalId: string): Promise<ShotProposal>
  exportStoryboardMarkdown(storyboardId: string): Promise<string>
  getRun(runId: string): Promise<AgentRun>
  cancelRun(runId: string): Promise<AgentRun>
  streamRun(runId: string, onProgress: (progress: RunProgress) => void, signal: AbortSignal): Promise<void>
}

export class ApiError extends Error {
  constructor(public readonly status: number, message: string, public readonly code?: string) { super(message) }
}
