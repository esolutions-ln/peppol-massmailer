package com.esolutions.massmailer.customer.service;

import com.esolutions.massmailer.customer.model.Customer;
import com.esolutions.massmailer.customer.repository.CustomerRepository;
import com.esolutions.massmailer.model.DeliveryMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository repo;

    public CustomerService(CustomerRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public UpsertResult upsertByErpCustomerId(UUID organizationId, String erpCustomerId,
                                              String companyName, String tradingName,
                                              String erpSource,
                                              DeliveryMode deliveryMode,
                                              String vatNumber, String tinNumber, String bpn,
                                              String peppolParticipantId,
                                              String addressLine1, String addressLine2,
                                              String city, String country) {
        if (erpCustomerId == null || erpCustomerId.isBlank()) {
            throw new IllegalArgumentException("erpCustomerId is required");
        }
        String customerId = erpCustomerId.trim();
        String resolvedParticipantId = peppolParticipantId;
        if (resolvedParticipantId == null || resolvedParticipantId.isBlank()) {
            if (vatNumber != null && !vatNumber.isBlank()) {
                resolvedParticipantId = "0190:ZW" + vatNumber.trim();
            } else if (tinNumber != null && !tinNumber.isBlank()) {
                resolvedParticipantId = "0190:ZW" + tinNumber.trim();
            }
        }
        final String finalParticipantId = resolvedParticipantId;

        var existing = repo.findByOrganizationIdAndErpCustomerId(organizationId, customerId);
        if (existing.isPresent()) {
            var c = existing.get();
            if (companyName != null) c.setCompanyName(companyName.trim());
            if (tradingName != null) c.setTradingName(tradingName.trim());
            if (erpSource != null) c.setErpSource(erpSource.trim());
            if (deliveryMode != null) c.setDeliveryMode(deliveryMode);
            if (vatNumber != null) c.setVatNumber(vatNumber.trim());
            if (tinNumber != null) c.setTinNumber(tinNumber.trim());
            if (bpn != null) c.setBpn(bpn.trim());
            if (finalParticipantId != null) c.setPeppolParticipantId(finalParticipantId);
            if (addressLine1 != null) c.setAddressLine1(addressLine1.trim());
            if (addressLine2 != null) c.setAddressLine2(addressLine2.trim());
            if (city != null) c.setCity(city.trim());
            if (country != null) c.setCountry(country.trim());
            c.setUpdatedAt(java.time.Instant.now());
            return new UpsertResult(repo.save(c), false);
        }

        var customer = Customer.builder()
                .organizationId(organizationId)
                .erpCustomerId(customerId)
                .companyName(companyName != null ? companyName.trim() : null)
                .tradingName(tradingName != null ? tradingName.trim() : null)
                .erpSource(erpSource)
                .deliveryMode(deliveryMode)
                .vatNumber(vatNumber != null ? vatNumber.trim() : null)
                .tinNumber(tinNumber != null ? tinNumber.trim() : null)
                .bpn(bpn != null ? bpn.trim() : null)
                .peppolParticipantId(finalParticipantId)
                .addressLine1(addressLine1 != null ? addressLine1.trim() : null)
                .addressLine2(addressLine2 != null ? addressLine2.trim() : null)
                .city(city != null ? city.trim() : null)
                .country(country != null ? country.trim() : null)
                .build();
        log.info("Registered new customer: erpCustomerId={} [org={}]", customerId, organizationId);
        return new UpsertResult(repo.save(customer), true);
    }

    public record UpsertResult(Customer customer, boolean created) {}

    @Transactional(readOnly = true)
    public Page<Customer> listByOrg(UUID organizationId, String search, Pageable pageable) {
        if (search == null || search.isBlank()) {
            return repo.findByOrganizationId(organizationId, pageable);
        }
        return repo.searchByOrganizationId(organizationId, search.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public Customer getByErpCustomerId(UUID organizationId, String erpCustomerId) {
        return repo.findByOrganizationIdAndErpCustomerId(organizationId, erpCustomerId)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Customer getByTaxId(UUID organizationId, String bpn,
                               String vatNumber, String tinNumber) {
        if (bpn != null && !bpn.isBlank()) {
            var byBpn = repo.findFirstByOrganizationIdAndBpnOrderByCreatedAtDesc(
                    organizationId, bpn.trim());
            if (byBpn.isPresent()) return byBpn.get();
        }
        if (vatNumber != null && !vatNumber.isBlank()) {
            var byVat = repo.findFirstByOrganizationIdAndVatNumberOrderByCreatedAtDesc(
                    organizationId, vatNumber.trim());
            if (byVat.isPresent()) return byVat.get();
        }
        if (tinNumber != null && !tinNumber.isBlank()) {
            var byTin = repo.findFirstByOrganizationIdAndTinNumberOrderByCreatedAtDesc(
                    organizationId, tinNumber.trim());
            if (byTin.isPresent()) return byTin.get();
        }
        return null;
    }

    @Transactional(readOnly = true)
    public List<Customer> listByTaxId(UUID organizationId, String bpn,
                                      String vatNumber, String tinNumber) {
        var seen = new LinkedHashMap<UUID, Customer>();
        if (bpn != null && !bpn.isBlank()) {
            for (var c : repo.findByOrganizationIdAndBpn(organizationId, bpn.trim())) {
                seen.putIfAbsent(c.getId(), c);
            }
        }
        if (vatNumber != null && !vatNumber.isBlank()) {
            for (var c : repo.findByOrganizationIdAndVatNumber(organizationId, vatNumber.trim())) {
                seen.putIfAbsent(c.getId(), c);
            }
        }
        if (tinNumber != null && !tinNumber.isBlank()) {
            for (var c : repo.findByOrganizationIdAndTinNumber(organizationId, tinNumber.trim())) {
                seen.putIfAbsent(c.getId(), c);
            }
        }
        return List.copyOf(seen.values());
    }

    @Transactional
    public void recordDelivery(UUID organizationId, String erpCustomerId, boolean success) {
        repo.findByOrganizationIdAndErpCustomerId(organizationId, erpCustomerId)
                .ifPresent(customer -> {
                    customer.recordDelivery(success);
                    repo.save(customer);
                });
    }

    @Transactional
    public Customer updateById(UUID organizationId, UUID id,
                               String companyName, String tradingName,
                               String erpSource, String erpCustomerId,
                               DeliveryMode deliveryMode,
                               String vatNumber, String tinNumber, String bpn,
                               String peppolParticipantId,
                               String addressLine1, String addressLine2,
                               String city, String country,
                               Boolean unsubscribed) {
        var existing = repo.findById(id)
                .filter(c -> c.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Customer " + id + " not found in organization " + organizationId));

        if (companyName != null) existing.setCompanyName(companyName.isBlank() ? null : companyName.trim());
        if (tradingName != null) existing.setTradingName(tradingName.isBlank() ? null : tradingName.trim());
        if (erpSource != null) existing.setErpSource(erpSource.isBlank() ? null : erpSource.trim());
        if (erpCustomerId != null) existing.setErpCustomerId(erpCustomerId.isBlank() ? null : erpCustomerId.trim());
        if (deliveryMode != null) existing.setDeliveryMode(deliveryMode);
        if (vatNumber != null) existing.setVatNumber(vatNumber.isBlank() ? null : vatNumber.trim());
        if (tinNumber != null) existing.setTinNumber(tinNumber.isBlank() ? null : tinNumber.trim());
        if (bpn != null) existing.setBpn(bpn.isBlank() ? null : bpn.trim());
        if (addressLine1 != null) existing.setAddressLine1(addressLine1.isBlank() ? null : addressLine1.trim());
        if (addressLine2 != null) existing.setAddressLine2(addressLine2.isBlank() ? null : addressLine2.trim());
        if (city != null) existing.setCity(city.isBlank() ? null : city.trim());
        if (country != null) existing.setCountry(country.isBlank() ? null : country.trim());
        if (unsubscribed != null) existing.setUnsubscribed(unsubscribed);

        if (peppolParticipantId != null && !peppolParticipantId.isBlank()) {
            existing.setPeppolParticipantId(peppolParticipantId.trim());
        } else if (vatNumber != null || tinNumber != null) {
            String vat = existing.getVatNumber();
            String tin = existing.getTinNumber();
            if (vat != null && !vat.isBlank()) {
                existing.setPeppolParticipantId("0190:ZW" + vat);
            } else if (tin != null && !tin.isBlank()) {
                existing.setPeppolParticipantId("0190:ZW" + tin);
            }
        }

        existing.setUpdatedAt(java.time.Instant.now());
        log.info("Updated customer {} [org={}]", id, organizationId);
        return repo.save(existing);
    }
}
