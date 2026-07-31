import axios, { AxiosResponse } from 'axios'
import type {
  Organization, OrgUser, CustomerContact, Customer, Contact,
  Campaign, CampaignDetail, InvoiceRecord, RateProfile,
  BillingPeriodSummary, UsageRecord, CostEstimate, OrgStats,
  AccessPoint, PeppolDelivery, PeppolInboundDoc, PeppolHealth,
  ParticipantLink, CreateParticipantLinkRequest,
  PeppolDeliveryStats, PeppolInvitation, TokenValidationResponse,
  PeppolCertificate, PeppolCertUploadRequest, PeppolActiveCertResponse,
  OrgMember, OrgLoginResponse, OrgMemberRole, DeliveryMode
} from '../types'

const BASE = '/api/v1'

// Automatically attach X-API-Key from session on every request
axios.interceptors.request.use(config => {
  try {
    const session = JSON.parse(localStorage.getItem('id_session') ?? 'null')
    if (session?.apiKey && !config.headers['X-API-Key']) {
      config.headers['X-API-Key'] = session.apiKey
    }
  } catch { /* ignore */ }
  return config
})

// On 401 the stored token is invalid/expired — clear the session and bounce to login.
// 403 is *not* cleared: the caller is authenticated, just lacks the role; let pages handle it.
axios.interceptors.response.use(
  res => res,
  err => {
    if (err?.response?.status === 401) {
      try {
        const session = JSON.parse(localStorage.getItem('id_session') ?? 'null')
        if (session) {
          localStorage.removeItem('id_session')
          const target = session.role === 'admin' ? '/admin/login' : '/login'
          if (!window.location.pathname.endsWith(target)) {
            window.location.assign(target)
          }
        }
      } catch { /* ignore */ }
    }
    return Promise.reject(err)
  }
)

function authHeaders(apiKey?: string): Record<string, string> {
  return apiKey ? { 'X-API-Key': apiKey } : {}
}

// ─── Admin Auth ───────────────────────────────────────────────────────────────
export const adminLogin = (username: string, password: string): Promise<AxiosResponse<{ token: string; name: string }>> =>
  axios.post(`${BASE}/admin/login`, { username, password })

// ─── Org Member Auth ──────────────────────────────────────────────────────────
export const orgMemberLogin = (
  slug: string, email: string, password: string
): Promise<AxiosResponse<OrgLoginResponse>> =>
  axios.post(`${BASE}/org/login`, { slug, email, password })

export const orgMemberLogout = (): Promise<AxiosResponse<void>> =>
  axios.post(`${BASE}/org/logout`)

// ─── Org Members (managed by ORG_ADMIN) ───────────────────────────────────────
export const listOrgMembers = (): Promise<AxiosResponse<OrgMember[]>> =>
  axios.get(`${BASE}/my/members`)

export const createOrgMember = (data: {
  email: string; password: string; displayName?: string; role: OrgMemberRole
}): Promise<AxiosResponse<OrgMember>> =>
  axios.post(`${BASE}/my/members`, data)

export const updateOrgMemberRole = (
  memberId: string, role: OrgMemberRole
): Promise<AxiosResponse<OrgMember>> =>
  axios.put(`${BASE}/my/members/${memberId}/role`, { role })

export const setOrgMemberActive = (
  memberId: string, active: boolean
): Promise<AxiosResponse<OrgMember>> =>
  axios.put(`${BASE}/my/members/${memberId}/active`, { active })

export const resetOrgMemberPassword = (
  memberId: string, password: string
): Promise<AxiosResponse<void>> =>
  axios.put(`${BASE}/my/members/${memberId}/password`, { password })

export const deleteOrgMember = (memberId: string): Promise<AxiosResponse<void>> =>
  axios.delete(`${BASE}/my/members/${memberId}`)

// ─── Organizations ────────────────────────────────────────────────────────────
export const registerOrg = (data: Partial<Organization> & { user: OrgUser }): Promise<AxiosResponse<Organization>> =>
  axios.post(`${BASE}/organizations`, data)

export const listOrgs = (): Promise<AxiosResponse<Organization[]>> =>
  axios.get(`${BASE}/organizations`)

