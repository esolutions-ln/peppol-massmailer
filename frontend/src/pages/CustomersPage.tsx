import { useEffect, useState } from 'react'
import { useAuth } from '../context/AuthContext'
import { listCustomers, registerCustomer, sendPeppolInvitation, updateCustomer } from '../api/client'
import { Users, Plus, X, Upload, Save } from 'lucide-react'
import CustomerImportModal from './CustomerImportModal'
import type { CustomerContact, DeliveryMode } from '../types'
import { deriveParticipantId } from '../types'

interface ModalProps {
  orgId: string
  apiKey?: string
  onClose: () => void
  onSave: () => void
}

function Modal({ onClose, onSave, orgId, apiKey }: ModalProps) {
  const [form, setForm] = useState({
    email: '', name: '', companyName: '', erpSource: 'GENERIC_API',
    vatNumber: '', tinNumber: '',
    deliveryMode: '' as DeliveryMode | ''   // empty = inherit from org
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const participantId = deriveParticipantId(form.vatNumber, form.tinNumber)
  const needsPeppol = form.deliveryMode === 'AS4' || form.deliveryMode === 'BOTH'

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (needsPeppol && !participantId) {
      setError('AS4 delivery requires a VAT or TIN number.')
      return
    }
    setLoading(true)
    try {
      const payload = {
        ...form,
        deliveryMode: form.deliveryMode || undefined,
        vatNumber: form.vatNumber.trim() || undefined,
        tinNumber: form.tinNumber.trim() || undefined,
        peppolParticipantId: participantId ?? undefined,
      }
      await registerCustomer(orgId, payload, apiKey)
      onSave()
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Failed to register customer.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 520 }}>
        <div className="modal-header">
          <span className="modal-title">Register Customer</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Email *</label>
            <input type="email" value={form.email} onChange={set('email')} required />
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>Contact Name</label>
              <input value={form.name} onChange={set('name')} />
            </div>
            <div className="form-group">
              <label>Company Name</label>
              <input value={form.companyName} onChange={set('companyName')} />
            </div>
          </div>

          {/* Delivery mode override */}
          <div className="form-group">
            <label>Delivery Mode</label>
            <select value={form.deliveryMode} onChange={set('deliveryMode')}>
              <option value="">Inherit from organisation</option>
              <option value="EMAIL">Email only</option>
              <option value="AS4">PEPPOL AS4 only</option>
              <option value="BOTH">Email + AS4</option>
            </select>
          </div>

          {/* Zimbabwe tax identifiers — shown when AS4 is involved */}
          <div className="grid-2">
            <div className="form-group">
              <label>VAT Number {needsPeppol && !form.tinNumber.trim() ? '*' : ''}</label>
              <input value={form.vatNumber} onChange={set('vatNumber')} placeholder="e.g. 12345678" />
            </div>
            <div className="form-group">
              <label>TIN Number {needsPeppol && !form.vatNumber.trim() ? '*' : ''}</label>
              <input
                value={form.tinNumber} onChange={set('tinNumber')}
                placeholder="e.g. 1234567890"
                disabled={!!form.vatNumber.trim()}
              />
            </div>
          </div>

          {participantId && (
            <div style={{
              background: '#f0f9ff', border: '1px solid #bae6fd',
              borderRadius: 7, padding: '8px 12px', marginBottom: 12,
              fontSize: 13, display: 'flex', alignItems: 'center', gap: 8
            }}>
              <span className="text-muted">PEPPOL Participant ID:</span>
              <code style={{ fontWeight: 600, color: '#0284c7' }}>{participantId}</code>
              <span className="badge badge-blue" style={{ marginLeft: 'auto' }}>
                {form.vatNumber.trim() ? 'VAT' : 'TIN'}
              </span>
            </div>
          )}

          <div className="form-group">
            <label>ERP Source</label>
            <select value={form.erpSource} onChange={set('erpSource')}>
              {['ODOO', 'SAGE_INTACCT', 'QUICKBOOKS_ONLINE', 'DYNAMICS_365', 'GENERIC_API'].map(o => (
                <option key={o}>{o}</option>
              ))}
            </select>
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

interface DetailPanelProps {
  orgId: string
  apiKey?: string
  customer: CustomerContact
  onClose: () => void
  onSaved: (c: CustomerContact) => void
}

function CustomerDetailPanel({ orgId, apiKey, customer, onClose, onSaved }: DetailPanelProps) {
  const [form, setForm] = useState<CustomerContact>(customer)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    setForm(customer)
    setError('')
    setSaved(false)
  }, [customer.id])

  const set = (k: keyof CustomerContact) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm(f => ({ ...f, [k]: e.target.value } as CustomerContact))

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setError('')
    setSaved(false)
    try {
      const payload: Partial<CustomerContact> = {
        email: form.email,
        name: form.name ?? '',
        phone: form.phone ?? '',
        companyName: form.companyName ?? '',
        tradingName: form.tradingName ?? '',
        erpSource: form.erpSource ?? '',
        erpCustomerId: form.erpCustomerId ?? '',
        deliveryMode: form.deliveryMode || undefined,
        vatNumber: form.vatNumber ?? '',
        tinNumber: form.tinNumber ?? '',
        bpn: form.bpn ?? '',
        peppolParticipantId: form.peppolParticipantId ?? '',
        addressLine1: form.addressLine1 ?? '',
        addressLine2: form.addressLine2 ?? '',
        city: form.city ?? '',
        country: form.country ?? '',
        unsubscribed: form.unsubscribed,
      }
      const res = await updateCustomer(orgId, customer.id, payload, apiKey)
      onSaved(res.data)
      setSaved(true)
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Failed to save changes.')
    } finally {
      setSaving(false)
    }
  }

  const pid = form.peppolParticipantId || deriveParticipantId(form.vatNumber, form.tinNumber)

  return (
    <aside
      style={{
        width: 420, flexShrink: 0,
        background: '#fff',
        borderLeft: '1px solid #e5e7eb',
        height: '100%',
        overflowY: 'auto',
        boxShadow: '-4px 0 12px rgba(0,0,0,0.04)',
      }}
    >
      <div
        style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '14px 18px', borderBottom: '1px solid #e5e7eb',
          position: 'sticky', top: 0, background: '#fff', zIndex: 1,
        }}
      >
        <div>
          <div style={{ fontWeight: 600 }}>{customer.companyName || customer.name || customer.email}</div>
          <div className="text-muted text-sm">{customer.email}</div>
        </div>
        <button className="close-btn" onClick={onClose}><X size={18} /></button>
      </div>

      <form onSubmit={handleSave} style={{ padding: 18 }}>
        {error && <div className="alert alert-error">{error}</div>}
        {saved && <div className="alert alert-success" style={{ marginBottom: 12 }}>Changes saved.</div>}

        <div className="form-group">
          <label>Email *</label>
          <input type="email" value={form.email ?? ''} onChange={set('email')} required />
        </div>
        <div className="grid-2">
          <div className="form-group">
            <label>Contact Name</label>
            <input value={form.name ?? ''} onChange={set('name')} />
          </div>
          <div className="form-group">
            <label>Phone</label>
            <input value={form.phone ?? ''} onChange={set('phone')} />
          </div>
        </div>
        <div className="grid-2">
          <div className="form-group">
            <label>Company Name</label>
            <input value={form.companyName ?? ''} onChange={set('companyName')} />
          </div>
          <div className="form-group">
            <label>Trading Name</label>
            <input value={form.tradingName ?? ''} onChange={set('tradingName')} />
          </div>
        </div>

        <div className="form-group">
          <label>Delivery Mode</label>
          <select value={form.deliveryMode ?? ''} onChange={set('deliveryMode')}>
            <option value="">Inherit from organisation</option>
            <option value="EMAIL">Email only</option>
            <option value="AS4">PEPPOL AS4 only</option>
            <option value="BOTH">Email + AS4</option>
          </select>
        </div>

        <div className="grid-2">
          <div className="form-group">
            <label>VAT Number</label>
            <input value={form.vatNumber ?? ''} onChange={set('vatNumber')} />
          </div>
          <div className="form-group">
            <label>TIN Number</label>
            <input value={form.tinNumber ?? ''} onChange={set('tinNumber')} />
          </div>
        </div>
        <div className="grid-2">
          <div className="form-group">
            <label>BPN</label>
            <input value={form.bpn ?? ''} onChange={set('bpn')} />
          </div>
          <div className="form-group">
            <label>PEPPOL Participant ID</label>
            <input value={form.peppolParticipantId ?? ''} onChange={set('peppolParticipantId')} placeholder={pid ?? ''} />
          </div>
        </div>

        <div className="form-group">
          <label>Address Line 1</label>
          <input value={form.addressLine1 ?? ''} onChange={set('addressLine1')} />
        </div>
        <div className="form-group">
          <label>Address Line 2</label>
          <input value={form.addressLine2 ?? ''} onChange={set('addressLine2')} />
        </div>
        <div className="grid-2">
          <div className="form-group">
            <label>City</label>
            <input value={form.city ?? ''} onChange={set('city')} />
          </div>
          <div className="form-group">
            <label>Country</label>
            <input value={form.country ?? ''} onChange={set('country')} />
          </div>
        </div>

        <div className="grid-2">
          <div className="form-group">
            <label>ERP Source</label>
            <select value={form.erpSource ?? ''} onChange={set('erpSource')}>
              <option value="">—</option>
              {['ODOO', 'SAGE_INTACCT', 'QUICKBOOKS_ONLINE', 'DYNAMICS_365', 'GENERIC_API'].map(o => (
                <option key={o}>{o}</option>
              ))}
            </select>
          </div>
          <div className="form-group">
            <label>ERP Customer ID</label>
            <input value={form.erpCustomerId ?? ''} onChange={set('erpCustomerId')} />
          </div>
        </div>

        <div className="form-group">
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <input
              type="checkbox"
              checked={!!form.unsubscribed}
              onChange={e => setForm(f => ({ ...f, unsubscribed: e.target.checked }))}
            />
            Unsubscribed (will not receive emails)
          </label>
        </div>

        <div style={{
          background: '#f8fafc', border: '1px solid #e2e8f0',
          borderRadius: 7, padding: 10, marginTop: 8, marginBottom: 16,
          fontSize: 12, color: '#475569',
        }}>
          <div>Invoices sent: <strong>{customer.totalInvoicesSent}</strong></div>
          <div>Delivery failures: <strong>{customer.totalDeliveryFailures}</strong></div>
          <div>Last sent: <strong>{customer.lastInvoiceSentAt ? new Date(customer.lastInvoiceSentAt).toLocaleString() : '—'}</strong></div>
        </div>

        <div className="flex gap-2">
          <button type="submit" className="btn btn-primary" disabled={saving}>
            {saving ? <span className="spinner" /> : (<><Save size={14} /> Save Changes</>)}
          </button>
          <button type="button" className="btn btn-secondary" onClick={onClose}>Close</button>
        </div>
      </form>
    </aside>
  )
}

