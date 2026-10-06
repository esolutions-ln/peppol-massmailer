package com.esolutions.massmailer.brevo;

import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.model.MailRecipient;
import com.esolutions.massmailer.model.MailRecipient.RecipientStatus;
import com.esolutions.massmailer.repository.CampaignRepository;
import com.esolutions.massmailer.repository.RecipientRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Applies Brevo transactional delivery events to invoice recipients.
 *
 * <p>Events arrive from two sources, both funnelled through {@link #handle(JsonNode)}:
 * the {@code POST /webhooks/brevo/transactional} webhook and the periodic
 * {@link BrevoEventReconciler}, which pulls {@code error}/{@code blocked}/bounce events
 * from {@code GET /v3/smtp/statistics/events} — Brevo cannot push {@code error}
 * (e.g. "sender is not valid") via webhook. Handling is idempotent, so the same event
 * from both sources is harmless.</p>
 *
 * <p>Recipients are matched by the {@code messageId} Brevo returned at send time.
 * A permanent failure moves a {@code SENT} recipient to {@code FAILED} (with the Brevo
 * reason) and adjusts its campaign's counters. Campaign recipients are only committed
 * when the whole dispatch finishes, so a failure event for an unknown messageId is
 * retried in the background for up to ~40 minutes.</p>
 */
@Service
public class BrevoDeliveryEventService {

    private static final Logger log = LoggerFactory.getLogger(BrevoDeliveryEventService.class);

    /** Backoff for permanent-failure events whose recipient isn't committed yet. */
    static final List<Duration> NOT_FOUND_RETRY_DELAYS = List.of(
            Duration.ofSeconds(15), Duration.ofSeconds(45), Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(20));

    private static final int ERROR_MESSAGE_MAX = 255;

    public enum Kind { DELIVERED, PERMANENT_FAILURE, TRANSIENT, COMPLAINT, IGNORED }

    public enum Outcome { UPDATED, NO_CHANGE, NOT_FOUND, IGNORED }

    /** One Brevo event, normalised across webhook and statistics-API payloads. */
    public record BrevoEvent(String event, String email, String messageId, String reason) {

        /** Webhook payloads use {@code message-id}; the statistics API uses {@code messageId}. */
        static Optional<BrevoEvent> from(JsonNode n) {
            String event = text(n, "event");
            String messageId = text(n, "message-id");
            if (messageId == null) messageId = text(n, "messageId");
            if (event == null) return Optional.empty();
            return Optional.of(new BrevoEvent(event, text(n, "email"), messageId, text(n, "reason")));
        }

        private static String text(JsonNode n, String field) {
            JsonNode v = n.get(field);
            return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
        }
    }

    private final RecipientRepository recipients;
    private final CampaignRepository campaigns;
    private final TransactionOperations tx;
    private final TaskScheduler scheduler;
    private final MailerProperties props;

    public BrevoDeliveryEventService(RecipientRepository recipients,
                                     CampaignRepository campaigns,
                                     TransactionOperations tx,
                                     TaskScheduler scheduler,
                                     MailerProperties props) {
        this.recipients = recipients;
        this.campaigns = campaigns;
        this.tx = tx;
        this.scheduler = scheduler;
        this.props = props;
    }

    /**
     * Maps both naming styles — webhook ({@code hard_bounce}, {@code invalid_email})
     * and statistics API / webhook config ({@code hardBounces}, {@code invalid}).
     */
    public static Kind classify(String event) {
        if (event == null) return Kind.IGNORED;
        String e = event.toLowerCase(Locale.ROOT).replace("_", "");
        return switch (e) {
            case "delivered" -> Kind.DELIVERED;
            case "hardbounce", "hardbounces", "invalid", "invalidemail", "blocked", "error" ->
                    Kind.PERMANENT_FAILURE;
            case "softbounce", "softbounces", "deferred" -> Kind.TRANSIENT;
            case "spam", "unsubscribed" -> Kind.COMPLAINT;
            default -> Kind.IGNORED; // request, sent, opened, click, proxy opens …
        };
    }

    /** Webhook entry point — unmatched failure events are retried in the background. */
    public int handle(JsonNode payload) {
        return handle(payload, true);
    }

    /**
     * Handles a webhook body or statistics-API event list: a single event object or an
     * array of them (Brevo "batched" webhooks).
     *
     * @param retryUnmatched schedule background retries for failure events whose recipient
     *                       isn't committed yet; the reconciler passes {@code false} since its
     *                       next run re-reads the same events anyway
     * @return number of events read from the payload
     */
    public int handle(JsonNode payload, boolean retryUnmatched) {
        List<JsonNode> nodes = new ArrayList<>();
        if (payload == null) return 0;
        if (payload.isArray()) payload.forEach(nodes::add);
        else if (payload.isObject()) nodes.add(payload);

        int count = 0;
        for (JsonNode n : nodes) {
            Optional<BrevoEvent> ev = BrevoEvent.from(n);
            if (ev.isEmpty()) continue;
            count++;
            Outcome outcome = apply(ev.get());
            if (outcome == Outcome.NOT_FOUND && retryUnmatched) {
                scheduleRetry(ev.get(), 0);
            }
        }
        return count;
    }

    /** Applies one event in its own transaction. */
    public Outcome apply(BrevoEvent ev) {
        Kind kind = classify(ev.event());
        if (kind == Kind.IGNORED) {
            log.debug("Brevo event '{}' for {} ignored", ev.event(), ev.email());
            return Outcome.IGNORED;
        }
        if (ev.messageId() == null) {
            log.warn("Brevo '{}' event for {} has no messageId — cannot match a recipient",
                    ev.event(), ev.email());
            return Outcome.IGNORED;
        }

        Outcome outcome = tx.execute(status -> applyInTx(kind, ev));
        if (outcome == Outcome.UPDATED) {
            log.info("Brevo '{}' for {} [msgId={}] → recipient marked FAILED{}", ev.event(), ev.email(),
                    ev.messageId(), ev.reason() != null ? " (" + ev.reason() + ")" : "");
        } else {
            log.debug("Brevo event '{}' for {} [msgId={}] → {}", ev.event(), ev.email(), ev.messageId(), outcome);
        }
        return outcome;
    }

    private Outcome applyInTx(Kind kind, BrevoEvent ev) {
        Optional<MailRecipient> found = recipients.findFirstByMessageId(ev.messageId());
        if (found.isEmpty()) {
            // Not a campaign recipient (single send / platform mail), or not committed yet.
            return kind == Kind.PERMANENT_FAILURE ? Outcome.NOT_FOUND : Outcome.NO_CHANGE;
        }
        MailRecipient r = found.get();

        switch (kind) {
            case PERMANENT_FAILURE -> {
                if (r.getDeliveryStatus() != RecipientStatus.SENT) return Outcome.NO_CHANGE;
                r.setDeliveryStatus(RecipientStatus.FAILED);
                r.setErrorMessage(truncate("Brevo " + ev.event()
                        + (ev.reason() != null ? ": " + ev.reason() : "")));
                // Same address will fail again — keep it out of POST /campaigns/{id}/retry.
                r.setRetryCount(Math.max(r.getRetryCount(), props.maxRetries()));
                recipients.save(r);
                var campaign = r.getCampaign();
                if (campaign != null) {
                    campaign.recordLateFailure();
                    campaigns.save(campaign);
                }
                return Outcome.UPDATED;
            }
            case COMPLAINT -> {
                log.warn("Recipient {} of invoice {} reported '{}' — review before sending again",
                        r.getEmail(), r.getInvoiceNumber(), ev.event());
                return Outcome.NO_CHANGE;
            }
            default -> {
                return Outcome.NO_CHANGE; // DELIVERED confirms SENT; TRANSIENT: Brevo keeps retrying
            }
        }
    }

    private void scheduleRetry(BrevoEvent ev, int attempt) {
        if (attempt >= NOT_FOUND_RETRY_DELAYS.size()) {
            log.warn("Brevo '{}' event for {} [msgId={}] never matched a campaign recipient "
                    + "(single send or platform mail?)", ev.event(), ev.email(), ev.messageId());
            return;
        }
        Duration delay = NOT_FOUND_RETRY_DELAYS.get(attempt);
        scheduler.schedule(() -> {
            try {
                if (apply(ev) == Outcome.NOT_FOUND) scheduleRetry(ev, attempt + 1);
            } catch (Exception e) {
                log.error("Retrying Brevo event {} failed: {}", ev.messageId(), e.getMessage());
                scheduleRetry(ev, attempt + 1);
            }
        }, Instant.now().plus(delay));
    }

    private static String truncate(String s) {
        return s.length() <= ERROR_MESSAGE_MAX ? s : s.substring(0, ERROR_MESSAGE_MAX - 1) + "…";
    }
}
