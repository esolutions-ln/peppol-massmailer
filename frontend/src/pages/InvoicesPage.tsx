import { useEffect, useState, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import { getMyInvoices, getMyInvoiceByNumber } from '../api/client'
import { FileText, RefreshCw, Search, X, Eye, Download } from 'lucide-react'
import type { InvoiceRecord } from '../types'

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    SENT: 'badge-green', FAILED: 'badge-red',
    PENDING: 'badge-gray', SKIPPED: 'badge-yellow', BOUNCED: 'badge-red'
  }
  return <span className={`badge ${map[status] ?? 'badge-gray'}`}>{status}</span>
}

function PdfPreviewModal({ invoice, onClose }: { invoice: InvoiceRecord; onClose: () => void }) {
  const hasPdf = !!invoice.pdfBase64
  const pdfSrc = hasPdf ? `data:application/pdf;base64,${invoice.pdfBase64}` : null

  const handleDownload = () => {
    if (!pdfSrc) return
    const a = document.createElement('a')
    a.href = pdfSrc
    a.download = invoice.pdfFileName ?? `${invoice.invoiceNumber}.pdf`
    a.click()
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal"
        style={{ maxWidth: 860, width: '95vw', maxHeight: '92vh', display: 'flex', flexDirection: 'column' }}
        onClick={e => e.stopPropagation()}
      >
        <div className="modal-header" style={{ flexShrink: 0 }}>
          <div>
            <span className="modal-title">{invoice.invoiceNumber}</span>
            <span className="text-sm text-muted" style={{ marginLeft: 10 }}>
              {invoice.recipientName ?? invoice.recipientEmail}
            </span>
          </div>
          <div className="flex gap-2">
            {hasPdf && (
              <button className="btn btn-secondary btn-sm" onClick={handleDownload}>
                <Download size={14} /> Download PDF
              </button>
            )}
            <button className="close-btn" onClick={onClose}><X size={18} /></button>
          </div>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 12, padding: '0 0 16px', flexShrink: 0 }}>
          {([
            ['Status', <StatusBadge key="s" status={invoice.status} />],
            ['Amount', invoice.currency && invoice.totalAmount ? `${invoice.currency} ${Number(invoice.totalAmount).toLocaleString()}` : '—'],
            ['VAT', invoice.currency && invoice.vatAmount ? `${invoice.currency} ${Number(invoice.vatAmount).toLocaleString()}` : '—'],
            ['Invoice Date', invoice.invoiceDate ?? '—'],
            ['Due Date', invoice.dueDate ?? '—'],
            ['Sent At', invoice.sentAt ? new Date(invoice.sentAt).toLocaleString() : '—'],
            ['Campaign', invoice.campaignName ?? '—'],
            ['Retries', invoice.retryCount ?? 0],
          ] as [string, React.ReactNode][]).map(([label, value]) => (
            <div key={label}>
              <div className="text-sm text-muted">{label}</div>
              <div style={{ fontWeight: 500, marginTop: 2, fontSize: 14 }}>{value}</div>
            </div>
          ))}
        </div>

        {invoice.errorMessage && (
          <div className="alert alert-error" style={{ flexShrink: 0, marginBottom: 12 }}>{invoice.errorMessage}</div>
        )}

        <div style={{ flex: 1, minHeight: 0, borderTop: '1px solid #e2e8f0', paddingTop: 16 }}>
          {hasPdf && pdfSrc ? (
            <iframe
              src={pdfSrc}
              title={`PDF preview — ${invoice.invoiceNumber}`}
              style={{ width: '100%', height: '100%', minHeight: 480, border: 'none', borderRadius: 6 }}
            />
          ) : (
            <div className="empty-state" style={{ paddingTop: 40 }}>
              <FileText size={36} />
              <p style={{ marginTop: 8 }}>No PDF attachment stored for this invoice</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

export default function InvoicesPage() {
  const { session } = useAuth()
  const [invoices, setInvoices] = useState<InvoiceRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [activeSearch, setActiveSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [selected, setSelected] = useState<InvoiceRecord | null>(null)

  const load = useCallback((invoiceSearch: string, status: string) => {
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
      getMyInvoices(session?.apiKey, params)
        .then(r => setInvoices(r.data ?? []))
        .catch(() => { setInvoices([]); setError('Failed to load invoices. Check your connection.') })
        .finally(() => setLoading(false))
    }
  }, [session?.apiKey])

  useEffect(() => {
    setActiveSearch('')
    setSearchInput('')
    load('', statusFilter)
  }, [statusFilter])

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault()
    const term = searchInput.trim()
    setActiveSearch(term)
    setStatusFilter('')
    load(term, '')
  }

  const handleClear = () => {
    setSearchInput('')
    setActiveSearch('')
    setError('')
    load('', statusFilter)
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
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Invoice #</th><th>Recipient</th><th>Campaign</th>
                      <th>Status</th><th>Amount</th><th>Sent At</th><th>Retries</th><th></th>
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
                          {inv.errorMessage && (
                            <div className="text-sm" style={{ color: '#dc2626', marginTop: 2, maxWidth: 200, wordBreak: 'break-word' }}>
                              {inv.errorMessage}
                            </div>
                          )}
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
            </>
          ) : null}
        </div>
      </div>
      {selected && <PdfPreviewModal invoice={selected} onClose={() => setSelected(null)} />}
    </>
  )
}
