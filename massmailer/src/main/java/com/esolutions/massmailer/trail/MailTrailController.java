package com.esolutions.massmailer.trail;

import com.esolutions.massmailer.dto.PageResponse;
import com.esolutions.massmailer.security.OrgPrincipal;
import com.esolutions.massmailer.trail.MailSendAttemptRepository.DeniedIpSummary;
import com.esolutions.massmailer.trail.MailSendAttemptRepository.Filter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Email sending trail — every transport attempt, its outcome, and the IPs involved.
 *
 * Org endpoints ({@code /api/v1/my/mail-trail}) are scoped to the caller's organisation.
 * Admin endpoints ({@code /api/v1/admin/mail-trail}) span all organisations and expose
 * platform egress-IP diagnostics.
 */
@RestController
@Tag(name = "Email Sending Trail")
public class MailTrailController {

    private static final int MAX_PAGE_SIZE = 200;

    private final MailSendAttemptRepository repo;
    private final EgressIpResolver ipResolver;

    public MailTrailController(MailSendAttemptRepository repo, EgressIpResolver ipResolver) {
        this.repo = repo;
        this.ipResolver = ipResolver;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Org-scoped
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "List this organisation's email send attempts",
            description = """
                    Newest first. Every attempt made by the mail transport is recorded, including \
                    failures, with the SMTP reply / Brevo HTTP status, the enhanced status code, and \
                    the sending IPs (`egressIp`, `serverReportedIp`, `localIp`).

                    `outcome` is one of DELIVERED, FAILED, AUTH_FAILED, IP_DENIED, SKIPPED.""")
    @GetMapping(value = "/api/v1/my/mail-trail", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PageResponse<MailSendAttempt>> myTrail(
            @AuthenticationPrincipal OrgPrincipal principal,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) String invoiceNumber,
            @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) MailSendAttempt.Outcome outcome,
            @RequestParam(required = false) SendContext.Source source,
            @RequestParam(required = false) Boolean ipDenied,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (principal == null) return ResponseEntity.status(401).build();
        var filter = new Filter(principal.orgId(), campaignId, recipient, invoiceNumber,
                outcome, source, ipDenied, null, from, to);
        return ResponseEntity.ok(page(filter, page, size));
    }

    @Operation(summary = "Get one of this organisation's email send attempts")
    @GetMapping(value = "/api/v1/my/mail-trail/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MailSendAttempt> myAttempt(@AuthenticationPrincipal OrgPrincipal principal,
                                                     @PathVariable UUID id) {
        if (principal == null) return ResponseEntity.status(401).build();
        return repo.findById(id)
                .filter(a -> principal.orgId().equals(a.organizationId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Admin
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "List email send attempts across all organisations (admin)")
    @GetMapping(value = "/api/v1/admin/mail-trail", produces = MediaType.APPLICATION_JSON_VALUE)
    public PageResponse<MailSendAttempt> adminTrail(
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) String invoiceNumber,
            @RequestParam(required = false) MailSendAttempt.Outcome outcome,
            @RequestParam(required = false) SendContext.Source source,
            @RequestParam(required = false) Boolean ipDenied,
            @RequestParam(required = false) String egressIp,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var filter = new Filter(organizationId, campaignId, recipient, invoiceNumber,
                outcome, source, ipDenied, egressIp, from, to);
        return page(filter, page, size);
    }

    @Operation(summary = "Get any email send attempt (admin)")
    @GetMapping(value = "/api/v1/admin/mail-trail/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MailSendAttempt> adminAttempt(@PathVariable UUID id) {
        return repo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Sending IPs refused by the mail provider (admin)",
            description = """
                    Groups IP_DENIED attempts by egress IP, mail host and transport — the list of \
                    addresses to allow-list with the provider. Optional `since` (ISO-8601) limits the window.""")
    @GetMapping(value = "/api/v1/admin/mail-trail/denied-ips", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<DeniedIpSummary> deniedIps(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return repo.deniedIpSummary(since);
    }

    @Operation(summary = "Current outbound mail egress IP (admin)",
            description = "Performs a fresh public-IP lookup — use it to know which IP to allow-list before sending.")
    @GetMapping(value = "/api/v1/admin/mail-trail/egress-ip", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> egressIp() {
        String ip = ipResolver.fresh();
        return Map.of(
                "egressIp", ip != null ? ip : "unknown",
                "checkedAt", Instant.now().toString());
    }

    private PageResponse<MailSendAttempt> page(Filter filter, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.clamp(size, 1, MAX_PAGE_SIZE);
        long total = repo.count(filter);
        return new PageResponse<>(repo.search(filter, p, s), p, s, total, (int) ((total + s - 1) / s));
    }
}
