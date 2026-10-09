import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link, Navigate, NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth'
import { useAdminApi, type AdminRole, type AdminUser, type PageResult, type Usage, type UsageSummary } from '../api/admin-api'
import { runErrorMessage } from '../api/run-errors'
import { AppHeader, AppShell, Button, ErrorState, Icon, LoadingBlock, StatusBadge } from '../components'
import '../accounts.css'

function useResource<T>(load: () => Promise<T>) {
  const [data, setData] = useState<T | null>(null); const [error, setError] = useState(''); const [loading, setLoading] = useState(true); const [version, setVersion] = useState(0)
  useEffect(() => { let active = true; setLoading(true); setError(''); void load().then((value) => { if (active) setData(value) }).catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : '内容暂时无法加载。') }).finally(() => { if (active) setLoading(false) }); return () => { active = false } }, [load, version])
  return { data, error, loading, reload: () => setVersion((value) => value + 1) }
}
function Notice({ error, success }: { error?: string; success?: string }) { return error ? <div className="admin-notice admin-notice--error" role="alert">{error}</div> : success ? <div className="admin-notice" role="status">{success}</div> : null }
function PageHeading({ title, description, action }: { title: string; description: string; action?: ReactNode }) { return <div className="admin-heading"><div><h2>{title}</h2><p>{description}</p></div>{action}</div> }
function TableScrollHint() { return <p className="admin-scroll-hint">左右滑动查看状态与操作</p> }
function Pagination<T>({ value, loading, onPage }: { value: PageResult<T>; loading: boolean; onPage: (page: number) => void }) { return <nav className="pagination" aria-label="分页"><span>共 {value.totalElements} 条 · 第 {value.page + 1} / {Math.max(1, value.totalPages)} 页</span><div><Button className="button--secondary button--small" disabled={loading || value.page <= 0} onClick={() => onPage(value.page - 1)}>上一页</Button><Button className="button--secondary button--small" disabled={loading || value.page + 1 >= value.totalPages} onClick={() => onPage(value.page + 1)}>下一页</Button></div></nav> }
const roleName = (name: string) => name === 'ADMIN' ? '管理员' : name === 'USER' ? '成员' : name
const dateTime = (value: string | null) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : '—'
const modeName = (mode: Usage['mode']) => ({ live: '真实模型', mock: 'Mock 流程测试', unknown: '未记录模式' })[mode]
const taskName = (type: string) => ({ generate_storyboard: '生成分镜', rewrite_storyboard_shot: '单镜重做', analyze_script: '解析剧本' })[type] ?? type
const count = (value: number | null) => value === null ? '未记录' : value.toLocaleString('zh-CN')
function costText(usage: Pick<Usage, 'estimatedCostUsd' | 'costStatus'>) {
  if (usage.costStatus === 'not_applicable') return '不适用'
  if (usage.estimatedCostUsd === null || usage.costStatus === 'unavailable') return '未记录'
  return `USD ${usage.estimatedCostUsd.toFixed(6)}${usage.costStatus === 'partial' ? '（部分记录）' : ''}`
}
function costExplanation(usage: Pick<Usage, 'costStatus'>) {
  return usage.costStatus === 'not_applicable' ? 'Mock 流程测试不使用真实模型费用。' : usage.costStatus === 'unavailable' ? '暂无可用于费用估算的记录，不代表费用为零。' : usage.costStatus === 'partial' ? '仅汇总已有费用记录，缺失部分未计入。估算不代表供应商实际扣款。' : '按用量与配置费率估算，不代表供应商实际扣款。'
}

