import { createContext, useContext, useState, ReactNode } from 'react'
import type { Session } from '../types'

interface AuthContextValue {
  session: Session | null
  login: (data: Session) => void
  logout: () => void
  isAdmin: boolean
  isOrg: boolean
  isOrgAdmin: boolean
  isOrgViewer: boolean
  canManageMembers: boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => {
    try { return JSON.parse(localStorage.getItem('id_session') ?? 'null') } catch { return null }
  })

  const login = (data: Session) => {
    localStorage.setItem('id_session', JSON.stringify(data))
    setSession(data)
  }

  const logout = () => {
    localStorage.removeItem('id_session')
    setSession(null)
  }

  const isAdmin = session?.role === 'admin'
  const isOrgAdmin = session?.role === 'org' && (session.memberRole == null || session.memberRole === 'ORG_ADMIN')
  const isOrgViewer = session?.role === 'org_viewer'
  const isOrg = isOrgAdmin || isOrgViewer
  // Member management requires the full ORG role (legacy API key or ORG_ADMIN member).
  const canManageMembers = isOrgAdmin

  return (
    <AuthContext.Provider value={{ session, login, logout, isAdmin, isOrg, isOrgAdmin, isOrgViewer, canManageMembers }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
