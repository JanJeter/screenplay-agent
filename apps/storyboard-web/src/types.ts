export type Project = { id: string; name: string; description: string; genre: string; status: string; updatedAt?: string }
export type Script = { id: string; projectId: string; versionName: string; originalFilename: string; status: string; createdAt?: string; rawText?: string }
export type Scene = { id: string; scriptVersionId: string; sceneNo: string; heading: string; rawText: string; summary?: string; sortOrder: number }
export type ShotSize = 'ESTABLISHING' | 'WIDE' | 'MEDIUM' | 'CLOSE_UP' | 'EXTREME_CLOSE_UP'
export type CameraMovement = 'STATIC' | 'PAN' | 'TILT' | 'DOLLY_IN' | 'DOLLY_OUT' | 'TRACK' | 'HANDHELD'
export type Shot = { id: string; orderIndex: number; shotSize: ShotSize; cameraMovement: CameraMovement; visualDescription: string; dialogue: string; sound: string; durationSeconds: number; imagePrompt: string; videoPrompt: string; sourceQuote: string }
export type EditableShot = Omit<Shot, 'id' | 'orderIndex'>
export type ProposalStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED'
export type ShotProposalSummary = { id: string; targetShotId: string; baseStoryboardRevision: number; status: ProposalStatus; createdAt: string }
export type ShotProposal = { id: string; storyboardId: string; targetShotId: string; sourceRunId: string; baseStoryboardRevision: number; status: ProposalStatus; candidateShot: EditableShot; createdAt: string; resolvedAt: string | null }
export type Storyboard = { id: string; projectId: string; createdBy: string; sourceRunId: string; sourceSnapshot: { scriptId: string; scriptRevision: number; sourceSceneId: string; sceneNo: number; heading: string; sceneText: string; sceneHash: string }; revision: number; createdAt: string; updatedAt: string; shots: Shot[]; proposalSummaries: ShotProposalSummary[] }
export type StoryboardSummary = { id: string; projectId: string; scriptId: string; scriptRevision: number; sceneNo: number; heading: string; revision: number; shotCount: number; createdAt: string; updatedAt: string }
// Keep this in lockstep with Java AgentRunStatus. FINALIZING still accepts the
// gateway's durable result; INTERRUPTED is a terminal recovery outcome.
export type RunStatus = 'QUEUED' | 'RUNNING' | 'FINALIZING' | 'CANCELLING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
export type AgentRun = { id: string; status: RunStatus; errorCode: string | null; resultRef: { type: 'storyboard' | 'shot_proposal'; id: string; storyboardId: string } | null }
export type Session = { accessToken: string; refreshToken: string }
export type UserProfile = { id: number; email: string; fullName: string; roles: string[]; permissions: string[]; organizationId: number; organizationName: string; emailVerified: boolean; enabled: boolean }
export type GenerationInput = { scriptId: string; sceneId: string; targetShotCount: number; instructions: string; clientRequestId: string }
export type ShotRegenerationInput = { instruction: string; expectedRevision: number; clientRequestId: string }
export type RunAccepted = { runId: string; status: RunStatus }
export type StoryboardSaveInput = { expectedRevision: number; shots: Array<Omit<Shot, 'orderIndex'>> }
export type RunProgress = { event: string; detail: string }
