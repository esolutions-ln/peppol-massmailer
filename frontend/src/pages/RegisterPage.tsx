import { useState, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { registerOrg, listRateProfiles, registerAccessPoint } from '../api/client'
import { CheckCircle, Network, Mail, Wifi } from 'lucide-react'
import type { Organization, RateProfile, AccessPoint, AccessPointRole, DeliveryMode } from '../types'
import { deriveParticipantId } from '../types'

const ERP_OPTIONS = ['ODOO', 'SAGE_INTACCT', 'QUICKBOOKS_ONLINE', 'DYNAMICS_365', 'GENERIC_API']

function Steps({ current }: { current: number }) {
  const steps = ['Organisation', 'PEPPOL eRegistry', 'Done']
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 0, marginBottom: 28 }}>
      {steps.map((label, i) => {
        const idx = i + 1
        const done = current > idx
        const active = current === idx
        return (
          <div key={label} style={{ display: 'contents' }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
              <div style={{
                width: 28, height: 28, borderRadius: '50%', display: 'flex', alignItems: 'center',
                justifyContent: 'center', fontSize: 13, fontWeight: 600,
                background: done ? '#16a34a' : active ? '#6366f1' : '#e2e8f0',
                color: done || active ? '#fff' : '#64748b'
              }}>
                {done ? <CheckCircle size={14} /> : idx}
              </div>
              <span style={{ fontSize: 11, color: active ? '#6366f1' : done ? '#16a34a' : '#94a3b8', whiteSpace: 'nowrap' }}>
                {label}
              </span>
            </div>
            {i < steps.length - 1 && (
              <div style={{ flex: 1, height: 2, background: done ? '#16a34a' : '#e2e8f0', margin: '0 6px', marginBottom: 18 }} />
            )}
          </div>
        )
      })}
    </div>
  )
}

