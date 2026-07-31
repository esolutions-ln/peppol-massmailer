import { useEffect, useState } from 'react'
import { listRateProfiles, createRateProfile } from '../../api/client'
import { Layers, Plus, X, Trash2 } from 'lucide-react'
import type { RateProfile, RateTier } from '../../types'

function CreateModal({ onClose, onSave }: { onClose: () => void; onSave: () => void }) {
  const [form, setForm] = useState({ name: '', description: '', currency: 'USD', monthlyBaseFee: 25 })
  const [tiers, setTiers] = useState<Partial<RateTier>[]>([
    { tierName: 'Base', fromInvoice: 1, toInvoice: 500, ratePerInvoice: 0.10 },
    { tierName: 'Volume', fromInvoice: 501, toInvoice: 2000, ratePerInvoice: 0.07 },
    { tierName: 'Enterprise', fromInvoice: 2001, toInvoice: null, ratePerInvoice: 0.04 }
  ])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const setField = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const setTier = (i: number, k: keyof RateTier) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setTiers(t => t.map((tier, idx) => idx === i ? { ...tier, [k]: e.target.value === '' ? null : e.target.value } : tier))

  const addTier = () => setTiers(t => [...t, { tierName: '', fromInvoice: undefined, toInvoice: null, ratePerInvoice: undefined }])
  const removeTier = (i: number) => setTiers(t => t.filter((_, idx) => idx !== i))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)
    try {
      const payload = {
        ...form,
        monthlyBaseFee: Number(form.monthlyBaseFee),
        tiers: tiers.map(t => ({
          tierName: t.tierName,
          fromInvoice: Number(t.fromInvoice),
          toInvoice: t.toInvoice ? Number(t.toInvoice) : null,
          ratePerInvoice: Number(t.ratePerInvoice)
        }))
      }
      await createRateProfile(payload)
      onSave()
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Failed to create rate profile.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 580 }}>
        <div className="modal-header">
          <span className="modal-title">Create Rate Profile</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="grid-2">
            <div className="form-group">
              <label>Profile Name *</label>
              <input value={form.name} onChange={setField('name')} placeholder="Standard" required />
            </div>
            <div className="form-group">
              <label>Currency</label>
              <select value={form.currency} onChange={setField('currency')}>
                {['USD', 'ZWG', 'ZAR', 'GBP', 'EUR'].map(c => <option key={c}>{c}</option>)}
              </select>
            </div>
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>Monthly Base Fee</label>
              <input type="number" step="0.01" value={form.monthlyBaseFee} onChange={setField('monthlyBaseFee')} />
            </div>
            <div className="form-group">
              <label>Description</label>
              <input value={form.description} onChange={setField('description')} />
            </div>
          </div>

          <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <label style={{ margin: 0 }}>Pricing Tiers</label>
            <button type="button" className="btn btn-secondary btn-sm" onClick={addTier}><Plus size={12} /> Add Tier</button>
          </div>
          {tiers.map((tier, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr 1fr auto', gap: 8, marginBottom: 8, alignItems: 'end' }}>
              <div className="form-group" style={{ margin: 0 }}>
                {i === 0 && <label>Tier Name</label>}
                <input value={tier.tierName ?? ''} onChange={setTier(i, 'tierName')} placeholder="Base" />
              </div>
              <div className="form-group" style={{ margin: 0 }}>
                {i === 0 && <label>From</label>}
                <input type="number" value={tier.fromInvoice ?? ''} onChange={setTier(i, 'fromInvoice')} required />
              </div>
              <div className="form-group" style={{ margin: 0 }}>
                {i === 0 && <label>To (blank=∞)</label>}
                <input type="number" value={tier.toInvoice ?? ''} onChange={setTier(i, 'toInvoice')} placeholder="∞" />
              </div>
              <div className="form-group" style={{ margin: 0 }}>
                {i === 0 && <label>Rate/Invoice</label>}
                <input type="number" step="0.001" value={tier.ratePerInvoice ?? ''} onChange={setTier(i, 'ratePerInvoice')} required />
              </div>
              <button type="button" className="btn btn-danger btn-sm" style={{ marginBottom: 0 }} onClick={() => removeTier(i)}>
                <Trash2 size={12} />
              </button>
            </div>
          ))}

          <div className="flex gap-2 mt-4">
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? <span className="spinner" /> : 'Create Profile'}
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function RateProfilesPage() {
  const [profiles, setProfiles] = useState<RateProfile[]>([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)

  const load = () => {
    setLoading(true)
    listRateProfiles().then(r => setProfiles(r.data ?? [])).catch(() => {}).finally(() => setLoading(false))
  }

  useEffect(load, [])

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Rate Profiles</span>
        <button className="btn btn-primary btn-sm" onClick={() => setShowModal(true)}><Plus size={14} /> New Profile</button>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Rate Profiles</h2>
          <p>Tiered pricing plans assigned to organizations</p>
        </div>
        {loading ? (
          <div className="loading-center"><span className="spinner" /></div>
        ) : profiles.length === 0 ? (
          <div className="empty-state"><Layers size={32} /><p>No rate profiles yet</p></div>
        ) : (
          <div style={{ display: 'grid', gap: 16 }}>
            {profiles.map(p => (
              <div className="card" key={p.id}>
                <div className="card-header">
                  <div>
                    <span className="card-title">{p.name}</span>
                    {p.description && <span className="text-sm text-muted" style={{ marginLeft: 8 }}>{p.description}</span>}
                  </div>
                  <div className="flex gap-2">
                    <span className="badge badge-blue">{p.currency}</span>
                    {p.active ? <span className="badge badge-green">Active</span> : <span className="badge badge-gray">Inactive</span>}
                  </div>
                </div>
                <div className="text-sm text-muted" style={{ marginBottom: 12 }}>
                  Monthly base fee: <strong>{p.currency} {Number(p.monthlyBaseFee).toFixed(2)}</strong>
                </div>
                <div className="table-wrap">
                  <table>
                    <thead><tr><th>Tier</th><th>From Invoice</th><th>To Invoice</th><th>Rate / Invoice</th></tr></thead>
                    <tbody>
                      {p.tiers?.map((t, i) => (
                        <tr key={i}>
                          <td>{t.tierName ?? `Tier ${i + 1}`}</td>
                          <td>{t.fromInvoice}</td>
                          <td>{t.toInvoice ?? '∞'}</td>
                          <td>{p.currency} {Number(t.ratePerInvoice).toFixed(3)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
      {showModal && <CreateModal onClose={() => setShowModal(false)} onSave={() => { setShowModal(false); load() }} />}
    </>
  )
}
