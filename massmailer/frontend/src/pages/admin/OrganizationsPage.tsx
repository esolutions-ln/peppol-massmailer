import { useEffect, useState, useCallback, useMemo, useRef } from 'react'
import { Link } from 'react-router-dom'
import { listOrgsPaged, assignRateProfile, listRateProfiles, updateOrg, deactivateOrg, registerOrg, adminPeppolOnboard } from '../../api/client'
import { Building2, RefreshCw, X, Pencil, Power, Plus, Users, ChevronLeft, ChevronRight, Network } from 'lucide-react'
import type { Organization, RateProfile, DeliveryMode } from '../../types'

type OrgStatus = 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED'

function CreateOrgModal({ onClose, onCreated }: {
  onClose: () => void
  onCreated: (apiKey: string, orgName: string) => void
}) {
  const [form, setForm] = useState({
    name: '', slug: '',
    senderEmail: '', senderDisplayName: '',
    accountsEmail: '', companyAddress: '',
    primaryErpSource: 'GENERIC_API', erpTenantId: '',
    vatNumber: '', tinNumber: '',
    deliveryMode: 'EMAIL' as DeliveryMode,
    user: { firstName: '', lastName: '', jobTitle: '', emailAddress: '' },
  })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const set = (k: keyof Omit<typeof form, 'user'>) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setForm(f => ({ ...f, [k]: e.target.value }))

  const setUser = (k: keyof typeof form.user) =>
    (e: React.ChangeEvent<HTMLInputElement>) =>
      setForm(f => ({ ...f, user: { ...f.user, [k]: e.target.value } }))

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setError('')
    try {
      const payload = {
        ...form,
        accountsEmail: form.accountsEmail || undefined,
        companyAddress: form.companyAddress || undefined,
        erpTenantId: form.erpTenantId || undefined,
        vatNumber: form.vatNumber || undefined,
        tinNumber: form.tinNumber || undefined,
      } as Parameters<typeof registerOrg>[0]
      const res = await registerOrg(payload)
      const apiKey = (res.data as { apiKey?: string }).apiKey ?? ''
      onCreated(apiKey, form.name)
    } catch (err: any) {
      setError(err.response?.data?.error ?? err.response?.data?.message ?? 'Failed to create organisation.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 640 }}>
        <div className="modal-header">
          <span className="modal-title">New Organisation</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSave}>
          <div className="grid-2">
            <div className="form-group">
              <label>Name *</label>
              <input value={form.name} onChange={set('name')} required />
            </div>
            <div className="form-group">
              <label>Slug *</label>
              <input value={form.slug} onChange={set('slug')} required placeholder="lowercase, no spaces" />
            </div>
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>Sender Email *</label>
              <input type="email" value={form.senderEmail} onChange={set('senderEmail')} required />
            </div>
            <div className="form-group">
              <label>Sender Display Name *</label>
              <input value={form.senderDisplayName} onChange={set('senderDisplayName')} required />
            </div>
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>Accounts Email</label>
              <input type="email" value={form.accountsEmail} onChange={set('accountsEmail')} />
            </div>
            <div className="form-group">
              <label>Primary ERP Source</label>
              <select value={form.primaryErpSource} onChange={set('primaryErpSource')}>
                {['ODOO', 'SAGE_INTACCT', 'QUICKBOOKS_ONLINE', 'DYNAMICS_365', 'GENERIC_API'].map(o => (
                  <option key={o}>{o}</option>
                ))}
              </select>
            </div>
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>VAT Number</label>
              <input value={form.vatNumber} onChange={set('vatNumber')} />
            </div>
            <div className="form-group">
              <label>TIN Number</label>
              <input value={form.tinNumber} onChange={set('tinNumber')} />
            </div>
          </div>
          <div className="form-group">
            <label>Delivery Mode</label>
            <select value={form.deliveryMode} onChange={set('deliveryMode')}>
              <option value="EMAIL">Email only</option>
              <option value="AS4">PEPPOL AS4 only</option>
              <option value="BOTH">Email + AS4</option>
            </select>
          </div>
          <hr style={{ margin: '12px 0' }} />
          <div className="text-sm text-muted mb-2">Primary contact</div>
          <div className="grid-2">
            <div className="form-group">
              <label>First Name *</label>
              <input value={form.user.firstName} onChange={setUser('firstName')} required />
            </div>
            <div className="form-group">
              <label>Last Name *</label>
              <input value={form.user.lastName} onChange={setUser('lastName')} required />
            </div>
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>Contact Email *</label>
              <input type="email" value={form.user.emailAddress} onChange={setUser('emailAddress')} required />
            </div>
            <div className="form-group">
              <label>Job Title</label>
              <input value={form.user.jobTitle} onChange={setUser('jobTitle')} />
            </div>
          </div>
          <div className="flex gap-2 mt-4">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? <span className="spinner" /> : 'Create'}
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  )
}

