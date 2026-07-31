package com.esolutions.massmailer.repository;

import com.esolutions.massmailer.model.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, UUID> {

    List<EmailTemplate> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<EmailTemplate> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<EmailTemplate> findFirstByOrganizationIdAndIsDefaultTrue(UUID organizationId);
}