export function AdminLayout() {
  const auth = useAuth(); const location = useLocation()
  if (!auth.session) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  if (auth.profileLoading) return <AppShell className="admin-app"><main className="admin-shell"><LoadingBlock label="正在确认账号权限…" /></main></AppShell>
  if (auth.profileError) return <AppShell className="admin-app"><main className="admin-shell"><ErrorState title="暂时无法读取账号权限" detail={auth.profileError} onRetry={() => void auth.refreshProfile()} /></main></AppShell>
  if (!auth.isAdmin) return <AppShell className="admin-app"><AppHeader trail={<Link to="/projects">镜场工作台</Link>} /><main className="admin-shell"><section className="state"><h1>没有后台管理权限</h1><p>如需管理团队，请联系工作区管理员。</p><Link className="button" to="/projects">返回工作台</Link></section></main></AppShell>
  return <AppShell className="admin-app"><AppHeader trail={<><Link to="/projects">团队工作区</Link><Icon name="chevron" /><span>后台管理</span></>} /><main className="admin-shell">
    <div className="admin-main-heading"><div><p className="eyebrow">工作区设置</p><h1>团队管理</h1><p className="admin-workspace-description">管理成员、角色与创作用量。</p></div><span className="admin-workspace-mark"><Icon name="users" />{auth.profile?.organizationName || '团队工作区'}</span></div>
    <nav className="admin-nav" aria-label="后台导航"><NavLink to="/admin/users">用户管理</NavLink><NavLink to="/admin/roles">角色权限</NavLink><NavLink to="/admin/runs">生成任务</NavLink><NavLink to="/admin/usage">用量记录</NavLink></nav><Outlet />
  </main></AppShell>
}

export function AdminUsersPage() {
  const api = useAdminApi(); const [page, setPage] = useState(0); const [query, setQuery] = useState(''); const [search, setSearch] = useState('')
  const load = useCallback(() => api.users(page, query), [api, page, query]); const users = useResource(load); const roles = useResource(api.roles)
  const [selected, setSelected] = useState<AdminUser | null>(null); const [roleIds, setRoleIds] = useState<number[]>([]); const [busy, setBusy] = useState<number | null>(null); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const changeStatus = async (user: AdminUser) => {
    if (busy !== null || user.self) return
    if (user.enabled && !window.confirm(`停用“${user.fullName}”后，该用户将无法登录。确定停用吗？`)) return
    setBusy(user.id); setError(''); setNotice('')
    try { await api.setUserStatus(user.id, !user.enabled); setNotice(user.enabled ? '用户已停用。' : '用户已启用。'); users.reload() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '账号状态更新失败。') } finally { setBusy(null) }
  }
  const saveRoles = async (event: FormEvent) => {
    event.preventDefault(); if (!selected || busy !== null) return; if (roleIds.length === 0) { setError('请至少保留一个用户角色。'); return }; setBusy(selected.id); setError(''); setNotice('')
    try { await api.setUserRoles(selected.id, roleIds); setSelected(null); setNotice('用户角色已更新。'); users.reload() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '角色更新失败。') } finally { setBusy(null) }
  }
  return <section className="admin-page"><PageHeading title="用户管理" description="管理当前团队的账号状态和角色。所有成员共用一个工作区。" /><form className="admin-filter" onSubmit={(event) => { event.preventDefault(); setQuery(search.trim()); setPage(0) }}><label className="admin-search"><span className="visually-hidden">搜索用户</span><Icon name="search" /><input type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索姓名或邮箱" /></label><Button type="submit" className="button--secondary button--small">搜索</Button>{users.data && <span className="admin-filter-count">{users.data.totalElements} 位团队成员</span>}</form><Notice error={error || roles.error} success={notice} />{selected && <form className="admin-editor" onSubmit={(event) => void saveRoles(event)}><h2>分配角色：{selected.fullName}</h2><fieldset className="permission-list" disabled={busy !== null}><legend>用户角色</legend>{roles.data?.map((role) => <label key={role.id}><input type="checkbox" checked={roleIds.includes(role.id)} disabled={selected.self && role.name === 'ADMIN'} onChange={(event) => setRoleIds((current) => event.target.checked ? [...current, role.id] : current.filter((id) => id !== role.id))} /><span>{roleName(role.name)}<small>{role.name}</small></span></label>)}</fieldset>{selected.self && <p className="form-hint">当前账号的管理员角色不能移除。</p>}<div className="form-actions"><Button type="submit" className="button--small" disabled={busy !== null || roles.loading}>{busy !== null ? '正在保存…' : '保存角色'}</Button><Button type="button" className="button--secondary button--small" disabled={busy !== null} onClick={() => setSelected(null)}>取消</Button></div></form>}{users.error ? <ErrorState detail={users.error} onRetry={users.reload} /> : !users.data ? <LoadingBlock label="正在读取用户…" /> : <><div className="admin-table-wrap" tabIndex={0} aria-label="可横向滚动的数据表" aria-busy={users.loading}><TableScrollHint /><table className="admin-table"><caption className="visually-hidden">团队用户</caption><thead><tr><th scope="col">用户</th><th scope="col">角色</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody>{users.data.items.map((user) => <tr key={user.id}><td><div className="admin-person"><span className="admin-person-avatar" aria-hidden="true">{user.fullName.trim().slice(0, 1) || user.email.slice(0, 1)}</span><div><strong>{user.fullName}</strong>{user.self && <span className="read-only">当前账号</span>}<span className="table-secondary">{user.email}</span></div></div></td><td><div className="admin-role-tags">{user.roles.length ? user.roles.map((role) => <span key={role.id} className={`admin-role-tag ${role.name === 'ADMIN' ? 'admin-role-tag--admin' : ''}`}>{roleName(role.name)}</span>) : '未分配角色'}</div></td><td><span className={`account-status ${user.enabled ? '' : 'account-status--disabled'}`}>{user.enabled ? '启用' : '停用'}</span><span className="table-secondary">{user.emailVerified ? '邮箱已验证' : '邮箱未验证'}</span></td><td><div className="table-actions"><Button className="button--secondary button--small" disabled={busy !== null || roles.loading || Boolean(roles.error)} onClick={() => { setSelected(user); setRoleIds(user.roles.map((role) => role.id)); setError(''); setNotice('') }}>分配角色</Button><Button className="button--secondary button--small" disabled={busy !== null || user.self} onClick={() => void changeStatus(user)}>{busy === user.id ? '正在更新…' : user.enabled ? '停用' : '启用'}</Button></div></td></tr>)}</tbody></table>{users.data.items.length === 0 && <p className="admin-empty">没有找到匹配的用户，请调整搜索条件。</p>}</div><Pagination value={users.data} loading={users.loading} onPage={setPage} /></>}</section>
}

