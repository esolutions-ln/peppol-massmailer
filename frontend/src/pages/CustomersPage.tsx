import { useEffect, useState, useMemo, Fragment, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import { listCustomers, registerCustomer, sendPeppolInvitation, updateCustomer, addCustomerContact } from '../api/client'
import { Users, Plus, X, Upload, Save, ChevronDown, ChevronRight, ChevronLeft, ArrowUpDown, ArrowUp, ArrowDown } from 'lucide-react'
import CustomerImportModal from './CustomerImportModal'
import type { Customer, Contact, CustomerContact, DeliveryMode, PageResponse } from '../types'
import { deriveParticipantId } from '../types'

const VALID_SORT_KEYS = ['name', 'email', 'companyName', 'totalInvoicesSent', 'lastInvoiceSentAt', 'totalDeliveryFailures'] as const
type SortKey = typeof VALID_SORT_KEYS[number]
type SortDir = 'asc' | 'desc'

// Frontend sort key → backend Customer entity field mapping
const SORT_FIELD_MAP: Record<string, string> = {
  name: 'companyName',
  email: 'companyName',
  companyName: 'companyName',
  totalInvoicesSent: 'totalInvoicesSent',
  lastInvoiceSentAt: 'lastInvoiceSentAt',
  totalDeliveryFailures: 'totalDeliveryFailures',
}

const PAGE_SIZES = [10, 25, 50, 100]

// ─── Modal ───────────────────────────────────────────

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
    deliveryMode: '' as DeliveryMode | ''
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
          <div className="form-group">
            <label>Delivery Mode</label>
            <select value={form.deliveryMode} onChange={set('deliveryMode')}>
              <option value="">Inherit from organisation</option>
              <option value="EMAIL">Email only</option>
              <option value="AS4">PEPPOL AS4 only</option>
              <option value="BOTH">Email + AS4</option>
            </select>
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>VAT Number {needsPeppol && !form.tinNumber.trim() ? '*' : ''}</label>
              <input value={form.vatNumber} onChange={set('vatNumber')} placeholder="e.g. 12345678" />
            </div>
            <div className="form-group">
              <label>TIN Number {needsPeppol && !form.vatNumber.trim() ? '*' : ''}</label>
              <input value={form.tinNumber} onChange={set('tinNumber')} placeholder="e.g. 1234567890" disabled={!!form.vatNumber.trim()} />
            </div>
          </div>
          {participantId && (
            <div style={{ background: '#f0f9ff', border: '1px solid #bae6fd', borderRadius: 7, padding: '8px 12px', marginBottom: 12, fontSize: 13, display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="text-muted">PEPPOL Participant ID:</span>
              <code style={{ fontWeight: 600, color: '#0284c7' }}>{participantId}</code>
              <span className="badge badge-blue" style={{ marginLeft: 'auto' }}>{form.vatNumber.trim() ? 'VAT' : 'TIN'}</span>
            </div>
          )}
          <div className="form-group">
            <label>ERP Source</label>
            <select value={form.erpSource} onChange={set('erpSource')}>
              {['ODOO', 'SAGE_INTACCT', 'QUICKBOOKS_ONLINE', 'DYNAMICS_365', 'GENERIC_API'].map(o => <option key={o}>{o}</option>)}
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

// ─── Detail Panel ─────────────────────────────────────

interface DetailPanelProps {
  orgId: string
  apiKey?: string
  customer: Customer
  onClose: () => void
  onSaved: (c: Customer) => void
}

function CustomerDetailPanel({ orgId, apiKey, customer, onClose, onSaved }: DetailPanelProps) {
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

  useEffect(() => {
    setContacts(customer.contacts ?? [])
    setForm(f => ({
      ...f,
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
    }))
    setError('')
    setSaved(false)
    setAddEmail('')
    setAddName('')
    setAddPhone('')
  }, [customer.id])

  const set = (k: keyof typeof form) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm(f => ({ ...f, [k]: e.target.value }))

  const handleAddContact = async () => {
    if (!addEmail) return
    setAdding(true)
    setError('')
    try {
      const res = await addCustomerContact(orgId, customer.id, { email: addEmail, name: addName, phone: addPhone }, apiKey)
      setContacts(res.data.contacts ?? [])
      onSaved(res.data)
      setAddEmail('')
      setAddName('')
      setAddPhone('')
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Failed to add contact.')
    } finally {
      setAdding(false)
    }
  }

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setError('')
    setSaved(false)
    try {
      const payload = {
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

  const pid = deriveParticipantId(form.vatNumber, form.tinNumber) || form.peppolParticipantId

  return (
    <aside style={{ width: 420, flexShrink: 0, background: '#fff', borderLeft: '1px solid #e5e7eb', height: '100%', overflowY: 'auto', boxShadow: '-4px 0 12px rgba(0,0,0,0.04)' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 18px', borderBottom: '1px solid #e5e7eb', position: 'sticky', top: 0, background: '#fff', zIndex: 1 }}>
        <div>
          <div style={{ fontWeight: 600 }}>{customer.companyName || contacts[0]?.name || contacts[0]?.email}</div>
          <div className="text-muted text-sm">{contacts.length} contact{contacts.length !== 1 ? 's' : ''}</div>
        </div>
        <button className="close-btn" onClick={onClose}><X size={18} /></button>
      </div>
      <form onSubmit={handleSave} style={{ padding: 18 }}>
        {error && <div className="alert alert-error">{error}</div>}
        {saved && <div className="alert alert-success" style={{ marginBottom: 12 }}>Changes saved.</div>}

        {/* Contacts List */}
        <div style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
            <strong style={{ fontSize: 14 }}>Contacts</strong>
          </div>
          {contacts.length === 0 && (
            <div className="text-muted text-sm" style={{ fontStyle: 'italic', marginBottom: 8 }}>No contacts</div>
          )}
          {contacts.map(c => (
            <div key={c.id} style={{
              display: 'flex', alignItems: 'center', gap: 8,
              padding: '6px 10px', marginBottom: 4,
              background: '#f9fafb', borderRadius: 6,
              border: '1px solid #e5e7eb', fontSize: 13
            }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {c.name || '—'}
                </div>
                <div className="text-muted" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {c.email}
                </div>
              </div>
              {c.phone && <span className="text-muted text-sm" style={{ flexShrink: 0 }}>{c.phone}</span>}
            </div>
          ))}
          {/* Add Contact Form */}
          <div style={{
            display: 'flex', gap: 6, marginTop: 8,
            flexWrap: 'wrap'
          }}>
            <input
              style={{ flex: '1 1 140px', minWidth: 0, fontSize: 13, padding: '5px 8px' }}
              type="email" placeholder="Email *" value={addEmail}
              onChange={e => setAddEmail(e.target.value)}
            />
            <input
              style={{ flex: '1 1 100px', minWidth: 0, fontSize: 13, padding: '5px 8px' }}
              placeholder="Name" value={addName}
              onChange={e => setAddName(e.target.value)}
            />
            <input
              style={{ flex: '1 1 100px', minWidth: 0, fontSize: 13, padding: '5px 8px' }}
              placeholder="Phone" value={addPhone}
              onChange={e => setAddPhone(e.target.value)}
            />
            <button type="button" className="btn btn-primary btn-sm" disabled={adding || !addEmail} onClick={handleAddContact}>
              {adding ? <span className="spinner" /> : 'Add'}
            </button>
          </div>
        </div>

        <hr style={{ border: 'none', borderTop: '1px solid #e5e7eb', margin: '0 -18px 16px' }} />

        {/* Company Details */}
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
              {['ODOO', 'SAGE_INTACCT', 'QUICKBOOKS_ONLINE', 'DYNAMICS_365', 'GENERIC_API'].map(o => <option key={o}>{o}</option>)}
            </select>
          </div>
          <div className="form-group">
            <label>ERP Customer ID</label>
            <input value={form.erpCustomerId ?? ''} onChange={set('erpCustomerId')} />
          </div>
        </div>
        <div className="form-group">
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <input type="checkbox" checked={!!form.unsubscribed} onChange={e => setForm(f => ({ ...f, unsubscribed: e.target.checked }))} />
            Unsubscribed (will not receive emails)
          </label>
        </div>
        <div style={{ background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 7, padding: 10, marginTop: 8, marginBottom: 16, fontSize: 12, color: '#475569' }}>
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

// ─── Sort Header ──────────────────────────────────────

interface SortHeaderProps {
  label: string
  sortKey: SortKey
  currentKey: SortKey | null
  currentDir: SortDir
  onSort: (key: SortKey) => void
}

function SortHeader({ label, sortKey, currentKey, currentDir, onSort }: SortHeaderProps) {
  const active = currentKey === sortKey
  return (
    <th style={{ cursor: 'pointer', userSelect: 'none' }} onClick={() => onSort(sortKey)}>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
        {label}
        {active ? (
          currentDir === 'asc' ? <ArrowUp size={12} /> : <ArrowDown size={12} />
        ) : (
          <ArrowUpDown size={12} style={{ opacity: 0.3 }} />
        )}
      </span>
    </th>
  )
}

// ─── Customer Row ─────────────────────────────────────────

interface CustomerRowProps {
  c: Customer
  contact: Contact | null
  groupByCompany: boolean
  selectedId: string | null
  inviteState: InviteState
  onSelect: (id: string) => void
  onInvite: (c: Customer) => void
}

type InviteState = { status: 'idle' } | { status: 'sending' } | { status: 'success' } | { status: 'error'; message: string }

function CustomerRow({ c, contact, groupByCompany, selectedId, inviteState, onSelect, onInvite }: CustomerRowProps) {
  const pid = c.peppolParticipantId ?? deriveParticipantId(c.vatNumber, c.tinNumber)
  const isSelected = c.id === selectedId
  const primary = contact
  return (
    <tr
      onClick={() => onSelect(c.id)}
      style={{ cursor: 'pointer', background: isSelected ? '#eff6ff' : undefined }}
    >
      {groupByCompany && <td></td>}
      <td>
        {c.erpCustomerId
          ? <code style={{ fontSize: 12 }}>{c.erpCustomerId}</code>
          : <span className="text-muted text-sm">—</span>}
      </td>
      <td>{primary?.name ?? '—'}</td>
      <td>{primary?.email ?? '—'}</td>
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
      <td style={{ fontWeight: 600 }}>{c.totalInvoicesSent}</td>
      <td style={{ color: c.totalDeliveryFailures > 0 ? '#dc2626' : undefined, fontWeight: c.totalDeliveryFailures > 0 ? 600 : undefined }}>
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
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }} onClick={e => e.stopPropagation()}>
            <button className="btn btn-secondary btn-sm" disabled={inviteState.status === 'sending'} onClick={() => onInvite(c)}>
              {inviteState.status === 'sending' ? <span className="spinner" /> : 'Invite to PEPPOL'}
            </button>
            {inviteState.status === 'success' && <span style={{ color: '#16a34a', fontSize: 12 }}>Invitation sent!</span>}
            {inviteState.status === 'error' && <span style={{ color: '#dc2626', fontSize: 12 }}>{inviteState.message}</span>}
          </div>
        )}
      </td>
    </tr>
  )
}

// ─── Group Row ───────────────────────────────────────────

function GroupRow({ company, members, expanded, onToggle }: {
  company: string
  members: Customer[]
  expanded: boolean
  onToggle: () => void
}) {
  const totalSent = members.reduce((s, c) => s + c.totalInvoicesSent, 0)
  const totalFailed = members.reduce((s, c) => s + c.totalDeliveryFailures, 0)
  return (
    <tr style={{ background: '#f8fafc', cursor: 'pointer' }} onClick={onToggle}>
      <td style={{ textAlign: 'center' }}>
        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
      </td>
      <td colSpan={12}>
        <span style={{ fontWeight: 600 }}>{company}</span>
        <span className="text-muted text-sm" style={{ marginLeft: 8 }}>
          {members.length} contact{members.length !== 1 ? 's' : ''}
          <span style={{ marginLeft: 12 }}>{totalSent} invoices sent</span>
          {totalFailed > 0 && <span style={{ marginLeft: 12, color: '#dc2626' }}>{totalFailed} failures</span>}
        </span>
      </td>
    </tr>
  )
}

// ─── Main Page ────────────────────────────────────────

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

  const setInviteState = (customerId: string, state: InviteState) =>
    setInviteStates(prev => ({ ...prev, [customerId]: state }))

  const handleInvite = async (customer: Customer) => {
    if (!session?.apiKey) return
    const email = customer.contacts?.[0]?.email
    if (!email) return
    setInviteState(customer.id, { status: 'sending' })
    try {
      await sendPeppolInvitation(session.apiKey, email)
      setInviteState(customer.id, { status: 'success' })
    } catch (err: any) {
      const message = err.response?.data?.message ?? 'Failed to send invitation.'
      setInviteState(customer.id, { status: 'error', message })
    }
  }

  const load = useCallback(() => {
    if (!session?.orgId) return
    setLoading(true)
    listCustomers(session.orgId, session.apiKey, {
      page, size: pageSize, sort: SORT_FIELD_MAP[sortKey] ?? 'companyName', dir: sortDir, search
    })
      .then(r => {
        setCustomers(r.data.content ?? [])
        setPageRes(r.data)
      })
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
    return Array.from(map.entries())
      .map(([company, members]) => ({ company, members }))
      .sort((a, b) => a.company.localeCompare(b.company))
  }, [customers, groupByCompany])

  // Grouped: page over groups; expanded groups show their members in the same slot
  const pagedResults = useMemo<{
    rows: React.ReactNode[]
    showing: string
  }>(() => {
    if (!groupByCompany) {
      const rows: React.ReactNode[] = []
      for (const c of customers) {
        const list = c.contacts && c.contacts.length > 0 ? c.contacts : [null]
        for (const ct of list) {
          rows.push(
            <CustomerRow
              key={`${c.id}:${ct?.id ?? '0'}`}
              c={c}
              contact={ct}
              groupByCompany={false}
              selectedId={selectedId}
              inviteState={inviteStates[c.id] ?? { status: 'idle' }}
              onSelect={setSelectedId}
              onInvite={handleInvite}
            />
          )
        }
      }
      return {
        rows,
        showing: `Page ${page + 1} of ${totalPages} (${totalRecords} records)`,
      }
    }

    // Grouped mode — page over groups
    const totalGroups = groups!.length
    const totalMembers = groups!.reduce((s, g) => s + g.members.length, 0)
    const pages = Math.max(1, Math.ceil(totalGroups / pageSize))
    const groupSlice = groups!.slice(page * pageSize, (page + 1) * pageSize)

    const rows: React.ReactNode[] = []
    for (const g of groupSlice) {
      const expanded = expandedGroups.has(g.company)
      rows.push(
        <Fragment key={g.company}>
          <GroupRow
            company={g.company}
            members={g.members}
            expanded={expanded}
            onToggle={() => {
              setExpandedGroups(prev => {
                const next = new Set(prev)
                if (next.has(g.company)) next.delete(g.company)
                else next.add(g.company)
                return next
              })
            }}
          />
          {expanded && g.members.flatMap(c => {
            const list = c.contacts && c.contacts.length > 0 ? c.contacts : [null]
            return list.map(ct => (
              <CustomerRow
                key={`${c.id}:${ct?.id ?? '0'}`}
                c={c}
                contact={ct}
                groupByCompany={true}
                selectedId={selectedId}
                inviteState={inviteStates[c.id] ?? { status: 'idle' }}
                onSelect={setSelectedId}
                onInvite={handleInvite}
              />
            ))
          })}
        </Fragment>
      )
    }

    return {
      rows,
      showing: `Page ${page + 1} of ${pages} (${totalGroups} groups, ${totalMembers} contacts)`,
    }
  }, [customers, groups, groupByCompany, page, pageSize, totalPages, totalRecords, expandedGroups, selectedId, inviteStates])

  const handleSort = (key: SortKey) => {
    if (key === sortKey) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    } else {
      setSortKey(key)
      setSortDir('asc')
    }
    setPage(0)
  }

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
          <div className="flex gap-3 mb-4" style={{ flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between' }}>
            <input
              style={{ maxWidth: 320 }}
              placeholder="Search by name, email, company, or customer ID…"
              value={search}
              onChange={e => { setSearch(e.target.value); setPage(0) }}
            />
            <div className="flex gap-2" style={{ alignItems: 'center' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, color: '#475569', cursor: 'pointer' }}>
                <input type="checkbox" checked={groupByCompany} onChange={() => { setGroupByCompany(g => !g); setPage(0); setExpandedGroups(new Set()) }} />
                Group by company
              </label>
              <span className="text-sm text-muted">{totalRecords} record{totalRecords !== 1 ? 's' : ''}</span>
            </div>
          </div>
          {loading ? (
            <div className="loading-center"><span className="spinner" /></div>
          ) : !session?.orgId ? (
            <div className="empty-state"><p>No organization ID available.</p></div>
          ) : customers.length === 0 ? (
            <div className="empty-state"><Users size={32} /><p>No customers found</p></div>
          ) : (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      {groupByCompany && <th style={{ width: 28 }}></th>}
                      <th>Customer ID</th>
                      <SortHeader label="Name" sortKey="name" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <SortHeader label="Email" sortKey="email" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <SortHeader label="Company" sortKey="companyName" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <th>PEPPOL ID</th>
                      <th>Delivery</th>
                      <th>ERP</th>
                      <SortHeader label="Invoices Sent" sortKey="totalInvoicesSent" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <SortHeader label="Failures" sortKey="totalDeliveryFailures" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <th>Unsubscribed</th>
                      <SortHeader label="Last Sent" sortKey="lastInvoiceSentAt" currentKey={sortKey} currentDir={sortDir} onSort={handleSort} />
                      <th>PEPPOL</th>
                    </tr>
                  </thead>
                  <tbody>
                    {pagedResults.rows}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              <div className="flex gap-3 mt-4" style={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
                <div className="flex gap-2" style={{ alignItems: 'center' }}>
                  <span className="text-sm text-muted">Rows per page:</span>
                  <select
                    style={{ padding: '4px 8px', border: '1px solid #e2e8f0', borderRadius: 6, fontSize: 13 }}
                    value={pageSize}
                    onChange={e => { setPageSize(Number(e.target.value)); setPage(0) }}
                  >
                    {PAGE_SIZES.map(s => <option key={s}>{s}</option>)}
                  </select>
                </div>
                <div className="flex gap-2" style={{ alignItems: 'center' }}>
                  <span className="text-sm text-muted">{pagedResults.showing}</span>
                  <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>
                    <ChevronLeft size={14} />
                  </button>
                  <button className="btn btn-secondary btn-sm" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>
                    <ChevronRight size={14} />
                  </button>
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
              orgId={session.orgId}
              apiKey={session.apiKey}
              customer={selected}
              onClose={() => setSelectedId(null)}
              onSaved={updated => setCustomers(list => list.map(c => c.id === updated.id ? updated as unknown as Customer : c))}
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
