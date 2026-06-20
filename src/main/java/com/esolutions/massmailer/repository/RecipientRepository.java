package com.esolutions.massmailer.repository;

import com.esolutions.massmailer.model.MailRecipient;
import com.esolutions.massmailer.model.MailRecipient.RecipientStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

@Repository
public interface RecipientRepository extends JpaRepository<MailRecipient, UUID> {

    List<MailRecipient> findByCampaignIdAndDeliveryStatus(UUID campaignId, RecipientStatus status);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.id = :campaignId AND r.deliveryStatus = 'FAILED' AND r.retryCount < :maxRetries")
    List<MailRecipient> findRetryable(UUID campaignId, int maxRetries);

    long countByCampaignIdAndDeliveryStatus(UUID campaignId, RecipientStatus status);

    // ── Org-scoped invoice queries for the dashboard ──
    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.organizationId = :orgId ORDER BY r.sentAt DESC NULLS LAST")
    List<MailRecipient> findByOrganizationId(UUID orgId);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.organizationId = :orgId ORDER BY r.sentAt DESC NULLS LAST")
    Page<MailRecipient> findByOrganizationId(UUID orgId, Pageable pageable);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.organizationId = :orgId AND r.deliveryStatus = :status ORDER BY r.sentAt DESC NULLS LAST")
    List<MailRecipient> findByOrganizationIdAndStatus(UUID orgId, RecipientStatus status);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.organizationId = :orgId AND r.deliveryStatus = :status ORDER BY r.sentAt DESC NULLS LAST")
    Page<MailRecipient> findByOrganizationIdAndStatus(UUID orgId, RecipientStatus status, Pageable pageable);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.organizationId = :orgId AND r.invoiceNumber = :invoiceNumber")
    List<MailRecipient> findByOrganizationIdAndInvoiceNumber(UUID orgId, String invoiceNumber);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.id = :campaignId ORDER BY r.email ASC")
    List<MailRecipient> findByCampaignId(UUID campaignId);

    // ── Per-customer invoice counts ──
    @Query("SELECT r.email, r.deliveryStatus, COUNT(r) FROM MailRecipient r WHERE r.email IN :emails GROUP BY r.email, r.deliveryStatus")
    List<Object[]> countByEmailsGroupedByStatus(@Param("emails") List<String> emails);

    @Query("SELECT r.customerId, r.deliveryStatus, COUNT(r) FROM MailRecipient r WHERE r.customerId IN :customerIds GROUP BY r.customerId, r.deliveryStatus")
    List<Object[]> countByCustomerIdsGroupedByStatus(@Param("customerIds") List<UUID> customerIds);

    List<MailRecipient> findByCustomerId(UUID customerId);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.organizationId = :orgId AND r.customerId = :customerId ORDER BY r.sentAt DESC NULLS LAST")
    List<MailRecipient> findByOrganizationIdAndCustomerId(@Param("orgId") UUID orgId, @Param("customerId") UUID customerId);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.organizationId = :orgId AND r.customerId = :customerId ORDER BY r.sentAt DESC NULLS LAST")
    Page<MailRecipient> findByOrganizationIdAndCustomerId(@Param("orgId") UUID orgId, @Param("customerId") UUID customerId, Pageable pageable);
}
