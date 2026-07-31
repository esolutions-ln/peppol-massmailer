package com.esolutions.massmailer.peppol.repository;

import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.AccessPoint.AccessPointRole;
import com.esolutions.massmailer.peppol.model.AccessPoint.AccessPointStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccessPointRepository extends JpaRepository<AccessPoint, UUID> {

    Optional<AccessPoint> findByParticipantId(String participantId);

    List<AccessPoint> findByOrganizationIdAndStatus(UUID organizationId, AccessPointStatus status);

    List<AccessPoint> findByRoleAndStatus(AccessPointRole role, AccessPointStatus status);

    boolean existsByParticipantId(String participantId);
}
