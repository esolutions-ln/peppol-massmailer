import { useState, useRef, DragEvent, useMemo } from 'react'
import { X, Upload, FileText, CheckCircle2, AlertTriangle, ArrowLeft, ArrowRight } from 'lucide-react'
import {
  importCustomersCsv, previewCustomersCsv,
  type CustomerImportResult, type CustomerImportPreview,
} from '../api/client'

interface Props {
  orgId: string
  apiKey?: string
  onClose: () => void
  onImported: () => void
}

const SAMPLE_CSV =
  'RowId,erpCustomerId,email,companyName,vatNumber\n' +
  '1,10006502,accounts@gtel.co.zw,G Tide Mobile Phone Zimbabwe Private Limited,220132956\n'

type Step = 'upload' | 'map' | 'result'

const TARGETS: Array<{ key: string; label: string; required?: boolean; hint?: string }> = [
  { key: 'erpCustomerId',      label: 'Customer ID',        required: true, hint: 'Unique key for matching existing customers' },
  { key: 'email',              label: 'Email',              hint: 'Optional. Picks first valid address if cell has multiple' },
  { key: 'name',               label: 'Contact name' },
  { key: 'phone',              label: 'Phone' },
  { key: 'companyName',        label: 'Company (legal)' },
  { key: 'tradingName',        label: 'Trading name' },
  { key: 'vatNumber',          label: 'VAT number' },
  { key: 'tinNumber',          label: 'TIN number' },
  { key: 'bpn',                label: 'BPN (ZIMRA)' },
  { key: 'addressLine1',       label: 'Address line 1' },
  { key: 'addressLine2',       label: 'Address line 2' },
  { key: 'city',               label: 'City' },
  { key: 'country',            label: 'Country' },
  { key: 'deliveryMode',       label: 'Delivery mode', hint: 'EMAIL / AS4 / BOTH' },
  { key: 'erpSource',          label: 'ERP source' },
  { key: 'peppolParticipantId', label: 'PEPPOL participant ID' },
]

export default function CustomerImportModal({ orgId, apiKey, onClose, onImported }: Props) {
  const [step, setStep] = useState<Step>('upload')
  const [file, setFile] = useState<File | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [preview, setPreview] = useState<CustomerImportPreview | null>(null)
  const [mapping, setMapping] = useState<Record<string, string>>({})
  const [result, setResult] = useState<CustomerImportResult | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const acceptFile = (f: File | null | undefined) => {
    if (!f) return
    if (!/\.csv$/i.test(f.name) && f.type !== 'text/csv') {
      setError('Please choose a .csv file.')
      return
    }
    setError('')
    setFile(f)
  }

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setDragOver(false)
    acceptFile(e.dataTransfer.files?.[0])
  }

  const goToMap = async () => {
    if (!file) return
    setBusy(true); setError('')
    try {
      const resp = await previewCustomersCsv(orgId, file, apiKey)
      setPreview(resp.data)
      setMapping(resp.data.suggestedMapping ?? {})
      setStep('map')
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Could not parse CSV.')
    } finally { setBusy(false) }
  }

  const runImport = async () => {
    if (!file) return
    if (!mapping.erpCustomerId) {
      setError('Customer ID is required — choose a source column for Customer ID.')
      return
    }
    setBusy(true); setError('')
    try {
      const cleaned = Object.fromEntries(Object.entries(mapping).filter(([, v]) => v))
      const resp = await importCustomersCsv(orgId, file, apiKey, cleaned)
      setResult(resp.data)
      setStep('result')
      if (resp.data.created + resp.data.updated > 0) onImported()
    } catch (err: any) {
      const body = err.response?.data
      const detail = typeof body === 'string'
        ? body
        : (body?.message ?? body?.error ?? JSON.stringify(body))
      const status = err.response?.status ? ` (HTTP ${err.response.status})` : ''
      setError(`Import failed${status}: ${detail ?? err.message ?? 'Unknown error'}`)
    } finally { setBusy(false) }
  }

  const downloadSample = () => {
    const blob = new Blob([SAMPLE_CSV], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = 'customers-sample.csv'; a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 820 }}>
        <div className="modal-header">
          <span className="modal-title">Import Customers — {step === 'upload' ? 'Choose File' : step === 'map' ? 'Map Columns' : 'Result'}</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>

        <Stepper step={step} />
        {error && <div className="alert alert-error">{error}</div>}

        {step === 'upload' && (
          <UploadStep
            file={file} dragOver={dragOver} setDragOver={setDragOver}
            onDrop={onDrop} onPick={f => acceptFile(f)}
            inputRef={inputRef} downloadSample={downloadSample}
            onCancel={onClose} onNext={goToMap} busy={busy}
          />
        )}

        {step === 'map' && preview && (
          <MapStep
            preview={preview} mapping={mapping} setMapping={setMapping}
            onBack={() => setStep('upload')} onNext={runImport} busy={busy}
          />
        )}

        {step === 'result' && result && (
          <ResultStep
            result={result}
            onAgain={() => { setFile(null); setPreview(null); setMapping({}); setResult(null); setStep('upload') }}
            onDone={onClose}
          />
        )}
      </div>
    </div>
  )
}

