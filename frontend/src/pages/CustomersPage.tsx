import { useEffect, useState, useMemo, Fragment, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { listCustomers, registerCustomer, sendPeppolInvitation, updateCustomer, addCustomerContact, getCustomerInvoices } from '../api/client'
import { Users, Plus, X, Upload, Save, ChevronDown, ChevronRight, ChevronLeft, ArrowUpDown, ArrowUp, ArrowDown, ExternalLink, FileText } from 'lucide-react'
import CustomerImportModal from './CustomerImportModal'
import type { Customer, Contact, DeliveryMode, PageResponse, InvoiceRecord } from '../types'
import { deriveParticipantId } from '../types'

const VALID_SORT_KEYS = ['name', 'email', 'companyName', 'totalInvoicesSent', 'lastInvoiceSentAt', 'totalDeliveryFailures'] as const
type SortKey = typeof VALID_SORT_KEYS[number]
type SortDir = 'asc' | 'desc'
type InviteState = { status: 'idle' } | { status: 'sending' } | { status: 'success' } | { status: 'error'; message: string }

const SORT_FIELD_MAP: Record<string, string> = {
  name: 'companyName', email: 'companyName', companyName: 'companyName',
  totalInvoicesSent: 'totalInvoicesSent', lastInvoiceSentAt: 'lastInvoiceSentAt',
  totalDeliveryFailures: 'totalDeliveryFailures',
}
const PAGE_SIZES = [10, 25, 50, 100]

// ─── Shared sub-components ────────────────────────────────────────────────────

function CSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ border: '1px solid #e8edf2', borderRadius: 10, background: '#fff', minWidth: 0 }}>
      <div style={{ padding: '10px 16px', borderBottom: '1px solid #f1f5f9', background: '#f8fafc', display: 'flex', alignItems: 'center', gap: 8 }}>
        <div style={{ width: 3, height: 14, borderRadius: 2, background: 'var(--brand-orange)', flexShrink: 0 }} />
        <span style={{ fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.07em', color: '#64748b' }}>{title}</span>
      </div>
      <div style={{ padding: '14px 16px' }}>
        {children}
      </div>
    </div>
  )
}

// Two-column grid that prevents overflow — use instead of grid-2 inside the panel
const col2: React.CSSProperties = { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, minWidth: 0 }

function CField({ label, children, style }: { label: string; children: React.ReactNode; style?: React.CSSProperties }) {
  return (
    <div style={{ marginBottom: 10, minWidth: 0, ...style }}>
      <label style={{ fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.06em', color: '#94a3b8', marginBottom: 4, display: 'block' }}>{label}</label>
      {children}
    </div>
  )
}

function InvStatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    SENT: 'badge-green', FAILED: 'badge-red',
    PENDING: 'badge-gray', SKIPPED: 'badge-yellow', BOUNCED: 'badge-red',
  }
  return <span className={`badge ${map[status] ?? 'badge-gray'}`}>{status}</span>
}

// ─── Add Customer Modal ───────────────────────────────────────────────────────

interface ModalProps { orgId: string; apiKey?: string; onClose: () => void; onSave: () => void }

