package com.esolutions.massmailer.billing.repository;

import com.esolutions.massmailer.billing.model.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    long countByOrganizationIdAndBillingPeriod(UUID orgId, String period);

    @Query("SELECT COUNT(u) FROM UsageRecord u WHERE u.organizationId = :orgId " +
            "AND u.billingPeriod = :period AND (u.outcome = 'DELIVERED' OR u.outcome = 'FAILED')")
    long countBillableByOrgAndPeriod(UUID orgId, String period);

    @Query("SELECT u.outcome, COUNT(u) FROM UsageRecord u " +
            "WHERE u.organizationId = :orgId AND u.billingPeriod = :period " +
            "GROUP BY u.outcome")
    List<Object[]> countByOutcome(UUID orgId, String period);

    List<UsageRecord> findByOrganizationIdAndBillingPeriodOrderByRecordedAtDesc(
            UUID orgId, String period);

    /** Bulk-marks all unbilled records for an org+period as billed. Called on period close. */
    @Modifying
    @Query("UPDATE UsageRecord u SET u.billed = true " +
           "WHERE u.organizationId = :orgId AND u.billingPeriod = :period AND u.billed = false")
    int markBilledByOrgAndPeriod(@Param("orgId") UUID orgId, @Param("period") String period);
}
