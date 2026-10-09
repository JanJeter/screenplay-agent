import { useId, useState, type InputHTMLAttributes, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { EnvironmentNotice, Icon } from '../components'
import '../accounts.css'

export function AccountLayout({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return <main className="account-screen">
    <section className="account-story-side">
      <Link className="account-brand" to="/login" aria-label="镜场工作台"><Icon name="logo" /><strong>镜场</strong><span>JINGCHANG</span></Link>
      <div className="account-story-copy"><p className="account-kicker">为故事，找到恰好的画面。</p><h1>一场戏，<br />从一个镜头开始。</h1><p className="account-story-description">把剧本中的情绪、动作与留白，<br />整理成可以继续打磨的分镜。</p></div>
      <div className="account-viewfinder" aria-hidden="true">
        <div className="account-viewfinder-top"><span>从文字，到画面</span><span>16 : 9</span></div>
        <div className="account-frame-composition"><i className="account-frame-corner corner-tl" /><i className="account-frame-corner corner-tr" /><i className="account-frame-corner corner-bl" /><i className="account-frame-corner corner-br" /><div className="account-composition-lines"><i /><i /><i /></div><div className="account-focus-mark"><span /><span /></div><blockquote>一封未拆开的信，<br />让沉默有了形状。</blockquote><span className="account-frame-note">在镜头之间，继续你的故事。</span></div>
        <div className="account-viewfinder-bottom"><span><i />故事还在继续</span><span>文字 → 场景 → 分镜</span></div>
      </div>
      <div className="account-story-footer"><span>文本为起点，创作为目的。</span><span>镜场 · 团队工作区</span></div>
    </section>
    <section className="account-form-side">
      <div className="account-form-top"><Link to="/login" className="account-mobile-brand"><Icon name="logo" /><strong>镜场</strong></Link><EnvironmentNotice /></div>
      <div className="account-form-container"><div className="account-form-heading"><span className="account-small-rule" /><h2>{title}</h2><p>{description}</p></div><div className="account-content">{children}</div></div>
      <div className="account-form-bottom"><span>让创作保持专注。</span><span>镜场 · 团队工作区</span></div>
    </section>
  </main>
}

type AccountInputProps = InputHTMLAttributes<HTMLInputElement> & { label: string; leading?: 'mail' | 'users'; action?: ReactNode }
export function AccountInput({ label, leading = 'mail', action, id, ...props }: AccountInputProps) {
  const generatedId = useId(); const inputId = id ?? generatedId
  return <div className="account-field"><div className="account-label-row"><label htmlFor={inputId}>{label}</label>{action}</div><div className="account-input-wrap"><Icon name={leading} /><input {...props} id={inputId} /></div></div>
}

export function AccountPassword({ label, action, id, ...props }: Omit<AccountInputProps, 'leading'>) {
  const generatedId = useId(); const inputId = id ?? generatedId; const [visible, setVisible] = useState(false)
  return <div className="account-field"><div className="account-label-row"><label htmlFor={inputId}>{label}</label>{action}</div><div className="account-input-wrap"><Icon name="lock" /><input {...props} id={inputId} type={visible ? 'text' : 'password'} /><button type="button" className="account-visibility" aria-label={visible ? '隐藏输入内容' : '显示输入内容'} aria-controls={inputId} aria-pressed={visible} onClick={() => setVisible((value) => !value)}><Icon name="eye" /></button></div></div>
}