// ── sub-components ──

function Stepper({ step }: { step: Step }) {
  const steps: Array<{ key: Step; label: string }> = [
    { key: 'upload', label: 'Upload' },
    { key: 'map', label: 'Map' },
    { key: 'result', label: 'Result' },
  ]
  const activeIdx = steps.findIndex(s => s.key === step)
  return (
    <div style={{ display: 'flex', gap: 6, margin: '0 0 14px' }}>
      {steps.map((s, i) => (
        <div key={s.key} style={{
          flex: 1, padding: '6px 10px', borderRadius: 6, fontSize: 12, fontWeight: 600,
          textAlign: 'center',
          background: i <= activeIdx ? '#0284c7' : '#e2e8f0',
          color: i <= activeIdx ? '#fff' : '#64748b',
        }}>
          {i + 1}. {s.label}
        </div>
      ))}
    </div>
  )
}

interface UploadProps {
  file: File | null
  dragOver: boolean
  setDragOver: (b: boolean) => void
  onDrop: (e: DragEvent<HTMLDivElement>) => void
  onPick: (f: File | null | undefined) => void
  inputRef: React.RefObject<HTMLInputElement>
  downloadSample: () => void
  onCancel: () => void
  onNext: () => void
  busy: boolean
}
function UploadStep({ file, dragOver, setDragOver, onDrop, onPick, inputRef, downloadSample, onCancel, onNext, busy }: UploadProps) {
  return (
    <>
      <div
        onDragOver={e => { e.preventDefault(); setDragOver(true) }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
        onClick={() => inputRef.current?.click()}
        style={{
          border: `2px dashed ${dragOver ? '#0284c7' : '#cbd5e1'}`,
          borderRadius: 10, padding: 32, textAlign: 'center', cursor: 'pointer',
          background: dragOver ? '#f0f9ff' : '#f8fafc', transition: 'all 120ms ease',
        }}
      >
        {file ? (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10 }}>
            <FileText size={24} color="#0284c7" />
            <div style={{ textAlign: 'left' }}>
              <div style={{ fontWeight: 600 }}>{file.name}</div>
              <div className="text-muted text-sm">{(file.size / 1024).toFixed(1)} KB</div>
            </div>
          </div>
        ) : (
          <>
            <Upload size={28} color="#64748b" />
            <div style={{ marginTop: 10, fontWeight: 600 }}>Drag a CSV file here or click to choose</div>
            <div className="text-muted text-sm" style={{ marginTop: 4 }}>
              Header row required. Column 1 is treated as a row label and ignored.
            </div>
          </>
        )}
        <input
          ref={inputRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }}
          onChange={e => onPick(e.target.files?.[0])}
        />
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 14 }}>
        <button type="button" className="btn btn-link" onClick={downloadSample}
                style={{ fontSize: 13, padding: 0 }}>
          Download sample CSV
        </button>
        <div className="flex gap-2">
          <button type="button" className="btn btn-secondary" onClick={onCancel}>Cancel</button>
          <button type="button" className="btn btn-primary" disabled={!file || busy} onClick={onNext}>
            {busy ? <span className="spinner" /> : <>Next <ArrowRight size={14} /></>}
          </button>
        </div>
      </div>
    </>
  )
}

