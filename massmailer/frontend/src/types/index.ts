export interface PageResponse<T> {
  content: T[]
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
}

export interface InvoiceRecord {
  id: string
  invoiceNumber: string
  recipientEmail: string
  recipientName: string
  status: string
  currency: string
  totalAmount: number
  vatAmount: number
  invoiceDate: string
  dueDate: string
  sentAt: string
  errorMessage: string
  retryCount: number
  messageId: string
  campaignId: string
  campaignName: string
  pdfFileName: string
  pdfBase64: string
  attachmentSizeBytes: number
}