import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, Navigate, useBlocker, useParams, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/contract'
import { useApi } from '../api/api-context'
import { useAuth } from '../auth'
import { AppHeader, Button, EmptyState, ErrorState, LoadingBlock, ShotCard, StatusBadge, labelMovement, labelShotSize } from '../components'
import type { AgentRun, CameraMovement, EditableShot, RunStatus, Scene, Shot, ShotProposal, ShotSize, Storyboard, StoryboardSummary } from '../types'

type LoadState = { scenes: Scene[]; storyboards: StoryboardSummary[]; detail: Storyboard | null }
const terminal = (status: RunStatus) => status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED' || status === 'INTERRUPTED'
const newRequestId = () => crypto.randomUUID?.() ?? `storyboard-${Date.now()}-${Math.random().toString(16).slice(2)}`

export function StoryboardWorkspacePage() {
  const { projectId = '', scriptId = '' } = useParams(); const api = useApi(); const auth = useAuth(); const [params, setParams] = useSearchParams()
  const fixtureView = params.get('fixture'); const selectedStoryboardId = params.get('storyboard'); const activeRunId = params.get('run')
  const [state, setState] = useState<LoadState | null>(null); const [working, setWorking] = useState<Storyboard | null>(null); const [error, setError] = useState(''); const [run, setRun] = useState<AgentRun | null>(null); const [runMessage, setRunMessage] = useState(''); const [selectedSceneId, setSelectedSceneId] = useState(''); const [selectedShotId, setSelectedShotId] = useState(''); const [sourceOpen, setSourceOpen] = useState(false); const [dirty, setDirty] = useState(false); const [saving, setSaving] = useState(false); const [saveNotice, setSaveNotice] = useState(''); const [conflict, setConflict] = useState<Storyboard | null>(null); const [generating, setGenerating] = useState(false); const [proposal, setProposal] = useState<ShotProposal | null>(null); const [proposalBusy, setProposalBusy] = useState(false); const [proposalNotice, setProposalNotice] = useState(''); const [rewriteOpen, setRewriteOpen] = useState(false); const [exporting, setExporting] = useState(false); const [exportNotice, setExportNotice] = useState('')
  const streamAbort = useRef<AbortController | null>(null); const observerVersionRef = useRef(0); const loadVersionRef = useRef(0); const pageVersionRef = useRef(0); const workingRef = useRef<Storyboard | null>(null); const allowedStoryboardNavigationRef = useRef<string | null>(null); const dirtyRef = useRef(false); const editVersionRef = useRef(0); const saveInFlightRef = useRef(false); dirtyRef.current = dirty; workingRef.current = working
  const stopObserving = useCallback(() => { observerVersionRef.current += 1; streamAbort.current?.abort(); streamAbort.current = null }, [])
  const load = useCallback(async (preserveWorking = false) => {
    const loadVersion = ++loadVersionRef.current
    setError(''); if (!preserveWorking) setState(null)
    if (auth.isFixture && fixtureView === 'loading') return
    if (auth.isFixture && fixtureView === 'failed') { setError('分镜任务未能保存有效结果。请检查场景内容后重新发起生成。'); return }
    try {
      const [scenes, storyboards] = await Promise.all([api.listScenes(scriptId), api.listStoryboards(projectId, scriptId)])
      const selected = selectedStoryboardId ?? storyboards[0]?.id
      const detail = selected && !(auth.isFixture && fixtureView === 'empty') ? await api.getStoryboard(selected) : null
      if (loadVersion !== loadVersionRef.current) return
      setState({ scenes, storyboards, detail }); if (detail && !preserveWorking) { pageVersionRef.current += 1; workingRef.current = detail; setWorking(detail); setSelectedShotId(detail.shots[0]?.id ?? ''); setDirty(false); setSaveNotice(''); setProposal(null); setProposalNotice('') }
      setSelectedSceneId((current) => current || detail?.sourceSnapshot.sourceSceneId || scenes[0]?.id || '')
    } catch (reason) { if (loadVersion === loadVersionRef.current) setError(reason instanceof Error ? reason.message : '工作台暂时不可用。') }
  }, [api, auth.isFixture, fixtureView, projectId, scriptId, selectedStoryboardId])
  useEffect(() => { void load() }, [load])
  useEffect(() => () => stopObserving(), [stopObserving])
  useEffect(() => {
    if (!dirty) return
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [dirty])
  const shouldBlockNavigation = useCallback(({ currentLocation, nextLocation }: { currentLocation: { pathname: string; search: string }; nextLocation: { pathname: string; search: string } }) => {
    if (!dirty) return false
    if (currentLocation.pathname !== nextLocation.pathname) return true
    const nextStoryboardId = new URLSearchParams(nextLocation.search).get('storyboard')
    const allowedStoryboardId = allowedStoryboardNavigationRef.current
    // A missing `storyboard` selects the default resource. It must never be
    // confused with the absence of an explicit, already-confirmed navigation.
    if (allowedStoryboardId !== null && allowedStoryboardId === nextStoryboardId) { allowedStoryboardNavigationRef.current = null; return false }
    // `run` is progress-only state. A storyboard query change replaces the
    // editing resource and must receive the same protection as a path change.
    return new URLSearchParams(currentLocation.search).get('storyboard') !== nextStoryboardId
  }, [dirty])
  const navigationBlocker = useBlocker(shouldBlockNavigation)
  useEffect(() => {
    if (navigationBlocker.state !== 'blocked') return
    if (window.confirm('当前分镜有未保存修改，确定离开吗？')) navigationBlocker.proceed()
    else navigationBlocker.reset()
  }, [navigationBlocker])
  const openSavedStoryboard = useCallback(async (storyboardId: string) => {
    if (dirty && !window.confirm('当前分镜有未保存修改，确定切换吗？')) return
    const pageVersion = ++pageVersionRef.current; stopObserving(); setRun(null)
    const detail = await api.getStoryboard(storyboardId); if (pageVersion !== pageVersionRef.current) return
    workingRef.current = detail; setWorking(detail); setSelectedShotId(detail.shots[0]?.id ?? ''); setDirty(false); setSaveNotice(''); setConflict(null); setProposal(null); setProposalNotice(''); setState((current) => current ? { ...current, detail } : current); allowedStoryboardNavigationRef.current = storyboardId; setParams((current) => { current.set('storyboard', storyboardId); current.delete('fixture'); current.delete('run'); return current }); queueMicrotask(() => { if (allowedStoryboardNavigationRef.current === storyboardId) allowedStoryboardNavigationRef.current = null })
  }, [api, dirty, setParams, stopObserving])
  const applyCompletedRun = useCallback(async (currentRun: AgentRun, isCurrent: () => boolean) => {
    if (!currentRun.resultRef) { setRunMessage('任务完成，但未返回可读取的分镜结果。'); return }
    const pageVersion = pageVersionRef.current; const workingId = workingRef.current?.id; const editVersion = editVersionRef.current
    if (currentRun.resultRef.type === 'shot_proposal') { const next = await api.getShotProposal(currentRun.resultRef.storyboardId, currentRun.resultRef.id); if (!isCurrent() || pageVersion !== pageVersionRef.current || workingId !== workingRef.current?.id || editVersion !== editVersionRef.current || dirtyRef.current) return; setProposal(next); setSelectedShotId(next.targetShotId); setRewriteOpen(false); setProposalNotice('候选镜头已保存；原镜头尚未修改。请比较后采纳或放弃。'); setRunMessage('单镜候选已生成。'); return }
    const detail = await api.getStoryboard(currentRun.resultRef.storyboardId); if (!isCurrent()) return
    setState((current) => current ? { ...current, storyboards: [toSummary(detail), ...current.storyboards.filter((item) => item.id !== detail.id)] } : current)
    if (pageVersion !== pageVersionRef.current || workingId !== workingRef.current?.id || editVersion !== editVersionRef.current || dirtyRef.current) { setGenerating(false); setRunMessage('新分镜已保存。当前编辑在等待期间发生变化，未自动切换。'); return }
    pageVersionRef.current += 1; workingRef.current = detail; setWorking(detail); setSelectedShotId(detail.shots[0]?.id ?? ''); setGenerating(false); setState((current) => current ? { ...current, detail } : current); setParams((current) => { current.set('storyboard', detail.id); current.delete('run'); return current }); setRunMessage('分镜已保存，可继续编辑。')
  }, [api, setParams])
  const observeRun = useCallback(async (runId: string) => {
    stopObserving(); const observerVersion = observerVersionRef.current; const controller = new AbortController(); streamAbort.current = controller
    const isCurrent = () => observerVersion === observerVersionRef.current
    const refresh = async () => {
      const current = await api.getRun(runId); if (!isCurrent()) return true; setRun(current)
      if (terminal(current.status)) { controller.abort(); if (current.status === 'COMPLETED') await applyCompletedRun(current, isCurrent); else if (isCurrent()) { setGenerating(false); setRunMessage(current.status === 'CANCELLED' ? '任务已停止；已有分镜未受影响。' : current.status === 'INTERRUPTED' ? `任务已中断：${current.errorCode ?? '服务恢复前未完成，请重新发起任务。'}` : `任务失败：${current.errorCode ?? '请检查场景和要求后重新生成。'}`) }; return true }
      return false
    }
    try { if (await refresh()) return } catch (reason) { if (isCurrent()) setRunMessage(reason instanceof Error ? reason.message : '无法恢复任务状态。') }
    if (!isCurrent()) return
    void api.streamRun(runId, (progress) => { if (isCurrent()) setRunMessage(progress.detail || progress.event) }, controller.signal).catch(() => undefined)
    const timer = window.setInterval(() => { void refresh().then((finished) => { if (finished) window.clearInterval(timer) }).catch(() => { if (isCurrent()) setRunMessage('连接已断开，正在轮询恢复任务状态。') }) }, 2500)
    controller.signal.addEventListener('abort', () => window.clearInterval(timer), { once: true })
  }, [api, applyCompletedRun, stopObserving])
  useEffect(() => { if (activeRunId) void observeRun(activeRunId); else stopObserving(); return () => stopObserving() }, [activeRunId, observeRun, stopObserving])
  const selectedShot = useMemo(() => working?.shots.find((shot) => shot.id === selectedShotId) ?? working?.shots[0] ?? null, [selectedShotId, working])
  const selectedScene = state?.scenes.find((scene) => scene.id === selectedSceneId) ?? null
  useEffect(() => {
    const pending = working?.proposalSummaries.find((item) => item.targetShotId === selectedShotId && item.status === 'PENDING')
    if (!working || !pending || proposal?.id === pending.id) return
    let cancelled = false
    void api.getShotProposal(working.id, pending.id).then((next) => { if (!cancelled) setProposal(next) }).catch(() => undefined)
    return () => { cancelled = true }
  }, [api, proposal?.id, selectedShotId, working])
  if (!auth.session) return <Navigate to="/login" replace state={{ from: `/projects/${projectId}/scripts/${scriptId}/storyboard` }} />
  const updateWorking = (updater: (current: Storyboard) => Storyboard) => { editVersionRef.current += 1; setWorking((current) => { const next=current ? updater(current) : current; workingRef.current=next; return next }); setDirty(true); setSaveNotice(''); setConflict(null) }
  const cancelRun = async () => { if (!run || terminal(run.status)) return; try { setRun(await api.cancelRun(run.id)); setRunMessage('正在停止任务…') } catch (reason) { setRunMessage(reason instanceof Error ? reason.message : '停止任务未完成。') } }
  const save = async () => {
    if (!working || !dirty || saveInFlightRef.current) return !dirty
    // Keep the exact request snapshot and edit generation. A response may only
    // clear dirty when no newer local edit has happened while it was in flight.
    const submitting = working; const submittedEditVersion = editVersionRef.current; const submittedPageVersion = pageVersionRef.current
    saveInFlightRef.current = true; setSaving(true); setSaveNotice('')
    try {
      const saved = await api.saveStoryboard(submitting.id, { expectedRevision: submitting.revision, shots: submitting.shots.map(({ orderIndex: _orderIndex, ...shot }) => shot) })
      const stillCurrent = submittedPageVersion === pageVersionRef.current && workingRef.current?.id === submitting.id
      setState((current) => current ? { ...current, detail: stillCurrent && current.detail?.id === saved.id ? saved : current.detail, storyboards: current.storyboards.map((item) => item.id === saved.id ? toSummary(saved) : item) } : current)
      if (!stillCurrent) return true
      if (editVersionRef.current === submittedEditVersion) {
        workingRef.current=saved; setWorking(saved); setDirty(false); setSaveNotice(`已保存为 r${saved.revision}。`)
      } else {
        // B was typed after A was sent. Preserve B, but rebase its next save on
        // the revision returned for A instead of bypassing Java's conflict rule.
        setWorking((current) => { const next=current?.id === saved.id ? { ...current, revision: saved.revision, updatedAt: saved.updatedAt, proposalSummaries: saved.proposalSummaries } : current; workingRef.current=next; return next })
        setDirty(true); setSaveNotice(`已保存先前修改为 r${saved.revision}；后续输入仍未保存。`)
      }
      return true
    }
    catch (reason) {
      if (submittedPageVersion !== pageVersionRef.current || workingRef.current?.id !== submitting.id) return false
      if (reason instanceof ApiError && reason.code === 'STORYBOARD_REVISION_CONFLICT') { try { const server=await api.getStoryboard(submitting.id); if (submittedPageVersion === pageVersionRef.current && workingRef.current?.id === submitting.id) { setConflict(server); setSaveNotice('服务器版本已更新。本地修改仍保留，请比较后决定。') } } catch { if (submittedPageVersion === pageVersionRef.current && workingRef.current?.id === submitting.id) setSaveNotice('检测到版本冲突；本地修改仍保留。请重新加载服务器版本后比较。') } }
      else setSaveNotice(reason instanceof Error ? reason.message : '保存未完成。'); return false
    } finally { saveInFlightRef.current = false; setSaving(false) }
  }
  const submitGeneration = async (count: number, instructions: string) => { if (!selectedScene) return; stopObserving(); const accepted = await api.generateStoryboard(projectId, { scriptId, sceneId: selectedScene.id, targetShotCount: count, instructions, clientRequestId: newRequestId() }); setRun({ id: accepted.runId, status: accepted.status, errorCode: null, resultRef: null }); setRunMessage('任务已提交，正在恢复进度。'); setParams((current) => { current.set('run', accepted.runId); return current }) }
  const beginRewrite = () => {
    if (!working || !selectedShot) return
    if (dirty) { if (!window.confirm('当前分镜有未保存修改。确定放弃这些本地修改后再重做当前镜头吗？')) return; setWorking(state?.detail ?? working); setDirty(false); setSaveNotice('已放弃本地修改。') }
    setProposal(null); setProposalNotice(''); setRewriteOpen(true)
  }
  const submitRewrite = async (instruction: string) => { if (!working || !selectedShot) return; stopObserving(); const accepted = await api.regenerateShot(working.id, selectedShot.id, { instruction, expectedRevision: working.revision, clientRequestId: newRequestId() }); setRun({ id: accepted.runId, status: accepted.status, errorCode: null, resultRef: null }); setRunMessage('单镜重做已提交，原镜头保持不变。'); setParams((current) => { current.set('run', accepted.runId); return current }) }
  const acceptProposal = async () => {
    if (!working || !proposal || proposalBusy) return
    if (dirty) { setProposalNotice('当前分镜有未保存修改。请先保存或加载服务器版本后，再采纳候选；本地编辑未被放弃。'); return }
    const storyboardId=working.id, pageVersion=pageVersionRef.current, editVersion=editVersionRef.current; setProposalBusy(true)
    try {
      const saved = await api.acceptShotProposal(working.id, proposal.id, working.revision)
      const stillCurrent=pageVersion===pageVersionRef.current&&workingRef.current?.id===storyboardId&&editVersion===editVersionRef.current&&!dirtyRef.current
      setState((current) => current ? { ...current, detail: stillCurrent && current.detail?.id===saved.id ? saved : current.detail, storyboards: current.storyboards.map((item) => item.id === saved.id ? toSummary(saved) : item) } : current)
      if (!stillCurrent) return
      workingRef.current=saved; setWorking(saved); setProposal(null); setProposalNotice('候选已采纳，只更新了当前镜头。'); setDirty(false)
    } catch (reason) {
      if (pageVersion!==pageVersionRef.current||workingRef.current?.id!==storyboardId) return
      if (reason instanceof ApiError && reason.code === 'STORYBOARD_REVISION_CONFLICT') setProposalNotice('候选基于旧版本，无法采纳。请重新生成候选或加载服务器版本后比较。')
      else setProposalNotice(reason instanceof Error ? reason.message : '采纳未完成。请重新加载后比较。')
    } finally { setProposalBusy(false) }
  }
  const rejectProposal = async () => {
    if (!working || !proposal || proposalBusy) return
    const storyboardId = working.id; const proposalId = proposal.id; const pageVersion = pageVersionRef.current; const editVersion = editVersionRef.current
    setProposalBusy(true)
    try {
      await api.rejectShotProposal(storyboardId, proposalId)
      const stillCurrent = pageVersion === pageVersionRef.current && workingRef.current?.id === storyboardId && editVersion === editVersionRef.current
      if (!stillCurrent) return
      setWorking((current) => {
        const next = current ? { ...current, proposalSummaries: current.proposalSummaries.map((item) => item.id === proposalId ? { ...item, status: 'REJECTED' as const } : item) } : current
        workingRef.current = next
        return next
      })
      setProposal(null); setProposalNotice('已放弃候选；原镜头未修改。')
    } catch (reason) {
      if (pageVersion === pageVersionRef.current && workingRef.current?.id === storyboardId) setProposalNotice(reason instanceof Error ? reason.message : '放弃候选未完成。')
    } finally { setProposalBusy(false) }
  }
  const exportMarkdown = async () => { if (!working || exporting) return; setExporting(true); setExportNotice(''); try { let savedFirst = false; if (dirty) { if (window.confirm('有未保存修改。确定后会先保存，再导出；取消则导出当前已保存版本。')) { if (!await save()) return; savedFirst = true } else setExportNotice('已导出当前 Java 已保存版本；本地未保存修改未包含。') } const markdown = await api.exportStoryboardMarkdown(working.id); const file = new Blob([markdown], { type: 'text/markdown;charset=utf-8' }); const url = URL.createObjectURL(file); const link = document.createElement('a'); link.href = url; link.download = `分镜-场${working.sourceSnapshot.sceneNo}.md`; document.body.append(link); link.click(); link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 0); if (savedFirst) setExportNotice('已保存本地修改并下载 Java 已保存版本。'); else if (!dirty) setExportNotice(`已下载 Java 保存的 r${working.revision}。`) } catch (reason) { setExportNotice(reason instanceof Error ? reason.message : '导出未完成。') } finally { setExporting(false) } }
  return <main className="workbench-shell"><AppHeader trail={<><Link to="/projects">项目</Link><span>/</span><Link to={`/projects/${projectId}/scripts`}>剧本版本</Link><span>/</span><span>分镜工作台</span></>} actions={<span className="integration-note">Java 保存产物 · r{working?.revision ?? '—'}</span>} />{error ? <ErrorState title="暂时无法打开分镜" detail={error} onRetry={() => void load()} /> : state === null ? <LoadingBlock /> : <section className="workbench" aria-label="单场景分镜工作台"><aside className="scene-panel"><div className="panel-head"><span>场景</span><strong>{state.scenes.length}</strong></div><div className="scene-list">{state.scenes.map((scene) => <button className={scene.id === selectedSceneId ? 'scene-item scene-item--selected' : 'scene-item'} key={scene.id} onClick={() => setSelectedSceneId(scene.id)}><span>场 {scene.sceneNo}</span><strong>{scene.heading}</strong><small>{scene.summary}</small></button>)}</div><div className="saved-storyboards"><span>已保存分镜</span>{state.storyboards.map((item) => <button className={item.id === working?.id ? 'draft-link draft-link--active' : 'draft-link'} key={item.id} onClick={() => void openSavedStoryboard(item.id)}><b>场 {item.sceneNo}</b><span>{item.shotCount} 镜 · r{item.revision}</span></button>)}</div></aside><section className="shots-panel">{!working || generating ? <GenerationPanel scene={selectedScene} activeRun={run} runMessage={runMessage} onGenerate={submitGeneration} onCancel={() => void cancelRun()} onRetry={() => activeRunId && void observeRun(activeRunId)} /> : <><div className="workbench-title"><div><p className="section-label">场 {working.sourceSnapshot.sceneNo}</p><h1>{working.sourceSnapshot.heading}</h1><p>分镜草稿 r{working.revision} · {working.shots.length} 个镜头 · 预计 {working.shots.reduce((total, shot) => total + shot.durationSeconds, 0)} 秒</p></div><div className="title-actions"><StatusBadge status={run?.status ?? 'COMPLETED'} /><Button className="button--secondary button--small" onClick={() => { if (!dirty || window.confirm('当前分镜有未保存修改。继续生成不会覆盖它，但请先保存或确认保留本地编辑。')) setGenerating(true) }}>生成另一份</Button></div></div>{run && <RunStrip run={run} message={runMessage} onCancel={() => void cancelRun()} onResume={() => activeRunId && void observeRun(activeRunId)} />}{dirty && <div className="unsaved-strip">有未保存修改。任务进度和新响应不会覆盖当前编辑。</div>}<div className="shots-list">{working.shots.map((shot) => <ShotCard key={shot.id} shot={shot} selected={shot.id === selectedShot?.id} onSelect={() => setSelectedShotId(shot.id)} />)}</div></>}</section><aside className="editor-panel">{working && selectedShot ? <ShotEditor shot={selectedShot} sourceText={working.sourceSnapshot.sceneText} sourceOpen={sourceOpen} dirty={dirty} saving={saving} notice={saveNotice} conflict={conflict} proposal={proposal?.targetShotId === selectedShot.id ? proposal : null} proposalBusy={proposalBusy} proposalNotice={proposalNotice} rewriteOpen={rewriteOpen} exporting={exporting} exportNotice={exportNotice} onExport={() => void exportMarkdown()} onSourceToggle={() => setSourceOpen((open) => !open)} onChange={(next) => updateWorking((current) => ({ ...current, shots: current.shots.map((shot) => shot.id === next.id ? next : shot) }))} onMove={(direction) => updateWorking((current) => moveShot(current, selectedShot.id, direction))} onSave={() => void save()} onStartRewrite={beginRewrite} onCancelRewrite={() => setRewriteOpen(false)} onSubmitRewrite={submitRewrite} onAcceptProposal={() => void acceptProposal()} onRejectProposal={() => void rejectProposal()} onUseServer={() => { if (conflict && window.confirm('将放弃本地未保存修改并加载服务器版本。确定吗？')) { setWorking(conflict); setSelectedShotId(conflict.shots[0]?.id ?? ''); setDirty(false); setConflict(null); setSaveNotice('已加载服务器 r' + conflict.revision + '。') } }} /> : <WorkbenchEmpty scene={selectedScene} />}</aside></section>}</main>
}

