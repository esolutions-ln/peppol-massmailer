package com.esolutions.massmailer.peppol.controller;

import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus;
import com.esolutions.massmailer.peppol.repository.PeppolDeliveryRecordRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Per-organisation PEPPOL delivery dashboard.
 *
 * Provides aggregate statistics, failed delivery listing, and retry capability.
 *
 * Base path: /api/v1/dashboard/{orgId}
 */
@RestController
@RequestMapping("/api/v1/dashboard/{orgId}")
@Tag(name = "PEPPOL Delivery Dashboard")
public class OrgDeliveryDashboardController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PeppolDeliveryRecordRepository deliveryRepo;

    public OrgDeliveryDashboardController(PeppolDeliveryRecordRepository deliveryRepo) {
        this.deliveryRepo = deliveryRepo;
    }

    // ── Response records ──

    /**
     * A single day's delivery counts in the 30-day trend.
     */
    public record DailyDeliveryCount(
            String date,       // "yyyy-MM-dd"
            long delivered,
            long failed
    ) {}

    /**
     * Aggregate PEPPOL delivery statistics for an organisation.
     */
    public record PeppolDeliveryStats(
            long totalDispatched,
            long delivered,
            long failed,
            long retrying,
            double successRate,       // (delivered / totalDispatched) * 100; 0 when totalDispatched=0
            String currentPeriod,     // "yyyy-MM"
            List<DailyDeliveryCount> dailyTrend  // last 30 days
    ) {}

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/dashboard/{orgId}/peppol-stats
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Get PEPPOL delivery statistics",
            description = """
                    Returns aggregate delivery counts, success rate, and a 30-day daily trend
                    for the specified organisation.

                    - `successRate` = (delivered / totalDispatched) × 100; returns 0 when totalDispatched = 0
                    - `currentPeriod` = current month in "YYYY-MM" format
                    - `dailyTrend` = last 30 days grouped by date (UTC), with delivered and failed counts per day
                    """)
    @ApiResponse(responseCode = "200", description = "Stats returned")
    @GetMapping(value = "/peppol-stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PeppolDeliveryStats> getPeppolStats(@PathVariable UUID orgId) {

        long totalDispatched = deliveryRepo.countByOrganizationId(orgId);
        long delivered = deliveryRepo.countByOrganizationIdAndStatus(orgId, DeliveryStatus.DELIVERED);
        long failed = deliveryRepo.countByOrganizationIdAndStatus(orgId, DeliveryStatus.FAILED);
        long retrying = deliveryRepo.countByOrganizationIdAndStatus(orgId, DeliveryStatus.RETRYING);

        double successRate = totalDispatched == 0
                ? 0.0
                : (delivered * 100.0) / totalDispatched;

        String currentPeriod = LocalDate.now(ZoneOffset.UTC).format(PERIOD_FMT);

        List<DailyDeliveryCount> dailyTrend = computeDailyTrend(orgId);

        return ResponseEntity.ok(new PeppolDeliveryStats(
                totalDispatched, delivered, failed, retrying,
                successRate, currentPeriod, dailyTrend
        ));
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/dashboard/{orgId}/failed-deliveries
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "List failed PEPPOL deliveries",
            description = "Returns all PeppolDeliveryRecord entries with status=FAILED for the organisation.")
    @ApiResponse(responseCode = "200", description = "Failed deliveries returned")
    @GetMapping(value = "/failed-deliveries", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PeppolDeliveryRecord>> getFailedDeliveries(@PathVariable UUID orgId) {
        List<PeppolDeliveryRecord> failed =
                deliveryRepo.findByOrganizationIdAndStatus(orgId, DeliveryStatus.FAILED);
        return ResponseEntity.ok(failed);
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /api/v1/dashboard/{orgId}/retry/{deliveryRecordId}
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Retry a failed PEPPOL delivery",
            description = """
                    Re-queues a failed delivery by resetting its status to PENDING and retryCount to 0.
                    The stored `ublXmlPayload` is preserved — no UBL rebuild occurs.

                    Returns HTTP 400 if the record is not in FAILED status.

                    Note: after re-queuing, a separate retry job or manual trigger is responsible
                    for picking up PENDING records and re-transmitting them.
                    """)
    @ApiResponse(responseCode = "200", description = "Delivery re-queued as PENDING")
    @ApiResponse(responseCode = "400", description = "Record is not in FAILED status")
    @ApiResponse(responseCode = "404", description = "Delivery record not found")
    @PostMapping(value = "/retry/{deliveryRecordId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> retryDelivery(
            @PathVariable UUID orgId,
            @PathVariable UUID deliveryRecordId) {

        Optional<PeppolDeliveryRecord> opt = deliveryRepo.findById(deliveryRecordId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        PeppolDeliveryRecord record = opt.get();

        // Scope check — ensure the record belongs to this org
        if (!orgId.equals(record.getOrganizationId())) {
            return ResponseEntity.notFound().build();
        }

        if (record.getStatus() != DeliveryStatus.FAILED) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Delivery record is not in FAILED status",
                           "currentStatus", record.getStatus().name(),
                           "deliveryRecordId", deliveryRecordId));
        }

        // Reset to PENDING so a retry job or trigger can pick it up
        record.setStatus(DeliveryStatus.PENDING);
        record.setRetryCount(0);
        record.setErrorMessage(null);
        deliveryRepo.save(record);

        return ResponseEntity.ok(Map.of(
                "message", "Delivery re-queued as PENDING",
                "deliveryRecordId", deliveryRecordId,
                "status", DeliveryStatus.PENDING.name()
        ));
    }

    // ── Helpers ──

    /**
     * Computes the 30-day daily delivery trend by grouping records by UTC date.
     * Returns one entry per day that has at least one record; days with no activity are omitted.
     */
    private List<DailyDeliveryCount> computeDailyTrend(UUID orgId) {
        Instant since = LocalDate.now(ZoneOffset.UTC).minusDays(29)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        List<PeppolDeliveryRecord> recent =
                deliveryRepo.findByOrganizationIdAndCreatedAtAfter(orgId, since);

        // Group by date string, accumulate delivered/failed counts
        Map<String, long[]> byDate = new TreeMap<>(); // TreeMap keeps dates sorted
        for (PeppolDeliveryRecord r : recent) {
            String date = r.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().format(DATE_FMT);
            long[] counts = byDate.computeIfAbsent(date, k -> new long[]{0L, 0L});
            if (r.getStatus() == DeliveryStatus.DELIVERED) {
                counts[0]++;
            } else if (r.getStatus() == DeliveryStatus.FAILED) {
                counts[1]++;
            }
        }

        return byDate.entrySet().stream()
                .map(e -> new DailyDeliveryCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .collect(Collectors.toList());
    }
}