function ApiKeyReveal({ apiKey, orgName, onClose }: { apiKey: string; orgName: string; onClose: () => void }) {
  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 480 }}>
        <div className="modal-header">
          <span className="modal-title">Organisation created</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        <p className="text-sm">
          <strong>{orgName}</strong> has been registered. Copy the API key now — it is shown <em>once only</em>.
        </p>
        <div style={{
          background: '#f8fafc', border: '1px solid #cbd5e1',
          borderRadius: 8, padding: 12, marginTop: 12, fontFamily: 'monospace',
          fontSize: 13, wordBreak: 'break-all'
        }}>{apiKey || '(API key was not returned)'}</div>
        <div className="flex gap-2 mt-4">
          <button className="btn btn-primary" onClick={() => { navigator.clipboard?.writeText(apiKey); }}>Copy</button>
          <button className="btn btn-secondary" onClick={onClose}>Done</button>
        </div>
      </div>
    </div>
  )
}

function EditOrgModal({ org, onClose, onSaved }: {
  org: Organization
  onClose: () => void
  onSaved: () => void
}) {
  const [form, setForm] = useState({
    name: org.name ?? '',
    slug: org.slug ?? '',
    senderEmail: org.senderEmail ?? '',
    senderDisplayName: org.senderDisplayName ?? '',
    accountsEmail: org.accountsEmail ?? '',
    companyAddress: org.companyAddress ?? '',
    primaryErpSource: org.primaryErpSource ?? '',
    erpTenantId: org.erpTenantId ?? '',
    vatNumber: org.vatNumber ?? '',
    tinNumber: org.tinNumber ?? '',
    peppolParticipantId: org.peppolParticipantId ?? '',
    deliveryMode: (org.deliveryMode ?? 'EMAIL') as DeliveryMode,
    status: (org.status ?? 'ACTIVE') as OrgStatus,
  })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const set = (k: keyof typeof form) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setForm(f => ({ ...f, [k]: e.target.value }))

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setError('')
    try {
      await updateOrg(org.id, form)
      onSaved()
    } catch (err: any) {
      setError(err.response?.data?.error ?? err.response?.data?.message ?? 'Failed to save changes.')
    } finally {
      setSaving(false)
    }
  }

  const slugChanged = form.slug !== org.slug

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 640 }}>
        <div className="modal-header">
          <span className="modal-title">Edit Organisation</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSave}>
          <div className="grid-2">
            <div className="form-group">
              <label>Name *</label>
              <input value={form.name} onChange={set('name')} required />
            </div>
            <div className="form-group">
              <label>Slug *</label>
              <input value={form.slug} onChange={set('slug')} required />
              {slugChanged && (
                <small style={{ color: '#b45309' }}>
                  Changing the slug invalidates URLs already wired to <code>{org.slug}</code>.
                </small>
              )}
            </div>
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>Sender Email *</label>
              <input type="email" value={form.senderEmail} onChange={set('senderEmail')} required />
            </div>
            <div className="form-group">
              <label>Sender Display Name *</label>
              <input value={form.senderDisplayName} onChange={set('senderDisplayName')} required />
            </div>
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>Accounts Email</label>
              <input type="email" value={form.accountsEmail} onChange={set('accountsEmail')} />
            </div>
            <div className="form-group">
              <label>Primary ERP Source</label>
              <select value={form.primaryErpSource} onChange={set('primaryErpSource')}>
                <option value="">— None —</option>
                {['ODOO', 'SAGE_INTACCT', 'QUICKBOOKS_ONLINE', 'DYNAMICS_365', 'GENERIC_API'].map(o => (
                  <option key={o}>{o}</option>
                ))}
              </select>
            </div>
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>ERP Tenant ID</label>
              <input value={form.erpTenantId} onChange={set('erpTenantId')} />
            </div>
            <div className="form-group">
              <label>Delivery Mode</label>
              <select value={form.deliveryMode} onChange={set('deliveryMode')}>
                <option value="EMAIL">Email only</option>
                <option value="AS4">PEPPOL AS4 only</option>
                <option value="BOTH">Email + AS4</option>
              </select>
            </div>
          </div>
          <div className="form-group">
            <label>Company Address</label>
            <textarea value={form.companyAddress} onChange={set('companyAddress')} rows={2} />
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>VAT Number</label>
              <input value={form.vatNumber} onChange={set('vatNumber')} />
            </div>
            <div className="form-group">
              <label>TIN Number</label>
              <input value={form.tinNumber} onChange={set('tinNumber')} />
            </div>
          </div>
          <div className="form-group">
            <label>PEPPOL Participant ID</label>
            <input value={form.peppolParticipantId} onChange={set('peppolParticipantId')}
                   placeholder="Auto-derived from VAT/TIN if left blank" />
          </div>
          <div className="form-group">
            <label>Status</label>
            <select value={form.status} onChange={set('status')}>
              <option value="ACTIVE">ACTIVE</option>
              <option value="SUSPENDED">SUSPENDED</option>
              <option value="DEACTIVATED">DEACTIVATED</option>
            </select>
          </div>
          <div className="flex gap-2 mt-4">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? <span className="spinner" /> : 'Save'}
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  )
}

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

