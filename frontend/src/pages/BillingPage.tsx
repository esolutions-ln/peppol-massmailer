import { useEffect, useState } from 'react'
import {
  getBillingSummaryAdmin, getBillingHistoryAdmin, getUsageRecordsAdmin,
  listRateProfiles, estimateCost
} from '../api/client'
import { CreditCard } from 'lucide-react'
import type { BillingPeriodSummary, UsageRecord, RateProfile, CostEstimate } from '../types'

function currentPeriod() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function BillingStatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = { OPEN: 'badge-blue', CLOSED: 'badge-gray', INVOICED: 'badge-yellow', PAID: 'badge-green' }
  return <span className={`badge ${map[status] ?? 'badge-gray'}`}>{status}</span>
}

export default function BillingPage() {
  const [period, setPeriod] = useState(currentPeriod())
  const [tab, setTab] = useState('summary')
  const [orgIdInput, setOrgIdInput] = useState('')
  const [selectedOrgId, setSelectedOrgId] = useState('')
  const [summary, setSummary] = useState<BillingPeriodSummary | null>(null)
  const [history, setHistory] = useState<BillingPeriodSummary[]>([])
  const [usage, setUsage] = useState<UsageRecord[]>([])
  const [rateProfiles, setRateProfiles] = useState<RateProfile[]>([])
  const [estimate, setEstimate] = useState<CostEstimate | null>(null)
  const [estForm, setEstForm] = useState({ rateProfileId: '', invoiceCount: 500 })
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    listRateProfiles().then(r => setRateProfiles(r.data ?? [])).catch(() => {})
  }, [])

  useEffect(() => {
    if (!selectedOrgId) return
    setLoading(true)
    Promise.all([
      getBillingSummaryAdmin(selectedOrgId, period).catch(() => ({ data: null })),
      getBillingHistoryAdmin(selectedOrgId).catch(() => ({ data: [] })),
      getUsageRecordsAdmin(selectedOrgId, period).catch(() => ({ data: [] }))
    ]).then(([s, h, u]) => {
      setSummary(s.data)
      setHistory((h.data ?? []) as BillingPeriodSummary[])
      setUsage((u.data ?? []) as UsageRecord[])
    }).finally(() => setLoading(false))
  }, [selectedOrgId, period])

  const handleOrgSearch = (e: React.FormEvent) => {
    e.preventDefault()
    setSelectedOrgId(orgIdInput.trim())
  }

  const clearOrg = () => {
    setSelectedOrgId('')
    setOrgIdInput('')
    setSummary(null)
    setHistory([])
    setUsage([])
  }

  const handleEstimate = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      const res = await estimateCost({ rateProfileId: estForm.rateProfileId, invoiceCount: Number(estForm.invoiceCount) })
      setEstimate(res.data)
    } catch { setEstimate(null) }
  }

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Billing</span>
        <input
          type="month" value={period} onChange={e => setPeriod(e.target.value)}
          style={{ width: 160, padding: '6px 10px', border: '1px solid #e2e8f0', borderRadius: 7, fontSize: 14 }}
        />
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Billing & Usage</h2>
          <p>View billing summaries, usage records and cost estimates for any organization</p>
        </div>

        <div className="flex gap-2 mb-4">
          {['summary', 'history', 'usage', 'estimate'].map(t => (
            <button key={t} className={`btn ${tab === t ? 'btn-primary' : 'btn-secondary'} btn-sm`} onClick={() => setTab(t)}>
              {t.charAt(0).toUpperCase() + t.slice(1)}
            </button>
          ))}
        </div>

        {tab !== 'estimate' && (
          <div className="card" style={{ marginBottom: 20 }}>
            <form onSubmit={handleOrgSearch} style={{ display: 'flex', gap: 10, alignItems: 'flex-end' }}>
              <div className="form-group" style={{ margin: 0, flex: 1 }}>
                <label>Organization ID</label>
                <input value={orgIdInput} onChange={e => setOrgIdInput(e.target.value)} placeholder="Paste organization UUID..." />
              </div>
              <button type="submit" className="btn btn-primary">Load</button>
              {selectedOrgId && <button type="button" className="btn btn-secondary" onClick={clearOrg}>Clear</button>}
            </form>
            {selectedOrgId && (
              <div className="text-sm text-muted" style={{ marginTop: 8 }}>
                Viewing: <span style={{ fontFamily: 'monospace' }}>{selectedOrgId}</span>
              </div>
            )}
          </div>
        )}

        {tab !== 'estimate' && !selectedOrgId ? (
          <div className="empty-state"><CreditCard size={32} /><p>Enter an organization ID above to load billing data</p></div>
        ) : tab !== 'estimate' && loading ? (
          <div className="loading-center"><span className="spinner" /></div>
        ) : tab === 'summary' ? (
          summary ? (
            <>
              <div className="stats-grid">
                {([['Total Submitted', summary.totalSubmitted], ['Delivered', summary.delivered], ['Failed', summary.failed], ['Billable', summary.billable]] as [string, number][]).map(([label, val]) => (
                  <div className="stat-card" key={label}>
                    <div className="stat-label">{label}</div>
                    <div className="stat-value">{val?.toLocaleString()}</div>
                  </div>
                ))}
              </div>
              <div className="card">
                <div className="card-header">
                  <span className="card-title">Cost Breakdown - {period}</span>
                  <BillingStatusBadge status={summary.status} />
                </div>
                <div className="grid-3">
                  <div><div className="text-sm text-muted">Rate Profile</div><div style={{ fontWeight: 600, marginTop: 4 }}>{summary.rateProfileName ?? '-'}</div></div>
                  <div><div className="text-sm text-muted">Base Fee</div><div style={{ fontWeight: 600, marginTop: 4 }}>{summary.currency} {Number(summary.baseFee).toFixed(2)}</div></div>
                  <div><div className="text-sm text-muted">Usage Charges</div><div style={{ fontWeight: 600, marginTop: 4 }}>{summary.currency} {Number(summary.usageCharges).toFixed(2)}</div></div>
                </div>
                <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid #e2e8f0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span className="text-muted">Total Amount</span>
                  <span style={{ fontSize: 22, fontWeight: 700 }}>{summary.currency} {Number(summary.totalAmount).toFixed(2)}</span>
                </div>
              </div>
            </>
          ) : (
            <div className="empty-state"><CreditCard size={32} /><p>No billing data for {period}</p></div>
          )
        ) : tab === 'history' ? (
          <div className="card">
            <div className="card-header"><span className="card-title">Billing History</span></div>
            {history.length === 0 ? (
              <div className="empty-state"><p>No billing history</p></div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Period</th><th>Billable</th><th>Base Fee</th><th>Usage</th><th>Total</th><th>Currency</th><th>Status</th></tr></thead>
                  <tbody>
                    {history.map(h => (
                      <tr key={h.billingPeriod}>
                        <td>{h.billingPeriod}</td><td>{h.billable}</td>
                        <td>{Number(h.baseFee).toFixed(2)}</td><td>{Number(h.usageCharges).toFixed(2)}</td>
                        <td style={{ fontWeight: 600 }}>{Number(h.totalAmount).toFixed(2)}</td>
                        <td>{h.currency}</td><td><BillingStatusBadge status={h.status} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        ) : tab === 'usage' ? (
          <div className="card">
            <div className="card-header"><span className="card-title">Usage Records - {period}</span></div>
            {usage.length === 0 ? (
              <div className="empty-state"><p>No usage records for {period}</p></div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Invoice #</th><th>Recipient</th><th>Outcome</th><th>Billable</th><th>ERP Source</th><th>PDF Size</th><th>Recorded</th></tr></thead>
                  <tbody>
                    {usage.map(u => (
                      <tr key={u.id}>
                        <td>{u.invoiceNumber}</td><td>{u.recipientEmail}</td>
                        <td><span className={`badge ${u.outcome === 'DELIVERED' ? 'badge-green' : u.outcome === 'FAILED' ? 'badge-red' : 'badge-yellow'}`}>{u.outcome}</span></td>
                        <td>{u.billable ? <span className="badge badge-blue">Yes</span> : <span className="badge badge-gray">No</span>}</td>
                        <td className="text-muted text-sm">{u.erpSource ?? '-'}</td>
                        <td className="text-muted text-sm">{u.pdfSizeBytes ? `${(u.pdfSizeBytes / 1024).toFixed(1)} KB` : '-'}</td>
                        <td className="text-sm text-muted">{u.recordedAt ? new Date(u.recordedAt).toLocaleString() : '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        ) : (
          <div className="card" style={{ maxWidth: 480 }}>
            <div className="card-header"><span className="card-title">Cost Estimator</span></div>
            <form onSubmit={handleEstimate}>
              <div className="form-group">
                <label>Rate Profile</label>
                <select value={estForm.rateProfileId} onChange={e => setEstForm(f => ({ ...f, rateProfileId: e.target.value }))} required>
                  <option value="">- Select -</option>
                  {rateProfiles.map(p => <option key={p.id} value={p.id}>{p.name} ({p.currency})</option>)}
                </select>
              </div>
              <div className="form-group">
                <label>Invoice Count</label>
                <input type="number" min="1" value={estForm.invoiceCount} onChange={e => setEstForm(f => ({ ...f, invoiceCount: Number(e.target.value) }))} required />
              </div>
              <button className="btn btn-primary">Estimate Cost</button>
            </form>
            {estimate && (
              <div style={{ marginTop: 20, padding: 16, background: '#f0f9ff', borderRadius: 8, border: '1px solid #bae6fd' }}>
                <div className="text-sm text-muted">Profile: {estimate.rateProfileName}</div>
                <div className="grid-3 mt-4">
                  <div><div className="text-sm text-muted">Base Fee</div><div style={{ fontWeight: 600 }}>{estimate.currency} {Number(estimate.baseFee).toFixed(2)}</div></div>
                  <div><div className="text-sm text-muted">Usage</div><div style={{ fontWeight: 600 }}>{estimate.currency} {Number(estimate.usageCharges).toFixed(2)}</div></div>
                  <div><div className="text-sm text-muted">Total</div><div style={{ fontWeight: 700, fontSize: 18 }}>{estimate.currency} {Number(estimate.totalEstimate).toFixed(2)}</div></div>
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </>
  )
}