function Modal({ onClose, onSave, orgId, apiKey }: ModalProps) {
  const [form, setForm] = useState({
    email: '', name: '', companyName: '', erpSource: 'GENERIC_API',
    erpCustomerId: '',
    vatNumber: '', tinNumber: '', deliveryMode: '' as DeliveryMode | ''
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const participantId = deriveParticipantId(form.vatNumber, form.tinNumber)
  const needsPeppol = form.deliveryMode === 'AS4' || form.deliveryMode === 'BOTH'

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (needsPeppol && !participantId) { setError('AS4 delivery requires a VAT or TIN number.'); return }
    setLoading(true)
    try {
      await registerCustomer(orgId, {
        ...form,
        deliveryMode: form.deliveryMode || undefined,
        vatNumber: form.vatNumber.trim() || undefined,
        tinNumber: form.tinNumber.trim() || undefined,
        erpCustomerId: form.erpCustomerId.trim() || undefined,
        peppolParticipantId: participantId ?? undefined,
      }, apiKey)
      onSave()
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Failed to register customer.')
    } finally { setLoading(false) }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 540 }} onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{ width: 34, height: 34, borderRadius: 9, background: 'var(--brand-orange)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, boxShadow: '0 2px 6px rgba(232,93,27,.3)' }}>
              <Plus size={17} color="#fff" />
            </div>
            <div>
              <div className="modal-title">Register Customer</div>
              <div className="text-sm text-muted" style={{ marginTop: 1 }}>Add a new invoice recipient</div>
            </div>
          </div>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>

        {error && <div className="alert alert-error">{error}</div>}

        <form id="add-customer-form" onSubmit={handleSubmit}>
          <CSection title="Contact Info">
            <CField label="Email *">
              <input type="email" value={form.email} onChange={set('email')} placeholder="contact@company.com" required />
            </CField>
            <div style={col2}>
              <CField label="Contact Name">
                <input value={form.name} onChange={set('name')} placeholder="Jane Moyo" />
              </CField>
              <CField label="Company Name">
                <input value={form.companyName} onChange={set('companyName')} placeholder="Acme Holdings" />
              </CField>
            </div>
          </CSection>

          <div style={{ marginTop: 12 }}>
            <CSection title="Delivery & PEPPOL">
              <CField label="Delivery Mode">
                <select value={form.deliveryMode} onChange={set('deliveryMode')}>
                  <option value="">Inherit from organisation</option>
                  <option value="EMAIL">Email only</option>
                  <option value="AS4">PEPPOL AS4 only</option>
                  <option value="BOTH">Email + AS4</option>
                </select>
              </CField>
              <div style={col2}>
                <CField label={`VAT Number${needsPeppol && !form.tinNumber.trim() ? ' *' : ''}`}>
                  <input value={form.vatNumber} onChange={set('vatNumber')} placeholder="e.g. 12345678" />
                </CField>
                <CField label={`TIN Number${needsPeppol && !form.vatNumber.trim() ? ' *' : ''}`}>
                  <input value={form.tinNumber} onChange={set('tinNumber')} placeholder="e.g. 1234567890" disabled={!!form.vatNumber.trim()} />
                </CField>
              </div>
              {participantId && (
                <div style={{ background: '#f0f9ff', border: '1px solid #bae6fd', borderRadius: 8, padding: '10px 14px', fontSize: 13, color: '#0369a1', marginTop: 4 }}>
                  PEPPOL Participant ID: <code style={{ fontWeight: 700 }}>{participantId}</code>
                  <span className="badge badge-blue" style={{ marginLeft: 8 }}>{form.vatNumber.trim() ? 'VAT' : 'TIN'}</span>
                </div>
              )}
            </CSection>
          </div>

          <div style={{ marginTop: 12 }}>
            <CSection title="ERP / Account">
              <CField label="ERP Customer ID">
                <input value={form.erpCustomerId} onChange={set('erpCustomerId')} placeholder="e.g. CUST-0001" />
              </CField>
              <CField label="ERP Source">
                <select value={form.erpSource} onChange={set('erpSource')}>
                  {['ODOO', 'SAGE_INTACCT', 'QUICKBOOKS_ONLINE', 'DYNAMICS_365', 'GENERIC_API'].map(o => <option key={o}>{o}</option>)}
                </select>
              </CField>
            </CSection>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 20 }}>
            <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? <span className="spinner" /> : <Plus size={15} />}
              Register Customer
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}


// ─── Customer Detail Panel ────────────────────────────────────────────────────

interface DetailPanelProps {
  orgId: string; apiKey?: string; customer: Customer
  onClose: () => void; onSaved: (c: Customer) => void
}

