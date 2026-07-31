package com.esolutions.massmailer.organization.repository;

import com.esolutions.massmailer.organization.model.OrgSessionToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgSessionTokenRepository extends JpaRepository<OrgSessionToken, UUID> {

    Optional<OrgSessionToken> findByTokenAndExpiresAtAfter(String token, Instant now);

    @Modifying
    @Query("DELETE FROM OrgSessionToken t WHERE t.token = :token")
    void deleteByToken(String token);

    @Modifying
    @Query("DELETE FROM OrgSessionToken t WHERE t.orgMember.id = :memberId")
    void deleteByOrgMemberId(UUID memberId);
}
