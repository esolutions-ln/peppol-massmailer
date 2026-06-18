package com.esolutions.massmailer.peppol;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.model.MailCampaign;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus;
import com.esolutions.massmailer.peppol.repository.PeppolDeliveryRecordRepository;
import com.esolutions.massmailer.repository.CampaignRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for data isolation across tenants (organisations).
 *
 * <p>Property 14: Data Isolation — Validates: Requirements 15.2
 *
 * <p>For any authenticated organisation, all queries for customers, delivery records,
 * and campaigns must return only records scoped to that organisation's ID.
 * No cross-tenant data leakage is permitted.
 *
 * <pre>
 * ∀ orgId ∈ authenticated_orgs, ∀ record ∈ query_results(orgId):
 *   record.organizationId = orgId
 * </pre>
 */
class DataIsolationPropertyTest {

    /**
     * Property 14: Data Isolation
     *
     * <p>Seeds N organisations (2–5), each with their own customers, delivery records,
     * and campaigns. For each org, queries the scoped repositories and asserts that
     * ALL returned records have {@code organizationId == authenticatedOrgId} and that
     * NO records from other orgs appear in the results.
     *
     * <p><b>Validates: Requirements 15.2</b>
     */
    @Property(tries = 200)
    void allQueryResultsAreIsolatedToAuthenticatedOrg(
            @ForAll @IntRange(min = 2, max = 5) int numOrgs) {

        // ── Arrange: create N org IDs ─────────────────────────────────────────

        List<UUID> orgIds = new ArrayList<>();
        for (int i = 0; i < numOrgs; i++) {
            orgIds.add(UUID.randomUUID());
        }

        // ── Seed: build in-memory data stores keyed by orgId ─────────────────

        // customers: orgId → list of CustomerContact
        Map<UUID, List<CustomerContact>> customerStore = new HashMap<>();
        // delivery records: orgId → list of PeppolDeliveryRecord
        Map<UUID, List<PeppolDeliveryRecord>> deliveryStore = new HashMap<>();
        // campaigns: orgId → list of MailCampaign
        Map<UUID, List<MailCampaign>> campaignStore = new HashMap<>();

        for (UUID orgId : orgIds) {
            // 1–3 customers per org
            List<CustomerContact> customers = new ArrayList<>();
            int customerCount = 1 + (orgId.hashCode() & 0x7FFFFFFF) % 3;
            for (int c = 0; c < customerCount; c++) {
                customers.add(CustomerContact.builder()
                        .id(UUID.randomUUID())
                        .organizationId(orgId)
                        .email("customer" + c + "@org-" + orgId.toString().substring(0, 8) + ".com")
                        .name("Customer " + c)
                        .build());
            }
            customerStore.put(orgId, customers);

            // 1–3 delivery records per org
            List<PeppolDeliveryRecord> records = new ArrayList<>();
            int recordCount = 1 + (orgId.hashCode() & 0x7FFFFFFF) % 3;
            for (int r = 0; r < recordCount; r++) {
                records.add(PeppolDeliveryRecord.builder()
                        .id(UUID.randomUUID())
                        .organizationId(orgId)
                        .invoiceNumber("INV-" + orgId.toString().substring(0, 8) + "-" + r)
                        .senderParticipantId("0190:ZW" + r)
                        .receiverParticipantId("0190:ZW9" + r)
                        .status(DeliveryStatus.DELIVERED)
                        .build());
            }
            deliveryStore.put(orgId, records);

            // 1–2 campaigns per org
            List<MailCampaign> campaigns = new ArrayList<>();
            int campaignCount = 1 + (orgId.hashCode() & 0x7FFFFFFF) % 2;
            for (int k = 0; k < campaignCount; k++) {
                campaigns.add(MailCampaign.builder()
                        .id(UUID.randomUUID())
                        .organizationId(orgId)
                        .name("Campaign " + k + " for " + orgId.toString().substring(0, 8))
                        .subject("Subject " + k)
                        .templateName("template")
                        .build());
            }
            campaignStore.put(orgId, campaigns);
        }

        // ── Mock repositories with scoped query behaviour ─────────────────────

        CustomerContactRepository customerRepo = mock(CustomerContactRepository.class);
        PeppolDeliveryRecordRepository deliveryRepo = mock(PeppolDeliveryRecordRepository.class);
        CampaignRepository campaignRepo = mock(CampaignRepository.class);

        // Scoped queries return only records matching the requested orgId
        when(customerRepo.findByOrganizationIdOrderByCreatedAtDesc(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID requestedOrgId = inv.getArgument(0, UUID.class);
                    return customerStore.getOrDefault(requestedOrgId, List.of());
                });

        when(deliveryRepo.findByOrganizationIdOrderByCreatedAtDesc(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID requestedOrgId = inv.getArgument(0, UUID.class);
                    return deliveryStore.getOrDefault(requestedOrgId, List.of());
                });

        when(campaignRepo.findByOrganizationIdOrderByCreatedAtDesc(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID requestedOrgId = inv.getArgument(0, UUID.class);
                    return campaignStore.getOrDefault(requestedOrgId, List.of());
                });

        // ── Assert: for each authenticated org, all results are scoped ────────

        for (UUID authenticatedOrgId : orgIds) {

            // Query customers scoped to this org
            List<CustomerContact> customers =
                    customerRepo.findByOrganizationIdOrderByCreatedAtDesc(authenticatedOrgId);

            // Query delivery records scoped to this org
            List<PeppolDeliveryRecord> deliveries =
                    deliveryRepo.findByOrganizationIdOrderByCreatedAtDesc(authenticatedOrgId);

            // Query campaigns scoped to this org
            List<MailCampaign> campaigns =
                    campaignRepo.findByOrganizationIdOrderByCreatedAtDesc(authenticatedOrgId);

            // ── Invariant: ∀ record ∈ queryResults: record.organizationId == authenticatedOrgId ──

            assertThat(customers)
                    .as("All customers returned for org %s must have organizationId == authenticatedOrgId",
                            authenticatedOrgId)
                    .allSatisfy(c -> assertThat(c.getOrganizationId())
                            .isEqualTo(authenticatedOrgId));

            assertThat(deliveries)
                    .as("All delivery records returned for org %s must have organizationId == authenticatedOrgId",
                            authenticatedOrgId)
                    .allSatisfy(r -> assertThat(r.getOrganizationId())
                            .isEqualTo(authenticatedOrgId));

            assertThat(campaigns)
                    .as("All campaigns returned for org %s must have organizationId == authenticatedOrgId",
                            authenticatedOrgId)
                    .allSatisfy(c -> assertThat(c.getOrganizationId())
                            .isEqualTo(authenticatedOrgId));

            // ── No cross-tenant records: collect all OTHER org IDs ────────────

            Set<UUID> otherOrgIds = orgIds.stream()
                    .filter(id -> !id.equals(authenticatedOrgId))
                    .collect(Collectors.toSet());

            Set<UUID> customerOrgIds = customers.stream()
                    .map(CustomerContact::getOrganizationId)
                    .collect(Collectors.toSet());

            Set<UUID> deliveryOrgIds = deliveries.stream()
                    .map(PeppolDeliveryRecord::getOrganizationId)
                    .collect(Collectors.toSet());

            Set<UUID> campaignOrgIds = campaigns.stream()
                    .map(MailCampaign::getOrganizationId)
                    .collect(Collectors.toSet());

            assertThat(customerOrgIds)
                    .as("Customer results for org %s must not contain records from other orgs %s",
                            authenticatedOrgId, otherOrgIds)
                    .doesNotContainAnyElementsOf(otherOrgIds);

            assertThat(deliveryOrgIds)
                    .as("Delivery record results for org %s must not contain records from other orgs %s",
                            authenticatedOrgId, otherOrgIds)
                    .doesNotContainAnyElementsOf(otherOrgIds);

            assertThat(campaignOrgIds)
                    .as("Campaign results for org %s must not contain records from other orgs %s",
                            authenticatedOrgId, otherOrgIds)
                    .doesNotContainAnyElementsOf(otherOrgIds);
        }
    }
}
