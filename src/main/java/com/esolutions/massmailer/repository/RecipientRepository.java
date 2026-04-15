package com.esolutions.massmailer.repository;

import com.esolutions.massmailer.model.MailRecipient;
import com.esolutions.massmailer.model.MailRecipient.RecipientStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RecipientRepository extends JpaRepository<MailRecipient, UUID> {

    List<MailRecipient> findByCampaignIdAndDeliveryStatus(UUID campaignId, RecipientStatus status);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.id = :campaignId AND r.deliveryStatus = 'FAILED' AND r.retryCount < :maxRetries")
    List<MailRecipient> findRetryable(UUID campaignId, int maxRetries);

    long countByCampaignIdAndDeliveryStatus(UUID campaignId, RecipientStatus status);

    // ── Org-scoped invoice queries for the dashboard ──
    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.organizationId = :orgId ORDER BY r.sentAt DESC NULLS LAST")
    List<MailRecipient> findByOrganizationId(UUID orgId);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.organizationId = :orgId AND r.deliveryStatus = :status ORDER BY r.sentAt DESC NULLS LAST")
    List<MailRecipient> findByOrganizationIdAndStatus(UUID orgId, RecipientStatus status);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.organizationId = :orgId AND r.invoiceNumber = :invoiceNumber")
    List<MailRecipient> findByOrganizationIdAndInvoiceNumber(UUID orgId, String invoiceNumber);

    @Query("SELECT r FROM MailRecipient r WHERE r.campaign.id = :campaignId ORDER BY r.email ASC")
    List<MailRecipient> findByCampaignId(UUID campaignId);
}
