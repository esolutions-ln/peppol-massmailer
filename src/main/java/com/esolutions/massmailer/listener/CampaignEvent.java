package com.esolutions.massmailer.listener;

import java.util.UUID;

/**
 * Sealed hierarchy for campaign lifecycle events.
 * Enables type-safe event handling via pattern matching.
 */
public sealed interface CampaignEvent {

    UUID campaignId();

    record Started(UUID campaignId, int totalRecipients) implements CampaignEvent {}

    record BatchCompleted(UUID campaignId, int batchNumber, int sent, int failed) implements CampaignEvent {}

    record Completed(UUID campaignId, int totalSent, int totalFailed, long durationMs) implements CampaignEvent {}

    record Failed(UUID campaignId, String reason) implements CampaignEvent {}
}