function PeppolOnboardModal({ org, onClose, onSaved }: {
  org: Organization
  onClose: () => void
  onSaved: () => void
}) {
  const [form, setForm] = useState({
    peppolParticipantId: org.peppolParticipantId ?? '',
    deliveryMode: (org.deliveryMode === 'AS4' || org.deliveryMode === 'BOTH' ? org.deliveryMode : 'AS4') as 'AS4' | 'BOTH',
    participantName: org.name ?? '',
    endpointUrl: '',
    simplifiedHttpDelivery: true,
  })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)

  const set = (k: keyof typeof form) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm(f => ({ ...f, [k]: e.target.value }))

  const handleOnboard = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setError('')
    try {
      await adminPeppolOnboard(org.id, form)
      setSuccess(true)
      onSaved()
    } catch (err: any) {
      setError(err.response?.data?.message ?? err.response?.data ?? 'Onboarding failed.')
    } finally {
      setSaving(false)
    }
  }

  const alreadyOnPeppol = org.deliveryMode === 'AS4' || org.deliveryMode === 'BOTH'

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 520 }}>
        <div className="modal-header">
          <span className="modal-title">PEPPOL Onboarding — {org.name}</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        {success && <div className="alert alert-success">PEPPOL onboarded successfully!</div>}
        {error && <div className="alert alert-error">{error}</div>}
        <div className="mb-4" style={{ background: '#f8fafc', borderRadius: 8, padding: 12, fontSize: 13 }}>
          <div><span className="text-muted">Current delivery mode:</span> <strong>{org.deliveryMode}</strong></div>
          <div><span className="text-muted">PEPPOL participant ID:</span> <strong>{org.peppolParticipantId || '—'}</strong></div>
        </div>
        {alreadyOnPeppol ? (
          <>
            <p className="text-sm text-muted">This organisation is already PEPPOL-enabled. Use the <strong>Edit</strong> dialog to modify settings.</p>
            <div className="flex gap-2 mt-4">
              <button type="button" className="btn btn-secondary" onClick={onClose}>Close</button>
            </div>
          </>
        ) : (
          <form onSubmit={handleOnboard}>
            <div className="grid-2">
              <div className="form-group">
                <label>Delivery Mode</label>
                <select value={form.deliveryMode} onChange={set('deliveryMode')}>
                  <option value="AS4">PEPPOL AS4 only</option>
                  <option value="BOTH">Email + AS4</option>
                </select>
              </div>
              <div className="form-group">
                <label>Participant ID</label>
                <input value={form.peppolParticipantId} onChange={set('peppolParticipantId')}
                       placeholder="Auto-derived from VAT/TIN" />
              </div>
            </div>
            <div className="form-group">
              <label>Participant Name *</label>
              <input value={form.participantName} onChange={set('participantName')} required />
            </div>
            <div className="form-group">
              <label>Gateway Endpoint URL *</label>
              <input value={form.endpointUrl} onChange={set('endpointUrl')}
                     placeholder="https://ap.invoicedirect.biz/peppol/as4/receive" required />
            </div>
            <div className="form-group">
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
                <input type="checkbox" style={{ width: 'auto' }}
                       checked={form.simplifiedHttpDelivery}
                       onChange={e => setForm(f => ({ ...f, simplifiedHttpDelivery: e.target.checked }))} />
                Use simplified HTTP delivery (instead of full AS4)
              </label>
            </div>
            <div className="flex gap-2 mt-4">
              <button type="submit" className="btn btn-primary" disabled={saving}>
                {saving ? <span className="spinner" /> : 'Onboard to PEPPOL'}
              </button>
              <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
            </div>
          </form>
        )}
      </div>
    </div>
  )
}

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100]

