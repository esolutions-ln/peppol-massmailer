package com.esolutions.massmailer.organization.repository;

import com.esolutions.massmailer.organization.model.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    Optional<Organization> findByApiKey(String apiKey);

    Optional<Organization> findByPreviousApiKey(String previousApiKey);

    boolean existsBySlug(String slug);

    @Query("""
            SELECT o FROM Organization o
            WHERE (:q IS NULL OR :q = ''
                   OR LOWER(o.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(o.slug) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(o.senderEmail) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Organization> search(@Param("q") String q, Pageable pageable);
}