function GenerationPanel({ scene, activeRun, runMessage, onGenerate, onCancel, onRetry }: { scene: Scene | null; activeRun: AgentRun | null; runMessage: string; onGenerate: (count: number, instructions: string) => Promise<void>; onCancel: () => void; onRetry: () => void }) {
  const [count, setCount] = useState(6); const [instructions, setInstructions] = useState(''); const [pending, setPending] = useState(false); const [formError, setFormError] = useState(''); const busy = activeRun && !terminal(activeRun.status)
  async function submit(event: FormEvent) { event.preventDefault(); if (!scene) return; if (scene.rawText.replace(/\r\n?/g, '\n').length > 8000) { setFormError('所选场景超过 8,000 字符，请缩小场景范围后再生成。'); return } if (instructions.replace(/\r\n?/g, '\n').length > 1000) { setFormError('创作要求最多 1,000 字符。'); return }; setPending(true); setFormError(''); try { await onGenerate(count, instructions) } catch (reason) { setFormError(reason instanceof Error ? reason.message : '生成任务未提交。') } finally { setPending(false) } }
  if (!scene) return <EmptyState title="还没有可选场景" detail="返回剧本版本页粘贴文本并完成解析。" action={<span className="muted">解析完成后可在这里选择场景。</span>} />
  return <section className="generation-panel"><p className="section-label">首次生成</p><h1>{scene.heading}</h1><p>选择 4–8 个镜头并填写创作要求。新任务会创建另一份分镜，不会覆盖已有剧本或草稿。</p><form onSubmit={(event) => void submit(event)}><label>镜头数<select value={count} onChange={(event) => setCount(Number(event.target.value))}>{[4, 5, 6, 7, 8].map((value) => <option value={value} key={value}>{value} 个镜头</option>)}</select></label><label>创作要求<textarea value={instructions} onChange={(event) => setInstructions(event.target.value)} maxLength={1000} rows={5} placeholder="例如：克制、冷色调，突出信件状态与人物距离。" /></label>{formError && <p className="form-error" role="alert">{formError}</p>}<Button type="submit" disabled={pending || Boolean(busy)}>{pending ? '正在提交…' : busy ? '任务进行中' : '生成分镜'}</Button></form>{activeRun && <RunStrip run={activeRun} message={runMessage} onCancel={onCancel} onResume={onRetry} />}</section>
}

