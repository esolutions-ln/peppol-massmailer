package com.esolutions.massmailer.repository;

import com.esolutions.massmailer.model.CampaignStatus;
import com.esolutions.massmailer.model.MailCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignRepository extends JpaRepository<MailCampaign, UUID> {

    List<MailCampaign> findByStatusOrderByCreatedAtDesc(CampaignStatus status);

    List<MailCampaign> findAllByOrderByCreatedAtDesc();

    // ── Org-scoped queries for the invoice dashboard ──
    List<MailCampaign> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<MailCampaign> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId, CampaignStatus status);

    Optional<MailCampaign> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
