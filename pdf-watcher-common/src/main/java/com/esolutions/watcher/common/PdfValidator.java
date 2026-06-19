package com.esolutions.watcher.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PdfValidator {

    private static final Logger log = LoggerFactory.getLogger(PdfValidator.class);

    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46, 0x2D};

    public static boolean isValidPdf(byte[] bytes, String source) {
        if (bytes == null || bytes.length < 5) {
            log.warn("PDF too small or null: {} ({} bytes)", source, bytes != null ? bytes.length : 0);
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                log.warn("Invalid PDF magic bytes: {}", source);
                return false;
            }
        }
        return true;
    }

    public static void assertValidPdf(byte[] bytes, String source) {
        if (!isValidPdf(bytes, source)) {
            throw new IllegalArgumentException(
                    "File does not have valid PDF magic bytes (%%PDF-): " + source);
        }
    }
}
