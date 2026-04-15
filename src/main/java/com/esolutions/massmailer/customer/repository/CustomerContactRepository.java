package com.esolutions.massmailer.customer.repository;

import com.esolutions.massmailer.customer.model.CustomerContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerContactRepository extends JpaRepository<CustomerContact, UUID> {

    Optional<CustomerContact> findByOrganizationIdAndEmail(UUID organizationId, String email);

    List<CustomerContact> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    boolean existsByOrganizationIdAndEmail(UUID organizationId, String email);

    Optional<CustomerContact> findByOrganizationIdAndErpCustomerId(UUID organizationId, String erpCustomerId);
}
