package com.esolutions.massmailer.customer;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.customer.service.CustomerContactService;
import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.ErpSource;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.FiscalMetadata;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.PdfSource;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for CustomerContactService.
 *
 * P8: Customer Registry Completeness — validates Requirements 9.1, 9.3, 9.4
 * P9: Unsubscribe Enforcement       — validates Requirements 9.5
 *
 * Note: jqwik does not process JUnit 5 extensions (@ExtendWith), so mocks are
 * created manually via Mockito.mock() and an in-memory map simulates the
 * repository for P8.
 */
class CustomerContactServicePropertyTest {

    // ── Property P8: Customer Registry Completeness ──────────────────────────
    // Validates: Requirements 9.1, 9.3, 9.4

    /**
     * P8a — After calling upsertAll() twice with the same list of invoices,
     * exactly one CustomerContact exists per (organizationId, email) pair,
     * and all stored emails are lowercase.
     *
     * **Validates: Requirements 9.1, 9.3, 9.4**
     */
    @Property
    void upsertAllIsIdempotentAndProducesOneContactPerPair(
            @ForAll("distinctEmailInvoiceLists") List<CanonicalInvoice> invoices
    ) {
        UUID orgId = UUID.randomUUID();
        InMemoryRepo repo = new InMemoryRepo();
        CustomerContactService service = new CustomerContactService(repo);

        // Call upsertAll twice — idempotency check
        List<CustomerContact> firstCall = service.upsertAll(orgId, invoices);
        List<CustomerContact> secondCall = service.upsertAll(orgId, invoices);

        // Collect all contacts stored in the in-memory repo
        Map<String, CustomerContact> stored = repo.getAll(orgId);

        // Exactly one contact per (orgId, email) pair
        assertThat(stored).as("Exactly one contact per email after two upsertAll calls")
                .hasSize(invoices.size());

        // All emails are lowercase
        for (String storedEmail : stored.keySet()) {
            assertThat(storedEmail)
                    .as("Stored email must be lowercase: %s", storedEmail)
                    .isEqualTo(storedEmail.toLowerCase(Locale.ROOT));
        }

        // Every invoice email is represented
        for (CanonicalInvoice inv : invoices) {
            String expectedEmail = inv.recipientEmail().trim().toLowerCase(Locale.ROOT);
            assertThat(stored).as("Contact must exist for email: %s", expectedEmail)
                    .containsKey(expectedEmail);
        }
    }

    /**
     * P8b — upsertAll() with mixed-case emails normalises to lowercase before storage.
     *
     * **Validates: Requirements 9.3**
     */
    @Property
    void emailsAreNormalisedToLowercase(@ForAll("mixedCaseEmails") String rawEmail) {
        UUID orgId = UUID.randomUUID();
        InMemoryRepo repo = new InMemoryRepo();
        CustomerContactService service = new CustomerContactService(repo);

        CanonicalInvoice invoice = buildInvoice(rawEmail, "INV-001");
        service.upsertAll(orgId, List.of(invoice));

        String expectedEmail = rawEmail.trim().toLowerCase(Locale.ROOT);
        Map<String, CustomerContact> stored = repo.getAll(orgId);

        assertThat(stored).as("Contact must be stored under lowercase email")
                .containsKey(expectedEmail);
        assertThat(stored.get(expectedEmail).getEmail())
                .as("CustomerContact.email must be lowercase")
                .isEqualTo(expectedEmail);
    }

    // ── Property P9: Unsubscribe Enforcement ─────────────────────────────────
    // Validates: Requirements 9.5

    /**
     * P9 — When a CustomerContact with unsubscribed=true exists for a given email,
     * upsertAll() must throw IllegalArgumentException before any DB writes occur.
     *
     * **Validates: Requirements 9.5**
     */
    @Property
    void upsertAllThrowsForUnsubscribedRecipient(
            @ForAll("emails") String email,
            @ForAll("invoiceNumbers") String invoiceNumber
    ) {
        UUID orgId = UUID.randomUUID();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        // Pre-create an unsubscribed contact in the mock repository
        CustomerContact unsubscribedContact = CustomerContact.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .email(normalizedEmail)
                .unsubscribed(true)
                .build();

        CustomerContactRepository mockRepo = mock(CustomerContactRepository.class);
        when(mockRepo.findByOrganizationIdAndEmail(eq(orgId), eq(normalizedEmail)))
                .thenReturn(Optional.of(unsubscribedContact));

        CustomerContactService service = new CustomerContactService(mockRepo);
        CanonicalInvoice invoice = buildInvoice(normalizedEmail, invoiceNumber);

        // upsertAll must throw before any save() is called
        assertThatThrownBy(() -> service.upsertAll(orgId, List.of(invoice)))
                .as("upsertAll() must throw IllegalArgumentException for unsubscribed recipient")
                .isInstanceOf(IllegalArgumentException.class);

        // No writes should have occurred
        verify(mockRepo, never()).save(any());
        verify(mockRepo, never()).saveAll(any());
    }

    // ── Arbitraries ──────────────────────────────────────────────────────────

