package com.esolutions.massmailer.domain;

import com.esolutions.massmailer.domain.model.ZimbabweCurrency;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Property-based tests for ZimbabweCurrency symbol mapping.
 *
 * P12: Currency Symbol Mapping — validates Requirements 13.1, 13.2, 13.3
 */
class ZimbabweCurrencyPropertyTest {

    private static final List<String> SUPPORTED_CODES =
            List.of("USD", "ZWG", "ZAR", "GBP", "EUR", "CNY", "BWP");

    // ── Property P12: Currency Symbol Mapping ────────────────────────────────
    // Validates: Requirements 13.1, 13.2, 13.3

    /**
     * P12a — For each of the 7 supported ISO codes, symbolFor() returns a non-null,
     * non-empty string.
     *
     * **Validates: Requirements 13.1, 13.2**
     */
    @Property
    void supportedCodesHaveNonEmptySymbols(
            @ForAll("supportedIsoCodes") String isoCode
    ) {
        String symbol = ZimbabweCurrency.symbolFor(isoCode);

        assertThat(symbol)
                .as("symbolFor(\"%s\") must not be null", isoCode)
                .isNotNull();
        assertThat(symbol)
                .as("symbolFor(\"%s\") must not be empty", isoCode)
                .isNotEmpty();
    }

    /**
     * P12b — symbolFor(null) returns "" without throwing.
     *
     * **Validates: Requirements 13.2**
     */
    @Property
    void symbolForNullReturnsEmptyStringWithoutThrowing() {
        assertThatCode(() -> ZimbabweCurrency.symbolFor(null))
                .as("symbolFor(null) must not throw")
                .doesNotThrowAnyException();

        String result = ZimbabweCurrency.symbolFor(null);
        assertThat(result)
                .as("symbolFor(null) must return \"\"")
                .isEqualTo("");
    }

    /**
     * P12c — symbolFor("") returns "" without throwing.
     *
     * **Validates: Requirements 13.2**
     */
    @Property
    void symbolForEmptyStringReturnsEmptyStringWithoutThrowing() {
        assertThatCode(() -> ZimbabweCurrency.symbolFor(""))
                .as("symbolFor(\"\") must not throw")
                .doesNotThrowAnyException();

        String result = ZimbabweCurrency.symbolFor("");
        assertThat(result)
                .as("symbolFor(\"\") must return \"\"")
                .isEqualTo("");
    }

    /**
     * P12d — symbolFor("UNKNOWN") returns the code itself (graceful fallback).
     *
     * For any unrecognised ISO code, symbolFor() must return the code as-is
     * rather than null or throwing.
     *
     * **Validates: Requirements 13.3**
     */
    @Property
    void symbolForUnknownCodeReturnsCodeItself() {
        String result = ZimbabweCurrency.symbolFor("UNKNOWN");

        assertThat(result)
                .as("symbolFor(\"UNKNOWN\") must return the code itself as graceful fallback")
                .isEqualTo("UNKNOWN");
    }

    /**
     * P12e — For any arbitrary non-blank, non-supported code, symbolFor() returns
     * the code itself without throwing (general graceful fallback property).
     *
     * **Validates: Requirements 13.3**
     */
    @Property
    void symbolForArbitraryUnknownCodeNeverThrowsAndReturnsFallback(
            @ForAll("unknownIsoCodes") String unknownCode
    ) {
        assertThatCode(() -> ZimbabweCurrency.symbolFor(unknownCode))
                .as("symbolFor(\"%s\") must not throw for unknown code", unknownCode)
                .doesNotThrowAnyException();

        String result = ZimbabweCurrency.symbolFor(unknownCode);
        assertThat(result)
                .as("symbolFor(\"%s\") must return the code itself as fallback", unknownCode)
                .isEqualTo(unknownCode.toUpperCase());
    }

    // ── Arbitraries ──────────────────────────────────────────────────────────

    /** Generates one of the 7 supported ISO 4217 codes. */
    @Provide
    Arbitrary<String> supportedIsoCodes() {
        return Arbitraries.of(SUPPORTED_CODES);
    }

    /**
     * Generates arbitrary uppercase alphabetic strings that are NOT known currency codes.
     * These represent unknown ISO codes that should trigger the graceful fallback.
     */
    @Provide
    Arbitrary<String> unknownIsoCodes() {
        return Arbitraries.strings()
                .withCharRange('A', 'Z')
                .ofMinLength(2)
                .ofMaxLength(5)
                .filter(code -> !ZimbabweCurrency.isKnown(code));
    }
}
