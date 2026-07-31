import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { getOrgBySlug, orgMemberLogin } from '../api/client'

type Mode = 'password' | 'apikey'

export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [mode, setMode] = useState<Mode>('password')
  const [slug, setSlug] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handlePasswordSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(''); setLoading(true)
    try {
      const { data } = await orgMemberLogin(slug.trim(), email.trim(), password)
      login({
        role: data.role === 'ORG_VIEWER' ? 'org_viewer' : 'org',
        name: data.displayName || data.email,
        apiKey: data.token,
        orgId: data.orgId,
        slug: data.orgSlug,
        memberId: data.memberId,
        email: data.email,
        memberRole: data.role,
      })
      navigate('/dashboard')
    } catch (err: any) {
      setError(err?.response?.data?.error || err?.response?.data?.message ||
        'Invalid organisation, email, or password.')
    } finally {
      setLoading(false)
    }
  }

  const handleApiKeySubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(''); setLoading(true)
    try {
      const res = await getOrgBySlug(slug)
      const org = res.data
      if (org.status !== 'ACTIVE') { setError('Organization is not active.'); return }
      login({ role: 'org', name: org.name, apiKey, orgId: org.id, slug: org.slug })
      navigate('/dashboard')
    } catch {
      setError('Invalid organization slug or API key.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-logo">
          <h1>Invoice<span>Direct</span></h1>
          <p>PEPPOL-compliant invoice delivery platform</p>
        </div>

        <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
          <button type="button"
            className={`btn ${mode === 'password' ? 'btn-primary' : 'btn-secondary'}`}
            style={{ flex: 1, justifyContent: 'center' }}
            onClick={() => { setMode('password'); setError('') }}>
            Sign in
          </button>
          <button type="button"
            className={`btn ${mode === 'apikey' ? 'btn-primary' : 'btn-secondary'}`}
            style={{ flex: 1, justifyContent: 'center' }}
            onClick={() => { setMode('apikey'); setError('') }}>
            API key
          </button>
        </div>

        {error && <div className="alert alert-error">{error}</div>}

        {mode === 'password' ? (
          <form onSubmit={handlePasswordSubmit}>
            <div className="form-group">
              <label>Organisation Slug</label>
              <input value={slug} onChange={e => setSlug(e.target.value)}
                placeholder="e.g. acme-holdings" required />
            </div>
            <div className="form-group">
              <label>Email</label>
              <input type="email" value={email} onChange={e => setEmail(e.target.value)}
                placeholder="you@yourcompany.co.zw" required />
            </div>
            <div className="form-group">
              <label>Password</label>
              <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                placeholder="Your password" required />
            </div>
            <button className="btn btn-primary"
              style={{ width: '100%', justifyContent: 'center' }} disabled={loading}>
              {loading ? <span className="spinner" /> : 'Sign In'}
            </button>
          </form>
        ) : (
          <form onSubmit={handleApiKeySubmit}>
            <div className="form-group">
              <label>Organisation Slug</label>
              <input value={slug} onChange={e => setSlug(e.target.value)}
                placeholder="e.g. acme-holdings" required />
            </div>
            <div className="form-group">
              <label>API Key</label>
              <input type="password" value={apiKey} onChange={e => setApiKey(e.target.value)}
                placeholder="Organisation API key (for ERP integration)" required />
            </div>
            <button className="btn btn-primary"
              style={{ width: '100%', justifyContent: 'center' }} disabled={loading}>
              {loading ? <span className="spinner" /> : 'Sign In with API key'}
            </button>
          </form>
        )}

        <div className="auth-divider">Don't have an account?</div>
        <Link to="/register" className="btn btn-secondary"
          style={{ width: '100%', justifyContent: 'center' }}>
          Register your organisation
        </Link>
        <div style={{ marginTop: 20, textAlign: 'center' }}>
          <Link to="/admin/login"
            style={{ fontSize: 13, color: '#94a3b8', textDecoration: 'none' }}>
            Platform admin? Sign in here →
          </Link>
        </div>
      </div>
    </div>
  )
}
