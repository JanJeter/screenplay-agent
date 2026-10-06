import { FormEvent, useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Button } from '../components'
import { useAuth } from '../auth'

export function LoginPage() {
  const auth = useAuth(); const navigate = useNavigate(); const location = useLocation()
  const [email, setEmail] = useState(''); const [password, setPassword] = useState(''); const [error, setError] = useState(''); const [pending, setPending] = useState(false)
  if (auth.session) return <Navigate to="/projects" replace />
  const target = (location.state as { from?: string } | null)?.from ?? '/projects'
  async function submit(event: FormEvent) { event.preventDefault(); setPending(true); setError(''); try { await auth.login(email, password); navigate(target, { replace: true }) } catch (reason) { setError(reason instanceof Error ? reason.message : '登录未完成。') } finally { setPending(false) } }
  return <main className="login-shell"><section className="login-intro"><p className="wordmark">镜场</p><h1>把一场戏，整理成可继续打磨的镜头。</h1><p>从项目、剧本版本到分镜草稿，所有内容都在同一个工作区完成。</p></section><section className="login-card"><h2>登录工作台</h2><p className="muted">使用现有账号继续。</p><form onSubmit={submit}><label>邮箱<input type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></label><label>密码<input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required /></label>{error && <p className="form-error" role="alert">{error}</p>}<Button type="submit" disabled={pending}>{pending ? '正在登录…' : '登录'}</Button></form>{auth.isFixture && <div className="fixture-entry"><span>开发模式</span><Button className="button--quiet" onClick={() => { auth.enterFixture(); navigate('/projects') }}>进入 fixtures 演示</Button></div>}</section></main>
}
