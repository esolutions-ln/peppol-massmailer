import { useState, useEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { validateAdminPasswordResetToken, completeAdminPasswordReset } from '../../api/client'

type PageState = 'loading' | 'error' | 'form' | 'success'

export default function AdminResetPasswordPage() {
  const { token } = useParams<{ token: string }>()
  const navigate = useNavigate()
  const [state, setState] = useState<PageState>('loading')
  const [username, setUsername] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [inlineError, setInlineError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  useEffect(() => {
    if (!token) {
      setErrorMessage('Invalid reset link.')
      setState('error')
      return
    }
    validateAdminPasswordResetToken(token)
      .then(data => {
        setUsername(data.username)
        setState('form')
      })
      .catch((err: any) => {
        const status = err.response?.status
        const msg = err.response?.data?.error || err.response?.data?.message
        if (status === 410) {
          setErrorMessage(msg ?? 'This reset link has already been used or has expired.')
        } else if (status === 404) {
          setErrorMessage(msg ?? 'This reset link is invalid.')
        } else {
          setErrorMessage(msg ?? 'Unable to validate this reset link. Please try again later.')
        }
        setState('error')
      })
  }, [token])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!token) return
    setInlineError('')
    if (newPassword.length < 8) {
      setInlineError('Password must be at least 8 characters.')
      return
    }
    if (newPassword !== confirmPassword) {
      setInlineError('Passwords do not match.')
      return
    }
    setSubmitting(true)
    try {
      await completeAdminPasswordReset(token, newPassword)
      setState('success')
    } catch (err: any) {
      const status = err.response?.status
      const msg = err.response?.data?.error || err.response?.data?.message
      if (status === 400 || status === 410) {
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
            <p>Reset Password</p>
          </div>
          <div style={{ display: 'flex', justifyContent: 'center', padding: '24px 0' }}>
            <span className="spinner" />
          </div>
          <p style={{ textAlign: 'center', color: '#64748b', fontSize: 14 }}>Validating your reset link...</p>
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
            <p>Reset Password</p>
          </div>
          <div className="alert alert-error">{errorMessage}</div>
          <p style={{ textAlign: 'center', color: '#64748b', fontSize: 13, marginTop: 12 }}>
            You can request a new reset link from the admin login page.
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
            <p>Reset Password</p>
          </div>
          <div className="alert alert-success" style={{ marginBottom: 16 }}>
            Your password has been reset.
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
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-logo">
          <h1>Invoice<span>Direct</span></h1>
          <p>Reset Password</p>
        </div>

        <div style={{ background: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: 8, padding: '12px 14px', marginBottom: 16, fontSize: 13, color: '#1e40af' }}>
          Setting a new password for <span style={{ fontWeight: 600 }}>{username}</span>.
        </div>

        {inlineError && <div className="alert alert-error">{inlineError}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>New password *</label>
            <input
              type="password"
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              placeholder="At least 8 characters"
              autoComplete="new-password"
              minLength={8}
              required
            />
          </div>
          <div className="form-group">
            <label>Confirm new password *</label>
            <input
              type="password"
              value={confirmPassword}
              onChange={e => setConfirmPassword(e.target.value)}
              placeholder="Repeat new password"
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
            {submitting ? <span className="spinner" /> : 'Reset password'}
          </button>
        </form>
      </div>
    </div>
  )
}
