import { useState, useEffect, useRef } from 'react'
import { useAuth } from '../context/AuthContext'
import { sendSingleInvoiceUpload } from '../api/client'
import { Send, Upload, CheckCircle, AlertCircle, FileText, X, Sparkles } from 'lucide-react'

interface SendResult {
  status: string; recipient: string; invoiceNumber: string; messageId?: string; error?: string
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 12 }}>
      <label style={{ fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.06em', color: '#94a3b8', marginBottom: 4, display: 'block' }}>{label}</label>
      {children}
    </div>
  )
}

function SSection({ title, children, highlight }: { title: string; children: React.ReactNode; highlight?: boolean }) {
  return (
    <div style={{ border: `1px solid ${highlight ? '#fed7aa' : '#e8edf2'}`, borderRadius: 10, background: highlight ? '#fffbf5' : '#fff', marginBottom: 14 }}>
      <div style={{ padding: '8px 14px', borderBottom: `1px solid ${highlight ? '#fed7aa' : '#f1f5f9'}`, background: highlight ? '#fff7ed' : '#f8fafc', display: 'flex', alignItems: 'center', gap: 8, borderRadius: '10px 10px 0 0' }}>
        <div style={{ width: 3, height: 13, borderRadius: 2, background: 'var(--brand-orange)', flexShrink: 0 }} />
        <span style={{ fontSize: 10.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.07em', color: '#64748b' }}>{title}</span>
        {highlight && <span style={{ marginLeft: 4, fontSize: 10, fontWeight: 700, color: 'var(--brand-orange)', background: '#fed7aa', borderRadius: 4, padding: '1px 6px' }}>AUTO-FILLED</span>}
      </div>
      <div style={{ padding: '14px' }}>{children}</div>
    </div>
  )
}

// ── PDF text extractor ─────────────────────────────────────────────────────

async function extractTextFromPdf(file: File): Promise<string> {
  const pdfjsLib = await import('pdfjs-dist')
  // Point worker at the bundled worker inside node_modules
  pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
    'pdfjs-dist/build/pdf.worker.min.mjs',
    import.meta.url,
  ).toString()

  const arrayBuffer = await file.arrayBuffer()
  const pdf = await pdfjsLib.getDocument({ data: arrayBuffer }).promise
  let text = ''
  for (let i = 1; i <= Math.min(pdf.numPages, 3); i++) {
    const page = await pdf.getPage(i)
    const content = await page.getTextContent()
    text += content.items.map((item: any) => item.str).join(' ') + '\n'
  }
  return text
}

// ── Regex-based field extraction ───────────────────────────────────────────

interface Extracted {
  invoiceNumber?: string
  invoiceDate?: string   // yyyy-mm-dd
  dueDate?: string       // yyyy-mm-dd
  totalAmount?: string
  vatAmount?: string
  currency?: string
  recipientName?: string
  toEmail?: string
}

function parseDate(raw: string): string | undefined {
  // Accepts: DD/MM/YYYY, DD-MM-YYYY, YYYY-MM-DD, DD Mon YYYY, Mon DD YYYY
  const s = raw.trim()
  const iso = s.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (iso) return s
  const dmy = s.match(/^(\d{1,2})[\/\-\.](\d{1,2})[\/\-\.](\d{4})$/)
  if (dmy) return `${dmy[3]}-${dmy[2].padStart(2,'0')}-${dmy[1].padStart(2,'0')}`
  const months: Record<string,string> = { jan:'01',feb:'02',mar:'03',apr:'04',may:'05',jun:'06',jul:'07',aug:'08',sep:'09',oct:'10',nov:'11',dec:'12' }
  const wordy = s.match(/(\d{1,2})\s+([A-Za-z]{3,})\s+(\d{4})/)
  if (wordy) { const m = months[wordy[2].toLowerCase().slice(0,3)]; if (m) return `${wordy[3]}-${m}-${wordy[1].padStart(2,'0')}` }
  const wordy2 = s.match(/([A-Za-z]{3,})\s+(\d{1,2}),?\s+(\d{4})/)
  if (wordy2) { const m = months[wordy2[1].toLowerCase().slice(0,3)]; if (m) return `${wordy2[3]}-${m}-${wordy2[2].padStart(2,'0')}` }
  return undefined
}

function extractFields(text: string): Extracted {
  const result: Extracted = {}

  // Invoice number
  const invMatch = text.match(/(?:invoice\s*(?:no|number|#|num)[:\s#]*)([\w\-\/]+)/i)
    ?? text.match(/(?:inv[:\-\s#]+)([\w\-\/]+)/i)
  if (invMatch) result.invoiceNumber = invMatch[1].trim()

  // Dates
  const datePattern = /\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{4}|\d{4}[\/\-]\d{2}[\/\-]\d{2}|\d{1,2}\s+[A-Za-z]{3,}\s+\d{4}|[A-Za-z]{3,}\s+\d{1,2},?\s+\d{4}/g

  const invDateCtx = text.match(/(?:invoice\s*date|date\s*of\s*invoice|issued?)[:\s]*([\d\/\-\.A-Za-z ,]+)/i)
  if (invDateCtx) { const d = parseDate(invDateCtx[1].trim()); if (d) result.invoiceDate = d }

  const dueDateCtx = text.match(/(?:due\s*date|payment\s*due|pay\s*by|due\s*on)[:\s]*([\d\/\-\.A-Za-z ,]+)/i)
  if (dueDateCtx) { const d = parseDate(dueDateCtx[1].trim()); if (d) result.dueDate = d }

  // If we didn't get dates from context, grab first two dates from text
  if (!result.invoiceDate || !result.dueDate) {
    const allDates = [...text.matchAll(datePattern)].map(m => parseDate(m[0])).filter(Boolean) as string[]
    if (!result.invoiceDate && allDates[0]) result.invoiceDate = allDates[0]
    if (!result.dueDate && allDates[1] && allDates[1] !== result.invoiceDate) result.dueDate = allDates[1]
  }

  // Currency detection
  if (/USD|\$/.test(text)) result.currency = 'USD'
  else if (/ZWG|ZWL/.test(text)) result.currency = 'ZWG'
  else if (/ZAR|R\s*\d/.test(text)) result.currency = 'ZAR'
  else if (/GBP|£/.test(text)) result.currency = 'GBP'
  else if (/EUR|€/.test(text)) result.currency = 'EUR'

  // Amounts
  const totalMatch = text.match(/(?:total\s*(?:amount|due|payable|invoice)?|amount\s*due|grand\s*total)[:\s]*(?:[A-Z$£€]{0,4}\s*)?([\d,]+\.?\d{0,2})/i)
  if (totalMatch) result.totalAmount = totalMatch[1].replace(/,/g, '')

  const vatMatch = text.match(/(?:vat|tax|gst)[:\s]*(?:[A-Z$£€]{0,4}\s*)?([\d,]+\.?\d{0,2})/i)
  if (vatMatch) result.vatAmount = vatMatch[1].replace(/,/g, '')

  // Email
  const emailMatch = text.match(/[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}/)
  if (emailMatch) result.toEmail = emailMatch[0]

  // Recipient name — look for "Bill to", "To:", "Customer:"
  const billTo = text.match(/(?:bill\s*to|sold\s*to|customer|client|to:)\s*[:\-]?\s*([A-Z][^\n,]{3,60})/i)
  if (billTo) result.recipientName = billTo[1].trim().replace(/\s+/g, ' ')

  return result
}

// ──────────────────────────────────────────────────────────────────────────────

export default function SendTestPage() {
  const { session } = useAuth()
  const [form, setForm] = useState({
    to: '', fromName: '', subject: '',
    invoiceNumber: '', recipientName: '',
    invoiceDate: new Date().toISOString().slice(0, 10),
    dueDate: '', totalAmount: '', vatAmount: '',
    currency: 'USD', templateName: 'invoice',
  })
  const [pdfFile, setPdfFile] = useState<File | null>(null)
  const [pdfUrl, setPdfUrl] = useState<string | null>(null)
  const [parsing, setParsing] = useState(false)
  const [parsed, setParsed] = useState(false)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<SendResult | null>(null)
  const [error, setError] = useState('')
  const [dragging, setDragging] = useState(false)
  const dropRef = useRef<HTMLDivElement>(null)

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  useEffect(() => {
    if (!pdfFile) { setPdfUrl(null); setParsed(false); return }
    const url = URL.createObjectURL(pdfFile)
    setPdfUrl(url)

    // Auto-parse
    setParsing(true)
    extractTextFromPdf(pdfFile)
      .then(text => {
        const fields = extractFields(text)
        setForm(f => ({
          ...f,
          ...(fields.invoiceNumber ? { invoiceNumber: fields.invoiceNumber } : {}),
          ...(fields.invoiceDate   ? { invoiceDate: fields.invoiceDate }     : {}),
          ...(fields.dueDate       ? { dueDate: fields.dueDate }             : {}),
          ...(fields.totalAmount   ? { totalAmount: fields.totalAmount }     : {}),
          ...(fields.vatAmount     ? { vatAmount: fields.vatAmount }         : {}),
          ...(fields.currency      ? { currency: fields.currency }           : {}),
          ...(fields.toEmail       ? { to: fields.toEmail }                  : {}),
          ...(fields.recipientName ? { recipientName: fields.recipientName } : {}),
        }))
        setParsed(true)
      })
      .catch(() => {/* silent — user can fill manually */})
      .finally(() => setParsing(false))

    return () => URL.revokeObjectURL(url)
  }, [pdfFile])

  const acceptFile = (file: File | undefined) => {
    if (!file) return
    if (file.type !== 'application/pdf') { setError('Please select a valid PDF file.'); return }
    setError('')
    setPdfFile(file)
  }

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => acceptFile(e.target.files?.[0])
  const handleDrop = (e: React.DragEvent) => { e.preventDefault(); setDragging(false); acceptFile(e.dataTransfer.files?.[0]) }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!pdfFile) { setError('Please attach a PDF file.'); return }
    setError(''); setResult(null); setLoading(true)
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
        variables: { companyName: form.fromName || session?.name || 'eSolutions' },
      }
      Object.keys(metadata).forEach(k => { if (metadata[k] === undefined) delete metadata[k] })
      const fd = new FormData()
      fd.append('pdf', pdfFile)
      fd.append('metadata', JSON.stringify(metadata))
      const res = await sendSingleInvoiceUpload(fd, session?.apiKey)
      setResult(res.data as SendResult)
    } catch (err: any) {
      setError(err.response?.data?.error ?? err.response?.data?.message ?? err.message ?? 'Send failed.')
    } finally { setLoading(false) }
  }

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Send Invoice</span>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Send Invoice</h2>
          <p>Upload a PDF — fields are auto-populated from the document, then review and send</p>
        </div>

        {result && (
          <div className={`alert ${result.status === 'delivered' ? 'alert-success' : 'alert-error'}`} style={{ marginBottom: 20 }}>
            {result.status === 'delivered' ? <CheckCircle size={16} style={{ flexShrink: 0 }} /> : <AlertCircle size={16} style={{ flexShrink: 0 }} />}
            <div>
              <strong>{result.status === 'delivered' ? 'Email delivered!' : 'Delivery failed'}</strong>
              <div style={{ fontSize: 13, marginTop: 3, opacity: .85 }}>
                To: {result.recipient} · Invoice: {result.invoiceNumber}
                {result.messageId && <> · ID: <code style={{ fontSize: 12 }}>{result.messageId}</code></>}
                {result.error && <> · {result.error}</>}
              </div>
            </div>
          </div>
        )}
        {error && <div className="alert alert-error" style={{ marginBottom: 16 }}>{error}</div>}

        <form onSubmit={handleSubmit}>
          {/* ── Upload strip ── */}
          <div
            ref={dropRef}
            onDragOver={e => { e.preventDefault(); setDragging(true) }}
            onDragLeave={() => setDragging(false)}
            onDrop={handleDrop}
            onClick={() => !pdfFile && document.getElementById('pdf-input')?.click()}
            style={{
              border: `2px dashed ${dragging ? 'var(--brand-orange)' : pdfFile ? '#16a34a' : '#d1d5db'}`,
              borderRadius: 12,
              padding: pdfFile ? '14px 20px' : '32px 20px',
              textAlign: 'center',
              cursor: pdfFile ? 'default' : 'pointer',
              background: dragging ? '#fff7ed' : pdfFile ? '#f0fdf4' : '#fafafa',
              transition: 'all 0.2s',
              marginBottom: 20,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 16,
            }}
          >
            <input id="pdf-input" type="file" accept=".pdf,application/pdf" onChange={handleFileChange} style={{ display: 'none' }} />
            {pdfFile ? (
              <>
                <div style={{ width: 40, height: 40, borderRadius: 10, background: '#dcfce7', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                  <FileText size={22} color="#16a34a" />
                </div>
                <div style={{ flex: 1, textAlign: 'left' }}>
                  <div style={{ fontWeight: 700, color: '#15803d', fontSize: 14 }}>{pdfFile.name}</div>
                  <div style={{ fontSize: 12, color: '#4b5563', marginTop: 2, display: 'flex', alignItems: 'center', gap: 8 }}>
                    {(pdfFile.size / 1024).toFixed(1)} KB
                    {parsing && <><span className="spinner" style={{ width: 12, height: 12, borderWidth: 1.5 }} /><span style={{ color: 'var(--brand-orange)' }}>Extracting data…</span></>}
                    {parsed && !parsing && <><Sparkles size={12} color="var(--brand-orange)" /><span style={{ color: 'var(--brand-orange)', fontWeight: 600 }}>Fields auto-populated</span></>}
                  </div>
                </div>
                <button type="button" className="btn btn-secondary btn-sm" onClick={e => { e.stopPropagation(); setPdfFile(null); setResult(null); setParsed(false) }}>
                  <X size={13} /> Remove
                </button>
                <button type="button" className="btn btn-secondary btn-sm" onClick={e => { e.stopPropagation(); document.getElementById('pdf-input')?.click() }}>
                  <Upload size={13} /> Replace
                </button>
              </>
            ) : (
              <div>
                <Upload size={30} style={{ color: '#9ca3af', display: 'block', margin: '0 auto 10px' }} />
                <div style={{ fontSize: 14, fontWeight: 600, color: '#374151' }}>Drop your PDF here or click to browse</div>
                <div style={{ fontSize: 12, color: '#9ca3af', marginTop: 4 }}>Fields will be auto-extracted from the document</div>
              </div>
            )}
          </div>

          {/* ── Two-column layout once PDF is loaded ── */}
          <div style={{ display: 'grid', gridTemplateColumns: pdfFile ? '1fr 400px' : '1fr', gap: 20, alignItems: 'start' }}>

            {/* Left: PDF preview */}
            {pdfFile && pdfUrl && (
              <div style={{ border: '1px solid #e8edf2', borderRadius: 12, overflow: 'hidden', background: '#f8fafc', position: 'sticky', top: 74 }}>
                <div style={{ padding: '10px 16px', borderBottom: '1px solid #e8edf2', background: '#fff', display: 'flex', alignItems: 'center', gap: 8 }}>
                  <div style={{ width: 3, height: 13, borderRadius: 2, background: 'var(--brand-orange)', flexShrink: 0 }} />
                  <span style={{ fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.07em', color: '#64748b' }}>PDF Preview</span>
                  <span style={{ marginLeft: 'auto', fontSize: 12, color: '#9ca3af', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 180 }}>{pdfFile.name}</span>
                </div>
                <iframe src={pdfUrl} title="PDF Preview" style={{ width: '100%', height: 'calc(100vh - 280px)', minHeight: 500, border: 'none', display: 'block' }} />
              </div>
            )}

            {/* Right: form fields */}
            <div>
              <SSection title="Recipient" highlight={parsed && (!!form.to || !!form.recipientName)}>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                  <Field label="To Email *">
                    <input type="email" value={form.to} onChange={set('to')} placeholder="recipient@example.com" required />
                  </Field>
                  <Field label="Recipient Name">
                    <input value={form.recipientName} onChange={set('recipientName')} placeholder="Acme Corporation" />
                  </Field>
                </div>
                <Field label="From Name">
                  <input value={form.fromName} onChange={set('fromName')} placeholder={session?.name || 'Your company name'} />
                  <small>Displayed in the email body. SMTP sender is configured server-side.</small>
                </Field>
                <Field label="Subject">
                  <input value={form.subject} onChange={set('subject')} placeholder={`Invoice ${form.invoiceNumber || 'INV-001'}`} />
                  <small>Defaults to "Invoice [number]" if left blank.</small>
                </Field>
              </SSection>

              <SSection title="Invoice Details" highlight={parsed && (!!form.invoiceNumber || !!form.totalAmount)}>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                  <Field label="Invoice Number *">
                    <input value={form.invoiceNumber} onChange={set('invoiceNumber')} placeholder="INV-2026-0001" required />
                  </Field>
                  <Field label="Currency">
                    <select value={form.currency} onChange={set('currency')}>
                      {['USD', 'ZWG', 'ZAR', 'GBP', 'EUR'].map(c => <option key={c}>{c}</option>)}
                    </select>
                  </Field>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                  <Field label="Invoice Date">
                    <input type="date" value={form.invoiceDate} onChange={set('invoiceDate')} />
                  </Field>
                  <Field label="Due Date">
                    <input type="date" value={form.dueDate} onChange={set('dueDate')} />
                  </Field>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                  <Field label="Total Amount">
                    <input type="number" step="0.01" value={form.totalAmount} onChange={set('totalAmount')} placeholder="0.00" />
                  </Field>
                  <Field label="VAT Amount">
                    <input type="number" step="0.01" value={form.vatAmount} onChange={set('vatAmount')} placeholder="0.00" />
                  </Field>
                </div>
              </SSection>

              <SSection title="Settings">
                <Field label="Email Template">
                  <select value={form.templateName} onChange={set('templateName')}>
                    <option value="invoice">Invoice</option>
                    <option value="platform-invoice">Platform Invoice</option>
                    <option value="generic">Generic</option>
                  </select>
                </Field>
              </SSection>

              {/* Summary card */}
              {pdfFile && (
                <div style={{ border: '1px solid #e8edf2', borderRadius: 10, background: '#fafbff', padding: '14px 16px', marginBottom: 14 }}>
                  <div style={{ fontSize: 10.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.07em', color: '#94a3b8', marginBottom: 10 }}>Details to be sent</div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px 16px' }}>
                    {([
                      ['To', form.to],
                      ['Invoice #', form.invoiceNumber],
                      ['Recipient', form.recipientName],
                      ['Date', form.invoiceDate],
                      ['Due', form.dueDate],
                      ['Amount', form.totalAmount ? `${form.currency} ${parseFloat(form.totalAmount).toLocaleString()}` : ''],
                      ['VAT', form.vatAmount ? `${form.currency} ${parseFloat(form.vatAmount).toLocaleString()}` : ''],
                      ['Template', form.templateName],
                    ] as [string, string][]).map(([label, value]) => (
                      <div key={label}>
                        <div style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.06em', color: '#94a3b8' }}>{label}</div>
                        <div style={{ fontSize: 13, fontWeight: 600, color: value ? '#0f172a' : '#cbd5e1', marginTop: 1 }}>{value || '—'}</div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <button className="btn btn-primary" disabled={loading} style={{ width: '100%', justifyContent: 'center', padding: '11px 20px', fontSize: 14 }}>
                {loading ? <span className="spinner" /> : <Send size={15} />}
                {loading ? 'Sending…' : 'Send Invoice'}
              </button>
            </div>
          </div>
        </form>
      </div>
    </>
  )
}