function CustomerDetailPanel({ orgId, apiKey, customer, onClose, onSaved }: DetailPanelProps) {
  const navigate = useNavigate()
  const [contacts, setContacts] = useState<Contact[]>(customer.contacts ?? [])
  const [addEmail, setAddEmail] = useState('')
  const [addName, setAddName] = useState('')
  const [addPhone, setAddPhone] = useState('')
  const [adding, setAdding] = useState(false)
  const [form, setForm] = useState({
    companyName: customer.companyName ?? '',
    tradingName: customer.tradingName ?? '',
    erpSource: customer.erpSource ?? '',
    erpCustomerId: customer.erpCustomerId ?? '',
    deliveryMode: customer.deliveryMode || '' as DeliveryMode | '',
    vatNumber: customer.vatNumber ?? '',
    tinNumber: customer.tinNumber ?? '',
    bpn: customer.bpn ?? '',
    peppolParticipantId: customer.peppolParticipantId ?? '',
    addressLine1: customer.addressLine1 ?? '',
    addressLine2: customer.addressLine2 ?? '',
    city: customer.city ?? '',
    country: customer.country ?? '',
    unsubscribed: customer.unsubscribed,
  })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const [inviteState, setInviteState] = useState<InviteState>({ status: 'idle' })
  const [invoices, setInvoices] = useState<InvoiceRecord[]>([])
  const [invoicesLoading, setInvoicesLoading] = useState(false)
  const [invoicesPage, setInvoicesPage] = useState(0)
  const [invoicesTotal, setInvoicesTotal] = useState(0)
  const INVOICES_PAGE_SIZE = 5

  useEffect(() => {
    setContacts(customer.contacts ?? [])
    setForm(f => ({
      ...f,
      companyName: customer.companyName ?? '', tradingName: customer.tradingName ?? '',
      erpSource: customer.erpSource ?? '', erpCustomerId: customer.erpCustomerId ?? '',
      deliveryMode: customer.deliveryMode || '' as DeliveryMode | '',
      vatNumber: customer.vatNumber ?? '', tinNumber: customer.tinNumber ?? '',
      bpn: customer.bpn ?? '', peppolParticipantId: customer.peppolParticipantId ?? '',
      addressLine1: customer.addressLine1 ?? '', addressLine2: customer.addressLine2 ?? '',
      city: customer.city ?? '', country: customer.country ?? '',
      unsubscribed: customer.unsubscribed,
    }))
    setError(''); setSaved(false); setInviteState({ status: 'idle' })
    setAddEmail(''); setAddName(''); setAddPhone('')
  }, [customer.id])

  useEffect(() => {
    if (!apiKey) return
    setInvoicesLoading(true); setInvoicesPage(0)
    getCustomerInvoices(customer.id, apiKey, { page: '0', size: String(INVOICES_PAGE_SIZE) })
      .then(r => { setInvoices(r.data.content ?? []); setInvoicesTotal(r.data.totalElements) })
      .catch(() => setInvoices([]))
      .finally(() => setInvoicesLoading(false))
  }, [customer.id, apiKey])

  const handleLoadMoreInvoices = () => {
    if (!apiKey) return
    const next = invoicesPage + 1
    getCustomerInvoices(customer.id, apiKey, { page: String(next), size: String(INVOICES_PAGE_SIZE) })
      .then(r => { setInvoices(p => [...p, ...(r.data.content ?? [])]); setInvoicesTotal(r.data.totalElements); setInvoicesPage(next) })
      .catch(() => {})
  }

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const handleAddContact = async () => {
    if (!addEmail) return
    setAdding(true); setError('')
    try {
      const res = await addCustomerContact(orgId, customer.id, { email: addEmail, name: addName, phone: addPhone }, apiKey)
      setContacts(res.data.contacts ?? []); onSaved(res.data)
      setAddEmail(''); setAddName(''); setAddPhone('')
    } catch (err: any) { setError(err.response?.data?.message ?? 'Failed to add contact.')
    } finally { setAdding(false) }
  }

  const handleSave = async (e?: React.FormEvent) => {
    e?.preventDefault()
    setSaving(true); setError(''); setSaved(false)
    try {
      const res = await updateCustomer(orgId, customer.id, {
        companyName: form.companyName ?? '', tradingName: form.tradingName ?? '',
        erpSource: form.erpSource ?? '', erpCustomerId: form.erpCustomerId ?? '',
        deliveryMode: form.deliveryMode || undefined,
        vatNumber: form.vatNumber ?? '', tinNumber: form.tinNumber ?? '',
        bpn: form.bpn ?? '', peppolParticipantId: form.peppolParticipantId ?? '',
        addressLine1: form.addressLine1 ?? '', addressLine2: form.addressLine2 ?? '',
        city: form.city ?? '', country: form.country ?? '',
        unsubscribed: form.unsubscribed,
      }, apiKey)
      onSaved(res.data); setSaved(true)
    } catch (err: any) { setError(err.response?.data?.message ?? 'Failed to save changes.')
    } finally { setSaving(false) }
  }

  const pid = deriveParticipantId(form.vatNumber, form.tinNumber) || form.peppolParticipantId
  const needsPid = form.deliveryMode === 'AS4' || form.deliveryMode === 'BOTH'

  const handleInvite = async () => {
    if (!apiKey) return
    const email = contacts[0]?.email; if (!email) return
    setInviteState({ status: 'sending' })
    try {
      const { sendPeppolInvitation } = await import('../api/client')
      await sendPeppolInvitation(apiKey, email)
      setInviteState({ status: 'success' })
    } catch (err: any) { setInviteState({ status: 'error', message: err.response?.data?.message ?? 'Failed to send invitation.' }) }
  }

  const displayName = customer.companyName || contacts[0]?.name || contacts[0]?.email || '?'

  return (
    <div className="modal-overlay" style={{ alignItems: 'flex-start', paddingTop: 16, paddingBottom: 16 }} onClick={onClose}>
      <div style={{ background: '#fff', borderRadius: 16, width: '100%', maxWidth: 780, maxHeight: 'calc(100vh - 40px)', display: 'flex', flexDirection: 'column', boxShadow: '0 25px 60px rgba(0,0,0,.3)', border: '1px solid #e8edf2' }} onClick={e => e.stopPropagation()}>

        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, padding: '18px 24px', borderBottom: '1px solid #f1f5f9', flexShrink: 0, background: '#fafbff', borderRadius: '16px 16px 0 0' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, minWidth: 0, flex: 1 }}>
            <div style={{ width: 38, height: 38, borderRadius: 10, background: 'var(--brand-orange)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', fontWeight: 800, fontSize: 15, flexShrink: 0, boxShadow: '0 2px 8px rgba(232,93,27,.3)' }}>
              {displayName.charAt(0).toUpperCase()}
            </div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 15, fontWeight: 700, color: '#0f172a', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{displayName}</div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 2, flexWrap: 'wrap' }}>
                {customer.erpCustomerId && (
                  <span className="text-sm text-muted">ERP: <code style={{ color: 'var(--brand-orange)', fontFamily: 'monospace', fontWeight: 600 }}>{customer.erpCustomerId}</code></span>
                )}
                {customer.createdAt && (
                  <span className="text-sm text-muted">· Created {new Date(customer.createdAt).toLocaleDateString()}</span>
                )}
              </div>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
            <span className={`badge ${customer.unsubscribed ? 'badge-red' : 'badge-green'}`}>{customer.unsubscribed ? 'Inactive' : 'Active'}</span>
            <button className="close-btn" onClick={() => navigate('/invoices')} title="View all invoices"><ExternalLink size={15} /></button>
            <button className="close-btn" onClick={onClose}><X size={18} /></button>
          </div>
        </div>

        {/* Scrollable body */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '20px 24px 24px', display: 'flex', flexDirection: 'column', gap: 14 }}>

          {error && <div className="alert alert-error">{error}</div>}
          {saved && <div className="alert alert-success">Changes saved successfully.</div>}

          {/* Row 1: Company & Account | Address & Tax */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, alignItems: 'start', minWidth: 0 }}>
            <CSection title="Company & Account">
              <CField label="Company Name"><input value={form.companyName} onChange={set('companyName')} /></CField>
              <CField label="Trading Name"><input value={form.tradingName} onChange={set('tradingName')} /></CField>
              <CField label="ERP Source">
                <select value={form.erpSource} onChange={set('erpSource')}>
                  <option value="">—</option>
                  {['ODOO', 'SAGE_INTACCT', 'QUICKBOOKS_ONLINE', 'DYNAMICS_365', 'GENERIC_API'].map(o => <option key={o}>{o}</option>)}
                </select>
              </CField>
              <CField label="ERP Customer ID"><input value={form.erpCustomerId} onChange={set('erpCustomerId')} /></CField>
            </CSection>

            <CSection title="Address & Tax">
              <CField label="Address Line 1"><input value={form.addressLine1} onChange={set('addressLine1')} /></CField>
              <CField label="Address Line 2"><input value={form.addressLine2} onChange={set('addressLine2')} /></CField>
              <div style={col2}>
                <CField label="City"><input value={form.city} onChange={set('city')} /></CField>
                <CField label="Country"><input value={form.country} onChange={set('country')} /></CField>
              </div>
              <div style={col2}>
                <CField label="VAT Number"><input value={form.vatNumber} onChange={set('vatNumber')} placeholder="e.g. 12345678" /></CField>
                <CField label="TIN Number"><input value={form.tinNumber} onChange={set('tinNumber')} placeholder="e.g. 1234567890" disabled={!!form.vatNumber.trim()} /></CField>
              </div>
              <CField label="BPN"><input value={form.bpn} onChange={set('bpn')} placeholder="e.g. 2000480465" /></CField>
            </CSection>
          </div>

          {/* Delivery & PEPPOL */}
          <CSection title="Delivery & PEPPOL">
            <div style={col2}>
              <CField label="Delivery Mode">
                <select value={form.deliveryMode} onChange={set('deliveryMode')}>
                  <option value="">Inherit from organisation</option>
                  <option value="EMAIL">Email only</option>
                  <option value="AS4">PEPPOL AS4 only</option>
                  <option value="BOTH">Email + AS4</option>
                </select>
              </CField>
              <CField label="Participant ID">
                <input value={form.peppolParticipantId} onChange={set('peppolParticipantId')} placeholder={pid ?? 'Auto-derived from VAT/TIN'} />
              </CField>
            </div>
            {pid && (
              <div style={{ background: '#f0f9ff', border: '1px solid #bae6fd', borderRadius: 8, padding: '10px 14px', fontSize: 13, color: '#0369a1', marginTop: 4 }}>
                Resolved ID: <code style={{ fontWeight: 700 }}>{pid}</code>
                <span className="badge badge-blue" style={{ marginLeft: 8 }}>{form.vatNumber.trim() ? 'VAT' : 'TIN'}</span>
              </div>
            )}
            {!pid && !needsPid && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 4 }}>
                <button type="button" className="btn btn-secondary btn-sm"
                  disabled={inviteState.status === 'sending' || !contacts[0]?.email}
                  onClick={handleInvite}>
                  {inviteState.status === 'sending' ? <span className="spinner" /> : null}
                  Invite to PEPPOL
                </button>
                {inviteState.status === 'success' && <span style={{ color: '#16a34a', fontSize: 13 }}>Invitation sent!</span>}
                {inviteState.status === 'error' && <span style={{ color: '#dc2626', fontSize: 13 }}>{(inviteState as any).message}</span>}
              </div>
            )}
          </CSection>

          {/* Receiver Status */}
          <CSection title="Receiver Status">
            <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer', margin: 0 }}>
                <div
                  onClick={() => setForm(f => ({ ...f, unsubscribed: !f.unsubscribed }))}
                  style={{ width: 40, height: 22, borderRadius: 11, background: form.unsubscribed ? '#d1d5db' : 'var(--brand-orange)', position: 'relative', cursor: 'pointer', transition: 'background .2s', flexShrink: 0 }}
                >
                  <div style={{ width: 16, height: 16, borderRadius: 8, background: '#fff', position: 'absolute', top: 3, left: form.unsubscribed ? 3 : 21, transition: 'left .2s', boxShadow: '0 1px 3px rgba(0,0,0,.2)' }} />
                </div>
                <div>
                  <div style={{ fontSize: 13, fontWeight: 600, color: '#374151' }}>{form.unsubscribed ? 'Unsubscribed' : 'Active receiver'}</div>
                  <div className="text-sm text-muted">{form.unsubscribed ? 'Will not receive future emails' : 'Can receive invoice emails'}</div>
                </div>
              </label>
              <span className={`badge ${form.unsubscribed ? 'badge-red' : 'badge-green'}`}>{form.unsubscribed ? 'Inactive' : 'Active'}</span>
            </div>

            <div className="stats-grid" style={{ marginTop: 14, marginBottom: 0, gridTemplateColumns: 'repeat(4, 1fr)', gap: 10 }}>
              <div className="stat-card" style={{ padding: '12px 14px' }}>
                <div className="stat-label">Pending</div>
                <div className="stat-value" style={{ fontSize: 20, color: '#d97706' }}>{customer.invoicesPending ?? 0}</div>
              </div>
              <div className="stat-card" style={{ padding: '12px 14px' }}>
                <div className="stat-label">Sent</div>
                <div className="stat-value" style={{ fontSize: 20, color: '#16a34a' }}>{customer.invoicesSent ?? 0}</div>
              </div>
              <div className="stat-card" style={{ padding: '12px 14px' }}>
                <div className="stat-label">Failures</div>
                <div className="stat-value" style={{ fontSize: 20, color: customer.totalDeliveryFailures > 0 ? '#dc2626' : undefined }}>{customer.totalDeliveryFailures}</div>
              </div>
              <div className="stat-card" style={{ padding: '12px 14px' }}>
                <div className="stat-label">Last Sent</div>
                <div style={{ fontSize: 13, fontWeight: 600, color: '#0f172a', marginTop: 4 }}>{customer.lastInvoiceSentAt ? new Date(customer.lastInvoiceSentAt).toLocaleDateString() : '—'}</div>
              </div>
            </div>
          </CSection>

          {/* Invoices */}
          <CSection title={`Invoices (${invoicesTotal})`}>
            {invoicesLoading && invoices.length === 0 ? (
              <div className="loading-center" style={{ padding: 24 }}><span className="spinner" /></div>
            ) : invoices.length === 0 ? (
              <div className="empty-state" style={{ padding: '24px 0' }}><FileText size={28} /><p>No invoices sent yet</p></div>
            ) : (
              <>
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr><th>Invoice #</th><th>Status</th><th>Amount</th><th>Sent</th></tr>
                    </thead>
                    <tbody>
                      {invoices.map(inv => (
                        <tr key={inv.id}>
                          <td style={{ fontWeight: 600 }}>{inv.invoiceNumber}</td>
                          <td><InvStatusBadge status={inv.status} /></td>
                          <td>{inv.currency && inv.totalAmount != null ? `${inv.currency} ${Number(inv.totalAmount).toLocaleString()}` : '—'}</td>
                          <td className="text-sm text-muted">{inv.sentAt ? new Date(inv.sentAt).toLocaleDateString() : '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {invoices.length < invoicesTotal && (
                  <button className="btn btn-secondary btn-sm" style={{ marginTop: 10, width: '100%', justifyContent: 'center' }} onClick={handleLoadMoreInvoices}>
                    Load more ({invoicesTotal - invoices.length} remaining)
                  </button>
                )}
              </>
            )}
          </CSection>

          {/* Contacts */}
          <CSection title={`Contacts (${contacts.length})`}>
            {contacts.length > 0 && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 14 }}>
                {contacts.map(c => (
                  <div key={c.id} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px', background: '#f8fafc', border: '1px solid #e8edf2', borderRadius: 9 }}>
                    <div style={{ width: 34, height: 34, borderRadius: 8, background: 'linear-gradient(135deg, var(--brand-orange), var(--brand-orange-light))', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', fontWeight: 700, fontSize: 13, flexShrink: 0 }}>
                      {(c.name || c.email).charAt(0).toUpperCase()}
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 13, fontWeight: 600, color: '#0f172a', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.name || '—'}</div>
                      <div className="text-sm text-muted" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.email}</div>
                    </div>
                    {c.phone && <span className="text-sm text-muted" style={{ flexShrink: 0 }}>{c.phone}</span>}
                  </div>
                ))}
              </div>
            )}
            {contacts.length === 0 && <p className="text-sm text-muted" style={{ marginBottom: 12 }}>No contacts yet. Add one below.</p>}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr auto', gap: 8, alignItems: 'end' }}>
              <CField label="Email *" style={{ margin: 0 }}>
                <input type="email" placeholder="Email *" value={addEmail} onChange={e => setAddEmail(e.target.value)} />
              </CField>
              <CField label="Name" style={{ margin: 0 }}>
                <input placeholder="Name" value={addName} onChange={e => setAddName(e.target.value)} />
              </CField>
              <CField label="Phone" style={{ margin: 0 }}>
                <input placeholder="Phone" value={addPhone} onChange={e => setAddPhone(e.target.value)} />
              </CField>
              <button className="btn btn-primary btn-sm" style={{ height: 38 }} disabled={adding || !addEmail} onClick={handleAddContact}>
                {adding ? <span className="spinner" /> : 'Add'}
              </button>
            </div>
          </CSection>
        </div>

        {/* Footer */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8, padding: '14px 24px', borderTop: '1px solid #f1f5f9', background: '#fafbff', borderRadius: '0 0 16px 16px', flexShrink: 0 }}>
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSave} disabled={saving}>
            {saving ? <span className="spinner" /> : <Save size={15} />}
            Save Changes
          </button>
        </div>
      </div>
    </div>
  )
}


