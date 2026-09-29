package com.esolutions.massmailer.trail;

import java.util.UUID;

/**
 * Caller-supplied context for an outbound send, recorded on the mail trail.
 *
 * Background senders (campaigns, PEPPOL notifications) run without an authenticated
 * org, so they pass the owning organisation explicitly. When {@code organizationId}
 * is null the org resolved by the sender lookup is used instead.
 *
 * @param source         which subsystem initiated the send
 * @param organizationId owning organisation; nullable
 * @param campaignId     originating campaign; nullable
 */
public record SendContext(Source source, UUID organizationId, UUID campaignId) {

    public enum Source {
        SINGLE_API,
        SINGLE_API_UPLOAD,
        CAMPAIGN,
        PEPPOL_NOTIFICATION,
        UNSPECIFIED
    }

    public SendContext {
        if (source == null) source = Source.UNSPECIFIED;
    }

    public static SendContext of(Source source) {
        return new SendContext(source, null, null);
    }

    public static SendContext unspecified() {
        return of(Source.UNSPECIFIED);
    }
}