function OrgForm({ onCreated }: { onCreated: (org: Organization) => void }) {
  const [form, setForm] = useState({
    // User contact block
    firstName: '', lastName: '', jobTitle: '', userEmail: '',
    // Org fields
    name: '', slug: '', senderEmail: '', senderDisplayName: '',
    accountsEmail: '', companyAddress: '', primaryErpSource: 'GENERIC_API',
    erpTenantId: '', rateProfileId: '',
    deliveryMode: 'EMAIL' as DeliveryMode,
    vatNumber: '', tinNumber: ''
  })
  const [rateProfiles, setRateProfiles] = useState<RateProfile[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    listRateProfiles().then(r => setRateProfiles(r.data ?? [])).catch(() => {})
  }, [])

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const handleNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const name = e.target.value
    setForm(f => ({
      ...f, name,
      slug: f.slug || name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
    }))
  }

  // Derive org's own participant ID live
  const orgParticipantId = deriveParticipantId(form.vatNumber, form.tinNumber)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (form.deliveryMode !== 'EMAIL' && !orgParticipantId) {
      setError('AS4 delivery requires a VAT or TIN number to derive your PEPPOL Participant ID.')
      return
    }
    setLoading(true)
    try {
      const payload = {
        user: {
          firstName: form.firstName,
          lastName: form.lastName,
          jobTitle: form.jobTitle || undefined,
          emailAddress: form.userEmail,
        },
        name: form.name,
        slug: form.slug,
        senderEmail: form.senderEmail,
        senderDisplayName: form.senderDisplayName,
        accountsEmail: form.accountsEmail || undefined,
        companyAddress: form.companyAddress || undefined,
        primaryErpSource: form.primaryErpSource || undefined,
        erpTenantId: form.erpTenantId || undefined,
        rateProfileId: form.rateProfileId || undefined,
        deliveryMode: form.deliveryMode,
        vatNumber: form.vatNumber.trim() || undefined,
        tinNumber: form.tinNumber.trim() || undefined,
        peppolParticipantId: orgParticipantId ?? undefined,
      }
      const res = await registerOrg(payload)
      onCreated(res.data)
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Registration failed. Check your details.')
    } finally {
      setLoading(false)
    }
  }

  const needsPeppol = form.deliveryMode !== 'EMAIL'

  return (
    <>
      {error && <div className="alert alert-error">{error}</div>}
      <form onSubmit={handleSubmit}>

        {/* ── Primary Contact ── */}
        <div style={{ marginBottom: 16, paddingBottom: 16, borderBottom: '1px solid #e2e8f0' }}>
          <div style={{ fontSize: 13, fontWeight: 600, color: '#374151', marginBottom: 10 }}>Primary Contact</div>
          <div className="grid-2">
            <div className="form-group">
              <label>First Name *</label>
              <input value={form.firstName} onChange={set('firstName')} placeholder="John" required />
            </div>
            <div className="form-group">
              <label>Last Name *</label>
              <input value={form.lastName} onChange={set('lastName')} placeholder="Doe" required />
            </div>
          </div>
          <div className="grid-2">
            <div className="form-group">
              <label>Job Title</label>
              <input value={form.jobTitle} onChange={set('jobTitle')} placeholder="Finance Manager" />
            </div>
            <div className="form-group">
              <label>Contact Email *</label>
              <input type="email" value={form.userEmail} onChange={set('userEmail')} placeholder="john.doe@acme.co.zw" required />
            </div>
          </div>
        </div>

        {/* ── Organisation Details ── */}
        <div className="grid-2">
          <div className="form-group">
            <label>Company Name *</label>
            <input value={form.name} onChange={handleNameChange} placeholder="Acme Holdings (Pvt) Ltd" required />
          </div>
          <div className="form-group">
            <label>Slug *</label>
            <input value={form.slug} onChange={set('slug')} placeholder="acme-holdings" required />
          </div>
        </div>

        {/* Delivery mode selector */}
        <div className="form-group">
          <label>Invoice Delivery Mode *</label>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8, marginTop: 4 }}>
            {([
              ['EMAIL', 'Email only', 'Send invoices as PDF email attachments', Mail],
              ['AS4', 'PEPPOL AS4 only', 'Send via PEPPOL network to customer ERP', Wifi],
              ['BOTH', 'Email + AS4', 'Send both email and PEPPOL delivery', CheckCircle],
            ] as [DeliveryMode, string, string, React.ElementType][]).map(([val, label, desc, Icon]) => (
              <label
                key={val}
                style={{
                  display: 'flex', flexDirection: 'column', gap: 4, padding: '10px 12px',
                  border: `2px solid ${form.deliveryMode === val ? '#6366f1' : '#e2e8f0'}`,
                  borderRadius: 8, cursor: 'pointer',
                  background: form.deliveryMode === val ? '#f5f3ff' : '#fff'
                }}
              >
                <input type="radio" name="deliveryMode" value={val} checked={form.deliveryMode === val}
                  onChange={() => setForm(f => ({ ...f, deliveryMode: val }))} style={{ display: 'none' }} />
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 600, fontSize: 13 }}>
                  <Icon size={14} color={form.deliveryMode === val ? '#6366f1' : '#64748b'} />
                  {label}
                </div>
                <span style={{ fontSize: 11, color: '#64748b', lineHeight: 1.3 }}>{desc}</span>
              </label>
            ))}
          </div>
        </div>

        {/* PEPPOL identity — only required for AS4/BOTH */}
        {needsPeppol && (
          <div style={{ background: '#f0f9ff', border: '1px solid #bae6fd', borderRadius: 8, padding: '12px 14px', marginBottom: 12 }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: '#0284c7', marginBottom: 8 }}>
              Your PEPPOL Identity (Zimbabwe)
            </div>
            <div className="grid-2">
              <div className="form-group" style={{ margin: 0 }}>
                <label>VAT Number {needsPeppol && !form.tinNumber && '*'}</label>
                <input value={form.vatNumber} onChange={set('vatNumber')} placeholder="e.g. 12345678" />
              </div>
              <div className="form-group" style={{ margin: 0 }}>
                <label>TIN Number {needsPeppol && !form.vatNumber && '*'}</label>
                <input value={form.tinNumber} onChange={set('tinNumber')} placeholder="e.g. 1234567890"
                  disabled={!!form.vatNumber.trim()} />
              </div>
            </div>
            {orgParticipantId && (
              <div style={{ marginTop: 8, fontSize: 12, color: '#0369a1' }}>
                Your Participant ID: <code style={{ fontWeight: 700 }}>{orgParticipantId}</code>
                <span className="badge badge-blue" style={{ marginLeft: 8 }}>
                  {form.vatNumber.trim() ? 'VAT' : 'TIN'}
                </span>
              </div>
            )}
          </div>
        )}

        <div className="grid-2">
          <div className="form-group">
            <label>Sender Email *</label>
            <input type="email" value={form.senderEmail} onChange={set('senderEmail')} placeholder="noreply@acme.co.zw" required />
          </div>
          <div className="form-group">
            <label>Sender Display Name *</label>
            <input value={form.senderDisplayName} onChange={set('senderDisplayName')} placeholder="Acme Accounts" required />
          </div>
        </div>
        <div className="grid-2">
          <div className="form-group">
            <label>Accounts Email</label>
            <input type="email" value={form.accountsEmail} onChange={set('accountsEmail')} placeholder="accounts@acme.co.zw" />
          </div>
          <div className="form-group">
            <label>ERP System</label>
            <select value={form.primaryErpSource} onChange={set('primaryErpSource')}>
              {ERP_OPTIONS.map(o => <option key={o}>{o}</option>)}
            </select>
          </div>
        </div>
        <div className="form-group">
          <label>Company Address</label>
          <input value={form.companyAddress} onChange={set('companyAddress')} placeholder="45 Borrowdale Road, Harare" />
        </div>
        <div className="grid-2">
          <div className="form-group">
            <label>ERP Tenant ID</label>
            <input value={form.erpTenantId} onChange={set('erpTenantId')} placeholder="acme-holdings" />
          </div>
          <div className="form-group">
            <label>Rate Profile</label>
            <select value={form.rateProfileId} onChange={set('rateProfileId')}>
              <option value="">- Select plan -</option>
              {rateProfiles.map(p => <option key={p.id} value={p.id}>{p.name} ({p.currency})</option>)}
            </select>
          </div>
        </div>
        <button className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }} disabled={loading}>
          {loading ? <span className="spinner" /> : 'Register Organisation'}
        </button>
      </form>
    </>
  )
}

