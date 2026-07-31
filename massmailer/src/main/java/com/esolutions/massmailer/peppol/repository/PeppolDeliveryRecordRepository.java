package com.esolutions.massmailer.peppol.repository;

import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PeppolDeliveryRecordRepository extends JpaRepository<PeppolDeliveryRecord, UUID> {

    Optional<PeppolDeliveryRecord> findByInvoiceNumberAndOrganizationId(
            String invoiceNumber, UUID organizationId);

    List<PeppolDeliveryRecord> findByOrganizationIdAndStatus(UUID organizationId, DeliveryStatus status);

    List<PeppolDeliveryRecord> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    long countByOrganizationId(UUID organizationId);

    long countByOrganizationIdAndStatus(UUID organizationId, DeliveryStatus status);

    /**
     * Returns all delivery records for an org created on or after the given timestamp.
     * Used for computing the 30-day daily trend.
     */
    @Query("SELECT r FROM PeppolDeliveryRecord r WHERE r.organizationId = :orgId AND r.createdAt >= :since")
    List<PeppolDeliveryRecord> findByOrganizationIdAndCreatedAtAfter(
            @Param("orgId") UUID orgId,
            @Param("since") Instant since);
}
