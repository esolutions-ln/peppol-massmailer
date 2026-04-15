import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { getOrgBySlug } from '../api/client'

export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [slug, setSlug] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
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

        {error && <div className="alert alert-error">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Organization Slug</label>
            <input value={slug} onChange={e => setSlug(e.target.value)} placeholder="e.g. acme-holdings" required />
          </div>
          <div className="form-group">
            <label>API Key</label>
            <input type="password" value={apiKey} onChange={e => setApiKey(e.target.value)} placeholder="Your organization API key" required />
          </div>
          <button className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }} disabled={loading}>
            {loading ? <span className="spinner" /> : 'Sign In'}
          </button>
        </form>

        <div className="auth-divider">Don't have an account?</div>
        <Link to="/register" className="btn btn-secondary" style={{ width: '100%', justifyContent: 'center' }}>
          Register your organization
        </Link>
        <div style={{ marginTop: 20, textAlign: 'center' }}>
          <Link to="/admin/login" style={{ fontSize: 13, color: '#94a3b8', textDecoration: 'none' }}>
            Platform admin? Sign in here →
          </Link>
        </div>
      </div>
    </div>
  )
}