function RunStrip({ run, message, onCancel, onResume }: { run: AgentRun; message: string; onCancel: () => void; onResume: () => void }) { return <div className="run-strip"><div><StatusBadge status={run.status} /><span>{message || (run.status === 'FAILED' ? '任务失败，请检查输入后发起新任务。' : '正在等待任务状态。')}</span></div>{!terminal(run.status) ? <Button className="button--secondary button--small" onClick={onCancel}>停止</Button> : run.status === 'FAILED' ? <Button className="button--secondary button--small" onClick={onResume}>查询原任务</Button> : null}</div> }

function WorkbenchEmpty({ scene }: { scene: Scene | null }) { return <section className="workspace-empty"><div><p className="section-label">单场景分镜</p><h1>{scene ? '从所选场景生成第一份分镜。' : '先选择一场戏。'}</h1><p>正式分镜只会从 Java 已保存的产物加载。任务失败或取消时，已有编辑和草稿都会保留。</p></div></section> }

function ShotEditor({ shot, sourceText, sourceOpen, dirty, saving, notice, conflict, proposal, proposalBusy, proposalNotice, rewriteOpen, exporting, exportNotice, onExport, onSourceToggle, onChange, onMove, onSave, onStartRewrite, onCancelRewrite, onSubmitRewrite, onAcceptProposal, onRejectProposal, onUseServer }: { shot: Shot; sourceText: string; sourceOpen: boolean; dirty: boolean; saving: boolean; notice: string; conflict: Storyboard | null; proposal: ShotProposal | null; proposalBusy: boolean; proposalNotice: string; rewriteOpen: boolean; exporting: boolean; exportNotice: string; onExport: () => void; onSourceToggle: () => void; onChange: (shot: Shot) => void; onMove: (direction: -1 | 1) => void; onSave: () => void; onStartRewrite: () => void; onCancelRewrite: () => void; onSubmitRewrite: (instruction: string) => Promise<void>; onAcceptProposal: () => void; onRejectProposal: () => void; onUseServer: () => void }) {
  const set = <K extends keyof Shot>(key: K, value: Shot[K]) => onChange({ ...shot, [key]: value })
  return <div className="editor-content"><div className="editor-head"><div><p className="section-label">当前镜头</p><h2>镜头 {shot.orderIndex + 1}</h2></div><span className={dirty ? 'unsaved-pill' : 'read-only'}>{dirty ? '未保存' : '已保存'}</span></div><div className="editor-actions"><Button className="button--secondary button--small" onClick={() => onMove(-1)} disabled={proposalBusy || shot.orderIndex === 0}>上移</Button><Button className="button--secondary button--small" onClick={() => onMove(1)} disabled={proposalBusy}>下移</Button><Button className="button--small" onClick={onSave} disabled={!dirty || saving || proposalBusy}>{saving ? '正在保存…' : '保存修改'}</Button><Button className="button--secondary button--small" onClick={onExport} disabled={exporting}>{exporting ? '正在导出…' : '下载 Markdown'}</Button><Button className="button--secondary button--small" onClick={onStartRewrite} disabled={proposalBusy}>重做此镜头</Button></div>{notice && <div className={conflict ? 'editor-notice editor-notice--warning' : 'editor-notice'}>{notice}{conflict && <button onClick={onUseServer}>加载服务器 r{conflict.revision}</button>}</div>}{exportNotice && <div className="editor-notice">{exportNotice}</div>}{proposalNotice && <div className="editor-notice">{proposalNotice}</div>}{rewriteOpen && <RewriteForm onCancel={onCancelRewrite} onSubmit={onSubmitRewrite} />}{proposal && <ProposalComparison original={shot} proposal={proposal} busy={proposalBusy} onAccept={onAcceptProposal} onReject={onRejectProposal} />}<fieldset className="editor-edit-fields" disabled={proposalBusy}><div className="field-grid"><label className="field"><span>景别</span><select value={shot.shotSize} onChange={(event) => set('shotSize', event.target.value as ShotSize)}>{(['ESTABLISHING', 'WIDE', 'MEDIUM', 'CLOSE_UP', 'EXTREME_CLOSE_UP'] as ShotSize[]).map((value) => <option value={value} key={value}>{labelShotSize(value)}</option>)}</select></label><label className="field"><span>运镜</span><select value={shot.cameraMovement} onChange={(event) => set('cameraMovement', event.target.value as CameraMovement)}>{(['STATIC', 'PAN', 'TILT', 'DOLLY_IN', 'DOLLY_OUT', 'TRACK', 'HANDHELD'] as CameraMovement[]).map((value) => <option value={value} key={value}>{labelMovement(value)}</option>)}</select></label><label className="field"><span>预计时长（秒）</span><input type="number" min="1" max="30" value={shot.durationSeconds} onChange={(event) => set('durationSeconds', Number(event.target.value))} /></label></div><EditableField label="画面描述" value={shot.visualDescription} onChange={(value) => set('visualDescription', value)} /><EditableField label="对白" value={shot.dialogue} onChange={(value) => set('dialogue', value)} /><EditableField label="声音" value={shot.sound} onChange={(value) => set('sound', value)} /><PromptEditor label="图像 Prompt" value={shot.imagePrompt} onChange={(value) => set('imagePrompt', value)} /><PromptEditor label="视频 Prompt" value={shot.videoPrompt} onChange={(value) => set('videoPrompt', value)} /><section className="source-check"><button onClick={onSourceToggle} aria-expanded={sourceOpen}>核对原文 <span>{sourceOpen ? '−' : '+'}</span></button><blockquote>{shot.sourceQuote}</blockquote>{sourceOpen && <pre>{sourceText}</pre>}</section></fieldset></div>
}