export function AdminRolesPage() {
  const api = useAdminApi(); const roles = useResource(api.roles); const permissions = useResource(api.permissions)
  const [editing, setEditing] = useState<AdminRole | 'new' | null>(null); const [name, setName] = useState(''); const [selectedPermissions, setSelectedPermissions] = useState<string[]>([]); const [pending, setPending] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const edit = (role: AdminRole | 'new') => { setEditing(role); setName(role === 'new' ? '' : role.name); setSelectedPermissions(role === 'new' ? [] : role.permissions); setError(''); setNotice('') }
  const save = async (event: FormEvent) => {
    event.preventDefault(); if (!editing || pending) return; setPending(true); setError(''); setNotice('')
    try { await api.saveRole(editing === 'new' ? null : editing.id, { name: name.trim().toUpperCase(), permissions: selectedPermissions }); setEditing(null); setNotice('角色权限已保存。'); roles.reload() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '角色保存失败。') } finally { setPending(false) }
  }
  const label = (code: string) => permissions.data?.find((permission) => permission.code === code)?.label ?? code
  return <section className="admin-page"><PageHeading title="角色权限" description="ADMIN 和 USER 为只读系统角色；可创建自定义角色并分配权限。后台管理仅管理员可访问。" action={<Button className="button--small" disabled={pending} onClick={() => edit('new')}><Icon name="plus" />创建角色</Button>} /><Notice error={error || permissions.error} success={notice} />{editing && <form className="admin-editor" onSubmit={(event) => void save(event)}><h2>{editing === 'new' ? '创建角色' : '编辑角色'}</h2><label className="field">角色名称<input value={name} onChange={(event) => setName(event.target.value)} minLength={2} maxLength={32} required disabled={pending} /></label><fieldset className="permission-list" disabled={pending || permissions.loading}><legend>权限</legend>{permissions.data?.map((permission) => <label key={permission.code}><input type="checkbox" checked={selectedPermissions.includes(permission.code)} onChange={(event) => setSelectedPermissions((current) => event.target.checked ? [...current, permission.code] : current.filter((code) => code !== permission.code))} /><span>{permission.label}<small>{permission.description}</small></span></label>)}</fieldset><div className="form-actions"><Button type="submit" className="button--small" disabled={pending || permissions.loading || Boolean(permissions.error)}>{pending ? '正在保存…' : '保存角色权限'}</Button><Button type="button" className="button--secondary button--small" disabled={pending} onClick={() => setEditing(null)}>取消</Button></div></form>}{roles.error ? <ErrorState detail={roles.error} onRetry={roles.reload} /> : !roles.data ? <LoadingBlock label="正在读取角色…" /> : <div className="admin-table-wrap" tabIndex={0} aria-label="可横向滚动的数据表"><TableScrollHint /><table className="admin-table"><caption className="visually-hidden">工作区角色</caption><thead><tr><th scope="col">角色</th><th scope="col">权限</th><th scope="col">操作</th></tr></thead><tbody>{roles.data.map((role) => <tr key={role.id}><td><div className="admin-role-name"><Icon name="users" /><div><strong>{roleName(role.name)}</strong><span className="table-secondary">{role.name}</span></div></div></td><td><div className="admin-permission-tags">{role.permissions.length ? role.permissions.map((code) => <span key={code}>{label(code)}</span>) : '未分配权限'}</div></td><td>{role.system ? <span className="read-only">系统角色 · 只读</span> : <Button className="button--secondary button--small" disabled={pending} onClick={() => edit(role)}>编辑权限</Button>}</td></tr>)}</tbody></table></div>}</section>
}

