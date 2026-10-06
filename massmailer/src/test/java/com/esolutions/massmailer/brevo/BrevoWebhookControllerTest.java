package com.esolutions.massmailer.brevo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BrevoWebhookControllerTest {

    private static final String TOKEN = "s3cret-webhook-token";
    private static final String BODY = """
            {"event":"delivered","email":"john@example.com","message-id":"<m@relay>"}""";

    private final BrevoDeliveryEventService events = mock(BrevoDeliveryEventService.class);

    private BrevoWebhookController controller(String token) {
        return new BrevoWebhookController(events, new ObjectMapper(), token);
    }

    @Test
    void rejectsEverythingWhenTokenNotConfigured() {
        var resp = controller("").transactional("Bearer " + TOKEN, null, BODY);
        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(events);
    }

    @Test
    void rejectsMissingOrWrongToken() {
        assertThat(controller(TOKEN).transactional(null, null, BODY).getStatusCode().value()).isEqualTo(401);
        assertThat(controller(TOKEN).transactional("Bearer nope", null, BODY).getStatusCode().value()).isEqualTo(401);
        assertThat(controller(TOKEN).transactional(null, "nope", BODY).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(events);
    }

    @Test
    void acceptsBearerToken() {
        when(events.handle(any(JsonNode.class))).thenReturn(1);
        var resp = controller(TOKEN).transactional("Bearer " + TOKEN, null, BODY);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("received", 1);
        verify(events).handle(any(JsonNode.class));
    }

    @Test
    void acceptsQueryToken() {
        var resp = controller(TOKEN).transactional(null, TOKEN, BODY);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void rejectsInvalidJson() {
        var resp = controller(TOKEN).transactional("Bearer " + TOKEN, null, "{not json");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(events);
    }
}
