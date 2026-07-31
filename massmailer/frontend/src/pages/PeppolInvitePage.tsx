import { useState, useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { validateInvitationToken, completeInvitation } from '../api/client'
import type { TokenValidationResponse } from '../types'

type PageState = 'loading' | 'error' | 'form' | 'success'

interface FormData {
  participantId: string
  endpointUrl: string
  deliveryAuthToken: string
  simplifiedHttpDelivery: boolean
}

interface SuccessData {
  participantId: string
  endpointUrl: string
}

export default function PeppolInvitePage() {
  const { token } = useParams<{ token: string }>()
  const [state, setState] = useState<PageState>('loading')
  const [tokenData, setTokenData] = useState<TokenValidationResponse | null>(null)
  const [errorMessage, setErrorMessage] = useState('')
  const [inlineError, setInlineError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [successData, setSuccessData] = useState<SuccessData | null>(null)
  const [form, setForm] = useState<FormData>({
    participantId: '',
    endpointUrl: '',
    deliveryAuthToken: '',
    simplifiedHttpDelivery: true,
  })

  useEffect(() => {
    if (!token) {
      setErrorMessage('Invalid invitation link.')
      setState('error')
      return
    }
    validateInvitationToken(token)
      .then(data => {
        setTokenData(data)
        setState('form')
      })
      .catch((err: any) => {
        const status = err.response?.status
        const msg = err.response?.data?.message
        if (status === 410) {
          setErrorMessage(msg ?? 'This invitation link has already been used or has expired.')
        } else if (status === 404) {
          setErrorMessage(msg ?? 'This invitation link is invalid.')
        } else {
          setErrorMessage(msg ?? 'Unable to validate invitation link. Please try again later.')
        }
        setState('error')
      })
  }, [token])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!token) return
    setInlineError('')
    setSubmitting(true)
    try {
      const result = await completeInvitation(token, {
        participantId: form.participantId,
        endpointUrl: form.endpointUrl,
        deliveryAuthToken: form.deliveryAuthToken || undefined,
        simplifiedHttpDelivery: form.simplifiedHttpDelivery,
      })
      setSuccessData(result)
      setState('success')
    } catch (err: any) {
      const status = err.response?.status
      const msg = err.response?.data?.message
      if (status === 400) {
        setInlineError(msg ?? 'Invalid submission. Please check your details.')
      } else {
        setInlineError(msg ?? 'An unexpected error occurred. Please try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (state === 'loading') {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <div className="auth-logo">
            <h1>Invoice<span>Direct</span></h1>
            <p>PEPPOL Self-Registration</p>
          </div>
          <div style={{ display: 'flex', justifyContent: 'center', padding: '24px 0' }}>
            <span className="spinner" />
          </div>
          <p style={{ textAlign: 'center', color: '#64748b', fontSize: 14 }}>Validating your invitation link...</p>
        </div>
      </div>
    )
  }

  if (state === 'error') {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <div className="auth-logo">
            <h1>Invoice<span>Direct</span></h1>
            <p>PEPPOL Self-Registration</p>
          </div>
          <div className="alert alert-error">{errorMessage}</div>
          <p style={{ textAlign: 'center', color: '#64748b', fontSize: 13, marginTop: 12 }}>
            If you believe this is a mistake, please contact the organisation that sent you this invitation.
          </p>
        </div>
      </div>
    )
  }

  if (state === 'success' && successData) {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <div className="auth-logo">
            <h1>Invoice<span>Direct</span></h1>
            <p>PEPPOL Self-Registration</p>
          </div>
          <div className="alert alert-success" style={{ marginBottom: 16 }}>
            You have successfully registered on the PEPPOL network.
          </div>
          <div style={{ background: '#f8fafc', borderRadius: 8, padding: 14, fontSize: 13 }}>
            <div style={{ marginBottom: 8 }}>
              <span className="text-muted">Participant ID: </span>
              <span style={{ fontFamily: 'monospace', fontWeight: 600 }}>{successData.participantId}</span>
            </div>
            <div>
              <span className="text-muted">Endpoint URL: </span>
              <span style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{successData.endpointUrl}</span>
            </div>
          </div>
          <p style={{ textAlign: 'center', color: '#64748b', fontSize: 13, marginTop: 16 }}>
            You can now receive invoices directly into your ERP system via PEPPOL.
          </p>
        </div>
      </div>
    )
  }

  // form state
  return (
    <div className="auth-page" style={{ alignItems: 'flex-start', paddingTop: 40 }}>
      <div className="auth-card" style={{ maxWidth: 480 }}>
        <div className="auth-logo">
          <h1>Invoice<span>Direct</span></h1>
          <p>PEPPOL Self-Registration</p>
        </div>

        {tokenData && (
          <div style={{ background: '#f0f9ff', border: '1px solid #bae6fd', borderRadius: 8, padding: '12px 14px', marginBottom: 16 }}>
            <div style={{ fontSize: 13, color: '#0369a1', marginBottom: 4 }}>
              <span style={{ fontWeight: 600 }}>{tokenData.organisationName}</span> has invited you to register on the PEPPOL network.
            </div>
            <div style={{ fontSize: 13, color: '#0369a1' }}>
              Registering as: <span style={{ fontWeight: 600 }}>{tokenData.customerEmail}</span>
            </div>
          </div>
        )}

        {inlineError && <div className="alert alert-error">{inlineError}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Participant ID *</label>
            <input
              value={form.participantId}
              onChange={e => setForm(f => ({ ...f, participantId: e.target.value }))}
              placeholder="e.g. 0190:ZW123456789"
              required
            />
            <span style={{ fontSize: 12, color: '#94a3b8' }}>Format: scheme:value (e.g. 0190:ZW123456789)</span>
          </div>
          <div className="form-group">
            <label>Endpoint URL *</label>
            <input
              value={form.endpointUrl}
              onChange={e => setForm(f => ({ ...f, endpointUrl: e.target.value }))}
              placeholder="https://erp.yourcompany.com/peppol/receive"
              required
            />
            <span style={{ fontSize: 12, color: '#94a3b8' }}>Must be a valid HTTPS URL</span>
          </div>
          <div className="form-group">
            <label>Auth Token (optional)</label>
            <input
              type="password"
              value={form.deliveryAuthToken}
              onChange={e => setForm(f => ({ ...f, deliveryAuthToken: e.target.value }))}
              placeholder="Bearer token for your endpoint"
            />
          </div>
          <div className="form-group">
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
              <input
                type="checkbox"
                style={{ width: 'auto' }}
                checked={form.simplifiedHttpDelivery}
                onChange={e => setForm(f => ({ ...f, simplifiedHttpDelivery: e.target.checked }))}
              />
              Use simplified HTTP delivery (instead of full AS4)
            </label>
          </div>
          <button
            type="submit"
            className="btn btn-primary"
            style={{ width: '100%', justifyContent: 'center' }}
            disabled={submitting}
          >
            {submitting ? <span className="spinner" /> : 'Complete Registration'}
          </button>
        </form>
      </div>
    </div>
  )
}