export interface PagedResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export const listOrgsPaged = (
  params: { page?: number; size?: number; search?: string; sort?: string; dir?: 'asc' | 'desc' }
): Promise<AxiosResponse<PagedResponse<Organization>>> => {
  const query: Record<string, string | number> = {
    page: params.page ?? 0,
    size: params.size ?? 20,
  }
  if (params.search) query.search = params.search
  if (params.sort) query.sort = params.sort
  if (params.dir) query.dir = params.dir
  return axios.get(`${BASE}/organizations`, { params: query })
}

export const getOrgById = (id: string): Promise<AxiosResponse<Organization>> =>
  axios.get(`${BASE}/organizations/${id}`)

export const getOrgBySlug = (slug: string): Promise<AxiosResponse<Organization>> =>
  axios.get(`${BASE}/organizations/by-slug/${slug}`)

export const assignRateProfile = (id: string, rateProfileId: string): Promise<AxiosResponse<void>> =>
  axios.patch(`${BASE}/organizations/${id}/rate-profile`, { rateProfileId })

export const updateOrg = (id: string, data: Partial<Organization>): Promise<AxiosResponse<Organization>> =>
  axios.put(`${BASE}/organizations/${id}`, data)

export const deactivateOrg = (id: string): Promise<AxiosResponse<Organization>> =>
  axios.delete(`${BASE}/organizations/${id}`)

// ─── Customers ────────────────────────────────────────────────────────────────
export const registerCustomer = (
  orgId: string, data: Partial<Customer> & { email?: string; name?: string; phone?: string }, apiKey?: string
): Promise<AxiosResponse<Customer>> =>
  axios.post(`${BASE}/organizations/${orgId}/customers`, data, { headers: authHeaders(apiKey) })

export const updateCustomer = (
  orgId: string, customerId: string, data: Partial<Customer> & { email?: string; name?: string; phone?: string }, apiKey?: string
): Promise<AxiosResponse<Customer>> =>
  axios.put(`${BASE}/organizations/${orgId}/customers/${customerId}`, data, { headers: authHeaders(apiKey) })

export const listCustomers = (
  orgId: string, apiKey?: string, params?: { page?: number; size?: number; sort?: string; dir?: string; search?: string }
): Promise<AxiosResponse<PageResponse<Customer>>> =>
  axios.get(`${BASE}/organizations/${orgId}/customers`, { headers: authHeaders(apiKey), params })

export const addCustomerContact = (
  orgId: string, customerId: string, data: { email: string; name?: string; phone?: string }, apiKey?: string
): Promise<AxiosResponse<Customer>> =>
  axios.post(`${BASE}/organizations/${orgId}/customers/${customerId}/contacts`, data, { headers: authHeaders(apiKey) })

export const getCustomerByEmail = (
  orgId: string, email: string, apiKey?: string
): Promise<AxiosResponse<Customer>> =>
  axios.get(`${BASE}/organizations/${orgId}/customers/by-email`, {
    headers: authHeaders(apiKey), params: { email }
  })

export interface CustomerImportRowOutcome {
  row: number
  identifier: string | null
  status: 'CREATED' | 'UPDATED' | 'SKIPPED' | 'ERROR'
  message: string | null
}

export interface CustomerImportResult {
  totalRows: number
  created: number
  updated: number
  skipped: number
  errors: Array<{ row: number; message: string; rawLine: string }>
  outcomes: CustomerImportRowOutcome[]
}

export const importCustomersCsv = (
  orgId: string, file: File, apiKey?: string, mapping?: Record<string, string>
): Promise<AxiosResponse<CustomerImportResult>> => {
  const fd = new FormData()
  fd.append('file', file)
  if (mapping && Object.keys(mapping).length) {
    fd.append('mapping', new Blob([JSON.stringify(mapping)], { type: 'application/json' }))
  }
  return axios.post(`${BASE}/organizations/${orgId}/customers/import`, fd, {
    headers: { ...authHeaders(apiKey), 'Content-Type': 'multipart/form-data' }
  })
}

export interface CustomerImportPreview {
  columns: string[]
  sampleRows: string[][]
  suggestedMapping: Record<string, string>
}

export const previewCustomersCsv = (
  orgId: string, file: File, apiKey?: string
): Promise<AxiosResponse<CustomerImportPreview>> => {
  const fd = new FormData()
  fd.append('file', file)
  return axios.post(`${BASE}/organizations/${orgId}/customers/import/preview`, fd, {
    headers: { ...authHeaders(apiKey), 'Content-Type': 'multipart/form-data' }
  })
}

