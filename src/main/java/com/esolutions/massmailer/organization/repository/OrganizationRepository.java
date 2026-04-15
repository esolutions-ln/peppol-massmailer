package com.esolutions.massmailer.organization.repository;

import com.esolutions.massmailer.organization.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    Optional<Organization> findByApiKey(String apiKey);

    boolean existsBySlug(String slug);
}
