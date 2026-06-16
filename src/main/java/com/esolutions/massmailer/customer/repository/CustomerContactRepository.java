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
}
