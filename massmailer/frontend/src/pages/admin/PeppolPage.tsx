import { useEffect, useState } from 'react'
import {
  listAccessPoints, registerAccessPoint, updateAccessPointStatus,
  getPeppolDeliveries, getPeppolInbox, getPeppolHealth,
  listParticipantLinks, createParticipantLink, deleteParticipantLink,
  adminPeppolOnboard, listOrgsPaged,
  adminPeppolUploadCert, adminPeppolRotateCert,
  adminPeppolGetActiveCert, adminPeppolListCerts
} from '../../api/client'
import { Network, Plus, X, RefreshCw, CheckCircle, XCircle, Link, Building2, Shield, ShieldOff } from 'lucide-react'
import type { AccessPoint, PeppolDelivery, PeppolInboundDoc, PeppolHealth, ParticipantLink, Organization, PeppolCertificate, PeppolCertUploadRequest, PeppolActiveCertResponse } from '../../types'
import { useAuth } from '../../context/AuthContext'

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = { ACTIVE: 'badge-green', SUSPENDED: 'badge-yellow', INACTIVE: 'badge-red' }
  return <span className={`badge ${map[status] ?? 'badge-gray'}`}>{status}</span>
}

function DeliveryBadge({ status }: { status: string }) {
  const map: Record<string, string> = { DELIVERED: 'badge-green', FAILED: 'badge-red', PENDING: 'badge-gray', ACKNOWLEDGED: 'badge-green', REJECTED: 'badge-red' }
  return <span className={`badge ${map[status] ?? 'badge-gray'}`}>{status}</span>
}

