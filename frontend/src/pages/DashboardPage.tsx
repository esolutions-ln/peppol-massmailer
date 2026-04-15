import { useEffect, useState } from 'react'
import { useAuth } from '../context/AuthContext'
import { getMyStats, getMyCampaigns, listCampaigns, listOrgs, getPeppolStats, getFailedDeliveries, retryDelivery } from '../api/client'
import { Mail } from 'lucide-react'
import type { Campaign, OrgStats, Organization, PeppolDeliveryStats, PeppolDelivery } from '../types'

function currentPeriod() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    COMPLETED: 'badge-green', IN_PROGRESS: 'badge-blue',
    PARTIALLY_FAILED: 'badge-yellow', FAILED: 'badge-red',
    QUEUED: 'badge-gray', CREATED: 'badge-gray'
  }
  return <span className={`badge ${map[status] ?? 'badge-gray'}`}>{status?.replace(/_/g, ' ')}</span>
}

interface AdminStats {
  totalCampaigns: number
  totalInvoices: number
  delivered: number
  failed: number
  orgs: number
}

function DeliveryDashboard({ orgId, apiKey }: { orgId: string; apiKey: string }) {
  const [peppolStats, setPeppolStats] = useState<PeppolDeliveryStats | null>(null)
  const [failedDeliveries, setFailedDeliveries] = useState<PeppolDelivery[]>([])
  const [loadingStats, setLoadingStats] = useState(true)
  const [retrying, setRetrying] = useState<string | null>(null)

  const loadFailedDeliveries = async () => {
    const res = await getFailedDeliveries(orgId, apiKey).catch(() => ({ data: [] as PeppolDelivery[] }))
    setFailedDeliveries(res.data ?? [])
  }

  useEffect(() => {
    const load = async () => {
      try {
        const [s] = await Promise.all([
          getPeppolStats(orgId, apiKey).catch(() => ({ data: null })),
          loadFailedDeliveries()
        ])
        setPeppolStats(s.data)
      } finally {
        setLoadingStats(false)
      }
    }
    load()
  }, [orgId, apiKey])

  const handleRetry = async (delivery: PeppolDelivery) => {
    setRetrying(delivery.id)
    try {
      await retryDelivery(orgId, delivery.id, apiKey)
      await loadFailedDeliveries()
    } catch {
      // retry failed — leave the item in the list
    } finally {
      setRetrying(null)
    }
  }

  return (
    <div className="card mt-6">
      <div className="card-header">
        <span className="card-title">PEPPOL Delivery</span>
        {peppolStats?.currentPeriod && (
          <span className="text-sm text-muted">{peppolStats.currentPeriod}</span>
        )}
      </div>

      {loadingStats ? (
        <div className="loading-center"><span className="spinner" /></div>
      ) : (
        <>
          {/* Stat cards */}
          <div className="stats-grid" style={{ padding: '1rem' }}>
            <div className="stat-card">
              <div className="stat-label">Total Dispatched</div>
              <div className="stat-value">{peppolStats?.totalDispatched ?? '—'}</div>
            </div>
            <div className="stat-card">
              <div className="stat-label">Delivered</div>
              <div className="stat-value" style={{ color: '#16a34a' }}>{peppolStats?.delivered ?? '—'}</div>
            </div>
            <div className="stat-card">
              <div className="stat-label">Failed</div>
              <div className="stat-value" style={{ color: (peppolStats?.failed ?? 0) > 0 ? '#dc2626' : undefined }}>
                {peppolStats?.failed ?? '—'}
              </div>
            </div>
            <div className="stat-card">
              <div className="stat-label">Success Rate</div>
              <div className="stat-value">
                {peppolStats != null ? `${peppolStats.successRate.toFixed(1)}%` : '—'}
              </div>
            </div>
          </div>

          {/* 30-day daily trend */}
          {(peppolStats?.dailyTrend?.length ?? 0) > 0 && (
            <div style={{ padding: '0 1rem 1rem' }}>
              <div className="card-title" style={{ marginBottom: '0.5rem', fontSize: '0.875rem' }}>30-Day Trend</div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr><th>Date</th><th>Delivered</th><th>Failed</th></tr>
                  </thead>
                  <tbody>
                    {peppolStats!.dailyTrend.map(d => (
                      <tr key={d.date}>
                        <td className="text-muted text-sm">{d.date}</td>
                        <td style={{ color: '#16a34a' }}>{d.delivered}</td>
                        <td style={{ color: d.failed > 0 ? '#dc2626' : undefined }}>{d.failed}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* Failed deliveries */}
          <div style={{ padding: '0 1rem 1rem' }}>
            <div className="card-title" style={{ marginBottom: '0.5rem', fontSize: '0.875rem' }}>
              Failed Deliveries {failedDeliveries.length > 0 && `(${failedDeliveries.length})`}
            </div>
            {failedDeliveries.length === 0 ? (
              <p className="text-muted text-sm">No failed deliveries.</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr><th>Invoice #</th><th>Receiver Participant ID</th><th>Error</th><th></th></tr>
                  </thead>
                  <tbody>
                    {failedDeliveries.map(d => (
                      <tr key={d.id}>
                        <td>{d.invoiceNumber ?? '—'}</td>
                        <td className="text-muted text-sm">{d.receiverParticipantId ?? '—'}</td>
                        <td className="text-sm" style={{ color: '#dc2626', maxWidth: '300px', wordBreak: 'break-word' }}>
                          {d.errorMessage ?? '—'}
                        </td>
                        <td>
                          <button
                            className="btn btn-sm"
                            disabled={retrying === d.id}
                            onClick={() => handleRetry(d)}
                          >
                            {retrying === d.id ? 'Retrying…' : 'Retry'}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  )
}

export default function DashboardPage() {
  const { session, isAdmin } = useAuth()
  const [stats, setStats] = useState<OrgStats | AdminStats | null>(null)
  const [campaigns, setCampaigns] = useState<Campaign[]>([])
  const [orgs, setOrgs] = useState<Organization[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const load = async () => {
      try {
        if (isAdmin) {
          const [c, o] = await Promise.all([
            listCampaigns().catch(() => ({ data: [] as Campaign[] })),
            listOrgs().catch(() => ({ data: [] as Organization[] }))
          ])
          const all = c.data ?? []
          setCampaigns(all.slice(0, 5))
          setOrgs(o.data ?? [])
          setStats({
            totalCampaigns: all.length,
            totalInvoices: all.reduce((s, c) => s + (c.totalRecipients ?? 0), 0),
            delivered: all.reduce((s, c) => s + (c.sent ?? 0), 0),
            failed: all.reduce((s, c) => s + (c.failed ?? 0), 0),
            orgs: (o.data ?? []).length
          })
        } else {
          const [s, c] = await Promise.all([
            getMyStats(session?.apiKey).catch(() => ({ data: null })),
            getMyCampaigns(session?.apiKey).catch(() => ({ data: [] as Campaign[] }))
          ])
          setStats(s.data)
          setCampaigns((c.data ?? []).slice(0, 5))
        }
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [])

  const adminStats = isAdmin ? (stats as AdminStats | null) : null
  const orgStats = !isAdmin ? (stats as OrgStats | null) : null

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Dashboard</span>
        <span className="text-sm text-muted">{currentPeriod()}</span>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Welcome back{session?.name ? `, ${session.name}` : ''}</h2>
          <p>{isAdmin ? 'Platform overview' : 'Your invoice delivery overview'}</p>
        </div>

        <div className="stats-grid">
          {isAdmin ? (
            <>
              <div className="stat-card">
                <div className="stat-label">Organizations</div>
                <div className="stat-value">{adminStats?.orgs ?? '—'}</div>
                <div className="stat-sub">registered</div>
              </div>
              <div className="stat-card">
                <div className="stat-label">Total Campaigns</div>
                <div className="stat-value">{adminStats?.totalCampaigns ?? '—'}</div>
                <div className="stat-sub">all time</div>
              </div>
              <div className="stat-card">
                <div className="stat-label">Invoices Sent</div>
                <div className="stat-value">{adminStats?.delivered?.toLocaleString() ?? '—'}</div>
                <div className="stat-sub">delivered</div>
              </div>
              <div className="stat-card">
                <div className="stat-label">Failed</div>
                <div className="stat-value" style={{ color: (adminStats?.failed ?? 0) > 0 ? '#dc2626' : undefined }}>
                  {adminStats?.failed ?? '—'}
                </div>
                <div className="stat-sub">delivery failures</div>
              </div>
            </>
          ) : (
            <>
              <div className="stat-card">
                <div className="stat-label">Total Campaigns</div>
                <div className="stat-value">{orgStats?.totalCampaigns ?? '—'}</div>
                <div className="stat-sub">all time</div>
              </div>
              <div className="stat-card">
                <div className="stat-label">Invoices Delivered</div>
                <div className="stat-value" style={{ color: '#16a34a' }}>
                  {orgStats?.delivered?.toLocaleString() ?? '—'}
                </div>
                <div className="stat-sub">of {orgStats?.totalInvoices?.toLocaleString() ?? '—'} total</div>
              </div>
              <div className="stat-card">
                <div className="stat-label">Failed</div>
                <div className="stat-value" style={{ color: (orgStats?.failed ?? 0) > 0 ? '#dc2626' : undefined }}>
                  {orgStats?.failed ?? '—'}
                </div>
                <div className="stat-sub">delivery failures</div>
              </div>
              <div className="stat-card">
                <div className="stat-label">This Month</div>
                <div className="stat-value">
                  {orgStats?.billingCurrency} {orgStats?.currentPeriodCost != null
                    ? Number(orgStats.currentPeriodCost).toFixed(2) : '—'}
                </div>
                <div className="stat-sub">{orgStats?.currentPeriod}</div>
              </div>
            </>
          )}
        </div>

        <div className="card">
          <div className="card-header">
            <span className="card-title">Recent Campaigns</span>
          </div>
          {loading ? (
            <div className="loading-center"><span className="spinner" /></div>
          ) : campaigns.length === 0 ? (
            <div className="empty-state"><Mail size={32} /><p>No campaigns yet</p></div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr><th>Name</th><th>Status</th><th>Recipients</th><th>Sent</th><th>Failed</th><th>Created</th></tr>
                </thead>
                <tbody>
                  {campaigns.map(c => (
                    <tr key={c.id}>
                      <td>{c.name}</td>
                      <td><StatusBadge status={c.status} /></td>
                      <td>{c.totalRecipients}</td>
                      <td style={{ color: '#16a34a' }}>{c.sent}</td>
                      <td style={{ color: c.failed > 0 ? '#dc2626' : undefined }}>{c.failed}</td>
                      <td className="text-muted text-sm">{c.createdAt ? new Date(c.createdAt).toLocaleDateString() : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {isAdmin && orgs.length > 0 && (
          <div className="card mt-6">
            <div className="card-header"><span className="card-title">Organizations</span></div>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Name</th><th>Slug</th><th>ERP</th><th>Status</th></tr></thead>
                <tbody>
                  {orgs.slice(0, 5).map(o => (
                    <tr key={o.id}>
                      <td>{o.name}</td>
                      <td className="text-muted">{o.slug}</td>
                      <td>{o.primaryErpSource ?? '—'}</td>
                      <td>
                        <span className={`badge ${o.status === 'ACTIVE' ? 'badge-green' : 'badge-red'}`}>{o.status}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {!isAdmin && session?.orgId && session?.apiKey && (
          <DeliveryDashboard orgId={session.orgId} apiKey={session.apiKey} />
        )}
      </div>
    </>
  )
}