export function AdminRunsPage() {
  const api = useAdminApi(); const [page, setPage] = useState(0); const [status, setStatus] = useState(''); const [taskType, setTaskType] = useState(''); const [selectedId, setSelectedId] = useState<string | null>(null)
  const load = useCallback(() => api.runs(page, status, taskType), [api, page, status, taskType]); const runs = useResource(load)
  return <section className="admin-page"><PageHeading title="生成任务" description="查看团队任务状态和已有用量记录。本页不会重新执行任务。" action={<Button className="button--secondary button--small" disabled={runs.loading} onClick={runs.reload}><Icon name="clock" />刷新任务</Button>} /><div className="admin-filter"><label>任务状态<select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}><option value="">全部状态</option>{[['queued', '排队中'], ['running', '进行中'], ['finalizing', '保存中'], ['completed', '已完成'], ['failed', '失败'], ['cancelled', '已取消'], ['interrupted', '已中断']].map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></label><label>任务类型<select value={taskType} onChange={(event) => { setTaskType(event.target.value); setPage(0) }}><option value="">全部类型</option><option value="generate_storyboard">生成分镜</option><option value="rewrite_storyboard_shot">单镜重做</option></select></label></div>{selectedId && <RunDetail id={selectedId} onClose={() => setSelectedId(null)} />}{runs.error ? <ErrorState detail={runs.error} onRetry={runs.reload} /> : !runs.data ? <LoadingBlock label="正在读取任务…" /> : <><div className="admin-table-wrap" tabIndex={0} aria-label="可横向滚动的数据表" aria-busy={runs.loading}><TableScrollHint /><table className="admin-table admin-runs-table"><caption className="visually-hidden">生成任务记录</caption><thead><tr><th scope="col">任务</th><th scope="col">项目 / 发起人</th><th scope="col">状态</th><th scope="col">费用估算</th><th scope="col">操作</th></tr></thead><tbody>{runs.data.items.map((run) => <tr key={run.id}><td><strong>{taskName(run.taskType)}</strong><span className="table-secondary">{dateTime(run.createdAt)}</span><span className="table-secondary">{modeName(run.usage.mode)}</span></td><td>{run.projectName || '未命名项目'}<span className="table-secondary">{run.userName || run.userEmail}</span></td><td><StatusBadge status={run.status.toUpperCase()} /></td><td>{costText(run.usage)}</td><td><Button className="button--secondary button--small" onClick={() => setSelectedId(run.id)}>查看详情</Button></td></tr>)}</tbody></table>{runs.data.items.length === 0 && <p className="admin-empty">当前筛选条件下没有任务记录。</p>}</div><Pagination value={runs.data} loading={runs.loading} onPage={setPage} /><p className="admin-footnote">费用按已有用量和配置费率估算，不是供应商实际扣款。Mock 和缺失费用记录不会计为真实费用零。</p></>}</section>
}

function RunDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const api = useAdminApi(); const load = useCallback(() => api.run(id), [api, id]); const detail = useResource(load)
  return <section className="admin-editor" aria-label="任务详情"><div className="admin-detail-heading"><h2>任务详情</h2><Button className="button--secondary button--small" onClick={onClose}>关闭详情</Button></div>{detail.error ? <Notice error={detail.error} /> : !detail.data ? <p className="muted">正在读取任务…</p> : <><dl className="admin-detail"><dt>任务 ID</dt><dd className="break-anywhere">{detail.data.id}</dd><dt>状态</dt><dd><StatusBadge status={detail.data.status.toUpperCase()} /></dd><dt>开始 / 结束</dt><dd>{dateTime(detail.data.startedAt)} / {dateTime(detail.data.endedAt)}</dd><dt>运行模式</dt><dd>{modeName(detail.data.usage.mode)}</dd><dt>模型请求</dt><dd>{detail.data.usage.providerRequests}</dd><dt>输入 / 输出 token</dt><dd>{count(detail.data.usage.inputTokens)} / {count(detail.data.usage.outputTokens)}</dd><dt>费用估算</dt><dd>{costText(detail.data.usage)}</dd></dl>{detail.data.errorCode && <Notice error={runErrorMessage(detail.data.errorCode) ?? `任务未完成（${detail.data.errorCode}）。`} />}<p className="form-hint">{costExplanation(detail.data.usage)}</p>{detail.data.resultStoryboardId && <Link className="account-link" to={`/projects/${detail.data.projectId}/scripts/${detail.data.scriptId}/storyboard?storyboard=${encodeURIComponent(detail.data.resultStoryboardId)}`}>打开已保存分镜</Link>}</>}</section>
}

export function AdminUsagePage() {
  const api = useAdminApi(); const summary = useResource(api.usage)
  return <section className="admin-page"><PageHeading title="用量记录" description="当前团队的任务与模型请求汇总，保留缺失记录的边界。" action={<Button className="button--secondary button--small" disabled={summary.loading} onClick={summary.reload}><Icon name="clock" />刷新用量</Button>} />{summary.error ? <ErrorState detail={summary.error} onRetry={summary.reload} /> : !summary.data ? <LoadingBlock label="正在读取用量…" /> : <UsageContent summary={summary.data} />}</section>
}
function UsageContent({ summary }: { summary: UsageSummary }) {
  return <>
    <section className="usage-overview"><div><h3>已有记录的费用估算</h3><p className="usage-amount">{costText(summary)}</p></div><p className="usage-explanation">{costExplanation(summary)}</p></section>
    <div className="admin-table-wrap admin-usage-wrap"><table className="admin-table admin-usage-table"><caption className="visually-hidden">任务和请求</caption><thead><tr><th scope="col">运行模式</th><th scope="col">任务数</th></tr></thead><tbody>
      <tr><th scope="row">真实模型<span className="table-secondary">已记录运行模式的真实模型任务</span></th><td>{count(summary.liveRuns)}</td></tr>
      <tr><th scope="row">Mock 流程测试<span className="table-secondary">不产生真实模型费用</span></th><td>{count(summary.mockRuns)}</td></tr>
      <tr><th scope="row">模式未记录<span className="table-secondary">保留原始记录，不归为零费用任务</span></th><td>{count(summary.unknownRuns)}</td></tr>
    </tbody><tfoot><tr><th scope="row">任务总数</th><td>{count(summary.totalRuns)}</td></tr></tfoot></table></div>
    <div className="admin-usage-totals"><span>模型请求数</span><strong>{count(summary.providerRequests)}</strong></div>
    <section className="admin-token-summary"><h3>已有 token 记录</h3><dl>{[['输入 token', summary.inputTokens], ['输出 token', summary.outputTokens], ['合计 token', summary.totalTokens]].map(([label, value]) => <div key={label as string}><dt>{label}</dt><dd>{count(value as number | null)}</dd></div>)}</dl><p>缺失记录显示为“未记录”，不当作真实用量为零。</p></section>
  </>
}