    /**
     * Generates a list of CanonicalInvoices with distinct lowercase emails.
     * List size is between 1 and 10.
     */
    @Provide
    Arbitrary<List<CanonicalInvoice>> distinctEmailInvoiceLists() {
        return emails()
                .list()
                .ofMinSize(1)
                .ofMaxSize(10)
                .uniqueElements()
                .map(emailList -> {
                    List<CanonicalInvoice> invoices = new ArrayList<>();
                    for (int i = 0; i < emailList.size(); i++) {
                        invoices.add(buildInvoice(emailList.get(i), "INV-" + (i + 1)));
                    }
                    return invoices;
                });
    }

    /** Generates simple lowercase email-like strings. */
    @Provide
    Arbitrary<String> emails() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(8)
                .map(local -> local + "@example.com");
    }

    /** Generates mixed-case email strings (some uppercase letters). */
    @Provide
    Arbitrary<String> mixedCaseEmails() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
                .ofMinLength(3)
                .ofMaxLength(8)
                .map(local -> local + "@Example.COM");
    }

    /** Generates realistic invoice number strings. */
    @Provide
    Arbitrary<String> invoiceNumbers() {
        return Arbitraries.strings()
                .withCharRange('A', 'Z')
                .ofMinLength(3)
                .ofMaxLength(8)
                .map(s -> "INV-" + s);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static CanonicalInvoice buildInvoice(String email, String invoiceNumber) {
        return new CanonicalInvoice(
                ErpSource.GENERIC_API,
                "tenant-1",
                invoiceNumber,
                email,
                "Test Recipient",
                "Test Company",
                invoiceNumber,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(15),
                BigDecimal.valueOf(115),
                "USD",
                FiscalMetadata.EMPTY,
                new PdfSource(null, null, null, "invoice.pdf"),
                Map.of()
        );
    }

    /**
     * In-memory repository that simulates the find-or-create behaviour of
     * CustomerContactRepository for P8 without requiring a Spring context.
     *
     * Keyed by (organizationId, email) — mirrors the DB unique constraint.
     */
    private static class InMemoryRepo implements CustomerContactRepository {

        // Key: orgId + "|" + email
        private final Map<String, CustomerContact> store = new ConcurrentHashMap<>();

        @Override
        public Optional<CustomerContact> findByOrganizationIdAndEmail(UUID organizationId, String email) {
            return Optional.ofNullable(store.get(key(organizationId, email)));
        }

        @Override
        public CustomerContact save(CustomerContact contact) {
            if (contact.getId() == null) {
                // Simulate @GeneratedValue
                try {
                    var field = CustomerContact.class.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(contact, UUID.randomUUID());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            store.put(key(contact.getOrganizationId(), contact.getEmail()), contact);
            return contact;
        }

        /** Returns all contacts for a given organization, keyed by email. */
        public Map<String, CustomerContact> getAll(UUID organizationId) {
            Map<String, CustomerContact> result = new HashMap<>();
            String prefix = organizationId + "|";
            for (Map.Entry<String, CustomerContact> entry : store.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    result.put(entry.getValue().getEmail(), entry.getValue());
                }
            }
            return result;
        }

        private static String key(UUID orgId, String email) {
            return orgId + "|" + email;
        }

        // ── Unused JpaRepository methods — minimal stubs ──────────────────

        @Override public <S extends CustomerContact> List<S> saveAll(Iterable<S> entities) {
            List<S> result = new ArrayList<>();
            for (S e : entities) { result.add((S) save(e)); }
            return result;
        }
        @Override public Optional<CustomerContact> findById(UUID id) { return Optional.empty(); }
        @Override public boolean existsById(UUID id) { return false; }
        @Override public List<CustomerContact> findAll() { return new ArrayList<>(store.values()); }
        @Override public List<CustomerContact> findAllById(Iterable<UUID> ids) { return List.of(); }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(UUID id) {}
        @Override public void delete(CustomerContact entity) {}
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public void deleteAll(Iterable<? extends CustomerContact> entities) {}
        @Override public void deleteAll() { store.clear(); }
        @Override public void flush() {}
        @Override public <S extends CustomerContact> S saveAndFlush(S entity) { return (S) save(entity); }
        @Override public <S extends CustomerContact> List<S> saveAllAndFlush(Iterable<S> entities) { return saveAll(entities); }
        @Override public void deleteAllInBatch(Iterable<CustomerContact> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public void deleteAllInBatch() { store.clear(); }
        @Override public CustomerContact getOne(UUID id) { return null; }
        @Override public CustomerContact getById(UUID id) { return null; }
        @Override public CustomerContact getReferenceById(UUID id) { return null; }
        @Override public <S extends CustomerContact> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends CustomerContact> List<S> findAll(org.springframework.data.domain.Example<S> example) { return List.of(); }
        @Override public <S extends CustomerContact> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends CustomerContact> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends CustomerContact> long count(org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends CustomerContact> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends CustomerContact, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public List<CustomerContact> findAll(org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public org.springframework.data.domain.Page<CustomerContact> findAll(org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public List<CustomerContact> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId) { return List.of(); }
        @Override public boolean existsByOrganizationIdAndEmail(UUID organizationId, String email) { return store.containsKey(key(organizationId, email)); }
        @Override public Optional<CustomerContact> findByOrganizationIdAndErpCustomerId(UUID organizationId, String erpCustomerId) { return Optional.empty(); }
    }
}
