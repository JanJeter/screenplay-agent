import { FormEvent, useEffect, useState } from 'react'
import { Link, Navigate, useParams } from 'react-router-dom'
import { AppHeader, Button, EmptyState, ErrorState, LoadingBlock } from '../components'
import { useApi } from '../api/api-context'
import { useAuth } from '../auth'
import type { Script } from '../types'

export function ScriptsPage() {
  const { projectId = '' } = useParams(); const api = useApi(); const auth = useAuth(); const [scripts, setScripts] = useState<Script[] | null>(null); const [error, setError] = useState(''); const [importing, setImporting] = useState(false); const [pending, setPending] = useState(false)
  const load = () => { setError(''); setScripts(null); api.listScripts(projectId).then(setScripts).catch((reason: Error) => setError(reason.message)) }
  useEffect(load, [api, projectId])
  if (!auth.session) return <Navigate to="/login" replace state={{ from: `/projects/${projectId}/scripts` }} />
  async function saveAndAnalyze(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget); const rawText = String(form.get('rawText') ?? '').replace(/\r\n?/g, '\n'); if (!rawText.trim()) return
    setPending(true); setError('')
    try { const script = await api.createScript(projectId, { versionName: String(form.get('versionName') ?? '').trim() || '未命名版本', originalFilename: String(form.get('originalFilename') ?? '').trim() || '粘贴剧本.txt', rawText }); await api.analyzeScript(script.id); setImporting(false); load() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '剧本导入或解析未完成。') } finally { setPending(false) }
  }
  return <main className="page-shell"><AppHeader trail={<><Link to="/projects">项目</Link><span>/</span><span>剧本版本</span></>} /><section className="page-heading"><div><p className="section-label">剧本版本</p><h1>导入并解析场景</h1><p>粘贴带场景头的短剧本。解析完成后选择一场戏，生成一份独立分镜草稿。</p></div><Button onClick={() => setImporting((open) => !open)}>{importing ? '收起导入' : '粘贴剧本'}</Button></section>{importing && <form className="inline-form script-import-form" onSubmit={(event) => void saveAndAnalyze(event)}><label>版本名称<input name="versionName" maxLength={80} defaultValue="第一稿" required /></label><label>文件名<input name="originalFilename" maxLength={255} defaultValue="粘贴剧本.txt" /></label><label className="form-span">剧本文本<textarea name="rawText" rows={12} maxLength={500000} placeholder={'INT. 旧公寓客厅 - 夜\n\n雨敲着窗。许晴站在门边，没有进来。'} required /></label><p className="form-hint form-span">保存后立即使用现有解析服务；解析失败时不会覆盖已保存版本。</p><div className="form-actions"><Button type="submit" disabled={pending}>{pending ? '正在解析场景…' : '保存并解析'}</Button><Button type="button" className="button--secondary" onClick={() => setImporting(false)}>取消</Button></div></form>}{error ? <ErrorState detail={error} onRetry={load} /> : scripts === null ? <LoadingBlock label="正在读取剧本版本…" /> : scripts.length === 0 ? <EmptyState title="还没有剧本版本" detail="粘贴短剧本，系统会解析其中的场景。" action={<Button onClick={() => setImporting(true)}>粘贴剧本</Button>} /> : <section className="project-list">{scripts.map((script) => <Link className="project-row" to={`/projects/${projectId}/scripts/${script.id}/storyboard`} key={script.id}><span><strong>{script.versionName}</strong><small>{script.originalFilename} · {script.status === 'ANALYZED' ? '已解析，可选择场景' : script.status}</small></span><span className="project-genre">进入分镜</span><span aria-hidden="true">→</span></Link>)}</section>}</main>
}