// ─── Campaigns ────────────────────────────────────────────────────────────────
export const createCampaign = (data: unknown, apiKey?: string): Promise<AxiosResponse<Campaign>> =>
  axios.post(`${BASE}/campaigns`, data, { headers: authHeaders(apiKey) })

export const listCampaigns = (): Promise<AxiosResponse<Campaign[]>> =>
  axios.get(`${BASE}/campaigns`)

export const getCampaign = (id: string): Promise<AxiosResponse<Campaign>> =>
  axios.get(`${BASE}/campaigns/${id}`)

export const retryCampaign = (id: string, apiKey?: string): Promise<AxiosResponse<void>> =>
  axios.post(`${BASE}/campaigns/${id}/retry`, {}, { headers: authHeaders(apiKey) })

// ─── ERP Dispatch ─────────────────────────────────────────────────────────────
export const erpDispatch = (data: unknown, apiKey?: string): Promise<AxiosResponse<unknown>> =>
  axios.post(`${BASE}/erp/dispatch`, data, { headers: authHeaders(apiKey) })

export const erpDispatchUpload = (formData: FormData, apiKey?: string): Promise<AxiosResponse<unknown>> =>
  axios.post(`${BASE}/erp/dispatch/upload`, formData, {
    headers: { ...authHeaders(apiKey), 'Content-Type': 'multipart/form-data' }
  })

export const listErpAdapters = (): Promise<AxiosResponse<string[]>> =>
  axios.get(`${BASE}/erp/adapters`)

export const checkErpHealth = (erpSource: string, tenantId = 'default'): Promise<AxiosResponse<unknown>> =>
  axios.get(`${BASE}/erp/health/${erpSource}`, { params: { tenantId } })

// ─── Single Mail ──────────────────────────────────────────────────────────────
export const sendSingleInvoice = (data: unknown, apiKey?: string): Promise<AxiosResponse<unknown>> =>
  axios.post(`${BASE}/mail/invoice`, data, { headers: authHeaders(apiKey) })

export const sendSingleInvoiceUpload = (formData: FormData, apiKey?: string): Promise<AxiosResponse<unknown>> =>
  axios.post(`${BASE}/mail/invoice/upload`, formData, {
    headers: { ...authHeaders(apiKey), 'Content-Type': 'multipart/form-data' }
  })

// ─── My Dashboard ─────────────────────────────────────────────────────────────
export const getMyProfile = (apiKey?: string): Promise<AxiosResponse<Organization>> =>
  axios.get(`${BASE}/my/profile`, { headers: authHeaders(apiKey) })

export const getMyStats = (apiKey?: string): Promise<AxiosResponse<OrgStats>> =>
  axios.get(`${BASE}/my/stats`, { headers: authHeaders(apiKey) })

export const getMyCampaigns = (
  apiKey?: string, params?: Record<string, string>
): Promise<AxiosResponse<Campaign[]>> =>
  axios.get(`${BASE}/my/campaigns`, { headers: authHeaders(apiKey), params })

export const getMyCampaignDetail = (
  id: string, apiKey?: string
): Promise<AxiosResponse<CampaignDetail>> =>
  axios.get(`${BASE}/my/campaigns/${id}`, { headers: authHeaders(apiKey) })

export const getMyInvoices = (
  apiKey?: string, params?: Record<string, string>
): Promise<AxiosResponse<PageResponse<InvoiceRecord>>> =>
  axios.get(`${BASE}/my/invoices`, { headers: authHeaders(apiKey), params })

export const getCustomerInvoices = (
  customerId: string, apiKey?: string, params?: Record<string, string>
): Promise<AxiosResponse<PageResponse<InvoiceRecord>>> =>
  axios.get(`${BASE}/my/customers/${customerId}/invoices`, { headers: authHeaders(apiKey), params })

export const getMyInvoiceByNumber = (
  invoiceNumber: string, apiKey?: string
): Promise<AxiosResponse<InvoiceRecord[]>> =>
  axios.get(`${BASE}/my/invoices/${invoiceNumber}`, { headers: authHeaders(apiKey) })

export const getMyBilling = (
  apiKey?: string, period?: string
): Promise<AxiosResponse<BillingPeriodSummary>> =>
  axios.get(`${BASE}/my/billing`, { headers: authHeaders(apiKey), params: period ? { period } : {} })

export const getMyBillingHistory = (apiKey?: string): Promise<AxiosResponse<BillingPeriodSummary[]>> =>
  axios.get(`${BASE}/my/billing/history`, { headers: authHeaders(apiKey) })

