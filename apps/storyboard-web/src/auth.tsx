import { createContext, useCallback, useContext, useMemo, useState, type PropsWithChildren } from 'react'
import type { Session } from './types'

type AuthContextValue = { session: Session | null; login(email: string, password: string): Promise<void>; enterFixture(): void; logout(): Promise<void>; updateSession(session: Session): void; expire(): void; isFixture: boolean }
const key = 'storyboard-web.session'
const isFixture = import.meta.env.VITE_STORYBOARD_DATA_SOURCE === 'fixtures'
const apiBase = import.meta.env.VITE_JAVA_API_BASE ?? ''
const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<Session | null>(() => { const stored = sessionStorage.getItem(key); return stored ? JSON.parse(stored) as Session : null })
  const persist = useCallback((value: Session | null) => { setSession(value); if (value) sessionStorage.setItem(key, JSON.stringify(value)); else sessionStorage.removeItem(key) }, [])
  const login = useCallback(async (email: string, password: string) => {
    const response = await fetch(`${apiBase}/api/v1/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password }) })
    if (!response.ok) throw new Error('邮箱或密码不正确。')
    persist(await response.json() as Session)
  }, [persist])
  const logout = useCallback(async () => { if (session) await fetch(`${apiBase}/api/v1/auth/logout`, { method: 'POST', headers: { Authorization: `Bearer ${session.accessToken}` } }).catch(() => undefined); persist(null) }, [persist, session])
  const value = useMemo(() => ({ session, login, logout, updateSession: persist, expire: () => persist(null), enterFixture: () => persist({ accessToken: 'fixture-session', refreshToken: 'fixture-session' }), isFixture }), [login, logout, persist, session])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() { const value = useContext(AuthContext); if (!value) throw new Error('AuthProvider is required'); return value }
