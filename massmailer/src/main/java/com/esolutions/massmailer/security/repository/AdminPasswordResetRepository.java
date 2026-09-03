package com.esolutions.massmailer.security.repository;

import com.esolutions.massmailer.security.model.AdminPasswordReset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminPasswordResetRepository extends JpaRepository<AdminPasswordReset, UUID> {

    Optional<AdminPasswordReset> findByToken(String token);

    List<AdminPasswordReset> findByAdminUserIdAndUsedAtIsNull(UUID adminUserId);
}
