package com.esolutions.massmailer.service;

import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.service.PdfAttachmentResolver.PdfResolutionException;
import com.esolutions.massmailer.service.PdfAttachmentResolver.ResolvedAttachment;
import net.jqwik.api.*;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for PdfAttachmentResolver.
 *
 * P6: PDF Magic Bytes — validates Requirements 5.3, 5.4
 *
 * No Spring context needed — PdfAttachmentResolver is instantiated directly
 * with fiscal validation disabled so the magic-bytes logic is tested in isolation.
 */
class PdfAttachmentResolverPropertyTest {

    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46, 0x2D}; // %PDF-

    /**
     * Resolver with fiscal validation DISABLED so only magic-byte logic is exercised.
     */
    private final PdfAttachmentResolver resolver = new PdfAttachmentResolver(
            new ZimraFiscalValidator(),
            new MailerProperties(
                    "noreply@test.com", "Test", 100, 5000, 3, 2000L,
                    false  // fiscalValidationEnabled = false
            )
    );

    // ── Property P6: PDF Magic Bytes ─────────────────────────────────────────
    // Validates: Requirements 5.3, 5.4

    /**
     * P6a — Valid PDF bytes (starting with %PDF-) must be accepted and the
     * returned attachment's first 5 bytes must equal the PDF magic bytes.
     *
     * **Validates: Requirements 5.3**
     */
    @Property
    void validPdfBytesAreAcceptedAndMagicBytesPreserved(@ForAll byte[] arbitraryBytes) throws IOException {
        // Prepend %PDF- to arbitrary bytes to form a valid PDF header
        byte[] pdfBytes = prependMagic(arbitraryBytes);

        ResolvedAttachment attachment = resolver.resolveFromBytes(pdfBytes, "test.pdf");

        byte[] returned = attachment.source().getInputStream().readAllBytes();
        assertThat(returned).hasSizeGreaterThanOrEqualTo(5);
        assertThat(returned[0]).isEqualTo((byte) 0x25); // %
        assertThat(returned[1]).isEqualTo((byte) 0x50); // P
        assertThat(returned[2]).isEqualTo((byte) 0x44); // D
        assertThat(returned[3]).isEqualTo((byte) 0x46); // F
        assertThat(returned[4]).isEqualTo((byte) 0x2D); // -
    }

    /**
     * P6b — Byte arrays NOT starting with %PDF- must cause resolveFromBytes
     * to throw PdfResolutionException.
     *
     * **Validates: Requirements 5.4**
     */
    @Property
    void invalidPdfBytesThrowPdfResolutionException(@ForAll("nonPdfBytes") byte[] bytes) {
        assertThatThrownBy(() -> resolver.resolveFromBytes(bytes, "test.pdf"))
                .isInstanceOf(PdfResolutionException.class);
    }

    // ── Arbitraries ──────────────────────────────────────────────────────────

    /**
     * Generates byte arrays that do NOT start with the PDF magic bytes %PDF-.
     * Covers: empty arrays, arrays shorter than 5 bytes, and arrays whose
     * first byte differs from 0x25 ('%').
     */
    @Provide
    Arbitrary<byte[]> nonPdfBytes() {
        // Arrays that are too short to contain the magic bytes
        Arbitrary<byte[]> tooShort = Arbitraries.bytes()
                .array(byte[].class)
                .ofMaxSize(4);

        // Arrays of length >= 5 whose first byte is NOT 0x25 ('%')
        Arbitrary<Byte> nonPercentByte = Arbitraries.bytes()
                .filter(b -> b != (byte) 0x25);
        Arbitrary<byte[]> wrongFirstByte = Combinators.combine(
                nonPercentByte,
                Arbitraries.bytes().array(byte[].class).ofMinSize(4).ofMaxSize(100)
        ).as((first, rest) -> {
            byte[] result = new byte[1 + rest.length];
            result[0] = first;
            System.arraycopy(rest, 0, result, 1, rest.length);
            return result;
        });

        // Arrays starting with 0x25 but whose second byte is NOT 0x50 ('P')
        Arbitrary<Byte> nonPByte = Arbitraries.bytes()
                .filter(b -> b != (byte) 0x50);
        Arbitrary<byte[]> wrongSecondByte = Combinators.combine(
                nonPByte,
                Arbitraries.bytes().array(byte[].class).ofMinSize(3).ofMaxSize(100)
        ).as((second, rest) -> {
            byte[] result = new byte[2 + rest.length];
            result[0] = (byte) 0x25;
            result[1] = second;
            System.arraycopy(rest, 0, result, 2, rest.length);
            return result;
        });

        return Arbitraries.oneOf(tooShort, wrongFirstByte, wrongSecondByte);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private byte[] prependMagic(byte[] body) {
        byte[] result = new byte[PDF_MAGIC.length + body.length];
        System.arraycopy(PDF_MAGIC, 0, result, 0, PDF_MAGIC.length);
        System.arraycopy(body, 0, result, PDF_MAGIC.length, body.length);
        return result;
    }
}
