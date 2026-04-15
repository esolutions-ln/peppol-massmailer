package com.esolutions.massmailer.peppol.service;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.peppol.as4.As4DeliveryResult;
import com.esolutions.massmailer.peppol.as4.As4Message;
import com.esolutions.massmailer.peppol.as4.As4TransportClient;
import com.esolutions.massmailer.peppol.as4.As4TransportException;
import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus;
import com.esolutions.massmailer.peppol.model.PeppolParticipantLink;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import com.esolutions.massmailer.peppol.repository.PeppolDeliveryRecordRepository;
import com.esolutions.massmailer.peppol.repository.PeppolParticipantLinkRepository;
import com.esolutions.massmailer.peppol.schematron.SchematronValidationException;
import com.esolutions.massmailer.peppol.schematron.SchematronValidator;
import com.esolutions.massmailer.peppol.ubl.UblInvoiceBuilder;
import com.esolutions.massmailer.service.PdfAttachmentResolver;
import com.esolutions.massmailer.service.SmtpSendService;
import com.esolutions.massmailer.service.TemplateRenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * PEPPOL delivery service — routes invoices via the 4-corner model.
 *
 * <h3>4-Corner Model</h3>
 * <pre>
 *   C1 (Supplier ERP)
 *     → C2 (Our AP Gateway — this service)
 *       → C3 (Buyer's AP endpoint — looked up from eRegistry)
 *         → C4 (Buyer ERP / Supplier Module)
 * </pre>
 *
 * <h3>Delivery Flow</h3>
 * <ol>
 *   <li>Resolve buyer's PEPPOL participant link from eRegistry</li>
 *   <li>Look up buyer's C3 Access Point endpoint</li>
 *   <li>Build UBL 2.1 BIS 3.0 Invoice XML</li>
 *   <li>POST to buyer's AP endpoint (simplified HTTP or AS4)</li>
 *   <li>Record delivery outcome in audit log</li>
 * </ol>
 *
 * <h3>Simplified HTTP vs AS4</h3>
 * <p>For internal/private networks, simplified HTTP delivery POSTs the UBL XML
 * directly over HTTPS. For production PEPPOL, set {@code simplifiedHttpDelivery=false}
 * on the AccessPoint and implement full AS4 envelope wrapping.</p>
 */
@Service
public class PeppolDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(PeppolDeliveryService.class);

    private final AccessPointRepository apRepo;
    private final PeppolParticipantLinkRepository linkRepo;
    private final PeppolDeliveryRecordRepository deliveryRepo;
    private final CustomerContactRepository customerRepo;
    private final OrganizationRepository orgRepo;
    private final UblInvoiceBuilder ublBuilder;
    private final SchematronValidator schematronValidator;
    private final RestTemplate restTemplate;
    private final As4TransportClient as4Client;
    private final TemplateRenderService templateRenderer;
    private final SmtpSendService smtpSendService;

    public PeppolDeliveryService(AccessPointRepository apRepo,
                                  PeppolParticipantLinkRepository linkRepo,
                                  PeppolDeliveryRecordRepository deliveryRepo,
                                  CustomerContactRepository customerRepo,
                                  OrganizationRepository orgRepo,
                                  UblInvoiceBuilder ublBuilder,
                                  SchematronValidator schematronValidator,
                                  RestTemplate restTemplate,
                                  As4TransportClient as4Client,
                                  TemplateRenderService templateRenderer,
                                  SmtpSendService smtpSendService) {
        this.apRepo = apRepo;
        this.linkRepo = linkRepo;
        this.deliveryRepo = deliveryRepo;
        this.customerRepo = customerRepo;
        this.orgRepo = orgRepo;
        this.ublBuilder = ublBuilder;
        this.schematronValidator = schematronValidator;
        this.restTemplate = restTemplate;
        this.as4Client = as4Client;
        this.templateRenderer = templateRenderer;
        this.smtpSendService = smtpSendService;
    }

    /**
     * Delivers an invoice via PEPPOL to the buyer's registered Access Point.
     *
     * @param organizationId the sending org (C1/C2)
     * @param invoice        the canonical invoice to deliver
     * @return the delivery record with outcome
     */
    @Transactional
    public PeppolDeliveryRecord deliver(UUID organizationId, CanonicalInvoice invoice) {

        // ── 1. Resolve supplier (C1) ──
        Organization supplier = orgRepo.findById(organizationId)
                .orElseThrow(() -> new PeppolRoutingException(
                        "Organization not found: " + organizationId));

        // ── 2. Resolve buyer's customer contact ──
        CustomerContact buyer = customerRepo
                .findByOrganizationIdAndEmail(organizationId,
                        invoice.recipientEmail().trim().toLowerCase())
                .orElseThrow(() -> new PeppolRoutingException(
                        "Customer contact not found for email: " + invoice.recipientEmail()
                        + ". Register the customer before PEPPOL dispatch."));

        // ── 3. Resolve buyer's PEPPOL participant link ──
        PeppolParticipantLink link = linkRepo
                .findByOrganizationIdAndCustomerContactId(organizationId, buyer.getId())
                .orElseGet(() -> {
                    // Buyer is not registered in the PEPPOL network — send them a notification
                    // email so they know an invoice arrived and can register their AP.
                    sendUnregisteredBuyerNotification(supplier, buyer, invoice);
                    throw new PeppolRoutingException(
                            "No PEPPOL participant link for customer: " + invoice.recipientEmail()
                            + " — notification email dispatched; register their Access Point"
                            + " in the eRegistry to enable future electronic delivery.");
                });

        // ── 4. Resolve buyer's C3 Access Point ──
        AccessPoint receiverAp = apRepo.findById(link.getReceiverAccessPointId())
                .orElseThrow(() -> new PeppolRoutingException(
                        "Receiver Access Point not found: " + link.getReceiverAccessPointId()));

        if (!receiverAp.isActive()) {
            throw new PeppolRoutingException(
                    "Receiver Access Point is not active: " + receiverAp.getParticipantId());
        }

        // ── 5. Resolve sender's C2 Access Point (our gateway) ──
        String senderParticipantId = apRepo
                .findByOrganizationIdAndStatus(organizationId, AccessPoint.AccessPointStatus.ACTIVE)
                .stream()
                .filter(ap -> ap.getRole() == AccessPoint.AccessPointRole.SENDER
                           || ap.getRole() == AccessPoint.AccessPointRole.GATEWAY)
                .findFirst()
                .map(AccessPoint::getParticipantId)
                .orElse("9915:" + supplier.getSlug()); // fallback to test scheme

        // ── 6. Build UBL 2.1 BIS 3.0 XML — include buyer for full BR-53 compliance ──
        String ublXml = ublBuilder.build(invoice, supplier, buyer);

        // ── 6a. Schematron validation ──
        var schematronResult = schematronValidator.validate(ublXml, UblInvoiceBuilder.PROFILE_ID);
        if (schematronResult.hasFatalViolations()) {
            var fatalMessages = schematronResult.violations().stream()
                    .filter(v -> v.isFatal())
                    .map(v -> "[" + v.ruleId() + "] " + v.message())
                    .toList();
            var failedRecord = PeppolDeliveryRecord.builder()
                    .organizationId(organizationId)
                    .invoiceNumber(invoice.invoiceNumber())
                    .senderParticipantId(senderParticipantId)
                    .receiverParticipantId(link.getParticipantId())
                    .deliveredToEndpoint(receiverAp.getEndpointUrl())
                    .documentTypeId(UblInvoiceBuilder.DOCUMENT_TYPE_ID)
                    .processId(UblInvoiceBuilder.PROFILE_ID)
                    .ublXmlPayload(ublXml)
                    .status(DeliveryStatus.FAILED)
                    .build();
            failedRecord.markSchematronFailed(fatalMessages);
            deliveryRepo.save(failedRecord);
            throw new SchematronValidationException(schematronResult.violations());
        }

        // ── 7. Create delivery record (TRANSMITTING) ──
        var warningMessages = schematronResult.getWarnings().stream()
                .map(v -> "[" + v.ruleId() + "] " + v.message())
                .toList();
        var record = PeppolDeliveryRecord.builder()
                .organizationId(organizationId)
                .invoiceNumber(invoice.invoiceNumber())
                .senderParticipantId(senderParticipantId)
                .receiverParticipantId(link.getParticipantId())
                .deliveredToEndpoint(receiverAp.getEndpointUrl())
                .documentTypeId(UblInvoiceBuilder.DOCUMENT_TYPE_ID)
                .processId(UblInvoiceBuilder.PROFILE_ID)
                .ublXmlPayload(ublXml)
                .schematronPassed(true)
                .status(DeliveryStatus.TRANSMITTING)
                .transmittedAt(Instant.now())
                .build();

        // Store warning messages if any
        if (!warningMessages.isEmpty()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < warningMessages.size(); i++) {
                String msg = warningMessages.get(i);
                sb.append("\"").append(msg.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                if (i < warningMessages.size() - 1) sb.append(",");
            }
            sb.append("]");
            record.setSchematronWarnings(sb.toString());
        }

        deliveryRepo.save(record);

        // ── 8. Transmit to buyer's AP ──
        try {
            if (receiverAp.isSimplifiedHttpDelivery()) {
                transmitHttp(receiverAp, ublXml, invoice.invoiceNumber());
                record.markDelivered("HTTP 200 OK");
            } else {
                // Full AS4 — markDelivered is called inside transmitAs4()
                transmitAs4(receiverAp, ublXml, record);
            }
            log.info("PEPPOL delivery successful: invoice={} → participant={} endpoint={}",
                    invoice.invoiceNumber(), link.getParticipantId(), receiverAp.getEndpointUrl());

        } catch (Exception e) {
            record.markFailed(e.getMessage());
            log.error("PEPPOL delivery failed: invoice={} → {}: {}",
                    invoice.invoiceNumber(), receiverAp.getEndpointUrl(), e.getMessage());
        }

        return deliveryRepo.save(record);
    }

    /**
     * Simplified HTTP delivery — POSTs UBL XML directly to the receiver's endpoint.
     * Used for internal/private PEPPOL networks and development.
     */
    private void transmitHttp(AccessPoint ap, String ublXml, String invoiceNumber) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.set("X-PEPPOL-Document-Type", UblInvoiceBuilder.DOCUMENT_TYPE_ID);
        headers.set("X-PEPPOL-Process", UblInvoiceBuilder.PROFILE_ID);
        headers.set("X-Invoice-Number", invoiceNumber);

        if (ap.getDeliveryAuthToken() != null && !ap.getDeliveryAuthToken().isBlank()) {
            headers.set("Authorization", "Bearer " + ap.getDeliveryAuthToken());
        }

        var entity = new HttpEntity<>(ublXml, headers);
        var response = restTemplate.postForEntity(ap.getEndpointUrl(), entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new PeppolRoutingException(
                    "AP endpoint returned non-2xx: " + response.getStatusCode()
                    + " for invoice " + invoiceNumber);
        }
    }

    /**
     * Full AS4 delivery via ebMS 3.0 transport.
     * Delegates to {@link As4TransportClient} and updates the delivery record
     * with MDN details on success, or throws {@link As4TransportException} on failure.
     */
    private void transmitAs4(AccessPoint ap, String ublXml, PeppolDeliveryRecord record) {
        As4Message message = buildAs4Message(record, ap, ublXml);
        As4DeliveryResult result = as4Client.send(message);
        if (result.success()) {
            record.markDelivered(result.rawMdnResponse(), result.mdnMessageId());
            record.setMdnStatus(result.mdnStatus());
            record.setMdnMessageId(result.mdnMessageId());
        } else {
            throw new As4TransportException(result.errorDescription());
        }
    }

    /**
     * Constructs an {@link As4Message} from the delivery record, receiver AP, and UBL payload.
     * The receiver's X.509 certificate is parsed from the AP's PEM {@code certificate} field
     * when present. Sender cert/key are not yet provisioned — a TODO is left for when
     * the sender AP certificate management is implemented.
     */
    private As4Message buildAs4Message(PeppolDeliveryRecord record, AccessPoint receiverAp, String ublXml) {
        X509Certificate receiverCert = null;
        if (receiverAp.getCertificate() != null && !receiverAp.getCertificate().isBlank()) {
            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                byte[] pemBytes = receiverAp.getCertificate()
                        .replaceAll("-----BEGIN CERTIFICATE-----", "")
                        .replaceAll("-----END CERTIFICATE-----", "")
                        .replaceAll("\\s+", "")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] derBytes = java.util.Base64.getDecoder().decode(pemBytes);
                receiverCert = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(derBytes));
            } catch (Exception e) {
                log.warn("Failed to parse receiver AP certificate for {}: {}",
                        receiverAp.getParticipantId(), e.getMessage());
            }
        }

        return new As4Message(
                record.getSenderParticipantId(),
                record.getReceiverParticipantId(),
                record.getDocumentTypeId(),
                record.getProcessId(),
                ublXml,
                // TODO: load sender X.509 cert and private key from key store
                // when sender AP certificate management is implemented
                null,
                null,
                receiverCert,
                receiverAp.getEndpointUrl()
        );
    }

    /**
     * Checks whether a customer has a PEPPOL participant link configured.
     * Used by the dispatch router to decide EMAIL vs PEPPOL channel.
     */
    public boolean hasPeppolLink(UUID organizationId, UUID customerContactId) {
        return linkRepo.findByOrganizationIdAndCustomerContactId(organizationId, customerContactId)
                .isPresent();
    }

    // ── Auto-notification for unregistered buyers ──

    /**
     * Sends a notification email to a buyer who received an invoice via the PEPPOL network
     * but is not yet registered in the local eRegistry. The email explains how to register
     * their Access Point for future electronic delivery and attaches the invoice PDF.
     *
     * <p>Failures are logged and swallowed — the notification is best-effort and must not
     * prevent the caller from recording the routing failure.</p>
     */
    private void sendUnregisteredBuyerNotification(Organization supplier,
                                                    CustomerContact buyer,
                                                    CanonicalInvoice invoice) {
        try {
            String supplierName = supplier.getCompanyName() != null
                    ? supplier.getCompanyName() : supplier.getName();

            var vars = Map.<String, Object>of(
                    "supplierName",   supplierName,
                    "supplierEmail",  supplier.getSenderEmail(),
                    "recipientName",  buyer.getName() != null ? buyer.getName() : buyer.getEmail(),
                    "invoiceNumber",  invoice.invoiceNumber() != null ? invoice.invoiceNumber() : "N/A",
                    "invoiceDate",    invoice.invoiceDate() != null ? invoice.invoiceDate().toString() : "",
                    "totalAmount",    invoice.totalAmount() != null ? invoice.totalAmount().toPlainString() : "",
                    "currency",       invoice.currency() != null ? invoice.currency() : "USD"
            );

            String html = templateRenderer.render("peppol-invoice-notification", Map.of(), vars);

            // Attach the PDF if available
            PdfAttachmentResolver.ResolvedAttachment attachment = null;
            if (invoice.pdfSource() != null && invoice.pdfSource().hasSource()) {
                try {
                    String base64 = invoice.pdfSource().base64();
                    String fileName = invoice.pdfSource().fileName();
                    if (base64 != null && !base64.isBlank()) {
                        byte[] pdfBytes = Base64.getDecoder().decode(base64);
                        attachment = new PdfAttachmentResolver.ResolvedAttachment(
                                new ByteArrayResource(pdfBytes),
                                fileName != null ? fileName : invoice.invoiceNumber() + ".pdf",
                                "application/pdf",
                                pdfBytes.length
                        );
                    }
                } catch (Exception ex) {
                    log.warn("PEPPOL notification: could not attach PDF for invoice {}: {}",
                            invoice.invoiceNumber(), ex.getMessage());
                }
            }

            String subject = "Invoice " + invoice.invoiceNumber()
                    + " received via eSolutions Access Point from " + supplierName;

            smtpSendService.sendWithFallback(
                    buyer.getEmail(), buyer.getName(), subject, html,
                    invoice.invoiceNumber(), attachment);

            log.info("PEPPOL notification sent to unregistered buyer {} for invoice {}",
                    buyer.getEmail(), invoice.invoiceNumber());

        } catch (Exception e) {
            log.warn("PEPPOL notification failed for buyer {} (invoice {}): {}",
                    buyer.getEmail(), invoice.invoiceNumber(), e.getMessage());
        }
    }

    // ── Custom exception ──

    public static class PeppolRoutingException extends RuntimeException {
        public PeppolRoutingException(String message) { super(message); }
    }
}
