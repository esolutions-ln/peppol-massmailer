// ─── Auth ─────────────────────────────────────────────────────────────────────
export interface Session {
  role: 'admin' | 'org'
  name: string
  apiKey: string
  orgId?: string
  slug?: string
  username?: string
}

// ─── Delivery ─────────────────────────────────────────────────────────────────
export type DeliveryMode = 'EMAIL' | 'AS4' | 'BOTH'

// ─── Organizations ────────────────────────────────────────────────────────────
export interface OrgUser {
  id?: string
  firstName: string
  lastName: string
  jobTitle?: string
  emailAddress: string
}

export interface Organization {
  id: string
  name: string
  slug: string
  senderEmail: string
  senderDisplayName: string
  accountsEmail?: string
  companyAddress?: string
  primaryErpSource?: string
  erpTenantId?: string
  rateProfileId?: string
  apiKey: string
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED'
  // Delivery configuration
  deliveryMode: DeliveryMode        // default delivery method for this org
  peppolParticipantId?: string      // org's own PEPPOL participant ID (0190:ZW...)
  vatNumber?: string                // org's VAT number
  tinNumber?: string                // org's TIN (fallback if no VAT)
  // Primary contact user
  user?: OrgUser
}

// ─── Customers ────────────────────────────────────────────────────────────────
export interface CustomerContact {
  id: string
  email: string
  name?: string
  companyName?: string
  erpSource?: string
  vatNumber?: string            // Zimbabwe VAT number e.g. 12345678
  tinNumber?: string            // Zimbabwe TIN number e.g. 1234567890
  peppolParticipantId?: string  // derived: 0190:ZW{vatNumber} or 0190:ZW{tinNumber}
  deliveryMode?: DeliveryMode   // overrides org default for this customer
  totalInvoicesSent: number
  totalDeliveryFailures: number
  unsubscribed: boolean
  lastInvoiceSentAt?: string
}

// Derives the Zimbabwe PEPPOL participant ID from VAT or TIN
export function deriveParticipantId(vatNumber?: string, tinNumber?: string): string | null {
  const id = vatNumber?.trim() || tinNumber?.trim()
  return id ? `0190:ZW${id}` : null
}

// ─── Campaigns ────────────────────────────────────────────────────────────────
export type CampaignStatus = 'QUEUED' | 'IN_PROGRESS' | 'COMPLETED' | 'PARTIALLY_FAILED' | 'FAILED' | 'CREATED'

export interface Campaign {
  id: string
  name: string
  status: CampaignStatus
  totalRecipients: number
  sent: number
  failed: number
  skipped: number
  createdAt?: string
  completedAt?: string
}

export interface InvoiceRecord {
  id: string
  invoiceNumber: string
  recipientEmail: string
  recipientName?: string
  campaignName?: string
  status: 'SENT' | 'FAILED' | 'PENDING' | 'SKIPPED' | 'BOUNCED'
  currency?: string
  totalAmount?: number
  vatAmount?: number
  invoiceDate?: string
  dueDate?: string
  sentAt?: string
  retryCount?: number
  errorMessage?: string
  pdfBase64?: string
  pdfFileName?: string
}

export interface CampaignDetail {
  campaign: Campaign
  totalInvoices: number
  invoices: InvoiceRecord[]
}

// ─── Billing ──────────────────────────────────────────────────────────────────
export interface RateTier {
  tierName?: string
  fromInvoice: number
  toInvoice?: number | null
  ratePerInvoice: number
}

export interface RateProfile {
  id: string
  name: string
  description?: string
  currency: string
  monthlyBaseFee: number
  active: boolean
  tiers?: RateTier[]
}

export interface BillingPeriodSummary {
  billingPeriod: string
  totalSubmitted: number
  delivered: number
  failed: number
  billable: number
  baseFee: number
  usageCharges: number
  totalAmount: number
  currency: string
  status: 'OPEN' | 'CLOSED' | 'INVOICED' | 'PAID'
  rateProfileName?: string
}

export interface UsageRecord {
  id: string
  invoiceNumber: string
  recipientEmail: string
  outcome: 'DELIVERED' | 'FAILED' | 'SKIPPED'
  billable: boolean
  erpSource?: string
  pdfSizeBytes?: number
  recordedAt?: string
}

export interface CostEstimate {
  rateProfileName: string
  currency: string
  baseFee: number
  usageCharges: number
  totalEstimate: number
}

// ─── Stats ────────────────────────────────────────────────────────────────────
export interface OrgStats {
  totalCampaigns: number
  totalInvoices: number
  delivered: number
  failed: number
  currentPeriodCost?: number
  currentPeriod?: string
  billingCurrency?: string
}

// ─── PEPPOL ───────────────────────────────────────────────────────────────────
export type AccessPointRole = 'GATEWAY' | 'RECEIVER' | 'SENDER'
export type AccessPointStatus = 'ACTIVE' | 'SUSPENDED' | 'INACTIVE'

export interface AccessPoint {
  id: string
  participantId: string
  participantName: string
  role: AccessPointRole
  endpointUrl: string
  simplifiedHttpDelivery: boolean
  status: AccessPointStatus
  organizationId?: string
  deliveryAuthToken?: string
}

export interface PeppolDelivery {
  id: string
  invoiceNumber?: string
  senderParticipantId?: string
  receiverParticipantId?: string
  status: string
  deliveredToEndpoint?: string
  errorMessage?: string
  createdAt?: string
}

export interface PeppolInboundDoc {
  id: string
  invoiceNumber?: string
  senderParticipantId?: string
  receiverParticipantId?: string
  documentTypeId?: string
  payloadHash?: string
  receivedAt?: string
}

export interface PeppolHealth {
  status: 'UP' | 'DOWN'
}

// ─── Participant Links ────────────────────────────────────────────────────────
export interface ParticipantLink {
  id: string
  organizationId: string
  customerContactId: string
  customerEmail: string
  participantId: string
  receiverAccessPointId: string
  receiverApName: string
  preferredChannel: 'PEPPOL' | 'EMAIL'
  createdAt: string
}

export interface CreateParticipantLinkRequest {
  organizationId: string
  customerEmail: string
  participantId: string
  receiverAccessPointId: string
  preferredChannel?: 'PEPPOL' | 'EMAIL'
}

// ─── PEPPOL Invitations ───────────────────────────────────────────────────────
export type InvitationStatus = 'PENDING' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED'

export interface PeppolInvitation {
  id: string
  customerEmail: string
  status: InvitationStatus
  createdAt: string
  expiresAt: string
  completedAt: string | null
}

export interface TokenValidationResponse {
  customerEmail: string
  organisationName: string
}

// ─── Delivery Dashboard ───────────────────────────────────────────────────────
export interface DailyDeliveryCount {
  date: string
  delivered: number
  failed: number
}

export interface PeppolDeliveryStats {
  totalDispatched: number
  delivered: number
  failed: number
  retrying: number
  successRate: number
  currentPeriod: string
  dailyTrend: DailyDeliveryCount[]
}
