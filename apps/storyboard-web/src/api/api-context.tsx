import { createContext, useContext, useEffect, useMemo, useRef, type PropsWithChildren } from 'react'
import { useAuth } from '../auth'
import { fixtureApi } from './fixtures-api'
import { createJavaApi } from './java-api'
import type { ScreenplayApi } from './contract'

const ApiContext = createContext<ScreenplayApi | null>(null)
export function ApiProvider({ children }: PropsWithChildren) {
  const auth = useAuth()
  // The API client must remain stable when refresh updates React session state:
  // remounting/reloading consumers at that point can discard a local edit.
  // Refs also make the retry immediately use the newly refreshed token, before
  // React has had a chance to render the updated session.
  const sessionRef = useRef(auth.session)
  const updateSessionRef = useRef(auth.updateSession)
  const expireRef = useRef(auth.expire)
  useEffect(() => { sessionRef.current = auth.session }, [auth.session])
  useEffect(() => { updateSessionRef.current = auth.updateSession; expireRef.current = auth.expire }, [auth.expire, auth.updateSession])
  const api = useMemo(() => auth.isFixture ? fixtureApi : createJavaApi({
    request: auth.request,
    getTokens: () => sessionRef.current,
    updateTokens: (tokens) => { sessionRef.current = tokens; updateSessionRef.current(tokens) },
    onExpired: () => { sessionRef.current = null; expireRef.current() }
  }), [auth.isFixture, auth.request])
  return <ApiContext.Provider value={api}>{children}</ApiContext.Provider>
}
export function useApi() { const value = useContext(ApiContext); if (!value) throw new Error('ApiProvider is required'); return value }
