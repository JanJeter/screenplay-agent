import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { AppHeader, AppShell, Button, EmptyState, ErrorState, Icon, LoadingBlock } from '../components'
import { useApi } from '../api/api-context'
import { useAuth } from '../auth'
import type { Project } from '../types'

export function ProjectsPage() {
  const api = useApi(); const auth = useAuth(); const navigate = useNavigate(); const [projects, setProjects] = useState<Project[] | null>(null); const [error, setError] = useState(''); const [creating, setCreating] = useState(false); const [pending, setPending] = useState(false)
  const [createError, setCreateError] = useState(''); const [query, setQuery] = useState(''); const [genre, setGenre] = useState(''); const requestVersion = useRef(0)
  const load = useCallback(async () => {
    const version = ++requestVersion.current; setError(''); setProjects(null)
    try { const result = await api.listProjects(); if (version === requestVersion.current) setProjects(result) }
    catch (reason) { if (version === requestVersion.current) setError(reason instanceof Error ? reason.message : '项目暂时无法加载。') }
  }, [api])
  useEffect(() => { if (auth.session) void load(); return () => { requestVersion.current += 1 } }, [load, auth.session])
  const genres = useMemo(() => [...new Set((projects ?? []).map(project => project.genre.trim()).filter(Boolean))], [projects])
  const matching = useMemo(() => (projects ?? []).filter(project => (!genre || project.genre.trim() === genre) && `${project.name} ${project.description} ${project.genre}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())).sort((a, b) => (Date.parse(b.updatedAt ?? '') || 0) - (Date.parse(a.updatedAt ?? '') || 0)), [projects, genre, query])
  if (!auth.session) return <Navigate to="/login" replace state={{ from: '/projects' }} />
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (pending) return; const form = new FormData(event.currentTarget); const name = String(form.get('name') ?? '').trim(); if (!name) return
    setPending(true); setCreateError('')
    try { const project = await api.createProject({ name, description: String(form.get('description') ?? '').trim(), genre: String(form.get('genre') ?? '').trim() }); navigate(`/projects/${project.id}/scripts`) }
    catch (reason) { setCreateError(reason instanceof Error ? reason.message : '创建项目未完成。') } finally { setPending(false) }
  }
  return <AppShell className="projects-app"><main className="page-shell projects-page">
    <AppHeader trail={<span className="workspace-name"><Icon name="users" />{auth.profile?.organizationName || '团队工作区'}<span className="workspace-name__suffix">／ 团队工作区</span></span>} />
    <div className="page-content">
      <section className="page-heading"><div><h1>创作空间</h1><p>把故事里的画面，一镜一镜写下来。</p></div><Button aria-label={creating ? '收起创建' : '创建项目'} disabled={pending} onClick={() => { setCreating(open => !open); setCreateError('') }}><Icon name={creating ? 'x' : 'plus'} />{creating ? '收起创建' : '新建项目'}</Button></section>
      {creating && <form className="inline-form project-create-form" onSubmit={(event) => void create(event)}>
        <label>项目名称<input name="name" maxLength={120} required autoFocus placeholder="给这个故事起个名字" disabled={pending} /></label>
        <label>类型<input name="genre" maxLength={120} placeholder="例如：悬疑短片" list="project-genres" disabled={pending} /><datalist id="project-genres">{[...new Set([...genres, '叙事短片', '竖屏短剧', '品牌广告'])].map(item => <option key={item} value={item} />)}</datalist></label>
        <label className="form-span">项目说明<textarea name="description" maxLength={10000} rows={3} placeholder="这个故事，想讲什么？" disabled={pending} /></label>
        {createError && <p className="form-error form-span" role="alert">{createError}</p>}
        <div className="form-actions"><Button type="button" className="button--quiet" disabled={pending} onClick={() => setCreating(false)}>取消</Button><Button type="submit" disabled={pending}>{pending ? '正在创建…' : '创建并导入剧本'}<Icon name="arrow" /></Button></div>
      </form>}
      {error ? <ErrorState detail={error} onRetry={() => void load()} /> : projects === null ? <LoadingBlock label="正在读取项目…" /> : <>
        <div className="project-toolbar"><div className="project-filters" aria-label="项目类型"><button type="button" className={`filter-chip ${!genre ? 'active' : ''}`} aria-pressed={!genre} onClick={() => setGenre('')}>全部 <span>{projects.length}</span></button>{genres.map(item => <button type="button" className={`filter-chip ${genre === item ? 'active' : ''}`} aria-pressed={genre === item} key={item} onClick={() => setGenre(item)}>{item}</button>)}</div><label className="project-search"><Icon name="search" /><input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="搜索项目或故事" aria-label="搜索项目" /></label></div>
        <div className="section-heading"><h2>我的项目 <span>{matching.length} 个</span></h2><span>最近更新</span></div>
        {projects.length === 0 ? <EmptyState title="还没有项目" detail="创建一个项目，粘贴剧本，再把一场戏拆成镜头。" action={<Button onClick={() => setCreating(true)}><Icon name="plus" />创建项目</Button>} /> : matching.length === 0 ? <EmptyState title="没有找到这个项目" detail="换个关键词，或查看全部项目。" action={<Button className="button--secondary" onClick={() => { setQuery(''); setGenre('') }}>查看全部项目</Button>} /> : <section className="project-list project-grid" aria-label="项目列表">{matching.map((project, index) => {
          const date = project.updatedAt ? new Date(project.updatedAt) : null
          const updated = date && !Number.isNaN(date.getTime()) ? date.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric' }) : null
          return <Link className={`project-row project-card project-card--${index % 4}`} to={`/projects/${project.id}/scripts`} key={project.id}><div className="project-card__top"><span className="project-genre">{project.genre || '未分类'}</span><h3>{project.name}</h3><p>{project.description || '一个新的故事，等待展开。'}</p></div><div className="project-card__bottom">{updated ? <time dateTime={project.updatedAt}>{updated}更新</time> : <span>打开剧本</span>}<Icon name="arrow" /></div></Link>
        })}</section>}
        <footer className="project-footnote"><Icon name="file" /><span>剧本文本 → 场景拆分 → 分镜创作</span><span>每份分镜独立保存</span></footer>
      </>}
    </div>
  </main></AppShell>
}
