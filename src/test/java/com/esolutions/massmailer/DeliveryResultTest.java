package com.esolutions.massmailer;

import com.esolutions.massmailer.model.DeliveryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryResultTest {

    @Test
    @DisplayName("Sealed interface — exhaustive pattern matching with invoice context")
    void shouldPatternMatchExhaustively() {
        var delivered = new DeliveryResult.Delivered("a@b.com", "INV-001", "msg-123", 45_000);
        var failed    = new DeliveryResult.Failed("c@d.com", "INV-002", "Timeout", true);
        var skipped   = new DeliveryResult.Skipped("e@f.com", "INV-003", "No PDF attachment");

        assertThat(describe(delivered)).isEqualTo("DELIVERED:INV-001→a@b.com:msg-123:45000b");
        assertThat(describe(failed)).isEqualTo("FAILED:INV-002→c@d.com:Timeout:retryable");
        assertThat(describe(skipped)).isEqualTo("SKIPPED:INV-003→e@f.com:No PDF attachment");
    }

    @Test
    @DisplayName("DeliveryResult records are value-equal")
    void shouldBeValueEqual() {
        var a = new DeliveryResult.Delivered("x@y.com", "INV-100", "id-1", 1024);
        var b = new DeliveryResult.Delivered("x@y.com", "INV-100", "id-1", 1024);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("All variants expose recipientEmail() and invoiceNumber()")
    void shouldExposeCommonAccessors() {
        DeliveryResult result = new DeliveryResult.Failed("a@b.com", "INV-999", "error", false);
        assertThat(result.recipientEmail()).isEqualTo("a@b.com");
        assertThat(result.invoiceNumber()).isEqualTo("INV-999");
    }

    private String describe(DeliveryResult result) {
        return switch (result) {
            case DeliveryResult.Delivered d ->
                    "DELIVERED:" + d.invoiceNumber() + "→" + d.recipientEmail()
                            + ":" + d.messageId() + ":" + d.attachmentSizeBytes() + "b";
            case DeliveryResult.Failed f ->
                    "FAILED:" + f.invoiceNumber() + "→" + f.recipientEmail()
                            + ":" + f.errorMessage() + (f.retryable() ? ":retryable" : ":terminal");
            case DeliveryResult.Skipped s ->
                    "SKIPPED:" + s.invoiceNumber() + "→" + s.recipientEmail()
                            + ":" + s.reason();
        };
    }
}
