import { useEffect, useState } from 'react'
import { useAuth } from '../context/AuthContext'
import { getMyCampaigns, getMyCampaignDetail, retryCampaign } from '../api/client'
import { RefreshCw, Mail } from 'lucide-react'
import type { Campaign, CampaignDetail, InvoiceRecord } from '../types'

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    COMPLETED: 'badge-green', IN_PROGRESS: 'badge-blue',
    PARTIALLY_FAILED: 'badge-yellow', FAILED: 'badge-red',
    QUEUED: 'badge-gray', CREATED: 'badge-gray'
  }
  return <span className={`badge ${map[status] ?? 'badge-gray'}`}>{status?.replace(/_/g, ' ')}</span>
}

function RecipientStatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    SENT: 'badge-green', FAILED: 'badge-red', PENDING: 'badge-gray', SKIPPED: 'badge-yellow', BOUNCED: 'badge-red'
  }
  return <span className={`badge ${map[status] ?? 'badge-gray'}`}>{status}</span>
}

function CampaignDetailModal({ campaignId, apiKey, onClose }: { campaignId: string; apiKey?: string; onClose: () => void }) {
  const [detail, setDetail] = useState<CampaignDetail | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    getMyCampaignDetail(campaignId, apiKey)
      .then(r => setDetail(r.data))
      .catch(() => setDetail(null))
      .finally(() => setLoading(false))
  }, [campaignId])

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 720 }} onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span className="modal-title">{detail?.campaign?.name ?? 'Campaign Detail'}</span>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>
        {loading ? (
          <div className="loading-center"><span className="spinner" /></div>
        ) : !detail ? (
          <p className="text-muted">Could not load campaign detail.</p>
        ) : (
          <>
            <div className="stats-grid" style={{ marginBottom: 16 }}>
              {([['Total', detail.totalInvoices], ['Sent', detail.campaign.sent], ['Failed', detail.campaign.failed], ['Skipped', detail.campaign.skipped]] as [string, number][]).map(([label, val]) => (
                <div className="stat-card" key={label} style={{ padding: '12px 16px' }}>
                  <div className="stat-label">{label}</div>
                  <div className="stat-value" style={{ fontSize: 20 }}>{val}</div>
                </div>
              ))}
            </div>
            <div className="table-wrap" style={{ maxHeight: 360, overflowY: 'auto' }}>
              <table>
                <thead>
                  <tr><th>Invoice #</th><th>Recipient</th><th>Status</th><th>Amount</th><th>Sent At</th><th>Error</th></tr>
                </thead>
                <tbody>
                  {detail.invoices.map((inv: InvoiceRecord) => (
                    <tr key={inv.id}>
                      <td style={{ fontWeight: 500 }}>{inv.invoiceNumber}</td>
                      <td>
                        <div>{inv.recipientName ?? '—'}</div>
                        <div className="text-sm text-muted">{inv.recipientEmail}</div>
                      </td>
                      <td><RecipientStatusBadge status={inv.status} /></td>
                      <td>{inv.currency} {inv.totalAmount ? Number(inv.totalAmount).toLocaleString() : '—'}</td>
                      <td className="text-sm text-muted">{inv.sentAt ? new Date(inv.sentAt).toLocaleString() : '—'}</td>
                      <td className="text-sm" style={{ color: '#dc2626', maxWidth: 180, wordBreak: 'break-word' }}>
                        {inv.errorMessage ?? '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

export default function CampaignsPage() {
  const { session } = useAuth()
  const [campaigns, setCampaigns] = useState<Campaign[]>([])
  const [loading, setLoading] = useState(true)
  const [retrying, setRetrying] = useState<string | null>(null)
  const [msg, setMsg] = useState({ text: '', type: '' })
  const [statusFilter, setStatusFilter] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const load = () => {
    setLoading(true)
    const params: Record<string, string> = statusFilter ? { status: statusFilter } : {}
    getMyCampaigns(session?.apiKey, params)
      .then(r => setCampaigns(r.data ?? []))
      .catch(() => setCampaigns([]))
      .finally(() => setLoading(false))
  }

  useEffect(load, [statusFilter])

  const handleRetry = async (id: string) => {
    setRetrying(id)
    try {
      await retryCampaign(id, session?.apiKey)
      setMsg({ text: 'Retry queued successfully.', type: 'success' })
      load()
    } catch {
      setMsg({ text: 'Retry failed — check the campaign status.', type: 'error' })
    } finally {
      setRetrying(null)
      setTimeout(() => setMsg({ text: '', type: '' }), 4000)
    }
  }

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Campaigns</span>
        <div className="flex gap-2">
          <select
            style={{ padding: '6px 10px', border: '1px solid #e2e8f0', borderRadius: 7, fontSize: 13 }}
            value={statusFilter}
            onChange={e => setStatusFilter(e.target.value)}
          >
            <option value="">All statuses</option>
            {['QUEUED', 'IN_PROGRESS', 'COMPLETED', 'PARTIALLY_FAILED', 'FAILED'].map(s => (
              <option key={s}>{s}</option>
            ))}
          </select>
          <button className="btn btn-secondary btn-sm" onClick={load}>
            <RefreshCw size={14} /> Refresh
          </button>
        </div>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Email Campaigns</h2>
          <p>Track all invoice dispatch campaigns for your organization</p>
        </div>

        {msg.text && (
          <div className={`alert alert-${msg.type === 'error' ? 'error' : 'success'}`}>{msg.text}</div>
        )}

        <div className="card">
          {loading ? (
            <div className="loading-center"><span className="spinner" /></div>
          ) : campaigns.length === 0 ? (
            <div className="empty-state"><Mail size={32} /><p>No campaigns found</p></div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Campaign Name</th><th>Status</th><th>Total</th><th>Sent</th>
                    <th>Failed</th><th>Skipped</th><th>Created</th><th>Completed</th><th></th>
                  </tr>
                </thead>
                <tbody>
                  {campaigns.map(c => (
                    <tr key={c.id}>
                      <td>
                        <button
                          style={{ background: 'none', border: 'none', cursor: 'pointer', textAlign: 'left', padding: 0 }}
                          onClick={() => setSelectedId(c.id)}
                        >
                          <div style={{ fontWeight: 500, color: '#0ea5e9' }}>{c.name}</div>
                          <div className="text-sm text-muted">{c.id?.slice(0, 8)}…</div>
                        </button>
                      </td>
                      <td><StatusBadge status={c.status} /></td>
                      <td>{c.totalRecipients}</td>
                      <td style={{ color: '#16a34a' }}>{c.sent}</td>
                      <td style={{ color: c.failed > 0 ? '#dc2626' : undefined }}>{c.failed}</td>
                      <td>{c.skipped}</td>
                      <td className="text-sm text-muted">{c.createdAt ? new Date(c.createdAt).toLocaleString() : '—'}</td>
                      <td className="text-sm text-muted">{c.completedAt ? new Date(c.completedAt).toLocaleString() : '—'}</td>
                      <td>
                        {(c.status === 'PARTIALLY_FAILED' || c.status === 'FAILED') && (
                          <button
                            className="btn btn-secondary btn-sm"
                            onClick={() => handleRetry(c.id)}
                            disabled={retrying === c.id}
                          >
                            {retrying === c.id ? <span className="spinner" /> : <><RefreshCw size={12} /> Retry</>}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {selectedId && (
        <CampaignDetailModal
          campaignId={selectedId}
          apiKey={session?.apiKey}
          onClose={() => setSelectedId(null)}
        />
      )}
    </>
  )
}
