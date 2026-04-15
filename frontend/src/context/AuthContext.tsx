import { createContext, useContext, useState, ReactNode } from 'react'
import type { Session } from '../types'

interface AuthContextValue {
  session: Session | null
  login: (data: Session) => void
  logout: () => void
  isAdmin: boolean
  isOrg: boolean
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

  return (
    <AuthContext.Provider value={{ session, login, logout, isAdmin: session?.role === 'admin', isOrg: session?.role === 'org' }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