function RegisterAPModal({ apiKey, onClose, onSave }: { apiKey?: string; onClose: () => void; onSave: () => void }) {
  const [form, setForm] = useState({
    participantId: '', participantName: '', role: 'RECEIVER' as const,
    endpointUrl: '', simplifiedHttpDelivery: true,
    deliveryAuthToken: '', organizationId: ''
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)
    try {
      await registerAccessPoint({
        ...form,
        organizationId: form.organizationId || undefined,
        deliveryAuthToken: form.deliveryAuthToken || undefined,
      }, apiKey)
      onSave()
    } catch (err: any) {
      setError(err.response?.data?.message ?? String(err.response?.data) ?? 'Registration failed.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 520 }}>
        <div className="modal-header">
          <span className="modal-title">Register Access Point</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="grid-2">
            <div className="form-group">
              <label>Participant ID *</label>
              <input value={form.participantId} onChange={set('participantId')} placeholder="0190:ZW123456789" required />
            </div>
            <div className="form-group">
              <label>Role *</label>
              <select value={form.role} onChange={set('role')}>
                <option value="GATEWAY">GATEWAY (C2)</option>
                <option value="RECEIVER">RECEIVER (C3)</option>
                <option value="SENDER">SENDER</option>
              </select>
            </div>
          </div>
          <div className="form-group">
            <label>Participant Name *</label>
            <input value={form.participantName} onChange={set('participantName')} placeholder="Acme Corporation AP" required />
          </div>
          <div className="form-group">
            <label>Endpoint URL *</label>
            <input value={form.endpointUrl} onChange={set('endpointUrl')} placeholder="https://erp.acme.co.zw/peppol/receive" required />
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>Organization ID (optional)</label>
              <input value={form.organizationId} onChange={set('organizationId')} placeholder="UUID" />
            </div>
            <div className="form-group">
              <label>Auth Token (optional)</label>
              <input type="password" value={form.deliveryAuthToken} onChange={set('deliveryAuthToken')} placeholder="Bearer token" />
            </div>
          </div>
          <div className="form-group">
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
              <input
                type="checkbox" style={{ width: 'auto' }}
                checked={form.simplifiedHttpDelivery}
                onChange={e => setForm(f => ({ ...f, simplifiedHttpDelivery: e.target.checked }))}
              />
              Use simplified HTTP delivery (instead of full AS4)
            </label>
          </div>
          <div className="flex gap-2 mt-4">
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? <span className="spinner" /> : 'Register'}
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  )
}

const PARTICIPANT_ID_RE = /^[^:]+:.+$/

function LinkCustomerModal({ onClose, onSave }: { onClose: () => void; onSave: () => void }) {
  const { session } = useAuth()
  const [form, setForm] = useState({
    organizationId: session?.orgId ?? '',
    customerEmail: '',
    participantId: '',
    receiverAccessPointId: '',
    preferredChannel: 'PEPPOL' as 'PEPPOL' | 'EMAIL',
  })
  const [aps, setAps] = useState<AccessPoint[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [pidError, setPidError] = useState('')

  useEffect(() => {
    listAccessPoints().then(r => setAps(r.data ?? [])).catch(() => setAps([]))
  }, [])

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const validatePid = (val: string) => {
    if (val && !PARTICIPANT_ID_RE.test(val)) setPidError('Format must be {scheme}:{value}, e.g. 0190:ZW123456789')
    else setPidError('')
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!PARTICIPANT_ID_RE.test(form.participantId)) { setPidError('Format must be {scheme}:{value}'); return }
    if (!form.organizationId) { setError('Organization ID is required'); return }
    setLoading(true)
    try {
      await createParticipantLink({
        organizationId: form.organizationId,
        customerEmail: form.customerEmail,
        participantId: form.participantId,
        receiverAccessPointId: form.receiverAccessPointId,
        preferredChannel: form.preferredChannel,
      })
      onSave()
    } catch (err: any) {
      setError(err.response?.data?.message ?? String(err.response?.data) ?? 'Failed to create link.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 480 }}>
        <div className="modal-header">
          <span className="modal-title">Link Customer</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Organization ID *</label>
            <input value={form.organizationId} onChange={set('organizationId')} placeholder="UUID of the sending organization" required />
          </div>
          <div className="form-group">
            <label>Customer Email *</label>
            <input value={form.customerEmail} onChange={set('customerEmail')} placeholder="customer@example.com" type="email" required />
          </div>
          <div className="form-group">
            <label>Participant ID *</label>
            <input
              value={form.participantId}
              onChange={e => { set('participantId')(e); validatePid(e.target.value) }}
              placeholder="0190:ZW123456789"
              required
            />
            {pidError && <span className="text-sm" style={{ color: '#dc2626' }}>{pidError}</span>}
          </div>
          <div className="form-group">
            <label>Access Point *</label>
            <select value={form.receiverAccessPointId} onChange={set('receiverAccessPointId')} required>
              <option value="">— Select AP —</option>
              {aps.map(ap => (
                <option key={ap.id} value={ap.id}>{ap.participantName} ({ap.participantId})</option>
              ))}
            </select>
          </div>
          <div className="form-group">
            <label>Channel</label>
            <div className="flex gap-2">
              {(['PEPPOL', 'EMAIL'] as const).map(ch => (
                <button
                  key={ch} type="button"
                  className={`btn btn-sm ${form.preferredChannel === ch ? 'btn-primary' : 'btn-secondary'}`}
                  onClick={() => setForm(f => ({ ...f, preferredChannel: ch }))}
                >{ch}</button>
              ))}
            </div>
          </div>
          <div className="flex gap-2 mt-4">
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? <span className="spinner" /> : 'Link'}
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  )
}

function ParticipantLinksTab({ orgId }: { orgId: string }) {
  const [links, setLinks] = useState<ParticipantLink[]>([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null)

  const load = () => {
    if (!orgId) { setLinks([]); setLoading(false); return }
    setLoading(true)
    listParticipantLinks(orgId).then(r => setLinks(r.data ?? [])).catch(() => setLinks([])).finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [orgId])

  const handleDelete = async (id: string) => {
    try { await deleteParticipantLink(id); load() } catch { /* ignore */ }
    setConfirmDeleteId(null)
  }

  return (
    <>
      <div className="card">
        <div className="card-header">
          <span className="card-title">Participant Links</span>
          <button className="btn btn-primary btn-sm" onClick={() => setShowModal(true)}><Plus size={14} /> Link Customer</button>
        </div>
        {loading ? (
          <div className="loading-center"><span className="spinner" /></div>
        ) : links.length === 0 ? (
          <div className="empty-state"><Link size={32} /><p>{orgId ? 'No participant links yet' : 'Enter an Organization ID above to search.'}</p></div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Customer Email</th>
                  <th>Participant ID</th>
                  <th>AP Name</th>
                  <th>Channel</th>
                  <th>Created At</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {links.map(link => (
                  <tr key={link.id}>
                    <td>{link.customerEmail}</td>
                    <td style={{ fontFamily: 'monospace', fontSize: 13 }}>{link.participantId}</td>
                    <td>{link.receiverApName}</td>
                    <td><span className={`badge ${link.preferredChannel === 'PEPPOL' ? 'badge-blue' : 'badge-gray'}`}>{link.preferredChannel}</span></td>
                    <td className="text-sm text-muted">{link.createdAt ? new Date(link.createdAt).toLocaleString() : '—'}</td>
                    <td>
                      {confirmDeleteId === link.id ? (
                        <span className="flex gap-1" style={{ alignItems: 'center' }}>
                          <span className="text-sm" style={{ color: '#dc2626' }}>Delete?</span>
                          <button className="btn btn-sm" style={{ background: '#dc2626', color: '#fff' }} onClick={() => handleDelete(link.id)}>Confirm</button>
                          <button className="btn btn-secondary btn-sm" onClick={() => setConfirmDeleteId(null)}>Cancel</button>
                        </span>
                      ) : (
                        <button className="btn btn-secondary btn-sm" onClick={() => setConfirmDeleteId(link.id)}>Delete</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {showModal && <LinkCustomerModal onClose={() => setShowModal(false)} onSave={() => { setShowModal(false); load() }} />}
    </>
  )
}

function OnboardOrgModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [orgs, setOrgs] = useState<Organization[]>([])
  const [selectedOrgId, setSelectedOrgId] = useState('')
  const [form, setForm] = useState({
    peppolParticipantId: '',
    deliveryMode: 'AS4' as 'AS4' | 'BOTH',
    participantName: '',
    endpointUrl: '',
    simplifiedHttpDelivery: true,
  })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    listOrgsPaged({ size: 200 }).then(r => setOrgs(r.data.content ?? [])).catch(() => {})
  }, [])

  const selectedOrg = orgs.find(o => o.id === selectedOrgId)

  const handleOrgChange = (orgId: string) => {
    setSelectedOrgId(orgId)
    const org = orgs.find(o => o.id === orgId)
    if (org) {
      setForm(f => ({
        ...f,
        peppolParticipantId: org.peppolParticipantId ?? '',
        participantName: org.name ?? '',
      }))
    }
  }

  const set = (k: keyof typeof form) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm(f => ({ ...f, [k]: e.target.value }))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!selectedOrgId) return
    setSaving(true)
    setError('')
    try {
      await adminPeppolOnboard(selectedOrgId, form)
      onSaved()
    } catch (err: any) {
      setError(err.response?.data?.message ?? err.response?.data ?? 'Onboarding failed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 520 }}>
        <div className="modal-header">
          <span className="modal-title">Quick Onboard — PEPPOL</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Organization *</label>
            <select value={selectedOrgId} onChange={e => handleOrgChange(e.target.value)} required>
              <option value="">— Select org —</option>
              {orgs.map(o => (
                <option key={o.id} value={o.id}>
                  {o.name} ({o.slug}) — {o.deliveryMode}{o.peppolParticipantId ? ` — ${o.peppolParticipantId}` : ''}
                </option>
              ))}
            </select>
          </div>
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
              Use simplified HTTP delivery
            </label>
          </div>
          <div className="flex gap-2 mt-4">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? <span className="spinner" /> : 'Onboard'}
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  )
}

function CertModal({ mode, orgId, onClose, onSaved }: { mode: 'upload' | 'rotate'; orgId: string; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({ certificatePem: '', privateKeyPem: '', description: '' })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLTextAreaElement | HTMLInputElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!orgId.trim()) { setError('Enter an Organization ID in the search field first.'); return }
    setSaving(true)
    setError('')
    try {
      const data: PeppolCertUploadRequest = {
        certificatePem: form.certificatePem.trim(),
        privateKeyPem: form.privateKeyPem.trim(),
        description: form.description.trim() || undefined
      }
      if (mode === 'rotate') {
        await adminPeppolRotateCert(orgId.trim(), data)
      } else {
        await adminPeppolUploadCert(orgId.trim(), data)
      }
      onSaved()
    } catch (err: any) {
      setError(err.response?.data?.message ?? err.response?.data ?? 'Operation failed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 600 }}>
        <div className="modal-header">
          <span className="modal-title">{mode === 'rotate' ? 'Rotate Certificate' : 'Upload Certificate'}</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Certificate (PEM) *</label>
            <textarea
              value={form.certificatePem}
              onChange={set('certificatePem')}
              placeholder="-----BEGIN CERTIFICATE-----\n..."
              rows={6} required
              style={{ fontFamily: 'monospace', fontSize: 12 }}
            />
          </div>
          <div className="form-group">
            <label>Private Key (PEM) *</label>
            <textarea
              value={form.privateKeyPem}
              onChange={set('privateKeyPem')}
              placeholder="-----BEGIN PRIVATE KEY-----\n..."
              rows={6} required
              style={{ fontFamily: 'monospace', fontSize: 12 }}
            />
          </div>
          <div className="form-group">
            <label>Description (optional)</label>
            <input value={form.description} onChange={set('description')} placeholder="e.g. Production cert 2026" />
          </div>
          <div className="flex gap-2 mt-4">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? <span className="spinner" /> : mode === 'rotate' ? 'Rotate' : 'Upload'}
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function PeppolPage() {
  const { session } = useAuth()
  const [tab, setTab] = useState('access-points')
  const [accessPoints, setAccessPoints] = useState<AccessPoint[]>([])
  const [deliveries, setDeliveries] = useState<PeppolDelivery[]>([])
  const [inbox, setInbox] = useState<PeppolInboundDoc[]>([])
  const [health, setHealth] = useState<PeppolHealth | null>(null)
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [showOnboard, setShowOnboard] = useState(false)
  const [orgIdFilter, setOrgIdFilter] = useState('')
  const [certs, setCerts] = useState<PeppolCertificate[]>([])
  const [activeCert, setActiveCert] = useState<PeppolActiveCertResponse | null>(null)
  const [certsOrgId, setCertsOrgId] = useState('')
  const [showCertModal, setShowCertModal] = useState(false)
  const [certModalMode, setCertModalMode] = useState<'upload' | 'rotate'>('upload')

  const loadAccessPoints = () => {
    setLoading(true)
    listAccessPoints().then(r => setAccessPoints(r.data ?? [])).catch(() => setAccessPoints([])).finally(() => setLoading(false))
  }

  const loadDeliveries = () => {
    if (!orgIdFilter.trim()) return
    setLoading(true)
    getPeppolDeliveries(orgIdFilter.trim()).then(r => setDeliveries(r.data ?? [])).catch(() => setDeliveries([])).finally(() => setLoading(false))
  }

  const loadInbox = () => {
    setLoading(true)
    getPeppolInbox(orgIdFilter.trim() ? { organizationId: orgIdFilter.trim() } : {})
      .then(r => setInbox(r.data ?? [])).catch(() => setInbox([])).finally(() => setLoading(false))
  }

  const loadHealth = () => {
    getPeppolHealth().then(r => setHealth(r.data)).catch(() => setHealth({ status: 'DOWN' }))
  }

  const loadCerts = (orgId: string) => {
    if (!orgId.trim()) { setCerts([]); setActiveCert(null); return }
    setCertsOrgId(orgId.trim())
    adminPeppolListCerts(orgId.trim()).then(r => setCerts(r.data ?? [])).catch(() => setCerts([]))
    adminPeppolGetActiveCert(orgId.trim()).then(r => setActiveCert(r.data)).catch(() => setActiveCert(null))
  }

  useEffect(() => { loadHealth(); loadAccessPoints() }, [])

  useEffect(() => {
    if (tab === 'access-points') loadAccessPoints()
    else if (tab === 'deliveries') loadDeliveries()
    else if (tab === 'inbox') loadInbox()
    else if (tab === 'certificates') loadCerts(certsOrgId)
  }, [tab])

  const handleSuspend = async (ap: AccessPoint) => {
    const newStatus = ap.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
    try { await updateAccessPointStatus(ap.id, newStatus); loadAccessPoints() } catch { /* ignore */ }
  }

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">PEPPOL eRegistry</span>
        <div className="flex gap-2" style={{ alignItems: 'center' }}>
          {health && (
            <span className={`badge ${health.status === 'UP' ? 'badge-green' : 'badge-red'}`}>AP {health.status}</span>
          )}
          <button className="btn btn-secondary btn-sm" onClick={() => { loadHealth(); if (tab === 'access-points') loadAccessPoints() }}>
            <RefreshCw size={14} />
          </button>
        </div>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>PEPPOL eRegistry</h2>
          <p>Manage Access Points, participant links, delivery history and inbound documents</p>
        </div>

        <div className="flex gap-2 mb-4">
          {['access-points', 'participant-links', 'deliveries', 'inbox', 'certificates', 'onboard'].map(t => (
            <button key={t} className={`btn btn-sm ${tab === t ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setTab(t)}>
              {t === 'access-points' ? 'Access Points' : t === 'participant-links' ? 'Participant Links' : t === 'deliveries' ? 'Delivery History' : t === 'inbox' ? 'Inbound Inbox' : t === 'certificates' ? 'Certificates' : 'Onboard Org'}
            </button>
          ))}
        </div>

        {(tab === 'deliveries' || tab === 'inbox' || tab === 'participant-links') && (
          <div className="flex gap-2 mb-4">
            <input style={{ maxWidth: 340 }} placeholder="Organization ID (UUID) to filter…" value={orgIdFilter} onChange={e => setOrgIdFilter(e.target.value)} />
            <button className="btn btn-secondary btn-sm" onClick={tab === 'deliveries' ? loadDeliveries : loadInbox}>Search</button>
          </div>
        )}

        {tab === 'access-points' && (
          <div className="card">
            <div className="card-header">
              <span className="card-title">Registered Access Points</span>
              <button className="btn btn-primary btn-sm" onClick={() => setShowModal(true)}><Plus size={14} /> Register AP</button>
            </div>
            {loading ? (
              <div className="loading-center"><span className="spinner" /></div>
            ) : accessPoints.length === 0 ? (
              <div className="empty-state"><Network size={32} /><p>No access points registered</p></div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Participant ID</th><th>Name</th><th>Role</th><th>Endpoint</th><th>HTTP</th><th>Status</th><th></th></tr></thead>
                  <tbody>
                    {accessPoints.map(ap => (
                      <tr key={ap.id}>
                        <td style={{ fontFamily: 'monospace', fontSize: 13 }}>{ap.participantId}</td>
                        <td>{ap.participantName}</td>
                        <td><span className="badge badge-blue">{ap.role}</span></td>
                        <td className="text-sm text-muted" style={{ maxWidth: 200, wordBreak: 'break-all' }}>{ap.endpointUrl}</td>
                        <td>{ap.simplifiedHttpDelivery ? <CheckCircle size={14} color="#16a34a" /> : <XCircle size={14} color="#dc2626" />}</td>
                        <td><StatusBadge status={ap.status} /></td>
                        <td>
                          <button className="btn btn-secondary btn-sm" onClick={() => handleSuspend(ap)}>
                            {ap.status === 'ACTIVE' ? 'Suspend' : 'Activate'}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {tab === 'participant-links' && (
          <ParticipantLinksTab orgId={orgIdFilter} />
        )}

        {tab === 'deliveries' && (
          <div className="card">
            <div className="card-header"><span className="card-title">PEPPOL Delivery History</span></div>
            {loading ? (
              <div className="loading-center"><span className="spinner" /></div>
            ) : deliveries.length === 0 ? (
              <div className="empty-state"><p>{orgIdFilter ? 'No deliveries found for this organization.' : 'Enter an Organization ID to search.'}</p></div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Invoice #</th><th>Sender</th><th>Receiver</th><th>Status</th><th>Endpoint</th><th>Created</th></tr></thead>
                  <tbody>
                    {deliveries.map(d => (
                      <tr key={d.id}>
                        <td style={{ fontWeight: 500 }}>{d.invoiceNumber ?? '—'}</td>
                        <td className="text-sm text-muted">{d.senderParticipantId ?? '—'}</td>
                        <td className="text-sm text-muted">{d.receiverParticipantId ?? '—'}</td>
                        <td><DeliveryBadge status={d.status} /></td>
                        <td className="text-sm text-muted" style={{ maxWidth: 180, wordBreak: 'break-all' }}>{d.deliveredToEndpoint ?? '—'}</td>
                        <td className="text-sm text-muted">{d.createdAt ? new Date(d.createdAt).toLocaleString() : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {tab === 'inbox' && (
          <div className="card">
            <div className="card-header"><span className="card-title">Inbound PEPPOL Documents</span></div>
            {loading ? (
              <div className="loading-center"><span className="spinner" /></div>
            ) : inbox.length === 0 ? (
              <div className="empty-state"><p>No inbound documents found.</p></div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Invoice #</th><th>From</th><th>To</th><th>Doc Type</th><th>Hash</th><th>Received</th></tr></thead>
                  <tbody>
                    {inbox.map(doc => (
                      <tr key={doc.id}>
                        <td style={{ fontWeight: 500 }}>{doc.invoiceNumber ?? '—'}</td>
                        <td className="text-sm text-muted">{doc.senderParticipantId ?? '—'}</td>
                        <td className="text-sm text-muted">{doc.receiverParticipantId ?? '—'}</td>
                        <td className="text-sm text-muted">{doc.documentTypeId ?? '—'}</td>
                        <td className="text-sm text-muted" style={{ fontFamily: 'monospace' }}>
                          {doc.payloadHash ? doc.payloadHash.slice(0, 12) + '…' : '—'}
                        </td>
                        <td className="text-sm text-muted">{doc.receivedAt ? new Date(doc.receivedAt).toLocaleString() : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {tab === 'onboard' && (
          <div className="card">
            <div className="card-header">
              <span className="card-title">Quick Onboard</span>
              <button className="btn btn-primary btn-sm" onClick={() => setShowOnboard(true)}>
                <Building2 size={14} /> Onboard Organization
              </button>
            </div>
            <div className="empty-state">
              <Building2 size={32} />
              <p>Select an organization and configure its PEPPOL gateway in one step.</p>
              <p className="text-sm text-muted">Sets the org's participant ID, delivery mode, and registers a GATEWAY Access Point.</p>
            </div>
          </div>
        )}

        {tab === 'certificates' && (
          <div className="card">
            <div className="card-header">
              <span className="card-title">Certificate Management</span>
              <div className="flex gap-2">
                <button className="btn btn-primary btn-sm" onClick={() => { setCertModalMode('upload'); setShowCertModal(true) }}>
                  <Shield size={14} /> Upload Certificate
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => { setCertModalMode('rotate'); setShowCertModal(true) }}>
                  <RefreshCw size={14} /> Rotate
                </button>
              </div>
            </div>

            <div className="form-group mb-4" style={{ maxWidth: 400 }}>
              <label>Organization ID</label>
              <div className="flex gap-2">
                <input
                  value={certsOrgId}
                  onChange={e => setCertsOrgId(e.target.value)}
                  placeholder="Enter organization UUID"
                  style={{ flex: 1 }}
                />
                <button className="btn btn-primary btn-sm" onClick={() => loadCerts(certsOrgId)}>
                  <RefreshCw size={14} />
                </button>
              </div>
            </div>

            {activeCert && (
              <div className="mb-4" style={{ padding: '12px 16px', background: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: 8 }}>
                <div className="flex gap-2" style={{ alignItems: 'center', marginBottom: 8 }}>
                  <Shield size={16} style={{ color: '#16a34a' }} />
                  <strong style={{ color: '#166534' }}>Active Certificate</strong>
                </div>
                <div className="grid-2 text-sm" style={{ gap: 4 }}>
                  <span className="text-muted">Subject:</span><span style={{ fontFamily: 'monospace', fontSize: 12 }}>{activeCert.subjectDn}</span>
                  <span className="text-muted">Issuer:</span><span style={{ fontFamily: 'monospace', fontSize: 12 }}>{activeCert.issuerDn}</span>
                  <span className="text-muted">Serial:</span><span style={{ fontFamily: 'monospace', fontSize: 12 }}>{activeCert.serialNumber}</span>
                  <span className="text-muted">Valid:</span><span style={{ fontSize: 12 }}>{new Date(activeCert.validFrom).toLocaleDateString()} — {new Date(activeCert.validTo).toLocaleDateString()}</span>
                </div>
              </div>
            )}

            {certs.length === 0 ? (
              <div className="empty-state"><ShieldOff size={32} /><p>{certsOrgId ? 'No certificates found for this organization.' : 'Enter an Organization ID to search.'}</p></div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Subject DN</th><th>Issuer DN</th><th>Serial</th><th>Valid From</th><th>Valid To</th><th>Status</th><th>Uploaded</th></tr></thead>
                  <tbody>
                    {certs.map(c => (
                      <tr key={c.id}>
                        <td style={{ fontFamily: 'monospace', fontSize: 12, maxWidth: 200, wordBreak: 'break-all' }}>{c.subjectDn}</td>
                        <td style={{ fontFamily: 'monospace', fontSize: 12, maxWidth: 200, wordBreak: 'break-all' }}>{c.issuerDn}</td>
                        <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{c.serialNumber.length > 16 ? c.serialNumber.slice(0, 16) + '…' : c.serialNumber}</td>
                        <td className="text-sm text-muted">{new Date(c.validFrom).toLocaleDateString()}</td>
                        <td className="text-sm text-muted">{new Date(c.validTo).toLocaleDateString()}</td>
                        <td>
                          <span className={`badge ${c.status === 'ACTIVE' ? 'badge-green' : c.status === 'ROTATED' ? 'badge-yellow' : c.status === 'EXPIRED' ? 'badge-red' : 'badge-gray'}`}>{c.status}</span>
                        </td>
                        <td className="text-sm text-muted">{new Date(c.createdAt).toLocaleDateString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </div>

      {showModal && <RegisterAPModal apiKey={session?.apiKey} onClose={() => setShowModal(false)} onSave={() => { setShowModal(false); loadAccessPoints() }} />}
      {showOnboard && <OnboardOrgModal onClose={() => setShowOnboard(false)} onSaved={() => { setShowOnboard(false); loadAccessPoints() }} />}
      {showCertModal && <CertModal mode={certModalMode} orgId={certsOrgId} onClose={() => setShowCertModal(false)} onSaved={() => { setShowCertModal(false); loadCerts(certsOrgId) }} />}
    </>
  )
}