function PeppolForm({ org, onDone, onSkip }: { org: Organization; onDone: () => void; onSkip: () => void }) {
  const [form, setForm] = useState({
    participantId: org.peppolParticipantId ?? '',
    participantName: org.name + ' AP',
    role: 'GATEWAY' as AccessPointRole, endpointUrl: '', simplifiedHttpDelivery: true, deliveryAuthToken: ''
  })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [registered, setRegistered] = useState<AccessPoint | null>(null)
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await registerAccessPoint({
        ...form,
        organizationId: org.id,
        deliveryAuthToken: form.deliveryAuthToken || undefined,
        simplifiedHttpDelivery: form.simplifiedHttpDelivery === true || (form.simplifiedHttpDelivery as unknown as string) === 'true'
      }, org.apiKey)
      setRegistered(res.data)
    } catch (err: any) {
      setError(err.response?.data?.message ?? String(err.response?.data) ?? 'PEPPOL registration failed.')
    } finally {
      setLoading(false)
    }
  }

  if (registered) {
    return (
      <div>
        <div className="alert alert-success" style={{ marginBottom: 16 }}>PEPPOL Access Point registered successfully.</div>
        <div style={{ background: '#f8fafc', borderRadius: 8, padding: 14, marginBottom: 20, fontSize: 13 }}>
          <div style={{ marginBottom: 6 }}><span className="text-muted">Participant ID: </span><span style={{ fontFamily: 'monospace' }}>{registered.participantId}</span></div>
          <div style={{ marginBottom: 6 }}><span className="text-muted">Role: </span><span className="badge badge-blue">{registered.role}</span></div>
          <div><span className="text-muted">Endpoint: </span><span style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{registered.endpointUrl}</span></div>
        </div>
        <button className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }} onClick={onDone}>Continue to Dashboard</button>
      </div>
    )
  }

  return (
    <>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, marginBottom: 16, padding: 12, background: '#f0f9ff', borderRadius: 8, border: '1px solid #bae6fd' }}>
        <Network size={18} style={{ color: '#0284c7', flexShrink: 0, marginTop: 1 }} />
        <p className="text-sm" style={{ margin: 0, color: '#0369a1' }}>
          Register your PEPPOL Access Point to enable ERP-to-ERP invoice delivery. Participant ID format: <code>0190:ZW&lt;VAT_NUMBER&gt;</code>. You can skip and set up later.
        </p>
      </div>
      {error && <div className="alert alert-error">{error}</div>}
      <form onSubmit={handleSubmit}>
        <div className="grid-2">
          <div className="form-group">
            <label>Participant ID *</label>
            <input value={form.participantId} onChange={set('participantId')} placeholder="0190:ZW123456789" required />
          </div>
          <div className="form-group">
            <label>Role *</label>
            <select value={form.role} onChange={set('role')}>
              <option value="GATEWAY">GATEWAY (C2 - recommended)</option>
              <option value="RECEIVER">RECEIVER (C3)</option>
              <option value="SENDER">SENDER</option>
            </select>
          </div>
        </div>
        <div className="form-group">
          <label>Access Point Name *</label>
          <input value={form.participantName} onChange={set('participantName')} placeholder="Acme Holdings AP Gateway" required />
        </div>
        <div className="form-group">
          <label>Endpoint URL *</label>
          <input value={form.endpointUrl} onChange={set('endpointUrl')} placeholder="https://erp.acme.co.zw/peppol/receive" required />
        </div>
        <div className="form-group">
          <label>Auth Token (optional)</label>
          <input type="password" value={form.deliveryAuthToken} onChange={set('deliveryAuthToken')} placeholder="Bearer token for your endpoint" />
        </div>
        <div className="form-group">
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
            <input type="checkbox" style={{ width: 'auto' }} checked={form.simplifiedHttpDelivery}
              onChange={e => setForm(f => ({ ...f, simplifiedHttpDelivery: e.target.checked }))} />
            Use simplified HTTP delivery (instead of full AS4)
          </label>
        </div>
        <div className="flex gap-2">
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? <span className="spinner" /> : 'Register Access Point'}
          </button>
          <button type="button" className="btn btn-secondary" onClick={onSkip}>Skip for now</button>
        </div>
      </form>
    </>
  )
}

