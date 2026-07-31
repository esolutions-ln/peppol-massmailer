package com.esolutions.massmailer.customer.repository;

import com.esolutions.massmailer.customer.model.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByOrganizationIdAndErpCustomerId(UUID organizationId, String erpCustomerId);

    Optional<Customer> findFirstByErpCustomerIdOrderByCreatedAtDesc(String erpCustomerId);

    Optional<Customer> findFirstByOrganizationIdAndBpnOrderByCreatedAtDesc(UUID organizationId, String bpn);

    Optional<Customer> findFirstByOrganizationIdAndVatNumberOrderByCreatedAtDesc(UUID organizationId, String vatNumber);

    Optional<Customer> findFirstByOrganizationIdAndTinNumberOrderByCreatedAtDesc(UUID organizationId, String tinNumber);

    List<Customer> findByOrganizationIdAndBpn(UUID organizationId, String bpn);

    List<Customer> findByOrganizationIdAndVatNumber(UUID organizationId, String vatNumber);

    List<Customer> findByOrganizationIdAndTinNumber(UUID organizationId, String tinNumber);

    Page<Customer> findByOrganizationId(UUID organizationId, Pageable pageable);

    @Query("""
            SELECT c FROM Customer c WHERE c.organizationId = :orgId AND
            (LOWER(COALESCE(c.companyName, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
             LOWER(COALESCE(c.tradingName, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
             LOWER(COALESCE(c.erpCustomerId, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
             LOWER(COALESCE(c.peppolParticipantId, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
             LOWER(COALESCE(c.vatNumber, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
             LOWER(COALESCE(c.tinNumber, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
             LOWER(COALESCE(c.bpn, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Customer> searchByOrganizationId(UUID orgId, String q, Pageable pageable);

    boolean existsByErpCustomerId(String erpCustomerId);

    Optional<Customer> findFirstByTinNumberOrderByCreatedAtDesc(String tinNumber);
}