function RewriteForm({ onCancel, onSubmit }: { onCancel: () => void; onSubmit: (instruction: string) => Promise<void> }) {
  const [instruction, setInstruction] = useState(''); const [pending, setPending] = useState(false); const [error, setError] = useState('')
  async function submit(event: FormEvent) { event.preventDefault(); if (!instruction.trim()) { setError('请填写修改要求。'); return }; setPending(true); setError(''); try { await onSubmit(instruction) } catch (reason) { setError(reason instanceof Error ? reason.message : '重做任务未提交。') } finally { setPending(false) } }
  return <form className="rewrite-form" onSubmit={(event) => void submit(event)}><label className="field field--wide"><span>修改要求</span><textarea value={instruction} onChange={(event) => setInstruction(event.target.value)} maxLength={1000} rows={3} placeholder="例如：改为更克制的静态构图，保持道具状态和对白。" /></label>{error && <p className="form-error" role="alert">{error}</p>}<div><Button type="submit" className="button--small" disabled={pending}>{pending ? '正在提交…' : '生成候选'}</Button><Button type="button" className="button--secondary button--small" onClick={onCancel}>取消</Button></div></form>
}

function ProposalComparison({ original, proposal, busy, onAccept, onReject }: { original: Shot; proposal: ShotProposal; busy: boolean; onAccept: () => void; onReject: () => void }) {
  return <section className="proposal-comparison" aria-label="原镜头与候选镜头对比"><div className="proposal-comparison__head"><div><p className="section-label">镜头候选</p><h3>原镜头与候选对比</h3></div><span className="read-only">基于 r{proposal.baseStoryboardRevision}</span></div><div className="proposal-columns"><ShotComparison title="原镜头" shot={original} /><ShotComparison title="候选镜头" shot={proposal.candidateShot} /></div><div className="editor-actions"><Button className="button--small" onClick={onAccept} disabled={busy}>{busy ? '正在处理…' : '采纳候选'}</Button><Button className="button--secondary button--small" onClick={onReject} disabled={busy}>放弃候选</Button></div></section>
}

