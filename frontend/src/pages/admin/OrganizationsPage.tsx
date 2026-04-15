import { useEffect, useState } from 'react'
import { listOrgs, assignRateProfile, listRateProfiles } from '../../api/client'
import { Building2, RefreshCw, X } from 'lucide-react'
import type { Organization, RateProfile } from '../../types'

function AssignModal({ org, profiles, onClose, onSave }: {
  org: Organization
  profiles: RateProfile[]
  onClose: () => void
  onSave: () => void
}) {
  const [profileId, setProfileId] = useState(org.rateProfileId ?? '')
  const [loading, setLoading] = useState(false)

  const handleSave = async () => {
    setLoading(true)
    try { await assignRateProfile(org.id, profileId); onSave() }
    catch { /* ignore */ }
    finally { setLoading(false) }
  }

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 380 }}>
        <div className="modal-header">
          <span className="modal-title">Assign Rate Profile</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        <p className="text-sm text-muted mb-4">{org.name}</p>
        <div className="form-group">
          <label>Rate Profile</label>
          <select value={profileId} onChange={e => setProfileId(e.target.value)}>
            <option value="">— None —</option>
            {profiles.map(p => <option key={p.id} value={p.id}>{p.name} ({p.currency})</option>)}
          </select>
        </div>
        <div className="flex gap-2 mt-4">
          <button className="btn btn-primary" onClick={handleSave} disabled={loading}>
            {loading ? <span className="spinner" /> : 'Save'}
          </button>
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
        </div>
      </div>
    </div>
  )
}

export default function OrganizationsPage() {
  const [orgs, setOrgs] = useState<Organization[]>([])
  const [profiles, setProfiles] = useState<RateProfile[]>([])
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<Organization | null>(null)
  const [search, setSearch] = useState('')

  const load = () => {
    setLoading(true)
    Promise.all([listOrgs(), listRateProfiles()])
      .then(([o, p]) => { setOrgs(o.data ?? []); setProfiles(p.data ?? []) })
      .catch(() => {})
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  const filtered = orgs.filter(o =>
    !search || o.name?.toLowerCase().includes(search.toLowerCase()) || o.slug?.includes(search.toLowerCase())
  )

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Organizations</span>
        <button className="btn btn-secondary btn-sm" onClick={load}><RefreshCw size={14} /> Refresh</button>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Organizations</h2>
          <p>All registered organizations on the platform</p>
        </div>
        <div className="card">
          <div className="mb-4">
            <input style={{ maxWidth: 280 }} placeholder="Search by name or slug…" value={search} onChange={e => setSearch(e.target.value)} />
          </div>
          {loading ? (
            <div className="loading-center"><span className="spinner" /></div>
          ) : filtered.length === 0 ? (
            <div className="empty-state"><Building2 size={32} /><p>No organizations found</p></div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr><th>Name</th><th>Slug</th><th>Sender Email</th><th>ERP</th><th>Rate Profile</th><th>Status</th><th></th></tr>
                </thead>
                <tbody>
                  {filtered.map(o => {
                    const profile = profiles.find(p => p.id === o.rateProfileId)
                    return (
                      <tr key={o.id}>
                        <td>
                          <div style={{ fontWeight: 500 }}>{o.name}</div>
                          <div className="text-sm text-muted">{o.id?.slice(0, 8)}…</div>
                        </td>
                        <td className="text-muted">{o.slug}</td>
                        <td>{o.senderEmail}</td>
                        <td className="text-sm">{o.primaryErpSource ?? '—'}</td>
                        <td>{profile ? <span className="badge badge-blue">{profile.name}</span> : <span className="text-muted text-sm">None</span>}</td>
                        <td><span className={`badge ${o.status === 'ACTIVE' ? 'badge-green' : 'badge-red'}`}>{o.status}</span></td>
                        <td>
                          <button className="btn btn-secondary btn-sm" onClick={() => setSelected(o)}>Assign Plan</button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
      {selected && (
        <AssignModal
          org={selected} profiles={profiles}
          onClose={() => setSelected(null)}
          onSave={() => { setSelected(null); load() }}
        />
      )}
    </>
  )
}