function CredentialsSummary({ org, onContinue }: { org: Organization; onContinue: () => void }) {
  return (
    <>
      <div className="alert alert-success">Organisation registered successfully.</div>
      <p className="text-sm text-muted" style={{ marginBottom: 12 }}>Save your API key — it will not be shown again.</p>
      <label>Organisation ID</label>
      <div className="api-key-box" style={{ marginBottom: 12 }}>{org.id}</div>
      <label>API Key</label>
      <div className="api-key-box">{org.apiKey}</div>
      <button className="btn btn-secondary copy-btn" onClick={() => navigator.clipboard.writeText(org.apiKey)}>Copy API Key</button>
      <button className="btn btn-primary mt-4" style={{ width: '100%', justifyContent: 'center' }} onClick={onContinue}>
        Next: PEPPOL eRegistry
      </button>
    </>
  )
}

export default function RegisterPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [step, setStep] = useState(1)
  const [createdOrg, setCreatedOrg] = useState<Organization | null>(null)

  const handleOrgCreated = (org: Organization) => { setCreatedOrg(org); setStep(2) }

  const handleGoToDashboard = () => {
    if (!createdOrg) return
    login({ role: 'org', name: createdOrg.name, apiKey: createdOrg.apiKey, orgId: createdOrg.id, slug: createdOrg.slug })
    navigate('/dashboard')
  }

  const indicatorStep = step <= 2 ? 1 : step === 3 ? 2 : 3

  return (
    <div className="auth-page" style={{ alignItems: 'flex-start', paddingTop: 40 }}>
      <div className="auth-card" style={{ maxWidth: 540 }}>
        <div className="auth-logo">
          <h1>Invoice<span>Direct</span></h1>
          <p>Register your organisation</p>
        </div>
        <Steps current={indicatorStep} />

        {step === 1 && <OrgForm onCreated={handleOrgCreated} />}
        {step === 2 && createdOrg && <CredentialsSummary org={createdOrg} onContinue={() => setStep(3)} />}
        {step === 3 && createdOrg && <PeppolForm org={createdOrg} onDone={handleGoToDashboard} onSkip={handleGoToDashboard} />}

        {step === 1 && (
          <>
            <div className="auth-divider">Already registered?</div>
            <Link to="/login" className="btn btn-secondary" style={{ width: '100%', justifyContent: 'center' }}>Sign In</Link>
          </>
        )}
      </div>
    </div>
  )
}
