import { useState } from 'react'
import { useAuth } from '../context/AuthContext'
import { sendSingleInvoiceUpload } from '../api/client'
import { Send, Upload, CheckCircle, AlertCircle } from 'lucide-react'

interface SendResult {
  status: string
  recipient: string
  invoiceNumber: string
  messageId?: string
  error?: string
}

export default function SendTestPage() {
  const { session } = useAuth()
  const [form, setForm] = useState({
    to: '',
    fromName: '',
    subject: '',
    invoiceNumber: '',
    recipientName: '',
    invoiceDate: new Date().toISOString().slice(0, 10),
    dueDate: '',
    totalAmount: '',
    vatAmount: '',
    currency: 'USD',
    templateName: 'invoice',
  })
  const [pdfFile, setPdfFile] = useState<File | null>(null)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<SendResult | null>(null)
  const [error, setError] = useState('')

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file && file.type !== 'application/pdf') {
      setError('Please select a valid PDF file.')
      setPdfFile(null)
      return
    }
    setError('')
    setPdfFile(file ?? null)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!pdfFile) {
      setError('Please attach a PDF file.')
      return
    }
    setError('')
    setResult(null)
    setLoading(true)

    try {
      const metadata: Record<string, unknown> = {
        to: form.to,
        subject: form.subject || `Invoice ${form.invoiceNumber}`,
        templateName: form.templateName,
        invoiceNumber: form.invoiceNumber,
        recipientName: form.recipientName || undefined,
        invoiceDate: form.invoiceDate || undefined,
        dueDate: form.dueDate || undefined,
        totalAmount: form.totalAmount ? parseFloat(form.totalAmount) : undefined,
        vatAmount: form.vatAmount ? parseFloat(form.vatAmount) : undefined,
        currency: form.currency,
        variables: {
          companyName: form.fromName || session?.name || 'eSolutions',
        },
      }

      // Remove undefined values
      Object.keys(metadata).forEach(k => {
        if (metadata[k] === undefined) delete metadata[k]
      })

      const fd = new FormData()
      fd.append('pdf', pdfFile)
      fd.append('metadata', JSON.stringify(metadata))

      const res = await sendSingleInvoiceUpload(fd, session?.apiKey)
      setResult(res.data as SendResult)
    } catch (err: any) {
      const msg = err.response?.data?.error ?? err.response?.data?.message ?? err.message ?? 'Send failed.'
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div>
      <div className="page-header">
        <div>
          <h2><Send size={20} style={{ marginRight: 8, verticalAlign: 'text-bottom' }} />Send Test Invoice</h2>
          <p style={{ color: '#94a3b8', marginTop: 4 }}>Upload a PDF and send it as an invoice email</p>
        </div>
      </div>

      {result && (
        <div className={`alert ${result.status === 'delivered' ? 'alert-success' : 'alert-error'}`}
             style={{ marginBottom: 20, display: 'flex', alignItems: 'flex-start', gap: 10 }}>
          {result.status === 'delivered'
            ? <CheckCircle size={18} style={{ marginTop: 2, flexShrink: 0 }} />
            : <AlertCircle size={18} style={{ marginTop: 2, flexShrink: 0 }} />}
          <div>
            <strong>{result.status === 'delivered' ? 'Email delivered!' : 'Delivery failed'}</strong>
            <div style={{ fontSize: 13, marginTop: 4 }}>
              To: {result.recipient} | Invoice: {result.invoiceNumber}
              {result.messageId && <> | Message-ID: <code style={{ fontSize: 12 }}>{result.messageId}</code></>}
              {result.error && <> | Error: {result.error}</>}
            </div>
          </div>
        </div>
      )}

      {error && <div className="alert alert-error" style={{ marginBottom: 20 }}>{error}</div>}

      <div className="card">
        <form onSubmit={handleSubmit}>
          {/* Email fields */}
          <div className="grid-2">
            <div className="form-group">
              <label>To Email *</label>
              <input type="email" value={form.to} onChange={set('to')} placeholder="recipient@example.com" required />
            </div>
            <div className="form-group">
              <label>From Name</label>
              <input value={form.fromName} onChange={set('fromName')} placeholder={session?.name || 'Your company name'} />
              <small style={{ color: '#64748b', fontSize: 12 }}>Displayed in the email template. The SMTP sender is configured server-side.</small>
            </div>
          </div>

          <div className="form-group">
            <label>Subject</label>
            <input value={form.subject} onChange={set('subject')} placeholder={`Invoice ${form.invoiceNumber || 'INV-001'}`} />
            <small style={{ color: '#64748b', fontSize: 12 }}>Defaults to "Invoice [number]" if left blank.</small>
          </div>

          {/* PDF Upload */}
          <div className="form-group">
            <label>PDF Attachment *</label>
            <div style={{
              border: '2px dashed #334155',
              borderRadius: 8,
              padding: 24,
              textAlign: 'center',
              cursor: 'pointer',
              background: pdfFile ? '#0f2a1a' : '#0f172a',
              transition: 'all 0.2s',
            }}
              onClick={() => document.getElementById('pdf-input')?.click()}
            >
              <input id="pdf-input" type="file" accept=".pdf,application/pdf" onChange={handleFileChange} style={{ display: 'none' }} />
              <Upload size={28} style={{ color: pdfFile ? '#4ade80' : '#64748b', marginBottom: 8 }} />
              {pdfFile ? (
                <div>
                  <div style={{ color: '#4ade80', fontWeight: 600 }}>{pdfFile.name}</div>
                  <div style={{ color: '#94a3b8', fontSize: 13 }}>{(pdfFile.size / 1024).toFixed(1)} KB</div>
                </div>
              ) : (
                <div style={{ color: '#94a3b8' }}>Click to select a PDF file</div>
              )}
            </div>
          </div>

          {/* Invoice details */}
          <div style={{ borderTop: '1px solid #1e293b', paddingTop: 20, marginTop: 20 }}>
            <h3 style={{ fontSize: 14, color: '#94a3b8', marginBottom: 16, fontWeight: 500 }}>Invoice Details</h3>
            <div className="grid-2">
              <div className="form-group">
                <label>Invoice Number *</label>
                <input value={form.invoiceNumber} onChange={set('invoiceNumber')} placeholder="INV-2026-0001" required />
              </div>
              <div className="form-group">
                <label>Recipient Name</label>
                <input value={form.recipientName} onChange={set('recipientName')} placeholder="Acme Corporation" />
              </div>
            </div>
            <div className="grid-2">
              <div className="form-group">
                <label>Invoice Date</label>
                <input type="date" value={form.invoiceDate} onChange={set('invoiceDate')} />
              </div>
              <div className="form-group">
                <label>Due Date</label>
                <input type="date" value={form.dueDate} onChange={set('dueDate')} />
              </div>
            </div>
            <div className="grid-2">
              <div className="form-group">
                <label>Total Amount</label>
                <input type="number" step="0.01" value={form.totalAmount} onChange={set('totalAmount')} placeholder="0.00" />
              </div>
              <div className="form-group">
                <label>VAT Amount</label>
                <input type="number" step="0.01" value={form.vatAmount} onChange={set('vatAmount')} placeholder="0.00" />
              </div>
            </div>
            <div className="grid-2">
              <div className="form-group">
                <label>Currency</label>
                <select value={form.currency} onChange={set('currency')}>
                  <option value="USD">USD</option>
                  <option value="ZWG">ZWG</option>
                  <option value="ZAR">ZAR</option>
                  <option value="GBP">GBP</option>
                  <option value="EUR">EUR</option>
                </select>
              </div>
              <div className="form-group">
                <label>Email Template</label>
                <select value={form.templateName} onChange={set('templateName')}>
                  <option value="invoice">Invoice</option>
                  <option value="platform-invoice">Platform Invoice</option>
                  <option value="generic">Generic</option>
                </select>
              </div>
            </div>
          </div>

          <div style={{ marginTop: 24 }}>
            <button className="btn btn-primary" disabled={loading} style={{ minWidth: 160 }}>
              {loading ? <span className="spinner" /> : <><Send size={15} style={{ marginRight: 6 }} />Send Invoice</>}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
