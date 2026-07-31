import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { registerOrg, orgMemberLogin } from '../api/client'
import { CheckCircle, ChevronDown, ChevronUp, Mail, Wifi, Copy } from 'lucide-react'
import type { Organization, DeliveryMode } from '../types'
import { deriveParticipantId } from '../types'

const ERP_OPTIONS = ['GENERIC_API', 'ODOO', 'SAGE_INTACCT', 'QUICKBOOKS_ONLINE', 'DYNAMICS_365']

/** Derive a URL-safe slug from a company name. */
function slugify(name: string): string {
  return name.toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
}

/** Best-effort split of a full name into first/last; the surname is always the last word. */
function splitFullName(full: string): { first: string; last: string } {
  const parts = full.trim().split(/\s+/)
  if (parts.length === 0 || (parts.length === 1 && !parts[0])) return { first: '', last: '' }
  if (parts.length === 1) return { first: parts[0], last: '' }
  return { first: parts.slice(0, -1).join(' '), last: parts[parts.length - 1] }
}

// ─────────────────────────────────────────────────────────────────────────────

interface RegistrationCredentials { slug: string; email: string; password: string }

function RegistrationForm({ onCreated }: {
  onCreated: (org: Organization, creds: RegistrationCredentials) => void
}) {
  // Three required visible fields cover 90% of the form.
  const [fullName, setFullName] = useState('')
  const [workEmail, setWorkEmail] = useState('')
  const [companyName, setCompanyName] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')

  // Everything else is optional and starts collapsed.
  const [showMore, setShowMore] = useState(false)
  const [companyAddress, setCompanyAddress] = useState('')
  const [senderEmailOverride, setSenderEmailOverride] = useState('')
  const [primaryErpSource, setPrimaryErpSource] = useState('GENERIC_API')
  const [vatNumber, setVatNumber] = useState('')
  const [tinNumber, setTinNumber] = useState('')
  const [deliveryMode, setDeliveryMode] = useState<DeliveryMode>('EMAIL')

  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const needsPeppol = deliveryMode !== 'EMAIL'
  const orgParticipantId = deriveParticipantId(vatNumber, tinNumber)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')

    const { first, last } = splitFullName(fullName)
    if (!first || !last) {
      setError('Please enter your full name (first and last).')
      return
    }
    if (password.length < 8) {
      setError('Password must be at least 8 characters.')
      return
    }
    if (password !== passwordConfirm) {
      setError('Passwords do not match.')
      return
    }
    if (needsPeppol && !orgParticipantId) {
      setError('PEPPOL delivery needs a VAT or TIN number. Add one or switch to email-only.')
      return
    }

    setLoading(true)
    try {
      const payload = {
        user: { firstName: first, lastName: last, emailAddress: workEmail, password },
        name: companyName,
        slug: slugify(companyName),
        senderEmail: (senderEmailOverride || workEmail).trim().toLowerCase(),
        senderDisplayName: companyName,
        companyAddress: companyAddress || undefined,
        primaryErpSource: primaryErpSource || undefined,
        vatNumber: vatNumber.trim() || undefined,
        tinNumber: tinNumber.trim() || undefined,
        deliveryMode,
      }
      const res = await registerOrg(payload)
      onCreated(res.data, {
        slug: slugify(companyName),
        email: workEmail.trim().toLowerCase(),
        password,
      })
    } catch (err: any) {
      const msg = err.response?.data?.error ?? err.response?.data?.message
      setError(msg || 'Registration failed. Please check the form and try again.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <form onSubmit={handleSubmit}>
      <p className="text-sm text-muted" style={{ marginBottom: 20 }}>
        Three fields and you're in. Your billing plan is assigned by the eSolutions team after sign-up.
      </p>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="form-group">
        <label>Your name *</label>
        <input
          value={fullName}
          onChange={e => setFullName(e.target.value)}
          placeholder="Jane Moyo"
          autoComplete="name"
          required
        />
      </div>

      <div className="form-group">
        <label>Work email *</label>
        <input
          type="email"
          value={workEmail}
          onChange={e => setWorkEmail(e.target.value)}
          placeholder="jane@acme.co.zw"
          autoComplete="email"
          required
        />
      </div>

      <div className="form-group">
        <label>Company name *</label>
        <input
          value={companyName}
          onChange={e => setCompanyName(e.target.value)}
          placeholder="Acme Holdings (Pvt) Ltd"
          autoComplete="organization"
          required
        />
        {companyName && (
          <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 4 }}>
            Your dashboard URL: <code>/{slugify(companyName)}</code>
          </div>
        )}
      </div>

      <div className="form-group">
        <label>Password *</label>
        <input
          type="password"
          value={password}
          onChange={e => setPassword(e.target.value)}
          placeholder="At least 8 characters"
          autoComplete="new-password"
          minLength={8}
          required
        />
        <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 4 }}>
          Used to sign in to your dashboard. We also issue an API key for ERP integrations.
        </div>
      </div>

      <div className="form-group">
        <label>Confirm password *</label>
        <input
          type="password"
          value={passwordConfirm}
          onChange={e => setPasswordConfirm(e.target.value)}
          placeholder="Repeat your password"
          autoComplete="new-password"
          minLength={8}
          required
        />
      </div>

      {/* ── Advanced options (collapsed by default) ────────────────────── */}
      <button
        type="button"
        onClick={() => setShowMore(v => !v)}
        style={{
          background: 'transparent', border: 'none', cursor: 'pointer',
          display: 'flex', alignItems: 'center', gap: 6,
          padding: '8px 0', margin: '8px 0 16px 0',
          color: '#6366f1', fontSize: 13, fontWeight: 600
        }}
      >
        {showMore ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        {showMore ? 'Hide advanced options' : 'Add VAT, PEPPOL or ERP details (optional)'}
      </button>

      {showMore && (
        <div style={{ paddingBottom: 8 }}>
          <div className="form-group">
            <label>Sender email (override)</label>
            <input
              type="email"
              value={senderEmailOverride}
              onChange={e => setSenderEmailOverride(e.target.value)}
              placeholder={workEmail || 'no-reply@acme.co.zw'}
            />
            <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 4 }}>
              Defaults to your work email. Leave blank to use it.
            </div>
          </div>

          <div className="form-group">
            <label>Company address</label>
            <input
              value={companyAddress}
              onChange={e => setCompanyAddress(e.target.value)}
              placeholder="45 Borrowdale Road, Harare"
            />
          </div>

          <div className="form-group">
            <label>ERP system</label>
            <select value={primaryErpSource} onChange={e => setPrimaryErpSource(e.target.value)}>
              {ERP_OPTIONS.map(o => <option key={o} value={o}>{o}</option>)}
            </select>
          </div>

          <div className="form-group">
            <label>Invoice delivery</label>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8, marginTop: 4 }}>
              {([
                ['EMAIL', 'Email', Mail],
                ['AS4', 'PEPPOL', Wifi],
                ['BOTH', 'Both', CheckCircle],
              ] as [DeliveryMode, string, React.ElementType][]).map(([val, label, Icon]) => (
                <label
                  key={val}
                  style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    gap: 6, padding: '8px 10px',
                    border: `2px solid ${deliveryMode === val ? '#6366f1' : '#e2e8f0'}`,
                    borderRadius: 8, cursor: 'pointer', fontSize: 12, fontWeight: 600,
                    background: deliveryMode === val ? '#f5f3ff' : '#fff'
                  }}
                >
                  <input
                    type="radio"
                    name="deliveryMode"
                    value={val}
                    checked={deliveryMode === val}
                    onChange={() => setDeliveryMode(val)}
                    style={{ display: 'none' }}
                  />
                  <Icon size={14} color={deliveryMode === val ? '#6366f1' : '#64748b'} />
                  {label}
                </label>
              ))}
            </div>
          </div>

          {needsPeppol && (
            <div style={{
              background: '#f0f9ff', border: '1px solid #bae6fd',
              borderRadius: 8, padding: '12px 14px'
            }}>
              <div style={{ fontSize: 12, fontWeight: 600, color: '#0284c7', marginBottom: 8 }}>
                Your PEPPOL identity (one of VAT or TIN is required)
              </div>
              <div className="grid-2">
                <div className="form-group" style={{ margin: 0 }}>
                  <label>VAT number</label>
                  <input
                    value={vatNumber}
                    onChange={e => setVatNumber(e.target.value)}
                    placeholder="12345678"
                    disabled={!!tinNumber.trim()}
                  />
                </div>
                <div className="form-group" style={{ margin: 0 }}>
                  <label>TIN number</label>
                  <input
                    value={tinNumber}
                    onChange={e => setTinNumber(e.target.value)}
                    placeholder="1234567890"
                    disabled={!!vatNumber.trim()}
                  />
                </div>
              </div>
              {orgParticipantId && (
                <div style={{ marginTop: 8, fontSize: 12, color: '#0369a1' }}>
                  Participant ID: <code style={{ fontWeight: 700 }}>{orgParticipantId}</code>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      <button
        className="btn btn-primary"
        style={{ width: '100%', justifyContent: 'center', marginTop: 8 }}
        disabled={loading}
      >
        {loading ? <span className="spinner" /> : 'Create account'}
      </button>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────

function CredentialsSummary({ org, onContinue }: { org: Organization; onContinue: () => void }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    await navigator.clipboard.writeText(org.apiKey)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }
  return (
    <>
      <div className="alert alert-success" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <CheckCircle size={16} />
        <span>You're in, <strong>{org.name}</strong>.</span>
      </div>

      <p className="text-sm text-muted" style={{ marginTop: 8, marginBottom: 16 }}>
        Save your API key now — we don't show it again. Your billing rate will be assigned by an
        administrator shortly.
      </p>

      <label style={{ fontSize: 12, color: '#64748b', marginBottom: 4, display: 'block' }}>
        Organisation ID
      </label>
      <div className="api-key-box" style={{ marginBottom: 12 }}>{org.id}</div>

      <label style={{ fontSize: 12, color: '#64748b', marginBottom: 4, display: 'block' }}>
        API key
      </label>
      <div className="api-key-box">{org.apiKey}</div>
      <button
        type="button"
        className="btn btn-secondary copy-btn"
        onClick={copy}
        style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 6 }}
      >
        <Copy size={13} />
        {copied ? 'Copied!' : 'Copy API key'}
      </button>

      <button
        className="btn btn-primary mt-4"
        style={{ width: '100%', justifyContent: 'center' }}
        onClick={onContinue}
      >
        Go to dashboard
      </button>

      <p className="text-sm text-muted" style={{ marginTop: 12, textAlign: 'center' }}>
        Need PEPPOL access-point setup? Configure it from your dashboard.
      </p>
    </>
  )
}

// ─────────────────────────────────────────────────────────────────────────────

export default function RegisterPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [createdOrg, setCreatedOrg] = useState<Organization | null>(null)
  const [creds, setCreds] = useState<RegistrationCredentials | null>(null)

  const handleCreated = (org: Organization, c: RegistrationCredentials) => {
    setCreatedOrg(org)
    setCreds(c)
  }

  const handleGoToDashboard = async () => {
    if (!createdOrg) return

    // Prefer member session (email + password), fall back to API key if login fails
    // (e.g. registration was done without a password, which is no longer possible via the UI
    // but may happen via the API)
    if (creds) {
      try {
        const { data } = await orgMemberLogin(creds.slug, creds.email, creds.password)
        login({
          role: data.role === 'ORG_VIEWER' ? 'org_viewer' : 'org',
          name: data.displayName || data.email,
          apiKey: data.token,
          orgId: data.orgId,
          slug: data.orgSlug,
          memberId: data.memberId,
          email: data.email,
          memberRole: data.role,
        })
        navigate('/dashboard')
        return
      } catch {
        // fall through to API key login
      }
    }

    login({
      role: 'org',
      name: createdOrg.name,
      apiKey: createdOrg.apiKey,
      orgId: createdOrg.id,
      slug: createdOrg.slug,
    })
    navigate('/dashboard')
  }

  return (
    <div className="auth-page" style={{ alignItems: 'flex-start', paddingTop: 40 }}>
      <div className="auth-card" style={{ maxWidth: 480 }}>
        <div className="auth-logo">
          <h1>Invoice<span>Direct</span></h1>
          <p>{createdOrg ? 'Account created' : 'Get started'}</p>
        </div>

        {!createdOrg && <RegistrationForm onCreated={handleCreated} />}
        {createdOrg && <CredentialsSummary org={createdOrg} onContinue={handleGoToDashboard} />}

        {!createdOrg && (
          <>
            <div className="auth-divider">Already have an account?</div>
            <Link
              to="/login"
              className="btn btn-secondary"
              style={{ width: '100%', justifyContent: 'center' }}
            >
              Sign in
            </Link>
          </>
        )}
      </div>
    </div>
  )
}
