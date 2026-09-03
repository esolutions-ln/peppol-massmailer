import { useState, useEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { validateAdminInvitationToken, completeAdminInvitation } from '../../api/client'
import type { AdminInvitationTokenValidation } from '../../types'

type PageState = 'loading' | 'error' | 'form' | 'success'

export default function AdminInvitePage() {
  const { token } = useParams<{ token: string }>()
  const navigate = useNavigate()
  const [state, setState] = useState<PageState>('loading')
  const [tokenData, setTokenData] = useState<AdminInvitationTokenValidation | null>(null)
  const [errorMessage, setErrorMessage] = useState('')
  const [inlineError, setInlineError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [createdUsername, setCreatedUsername] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')

  useEffect(() => {
    if (!token) {
      setErrorMessage('Invalid invitation link.')
      setState('error')
      return
    }
    validateAdminInvitationToken(token)
      .then(data => {
        setTokenData(data)
        setUsername(data.email.split('@')[0])
        setState('form')
      })
      .catch((err: any) => {
        const status = err.response?.status
        const msg = err.response?.data?.error || err.response?.data?.message
        if (status === 410) {
          setErrorMessage(msg ?? 'This invitation link has already been used or has expired.')
        } else if (status === 404) {
          setErrorMessage(msg ?? 'This invitation link is invalid.')
        } else {
          setErrorMessage(msg ?? 'Unable to validate invitation link. Please try again later.')
        }
        setState('error')
      })
  }, [token])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!token) return
    setInlineError('')
    if (password.length < 8) {
      setInlineError('Password must be at least 8 characters.')
      return
    }
    if (password !== passwordConfirm) {
      setInlineError('Passwords do not match.')
      return
    }
    setSubmitting(true)
    try {
      const result = await completeAdminInvitation(token, { username, password })
      setCreatedUsername(result.username)
      setState('success')
    } catch (err: any) {
      const status = err.response?.status
      const msg = err.response?.data?.error || err.response?.data?.message
      if (status === 400 || status === 409) {
        setInlineError(msg ?? 'Invalid submission. Please check your details.')
      } else {
        setInlineError(msg ?? 'An unexpected error occurred. Please try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (state === 'loading') {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <div className="auth-logo">
            <h1>Invoice<span>Direct</span></h1>
            <p>Platform Admin Invitation</p>
          </div>
          <div style={{ display: 'flex', justifyContent: 'center', padding: '24px 0' }}>
            <span className="spinner" />
          </div>
          <p style={{ textAlign: 'center', color: '#64748b', fontSize: 14 }}>Validating your invitation link...</p>
        </div>
      </div>
    )
  }

  if (state === 'error') {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <div className="auth-logo">
            <h1>Invoice<span>Direct</span></h1>
            <p>Platform Admin Invitation</p>
          </div>
          <div className="alert alert-error">{errorMessage}</div>
          <p style={{ textAlign: 'center', color: '#64748b', fontSize: 13, marginTop: 12 }}>
            If you believe this is a mistake, please contact the platform admin who invited you.
          </p>
        </div>
      </div>
    )
  }

  if (state === 'success') {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <div className="auth-logo">
            <h1>Invoice<span>Direct</span></h1>
            <p>Platform Admin Invitation</p>
          </div>
          <div className="alert alert-success" style={{ marginBottom: 16 }}>
            Your platform admin account has been created.
          </div>
          <div style={{ background: '#f8fafc', borderRadius: 8, padding: 14, fontSize: 13, marginBottom: 16 }}>
            <span className="text-muted">Username: </span>
            <span style={{ fontFamily: 'monospace', fontWeight: 600 }}>{createdUsername}</span>
          </div>
          <button className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }}
            onClick={() => navigate('/admin/login')}>
            Go to admin login
          </button>
        </div>
      </div>
    )
  }

  // form state
  return (
    <div className="auth-page" style={{ alignItems: 'flex-start', paddingTop: 40 }}>
      <div className="auth-card" style={{ maxWidth: 420 }}>
        <div className="auth-logo">
          <h1>Invoice<span>Direct</span></h1>
          <p>Platform Admin Invitation</p>
        </div>

        {tokenData && (
          <div style={{ background: '#fff7ed', border: '1px solid #fed7aa', borderRadius: 8, padding: '12px 14px', marginBottom: 16 }}>
            <div style={{ fontSize: 13, color: '#9a3412' }}>
              <span style={{ fontWeight: 600 }}>{tokenData.invitedBy ?? 'A platform admin'}</span> has invited{' '}
              <span style={{ fontWeight: 600 }}>{tokenData.email}</span> to become a platform administrator.
            </div>
          </div>
        )}

        {inlineError && <div className="alert alert-error">{inlineError}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Username *</label>
            <input
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </div>
          <div className="form-group">
            <label>Password *</label>
            <input
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder="At least 8 characters"
              autoComplete="new-password"
              minLength={8}
              required
            />
          </div>
          <div className="form-group">
            <label>Confirm password *</label>
            <input
              type="password"
              value={passwordConfirm}
              onChange={e => setPasswordConfirm(e.target.value)}
              placeholder="Repeat your password"
              autoComplete="new-password"
              minLength={8}
              required
            />
          </div>
          <button
            type="submit"
            className="btn btn-primary"
            style={{ width: '100%', justifyContent: 'center' }}
            disabled={submitting}
          >
            {submitting ? <span className="spinner" /> : 'Create admin account'}
          </button>
        </form>
      </div>
    </div>
  )
}
