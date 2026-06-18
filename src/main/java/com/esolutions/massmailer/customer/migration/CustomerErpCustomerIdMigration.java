package com.esolutions.massmailer.customer.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-shot schema migration that switches the customer_contacts uniqueness key
 * from (organization_id, email) to (organization_id, erp_customer_id).
 *
 * Steps (each idempotent — safe to re-run):
 *   1. Drop the legacy uk_customer_org_email constraint, if present.
 *   2. Make the email column nullable, if it is still NOT NULL.
 *   3. Backfill erp_customer_id for any rows where it is null/blank, using
 *      a synthesized 'LEGACY-{id}' value so the new unique can hold.
 *   4. Add the uk_customer_org_erp_customer_id unique constraint, if absent.
 *
 * Runs on application startup. Works against PostgreSQL and H2.
 */
@Component
@Order(0)
public class CustomerErpCustomerIdMigration {

    private static final Logger log = LoggerFactory.getLogger(CustomerErpCustomerIdMigration.class);

    private static final String TABLE = "customer_contacts";
    private static final String LEGACY_UK = "uk_customer_org_email";
    private static final String NEW_UK = "uk_customer_org_erp_customer_id";

    private final JdbcTemplate jdbc;
    private final CustomerContactSplitMigration splitMigration;

    public CustomerErpCustomerIdMigration(JdbcTemplate jdbc, CustomerContactSplitMigration splitMigration) {
        this.jdbc = jdbc;
        this.splitMigration = splitMigration;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (!tableExists(TABLE)) {
            log.debug("customer_contacts table not present yet — skipping migration");
            return;
        }
        // Each step runs in its own auto-committed connection so a failure in one
        // (e.g. unique-constraint add failing on residual duplicates) cannot roll
        // back the others. We want the email-unique drop to land regardless.
        dropLegacyUniqueConstraint();
        relaxEmailNullability();
        int backfilled = backfillErpCustomerId();
        if (backfilled > 0) {
            log.info("Backfilled erp_customer_id on {} legacy customer_contacts rows", backfilled);
        }
        addNewUniqueConstraint();
        logFinalState();
        splitMigration.run();
    }

    private void logFinalState() {
        boolean legacyGone = !constraintExists(LEGACY_UK);
        boolean newPresent = constraintExists(NEW_UK);
        log.info("customer_contacts migration state: {}={}, {}={}",
                LEGACY_UK, legacyGone ? "dropped" : "STILL PRESENT",
                NEW_UK,    newPresent ? "present" : "MISSING");
        if (!legacyGone) {
            log.error("Legacy email unique constraint is still active — imports that share an " +
                    "email across customerIds (multi-branch buyers) will be rejected by the DB. " +
                    "Drop it manually: ALTER TABLE {} DROP CONSTRAINT {};", TABLE, LEGACY_UK);
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
            log.warn("Could not probe for table {}: {}", table, e.getMessage());
            return false;
        }
    }

    private boolean constraintExists(String constraintName) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                            "WHERE LOWER(table_name) = LOWER(?) " +
                            "  AND LOWER(constraint_name) = LOWER(?)",
                    Integer.class, TABLE, constraintName);
            return n != null && n > 0;
        } catch (Exception e) {
            log.warn("Could not probe for constraint {}: {}", constraintName, e.getMessage());
            return false;
        }
    }

    private void dropLegacyUniqueConstraint() {
        if (!constraintExists(LEGACY_UK)) return;
        try {
            jdbc.execute("ALTER TABLE " + TABLE + " DROP CONSTRAINT " + LEGACY_UK);
            log.info("Dropped legacy unique constraint {}", LEGACY_UK);
        } catch (Exception e) {
            log.warn("Failed to drop {} — manual intervention may be required: {}",
                    LEGACY_UK, e.getMessage());
        }
    }

    private void relaxEmailNullability() {
        try {
            // Portable across PG and H2
            jdbc.execute("ALTER TABLE " + TABLE + " ALTER COLUMN email DROP NOT NULL");
            log.info("Relaxed NOT NULL on {}.email", TABLE);
        } catch (Exception e) {
            // Already nullable — H2 in dev often gets this right via ddl-auto=update.
            log.debug("email column already nullable (or DROP NOT NULL not applicable): {}",
                    e.getMessage());
        }
    }

    /**
     * Synthesises an erp_customer_id for any rows that still have a null or blank value,
     * so the new unique constraint can hold. Uses the row id to guarantee uniqueness.
     */
    private int backfillErpCustomerId() {
        try {
            return jdbc.update(
                    "UPDATE " + TABLE + " " +
                            "SET erp_customer_id = CONCAT('LEGACY-', id) " +
                            "WHERE erp_customer_id IS NULL OR TRIM(erp_customer_id) = ''");
        } catch (Exception e) {
            log.warn("Failed to backfill erp_customer_id: {}", e.getMessage());
            return 0;
        }
    }

    private void addNewUniqueConstraint() {
        if (constraintExists(NEW_UK)) return;
        try {
            jdbc.execute("ALTER TABLE " + TABLE +
                    " ADD CONSTRAINT " + NEW_UK +
                    " UNIQUE (organization_id, erp_customer_id)");
            log.info("Added unique constraint {} on (organization_id, erp_customer_id)", NEW_UK);
        } catch (Exception e) {
            log.error("Failed to add {} — there may be duplicate (organization_id, erp_customer_id) " +
                    "pairs to resolve first: {}", NEW_UK, e.getMessage());
        }
    }
}
