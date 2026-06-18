package com.esolutions.massmailer.customer.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Migrates data from the legacy {@code customer_contacts} table into the new
 * {@code customers} and {@code contacts} tables.
 *
 * <p>Each row in {@code customer_contacts} becomes:
 * <ol>
 *   <li>A row in {@code customers} (company-level), deduplicated by
 *       {@code erp_customer_id} — the first row for each ID wins.</li>
 *   <li>A row in {@code contacts} (person-level), one per email, linked
 *       to the customer by {@code customer_id}.</li>
 * </ol>
 *
 * <p>Idempotent — safe to re-run. Uses {@code INSERT WHERE NOT EXISTS}
 * semantics to avoid duplicates.
 */
@Component
public class CustomerContactSplitMigration {

    private static final Logger log = LoggerFactory.getLogger(CustomerContactSplitMigration.class);

    private final JdbcTemplate jdbc;

    public CustomerContactSplitMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called explicitly from {@link CustomerErpCustomerIdMigration} after its own migration steps. */
    public void run() {
        if (!tableExists("customer_contacts")) {
            log.debug("customer_contacts table not present — skipping split migration");
            return;
        }
        if (!tableExists("customers")) {
            log.debug("customers table not present yet — Hibernate DDL may not have run; skipping");
            return;
        }

        int customerRows = migrateCustomers();
        int contactRows = migrateContacts();
        log.info("CustomerContact split migration: {} customers, {} contacts copied", customerRows, contactRows);
    }

    private int migrateCustomers() {
        try {
            // Use DISTINCT ON to handle duplicate erp_customer_id values — pick the first row
            return jdbc.update("""
                    INSERT INTO customers (
                        id, organization_id, erp_customer_id, company_name, trading_name,
                        erp_source, delivery_mode, peppol_participant_id,
                        vat_number, tin_number, bpn,
                        address_line1, address_line2, city, country,
                        unsubscribed, unsubscribe_reason,
                        total_invoices_sent, total_delivery_failures, last_invoice_sent_at,
                        created_at, updated_at
                    )
                    SELECT
                        gen_random_uuid(), cc.organization_id, cc.erp_customer_id, cc.company_name, cc.trading_name,
                        cc.erp_source, cc.delivery_mode, cc.peppol_participant_id,
                        cc.vat_number, cc.tin_number, cc.bpn,
                        cc.address_line1, cc.address_line2, cc.city, cc.country,
                        COALESCE(cc.unsubscribed, false), cc.unsubscribe_reason,
                        COALESCE(cc.total_invoices_sent, 0), COALESCE(cc.total_delivery_failures, 0), cc.last_invoice_sent_at,
                        cc.created_at, cc.updated_at
                    FROM (
                        SELECT DISTINCT ON (erp_customer_id) *
                        FROM customer_contacts
                        WHERE erp_customer_id IS NOT NULL AND TRIM(erp_customer_id) != ''
                        ORDER BY erp_customer_id, id
                    ) cc
                    WHERE NOT EXISTS (
                        SELECT 1 FROM customers c WHERE c.erp_customer_id = cc.erp_customer_id
                    )
                    """);
        } catch (Exception e) {
            log.warn("Failed to migrate customers from customer_contacts: {}", e.getMessage());
            return 0;
        }
    }

    private int migrateContacts() {
        try {
            return jdbc.update("""
                    INSERT INTO contacts (
                        id, customer_id, email, name, phone, created_at, updated_at
                    )
                    SELECT
                        gen_random_uuid(),
                        c.id,
                        cc.email,
                        cc.name,
                        cc.phone,
                        COALESCE(cc.created_at, NOW()),
                        cc.updated_at
                    FROM customer_contacts cc
                    INNER JOIN customers c ON c.erp_customer_id = cc.erp_customer_id
                    WHERE cc.email IS NOT NULL AND TRIM(cc.email) != ''
                    AND NOT EXISTS (
                        SELECT 1 FROM contacts ct WHERE ct.email = LOWER(TRIM(cc.email))
                    )
                    """);
        } catch (Exception e) {
            log.warn("Failed to migrate contacts from customer_contacts: {}", e.getMessage());
            return 0;
        }
    }

    private boolean tableExists(String table) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE LOWER(table_name) = LOWER(?)",
                    Integer.class, table);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
