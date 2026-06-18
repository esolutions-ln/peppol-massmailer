package com.esolutions.forwarder.store;

import com.esolutions.forwarder.config.ForwarderProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite-backed ledger of submitted batches. Used to:
 * <ul>
 *   <li>Track which PDFs went into which remote campaign</li>
 *   <li>Poll status for in-flight campaigns</li>
 *   <li>Show recent activity on /status</li>
 * </ul>
 */
@Component
public class CampaignLedger {

    private static final Logger log = LoggerFactory.getLogger(CampaignLedger.class);

    public record LedgerEntry(
            String batchId,
            UUID campaignId,
            String status,
            int recipientCount,
            String files,
            Instant submittedAt,
            Instant lastCheckedAt
    ) {}

    private final ForwarderProperties props;

    public CampaignLedger(ForwarderProperties props) {
        this.props = props;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + props.ledgerPath());
    }

    @PostConstruct
    void init() {
        try {
            Path p = Path.of(props.ledgerPath()).toAbsolutePath();
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            try (Connection c = open(); Statement s = c.createStatement()) {
                s.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS batches (
                            batch_id TEXT PRIMARY KEY,
                            campaign_id TEXT,
                            status TEXT NOT NULL,
                            recipient_count INTEGER NOT NULL,
                            files TEXT NOT NULL,
                            submitted_at TEXT NOT NULL,
                            last_checked_at TEXT
                        )""");
            }
            log.info("Ledger ready at {}", p);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize ledger at " + props.ledgerPath(), e);
        }
    }

    public void recordSubmission(String batchId, UUID campaignId, int recipientCount, String filesCsv) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO batches(batch_id, campaign_id, status, recipient_count, files, submitted_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, batchId);
            ps.setString(2, campaignId != null ? campaignId.toString() : null);
            ps.setString(3, "SUBMITTED");
            ps.setInt(4, recipientCount);
            ps.setString(5, filesCsv);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Ledger write failed for batch {}: {}", batchId, e.getMessage());
        }
    }

    public void updateStatus(String batchId, String status) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE batches SET status=?, last_checked_at=? WHERE batch_id=?")) {
            ps.setString(1, status);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, batchId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Ledger update failed for batch {}: {}", batchId, e.getMessage());
        }
    }

    public List<LedgerEntry> findInFlight() {
        List<LedgerEntry> out = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT batch_id, campaign_id, status, recipient_count, files, submitted_at, last_checked_at "
                             + "FROM batches WHERE status IN ('SUBMITTED','RUNNING') AND campaign_id IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            log.error("Ledger read failed: {}", e.getMessage());
        }
        return out;
    }

    public List<LedgerEntry> findRecent(int limit) {
        List<LedgerEntry> out = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT batch_id, campaign_id, status, recipient_count, files, submitted_at, last_checked_at "
                             + "FROM batches ORDER BY submitted_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            log.error("Ledger recent read failed: {}", e.getMessage());
        }
        return out;
    }

    private static LedgerEntry map(ResultSet rs) throws SQLException {
        String cid = rs.getString(2);
        String last = rs.getString(7);
        return new LedgerEntry(
                rs.getString(1),
                cid != null ? UUID.fromString(cid) : null,
                rs.getString(3),
                rs.getInt(4),
                rs.getString(5),
                Instant.parse(rs.getString(6)),
                last != null ? Instant.parse(last) : null
        );
    }
}
