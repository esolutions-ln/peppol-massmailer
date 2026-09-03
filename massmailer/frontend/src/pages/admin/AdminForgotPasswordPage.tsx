import { useState } from 'react'
import { Link } from 'react-router-dom'
import { requestAdminPasswordReset } from '../../api/client'

export default function AdminForgotPasswordPage() {
  const [username, setUsername] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitted, setSubmitted] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    try {
      await requestAdminPasswordReset(username.trim())
    } catch {
      // Deliberately ignored — the request always shows the same generic message,
      // whether or not the username exists, so as not to reveal account existence.
    } finally {
      setSubmitting(false)
      setSubmitted(true)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-logo">
          <h1>Invoice<span>Direct</span></h1>
          <p>Platform Administration</p>
        </div>

        {submitted ? (
          <>
            <div className="alert alert-success">
              If an account with that username exists and has an email on file, a reset link has been sent.
            </div>
            <p style={{ textAlign: 'center', color: '#64748b', fontSize: 13, marginTop: 12 }}>
              Didn't get an email? Check spam, or ask another platform admin to check your account has an email set under My Profile.
            </p>
          </>
        ) : (
          <>
            <p style={{ fontSize: 13, color: '#64748b', marginBottom: 16 }}>
              Enter your admin username and we'll email you a link to reset your password —
              if that account has an email on file.
            </p>
            <form onSubmit={handleSubmit}>
              <div className="form-group">
                <label>Username</label>
                <input autoComplete="username" required value={username}
                  onChange={e => setUsername(e.target.value)} placeholder="admin" />
              </div>
              <button className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }} disabled={submitting}>
                {submitting ? <span className="spinner" /> : 'Send reset link'}
              </button>
            </form>
          </>
        )}

        <div style={{ marginTop: 20, textAlign: 'center' }}>
          <Link to="/admin/login" style={{ fontSize: 13, color: '#64748b', textDecoration: 'none' }}>← Back to admin login</Link>
        </div>
      </div>
    </div>
  )
}
