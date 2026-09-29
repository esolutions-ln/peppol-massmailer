package com.esolutions.massmailer.trail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC store for {@link MailSendAttempt} rows.
 *
 * Deliberately not a JPA entity: production runs with {@code ddl-auto=validate} and no
 * migration tool, so a new entity would fail startup validation until the table existed.
 * The table is created idempotently here instead (PostgreSQL and H2 compatible), in the
 * same spirit as the startup migrations under {@code customer.migration}.
 */
@Repository
public class MailSendAttemptRepository implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MailSendAttemptRepository.class);

    static final String TABLE = "mail_send_attempts";

    private final NamedParameterJdbcTemplate jdbc;

    public MailSendAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            ensureSchema();
        } catch (Exception e) {
            // Never block startup over the audit trail — sends still work, recording will log errors.
            log.error("Could not create {} table — email trail recording is unavailable: {}", TABLE, e.getMessage());
        }
    }

    void ensureSchema() {
        var ops = jdbc.getJdbcOperations();
        ops.execute("""
                CREATE TABLE IF NOT EXISTS mail_send_attempts (
                    id                 UUID PRIMARY KEY,
                    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
                    organization_id    UUID,
                    campaign_id        UUID,
                    source             VARCHAR(40)   NOT NULL,
                    invoice_number     VARCHAR(200),
                    recipient_email    VARCHAR(320),
                    cc_emails          VARCHAR(2000),
                    sender_email       VARCHAR(320),
                    subject            VARCHAR(1000),
                    transport          VARCHAR(20)   NOT NULL,
                    mail_host          VARCHAR(255),
                    mail_port          INTEGER,
                    attempt_number     INTEGER       NOT NULL,
                    outcome            VARCHAR(20)   NOT NULL,
                    retryable          BOOLEAN       NOT NULL,
                    ip_denied          BOOLEAN       NOT NULL,
                    message_id         VARCHAR(500),
                    response_code      INTEGER,
                    enhanced_status    VARCHAR(20),
                    error_message      VARCHAR(2000),
                    egress_ip          VARCHAR(64),
                    server_reported_ip VARCHAR(64),
                    local_ip           VARCHAR(64),
                    attachment_bytes   BIGINT,
                    duration_ms        BIGINT        NOT NULL
                )""");
        ops.execute("CREATE INDEX IF NOT EXISTS idx_msa_created ON mail_send_attempts (created_at)");
        ops.execute("CREATE INDEX IF NOT EXISTS idx_msa_org_created ON mail_send_attempts (organization_id, created_at)");
        ops.execute("CREATE INDEX IF NOT EXISTS idx_msa_invoice ON mail_send_attempts (invoice_number)");
        ops.execute("CREATE INDEX IF NOT EXISTS idx_msa_recipient ON mail_send_attempts (recipient_email)");
        ops.execute("CREATE INDEX IF NOT EXISTS idx_msa_ip_denied ON mail_send_attempts (ip_denied, egress_ip)");
    }

    public void insert(MailSendAttempt a) {
        jdbc.update("""
                INSERT INTO mail_send_attempts (
                    id, created_at, organization_id, campaign_id, source, invoice_number,
                    recipient_email, cc_emails, sender_email, subject, transport, mail_host, mail_port,
                    attempt_number, outcome, retryable, ip_denied, message_id, response_code,
                    enhanced_status, error_message, egress_ip, server_reported_ip, local_ip,
                    attachment_bytes, duration_ms
                ) VALUES (
                    :id, :createdAt, :organizationId, :campaignId, :source, :invoiceNumber,
                    :recipientEmail, :ccEmails, :senderEmail, :subject, :transport, :mailHost, :mailPort,
                    :attemptNumber, :outcome, :retryable, :ipDenied, :messageId, :responseCode,
                    :enhancedStatus, :errorMessage, :egressIp, :serverReportedIp, :localIp,
                    :attachmentBytes, :durationMs
                )""", new MapSqlParameterSource()
                .addValue("id", a.id())
                .addValue("createdAt", OffsetDateTime.ofInstant(a.createdAt(), ZoneOffset.UTC))
                .addValue("organizationId", a.organizationId())
                .addValue("campaignId", a.campaignId())
                .addValue("source", a.source().name())
                .addValue("invoiceNumber", truncate(a.invoiceNumber(), 200))
                .addValue("recipientEmail", truncate(a.recipientEmail(), 320))
                .addValue("ccEmails", truncate(a.ccEmails(), 2000))
                .addValue("senderEmail", truncate(a.senderEmail(), 320))
                .addValue("subject", truncate(a.subject(), 1000))
                .addValue("transport", a.transport().name())
                .addValue("mailHost", truncate(a.mailHost(), 255))
                .addValue("mailPort", a.mailPort())
                .addValue("attemptNumber", a.attemptNumber())
                .addValue("outcome", a.outcome().name())
                .addValue("retryable", a.retryable())
                .addValue("ipDenied", a.ipDenied())
                .addValue("messageId", truncate(a.messageId(), 500))
                .addValue("responseCode", a.responseCode())
                .addValue("enhancedStatus", truncate(a.enhancedStatus(), 20))
                .addValue("errorMessage", truncate(a.errorMessage(), 2000))
                .addValue("egressIp", truncate(a.egressIp(), 64))
                .addValue("serverReportedIp", truncate(a.serverReportedIp(), 64))
                .addValue("localIp", truncate(a.localIp(), 64))
                .addValue("attachmentBytes", a.attachmentBytes())
                .addValue("durationMs", a.durationMs()));
    }

    /** Search filter; every field is optional. */
    public record Filter(
            UUID organizationId,
            UUID campaignId,
            String recipientEmail,
            String invoiceNumber,
            MailSendAttempt.Outcome outcome,
            SendContext.Source source,
            Boolean ipDenied,
            String egressIp,
            Instant from,
            Instant to
    ) {}

    public List<MailSendAttempt> search(Filter f, int page, int size) {
        var params = new MapSqlParameterSource();
        String where = where(f, params);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query("SELECT * FROM mail_send_attempts" + where
                + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset", params, MAPPER);
    }

    public long count(Filter f) {
        var params = new MapSqlParameterSource();
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM mail_send_attempts" + where(f, params), params, Long.class);
        return n == null ? 0 : n;
    }

    public Optional<MailSendAttempt> findById(UUID id) {
        return jdbc.query("SELECT * FROM mail_send_attempts WHERE id = :id",
                new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    /** One row per (egress IP, mail host, transport) that the provider refused. */
    public record DeniedIpSummary(
            String egressIp,
            String serverReportedIp,
            String mailHost,
            MailSendAttempt.Transport transport,
            long deniedAttempts,
            long organizationsAffected,
            Instant firstSeen,
            Instant lastSeen
    ) {}

    public List<DeniedIpSummary> deniedIpSummary(Instant since) {
        var params = new MapSqlParameterSource();
        String sinceClause = "";
        if (since != null) {
            sinceClause = " AND created_at >= :since";
            params.addValue("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
        }
        return jdbc.query("""
                SELECT egress_ip, MAX(server_reported_ip) AS server_reported_ip, mail_host, transport,
                       COUNT(*) AS denied_attempts, COUNT(DISTINCT organization_id) AS orgs_affected,
                       MIN(created_at) AS first_seen, MAX(created_at) AS last_seen
                FROM mail_send_attempts
                WHERE ip_denied = TRUE""" + sinceClause + """

                GROUP BY egress_ip, mail_host, transport
                ORDER BY last_seen DESC""", params, (rs, i) -> new DeniedIpSummary(
                rs.getString("egress_ip"),
                rs.getString("server_reported_ip"),
                rs.getString("mail_host"),
                MailSendAttempt.Transport.valueOf(rs.getString("transport")),
                rs.getLong("denied_attempts"),
                rs.getLong("orgs_affected"),
                instant(rs, "first_seen"),
                instant(rs, "last_seen")));
    }

    private static String where(Filter f, MapSqlParameterSource params) {
        List<String> c = new ArrayList<>();
        if (f.organizationId() != null) { c.add("organization_id = :orgId"); params.addValue("orgId", f.organizationId()); }
        if (f.campaignId() != null) { c.add("campaign_id = :campaignId"); params.addValue("campaignId", f.campaignId()); }
        if (notBlank(f.recipientEmail())) {
            c.add("LOWER(recipient_email) = :recipient");
            params.addValue("recipient", f.recipientEmail().strip().toLowerCase());
        }
        if (notBlank(f.invoiceNumber())) { c.add("invoice_number = :invoice"); params.addValue("invoice", f.invoiceNumber().strip()); }
        if (f.outcome() != null) { c.add("outcome = :outcome"); params.addValue("outcome", f.outcome().name()); }
        if (f.source() != null) { c.add("source = :source"); params.addValue("source", f.source().name()); }
        if (f.ipDenied() != null) { c.add("ip_denied = :ipDenied"); params.addValue("ipDenied", f.ipDenied()); }
        if (notBlank(f.egressIp())) { c.add("egress_ip = :egressIp"); params.addValue("egressIp", f.egressIp().strip()); }
        if (f.from() != null) { c.add("created_at >= :from"); params.addValue("from", OffsetDateTime.ofInstant(f.from(), ZoneOffset.UTC)); }
        if (f.to() != null) { c.add("created_at < :to"); params.addValue("to", OffsetDateTime.ofInstant(f.to(), ZoneOffset.UTC)); }
        return c.isEmpty() ? "" : " WHERE " + String.join(" AND ", c);
    }

    private static final RowMapper<MailSendAttempt> MAPPER = (rs, i) -> new MailSendAttempt(
            rs.getObject("id", UUID.class),
            instant(rs, "created_at"),
            rs.getObject("organization_id", UUID.class),
            rs.getObject("campaign_id", UUID.class),
            SendContext.Source.valueOf(rs.getString("source")),
            rs.getString("invoice_number"),
            rs.getString("recipient_email"),
            rs.getString("cc_emails"),
            rs.getString("sender_email"),
            rs.getString("subject"),
            MailSendAttempt.Transport.valueOf(rs.getString("transport")),
            rs.getString("mail_host"),
            (Integer) rs.getObject("mail_port"),
            rs.getInt("attempt_number"),
            MailSendAttempt.Outcome.valueOf(rs.getString("outcome")),
            rs.getBoolean("retryable"),
            rs.getBoolean("ip_denied"),
            rs.getString("message_id"),
            (Integer) rs.getObject("response_code"),
            rs.getString("enhanced_status"),
            rs.getString("error_message"),
            rs.getString("egress_ip"),
            rs.getString("server_reported_ip"),
            rs.getString("local_ip"),
            rs.getObject("attachment_bytes") == null ? null : rs.getLong("attachment_bytes"),
            rs.getLong("duration_ms"));

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
