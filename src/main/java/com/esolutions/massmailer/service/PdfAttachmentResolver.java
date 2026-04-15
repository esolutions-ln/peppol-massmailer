package com.esolutions.massmailer.service;

import com.esolutions.massmailer.config.MailerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Resolves the invoice PDF attachment for a given recipient.
 * Supports two source modes:
 *   1. File path on disk / shared volume (typical for batch jobs where
 *      a PDF generator writes to a known output directory)
 *   2. Base64-encoded bytes (for API-driven uploads where the caller
 *      POSTs the PDF inline)
 *
 * Returns a record containing the bytes, filename, content type,
 * and size — everything needed by the SMTP service to attach.
 *
 * All resolved PDFs are validated for ZIMRA fiscalisation markers
 * before being returned. Non-fiscalised PDFs are rejected.
 */
@Service
public class PdfAttachmentResolver {

    private static final Logger log = LoggerFactory.getLogger(PdfAttachmentResolver.class);

    private final ZimraFiscalValidator fiscalValidator;
    private final boolean fiscalValidationEnabled;

    public PdfAttachmentResolver(ZimraFiscalValidator fiscalValidator,
                                  MailerProperties mailerProperties) {
        this.fiscalValidator = fiscalValidator;
        this.fiscalValidationEnabled = mailerProperties.fiscalValidationEnabled();
    }

    /**
     * Resolved PDF attachment — ready for MIME attachment.
     */
    public record ResolvedAttachment(
            InputStreamSource source,
            String fileName,
            String contentType,
            long sizeBytes
    ) {}

    /**
     * Resolves the PDF directly from raw bytes (e.g. multipart file upload).
     * Validates magic bytes and ZIMRA fiscalisation markers.
     */
    public ResolvedAttachment resolveFromBytes(byte[] bytes, String fileName) {
        validatePdfMagicBytes(bytes, fileName);
        validateFiscalisation(bytes, fileName);
        log.debug("Resolved PDF from uploaded bytes: {} ({} bytes)", fileName, bytes.length);
        return new ResolvedAttachment(
                new ByteArrayResource(bytes),
                fileName != null ? fileName : "invoice.pdf",
                "application/pdf",
                bytes.length
        );
    }

    /**
     * Resolves the PDF from either a file path or Base64 payload.
     *
     * @param pdfFilePath  absolute path to PDF on disk (preferred for batch)
     * @param pdfBase64    Base64-encoded PDF content (alternative for API upload)
     * @param pdfFileName  desired attachment filename, e.g. "INV-2026-0042.pdf"
     * @return resolved attachment, or null if no PDF source is available
     * @throws PdfResolutionException if the source exists but cannot be read
     */
    public ResolvedAttachment resolve(String pdfFilePath, String pdfBase64, String pdfFileName) {

        // ── Strategy 1: Read from file system ──
        if (pdfFilePath != null && !pdfFilePath.isBlank()) {
            return resolveFromFile(pdfFilePath, pdfFileName);
        }

        // ── Strategy 2: Decode Base64 ──
        if (pdfBase64 != null && !pdfBase64.isBlank()) {
            return resolveFromBase64(pdfBase64, pdfFileName);
        }

        log.warn("No PDF source provided for attachment '{}'", pdfFileName);
        return null;
    }

    private ResolvedAttachment resolveFromFile(String filePath, String fileName) {
        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            throw new PdfResolutionException("PDF file not found: " + filePath);
        }
        if (!Files.isReadable(path)) {
            throw new PdfResolutionException("PDF file not readable: " + filePath);
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            validatePdfMagicBytes(bytes, filePath);
            validateFiscalisation(bytes, filePath);

            log.debug("Resolved PDF from file: {} ({} bytes)", filePath, bytes.length);
            return new ResolvedAttachment(
                    new ByteArrayResource(bytes),
                    deriveFileName(fileName, path),
                    "application/pdf",
                    bytes.length
            );
        } catch (IOException e) {
            throw new PdfResolutionException("Failed to read PDF file: " + filePath, e);
        }
    }

    private ResolvedAttachment resolveFromBase64(String base64, String fileName) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            validatePdfMagicBytes(bytes, "Base64 input");
            validateFiscalisation(bytes, fileName);

            log.debug("Resolved PDF from Base64: {} ({} bytes)", fileName, bytes.length);
            return new ResolvedAttachment(
                    new ByteArrayResource(bytes),
                    fileName != null ? fileName : "invoice.pdf",
                    "application/pdf",
                    bytes.length
            );
        } catch (IllegalArgumentException e) {
            throw new PdfResolutionException("Invalid Base64 encoding for PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Validates that the PDF contains ZIMRA fiscal markers.
     * Throws PdfResolutionException if the document has not been fiscalised.
     * Skipped when {@code massmailer.fiscal-validation-enabled=false} (e.g. in tests).
     */
    private void validateFiscalisation(byte[] bytes, String source) {
        if (!fiscalValidationEnabled) {
            log.debug("Fiscal validation disabled — skipping for {}", source);
            return;
        }
        var result = fiscalValidator.validate(bytes, source);
        if (!result.valid()) {
            throw new PdfResolutionException(
                    "PDF failed ZIMRA fiscalisation validation (" + source + "): "
                    + String.join("; ", result.errors()));
        }
    }

    /**
     * Validates the PDF magic bytes (%PDF-) to catch corrupt or wrong file types
     * before we attach them to emails.
     */
    private void validatePdfMagicBytes(byte[] bytes, String source) {
        if (bytes.length < 5) {
            throw new PdfResolutionException("File too small to be a valid PDF: " + source);
        }
        // PDF files start with %PDF- (hex: 25 50 44 46 2D)
        if (bytes[0] != 0x25 || bytes[1] != 0x50 || bytes[2] != 0x44
                || bytes[3] != 0x46 || bytes[4] != 0x2D) {
            throw new PdfResolutionException(
                    "File does not have valid PDF magic bytes (%%PDF-): " + source);
        }
    }

    private String deriveFileName(String explicit, Path path) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        return path.getFileName().toString();
    }

    // ── Custom exception ──

    public static class PdfResolutionException extends RuntimeException {
        public PdfResolutionException(String message) { super(message); }
        public PdfResolutionException(String message, Throwable cause) { super(message, cause); }
    }
}
