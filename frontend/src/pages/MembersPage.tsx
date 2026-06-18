import { useEffect, useState } from 'react'
import { useAuth } from '../context/AuthContext'
import {
  listOrgMembers, createOrgMember, updateOrgMemberRole, setOrgMemberActive,
  resetOrgMemberPassword, deleteOrgMember
} from '../api/client'
import type { OrgMember, OrgMemberRole } from '../types'
import { UserPlus, Shield, Eye, RefreshCw, Trash2, Power } from 'lucide-react'

export default function MembersPage() {
  const { canManageMembers, session } = useAuth()
  const [members, setMembers] = useState<OrgMember[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState<{ email: string; password: string; displayName: string; role: OrgMemberRole }>({
    email: '', password: '', displayName: '', role: 'ORG_VIEWER'
  })
  const [submitting, setSubmitting] = useState(false)

  async function reload() {
    setLoading(true); setError('')
    try {
      const { data } = await listOrgMembers()
      setMembers(data)
    } catch (e: any) {
      setError(e?.response?.data?.error || 'Failed to load members.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { reload() }, [])

  if (!canManageMembers) {
    return (
      <div className="page">
        <h1>Team</h1>
        <div className="alert alert-error">
          You don't have permission to manage team members. Only organisation admins can.
        </div>
      </div>
    )
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true); setError('')
    try {
      await createOrgMember(form)
      setForm({ email: '', password: '', displayName: '', role: 'ORG_VIEWER' })
      setShowCreate(false)
      await reload()
    } catch (e: any) {
      setError(e?.response?.data?.error || e?.response?.data?.message || 'Failed to create member.')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleToggleActive(m: OrgMember) {
    if (!confirm(`${m.active ? 'Deactivate' : 'Activate'} ${m.email}?`)) return
    try {
      await setOrgMemberActive(m.id, !m.active)
      reload()
    } catch (e: any) {
      alert(e?.response?.data?.error || 'Failed to update member.')
    }
  }

  async function handleResetPassword(m: OrgMember) {
    const pwd = prompt(`Set a new password for ${m.email} (min 8 chars):`)
    if (!pwd) return
    if (pwd.length < 8) { alert('Password must be at least 8 characters.'); return }
    try {
      await resetOrgMemberPassword(m.id, pwd)
      alert(`Password reset. Share the new credentials securely with ${m.email}.`)
    } catch (e: any) {
      alert(e?.response?.data?.error || 'Failed to reset password.')
    }
  }

  async function handleChangeRole(m: OrgMember, role: OrgMemberRole) {
    if (role === m.role) return
    try {
      await updateOrgMemberRole(m.id, role)
      reload()
    } catch (e: any) {
      alert(e?.response?.data?.error || 'Failed to change role.')
    }
  }

  async function handleDelete(m: OrgMember) {
    if (!confirm(`Permanently delete ${m.email}? This revokes all their sessions and cannot be undone.`)) return
    try {
      await deleteOrgMember(m.id)
      reload()
    } catch (e: any) {
      alert(e?.response?.data?.error || 'Failed to delete member.')
    }
  }

  return (
    <div className="page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <h1>Team</h1>
          <p style={{ color: '#64748b', marginTop: 4 }}>
            Manage who can access this organisation's invoices and campaigns.
          </p>
        </div>
        <button className="btn btn-primary" onClick={() => setShowCreate(s => !s)}>
          <UserPlus size={16} /> Add member
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {showCreate && (
        <div className="card" style={{ marginBottom: 16 }}>
          <form onSubmit={handleCreate}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 12 }}>
              <div className="form-group">
                <label>Email</label>
                <input type="email" required value={form.email}
                  onChange={e => setForm({ ...form, email: e.target.value })}
                  placeholder="member@yourcompany.co.zw" />
              </div>
              <div className="form-group">
                <label>Display name</label>
                <input value={form.displayName}
                  onChange={e => setForm({ ...form, displayName: e.target.value })}
                  placeholder="Jane Doe" />
              </div>
              <div className="form-group">
                <label>Temporary password (min 8)</label>
                <input type="text" required minLength={8} value={form.password}
                  onChange={e => setForm({ ...form, password: e.target.value })}
                  placeholder="They'll change it on first login" />
              </div>
              <div className="form-group">
                <label>Role</label>
                <select value={form.role}
                  onChange={e => setForm({ ...form, role: e.target.value as OrgMemberRole })}>
                  <option value="ORG_VIEWER">Viewer — read-only access to invoices &amp; campaigns</option>
                  <option value="ORG_ADMIN">Admin — full access, can manage team</option>
                </select>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <button className="btn btn-primary" disabled={submitting} type="submit">
                {submitting ? 'Creating…' : 'Create member'}
              </button>
              <button className="btn btn-secondary" type="button" onClick={() => setShowCreate(false)}>
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      {loading ? <div className="spinner" /> : (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>Email</th>
                <th>Display name</th>
                <th>Role</th>
                <th>Status</th>
                <th>Last login</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {members.length === 0 ? (
                <tr><td colSpan={6} style={{ textAlign: 'center', padding: 24, color: '#64748b' }}>
                  No members yet. Add one above.
                </td></tr>
              ) : members.map(m => (
                <tr key={m.id}>
                  <td>{m.email}{session?.memberId === m.id && <span style={{ color: '#64748b', fontSize: 12 }}> (you)</span>}</td>
                  <td>{m.displayName || '—'}</td>
                  <td>
                    <select value={m.role}
                      onChange={e => handleChangeRole(m, e.target.value as OrgMemberRole)}
                      disabled={session?.memberId === m.id}
                      style={{ fontSize: 13, padding: '4px 8px' }}>
                      <option value="ORG_ADMIN">Admin</option>
                      <option value="ORG_VIEWER">Viewer</option>
                    </select>
                  </td>
                  <td>
                    <span style={{ color: m.active ? '#16a34a' : '#dc2626', fontWeight: 500 }}>
                      {m.active ? 'Active' : 'Disabled'}
                    </span>
                  </td>
                  <td style={{ color: '#64748b', fontSize: 13 }}>
                    {m.lastLoginAt ? new Date(m.lastLoginAt).toLocaleString() : 'Never'}
                  </td>
                  <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                    <button className="btn-icon" title="Reset password"
                      onClick={() => handleResetPassword(m)}><RefreshCw size={14} /></button>
                    <button className="btn-icon" title={m.active ? 'Deactivate' : 'Activate'}
                      onClick={() => handleToggleActive(m)}
                      disabled={session?.memberId === m.id}><Power size={14} /></button>
                    <button className="btn-icon" title="Delete"
                      onClick={() => handleDelete(m)}
                      disabled={session?.memberId === m.id}><Trash2 size={14} /></button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div style={{ marginTop: 24, padding: 16, background: '#f8fafc', borderRadius: 8, fontSize: 13, color: '#475569' }}>
        <strong style={{ color: '#0f172a' }}>About roles</strong>
        <ul style={{ marginTop: 8, paddingLeft: 20 }}>
          <li><Shield size={12} style={{ display: 'inline', marginRight: 4 }} /><b>Admin</b> — full access. Can send invoices, manage customers, templates, and team members.</li>
          <li><Eye size={12} style={{ display: 'inline', marginRight: 4 }} /><b>Viewer</b> — read-only. Can view invoices (including PDFs), campaigns, customers, and dashboards. Cannot send or manage anything.</li>
        </ul>
      </div>
    </div>
  )
}
