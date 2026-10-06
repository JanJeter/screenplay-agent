import { type ButtonHTMLAttributes, type ReactNode } from 'react'
import type { RunStatus, Shot } from './types'

const statusText: Record<RunStatus, string> = { QUEUED: '排队中', RUNNING: '正在生成分镜', FINALIZING: '正在保存结果', CANCELLING: '正在停止', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消', INTERRUPTED: '已中断' }
export function StatusBadge({ status }: { status: RunStatus | string }) { return <span className={`status status--${status.toLowerCase()}`}>{statusText[status as RunStatus] ?? status}</span> }
export function AppHeader({ trail, actions }: { trail: ReactNode; actions?: ReactNode }) { return <header className="app-header"><div className="trail">{trail}</div><div className="header-actions">{actions}</div></header> }
export function Button({ className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement>) { return <button className={`button ${className}`} {...props} /> }
export function LoadingBlock({ label = '正在读取工作台…' }: { label?: string }) { return <section className="state state--loading" aria-live="polite"><div className="skeleton skeleton--title" /><div className="skeleton" /><div className="skeleton" /><span>{label}</span></section> }
export function ErrorState({ title = '无法加载此内容', detail, onRetry }: { title?: string; detail: string; onRetry?: () => void }) { return <section className="state state--error" role="alert"><p className="state-mark">!</p><h2>{title}</h2><p>{detail}</p>{onRetry && <Button onClick={onRetry}>重新加载</Button>}</section> }
export function EmptyState({ title, detail, action }: { title: string; detail: string; action: ReactNode }) { return <section className="state"><p className="state-mark">+</p><h2>{title}</h2><p>{detail}</p>{action}</section> }
export function ShotCard({ shot, selected, onSelect }: { shot: Shot; selected: boolean; onSelect: () => void }) { return <button className={`shot-card ${selected ? 'shot-card--selected' : ''}`} onClick={onSelect} aria-pressed={selected}><span className="shot-number">镜头 {shot.orderIndex + 1}</span><span className="shot-meta">{labelShotSize(shot.shotSize)} · {labelMovement(shot.cameraMovement)} · {shot.durationSeconds} 秒</span><span className="shot-description">{shot.visualDescription}</span></button> }
export function labelShotSize(value: Shot['shotSize']) { return ({ ESTABLISHING: '建立镜头', WIDE: '全景', MEDIUM: '中景', CLOSE_UP: '近景', EXTREME_CLOSE_UP: '特写' })[value] }
export function labelMovement(value: Shot['cameraMovement']) { return ({ STATIC: '固定', PAN: '摇镜', TILT: '俯仰', DOLLY_IN: '推镜', DOLLY_OUT: '拉镜', TRACK: '跟拍', HANDHELD: '手持' })[value] }
