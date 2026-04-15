import { useEffect, useState } from 'react'
import { listCampaigns } from '../../api/client'
import { Mail, RefreshCw } from 'lucide-react'
import type { Campaign } from '../../types'

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    COMPLETED: 'badge-green', IN_PROGRESS: 'badge-blue',
    PARTIALLY_FAILED: 'badge-yellow', FAILED: 'badge-red',
    QUEUED: 'badge-gray', CREATED: 'badge-gray'
  }
  return <span className={`badge ${map[status] ?? 'badge-gray'}`}>{status?.replace(/_/g, ' ')}</span>
}

export default function AdminCampaignsPage() {
  const [campaigns, setCampaigns] = useState<Campaign[]>([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')

  const load = () => {
    setLoading(true)
    listCampaigns()
      .then(r => setCampaigns(r.data ?? []))
      .catch(() => setCampaigns([]))
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  const filtered = campaigns.filter(c => !search || c.name?.toLowerCase().includes(search.toLowerCase()))

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">All Campaigns</span>
        <button className="btn btn-secondary btn-sm" onClick={load}><RefreshCw size={14} /> Refresh</button>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>All Campaigns</h2>
          <p>Platform-wide campaign overview</p>
        </div>
        <div className="card">
          <div className="mb-4">
            <input style={{ maxWidth: 280 }} placeholder="Search campaigns…" value={search} onChange={e => setSearch(e.target.value)} />
          </div>
          {loading ? (
            <div className="loading-center"><span className="spinner" /></div>
          ) : filtered.length === 0 ? (
            <div className="empty-state"><Mail size={32} /><p>No campaigns found</p></div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr><th>Name</th><th>Status</th><th>Total</th><th>Sent</th><th>Failed</th><th>Skipped</th><th>Created</th><th>Completed</th></tr>
                </thead>
                <tbody>
                  {filtered.map(c => (
                    <tr key={c.id}>
                      <td>
                        <div style={{ fontWeight: 500 }}>{c.name}</div>
                        <div className="text-sm text-muted">{c.id?.slice(0, 8)}…</div>
                      </td>
                      <td><StatusBadge status={c.status} /></td>
                      <td>{c.totalRecipients}</td>
                      <td style={{ color: '#16a34a' }}>{c.sent}</td>
                      <td style={{ color: c.failed > 0 ? '#dc2626' : undefined }}>{c.failed}</td>
                      <td>{c.skipped}</td>
                      <td className="text-sm text-muted">{c.createdAt ? new Date(c.createdAt).toLocaleString() : '—'}</td>
                      <td className="text-sm text-muted">{c.completedAt ? new Date(c.completedAt).toLocaleString() : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </>
  )
}
