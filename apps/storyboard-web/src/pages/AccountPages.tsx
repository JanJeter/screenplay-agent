import { useState, type FormEvent } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router-dom'
import { publicAuthRequest } from '../api/http'
import { useAuth } from '../auth'
import { Button, Icon } from '../components'
import { AccountInput, AccountLayout, AccountPassword } from './AccountLayout'

type Message = { message: string }
function Failure({ message }: { message: string }) { return message ? <p className="form-error" role="alert">{message}</p> : null }
function Success({ message }: { message: string }) { return <div className="account-success" role="status">{message}</div> }
function useSubmission() {
  const [pending, setPending] = useState(false); const [error, setError] = useState(''); const [message, setMessage] = useState('')
  const submit = async (path: string, body: unknown) => {
    if (pending) return false
    const values = body as { password?: string; newPassword?: string }; const password = values.password ?? values.newPassword
    if (password && new TextEncoder().encode(password).length > 72) { setError('密码最多占 72 字节，请减少汉字或特殊字符。'); return false }
    setPending(true); setError('')
    try { const result = await publicAuthRequest<Message>(path, body); setMessage(result.message); return true }
    catch (reason) { setError(reason instanceof Error ? reason.message : '请求未完成，请稍后重试。'); return false }
    finally { setPending(false) }
  }
  return { pending, error, message, submit }
}
const normalizedEmail = (form: FormData) => String(form.get('email') ?? '').trim().toLowerCase()

export function RegisterPage() {
  const auth = useAuth(); const action = useSubmission(); const [email, setEmail] = useState('')
  if (auth.session) return <Navigate to="/projects" replace />
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = new FormData(event.currentTarget); const address = normalizedEmail(form); setEmail(address)
    await action.submit('register', { fullName: String(form.get('fullName') ?? '').trim(), email: address, password: String(form.get('password') ?? '') })
  }
  return <AccountLayout title="创建账号" description="开始你的下一场戏。验证邮箱后即可进入团队工作区。">
    {action.message ? <div className="account-stack"><Success message={action.message} /><p className="muted">请打开验证邮件中的链接。需要新的链接时，可重新发送。</p><Link className="account-link" to={`/resend-verification?email=${encodeURIComponent(email)}`}>重新发送验证邮件</Link></div> : <form className="account-form" onSubmit={(event) => void submit(event)} aria-busy={action.pending}>
      <AccountInput label="姓名" leading="users" name="fullName" autoComplete="name" placeholder="让团队认识你" maxLength={50} required />
      <AccountInput label="邮箱" name="email" type="email" autoComplete="email" placeholder="你的邮箱地址" maxLength={80} required />
      <AccountPassword label="密码" name="password" autoComplete="new-password" placeholder="设置登录密码" minLength={8} maxLength={64} required aria-describedby="register-password-hint" />
      <p id="register-password-hint" className="form-hint">使用 8–64 个字符。</p><Failure message={action.error} />
      <Button className="account-submit" type="submit" disabled={action.pending}>{action.pending ? '正在创建…' : '注册并发送验证邮件'}<Icon name="arrow" /></Button>
    </form>}
    <p className="account-footer">已有账号？<Link to="/login">返回登录</Link></p>
  </AccountLayout>
}

export function VerifyEmailPage() {
  const [params, setParams] = useSearchParams(); const [token] = useState(() => params.get('token') ?? ''); const action = useSubmission()
  const verify = async () => { if (await action.submit('verify-email', { token })) setParams({}, { replace: true }) }
  return <AccountLayout title="验证邮箱" description="完成验证后，使用邮箱和密码登录。">
    <div className="account-verification-mark" aria-hidden="true"><Icon name="mail" /></div>
    <div className="account-stack">{action.message ? <><Success message={action.message} /><Link className="button account-submit" to="/login">前往登录<Icon name="arrow" /></Link></> : token ? <><Button className="account-submit" onClick={() => void verify()} disabled={action.pending}>{action.pending ? '正在验证…' : '验证邮箱'}<Icon name="arrow" /></Button><Failure message={action.error} /><Link className="account-link" to="/resend-verification">重新发送验证邮件</Link></> : <><Failure message="验证链接不完整，请重新申请验证邮件。" /><Link className="account-link" to="/resend-verification">重新发送验证邮件</Link></>}</div>
    <p className="account-footer"><Link to="/login">返回登录</Link></p>
  </AccountLayout>
}

