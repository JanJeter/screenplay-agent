import { FormEvent, useEffect, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { AppHeader, Button, EmptyState, ErrorState, LoadingBlock } from '../components'
import { useApi } from '../api/api-context'
import { useAuth } from '../auth'
import type { Project } from '../types'

export function ProjectsPage() {
  const api = useApi(); const auth = useAuth(); const navigate = useNavigate(); const [projects, setProjects] = useState<Project[] | null>(null); const [error, setError] = useState(''); const [creating, setCreating] = useState(false); const [pending, setPending] = useState(false)
  const load = () => { setError(''); setProjects(null); api.listProjects().then(setProjects).catch((reason: Error) => setError(reason.message)) }
  useEffect(load, [api])
  if (!auth.session) return <Navigate to="/login" replace state={{ from: '/projects' }} />
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget); const name = String(form.get('name') ?? '').trim(); if (!name) return
    setPending(true); setError('')
    try { const project = await api.createProject({ name, description: String(form.get('description') ?? '').trim(), genre: String(form.get('genre') ?? '').trim() }); navigate(`/projects/${project.id}/scripts`) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '创建项目未完成。') } finally { setPending(false) }
  }
  return <main className="page-shell"><AppHeader trail={<><strong>镜场</strong><span>/</span><span>项目</span></>} actions={<button className="text-button" onClick={() => void auth.logout()}>退出登录</button>} /><section className="page-heading"><div><p className="section-label">项目</p><h1>从一场戏开始制作分镜</h1><p>创建项目，粘贴剧本并解析场景。分镜会作为独立草稿保存，不会改写原剧本。</p></div><Button onClick={() => setCreating((open) => !open)}>{creating ? '收起创建' : '创建项目'}</Button></section>{creating && <form className="inline-form" onSubmit={(event) => void create(event)}><label>项目名称<input name="name" maxLength={120} required autoFocus /></label><label>类型<input name="genre" maxLength={120} placeholder="例如：悬疑短片" /></label><label className="form-span">项目说明<textarea name="description" maxLength={10000} rows={3} /></label><div className="form-actions"><Button type="submit" disabled={pending}>{pending ? '正在创建…' : '创建并导入剧本'}</Button><Button type="button" className="button--secondary" onClick={() => setCreating(false)}>取消</Button></div></form>}{error ? <ErrorState detail={error} onRetry={load} /> : projects === null ? <LoadingBlock label="正在读取项目…" /> : projects.length === 0 ? <EmptyState title="还没有项目" detail="创建一个项目，然后导入剧本文本并完成场景解析。" action={<Button onClick={() => setCreating(true)}>创建项目</Button>} /> : <section className="project-list">{projects.map((project) => <Link className="project-row" to={`/projects/${project.id}/scripts`} key={project.id}><span><strong>{project.name}</strong><small>{project.description || '尚未填写项目说明'}</small></span><span className="project-genre">{project.genre || '未分类'}</span><span aria-hidden="true">→</span></Link>)}</section>}</main>
}
