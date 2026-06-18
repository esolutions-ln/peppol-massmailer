package com.esolutions.forwarder.watcher;

import com.esolutions.forwarder.client.MassMailerClient;
import com.esolutions.forwarder.config.ForwarderProperties;
import com.esolutions.forwarder.store.CampaignLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically polls in-flight campaigns and updates the ledger with their
 * latest status. Terminal statuses (COMPLETED, FAILED) stop being polled.
 */
@Component
public class StatusPoller {

    private static final Logger log = LoggerFactory.getLogger(StatusPoller.class);

    private final ForwarderProperties props;
    private final MassMailerClient client;
    private final CampaignLedger ledger;

    public StatusPoller(ForwarderProperties props, MassMailerClient client, CampaignLedger ledger) {
        this.props = props;
        this.client = client;
        this.ledger = ledger;
    }

    @Scheduled(fixedDelayString = "#{${forwarder.status-poll-seconds} * 1000}",
            initialDelayString = "#{${forwarder.status-poll-seconds} * 1000}")
    public void poll() {
        var inFlight = ledger.findInFlight();
        if (inFlight.isEmpty()) return;
        for (var entry : inFlight) {
            try {
                var resp = client.getCampaign(entry.campaignId());
                if (resp == null || resp.status() == null) continue;
                ledger.updateStatus(entry.batchId(), resp.status());
                log.debug("Batch {} (campaign {}) → {}", entry.batchId(), resp.id(), resp.status());
            } catch (Exception e) {
                log.warn("Poll failed for batch {}: {}", entry.batchId(), e.getMessage());
            }
        }
    }
}
