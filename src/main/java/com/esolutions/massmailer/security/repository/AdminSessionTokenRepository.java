package com.esolutions.massmailer.security.repository;

import com.esolutions.massmailer.security.model.AdminSessionToken;
import com.esolutions.massmailer.security.model.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AdminSessionTokenRepository extends JpaRepository<AdminSessionToken, UUID> {

    @org.springframework.data.jpa.repository.Query(
            "SELECT t FROM AdminSessionToken t JOIN FETCH t.adminUser WHERE t.token = :token AND t.expiresAt > :now")
    Optional<AdminSessionToken> findByTokenAndExpiresAtAfter(
            @org.springframework.data.repository.query.Param("token") String token,
            @org.springframework.data.repository.query.Param("now") Instant now);

    @Modifying
    @Transactional
    void deleteByToken(String token);

    @Modifying
    @Transactional
    void deleteByAdminUser(AdminUser user);
}
