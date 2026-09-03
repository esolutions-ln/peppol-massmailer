import { useEffect, useState } from 'react'
import { useAuth } from '../../context/AuthContext'
import { getAdminProfile, updateAdminProfile, changeAdminPassword } from '../../api/client'

export default function AdminProfilePage() {
  const { session, login } = useAuth()
  const [loading, setLoading] = useState(true)

  const [profileForm, setProfileForm] = useState({ displayName: '', email: '' })
  const [profileError, setProfileError] = useState('')
  const [profileSuccess, setProfileSuccess] = useState(false)
  const [savingProfile, setSavingProfile] = useState(false)

  const [pwForm, setPwForm] = useState({ currentPassword: '', newPassword: '', newPasswordConfirm: '' })
  const [pwError, setPwError] = useState('')
  const [changingPw, setChangingPw] = useState(false)
  const [pwChanged, setPwChanged] = useState(false)

  useEffect(() => {
    getAdminProfile()
      .then(({ data }) => {
        setProfileForm({ displayName: data.displayName ?? '', email: data.email ?? '' })
      })
      .catch(() => setProfileError('Failed to load your profile.'))
      .finally(() => setLoading(false))
  }, [])

  async function handleSaveProfile(e: React.FormEvent) {
    e.preventDefault()
    setSavingProfile(true); setProfileError(''); setProfileSuccess(false)
    try {
      const { data } = await updateAdminProfile(profileForm)
      setProfileSuccess(true)
      // Refresh the sidebar's displayed name for this session.
      if (session) login({ ...session, name: data.displayName || data.username })
    } catch (e: any) {
      setProfileError(e?.response?.data?.error || e?.response?.data?.message || 'Failed to update profile.')
    } finally {
      setSavingProfile(false)
    }
  }

  async function handleChangePassword(e: React.FormEvent) {
    e.preventDefault()
    setPwError('')
    if (pwForm.newPassword.length < 8) {
      setPwError('New password must be at least 8 characters.')
      return
    }
    if (pwForm.newPassword !== pwForm.newPasswordConfirm) {
      setPwError('New passwords do not match.')
      return
    }
    setChangingPw(true)
    try {
      await changeAdminPassword(pwForm)
      // Every session for this account — including this one — is now invalidated server-side.
      // Show a confirmation screen with an explicit "Sign in again" button rather than
      // auto-navigating: logging out here would flip ProtectedRoute's session check while
      // this page is still mounted, and its own redirect-to-/login could race an immediate
      // navigate() to /admin/login and win, landing on the wrong login page.
      setPwChanged(true)
    } catch (e: any) {
      setPwError(e?.response?.data?.error || e?.response?.data?.message || 'Failed to change password.')
    } finally {
      setChangingPw(false)
    }
  }

  function handleSignInAgain() {
    // Hard navigation, not React Router's navigate(): clearing the session and switching
    // routes via client-side state updates raced against ProtectedRoute's own
    // session-based redirect and unpredictably landed on the wrong login page. A full
    // page load sidesteps that entirely — the app boots fresh with no session.
    localStorage.removeItem('id_session')
    window.location.href = '/admin/login'
  }

  if (loading) {
    return (
      <>
        <div className="topbar"><span className="topbar-title">My Profile</span></div>
        <div className="content"><div className="loading-center"><span className="spinner" /></div></div>
      </>
    )
  }

  return (
    <>
      <div className="topbar"><span className="topbar-title">My Profile</span></div>
      <div className="content">
        <div className="page-header">
          <h2>My Profile</h2>
          <p>Update your details and password for your own platform admin account.</p>
        </div>

        <div className="card" style={{ marginBottom: 20 }}>
          <div className="card-header"><span className="card-title">Profile</span></div>
          {profileError && <div className="alert alert-error">{profileError}</div>}
          {profileSuccess && <div className="alert alert-success">Profile updated.</div>}
          <form onSubmit={handleSaveProfile}>
            <div className="grid-2">
              <div className="form-group">
                <label>Username</label>
                <input value={session?.username ?? ''} disabled />
                <span style={{ fontSize: 12, color: '#94a3b8' }}>Usernames cannot be changed.</span>
              </div>
              <div className="form-group">
                <label>Display name</label>
                <input value={profileForm.displayName}
                  onChange={e => setProfileForm({ ...profileForm, displayName: e.target.value })}
                  placeholder="Jane Moyo" />
              </div>
              <div className="form-group" style={{ gridColumn: '1 / -1' }}>
                <label>Email</label>
                <input type="email" value={profileForm.email}
                  onChange={e => setProfileForm({ ...profileForm, email: e.target.value })}
                  placeholder="jane.moyo@example.com" />
                <span style={{ fontSize: 12, color: '#94a3b8' }}>
                  Required for "forgot password" — without an email on file, you can't self-service reset your password if you get locked out.
                </span>
              </div>
            </div>
            <button className="btn btn-primary" disabled={savingProfile} type="submit" style={{ marginTop: 4 }}>
              {savingProfile ? <><span className="spinner" /> Saving…</> : 'Save profile'}
            </button>
          </form>
        </div>

        <div className="card">
          <div className="card-header"><span className="card-title">Change password</span></div>
          {pwChanged ? (
            <>
              <div className="alert alert-success">
                Password changed. Every session for this account has been signed out, including this one.
              </div>
              <button className="btn btn-primary" onClick={handleSignInAgain}>
                Sign in again
              </button>
            </>
          ) : (
          <>
          {pwError && <div className="alert alert-error">{pwError}</div>}
          <form onSubmit={handleChangePassword}>
            <div className="form-group">
              <label>Current password</label>
              <input type="password" required autoComplete="current-password"
                value={pwForm.currentPassword}
                onChange={e => setPwForm({ ...pwForm, currentPassword: e.target.value })} />
            </div>
            <div className="grid-2">
              <div className="form-group">
                <label>New password</label>
                <input type="password" required minLength={8} autoComplete="new-password"
                  placeholder="At least 8 characters"
                  value={pwForm.newPassword}
                  onChange={e => setPwForm({ ...pwForm, newPassword: e.target.value })} />
              </div>
              <div className="form-group">
                <label>Confirm new password</label>
                <input type="password" required minLength={8} autoComplete="new-password"
                  placeholder="Repeat new password"
                  value={pwForm.newPasswordConfirm}
                  onChange={e => setPwForm({ ...pwForm, newPasswordConfirm: e.target.value })} />
              </div>
            </div>
            <span style={{ fontSize: 12, color: '#94a3b8', display: 'block', marginBottom: 12 }}>
              Changing your password signs you out everywhere, including this session.
            </span>
            <button className="btn btn-primary" disabled={changingPw} type="submit">
              {changingPw ? <><span className="spinner" /> Changing…</> : 'Change password'}
            </button>
          </form>
          </>
          )}
        </div>
      </div>
    </>
  )
}
