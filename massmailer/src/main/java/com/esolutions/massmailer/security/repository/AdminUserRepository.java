package com.esolutions.massmailer.security.repository;

import com.esolutions.massmailer.security.model.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {
    Optional<AdminUser> findByUsername(String username);
    long countByActiveTrue();
    boolean existsByUsername(String username);
}
