import { useEffect, useState, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import {
  listEmailTemplates, createEmailTemplate, updateEmailTemplate,
  deleteEmailTemplate, setDefaultEmailTemplate,
  type EmailTemplate, type EmailTemplateUpsert,
} from '../api/client'
import { Pencil, Plus, Star, Trash2, X, RefreshCw, Save } from 'lucide-react'

const PLACEHOLDERS: { token: string; description: string }[] = [
  { token: '{{recipientName}}', description: "Customer's display name" },
  { token: '{{invoiceNumber}}', description: 'Fiscal invoice number' },
  { token: '{{invoiceDate}}', description: 'Invoice issue date' },
  { token: '{{dueDate}}', description: 'Payment due date' },
  { token: '{{totalAmount}}', description: 'Total amount (VAT inclusive)' },
  { token: '{{vatAmount}}', description: 'VAT amount' },
  { token: '{{currency}}', description: 'ISO currency code (e.g. USD)' },
  { token: '{{currencySymbol}}', description: 'Currency symbol' },
  { token: '{{companyName}}', description: 'Your organisation name' },
  { token: '{{accountsEmail}}', description: 'Accounts contact email' },
  { token: '{{verificationCode}}', description: 'ZIMRA verification code' },
]

const DEFAULT_BODY = `Hi {{recipientName}},

Please find attached invoice {{invoiceNumber}} dated {{invoiceDate}}.

Amount due: {{currencySymbol}}{{totalAmount}} ({{currency}})
Due date: {{dueDate}}

If you have any questions about this invoice, please reply to this email or contact {{accountsEmail}}.

Thank you for your business,
{{companyName}}`

interface EditorState {
  id?: string
  name: string
  subject: string
  body: string
  isDefault: boolean
}

const blankEditor = (): EditorState => ({
  name: '',
  subject: 'Invoice {{invoiceNumber}} from {{companyName}}',
  body: DEFAULT_BODY,
  isDefault: false,
})

function EditorModal({
  initial, onClose, onSave,
}: {
  initial: EditorState
  onClose: () => void
  onSave: (data: EmailTemplateUpsert, id?: string) => Promise<void>
}) {
  const [state, setState] = useState<EditorState>(initial)
  const [saving, setSaving] = useState(false)
  const [err, setErr] = useState('')

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!state.name.trim() || !state.subject.trim() || !state.body.trim()) {
      setErr('Name, subject, and body are required.')
      return
    }
    setSaving(true); setErr('')
    try {
      await onSave({
        name: state.name.trim(),
        subject: state.subject,
        body: state.body,
        isDefault: state.isDefault,
      }, state.id)
      onClose()
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? 'Failed to save template')
    } finally {
      setSaving(false)
    }
  }

  const insertToken = (token: string) => {
    setState(s => ({ ...s, body: s.body + token }))
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal"
        style={{ maxWidth: 980, width: '95vw', maxHeight: '92vh', display: 'flex', flexDirection: 'column' }}
        onClick={e => e.stopPropagation()}
      >
        <div className="modal-header" style={{ flexShrink: 0 }}>
          <span className="modal-title">{state.id ? 'Edit Template' : 'New Email Template'}</span>
          <button className="close-btn" onClick={onClose}><X size={18} /></button>
        </div>

        <form onSubmit={submit} style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div>
              <label className="text-sm text-muted">Template name</label>
              <input
                value={state.name}
                onChange={e => setState(s => ({ ...s, name: e.target.value }))}
                placeholder="e.g. Friendly reminder"
                style={{ width: '100%' }}
              />
            </div>
            <div>
              <label className="text-sm text-muted">Email subject</label>
              <input
                value={state.subject}
                onChange={e => setState(s => ({ ...s, subject: e.target.value }))}
                style={{ width: '100%' }}
              />
            </div>
          </div>

          <div style={{ flex: 1, minHeight: 0, display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 12 }}>
            <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
              <label className="text-sm text-muted">Message body</label>
              <textarea
                value={state.body}
                onChange={e => setState(s => ({ ...s, body: e.target.value }))}
                style={{ flex: 1, minHeight: 320, fontFamily: 'monospace', fontSize: 13, padding: 10, resize: 'vertical' }}
              />
            </div>
            <div style={{ minHeight: 0, overflowY: 'auto', border: '1px solid #e2e8f0', borderRadius: 6, padding: 10 }}>
              <div className="text-sm" style={{ fontWeight: 600, marginBottom: 6 }}>Available placeholders</div>
              <div className="text-sm text-muted" style={{ marginBottom: 8 }}>
                Click to insert into body.
              </div>
              {PLACEHOLDERS.map(p => (
                <div key={p.token} style={{ marginBottom: 6 }}>
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    style={{ width: '100%', justifyContent: 'flex-start', fontFamily: 'monospace', fontSize: 12 }}
                    onClick={() => insertToken(p.token)}
                  >
                    {p.token}
                  </button>
                  <div className="text-sm text-muted" style={{ fontSize: 11, marginTop: 2 }}>{p.description}</div>
                </div>
              ))}
            </div>
          </div>

          <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
            <input
              type="checkbox"
              checked={state.isDefault}
              onChange={e => setState(s => ({ ...s, isDefault: e.target.checked }))}
            />
            Use as default template for invoice emails
          </label>

          {err && <div className="alert alert-error">{err}</div>}

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <button type="button" className="btn btn-secondary btn-sm" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn btn-primary btn-sm" disabled={saving}>
              <Save size={14} /> {saving ? 'Saving…' : 'Save template'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function EmailTemplatesPage() {
  const { session } = useAuth()
  const [templates, setTemplates] = useState<EmailTemplate[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [editor, setEditor] = useState<EditorState | null>(null)

  const load = useCallback(() => {
    setLoading(true); setError('')
    listEmailTemplates(session?.apiKey)
      .then(r => setTemplates(r.data ?? []))
      .catch(() => setError('Failed to load templates.'))
      .finally(() => setLoading(false))
  }, [session?.apiKey])

  useEffect(() => { load() }, [load])

  const handleSave = async (data: EmailTemplateUpsert, id?: string) => {
    if (id) await updateEmailTemplate(id, data, session?.apiKey)
    else await createEmailTemplate(data, session?.apiKey)
    load()
  }

  const handleDelete = async (t: EmailTemplate) => {
    if (!confirm(`Delete template "${t.name}"?`)) return
    await deleteEmailTemplate(t.id, session?.apiKey)
    load()
  }

  const handleSetDefault = async (t: EmailTemplate) => {
    await setDefaultEmailTemplate(t.id, session?.apiKey)
    load()
  }

  return (
    <>
      <div className="topbar">
        <span className="topbar-title">Email Templates</span>
        <div className="flex gap-2">
          <button className="btn btn-secondary btn-sm" onClick={load}>
            <RefreshCw size={14} /> Refresh
          </button>
          <button className="btn btn-primary btn-sm" onClick={() => setEditor(blankEditor())}>
            <Plus size={14} /> New template
          </button>
        </div>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Email Templates</h2>
          <p>Customise the message body used when emailing invoices to your customers. Mark one template as default to apply it automatically.</p>
        </div>

        <div className="card">
          {error && <div className="alert alert-error" style={{ marginBottom: 12 }}>{error}</div>}

          {loading ? (
            <div className="loading-center"><span className="spinner" /></div>
          ) : templates.length === 0 ? (
            <div className="empty-state">
              <Pencil size={32} />
              <p>No templates yet. Click <strong>New template</strong> to create one.</p>
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Name</th><th>Subject</th><th>Default</th><th>Updated</th><th></th>
                  </tr>
                </thead>
                <tbody>
                  {templates.map(t => (
                    <tr key={t.id}>
                      <td style={{ fontWeight: 500 }}>{t.name}</td>
                      <td className="text-sm text-muted" style={{ maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {t.subject}
                      </td>
                      <td>
                        {t.isDefault
                          ? <span className="badge badge-green"><Star size={11} style={{ display: 'inline', marginRight: 3 }} /> Default</span>
                          : (
                            <button className="btn btn-secondary btn-sm" onClick={() => handleSetDefault(t)}>
                              <Star size={12} /> Make default
                            </button>
                          )}
                      </td>
                      <td className="text-sm text-muted">
                        {t.updatedAt ? new Date(t.updatedAt).toLocaleString() : '—'}
                      </td>
                      <td>
                        <div className="flex gap-2">
                          <button
                            className="btn btn-secondary btn-sm"
                            onClick={() => setEditor({
                              id: t.id, name: t.name, subject: t.subject,
                              body: t.body, isDefault: t.isDefault,
                            })}
                          >
                            <Pencil size={13} /> Edit
                          </button>
                          <button className="btn btn-secondary btn-sm" onClick={() => handleDelete(t)}>
                            <Trash2 size={13} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {editor && (
        <EditorModal
          initial={editor}
          onClose={() => setEditor(null)}
          onSave={handleSave}
        />
      )}
    </>
  )
}