// ─── Sort Header ──────────────────────────────────────────────────────────────

function SortHeader({ label, sortKey, currentKey, currentDir, onSort }: {
  label: string; sortKey: SortKey; currentKey: SortKey | null; currentDir: SortDir; onSort: (k: SortKey) => void
}) {
  const active = currentKey === sortKey
  return (
    <th style={{ cursor: 'pointer', userSelect: 'none' }} onClick={() => onSort(sortKey)}>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
        {label}
        {active ? (currentDir === 'asc' ? <ArrowUp size={12} /> : <ArrowDown size={12} />) : <ArrowUpDown size={12} style={{ opacity: 0.3 }} />}
      </span>
    </th>
  )
}

// ─── Customer Row ─────────────────────────────────────────────────────────────

type InviteState2 = InviteState

function CustomerRow({ c, contact, groupByCompany, selectedId, inviteState, onSelect, onInvite }: {
  c: Customer; contact: Contact | null; groupByCompany: boolean
  selectedId: string | null; inviteState: InviteState2
  onSelect: (id: string) => void; onInvite: (c: Customer) => void
}) {
  const pid = c.peppolParticipantId ?? deriveParticipantId(c.vatNumber, c.tinNumber)
  return (
    <tr onClick={() => onSelect(c.id)} style={{ cursor: 'pointer', background: c.id === selectedId ? '#fff7ed' : undefined }}>
      {groupByCompany && <td />}
      <td>{c.erpCustomerId ? <code style={{ fontSize: 12 }}>{c.erpCustomerId}</code> : <span className="text-muted text-sm">—</span>}</td>
      <td>{contact?.name ?? '—'}</td>
      <td>{contact?.email ?? '—'}</td>
      <td>{c.companyName ?? '—'}</td>
      <td>{pid ? <code style={{ fontSize: 12, color: '#0284c7' }}>{pid}</code> : <span className="text-muted text-sm">—</span>}</td>
      <td>
        {c.deliveryMode
          ? <span className={`badge ${c.deliveryMode === 'EMAIL' ? 'badge-blue' : c.deliveryMode === 'AS4' ? 'badge-green' : 'badge-yellow'}`}>{c.deliveryMode}</span>
          : <span className="text-muted text-sm">org default</span>}
      </td>
      <td className="text-muted text-sm">{c.erpSource ?? '—'}</td>
      <td>
        <div style={{ fontWeight: 600 }}>{c.invoicesSent} sent</div>
        {c.invoicesPending > 0 && <div className="text-sm" style={{ color: '#d97706' }}>{c.invoicesPending} pending</div>}
      </td>
      <td style={{ color: c.totalDeliveryFailures > 0 ? '#dc2626' : undefined, fontWeight: c.totalDeliveryFailures > 0 ? 600 : undefined }}>{c.totalDeliveryFailures}</td>
      <td>{c.unsubscribed ? <span className="badge badge-red">Yes</span> : <span className="badge badge-green">No</span>}</td>
      <td className="text-sm text-muted">{c.lastInvoiceSentAt ? new Date(c.lastInvoiceSentAt).toLocaleDateString() : '—'}</td>
      <td>
        {!pid && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }} onClick={e => e.stopPropagation()}>
            <button className="btn btn-secondary btn-sm" disabled={inviteState.status === 'sending'} onClick={() => onInvite(c)}>
              {inviteState.status === 'sending' ? <span className="spinner" /> : 'Invite to PEPPOL'}
            </button>
            {inviteState.status === 'success' && <span style={{ color: '#16a34a', fontSize: 12 }}>Sent!</span>}
            {inviteState.status === 'error' && <span style={{ color: '#dc2626', fontSize: 12 }}>{(inviteState as any).message}</span>}
          </div>
        )}
      </td>
    </tr>
  )
}

