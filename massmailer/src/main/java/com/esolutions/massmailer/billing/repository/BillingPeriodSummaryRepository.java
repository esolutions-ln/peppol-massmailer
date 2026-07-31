package com.esolutions.massmailer.billing.repository;

import com.esolutions.massmailer.billing.model.BillingPeriodSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingPeriodSummaryRepository extends JpaRepository<BillingPeriodSummary, UUID> {

    Optional<BillingPeriodSummary> findByOrganizationIdAndBillingPeriod(UUID orgId, String period);

    List<BillingPeriodSummary> findByOrganizationIdOrderByBillingPeriodDesc(UUID orgId);

    List<BillingPeriodSummary> findByBillingPeriodAndStatus(
            String period, BillingPeriodSummary.BillingStatus status);

    /** All summaries for a billing period across all organisations (for revenue reports). */
    List<BillingPeriodSummary> findByBillingPeriod(String period);
}
