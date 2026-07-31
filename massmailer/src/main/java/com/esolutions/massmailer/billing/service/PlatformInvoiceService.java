package com.esolutions.massmailer.billing.service;

import com.esolutions.massmailer.billing.model.BillingPeriodSummary;
import com.esolutions.massmailer.billing.repository.BillingPeriodSummaryRepository;
import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.service.TemplateRenderService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.UnsupportedEncodingException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generates and dispatches the monthly platform billing invoice to each
 * organisation's accounts email address.
 *
 * <p>The service renders the {@code platform-invoice} Thymeleaf template,
 * sends it via the configured JavaMailSender, and transitions the
 * {@link BillingPeriodSummary} from {@code CLOSED} → {@code INVOICED}.</p>
 *
 * <p>Designed to be called by {@link BillingScheduler} after period close,
 * or manually via the admin API endpoint.</p>
 */
@Service
public class PlatformInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(PlatformInvoiceService.class);

    private static final int PAYMENT_DUE_DAYS = 30;
    private static final String SUPPORT_EMAIL = "accounts@invoicedirect.biz";
    private static final String PAYMENT_INSTRUCTIONS =
            "Please remit payment to our accounts team at <a href=\"mailto:accounts@invoicedirect.biz\">"
            + "accounts@invoicedirect.biz</a>, quoting the invoice number above.";

    private final JavaMailSender mailSender;
    private final TemplateRenderService templateRenderer;
    private final OrganizationRepository orgRepo;
    private final BillingPeriodSummaryRepository summaryRepo;
    private final MailerProperties mailerProps;

    public PlatformInvoiceService(JavaMailSender mailSender,
                                   TemplateRenderService templateRenderer,
                                   OrganizationRepository orgRepo,
                                   BillingPeriodSummaryRepository summaryRepo,
                                   MailerProperties mailerProps) {
        this.mailSender = mailSender;
        this.templateRenderer = templateRenderer;
        this.orgRepo = orgRepo;
        this.summaryRepo = summaryRepo;
        this.mailerProps = mailerProps;
    }

    /**
     * Generates a platform invoice for the given org+period, sends it to the
     * org's {@code accountsEmail}, and transitions the summary to {@code INVOICED}.
     *
     * @param orgId  organisation ID
     * @param period billing period in {@code YYYY-MM} format
     * @return the updated {@link BillingPeriodSummary} with status INVOICED
     * @throws IllegalArgumentException if no closed summary exists for the org+period
     * @throws IllegalStateException    if the summary is not in CLOSED status
     */
    @Transactional
    public BillingPeriodSummary generateAndSend(UUID orgId, String period) {
        var summary = summaryRepo.findByOrganizationIdAndBillingPeriod(orgId, period)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No billing summary for org " + orgId + " period " + period));

        if (summary.getStatus() != BillingPeriodSummary.BillingStatus.CLOSED) {
            throw new IllegalStateException(
                    "Period must be CLOSED before generating invoice. Current status: " + summary.getStatus());
        }

        var org = orgRepo.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organisation not found: " + orgId));

        String recipientEmail = resolveRecipientEmail(org);
        String invoiceNumber = buildInvoiceNumber(orgId, period);
        Map<String, Object> vars = buildTemplateVars(org, summary, invoiceNumber, period);

        String htmlBody = templateRenderer.render("platform-invoice", null, vars);

        sendEmail(recipientEmail, org.getName(), invoiceNumber, period, htmlBody);

        summary.markInvoiced();
        summaryRepo.save(summary);

        log.info("Platform invoice {} dispatched to {} for org {} period {} — amount={} {}",
                invoiceNumber, recipientEmail, org.getSlug(), period,
                summary.getTotalAmount(), summary.getCurrency());

        return summary;
    }

    // ── Private helpers ──

    private String resolveRecipientEmail(Organization org) {
        // Prefer dedicated accounts email; fall back to sender email
        if (org.getAccountsEmail() != null && !org.getAccountsEmail().isBlank()) {
            return org.getAccountsEmail();
        }
        return org.getSenderEmail();
    }

    private String buildInvoiceNumber(UUID orgId, String period) {
        // Format: PI-{YYYY-MM}-{first 8 chars of orgId}
        return "PI-" + period + "-" + orgId.toString().substring(0, 8).toUpperCase();
    }

    private Map<String, Object> buildTemplateVars(Organization org,
                                                   BillingPeriodSummary summary,
                                                   String invoiceNumber,
                                                   String period) {
        var ym = YearMonth.parse(period);
        var invoiceDateLocal = ym.plusMonths(1).atDay(1); // 1st of the following month
        var dueDateLocal = invoiceDateLocal.plusDays(PAYMENT_DUE_DAYS);
        var fmt = DateTimeFormatter.ofPattern("d MMMM yyyy");

        Map<String, Object> vars = new HashMap<>();
        vars.put("orgName", org.getName());
        vars.put("billingPeriodLabel", ym.getMonth().getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + ym.getYear());
        vars.put("platformInvoiceNumber", invoiceNumber);
        vars.put("invoiceDate", invoiceDateLocal.format(fmt));
        vars.put("dueDate", dueDateLocal.format(fmt));
        vars.put("paymentDueDays", PAYMENT_DUE_DAYS);
        vars.put("rateProfileName", summary.getRateProfileName());
        vars.put("totalSubmitted", summary.getTotalInvoicesSubmitted());
        vars.put("deliveredCount", summary.getDeliveredCount());
        vars.put("failedCount", summary.getFailedCount());
        vars.put("skippedCount", summary.getSkippedCount());
        vars.put("billableCount", summary.getBillableCount());
        vars.put("baseFee", summary.getBaseFee());
        vars.put("usageCharges", summary.getUsageCharges());
        vars.put("totalAmount", summary.getTotalAmount());
        vars.put("currency", summary.getCurrency() != null ? summary.getCurrency() : "USD");
        vars.put("paymentInstructions", PAYMENT_INSTRUCTIONS);
        vars.put("supportEmail", SUPPORT_EMAIL);
        return vars;
    }

    private void sendEmail(String recipientEmail, String orgName,
                           String invoiceNumber, String period, String htmlBody) {
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(new InternetAddress(
                    mailerProps.fromAddress(),
                    mailerProps.fromName(),
                    "UTF-8"));
            helper.setTo(new InternetAddress(recipientEmail, orgName, "UTF-8"));
            helper.setSubject("InvoiceDirect Invoice " + invoiceNumber + " — " + period);
            helper.setText(htmlBody, true);

            // Anti-reply headers — this is a system-generated billing notification
            message.setHeader("X-Auto-Response-Suppress", "OOF, AutoReply");
            message.setHeader("Auto-Submitted", "auto-generated");

            mailSender.send(message);

        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new RuntimeException(
                    "Failed to send platform invoice " + invoiceNumber + " to " + recipientEmail, e);
        }
    }
}
