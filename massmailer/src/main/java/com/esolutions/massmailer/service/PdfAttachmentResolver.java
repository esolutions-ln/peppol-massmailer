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
 *
 * <p>Source modes:
 * <ol>
 *   <li>File path on a configured inbox directory — must be a descendant of
 *       {@code massmailer.pdf-inbox-base-path} to prevent path traversal.</li>
 *   <li>Base64-encoded bytes — size-capped before decode to protect against OOM.</li>
 * </ol>
 *
 * <p>All resolved PDFs are validated for magic bytes and (when enabled) ZIMRA
 * fiscalisation markers before being returned.
 */
@Service
public class PdfAttachmentResolver {

    private static final Logger log = LoggerFactory.getLogger(PdfAttachmentResolver.class);

    private final ZimraFiscalValidator fiscalValidator;
    private final boolean fiscalValidationEnabled;
    private final Path allowedBase;
    private final long maxPdfBytes;

    public PdfAttachmentResolver(ZimraFiscalValidator fiscalValidator,
                                  MailerProperties mailerProperties) {
        this.fiscalValidator = fiscalValidator;
        this.fiscalValidationEnabled = mailerProperties.fiscalValidationEnabled();
        this.maxPdfBytes = mailerProperties.maxPdfBytes();

        String configuredBase = mailerProperties.pdfInboxBasePath();
        if (configuredBase == null || configuredBase.isBlank()) {
            this.allowedBase = null;
            log.warn("massmailer.pdf-inbox-base-path is not set — file-path PDF resolution is DISABLED. " +
                    "Callers must use Base64 mode. Set the property to enable a trusted inbox directory.");
        } else {
            this.allowedBase = Path.of(configuredBase).toAbsolutePath().normalize();
            log.info("PDF inbox base path configured: {}", this.allowedBase);
        }
    }

    public record ResolvedAttachment(
            InputStreamSource source,
            String fileName,
            String contentType,
            long sizeBytes
    ) {}

    /**
     * Resolves the PDF directly from raw bytes (e.g. multipart file upload).
     * Validates size, magic bytes, and ZIMRA fiscalisation markers.
     */
    public ResolvedAttachment resolveFromBytes(byte[] bytes, String fileName) {
        if (bytes == null) {
            throw new PdfResolutionException("PDF bytes are null: " + fileName);
        }
        if (bytes.length > maxPdfBytes) {
            throw new PdfResolutionException(
                    "PDF exceeds maximum allowed size (" + bytes.length + " > " + maxPdfBytes + " bytes): " + fileName);
        }
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
     * @param pdfFilePath  absolute path under the configured inbox (preferred for batch)
     * @param pdfBase64    Base64-encoded PDF content (alternative for API upload)
     * @param pdfFileName  desired attachment filename, e.g. "INV-2026-0042.pdf"
     * @return resolved attachment, or null if no PDF source is available
     * @throws PdfResolutionException if the source exists but cannot be read,
     *                                or if the path is outside the configured inbox
     */
    public ResolvedAttachment resolve(String pdfFilePath, String pdfBase64, String pdfFileName) {
        if (pdfFilePath != null && !pdfFilePath.isBlank()) {
            return resolveFromFile(pdfFilePath, pdfFileName);
        }
        if (pdfBase64 != null && !pdfBase64.isBlank()) {
            return resolveFromBase64(pdfBase64, pdfFileName);
        }
        log.warn("No PDF source provided for attachment '{}'", pdfFileName);
        return null;
    }

    private ResolvedAttachment resolveFromFile(String filePath, String fileName) {
        if (allowedBase == null) {
            throw new PdfResolutionException(
                    "File-path PDF resolution is disabled — massmailer.pdf-inbox-base-path is not configured. " +
                    "Use Base64 mode (pdfBase64) instead.");
        }

        // ── Defence-in-depth path canonicalisation ──
        Path requested = Path.of(filePath).toAbsolutePath().normalize();
        if (!requested.startsWith(allowedBase)) {
            log.warn("Rejected PDF path outside allowed inbox: requested={} allowedBase={}",
                    requested, allowedBase);
            throw new PdfResolutionException(
                    "PDF path is outside the allowed inbox directory: " + filePath);
        }

        if (!Files.exists(requested)) {
            throw new PdfResolutionException("PDF file not found: " + filePath);
        }
        if (!Files.isRegularFile(requested)) {
            throw new PdfResolutionException("PDF path is not a regular file: " + filePath);
        }
        if (!Files.isReadable(requested)) {
            throw new PdfResolutionException("PDF file not readable: " + filePath);
        }

        try {
            long size = Files.size(requested);
            if (size > maxPdfBytes) {
                throw new PdfResolutionException(
                        "PDF exceeds maximum allowed size (" + size + " > " + maxPdfBytes + " bytes): " + filePath);
            }

            byte[] bytes = Files.readAllBytes(requested);
            validatePdfMagicBytes(bytes, filePath);
            validateFiscalisation(bytes, filePath);

            log.debug("Resolved PDF from file: {} ({} bytes)", filePath, bytes.length);
            return new ResolvedAttachment(
                    new ByteArrayResource(bytes),
                    deriveFileName(fileName, requested),
                    "application/pdf",
                    bytes.length
            );
        } catch (IOException e) {
            throw new PdfResolutionException("Failed to read PDF file: " + filePath, e);
        }
    }

    private ResolvedAttachment resolveFromBase64(String base64, String fileName) {
        // Reject oversized Base64 BEFORE decoding to avoid an OOM allocation.
        // 4 Base64 chars → 3 bytes, so encoded length ≈ ceil(4 * decoded / 3).
        long maxEncodedLen = (long) Math.ceil(4.0 * maxPdfBytes / 3.0);
        if (base64.length() > maxEncodedLen) {
            throw new PdfResolutionException(
                    "Base64 PDF payload exceeds maximum allowed size (encoded chars=" + base64.length()
                    + ", max=" + maxEncodedLen + ", decoded cap=" + maxPdfBytes + " bytes): " + fileName);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length > maxPdfBytes) {
                throw new PdfResolutionException(
                        "Decoded PDF exceeds maximum allowed size (" + bytes.length
                        + " > " + maxPdfBytes + " bytes): " + fileName);
            }
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

    public static class PdfResolutionException extends RuntimeException {
        public PdfResolutionException(String message) { super(message); }
        public PdfResolutionException(String message, Throwable cause) { super(message, cause); }
    }
}
