package com.esolutions.massmailer.organization.repository;

import com.esolutions.massmailer.organization.model.OrgUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgUserRepository extends JpaRepository<OrgUser, UUID> {

    Optional<OrgUser> findByOrganizationId(UUID organizationId);

    boolean existsByOrganizationId(UUID organizationId);
}
