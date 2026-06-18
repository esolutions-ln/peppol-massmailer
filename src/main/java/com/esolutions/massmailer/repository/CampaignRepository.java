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

    Optional<MailCampaign> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<MailCampaign> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<MailCampaign> findByOrganizationIdAndStatusOrderByCreatedAtDesc(UUID organizationId, CampaignStatus status);

    List<MailCampaign> findAllByOrderByCreatedAtDesc();
}
