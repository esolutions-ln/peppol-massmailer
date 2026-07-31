package com.esolutions.massmailer.billing.service;

import com.esolutions.massmailer.billing.model.BillingPeriodSummary;
import com.esolutions.massmailer.billing.repository.BillingPeriodSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

/**
 * Automated month-end billing jobs.
 *
 * <h3>Job 1 — Period Close ({@link #closeExpiredPeriods()})</h3>
 * <p>Runs at 00:05 on the 1st of every month. Finds all OPEN billing period
 * summaries for the previous month, closes each one (marking all
 * {@code UsageRecord}s as billed), and immediately dispatches the platform
 * invoice email to the organisation's accounts address.</p>
 *
 * <p>Failures for individual organisations are caught and logged so one bad
 * org (e.g. invalid email) never blocks the rest of the run.</p>
 *
 * <h3>Cron expressions</h3>
 * <pre>
 *   closeExpiredPeriods  — "0 5 0 1 * *"  (00:05 on the 1st of each month)
 * </pre>
 *
 * <p>{@code @EnableScheduling} is declared on {@link com.esolutions.massmailer.MassMailerApplication}.</p>
 */
@Component
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);

    private final BillingService billingService;
    private final PlatformInvoiceService platformInvoiceService;
    private final BillingPeriodSummaryRepository summaryRepo;

    public BillingScheduler(BillingService billingService,
                             PlatformInvoiceService platformInvoiceService,
                             BillingPeriodSummaryRepository summaryRepo) {
        this.billingService = billingService;
        this.platformInvoiceService = platformInvoiceService;
        this.summaryRepo = summaryRepo;
    }

    /**
     * Closes all OPEN billing period summaries for the previous calendar month,
     * then dispatches platform invoices to each organisation.
     *
     * <p>Cron: {@code 0 5 0 1 * *} — 00:05 on the 1st of each month (server UTC).</p>
     */
    @Scheduled(cron = "0 5 0 1 * *")
    public void closeExpiredPeriods() {
        String priorPeriod = YearMonth.now().minusMonths(1).toString(); // e.g. "2026-03"
        log.info("BillingScheduler: starting month-end close for period {}", priorPeriod);

        List<BillingPeriodSummary> openSummaries = summaryRepo
                .findByBillingPeriodAndStatus(priorPeriod, BillingPeriodSummary.BillingStatus.OPEN);

        if (openSummaries.isEmpty()) {
            log.info("BillingScheduler: no OPEN summaries found for period {} — nothing to do", priorPeriod);
            return;
        }

        log.info("BillingScheduler: found {} OPEN summaries to close for period {}",
                openSummaries.size(), priorPeriod);

        int closed = 0;
        int invoiced = 0;
        int errors = 0;

        for (var summary : openSummaries) {
            var orgId = summary.getOrganizationId();
            try {
                billingService.closePeriod(orgId, priorPeriod);
                closed++;
            } catch (Exception e) {
                log.error("BillingScheduler: failed to close period {} for org {} — {}",
                        priorPeriod, orgId, e.getMessage(), e);
                errors++;
                continue; // skip invoice dispatch if close failed
            }

            try {
                platformInvoiceService.generateAndSend(orgId, priorPeriod);
                invoiced++;
            } catch (Exception e) {
                // Invoice dispatch failure is non-fatal — period is still closed.
                // Admin can re-trigger via POST /api/v1/billing/invoice/{orgId}/{period}.
                log.error("BillingScheduler: failed to dispatch invoice for org {} period {} — {}",
                        orgId, priorPeriod, e.getMessage(), e);
                errors++;
            }
        }

        log.info("BillingScheduler: period {} complete — closed={}, invoiced={}, errors={}",
                priorPeriod, closed, invoiced, errors);
    }
}