// ─── Group Row ────────────────────────────────────────────────────────────────

function GroupRow({ company, members, expanded, onToggle }: {
  company: string; members: Customer[]; expanded: boolean; onToggle: () => void
}) {
  const totalSent = members.reduce((s, c) => s + c.totalInvoicesSent, 0)
  const totalFailed = members.reduce((s, c) => s + c.totalDeliveryFailures, 0)
  const totalPending = members.reduce((s, c) => s + (c.invoicesPending ?? 0), 0)
  return (
    <tr style={{ background: '#f8fafc', cursor: 'pointer' }} onClick={onToggle}>
      <td style={{ textAlign: 'center' }}>{expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}</td>
      <td colSpan={12}>
        <span style={{ fontWeight: 600 }}>{company}</span>
        <span className="text-muted text-sm" style={{ marginLeft: 8 }}>
          {members.length} contact{members.length !== 1 ? 's' : ''}
          <span style={{ marginLeft: 12, color: '#d97706' }}>{totalPending} pending</span>
          <span style={{ marginLeft: 12 }}>{totalSent} sent</span>
          {totalFailed > 0 && <span style={{ marginLeft: 12, color: '#dc2626' }}>{totalFailed} failures</span>}
        </span>
      </td>
    </tr>
  )
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function CustomersPage() {
  const { session } = useAuth()
  const [customers, setCustomers] = useState<Customer[]>([])
  const [pageRes, setPageRes] = useState<PageResponse<Customer> | null>(null)
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [showImport, setShowImport] = useState(false)
  const [search, setSearch] = useState('')
  const [inviteStates, setInviteStates] = useState<Record<string, InviteState>>({})
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [sortKey, setSortKey] = useState<SortKey>('name')
  const [sortDir, setSortDir] = useState<SortDir>('asc')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [groupByCompany, setGroupByCompany] = useState(false)
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set())

  const setInviteState = (id: string, state: InviteState) =>
    setInviteStates(prev => ({ ...prev, [id]: state }))

  const handleInvite = async (customer: Customer) => {
    if (!session?.apiKey) return
    const email = customer.contacts?.[0]?.email; if (!email) return
    setInviteState(customer.id, { status: 'sending' })
    try {
      await sendPeppolInvitation(session.apiKey, email)
      setInviteState(customer.id, { status: 'success' })
    } catch (err: any) {
      setInviteState(customer.id, { status: 'error', message: err.response?.data?.message ?? 'Failed.' })
    }
  }

  const load = useCallback(() => {
    if (!session?.orgId) return
    setLoading(true)
    listCustomers(session.orgId, session.apiKey, { page, size: pageSize, sort: SORT_FIELD_MAP[sortKey] ?? 'companyName', dir: sortDir, search })
      .then(r => { setCustomers(r.data.content ?? []); setPageRes(r.data) })
      .catch(() => { setCustomers([]); setPageRes(null) })
      .finally(() => setLoading(false))
  }, [session?.orgId, session?.apiKey, page, pageSize, sortKey, sortDir, search])

  useEffect(load, [load])

  const totalRecords = pageRes?.totalElements ?? 0
  const totalPages = pageRes?.totalPages ?? 1

  const groups = useMemo(() => {
    if (!groupByCompany) return null
    const map = new Map<string, Customer[]>()
    for (const c of customers) {
      const key = c.companyName?.trim() || '(no company)'
      if (!map.has(key)) map.set(key, [])
      map.get(key)!.push(c)
    }
    return Array.from(map.entries()).map(([company, members]) => ({ company, members })).sort((a, b) => a.company.localeCompare(b.company))
  }, [customers, groupByCompany])

  const pagedResults = useMemo<{ rows: React.ReactNode[]; showing: string }>(() => {
    if (!groupByCompany) {
      const rows: React.ReactNode[] = []
      for (const c of customers) {
        const list = c.contacts && c.contacts.length > 0 ? c.contacts : [null]
        for (const ct of list) {
          rows.push(<CustomerRow key={`${c.id}:${ct?.id ?? '0'}`} c={c} contact={ct} groupByCompany={false} selectedId={selectedId} inviteState={inviteStates[c.id] ?? { status: 'idle' }} onSelect={setSelectedId} onInvite={handleInvite} />)
        }
      }
      return { rows, showing: `Page ${page + 1} of ${totalPages} (${totalRecords} records)` }
    }
    const totalGroups = groups!.length
    const totalMembers = groups!.reduce((s, g) => s + g.members.length, 0)
    const pages = Math.max(1, Math.ceil(totalGroups / pageSize))
    const rows: React.ReactNode[] = []
    for (const g of groups!.slice(page * pageSize, (page + 1) * pageSize)) {
      const expanded = expandedGroups.has(g.company)
      rows.push(
        <Fragment key={g.company}>
          <GroupRow company={g.company} members={g.members} expanded={expanded} onToggle={() => {
            setExpandedGroups(prev => { const n = new Set(prev); n.has(g.company) ? n.delete(g.company) : n.add(g.company); return n })
          }} />
          {expanded && g.members.flatMap(c => (c.contacts && c.contacts.length > 0 ? c.contacts : [null]).map(ct => (
            <CustomerRow key={`${c.id}:${ct?.id ?? '0'}`} c={c} contact={ct} groupByCompany={true} selectedId={selectedId} inviteState={inviteStates[c.id] ?? { status: 'idle' }} onSelect={setSelectedId} onInvite={handleInvite} />
          )))}
        </Fragment>
      )
    }
    return { rows, showing: `Page ${page + 1} of ${pages} (${totalGroups} groups, ${totalMembers} contacts)` }
  }, [customers, groups, groupByCompany, page, pageSize, totalPages, totalRecords, expandedGroups, selectedId, inviteStates])

  const handleSort = (key: SortKey) => {
    if (key === sortKey) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortKey(key); setSortDir('asc') }
    setPage(0)
  }

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Customers</span>
        {session?.orgId && (
          <div className="flex gap-2">
            <button className="btn btn-secondary btn-sm" onClick={() => setShowImport(true)}><Upload size={14} /> Import CSV</button>
            <button className="btn btn-primary btn-sm" onClick={() => setShowModal(true)}><Plus size={14} /> Add Customer</button>
          </div>
        )}
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Customer Registry</h2>
          <p>Manage invoice recipients — click any row to view and edit details</p>
        </div>
        <div className="card">
          <div className="flex-between mb-4" style={{ flexWrap: 'wrap', gap: 10 }}>
            <input style={{ maxWidth: 320 }} placeholder="Search by name, email, company…" value={search} onChange={e => { setSearch(e.target.value); setPage(0) }} />
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: 'pointer', margin: 0 }}>
                <input type="checkbox" checked={groupByCompany} onChange={() => { setGroupByCompany(g => !g); setPage(0); setExpandedGroups(new Set()) }} style={{ width: 'auto', margin: 0 }} />
                Group by company
              </label>
              <span className="text-sm text-muted">{totalRecords} record{totalRecords !== 1 ? 's' : ''}</span>
            </div>
          </div>

          {loading ? (
            <div className="loading-center"><span className="spinner" /></div>
          ) : !session?.orgId ? (
            <div className="empty-state"><p>No organisation available.</p></div>
          ) : customers.length === 0 ? (
            <div className="empty-state"><Users size={32} /><p>No customers found</p></div>
          ) : (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      {groupByCompany && <th style={{ width: 28 }} />}
                      <th>Customer ID</th>
                      <SortHeader label="Name" sortKey="name" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <SortHeader label="Email" sortKey="email" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <SortHeader label="Company" sortKey="companyName" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <th>PEPPOL ID</th><th>Delivery</th><th>ERP</th>
                      <SortHeader label="Invoices" sortKey="totalInvoicesSent" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <SortHeader label="Failures" sortKey="totalDeliveryFailures" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <th>Unsubscribed</th>
                      <SortHeader label="Last Sent" sortKey="lastInvoiceSentAt" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <th>PEPPOL</th>
                    </tr>
                  </thead>
                  <tbody>{pagedResults.rows}</tbody>
                </table>
              </div>
              <div className="flex-between mt-4" style={{ flexWrap: 'wrap', gap: 10 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span className="text-sm text-muted">Rows per page:</span>
                  <select style={{ width: 'auto', padding: '5px 10px' }} value={pageSize} onChange={e => { setPageSize(Number(e.target.value)); setPage(0) }}>
                    {PAGE_SIZES.map(s => <option key={s}>{s}</option>)}
                  </select>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span className="text-sm text-muted">{pagedResults.showing}</span>
                  <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}><ChevronLeft size={14} /></button>
                  <button className="btn btn-secondary btn-sm" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}><ChevronRight size={14} /></button>
                </div>
              </div>
            </>
          )}
        </div>
      </div>

      {selectedId && session?.orgId && (() => {
        const selected = customers.find(c => c.id === selectedId)
        if (!selected) return null
        return (
          <CustomerDetailPanel
            orgId={session.orgId} apiKey={session.apiKey} customer={selected}
            onClose={() => setSelectedId(null)}
            onSaved={updated => setCustomers(list => list.map(c => c.id === updated.id ? updated as unknown as Customer : c))}
          />
        )
      })()}
      {showModal && session?.orgId && (
        <Modal orgId={session.orgId} apiKey={session.apiKey} onClose={() => setShowModal(false)} onSave={() => { setShowModal(false); load() }} />
      )}
      {showImport && session?.orgId && (
        <CustomerImportModal orgId={session.orgId} apiKey={session.apiKey} onClose={() => setShowImport(false)} onImported={load} />
      )}
    </>
  )
}
