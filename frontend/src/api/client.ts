import axios, { AxiosResponse } from 'axios'
import type {
  Organization, OrgUser, CustomerContact, Campaign, CampaignDetail,
  InvoiceRecord, RateProfile, BillingPeriodSummary, UsageRecord,
  CostEstimate, OrgStats, AccessPoint, PeppolDelivery,
  PeppolInboundDoc, PeppolHealth, ParticipantLink, CreateParticipantLinkRequest,
  PeppolDeliveryStats, PeppolInvitation, TokenValidationResponse
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

function authHeaders(apiKey?: string): Record<string, string> {
  return apiKey ? { 'X-API-Key': apiKey } : {}
}

// ─── Admin Auth ───────────────────────────────────────────────────────────────
export const adminLogin = (username: string, password: string): Promise<AxiosResponse<{ apiKey: string; name: string }>> =>
  axios.post(`${BASE}/admin/login`, { username, password })

// ─── Organizations ────────────────────────────────────────────────────────────
export const registerOrg = (data: Partial<Organization> & { user: OrgUser }): Promise<AxiosResponse<Organization>> =>
  axios.post(`${BASE}/organizations`, data)

export const listOrgs = (): Promise<AxiosResponse<Organization[]>> =>
  axios.get(`${BASE}/organizations`)

export const getOrgById = (id: string): Promise<AxiosResponse<Organization>> =>
  axios.get(`${BASE}/organizations/${id}`)

export const getOrgBySlug = (slug: string): Promise<AxiosResponse<Organization>> =>
  axios.get(`${BASE}/organizations/by-slug/${slug}`)

export const assignRateProfile = (id: string, rateProfileId: string): Promise<AxiosResponse<void>> =>
  axios.patch(`${BASE}/organizations/${id}/rate-profile`, { rateProfileId })

// ─── Customers ────────────────────────────────────────────────────────────────
export const registerCustomer = (
  orgId: string, data: Partial<CustomerContact>, apiKey?: string
): Promise<AxiosResponse<CustomerContact>> =>
  axios.post(`${BASE}/organizations/${orgId}/customers`, data, { headers: authHeaders(apiKey) })

export const listCustomers = (
  orgId: string, apiKey?: string
): Promise<AxiosResponse<CustomerContact[]>> =>
  axios.get(`${BASE}/organizations/${orgId}/customers`, { headers: authHeaders(apiKey) })

export const getCustomerByEmail = (
  orgId: string, email: string, apiKey?: string
): Promise<AxiosResponse<CustomerContact>> =>
  axios.get(`${BASE}/organizations/${orgId}/customers/by-email`, {
    headers: authHeaders(apiKey), params: { email }
  })

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
): Promise<AxiosResponse<InvoiceRecord[]>> =>
  axios.get(`${BASE}/my/invoices`, { headers: authHeaders(apiKey), params })

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
