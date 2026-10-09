import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type PropsWithChildren } from 'react'
import { createAuthorizedRequest, publicAuthRequest, type AuthorizedRequest } from './api/http'
import type { Session, UserProfile } from './types'

type AuthContextValue = {
  session: Session | null; profile: UserProfile | null; profileLoading: boolean; profileError: string; isAdmin: boolean
  login(email: string, password: string): Promise<void>; enterFixture(): void; logout(): Promise<void>
  updateSession(session: Session): void; expire(): void; refreshProfile(): Promise<void>; request: AuthorizedRequest; isFixture: boolean
}
const key = 'storyboard-web.session'
const isFixture = import.meta.env.VITE_STORYBOARD_DATA_SOURCE === 'fixtures'
const AuthContext = createContext<AuthContextValue | null>(null)
const fixtureProfile: UserProfile = { id: 0, email: 'fixture@example.com', fullName: '演示账号', roles: ['USER'], permissions: [], organizationId: 0, organizationName: '示例工作区', emailVerified: true, enabled: true }
function storedSession(): Session | null {
  try { const value = JSON.parse(sessionStorage.getItem(key) ?? 'null') as Session | null; return value?.accessToken && value.refreshToken ? value : null }
  catch { sessionStorage.removeItem(key); return null }
}
export function AuthProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<Session | null>(storedSession)
  const sessionRef = useRef(session); const profileVersion = useRef(0)
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [profileLoading, setProfileLoading] = useState(Boolean(session)); const [profileError, setProfileError] = useState('')
  const persist = useCallback((value: Session | null) => {
    sessionRef.current = value; setSession(value)
    if (value) sessionStorage.setItem(key, JSON.stringify(value))
    else { sessionStorage.removeItem(key); profileVersion.current += 1; setProfile(null); setProfileError(''); setProfileLoading(false) }
  }, [])
  const expire = useCallback(() => persist(null), [persist])
  const request = useMemo(() => createAuthorizedRequest({ getSession: () => sessionRef.current, updateSession: persist, onExpired: expire }), [expire, persist])
  const refreshProfile = useCallback(async () => {
    if (!sessionRef.current) return
    const version = ++profileVersion.current; setProfileLoading(true); setProfileError('')
    try {
      const next = isFixture ? fixtureProfile : await request<UserProfile>('/auth/me')
      if (version === profileVersion.current) setProfile(next)
    } catch (error) {
      if (version === profileVersion.current) { setProfile(null); setProfileError(error instanceof Error ? error.message : '账号资料暂时无法加载。') }
    } finally { if (version === profileVersion.current) setProfileLoading(false) }
  }, [request])
  const signedIn = Boolean(session)
  useEffect(() => { if (signedIn) void refreshProfile() }, [refreshProfile, signedIn])
  const login = useCallback(async (email: string, password: string) => {
    const tokens = await publicAuthRequest<Session>('login', { email: email.trim().toLowerCase(), password })
    setProfile(null); setProfileLoading(true); persist(tokens)
  }, [persist])
  const logout = useCallback(async () => {
    const event = new Event('storyboard:before-logout', { cancelable: true }); window.dispatchEvent(event); if (event.defaultPrevented) return
    try { if (sessionRef.current && !isFixture) await request<void>('/auth/logout', { method: 'POST', expectJson: false, retry: false }) } catch { /* Always remove the local session. */ }
    persist(null)
  }, [persist, request])
  const enterFixture = useCallback(() => persist({ accessToken: 'fixture-session', refreshToken: 'fixture-session' }), [persist])
  const isAdmin = Boolean(profile?.roles.some((role) => role === 'ADMIN' || role === 'ROLE_ADMIN'))
  const value = useMemo(() => ({ session, profile, profileLoading, profileError, isAdmin, login, logout, updateSession: persist, expire, refreshProfile, request, enterFixture, isFixture }), [session, profile, profileLoading, profileError, isAdmin, login, logout, persist, expire, refreshProfile, request, enterFixture])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
export function useAuth() { const value = useContext(AuthContext); if (!value) throw new Error('AuthProvider is required'); return value }