interface MapProps {
  preview: CustomerImportPreview
  mapping: Record<string, string>
  setMapping: (m: Record<string, string>) => void
  onBack: () => void
  onNext: () => void
  busy: boolean
}
function MapStep({ preview, mapping, setMapping, onBack, onNext, busy }: MapProps) {
  const setOne = (target: string, source: string) =>
    setMapping({ ...mapping, [target]: source })

  const previewFor = useMemo(() => (target: string) => {
    const src = mapping[target]
    if (!src || !preview.sampleRows.length) return null
    const colIdx = preview.columns.indexOf(src)
    if (colIdx < 0) return null
    const samples = preview.sampleRows
      .map(r => r[colIdx])
      .filter(v => v && v.length)
      .slice(0, 2)
    return samples.length ? samples.join(' · ') : null
  }, [mapping, preview])

  const mappedCount = Object.values(mapping).filter(Boolean).length

  return (
    <>
      <div className="text-muted text-sm" style={{ marginBottom: 10 }}>
        We detected <b>{preview.columns.length}</b> columns. Map each InvoiceDirect field to a source
        column from your CSV. {mappedCount} of {TARGETS.length} mapped.
      </div>

      <div style={{
        maxHeight: 380, overflow: 'auto',
        border: '1px solid #e2e8f0', borderRadius: 8,
      }}>
        <table style={{ width: '100%' }}>
          <thead>
            <tr style={{ position: 'sticky', top: 0, background: '#f8fafc', zIndex: 1 }}>
              <th style={{ width: '32%' }}>Target field</th>
              <th>Source column</th>
              <th>Sample</th>
            </tr>
          </thead>
          <tbody>
            {TARGETS.map(t => {
              const sample = previewFor(t.key)
              return (
                <tr key={t.key}>
                  <td>
                    <div style={{ fontWeight: 600 }}>
                      {t.label}{t.required && <span style={{ color: '#dc2626' }}> *</span>}
                    </div>
                    {t.hint && <div className="text-muted text-sm">{t.hint}</div>}
                  </td>
                  <td>
                    <select
                      value={mapping[t.key] ?? ''}
                      onChange={e => setOne(t.key, e.target.value)}
                      style={{ width: '100%' }}
                    >
                      <option value="">— skip —</option>
                      {preview.columns.map(c => (
                        <option key={c} value={c}>{c}</option>
                      ))}
                    </select>
                  </td>
                  <td className="text-muted text-sm" style={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {sample ?? '—'}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 14 }}>
        <button type="button" className="btn btn-secondary" onClick={onBack}>
          <ArrowLeft size={14} /> Back
        </button>
        <button type="button" className="btn btn-primary" disabled={busy || !mapping.erpCustomerId} onClick={onNext}>
          {busy ? <span className="spinner" /> : 'Import'}
        </button>
      </div>
    </>
  )
}

function ResultStep({ result, onAgain, onDone }: {
  result: CustomerImportResult
  onAgain: () => void
  onDone: () => void
}) {
  const downloadLog = () => {
    const lines = result.outcomes.map(o =>
      `row=${o.row}\t${o.status}\t${o.identifier ?? ''}\t${o.message ?? ''}`
    )
    const blob = new Blob([lines.join('\n')], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = 'import-log.txt'; a.click()
    URL.revokeObjectURL(url)
  }

  const statusStyle = (s: string): React.CSSProperties => {
    if (s === 'CREATED') return { color: '#22c55e' }
    if (s === 'UPDATED') return { color: '#38bdf8' }
    if (s === 'SKIPPED') return { color: '#eab308' }
    return { color: '#f87171' } // ERROR
  }

  return (
    <div>
      <div style={{
        background: result.errors.length === 0 ? '#f0fdf4' : '#fefce8',
        border: `1px solid ${result.errors.length === 0 ? '#bbf7d0' : '#fde68a'}`,
        borderRadius: 8, padding: 14, marginBottom: 14,
        display: 'flex', alignItems: 'center', gap: 10,
      }}>
        {result.errors.length === 0
          ? <CheckCircle2 size={20} color="#16a34a" />
          : <AlertTriangle size={20} color="#ca8a04" />}
        <div style={{ flex: 1 }}>
          <div style={{ fontWeight: 600 }}>
            Imported {result.created + result.updated} of {result.totalRows} rows
          </div>
          <div className="text-muted text-sm">
            {result.created} created &middot; {result.updated} updated &middot; {result.skipped} skipped
          </div>
        </div>
        <button className="btn btn-secondary btn-sm" onClick={downloadLog}>Download log</button>
      </div>

      <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 6 }}>Console</div>
      <div style={{
        background: '#0f172a', color: '#e2e8f0',
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
        fontSize: 12, lineHeight: 1.55,
        borderRadius: 8, padding: 12,
        maxHeight: 320, overflow: 'auto',
      }}>
        {result.outcomes.length === 0 && (
          <div style={{ color: '#94a3b8' }}>No rows processed.</div>
        )}
        {result.outcomes.map((o, i) => (
          <div key={i} style={{ whiteSpace: 'pre-wrap' }}>
            <span style={{ color: '#64748b' }}>row {String(o.row).padStart(4, ' ')}</span>
            {' '}
            <span style={{ ...statusStyle(o.status), fontWeight: 600 }}>
              {o.status.padEnd(7, ' ')}
            </span>
            {' '}
            <span style={{ color: '#cbd5e1' }}>{o.identifier ?? '—'}</span>
            {o.message && (
              <>
                {' '}
                <span style={{ color: '#fda4af' }}>{o.message}</span>
              </>
            )}
          </div>
        ))}
      </div>

      <div className="flex gap-2 mt-4" style={{ justifyContent: 'flex-end' }}>
        <button className="btn btn-secondary" onClick={onAgain}>Import another</button>
        <button className="btn btn-primary" onClick={onDone}>Done</button>
      </div>
    </div>
  )
}
