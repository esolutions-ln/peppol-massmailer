import { useEffect, useState } from 'react'
import { useAuth } from '../../context/AuthContext'
import { listAdminUsers, createAdminUser, deactivateAdminUser, reactivateAdminUser } from '../../api/client'
import type { AdminUser } from '../../types'
import { UserPlus, ShieldAlert, Power } from 'lucide-react'

export default function AdminUsersPage() {
  const { session } = useAuth()
  const [users, setUsers] = useState<AdminUser[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ username: '', password: '', displayName: '' })
  const [submitting, setSubmitting] = useState(false)

  async function reload() {
    setLoading(true); setError('')
    try {
      const { data } = await listAdminUsers()
      setUsers(data)
    } catch (e: any) {
      setError(e?.response?.data?.error || e?.response?.data?.message || 'Failed to load admin users.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { reload() }, [])

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true); setError('')
    try {
      await createAdminUser(form)
      setForm({ username: '', password: '', displayName: '' })
      setShowCreate(false)
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

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Platform Admins</span>
        <button className="btn btn-primary btn-sm" onClick={() => setShowCreate(s => !s)}>
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
          <span>Every platform admin has full, equal access — including the ability to create or deactivate other admins. There is no separate "super admin" tier, so only grant this to people who should have unrestricted platform control.</span>
        </div>

        {error && <div className="alert alert-error">{error}</div>}

        {showCreate && (
          <div className="card" style={{ marginBottom: 20 }}>
            <div className="card-header">
              <span className="card-title">New platform admin</span>
            </div>
            <form onSubmit={handleCreate}>
              <div className="grid-2">
                <div className="form-group">
                  <label>Username</label>
                  <input required value={form.username}
                    onChange={e => setForm({ ...form, username: e.target.value })}
                    placeholder="jane.moyo" />
                </div>
                <div className="form-group">
                  <label>Display name</label>
                  <input value={form.displayName}
                    onChange={e => setForm({ ...form, displayName: e.target.value })}
                    placeholder="Jane Moyo" />
                </div>
                <div className="form-group">
                  <label>Temporary password (min 8)</label>
                  <input type="text" required minLength={8} value={form.password}
                    onChange={e => setForm({ ...form, password: e.target.value })}
                    placeholder="Share this with them securely" />
                </div>
              </div>
              <div className="flex gap-2 mt-4">
                <button className="btn btn-primary" disabled={submitting} type="submit">
                  {submitting ? <><span className="spinner" /> Creating…</> : 'Create admin'}
                </button>
                <button className="btn btn-secondary" type="button" onClick={() => setShowCreate(false)}>
                  Cancel
                </button>
              </div>
            </form>
          </div>
        )}

        {loading ? (
          <div className="loading-center"><span className="spinner" /></div>
        ) : (
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
        )}
      </div>
    </>
  )
}