type InviteState = { status: 'idle' } | { status: 'sending' } | { status: 'success' } | { status: 'error'; message: string }

export default function CustomersPage() {
  const { session } = useAuth()
  const [customers, setCustomers] = useState<CustomerContact[]>([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [showImport, setShowImport] = useState(false)
  const [search, setSearch] = useState('')
  const [inviteStates, setInviteStates] = useState<Record<string, InviteState>>({})
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const setInviteState = (customerId: string, state: InviteState) =>
    setInviteStates(prev => ({ ...prev, [customerId]: state }))

  const handleInvite = async (customer: CustomerContact) => {
    if (!session?.apiKey) return
    setInviteState(customer.id, { status: 'sending' })
    try {
      await sendPeppolInvitation(session.apiKey, customer.email)
      setInviteState(customer.id, { status: 'success' })
    } catch (err: any) {
      const message = err.response?.data?.message ?? 'Failed to send invitation.'
      setInviteState(customer.id, { status: 'error', message })
    }
  }

  const load = () => {
    if (!session?.orgId) return
    setLoading(true)
    listCustomers(session.orgId, session.apiKey)
      .then(r => setCustomers(r.data ?? []))
      .catch(() => setCustomers([]))
      .finally(() => setLoading(false))
  }

  useEffect(load, [session?.orgId])

  const filtered = customers.filter(c =>
    !search ||
    c.email?.toLowerCase().includes(search.toLowerCase()) ||
    c.name?.toLowerCase().includes(search.toLowerCase()) ||
    c.companyName?.toLowerCase().includes(search.toLowerCase()) ||
    c.erpCustomerId?.toLowerCase().includes(search.toLowerCase()) ||
    c.peppolParticipantId?.toLowerCase().includes(search.toLowerCase())
  )

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Customers</span>
        {session?.orgId && (
          <div className="flex gap-2">
            <button className="btn btn-secondary btn-sm" onClick={() => setShowImport(true)}>
              <Upload size={14} /> Import CSV
            </button>
            <button className="btn btn-primary btn-sm" onClick={() => setShowModal(true)}>
              <Plus size={14} /> Add Customer
            </button>
          </div>
        )}
      </div>
      <div className="content" style={{ display: 'flex', gap: 0, padding: 0, height: 'calc(100vh - 56px)', overflow: 'hidden' }}>
        <div style={{ flex: 1, overflowY: 'auto', padding: 24 }}>
        <div className="page-header">
          <h2>Customer Registry</h2>
          <p>Manage invoice recipients for your organization — click a row to view and edit details</p>
        </div>
        <div className="card">
          <div className="mb-4">
            <input
              style={{ maxWidth: 320 }}
              placeholder="Search by customer ID, name, email, company or participant ID…"
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
          </div>
          {loading ? (
            <div className="loading-center"><span className="spinner" /></div>
          ) : !session?.orgId ? (
            <div className="empty-state"><p>No organization ID available.</p></div>
          ) : filtered.length === 0 ? (
            <div className="empty-state"><Users size={32} /><p>No customers found</p></div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Customer ID</th>
                    <th>Name</th><th>Email</th><th>Company</th>
                    <th>PEPPOL Participant ID</th>
                    <th>Delivery</th>
                    <th>ERP Source</th><th>Invoices Sent</th>
                    <th>Failures</th><th>Unsubscribed</th><th>Last Sent</th>
                    <th>PEPPOL</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map(c => {
                    // Derive on the fly if backend doesn't return it yet
                    const pid = c.peppolParticipantId ?? deriveParticipantId(c.vatNumber, c.tinNumber)
                    const inviteState = inviteStates[c.id] ?? { status: 'idle' }
                    const isSelected = c.id === selectedId
                    return (
                      <tr
                        key={c.id}
                        onClick={() => setSelectedId(c.id)}
                        style={{ cursor: 'pointer', background: isSelected ? '#eff6ff' : undefined }}
                      >
                        <td>
                          {c.erpCustomerId
                            ? <code style={{ fontSize: 12 }}>{c.erpCustomerId}</code>
                            : <span className="text-muted text-sm">—</span>}
                        </td>
                        <td>{c.name ?? '—'}</td>
                        <td>{c.email}</td>
                        <td>{c.companyName ?? '—'}</td>
                        <td>
                          {pid
                            ? <code style={{ fontSize: 12, color: '#0284c7' }}>{pid}</code>
                            : <span className="text-muted text-sm">—</span>}
                        </td>
                        <td>
                          {c.deliveryMode
                            ? <span className={`badge ${c.deliveryMode === 'EMAIL' ? 'badge-blue' : c.deliveryMode === 'AS4' ? 'badge-green' : 'badge-yellow'}`}>{c.deliveryMode}</span>
                            : <span className="text-muted text-sm">org default</span>}
                        </td>
                        <td className="text-muted text-sm">{c.erpSource ?? '—'}</td>
                        <td>{c.totalInvoicesSent}</td>
                        <td style={{ color: c.totalDeliveryFailures > 0 ? '#dc2626' : undefined }}>
                          {c.totalDeliveryFailures}
                        </td>
                        <td>
                          {c.unsubscribed
                            ? <span className="badge badge-red">Yes</span>
                            : <span className="badge badge-green">No</span>}
                        </td>
                        <td className="text-sm text-muted">
                          {c.lastInvoiceSentAt ? new Date(c.lastInvoiceSentAt).toLocaleDateString() : '—'}
                        </td>
                        <td>
                          {!pid && (
                            <div
                              style={{ display: 'flex', alignItems: 'center', gap: 6 }}
                              onClick={e => e.stopPropagation()}
                            >
                              <button
                                className="btn btn-secondary btn-sm"
                                disabled={inviteState.status === 'sending'}
                                onClick={() => handleInvite(c)}
                              >
                                {inviteState.status === 'sending' ? <span className="spinner" /> : 'Invite to PEPPOL'}
                              </button>
                              {inviteState.status === 'success' && (
                                <span style={{ color: '#16a34a', fontSize: 12 }}>Invitation sent!</span>
                              )}
                              {inviteState.status === 'error' && (
                                <span style={{ color: '#dc2626', fontSize: 12 }}>{inviteState.message}</span>
                              )}
                            </div>
                          )}
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
        {selectedId && session?.orgId && (() => {
          const selected = customers.find(c => c.id === selectedId)
          if (!selected) return null
          return (
            <CustomerDetailPanel
              orgId={session.orgId}
              apiKey={session.apiKey}
              customer={selected}
              onClose={() => setSelectedId(null)}
              onSaved={updated => setCustomers(list => list.map(c => c.id === updated.id ? updated : c))}
            />
          )
        })()}
      </div>
      {showModal && session?.orgId && (
        <Modal
          orgId={session.orgId}
          apiKey={session.apiKey}
          onClose={() => setShowModal(false)}
          onSave={() => { setShowModal(false); load() }}
        />
      )}
      {showImport && session?.orgId && (
        <CustomerImportModal
          orgId={session.orgId}
          apiKey={session.apiKey}
          onClose={() => setShowImport(false)}
          onImported={load}
        />
      )}
    </>
  )
}
