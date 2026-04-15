package com.esolutions.massmailer.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listens to campaign lifecycle events.
 * Extend this to push webhook notifications, Slack alerts, metrics, etc.
 *
 * Uses Java 25 pattern matching for exhaustive event handling.
 */
@Component
public class CampaignEventListener {

    private static final Logger log = LoggerFactory.getLogger(CampaignEventListener.class);

    @Async
    @EventListener
    public void onCampaignEvent(CampaignEvent event) {
        switch (event) {
            case CampaignEvent.Started e ->
                    log.info("▶ Campaign {} started — {} recipients queued",
                            e.campaignId(), e.totalRecipients());

            case CampaignEvent.BatchCompleted e ->
                    log.info("◼ Campaign {} batch #{} — sent={}, failed={}",
                            e.campaignId(), e.batchNumber(), e.sent(), e.failed());

            case CampaignEvent.Completed e ->
                    log.info("✔ Campaign {} completed in {}ms — sent={}, failed={}",
                            e.campaignId(), e.durationMs(), e.totalSent(), e.totalFailed());

            case CampaignEvent.Failed e ->
                    log.error("✘ Campaign {} failed: {}", e.campaignId(), e.reason());
        }
    }
}
