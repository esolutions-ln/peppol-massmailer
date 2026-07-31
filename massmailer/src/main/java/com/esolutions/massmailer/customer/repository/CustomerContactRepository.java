package com.esolutions.massmailer.customer.repository;

import com.esolutions.massmailer.customer.model.CustomerContact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerContactRepository extends JpaRepository<CustomerContact, UUID> {

    Optional<CustomerContact> findByOrganizationIdAndEmail(UUID organizationId, String email);

    Page<CustomerContact> findByOrganizationId(UUID organizationId, Pageable pageable);

    @Query("""
            SELECT c FROM CustomerContact c WHERE c.organizationId = :orgId AND
            (LOWER(COALESCE(c.email, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
             LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
             LOWER(COALESCE(c.companyName, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
             LOWER(COALESCE(c.erpCustomerId, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
             LOWER(COALESCE(c.peppolParticipantId, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<CustomerContact> searchByOrganizationId(UUID orgId, String q, Pageable pageable);

    boolean existsByOrganizationIdAndEmail(UUID organizationId, String email);

    Optional<CustomerContact> findByOrganizationIdAndErpCustomerId(UUID organizationId, String erpCustomerId);

    // VAT / TIN / BPN are NOT unique per org — a single buyer entity may run multiple
    // accounts across locations or branches, all sharing the same fiscal identifiers.
    // The "find first" variants return the most recent registration and avoid the
    // NonUniqueResultException that a plain findBy... would throw on duplicates.

    Optional<CustomerContact> findFirstByOrganizationIdAndVatNumberOrderByCreatedAtDesc(
            UUID organizationId, String vatNumber);

    Optional<CustomerContact> findFirstByOrganizationIdAndTinNumberOrderByCreatedAtDesc(
            UUID organizationId, String tinNumber);

    Optional<CustomerContact> findFirstByOrganizationIdAndBpnOrderByCreatedAtDesc(
            UUID organizationId, String bpn);

    List<CustomerContact> findByOrganizationIdAndVatNumber(UUID organizationId, String vatNumber);

    List<CustomerContact> findByOrganizationIdAndTinNumber(UUID organizationId, String tinNumber);

    List<CustomerContact> findByOrganizationIdAndBpn(UUID organizationId, String bpn);

    // Cross-org lookups used when sender resolution has only the buyer's identifier
    // (no authenticated org). Returns the most recent match across all orgs.
    Optional<CustomerContact> findFirstByErpCustomerIdOrderByCreatedAtDesc(String erpCustomerId);

    Optional<CustomerContact> findFirstByTinNumberOrderByCreatedAtDesc(String tinNumber);
}
