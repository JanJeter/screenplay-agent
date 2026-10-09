import { useEffect, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react'
import type { RunStatus, Shot } from './types'
import { currentWorkbenchUrl, isLegacyWorkbench } from './environment'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from './auth'
import { Icon } from './icons'
export { Icon } from './icons'

export function AppShell({ children, className = '' }: { children: ReactNode; className?: string }) {
  const auth = useAuth(); const location = useLocation(); const [recentWorkspace, setRecentWorkspace] = useState('')
  const mainRef = useRef<HTMLDivElement>(null)
  const inWorkspace = /^\/projects\/[^/]+\/scripts\/[^/]+\/storyboard$/.test(location.pathname)
  const workspacePath = inWorkspace ? `${location.pathname}${location.search}` : ''
  const identity = auth.profile ? `${auth.profile.organizationId}.${auth.profile.id}` : ''
  useEffect(() => {
    if (!identity) { setRecentWorkspace(''); return }
    try {
      const key = `storyboard-web.recent-workspace.${identity}`
      if (workspacePath) sessionStorage.setItem(key, workspacePath)
      const stored = sessionStorage.getItem(key) ?? ''
      setRecentWorkspace(/^\/projects\/[^/?#]+\/scripts\/[^/?#]+\/storyboard(?:\?[^#]*)?$/.test(stored) ? stored : '')
    } catch { setRecentWorkspace(workspacePath) }
  }, [identity, workspacePath])
  const storyboardLink = workspacePath || recentWorkspace
  return <div className={`app-shell ${className}`}>
    <a className="skip-link" href="#app-content" onClick={(event) => { event.preventDefault(); mainRef.current?.focus() }}>跳到主要内容</a>
    <aside className="app-rail" aria-label="主导航">
      <Link className="brand-mark" to="/projects" aria-label="镜场首页"><Icon name="logo" /></Link>
      <nav className="rail-nav">
        <Link className={`rail-link ${location.pathname.startsWith('/projects') && !inWorkspace ? 'active' : ''}`} to="/projects" aria-current={location.pathname.startsWith('/projects') && !inWorkspace ? 'page' : undefined}><Icon name="folder" /><span>项目</span></Link>
        {storyboardLink ? <Link className={`rail-link ${inWorkspace ? 'active' : ''}`} to={storyboardLink} aria-current={inWorkspace ? 'page' : undefined} title="继续最近打开的分镜"><Icon name="film" /><span>分镜</span></Link> : <span className="rail-link rail-link--unavailable" title="从项目中选择一版剧本，开始分镜创作" aria-label="分镜：先从项目选择剧本"><Icon name="film" /><span>分镜</span></span>}
        {auth.isAdmin && <Link className={`rail-link ${location.pathname.startsWith('/admin') ? 'active' : ''}`} to="/admin/users" aria-current={location.pathname.startsWith('/admin') ? 'page' : undefined}><Icon name="users" /><span>管理</span></Link>}
      </nav>
      <div className="rail-bottom"><span className="rail-wordmark">镜场</span><span className="avatar" title={auth.profile?.fullName || '团队成员'} aria-hidden="true">{auth.profile?.fullName?.slice(0, 1) || '镜'}</span></div>
    </aside>
    <div className="app-content" id="app-content" ref={mainRef} tabIndex={-1}>{children}</div>
  </div>
}

const statusText: Record<RunStatus, string> = { QUEUED: '排队中', RUNNING: '正在生成分镜', FINALIZING: '正在保存结果', CANCELLING: '正在停止', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消', INTERRUPTED: '已中断' }
export function StatusBadge({ status }: { status: RunStatus | string }) { return <span className={`status status--${status.toLowerCase()}`}>{statusText[status as RunStatus] ?? status}</span> }
export function EnvironmentNotice() {
  if (isLegacyWorkbench) return <span className="environment-notice">历史测试环境 · 只读 <a href={currentWorkbenchUrl} target="_blank" rel="noopener noreferrer">打开新工作台</a></span>
  const fixture = import.meta.env.VITE_STORYBOARD_DATA_SOURCE === 'fixtures'
  if (!fixture && import.meta.env.VITE_AGENT_MODE === 'live') return <span className="environment-notice">真实模型 · 当前工作台</span>
  if (!fixture && import.meta.env.VITE_AGENT_MODE !== 'mock') return null
  return <span className="environment-notice">{fixture ? '示例数据 · 流程演示' : 'Mock · 流程测试，不调用真实模型'}</span>
}
export function AppHeader({ trail, actions }: { trail: ReactNode; actions?: ReactNode }) { return <header className="app-header"><div className="trail">{trail}</div><div className="header-actions"><EnvironmentNotice />{actions}<AccountMenu /></div></header> }
function AccountMenu() {
  const auth = useAuth()
  if (!auth.session) return null
  return <details className="account-menu"><summary>{auth.profile?.fullName || '账号'}<span aria-hidden="true">⌄</span></summary><div className="account-menu__body">{auth.profile ? <><strong>{auth.profile.fullName}</strong><span>{auth.profile.email}</span><span>{auth.profile.organizationName}</span><Link to="/projects">团队工作台</Link>{auth.isAdmin && <Link to="/admin/users">后台管理</Link>}</> : auth.profileLoading ? <span>正在读取账号…</span> : <><span>账号资料暂时不可用。</span><button className="text-button" onClick={() => void auth.refreshProfile()}>重新读取账号</button></>}<button className="text-button" onClick={() => void auth.logout()}>退出登录</button></div></details>
}
export function Button({ className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement>) { return <button className={`button ${className}`} {...props} /> }
export function LoadingBlock({ label = '正在读取工作台…' }: { label?: string }) { return <section className="state state--loading" aria-live="polite"><div className="skeleton skeleton--title" /><div className="skeleton" /><div className="skeleton" /><span>{label}</span></section> }
export function ErrorState({ title = '无法加载此内容', detail, onRetry }: { title?: string; detail: string; onRetry?: () => void }) { return <section className="state state--error" role="alert"><p className="state-mark">!</p><h2>{title}</h2><p>{detail}</p>{onRetry && <Button onClick={onRetry}>重新加载</Button>}</section> }
export function EmptyState({ title, detail, action }: { title: string; detail: string; action: ReactNode }) { return <section className="state"><p className="state-mark">+</p><h2>{title}</h2><p>{detail}</p>{action}</section> }
export function ShotCard({ shot, selected, onSelect }: { shot: Shot; selected: boolean; onSelect: () => void }) {
  return <button className={`shot-card ${selected ? 'shot-card--selected' : ''}`} onClick={onSelect} aria-pressed={selected} aria-label={`镜头 ${shot.orderIndex + 1} · ${labelShotSize(shot.shotSize)} · ${shot.visualDescription}`}><span className="shot-number" aria-hidden="true">{String(shot.orderIndex + 1).padStart(2, '0')}</span><span className="shot-content"><span className="shot-description">{shot.visualDescription}</span><span className="shot-meta">{labelShotSize(shot.shotSize)} · {labelMovement(shot.cameraMovement)} · {shot.durationSeconds} 秒</span></span></button>
}
export function labelShotSize(value: Shot['shotSize']) { return ({ ESTABLISHING: '建立镜头', WIDE: '全景', MEDIUM: '中景', CLOSE_UP: '近景', EXTREME_CLOSE_UP: '特写' })[value] }
export function labelMovement(value: Shot['cameraMovement']) { return ({ STATIC: '固定', PAN: '摇镜', TILT: '俯仰', DOLLY_IN: '推镜', DOLLY_OUT: '拉镜', TRACK: '跟拍', HANDHELD: '手持' })[value] }
export function labelSceneHeading(heading: string) { return heading === 'UNSEGMENTED SCRIPT' ? '未识别场景标题' : heading }
