package com.esolutions.massmailer.billing.repository;

import com.esolutions.massmailer.billing.model.RateProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RateProfileRepository extends JpaRepository<RateProfile, UUID> {
    Optional<RateProfile> findByName(String name);
    List<RateProfile> findByActiveTrue();
}
