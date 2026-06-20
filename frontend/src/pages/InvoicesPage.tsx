import { useEffect, useState, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import { getMyInvoices, getMyInvoiceByNumber, listEmailTemplates } from '../api/client'
import type { EmailTemplate } from '../api/client'
import { FileText, RefreshCw, Search, X, Eye, Download, ChevronLeft, ChevronRight, Mail, Building2 } from 'lucide-react'
import type { InvoiceRecord, PageResponse } from '../types'

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    SENT: 'badge-green', FAILED: 'badge-red',
    PENDING: 'badge-gray', SKIPPED: 'badge-yellow', BOUNCED: 'badge-red'
  }
  return <span className={`badge ${map[status] ?? 'badge-gray'}`}>{status}</span>
}

// Render template placeholders with actual invoice values
function renderTemplate(body: string, invoice: InvoiceRecord, companyName: string): string {
  const fmt = (n?: number) => n != null ? Number(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—'
  return body
    .replace(/\{\{recipientName\}\}/g, invoice.recipientName ?? invoice.recipientEmail)
    .replace(/\{\{invoiceNumber\}\}/g, invoice.invoiceNumber)
    .replace(/\{\{invoiceDate\}\}/g, invoice.invoiceDate ?? '—')
    .replace(/\{\{dueDate\}\}/g, invoice.dueDate ?? '—')
    .replace(/\{\{totalAmount\}\}/g, fmt(invoice.totalAmount))
    .replace(/\{\{vatAmount\}\}/g, fmt(invoice.vatAmount))
    .replace(/\{\{currency\}\}/g, invoice.currency ?? '')
    .replace(/\{\{currencySymbol\}\}/g, invoice.currency === 'USD' ? '$' : invoice.currency === 'GBP' ? '£' : invoice.currency === 'EUR' ? '€' : (invoice.currency ?? ''))
    .replace(/\{\{companyName\}\}/g, companyName)
    .replace(/\{\{accountsEmail\}\}/g, '')
    .replace(/\{\{verificationCode\}\}/g, '')
}

function PdfPreviewModal({ invoice, onClose }: { invoice: InvoiceRecord; onClose: () => void }) {
  const { session } = useAuth()
  const hasPdf = !!invoice.pdfBase64
  const pdfSrc = hasPdf ? `data:application/pdf;base64,${invoice.pdfBase64}` : null
  const [template, setTemplate] = useState<EmailTemplate | null>(null)

  useEffect(() => {
    listEmailTemplates(session?.apiKey)
      .then(r => {
        const templates = r.data ?? []
        const def = templates.find(t => t.isDefault) ?? templates[0] ?? null
        setTemplate(def)
      })
      .catch(() => {})
  }, [session?.apiKey])

  const handleDownload = () => {
    if (!pdfSrc) return
    const a = document.createElement('a')
    a.href = pdfSrc
    a.download = invoice.pdfFileName ?? `${invoice.invoiceNumber}.pdf`
    a.click()
  }

  const companyName = session?.name ?? 'Your Organisation'
  const emailBody = template ? renderTemplate(template.body, invoice, companyName) : null
  const emailSubject = template ? renderTemplate(template.subject, invoice, companyName) : `Invoice ${invoice.invoiceNumber}`

  return (
    <div className="modal-overlay" style={{ padding: 16 }} onClick={onClose}>
      <div
        style={{
          background: '#fff', borderRadius: 16, width: '100%', maxWidth: 1400,
          maxHeight: 'calc(100vh - 32px)', display: 'flex', flexDirection: 'column',
          boxShadow: '0 25px 60px rgba(0,0,0,.35)', border: '1px solid #e8edf2', overflow: 'hidden'
        }}
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '20px 28px', borderBottom: '1px solid #f1f5f9', flexShrink: 0, background: '#fafbff' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
            <div style={{ width: 42, height: 42, borderRadius: 10, background: 'var(--brand-orange)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, boxShadow: '0 2px 8px rgba(232,93,27,.3)' }}>
              <FileText size={20} color="#fff" />
            </div>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{ fontSize: 18, fontWeight: 800, color: '#0f172a', letterSpacing: '-.2px' }}>{invoice.invoiceNumber}</span>
                {invoice.campaignName && <span className="badge badge-blue">{invoice.campaignName}</span>}
                <StatusBadge status={invoice.status} />
              </div>
              <div style={{ fontSize: 13, color: '#64748b', marginTop: 3 }}>
                {invoice.recipientName && <span style={{ fontWeight: 600, color: '#374151' }}>{invoice.recipientName}</span>}
                {invoice.recipientName && ' · '}
                <span>{invoice.recipientEmail}</span>
                {template && <span style={{ marginLeft: 8 }}>· Template: <span style={{ fontWeight: 600, color: 'var(--brand-orange)' }}>{template.name}</span></span>}
              </div>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {hasPdf && (
              <button className="btn btn-secondary btn-sm" onClick={handleDownload}>
                <Download size={14} /> Download PDF
              </button>
            )}
            <button className="close-btn" onClick={onClose}><X size={18} /></button>
          </div>
        </div>

        {/* Stats row */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: 1, borderBottom: '1px solid #f1f5f9', flexShrink: 0, background: '#f1f5f9' }}>
          {([
            ['Amount', invoice.currency && invoice.totalAmount != null ? `${invoice.currency} ${Number(invoice.totalAmount).toLocaleString()}` : '—'],
            ['VAT', invoice.currency && invoice.vatAmount != null ? `${invoice.currency} ${Number(invoice.vatAmount).toLocaleString()}` : '—'],
            ['Invoice Date', invoice.invoiceDate ?? '—'],
            ['Due Date', invoice.dueDate ?? '—'],
            ['Sent At', invoice.sentAt ? new Date(invoice.sentAt).toLocaleString() : '—'],
            ['Retries', String(invoice.retryCount ?? 0)],
          ] as [string, string][]).map(([label, value]) => (
            <div key={label} style={{ background: '#fff', padding: '12px 18px' }}>
              <div style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.07em', color: '#94a3b8', marginBottom: 4 }}>{label}</div>
              <div style={{ fontSize: 13, fontWeight: 600, color: '#0f172a' }}>{value}</div>
            </div>
          ))}
        </div>

        {invoice.errorMessage && (
          <div className="alert alert-error" style={{ flexShrink: 0, margin: '14px 28px 0', borderRadius: 8 }}>{invoice.errorMessage}</div>
        )}

        {/* Body: PDF left | recipient info right */}
        <div style={{ flex: 1, minHeight: 0, display: 'grid', gridTemplateColumns: hasPdf ? '1.2fr 500px' : '1fr', overflow: 'hidden' }}>

          {/* PDF preview */}
          {hasPdf && pdfSrc ? (
            <div style={{ borderRight: '1px solid #f1f5f9', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
              <iframe
                src={pdfSrc}
                title={`PDF — ${invoice.invoiceNumber}`}
                style={{ flex: 1, width: '100%', border: 'none', display: 'block', minHeight: 0 }}
              />
            </div>
          ) : (
            <div className="empty-state" style={{ paddingTop: 60 }}>
              <FileText size={40} />
              <p style={{ marginTop: 10 }}>No PDF attachment stored for this invoice</p>
            </div>
          )}

          {/* Right panel: recipient + template + email message */}
          <div style={{ display: 'flex', flexDirection: 'column', overflowY: 'auto', borderLeft: hasPdf ? '1px solid #f1f5f9' : 'none', background: '#fafbff' }}>

            {/* Recipient card */}
            <div style={{ padding: '20px 24px', borderBottom: '1px solid #f1f5f9', flexShrink: 0, background: '#fff' }}>
              <div style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.07em', color: '#94a3b8', marginBottom: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
                <Building2 size={12} /> Recipient
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div style={{ width: 44, height: 44, borderRadius: 11, background: 'linear-gradient(135deg, var(--brand-orange), var(--brand-orange-light))', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', fontWeight: 800, fontSize: 17, flexShrink: 0, boxShadow: '0 2px 8px rgba(232,93,27,.2)' }}>
                  {(invoice.recipientName ?? invoice.recipientEmail).charAt(0).toUpperCase()}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  {invoice.recipientName && (
                    <div style={{ fontWeight: 700, fontSize: 15, color: '#0f172a', marginBottom: 2 }}>{invoice.recipientName}</div>
                  )}
                  <div style={{ fontSize: 13, color: '#64748b', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{invoice.recipientEmail}</div>
                </div>
              </div>
            </div>

            {/* Template info */}
            {template && (
              <div style={{ padding: '16px 24px', borderBottom: '1px solid #f1f5f9', flexShrink: 0, background: '#fff' }}>
                <div style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.07em', color: '#94a3b8', marginBottom: 8 }}>
                  Email Template
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px', background: '#f0f9ff', border: '1px solid #bae6fd', borderRadius: 8 }}>
                  <Mail size={16} color="#0369a1" />
                  <div>
                    <div style={{ fontSize: 14, fontWeight: 700, color: '#0c4a6e' }}>{template.name}</div>
                    <div style={{ fontSize: 12, color: '#0369a1', marginTop: 1 }}>Subject: {emailSubject}</div>
                  </div>
                </div>
              </div>
            )}

            {/* Email message preview */}
            <div style={{ flex: 1, padding: '20px 24px', minHeight: 0, overflowY: 'auto' }}>
              <div style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.07em', color: '#94a3b8', marginBottom: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
                <Mail size={12} /> Message Sent
              </div>

              {/* Email chrome */}
              <div style={{ border: '1px solid #e8edf2', borderRadius: 10, overflow: 'hidden', background: '#fff', boxShadow: '0 2px 8px rgba(0,0,0,.05)' }}>
                {/* From / To / Subject */}
                <div style={{ background: '#f8fafc', borderBottom: '1px solid #e8edf2', padding: '12px 16px' }}>
                  <div style={{ display: 'grid', gridTemplateColumns: '56px 1fr', gap: '6px 10px', fontSize: 13 }}>
                    <span style={{ color: '#94a3b8', fontWeight: 600 }}>From</span>
                    <span style={{ color: '#374151', fontWeight: 500 }}>{companyName}</span>
                    <span style={{ color: '#94a3b8', fontWeight: 600 }}>To</span>
                    <span style={{ color: '#374151', fontWeight: 500 }}>{invoice.recipientName ? `${invoice.recipientName} <${invoice.recipientEmail}>` : invoice.recipientEmail}</span>
                    <span style={{ color: '#94a3b8', fontWeight: 600 }}>Subject</span>
                    <span style={{ color: '#0f172a', fontWeight: 700 }}>{emailSubject}</span>
                  </div>
                </div>
                {/* Body */}
                <div style={{ padding: '20px 16px', fontSize: 14, color: '#374151', lineHeight: 1.7, whiteSpace: 'pre-wrap', wordBreak: 'break-word', minHeight: 200 }}>
                  {emailBody ?? (
                    <span style={{ color: '#94a3b8', fontStyle: 'italic' }}>No email template found. Create one in Email Templates.</span>
                  )}
                </div>
                {/* Attachment indicator */}
                {hasPdf && (
                  <div style={{ borderTop: '1px solid #f1f5f9', padding: '12px 16px', display: 'flex', alignItems: 'center', gap: 10, background: '#fafbff' }}>
                    <div style={{ width: 32, height: 32, borderRadius: 7, background: '#fee2e2', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                      <FileText size={16} color="#dc2626" />
                    </div>
                    <div>
                      <div style={{ fontSize: 13, fontWeight: 600, color: '#0f172a' }}>{invoice.pdfFileName ?? `${invoice.invoiceNumber}.pdf`}</div>
                      <div style={{ fontSize: 11, color: '#94a3b8' }}>PDF attachment</div>
                    </div>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

export default function InvoicesPage() {
  const { session, isAdmin } = useAuth()
  const [invoices, setInvoices] = useState<InvoiceRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [activeSearch, setActiveSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [selected, setSelected] = useState<InvoiceRecord | null>(null)
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(0)

  const load = useCallback((invoiceSearch: string, status: string, pageNum: number) => {
    if (isAdmin) {
      setLoading(false)
      setInvoices([])
      setError('Admin view: open an organisation from the Organisations page to see its invoices.')
      return
    }
    setLoading(true)
    setError('')
    if (invoiceSearch.trim()) {
      getMyInvoiceByNumber(invoiceSearch.trim(), session?.apiKey)
        .then(r => { setInvoices(r.data ?? []); setError('') })
        .catch(err => {
          setInvoices([])
          setError(err.response?.status === 404
            ? `No invoice found for "${invoiceSearch.trim()}"`
            : 'Failed to load invoices. Check your connection.')
        })
        .finally(() => setLoading(false))
    } else {
      const params: Record<string, string> = {}
      if (status) params.status = status
      params.page = pageNum.toString()
      params.size = pageSize.toString()
      getMyInvoices(session?.apiKey, params)
        .then(r => {
          setInvoices(r.data.content ?? [])
          setTotalElements(r.data.totalElements)
          setTotalPages(r.data.totalPages)
        })
        .catch(() => { setInvoices([]); setError('Failed to load invoices. Check your connection.') })
        .finally(() => setLoading(false))
    }
  }, [session?.apiKey, isAdmin, pageSize])

  useEffect(() => {
    if (activeSearch) {
      load(activeSearch, '', page)
    } else {
      load('', statusFilter, page)
    }
  }, [page])

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault()
    const term = searchInput.trim()
    setActiveSearch(term)
    setStatusFilter('')
    setPage(0)
    load(term, '', 0)
  }

  const handleClear = () => {
    setSearchInput('')
    setActiveSearch('')
    setError('')
    setPage(0)
    load('', statusFilter, 0)
  }

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Emails Sent</span>
        <button className="btn btn-secondary btn-sm" onClick={() => load(activeSearch, statusFilter)}>
          <RefreshCw size={14} /> Refresh
        </button>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Emails Sent</h2>
          <p>All invoice emails dispatched through your account</p>
        </div>
        <div className="card">
          <div className="flex gap-3 mb-4" style={{ flexWrap: 'wrap', alignItems: 'center' }}>
            <form onSubmit={handleSearch} style={{ display: 'flex', gap: 8 }}>
              <input
                style={{ width: 260 }}
                placeholder="Search by invoice number..."
                value={searchInput}
                onChange={e => setSearchInput(e.target.value)}
              />
              <button type="submit" className="btn btn-secondary btn-sm"><Search size={14} /></button>
              {activeSearch && (
                <button type="button" className="btn btn-secondary btn-sm" onClick={handleClear}>Clear</button>
              )}
            </form>
            <select
              style={{ padding: '6px 10px', border: '1px solid #e2e8f0', borderRadius: 7, fontSize: 13 }}
              value={statusFilter}
              disabled={!!activeSearch}
              onChange={e => setStatusFilter(e.target.value)}
            >
              <option value="">All statuses</option>
              {['SENT', 'FAILED', 'PENDING', 'SKIPPED', 'BOUNCED'].map(s => <option key={s}>{s}</option>)}
            </select>
          </div>

          {error && <div className="alert alert-error" style={{ marginBottom: 12 }}>{error}</div>}

              {loading ? (
                <div className="loading-center"><span className="spinner" /></div>
              ) : invoices.length === 0 && !error ? (
                <div className="empty-state"><FileText size={32} /><p>No emails found</p></div>
              ) : invoices.length > 0 ? (
                <>
                  <div className="text-sm text-muted mb-4">
                    {invoices.length} record{invoices.length !== 1 ? 's' : ''}
                    {activeSearch && <span> matching <strong>{activeSearch}</strong></span>}
                    {totalElements > 0 && (
                      <span>
                        &nbsp;of <strong>{totalElements.toLocaleString()}</strong>
                      </span>
                    )}
                  </div>
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>Invoice #</th><th>Recipient</th><th>Campaign</th>
                          <th>Status</th><th>Reason</th><th>Amount</th><th>Sent At</th><th>Retries</th><th></th>
                        </tr>
                      </thead>
                      <tbody>
                        {invoices.map(inv => (
                          <tr key={inv.id}>
                            <td style={{ fontWeight: 500 }}>{inv.invoiceNumber}</td>
                            <td>
                              <div>{inv.recipientName ?? '—'}</div>
                              <div className="text-sm text-muted">{inv.recipientEmail}</div>
                            </td>
                            <td className="text-sm text-muted">{inv.campaignName ?? '—'}</td>
                            <td>
                              <StatusBadge status={inv.status} />
                            </td>
                            <td className="text-sm" style={{ maxWidth: 220, wordBreak: 'break-word', color: inv.errorMessage ? '#dc2626' : '#94a3b8' }}>
                              {inv.errorMessage || '—'}
                            </td>
                            <td>{inv.currency && inv.totalAmount ? `${inv.currency} ${Number(inv.totalAmount).toLocaleString()}` : '—'}</td>
                            <td className="text-sm text-muted">{inv.sentAt ? new Date(inv.sentAt).toLocaleString() : '—'}</td>
                            <td>{inv.retryCount ?? 0}</td>
                            <td>
                              <button className="btn btn-secondary btn-sm" onClick={() => setSelected(inv)}>
                                <Eye size={13} /> View
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  {totalPages > 1 && (
                    <div className="pagination-controls mt-4 flex justify-center items-center space-x-2">
                      <button
                        className="btn btn-secondary btn-sm flex items-center"
                        onClick={() => setPage(Math.max(0, page - 1))}
                        disabled={page === 0}
                      >
                        <ChevronLeft size={16} />
                        <span className="ml-1">Previous</span>
                      </button>
                      <span className="text-sm">
                        Page {page + 1} of {totalPages}
                      </span>
                      <button
                        className="btn btn-secondary btn-sm flex items-center"
                        onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
                        disabled={page === totalPages - 1}
                      >
                        <span className="mr-1">Next</span>
                        <ChevronRight size={16} />
                      </button>
                    </div>
                  )}
                </>
              ) : null}
        </div>
      </div>
      {selected && <PdfPreviewModal invoice={selected} onClose={() => setSelected(null)} />}
    </>
  )
}
