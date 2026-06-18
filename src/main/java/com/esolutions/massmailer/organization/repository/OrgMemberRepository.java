package com.esolutions.massmailer.organization.repository;

import com.esolutions.massmailer.organization.model.OrgMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgMemberRepository extends JpaRepository<OrgMember, UUID> {

    Optional<OrgMember> findByOrganizationIdAndEmail(UUID organizationId, String email);

    Optional<OrgMember> findByEmail(String email);

    List<OrgMember> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    boolean existsByOrganizationIdAndEmail(UUID organizationId, String email);
}