export function EmailRequestPage({ kind }: { kind: 'resend-verification' | 'forgot-password' }) {
  const [params] = useSearchParams(); const action = useSubmission(); const verify = kind === 'resend-verification'
  const submit = async (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); await action.submit(kind, { email: normalizedEmail(new FormData(event.currentTarget)) }) }
  return <AccountLayout title={verify ? '重新发送验证邮件' : '找回密码'} description={verify ? '输入注册邮箱，我们会发送新的验证链接。' : '输入账号邮箱，通过邮件设置新密码。'}>
    {action.message ? <div className="account-stack"><Success message={action.message} /><p className="muted">请查看邮件中的链接，链接只能使用一次。</p></div> : <form className="account-form" onSubmit={(event) => void submit(event)} aria-busy={action.pending}>
      <AccountInput label="邮箱" name="email" type="email" autoComplete="email" placeholder="你的邮箱地址" defaultValue={params.get('email') ?? ''} maxLength={80} required />
      <Failure message={action.error} /><Button className="account-submit" type="submit" disabled={action.pending}>{action.pending ? '正在发送…' : verify ? '发送验证邮件' : '发送重置邮件'}<Icon name="arrow" /></Button>
    </form>}
    {!verify && <div className="account-quiet-note"><Icon name="lock" /><p>密码重置链接只可使用一次。<br />已有的剧本和分镜不会受到影响。</p></div>}
    <p className="account-footer"><Link to="/login"><Icon name="arrowLeft" />返回登录</Link></p>
  </AccountLayout>
}

export function ResetPasswordPage() {
  const auth = useAuth(); const [params, setParams] = useSearchParams(); const [token] = useState(() => params.get('token') ?? ''); const action = useSubmission(); const [confirmationError, setConfirmationError] = useState('')
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = new FormData(event.currentTarget); const newPassword = String(form.get('password') ?? ''); setConfirmationError('')
    if (newPassword !== String(form.get('confirmPassword') ?? '')) { setConfirmationError('两次输入的密码不一致。'); return }
    if (await action.submit('reset-password', { token, newPassword })) { auth.expire(); setParams({}, { replace: true }) }
  }
  return <AccountLayout title="设置新密码" description="重置后需要使用新密码重新登录。">
    {action.message ? <div className="account-stack"><Success message={action.message} /><Link className="button account-submit" to="/login">使用新密码登录<Icon name="arrow" /></Link></div> : !token ? <div className="account-stack"><Failure message="重置链接不完整，请重新申请重置邮件。" /><Link className="account-link" to="/forgot-password">重新申请重置邮件</Link></div> : <form className="account-form" onSubmit={(event) => void submit(event)} aria-busy={action.pending}>
      <AccountPassword label="新密码" name="password" autoComplete="new-password" minLength={8} maxLength={64} required aria-describedby="reset-password-hint" />
      <AccountPassword label="确认新密码" name="confirmPassword" autoComplete="new-password" minLength={8} maxLength={64} required />
      <p id="reset-password-hint" className="form-hint">使用 8–64 个字符。</p><Failure message={confirmationError || action.error} />
      <Button className="account-submit" type="submit" disabled={action.pending}>{action.pending ? '正在重置…' : '重置密码'}<Icon name="arrow" /></Button>{action.error && <Link className="account-link" to="/forgot-password">重新申请重置邮件</Link>}
    </form>}
    <p className="account-footer"><Link to="/login">返回登录</Link></p>
  </AccountLayout>
}