function ShotComparison({ title, shot }: { title: string; shot: Pick<EditableShot, 'shotSize' | 'cameraMovement' | 'visualDescription' | 'dialogue' | 'sound' | 'durationSeconds' | 'imagePrompt' | 'videoPrompt'> }) { return <article><h4>{title}</h4><p>{labelShotSize(shot.shotSize)} · {labelMovement(shot.cameraMovement)} · {shot.durationSeconds} 秒</p><dl><dt>画面</dt><dd>{shot.visualDescription}</dd><dt>对白</dt><dd>{shot.dialogue || '—'}</dd><dt>声音</dt><dd>{shot.sound || '—'}</dd><dt>图像 Prompt</dt><dd>{shot.imagePrompt}</dd><dt>视频 Prompt</dt><dd>{shot.videoPrompt}</dd></dl></article> }

function EditableField({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) { return <label className="field field--wide"><span>{label}</span><textarea value={value} onChange={(event) => onChange(event.target.value)} rows={label.includes('Prompt') ? 4 : 3} /></label> }
function PromptEditor({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) { const [copied, setCopied] = useState(false); const copy = async () => { try { await navigator.clipboard.writeText(value); setCopied(true); window.setTimeout(() => setCopied(false), 1600) } catch { setCopied(false) } }; return <label className="field field--wide"><span className="prompt-label">{label}<button type="button" onClick={() => void copy()}>{copied ? '已复制' : '复制'}</button></span><textarea value={value} onChange={(event) => onChange(event.target.value)} rows={4} /></label> }

function moveShot(storyboard: Storyboard, shotId: string, direction: -1 | 1): Storyboard { const from = storyboard.shots.findIndex((shot) => shot.id === shotId); const to = from + direction; if (from < 0 || to < 0 || to >= storyboard.shots.length) return storyboard; const shots = [...storyboard.shots]; [shots[from], shots[to]] = [shots[to], shots[from]]; return { ...storyboard, shots: shots.map((shot, orderIndex) => ({ ...shot, orderIndex })) } }
function toSummary(storyboard: Storyboard): StoryboardSummary { return { id: storyboard.id, projectId: storyboard.projectId, scriptId: storyboard.sourceSnapshot.scriptId, scriptRevision: storyboard.sourceSnapshot.scriptRevision, sceneNo: storyboard.sourceSnapshot.sceneNo, heading: storyboard.sourceSnapshot.heading, revision: storyboard.revision, shotCount: storyboard.shots.length, createdAt: storyboard.createdAt, updatedAt: storyboard.updatedAt } }
