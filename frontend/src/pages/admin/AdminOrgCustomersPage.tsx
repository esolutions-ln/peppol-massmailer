import { useEffect, useState, useCallback } from 'react'
import { Link, useParams } from 'react-router-dom'
import { listCustomers, getOrgById } from '../../api/client'
import { useAuth } from '../../context/AuthContext'
import { ArrowLeft, Users, ChevronLeft, ChevronRight } from 'lucide-react'
import type { Customer, Organization, PageResponse } from '../../types'
import { deriveParticipantId } from '../../types'

const PAGE_SIZES = [25, 50, 100]

export default function AdminOrgCustomersPage() {
  const { orgId = '' } = useParams<{ orgId: string }>()
  const { session } = useAuth()
  const [org, setOrg] = useState<Organization | null>(null)
  const [customers, setCustomers] = useState<Customer[]>([])
  const [pageRes, setPageRes] = useState<PageResponse<Customer> | null>(null)
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(25)

  const load = useCallback(() => {
    if (!orgId) return
    setLoading(true)
    Promise.all([
      getOrgById(orgId).then(r => setOrg(r.data)).catch(() => setOrg(null)),
      listCustomers(orgId, session?.apiKey, { page, size: pageSize, search })
        .then(r => { setCustomers(r.data.content ?? []); setPageRes(r.data) })
        .catch(() => { setCustomers([]); setPageRes(null) }),
    ]).finally(() => setLoading(false))
  }, [orgId, session?.apiKey, page, pageSize, search])

  useEffect(load, [load])

  const totalElements = pageRes?.totalElements ?? 0
  const totalPages = pageRes?.totalPages ?? 1

  return (
    <>
      <div className="topbar">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Link to="/admin/organizations" className="btn btn-secondary btn-sm">
            <ArrowLeft size={14} /> Organisations
          </Link>
          <span className="topbar-title">
            {org?.name?.trim() ?? 'Organisation'} <span className="text-muted">/ Customers</span>
          </span>
        </div>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>Customers</h2>
          <p className="text-muted">
            {org ? <>Slug: <code>{org.slug}</code> · Status: {org.status}</> : 'Loading organisation…'}
          </p>
        </div>
        <div className="card">
          <div className="mb-4 flex gap-2" style={{ alignItems: 'center' }}>
            <input
              style={{ maxWidth: 360 }}
              placeholder="Search by customer ID, name, email, company or participant ID…"
              value={search}
              onChange={e => { setSearch(e.target.value); setPage(0) }}
            />
            <span className="text-muted text-sm" style={{ marginLeft: 'auto' }}>
              {customers.length} of {totalElements} customers
            </span>
          </div>
          {loading ? (
            <div className="loading-center"><span className="spinner" /></div>
          ) : customers.length === 0 ? (
            <div className="empty-state">
              <Users size={32} />
              <p>{totalElements === 0 ? 'This organisation has no customers yet.' : 'No customers match the search.'}</p>
            </div>
          ) : (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Customer ID</th>
                      <th>Name</th><th>Email</th><th>Company</th>
                      <th>PEPPOL Participant ID</th>
                      <th>Delivery</th>
                      <th>ERP Source</th>
                      <th>Sent</th><th>Failed</th>
                      <th>Unsubscribed</th>
                    </tr>
                  </thead>
                  <tbody>
                    {customers.map(c => {
                      const pid = c.peppolParticipantId ?? deriveParticipantId(c.vatNumber, c.tinNumber)
                      const primary = c.contacts?.[0]
                      return (
                        <tr key={c.id}>
                          <td>
                            {c.erpCustomerId
                              ? <code style={{ fontSize: 12 }}>{c.erpCustomerId}</code>
                              : <span className="text-muted text-sm">—</span>}
                          </td>
                          <td>{primary?.name ?? '—'}</td>
                          <td>{primary?.email ?? '—'}</td>
                          <td>{c.companyName ?? '—'}</td>
                          <td>
                            {pid
                              ? <code style={{ fontSize: 12, color: '#0284c7' }}>{pid}</code>
                              : <span className="text-muted text-sm">—</span>}
                          </td>
                          <td>
                            {c.deliveryMode
                              ? <span className={`badge ${c.deliveryMode === 'EMAIL' ? 'badge-blue' : c.deliveryMode === 'AS4' ? 'badge-green' : 'badge-yellow'}`}>{c.deliveryMode}</span>
                              : <span className="text-muted text-sm">org default</span>}
                          </td>
                          <td className="text-muted text-sm">{c.erpSource ?? '—'}</td>
                          <td>{c.totalInvoicesSent}</td>
                          <td style={{ color: c.totalDeliveryFailures > 0 ? '#dc2626' : undefined }}>
                            {c.totalDeliveryFailures}
                          </td>
                          <td>
                            {c.unsubscribed
                              ? <span className="badge badge-red">Yes</span>
                              : <span className="badge badge-green">No</span>}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
              <div className="flex gap-3 mt-4" style={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
                <div className="flex gap-2" style={{ alignItems: 'center' }}>
                  <span className="text-sm text-muted">Rows per page:</span>
                  <select
                    style={{ padding: '4px 8px', border: '1px solid #e2e8f0', borderRadius: 6, fontSize: 13 }}
                    value={pageSize}
                    onChange={e => { setPageSize(Number(e.target.value)); setPage(0) }}
                  >
                    {PAGE_SIZES.map(s => <option key={s}>{s}</option>)}
                  </select>
                </div>
                <div className="flex gap-2" style={{ alignItems: 'center' }}>
                  <span className="text-sm text-muted">Page {page + 1} of {totalPages} ({totalElements} records)</span>
                  <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>
                    <ChevronLeft size={14} />
                  </button>
                  <button className="btn btn-secondary btn-sm" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>
                    <ChevronRight size={14} />
                  </button>
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </>
  )
}
