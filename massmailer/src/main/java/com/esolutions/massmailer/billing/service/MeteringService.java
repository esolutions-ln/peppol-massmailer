package com.esolutions.massmailer.billing.service;

import com.esolutions.massmailer.billing.model.BillingPeriodSummary;
import com.esolutions.massmailer.billing.model.RateProfile;
import com.esolutions.massmailer.billing.model.UsageRecord;
import com.esolutions.massmailer.billing.model.UsageRecord.DeliveryOutcome;
import com.esolutions.massmailer.billing.repository.*;
import com.esolutions.massmailer.model.DeliveryResult;
import com.esolutions.massmailer.organization.model.Organization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Core metering service — records every invoice that passes through the
 * delivery channel and maintains running billing period summaries.
 *
 * <p>In PEPPOL terms, this is the Access Point's message counter.
 * Every document (invoice PDF) that enters the network, regardless of
 * whether delivery succeeds, gets metered here.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Called concurrently from virtual threads during batch dispatch.
 * Uses {@code REQUIRES_NEW} propagation so each meter record commits
 * independently — a billing DB write never rolls back an email send.</p>
 */
@Service
public class MeteringService {

    private static final Logger log = LoggerFactory.getLogger(MeteringService.class);

    private final UsageRecordRepository usageRepo;
    private final BillingPeriodSummaryRepository summaryRepo;
    private final RateProfileRepository rateProfileRepo;

    public MeteringService(UsageRecordRepository usageRepo,
                            BillingPeriodSummaryRepository summaryRepo,
                            RateProfileRepository rateProfileRepo) {
        this.usageRepo = usageRepo;
        this.summaryRepo = summaryRepo;
        this.rateProfileRepo = rateProfileRepo;
    }

    /**
     * Records an invoice delivery and updates the billing period summary.
     * Called by the dispatch pipeline after each individual send.
     *
     * @param org           the sending organization
     * @param result        the sealed DeliveryResult from the SMTP service
     * @param campaignId    optional campaign ID (null for single sends)
     * @param pdfSizeBytes  size of the attached PDF
     * @param erpSource     ERP system that produced the invoice
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDelivery(Organization org,
                                DeliveryResult result,
                                UUID campaignId,
                                long pdfSizeBytes,
                                String erpSource) {

        String period = UsageRecord.deriveBillingPeriod(Instant.now());

        // ── 1. Map sealed DeliveryResult → DeliveryOutcome ──
        DeliveryOutcome outcome = switch (result) {
            case DeliveryResult.Delivered _ -> DeliveryOutcome.DELIVERED;
            case DeliveryResult.Failed _    -> DeliveryOutcome.FAILED;
            case DeliveryResult.Skipped _   -> DeliveryOutcome.SKIPPED;
        };

        // ── 2. Create usage record ──
        var record = UsageRecord.builder()
                .organizationId(org.getId())
                .billingPeriod(period)
                .invoiceNumber(result.invoiceNumber())
                .campaignId(campaignId)
                .recipientEmail(result.recipientEmail())
                .outcome(outcome)
                .erpSource(erpSource)
                .pdfSizeBytes(pdfSizeBytes)
                .build();

        usageRepo.save(record);

        // ── 3. Update billing period summary (upsert) ──
        var summary = summaryRepo
                .findByOrganizationIdAndBillingPeriod(org.getId(), period)
                .orElseGet(() -> BillingPeriodSummary.builder()
                        .organizationId(org.getId())
                        .billingPeriod(period)
                        .build());

        // Increment counters
        summary.setTotalInvoicesSubmitted(summary.getTotalInvoicesSubmitted() + 1);

        switch (outcome) {
            case DELIVERED -> summary.setDeliveredCount(summary.getDeliveredCount() + 1);
            case FAILED    -> summary.setFailedCount(summary.getFailedCount() + 1);
            case SKIPPED   -> summary.setSkippedCount(summary.getSkippedCount() + 1);
        }

        if (record.isBillable()) {
            summary.setBillableCount(summary.getBillableCount() + 1);
        }

        // Recalculate cost if rate profile is assigned
        if (org.getRateProfileId() != null) {
            rateProfileRepo.findById(org.getRateProfileId())
                    .ifPresent(summary::recalculate);
        }

        summaryRepo.save(summary);

        log.debug("Metered invoice {} for org {} [period={}, outcome={}, billable={}]",
                result.invoiceNumber(), org.getSlug(), period, outcome, record.isBillable());
    }

    /**
     * Returns the current billable count for an org in a billing period.
     */
    @Transactional(readOnly = true)
    public long getBillableCount(UUID orgId, String period) {
        return usageRepo.countBillableByOrgAndPeriod(orgId, period);
    }
}
