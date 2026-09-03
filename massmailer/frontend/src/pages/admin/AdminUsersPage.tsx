import { useEffect, useState } from 'react'
import { useAuth } from '../../context/AuthContext'
import {
  listAdminUsers, createAdminUser, deactivateAdminUser, reactivateAdminUser,
  sendAdminInvitation, listAdminInvitations, cancelAdminInvitation
} from '../../api/client'
import type { AdminUser, AdminInvitation } from '../../types'
import { UserPlus, ShieldAlert, Power, Mail, X, Clock } from 'lucide-react'

type AddMode = 'invite' | 'direct'

const STATUS_BADGE: Record<AdminInvitation['status'], string> = {
  PENDING: 'badge-blue',
  COMPLETED: 'badge-green',
  CANCELLED: 'badge-red',
  EXPIRED: 'badge-red',
}

export default function AdminUsersPage() {
  const { session } = useAuth()
  const [users, setUsers] = useState<AdminUser[]>([])
  const [invitations, setInvitations] = useState<AdminInvitation[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const [showAdd, setShowAdd] = useState(false)
  const [mode, setMode] = useState<AddMode>('invite')
  const [inviteForm, setInviteForm] = useState({ email: '', displayName: '' })
  const [directForm, setDirectForm] = useState({ username: '', password: '', displayName: '', email: '' })
  const [submitting, setSubmitting] = useState(false)
  const [inviteSentTo, setInviteSentTo] = useState('')

  async function reload() {
    setLoading(true); setError('')
    try {
      const [usersRes, invitesRes] = await Promise.all([listAdminUsers(), listAdminInvitations()])
      setUsers(usersRes.data)
      setInvitations(invitesRes.data)
    } catch (e: any) {
      setError(e?.response?.data?.error || e?.response?.data?.message || 'Failed to load admin users.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { reload() }, [])

  async function handleInvite(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true); setError(''); setInviteSentTo('')
    try {
      await sendAdminInvitation(inviteForm)
      setInviteSentTo(inviteForm.email)
      setInviteForm({ email: '', displayName: '' })
      await reload()
    } catch (e: any) {
      setError(e?.response?.data?.error || e?.response?.data?.message || 'Failed to send invitation.')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleCreateDirect(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true); setError('')
    try {
      await createAdminUser(directForm)
      setDirectForm({ username: '', password: '', displayName: '', email: '' })
      setShowAdd(false)
      await reload()
    } catch (e: any) {
      setError(e?.response?.data?.error || e?.response?.data?.message || 'Failed to create admin user.')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleToggleActive(u: AdminUser) {
    if (!confirm(`${u.active ? 'Deactivate' : 'Reactivate'} ${u.username}?`)) return
    try {
      if (u.active) await deactivateAdminUser(u.id)
      else await reactivateAdminUser(u.id)
      reload()
    } catch (e: any) {
      alert(e?.response?.data?.error || e?.response?.data?.message || 'Failed to update admin user.')
    }
  }

  async function handleCancelInvitation(inv: AdminInvitation) {
    if (!confirm(`Cancel the invitation to ${inv.email}?`)) return
    try {
      await cancelAdminInvitation(inv.id)
      reload()
    } catch (e: any) {
      alert(e?.response?.data?.error || e?.response?.data?.message || 'Failed to cancel invitation.')
    }
  }

  const pendingInvitations = invitations.filter(i => i.status === 'PENDING')

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Platform Admins</span>
        <button className="btn btn-primary btn-sm" onClick={() => setShowAdd(s => !s)}>
          <UserPlus size={15} /> Add admin
        </button>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Platform Admins</h2>
          <p>Manage who has full platform-administrator access to InvoiceDirect.</p>
        </div>

        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '12px 16px', background: '#fff7ed', border: '1px solid #fed7aa', borderRadius: 10, fontSize: 13, color: '#9a3412', marginBottom: 20 }}>
          <ShieldAlert size={16} style={{ flexShrink: 0, marginTop: 1 }} />
          <span>Every platform admin has full, equal access — including the ability to invite, create, or deactivate other admins. There is no separate "super admin" tier, so only grant this to people who should have unrestricted platform control.</span>
        </div>

        {error && <div className="alert alert-error">{error}</div>}
        {inviteSentTo && (
          <div className="alert alert-success">
            Invitation sent to {inviteSentTo}. They have 72 hours to set up their account before the link expires.
          </div>
        )}

        {showAdd && (
          <div className="card" style={{ marginBottom: 20 }}>
            <div className="card-header" style={{ display: 'flex', gap: 4 }}>
              <button
                className={`btn btn-sm ${mode === 'invite' ? 'btn-primary' : 'btn-secondary'}`}
                onClick={() => setMode('invite')} type="button">
                <Mail size={13} /> Invite by email
              </button>
              <button
                className={`btn btn-sm ${mode === 'direct' ? 'btn-primary' : 'btn-secondary'}`}
                onClick={() => setMode('direct')} type="button">
                Create directly
              </button>
            </div>

            {mode === 'invite' ? (
              <form onSubmit={handleInvite}>
                <p className="text-sm text-muted" style={{ marginBottom: 12 }}>
                  We'll email them a link to set their own username and password. The link expires in 72 hours.
                </p>
                <div className="grid-2">
                  <div className="form-group">
                    <label>Email</label>
                    <input type="email" required value={inviteForm.email}
                      onChange={e => setInviteForm({ ...inviteForm, email: e.target.value })}
                      placeholder="jane.moyo@example.com" />
                  </div>
                  <div className="form-group">
                    <label>Display name (optional)</label>
                    <input value={inviteForm.displayName}
                      onChange={e => setInviteForm({ ...inviteForm, displayName: e.target.value })}
                      placeholder="Jane Moyo" />
                  </div>
                </div>
                <div className="flex gap-2 mt-4">
                  <button className="btn btn-primary" disabled={submitting} type="submit">
                    {submitting ? <><span className="spinner" /> Sending…</> : 'Send invitation'}
                  </button>
                  <button className="btn btn-secondary" type="button" onClick={() => setShowAdd(false)}>
                    Cancel
                  </button>
                </div>
              </form>
            ) : (
              <form onSubmit={handleCreateDirect}>
                <p className="text-sm text-muted" style={{ marginBottom: 12 }}>
                  Creates the account immediately with a password you set — use this if email delivery
                  isn't available. Prefer "Invite by email" when possible.
                </p>
                <div className="grid-2">
                  <div className="form-group">
                    <label>Username</label>
                    <input required value={directForm.username}
                      onChange={e => setDirectForm({ ...directForm, username: e.target.value })}
                      placeholder="jane.moyo" />
                  </div>
                  <div className="form-group">
                    <label>Display name</label>
                    <input value={directForm.displayName}
                      onChange={e => setDirectForm({ ...directForm, displayName: e.target.value })}
                      placeholder="Jane Moyo" />
                  </div>
                  <div className="form-group">
                    <label>Temporary password (min 8)</label>
                    <input type="text" required minLength={8} value={directForm.password}
                      onChange={e => setDirectForm({ ...directForm, password: e.target.value })}
                      placeholder="Share this with them securely" />
                  </div>
                  <div className="form-group">
                    <label>Email (optional)</label>
                    <input type="email" value={directForm.email}
                      onChange={e => setDirectForm({ ...directForm, email: e.target.value })}
                      placeholder="jane.moyo@example.com" />
                    <span style={{ fontSize: 12, color: '#94a3b8' }}>Needed for them to self-service "forgot password" later.</span>
                  </div>
                </div>
                <div className="flex gap-2 mt-4">
                  <button className="btn btn-primary" disabled={submitting} type="submit">
                    {submitting ? <><span className="spinner" /> Creating…</> : 'Create admin'}
                  </button>
                  <button className="btn btn-secondary" type="button" onClick={() => setShowAdd(false)}>
                    Cancel
                  </button>
                </div>
              </form>
            )}
          </div>
        )}

        {loading ? (
          <div className="loading-center"><span className="spinner" /></div>
        ) : (
          <>
            <div className="card">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Username</th>
                    <th>Display name</th>
                    <th>Status</th>
                    <th>Created</th>
                    <th style={{ textAlign: 'right' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {users.length === 0 ? (
                    <tr><td colSpan={5} style={{ textAlign: 'center', padding: 32, color: '#94a3b8' }}>
                      No admin users yet.
                    </td></tr>
                  ) : users.map(u => (
                    <tr key={u.id}>
                      <td>
                        <span style={{ fontWeight: 500 }}>{u.username}</span>
                        {session?.username === u.username && <span className="badge badge-blue" style={{ marginLeft: 6 }}>you</span>}
                      </td>
                      <td>{u.displayName || <span className="text-muted">—</span>}</td>
                      <td>
                        <span className={`badge ${u.active ? 'badge-green' : 'badge-red'}`}>
                          {u.active ? 'Active' : 'Disabled'}
                        </span>
                      </td>
                      <td className="text-sm text-muted">
                        {u.createdAt ? new Date(u.createdAt).toLocaleString() : '—'}
                      </td>
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                        <button className="btn-icon" title={u.active ? 'Deactivate' : 'Reactivate'}
                          onClick={() => handleToggleActive(u)}
                          disabled={session?.username === u.username}>
                          <Power size={14} />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {pendingInvitations.length > 0 && (
              <div className="card" style={{ marginTop: 20 }}>
                <div className="card-header">
                  <span className="card-title"><Clock size={14} style={{ verticalAlign: -2, marginRight: 6 }} />Pending invitations</span>
                </div>
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Email</th>
                      <th>Invited by</th>
                      <th>Status</th>
                      <th>Expires</th>
                      <th style={{ textAlign: 'right' }}>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {pendingInvitations.map(inv => (
                      <tr key={inv.id}>
                        <td style={{ fontWeight: 500 }}>{inv.email}</td>
                        <td className="text-sm text-muted">{inv.invitedBy}</td>
                        <td><span className={`badge ${STATUS_BADGE[inv.status]}`}>{inv.status}</span></td>
                        <td className="text-sm text-muted">{new Date(inv.expiresAt).toLocaleString()}</td>
                        <td style={{ textAlign: 'right' }}>
                          <button className="btn-icon" title="Cancel invitation" onClick={() => handleCancelInvitation(inv)}>
                            <X size={14} />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </div>
    </>
  )
}
