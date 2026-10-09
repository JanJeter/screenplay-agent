import { FormEvent, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Button, Icon } from '../components'
import { useAuth } from '../auth'
import { ApiError } from '../api/contract'
import { AccountInput, AccountLayout, AccountPassword } from './AccountLayout'

export function LoginPage() {
  const auth = useAuth(); const navigate = useNavigate(); const location = useLocation()
  const [email, setEmail] = useState(''); const [password, setPassword] = useState(''); const [error, setError] = useState(''); const [errorCode, setErrorCode] = useState(''); const [pending, setPending] = useState(false)
  if (auth.session) return <Navigate to="/projects" replace />
  const requestedTarget = (location.state as { from?: string } | null)?.from ?? '/projects'
  const target = requestedTarget.startsWith('/') && !requestedTarget.startsWith('//') ? requestedTarget : '/projects'
  async function submit(event: FormEvent) { event.preventDefault(); setPending(true); setError(''); setErrorCode(''); try { await auth.login(email, password); navigate(target, { replace: true }) } catch (reason) { setError(reason instanceof Error ? reason.message : '登录未完成。'); setErrorCode(reason instanceof ApiError ? reason.code ?? '' : '') } finally { setPending(false) } }
  return <AccountLayout title="登录工作台" description="回到你的故事，继续打磨下一个镜头。">
    <form className="account-form" onSubmit={submit} aria-busy={pending}>
      <AccountInput label="邮箱" type="email" autoComplete="email" placeholder="你的邮箱地址" maxLength={80} value={email} onChange={(event) => setEmail(event.target.value)} required />
      <AccountPassword label="密码" autoComplete="current-password" placeholder="输入密码" value={password} onChange={(event) => setPassword(event.target.value)} required action={<Link className="account-forgot" to="/forgot-password">忘记密码？</Link>} />
      {error && <p className="form-error" role="alert">{error}</p>}
      {errorCode === 'email_not_verified' && <Link className="account-link" to={`/resend-verification?email=${encodeURIComponent(email.trim().toLowerCase())}`}>重新发送验证邮件</Link>}
      <Button className="account-submit" type="submit" disabled={pending}>{pending ? '正在登录…' : '登录'}<Icon name="arrow" /></Button>
    </form>
    <p className="account-footer">还没有账号？<Link to="/register">创建账号</Link></p>
    {auth.isFixture && <div className="fixture-entry"><span>开发模式</span><Button className="button--quiet" onClick={() => { auth.enterFixture(); navigate('/projects') }}>进入 fixtures 演示</Button></div>}
  </AccountLayout>
}
