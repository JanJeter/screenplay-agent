import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { Link, Navigate, useBlocker, useParams } from 'react-router-dom'
import { AppHeader, AppShell, Button, EmptyState, ErrorState, Icon, LoadingBlock } from '../components'
import { useApi } from '../api/api-context'
import { useAuth } from '../auth'
import type { Script } from '../types'

const scriptStatuses: Record<string, string> = { UPLOADED: '待解析', ANALYZED: '已解析', ANALYZE_FAILED: '解析未完成' }
const scriptStatus = (status: string) => scriptStatuses[status] ?? status
const scriptDate = (value?: string) => value && !Number.isNaN(new Date(value).getTime()) ? new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' }).format(new Date(value)) : ''

export function ScriptsPage() {
  const { projectId = '' } = useParams(); const api = useApi(); const auth = useAuth()
  const [scripts, setScripts] = useState<Script[] | null>(null); const [error, setError] = useState(''); const [projectName, setProjectName] = useState(''); const [selectedId, setSelectedId] = useState('')
  const [importing, setImporting] = useState(false); const [pending, setPending] = useState(false); const [formError, setFormError] = useState(''); const [analysisError, setAnalysisError] = useState('')
  const [versionName, setVersionName] = useState('第一稿'); const [originalFilename, setOriginalFilename] = useState('粘贴剧本.txt'); const [rawText, setRawText] = useState(''); const [savedBeforeAnalysis, setSavedBeforeAnalysis] = useState<Script | null>(null)
  const requestVersion = useRef(0); const operationVersion = useRef(0); const submitting = useRef(false)
  const unsavedImport = Boolean(auth.session && rawText.trim() && !savedBeforeAnalysis)
  const navigationBlocker = useBlocker(({ currentLocation, nextLocation }) => unsavedImport && currentLocation.pathname !== nextLocation.pathname)
  useEffect(() => {
    if (navigationBlocker.state !== 'blocked') return
    if (window.confirm('当前剧本尚未保存，确定离开吗？')) navigationBlocker.proceed()
    else navigationBlocker.reset()
  }, [navigationBlocker])
  useEffect(() => {
    if (!unsavedImport) return
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    const beforeLogout = (event: Event) => { if (!window.confirm('当前剧本尚未保存，确定退出登录吗？')) event.preventDefault() }
    window.addEventListener('beforeunload', beforeUnload); window.addEventListener('storyboard:before-logout', beforeLogout)
    return () => { window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('storyboard:before-logout', beforeLogout) }
  }, [unsavedImport])
  const load = useCallback(async (preferredId?: string) => {
    const version = ++requestVersion.current
    setError(''); setScripts(null)
    try {
      const items = await api.listScripts(projectId)
      if (version !== requestVersion.current) return
      setScripts(items); setSelectedId((current) => items.some((item) => item.id === (preferredId ?? current)) ? (preferredId ?? current) : items[0]?.id ?? '')
    } catch (reason) { if (version === requestVersion.current) setError(reason instanceof Error ? reason.message : '剧本版本暂时无法读取。') }
  }, [api, projectId])
  useEffect(() => {
    void load()
    return () => { requestVersion.current += 1 }
  }, [load])
  useEffect(() => {
    let cancelled = false
    setProjectName('')
    void api.listProjects().then((projects) => { if (!cancelled) setProjectName(projects.find((project) => project.id === projectId)?.name ?? '') }).catch(() => undefined)
    return () => { cancelled = true }
  }, [api, projectId])
  useEffect(() => {
    operationVersion.current += 1; submitting.current = false; setPending(false); setImporting(false); setSavedBeforeAnalysis(null); setFormError(''); setAnalysisError(''); setVersionName('第一稿'); setOriginalFilename('粘贴剧本.txt'); setRawText('')
    return () => { operationVersion.current += 1 }
  }, [projectId])
  if (!auth.session) return <Navigate to="/login" replace state={{ from: '/projects/' + projectId + '/scripts' }} />
  const selectedScript = scripts?.find((script) => script.id === selectedId) ?? scripts?.[0] ?? null
  const clearImport = () => { setVersionName('第一稿'); setOriginalFilename('粘贴剧本.txt'); setRawText(''); setSavedBeforeAnalysis(null); setFormError('') }
  const closeImport = () => {
    if (submitting.current) return
    setImporting(false); setFormError('')
    if (savedBeforeAnalysis) { const savedId = savedBeforeAnalysis.id; clearImport(); void load(savedId) }
  }
  async function saveAndAnalyze(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const normalizedText = rawText.replace(/\r\n?/g, '\n')
    if (!savedBeforeAnalysis && !normalizedText.trim()) { setFormError('请先粘贴剧本文本。'); return }
    const version = operationVersion.current
    submitting.current = true; setPending(true); setFormError('')
    try {
      let saved = savedBeforeAnalysis
      if (!saved) {
        const created = await api.createScript(projectId, { versionName: versionName.trim() || '未命名版本', originalFilename: originalFilename.trim() || '粘贴剧本.txt', rawText: normalizedText })
        if (version !== operationVersion.current) return
        const createdWithText = { ...created, rawText: created.rawText ?? normalizedText }
        saved = createdWithText
        setSavedBeforeAnalysis(createdWithText); setSelectedId(createdWithText.id); setScripts((current) => [createdWithText, ...(current ?? []).filter((item) => item.id !== createdWithText.id)])
      }
      await api.analyzeScript(saved.id)
      if (version !== operationVersion.current) return
      setImporting(false); clearImport(); await load(saved.id)
    } catch (reason) { if (version === operationVersion.current) setFormError(reason instanceof Error ? reason.message : '剧本导入或解析未完成。') }
    finally { if (version === operationVersion.current) { submitting.current = false; setPending(false) } }
  }
  async function analyzeSelected() {
    if (!selectedScript || submitting.current) return
    const version = operationVersion.current; const selected = selectedScript
    submitting.current = true; setPending(true); setAnalysisError('')
    try { await api.analyzeScript(selected.id); if (version === operationVersion.current) await load(selected.id) }
    catch (reason) { if (version === operationVersion.current) setAnalysisError(reason instanceof Error ? reason.message : '场景解析未完成，请稍后重试。') }
    finally { if (version === operationVersion.current) { submitting.current = false; setPending(false) } }
  }
  return <AppShell className="scripts-page">
    <AppHeader trail={<><Link to="/projects">项目</Link><span>/</span>{projectName && <><span>{projectName}</span><span>/</span></>}<span>剧本版本</span></>} />
    <main className="page-content">
      <section className="page-heading"><div><h1>{projectName || '剧本版本'}</h1><p>保存故事的每一版，从原文中整理场景，再进入分镜。</p></div><Button onClick={() => importing ? closeImport() : setImporting(true)} disabled={pending}><Icon name={importing ? 'x' : 'plus'} />{importing ? '收起导入' : '粘贴剧本'}</Button></section>
      <div className="scripts-layout">
        <aside className="script-versions" aria-label="剧本版本列表">
          <div className="script-detail__heading"><h2>剧本版本</h2>{scripts && <span className="muted">{scripts.length} 版</span>}</div>
          {error ? <ErrorState detail={error} onRetry={() => void load()} /> : scripts === null ? <LoadingBlock label="正在读取剧本版本…" /> : scripts.length === 0 ? <p className="script-context">还没有剧本版本。粘贴文本，开始整理这个故事。</p> : scripts.map((script) => <article className={'script-version' + (selectedScript?.id === script.id ? ' active' : '')} key={script.id}>
            <button className="script-version__select" aria-pressed={selectedScript?.id === script.id} disabled={pending} onClick={() => { setSelectedId(script.id); setAnalysisError(''); if (!savedBeforeAnalysis) setImporting(false) }}><strong>{script.versionName}</strong><span>{scriptStatus(script.status)}{scriptDate(script.createdAt) && ' · ' + scriptDate(script.createdAt)}</span><small>{script.originalFilename}</small></button>
            <Link className="script-version__open" to={'/projects/' + projectId + '/scripts/' + script.id + '/storyboard'} aria-label={'进入分镜 · ' + script.versionName}>进入分镜<Icon name="arrow" /></Link>
          </article>)}
        </aside>
        <section className="script-detail" aria-label={importing ? '导入剧本' : '剧本原文'}>
          {importing ? <>
            <div className="script-detail__heading"><div><h2>粘贴一版剧本</h2><p className="script-context">保留场景头和对白，让故事的结构更清楚。</p></div><Icon name="file" /></div>
            <form className="inline-form script-import-form" onSubmit={(event) => void saveAndAnalyze(event)}>
              <label>版本名称<input name="versionName" maxLength={80} value={versionName} onChange={(event) => setVersionName(event.target.value)} readOnly={pending || Boolean(savedBeforeAnalysis)} required /></label>
              <label>文件名<input name="originalFilename" maxLength={255} value={originalFilename} onChange={(event) => setOriginalFilename(event.target.value)} readOnly={pending || Boolean(savedBeforeAnalysis)} /></label>
              <label className="form-span">剧本文本<textarea name="rawText" rows={15} maxLength={500000} value={rawText} onChange={(event) => setRawText(event.target.value)} readOnly={pending || Boolean(savedBeforeAnalysis)} placeholder={'INT. 旧公寓客厅 - 夜\n\n雨敲着窗。许晴站在门边，没有进来。'} required /></label>
              <p className="form-hint form-span">{savedBeforeAnalysis ? '这版剧本已保存。重试只会解析同一版本；如需修改原文，请取消后另建版本。' : '保存原文后解析场景。每次导入都会保留为独立版本。'}</p>
              {formError && <p className="form-error form-span" role="alert">{formError}</p>}
              <div className="form-actions"><Button type="submit" disabled={pending}><Icon name="check" />{pending ? '正在解析场景…' : savedBeforeAnalysis ? '重试解析' : '保存并解析'}</Button><Button type="button" className="button--secondary" onClick={closeImport} disabled={pending}>取消</Button></div>
            </form>
          </> : selectedScript ? <>
            <div className="script-detail__heading"><div><h2>{selectedScript.versionName}</h2><p className="script-context">{selectedScript.originalFilename} · {scriptStatus(selectedScript.status)}</p></div><span className="read-only">原文只读</span></div>
            {selectedScript.rawText ? <pre className="script-original">{selectedScript.rawText}</pre> : <p className="script-context">进入分镜，按场景阅读原文，选择一场戏开始创作。</p>}
            {analysisError && <p className="form-error" role="alert">{analysisError}</p>}
            <div className="script-detail__actions"><p className="script-context">{selectedScript.status === 'ANALYZED' ? '场景已解析，选择一场戏开始制作分镜。' : '先解析场景，再选择一场戏制作分镜。'}</p>{selectedScript.status !== 'ANALYZED' && <Button className="button--secondary" onClick={() => void analyzeSelected()} disabled={pending}>{pending ? '正在解析场景…' : '解析场景'}</Button>}<Link className="button" to={'/projects/' + projectId + '/scripts/' + selectedScript.id + '/storyboard'} aria-label="进入当前版本分镜">进入分镜<Icon name="arrow" /></Link></div>
          </> : scripts === null && !error ? <LoadingBlock label="正在读取剧本原文…" /> : <EmptyState title="给故事留一份原稿" detail="粘贴剧本，保存为独立版本。解析完成后，就可以开始分镜。" action={<span className="script-context">使用页面上方的“粘贴剧本”开始。</span>} />}
        </section>
      </div>
    </main>
  </AppShell>
}