// ─── Billing (admin) ──────────────────────────────────────────────────────────
export const createRateProfile = (data: Partial<RateProfile>): Promise<AxiosResponse<RateProfile>> =>
  axios.post(`${BASE}/billing/rate-profiles`, data)

export const listRateProfiles = (): Promise<AxiosResponse<RateProfile[]>> =>
  axios.get(`${BASE}/billing/rate-profiles`)

export const estimateCost = (data: { rateProfileId: string; invoiceCount: number }): Promise<AxiosResponse<CostEstimate>> =>
  axios.post(`${BASE}/billing/estimate`, data)

export const getBillingSummaryAdmin = (
  orgId: string, period: string
): Promise<AxiosResponse<BillingPeriodSummary>> =>
  axios.get(`${BASE}/billing/summary/${orgId}/${period}`)

export const getBillingHistoryAdmin = (orgId: string): Promise<AxiosResponse<BillingPeriodSummary[]>> =>
  axios.get(`${BASE}/billing/history/${orgId}`)

export const closeBillingPeriod = (orgId: string, period: string): Promise<AxiosResponse<void>> =>
  axios.post(`${BASE}/billing/close/${orgId}/${period}`)

export const getUsageRecordsAdmin = (
  orgId: string, period: string
): Promise<AxiosResponse<UsageRecord[]>> =>
  axios.get(`${BASE}/billing/usage/${orgId}/${period}`)

// ─── Admin PEPPOL Onboarding ────────────────────────────────────────────────
export const adminPeppolOnboard = (orgId: string, data: {
  deliveryMode: DeliveryMode
  participantName: string
  endpointUrl: string
  simplifiedHttpDelivery?: boolean
  peppolParticipantId?: string
  certificate?: string
  deliveryAuthToken?: string
}): Promise<AxiosResponse<unknown>> =>
  axios.post(`${BASE}/admin/orgs/${orgId}/peppol/onboard`, data)

// ─── eRegistry (PEPPOL) ───────────────────────────────────────────────────────
export const registerAccessPoint = (data: Partial<AccessPoint>, apiKey?: string): Promise<AxiosResponse<AccessPoint>> =>
  axios.post(`${BASE}/eregistry/access-points`, data, { headers: authHeaders(apiKey) })

export const listAccessPoints = (params?: Record<string, string>): Promise<AxiosResponse<AccessPoint[]>> =>
  axios.get(`${BASE}/eregistry/access-points`, { params })

export const getAccessPointByParticipant = (participantId: string): Promise<AxiosResponse<AccessPoint>> =>
  axios.get(`${BASE}/eregistry/access-points/by-participant/${participantId}`)

export const updateAccessPointStatus = (id: string, status: string): Promise<AxiosResponse<void>> =>
  axios.patch(`${BASE}/eregistry/access-points/${id}/status`, null, { params: { status } })

export const listParticipantLinks = (orgId: string): Promise<AxiosResponse<ParticipantLink[]>> =>
  axios.get(`${BASE}/eregistry/participant-links`, { params: { organizationId: orgId } })

export const createParticipantLink = (req: CreateParticipantLinkRequest): Promise<AxiosResponse<ParticipantLink>> =>
  axios.post(`${BASE}/eregistry/participant-links`, req)

export const deleteParticipantLink = (id: string): Promise<AxiosResponse<void>> =>
  axios.delete(`${BASE}/eregistry/participant-links/${id}`)

export const getPeppolDeliveries = (organizationId: string): Promise<AxiosResponse<PeppolDelivery[]>> =>
  axios.get(`${BASE}/eregistry/deliveries`, { params: { organizationId } })

// ─── PEPPOL Inbound ───────────────────────────────────────────────────────────
export const getPeppolInbox = (params?: Record<string, string>): Promise<AxiosResponse<PeppolInboundDoc[]>> =>
  axios.get('/peppol/as4/inbox', { params })

export const getPeppolHealth = (): Promise<AxiosResponse<PeppolHealth>> =>
  axios.get('/peppol/as4/health')

// ─── Delivery Dashboard ───────────────────────────────────────────────────────
export const getPeppolStats = (orgId: string, apiKey?: string): Promise<AxiosResponse<PeppolDeliveryStats>> =>
  axios.get(`${BASE}/dashboard/${orgId}/peppol-stats`, { headers: authHeaders(apiKey) })

