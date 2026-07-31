import { useEffect, useState, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import { getMyInvoices, getMyInvoiceByNumber } from '../api/client'
import { FileText, RefreshCw, Search, X, Eye, Download, ChevronLeft, ChevronRight } from 'lucide-react'
import type { InvoiceRecord, PageResponse } from '../types'

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

export default function CampaignsPage() {
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
    const params: Record<string, string> = {}
    if (status) params.status = status
    params.page = pageNum.toString()
    params.size = pageSize.toString()
    
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
        <span className="topbar-title">Invoices Sent</span>
        <button className="btn btn-secondary btn-sm" onClick={() => load(activeSearch, statusFilter)}>
          <RefreshCw size={14} /> Refresh
        </button>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Invoices Sent</h2>
          <p>Track every invoice dispatched — delivery status, failures, and pending</p>
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
            <div className="empty-state"><FileText size={32} /><p>No invoices sent yet</p></div>
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
