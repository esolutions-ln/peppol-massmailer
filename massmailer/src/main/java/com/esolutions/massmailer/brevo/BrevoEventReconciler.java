package com.esolutions.massmailer.brevo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically pulls failure events from Brevo's statistics API and applies them via
 * {@link BrevoDeliveryEventService}.
 *
 * <p>Needed because Brevo's webhooks cannot deliver the {@code error} event — which is
 * how Brevo reports a rejected send such as "sender … is not valid" after already
 * returning 2xx + messageId — and as a safety net for webhooks Brevo failed to deliver.
 * Handling is idempotent, so re-reading the same events every run is harmless.</p>
 */
@Component
@ConditionalOnProperty(prefix = "massmailer.brevo", name = "enabled", havingValue = "true")
public class BrevoEventReconciler {

    private static final Logger log = LoggerFactory.getLogger(BrevoEventReconciler.class);

    /** Statistics-API event filters that mean "not delivered". */
    static final List<String> FAILURE_EVENTS = List.of("error", "blocked", "hardBounces", "invalid");

    private static final int LOOKBACK_DAYS = 2;

    private final BrevoEmailClient brevo;
    private final BrevoDeliveryEventService events;

    public BrevoEventReconciler(BrevoEmailClient brevo, BrevoDeliveryEventService events) {
        this.brevo = brevo;
        this.events = events;
    }

    @Scheduled(initialDelayString = "${massmailer.brevo.reconcile-initial-delay:PT2M}",
               fixedDelayString = "${massmailer.brevo.reconcile-interval:PT15M}")
    public void reconcile() {
        for (String event : FAILURE_EVENTS) {
            try {
                int n = events.handle(brevo.getEvents(event, LOOKBACK_DAYS), false);
                log.debug("Brevo reconcile: read {} '{}' event(s)", n, event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Brevo reconcile for '{}' events failed: {}", event, e.getMessage());
            }
        }
    }
}