export const getFailedDeliveries = (orgId: string, apiKey?: string): Promise<AxiosResponse<PeppolDelivery[]>> =>
  axios.get(`${BASE}/dashboard/${orgId}/failed-deliveries`, { headers: authHeaders(apiKey) })

export const retryDelivery = (orgId: string, deliveryRecordId: string, apiKey?: string): Promise<AxiosResponse<void>> =>
  axios.post(`${BASE}/dashboard/${orgId}/retry/${deliveryRecordId}`, {}, { headers: authHeaders(apiKey) })

// ─── PEPPOL Invitations ───────────────────────────────────────────────────────
export const sendPeppolInvitation = (apiKey: string, customerEmail: string): Promise<PeppolInvitation> =>
  axios.post<PeppolInvitation>(`${BASE}/my/invitations`, { customerEmail }, { headers: authHeaders(apiKey) })
    .then(r => r.data)

export const listInvitations = (apiKey: string): Promise<PeppolInvitation[]> =>
  axios.get<PeppolInvitation[]>(`${BASE}/my/invitations`, { headers: authHeaders(apiKey) })
    .then(r => r.data)

export const cancelInvitation = (apiKey: string, id: string): Promise<void> =>
  axios.delete(`${BASE}/my/invitations/${id}`, { headers: authHeaders(apiKey) })
    .then(() => undefined)

export const validateInvitationToken = (token: string): Promise<TokenValidationResponse> =>
  axios.get<TokenValidationResponse>(`${BASE}/invitations/${token}`)
    .then(r => r.data)

export const completeInvitation = (
  token: string,
  data: { participantId: string; endpointUrl: string; deliveryAuthToken?: string; simplifiedHttpDelivery: boolean }
): Promise<{ participantId: string; endpointUrl: string }> =>
  axios.post<{ participantId: string; endpointUrl: string }>(`${BASE}/invitations/${token}/complete`, data)
    .then(r => r.data)

// ─── PEPPOL PKI (Certificate Management) ──────────────────────────────────────
export const adminPeppolUploadCert = (
  orgId: string, data: PeppolCertUploadRequest
): Promise<AxiosResponse<PeppolCertificate>> =>
  axios.post(`${BASE}/admin/orgs/${orgId}/peppol/certs`, data)

export const adminPeppolRotateCert = (
  orgId: string, data: PeppolCertUploadRequest
): Promise<AxiosResponse<PeppolCertificate>> =>
  axios.post(`${BASE}/admin/orgs/${orgId}/peppol/certs/rotate`, data)

export const adminPeppolGetActiveCert = (
  orgId: string
): Promise<AxiosResponse<PeppolActiveCertResponse>> =>
  axios.get(`${BASE}/admin/orgs/${orgId}/peppol/certs/active`)

export const adminPeppolListCerts = (
  orgId: string
): Promise<AxiosResponse<PeppolCertificate[]>> =>
  axios.get(`${BASE}/admin/orgs/${orgId}/peppol/certs`)

// ─── Email Templates ─────────────────────────────────────────────────────────
export interface EmailTemplate {
  id: string
  name: string
  subject: string
  body: string
  isDefault: boolean
  createdAt?: string
  updatedAt?: string
}

export interface EmailTemplateUpsert {
  name: string
  subject: string
  body: string
  isDefault?: boolean
}

export const listEmailTemplates = (apiKey?: string): Promise<AxiosResponse<EmailTemplate[]>> =>
  axios.get(`${BASE}/my/email-templates`, { headers: authHeaders(apiKey) })

export const createEmailTemplate = (
  data: EmailTemplateUpsert, apiKey?: string
): Promise<AxiosResponse<EmailTemplate>> =>
  axios.post(`${BASE}/my/email-templates`, data, { headers: authHeaders(apiKey) })

export const updateEmailTemplate = (
  id: string, data: EmailTemplateUpsert, apiKey?: string
): Promise<AxiosResponse<EmailTemplate>> =>
  axios.put(`${BASE}/my/email-templates/${id}`, data, { headers: authHeaders(apiKey) })

export const deleteEmailTemplate = (id: string, apiKey?: string): Promise<AxiosResponse<void>> =>
  axios.delete(`${BASE}/my/email-templates/${id}`, { headers: authHeaders(apiKey) })

export const setDefaultEmailTemplate = (
  id: string, apiKey?: string
): Promise<AxiosResponse<EmailTemplate>> =>
  axios.post(`${BASE}/my/email-templates/${id}/set-default`, {}, { headers: authHeaders(apiKey) })