export default function OrganizationsPage() {
  const [orgs, setOrgs] = useState<Organization[]>([])
  const [profiles, setProfiles] = useState<RateProfile[]>([])
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<Organization | null>(null)
  const [editing, setEditing] = useState<Organization | null>(null)
  const [peppolOrg, setPeppolOrg] = useState<Organization | null>(null)
  const [creating, setCreating] = useState(false)
  const [revealedKey, setRevealedKey] = useState<{ apiKey: string; orgName: string } | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [sort, setSort] = useState<'name' | 'slug' | 'status' | 'createdAt'>('name')
  const [dir, setDir] = useState<'asc' | 'desc'>('asc')
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(0)

  // Debounce search input -> committed search term
  const debounceTimer = useRef<number | null>(null)
  useEffect(() => {
    if (debounceTimer.current) window.clearTimeout(debounceTimer.current)
    debounceTimer.current = window.setTimeout(() => {
      setSearch(searchInput.trim())
      setPage(0)
    }, 300)
    return () => { if (debounceTimer.current) window.clearTimeout(debounceTimer.current) }
  }, [searchInput])

  const load = useCallback(() => {
    setLoading(true)
    Promise.all([
      listOrgsPaged({ page, size, search: search || undefined, sort, dir }),
      listRateProfiles(),
    ])
      .then(([o, p]) => {
        setOrgs(o.data.content ?? [])
        setTotalElements(o.data.totalElements ?? 0)
        setTotalPages(o.data.totalPages ?? 0)
        setProfiles(p.data ?? [])
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [page, size, search, sort, dir])

  useEffect(load, [load])

  const handleDeactivate = async (o: Organization) => {
    if (!window.confirm(`Deactivate "${o.name}"? It can be reactivated later from the Edit dialog.`)) return
    setBusyId(o.id)
    try { await deactivateOrg(o.id); load() }
    catch { /* surface via row state next refresh */ }
    finally { setBusyId(null) }
  }

  const toggleSort = (column: typeof sort) => {
    if (sort === column) setDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSort(column); setDir('asc') }
    setPage(0)
  }

  const sortIndicator = (column: typeof sort) => sort === column ? (dir === 'asc' ? ' ▲' : ' ▼') : ''

  const rangeLabel = useMemo(() => {
    if (totalElements === 0) return '0 of 0'
    const from = page * size + 1
    const to = Math.min((page + 1) * size, totalElements)
    return `${from}–${to} of ${totalElements}`
  }, [page, size, totalElements])

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Organizations</span>
        <div className="flex gap-2">
          <button className="btn btn-primary btn-sm" onClick={() => setCreating(true)}>
            <Plus size={14} /> New Organisation
          </button>
          <button className="btn btn-secondary btn-sm" onClick={load}><RefreshCw size={14} /> Refresh</button>
        </div>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Organizations</h2>
          <p>All registered organizations on the platform</p>
        </div>
        <div className="card">
          <div className="flex gap-3 mb-4" style={{ flexWrap: 'wrap', alignItems: 'center' }}>
            <input
              style={{ maxWidth: 280 }}
              placeholder="Search by name, slug, or sender email…"
              value={searchInput}
              onChange={e => setSearchInput(e.target.value)}
            />
            <label className="text-sm text-muted" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              Page size
              <select
                style={{ padding: '6px 10px', border: '1px solid #e2e8f0', borderRadius: 7, fontSize: 13 }}
                value={size}
                onChange={e => { setSize(Number(e.target.value)); setPage(0) }}
              >
                {PAGE_SIZE_OPTIONS.map(n => <option key={n} value={n}>{n}</option>)}
              </select>
            </label>
            <span className="text-sm text-muted" style={{ marginLeft: 'auto' }}>{rangeLabel}</span>
          </div>
          {loading ? (
            <div className="loading-center"><span className="spinner" /></div>
          ) : orgs.length === 0 ? (
            <div className="empty-state"><Building2 size={32} /><p>No organizations found</p></div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th onClick={() => toggleSort('name')} style={{ cursor: 'pointer' }}>Name{sortIndicator('name')}</th>
                    <th onClick={() => toggleSort('slug')} style={{ cursor: 'pointer' }}>Slug{sortIndicator('slug')}</th>
                    <th>Sender Email</th>
                    <th>ERP</th>
                    <th>Rate Profile</th>
                    <th onClick={() => toggleSort('status')} style={{ cursor: 'pointer' }}>Status{sortIndicator('status')}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {orgs.map(o => {
                    const profile = profiles.find(p => p.id === o.rateProfileId)
                    return (
                      <tr key={o.id}>
                        <td>
                          <Link to={`/admin/organizations/${o.id}/customers`}
                                style={{ fontWeight: 500, color: '#0f172a', textDecoration: 'none' }}>
                            {o.name}
                          </Link>
                          <div className="text-sm text-muted">{o.id?.slice(0, 8)}…</div>
                        </td>
                        <td className="text-muted">{o.slug}</td>
                        <td>{o.senderEmail}</td>
                        <td className="text-sm">{o.primaryErpSource ?? '—'}</td>
                        <td>{profile ? <span className="badge badge-blue">{profile.name}</span> : <span className="text-muted text-sm">None</span>}</td>
                        <td><span className={`badge ${o.status === 'ACTIVE' ? 'badge-green' : 'badge-red'}`}>{o.status}</span></td>
                        <td>
                          <div className="flex gap-2">
                            <Link to={`/admin/organizations/${o.id}/customers`} className="btn btn-secondary btn-sm" title="View customers">
                              <Users size={14} /> Customers
                            </Link>
                            <button className="btn btn-secondary btn-sm" onClick={() => setEditing(o)} title="Edit">
                              <Pencil size={14} /> Edit
                            </button>
                            <button className="btn btn-secondary btn-sm" onClick={() => setPeppolOrg(o)} title="PEPPOL">
                              <Network size={14} /> PEPPOL
                            </button>
                            <button className="btn btn-secondary btn-sm" onClick={() => setSelected(o)}>Assign Plan</button>
                            <button
                              className="btn btn-secondary btn-sm"
                              style={{ color: '#b91c1c' }}
                              disabled={busyId === o.id || o.status === 'DEACTIVATED'}
                              onClick={() => handleDeactivate(o)}
                              title={o.status === 'DEACTIVATED' ? 'Already deactivated' : 'Deactivate'}
                            >
                              {busyId === o.id ? <span className="spinner" /> : <><Power size={14} /> Deactivate</>}
                            </button>
                          </div>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
          {!loading && totalPages > 1 && (
            <div className="flex gap-2 mt-4" style={{ alignItems: 'center', justifyContent: 'flex-end' }}>
              <span className="text-sm text-muted">Page {page + 1} of {totalPages}</span>
              <button
                className="btn btn-secondary btn-sm"
                disabled={page === 0}
                onClick={() => setPage(p => Math.max(0, p - 1))}
              >
                <ChevronLeft size={14} /> Prev
              </button>
              <button
                className="btn btn-secondary btn-sm"
                disabled={page >= totalPages - 1}
                onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
              >
                Next <ChevronRight size={14} />
              </button>
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
      {editing && (
        <EditOrgModal
          org={editing}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); load() }}
        />
      )}
      {peppolOrg && (
        <PeppolOnboardModal
          org={peppolOrg}
          onClose={() => setPeppolOrg(null)}
          onSaved={() => { setPeppolOrg(null); load() }}
        />
      )}
      {creating && (
        <CreateOrgModal
          onClose={() => setCreating(false)}
          onCreated={(apiKey, orgName) => {
            setCreating(false)
            setRevealedKey({ apiKey, orgName })
            load()
          }}
        />
      )}
      {revealedKey && (
        <ApiKeyReveal
          apiKey={revealedKey.apiKey}
          orgName={revealedKey.orgName}
          onClose={() => setRevealedKey(null)}
        />
      )}
    </>
  )
}
