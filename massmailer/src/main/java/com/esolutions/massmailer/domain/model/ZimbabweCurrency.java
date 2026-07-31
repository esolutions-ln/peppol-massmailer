package com.esolutions.massmailer.domain.model;

/**
 * Zimbabwe-relevant ISO 4217 currency codes.
 *
 * Zimbabwe operates a multi-currency environment. ZIMRA fiscalised invoices
 * must clearly state the transaction currency. The most common are:
 *
 *   USD  — US Dollar          (dominant trade currency)
 *   ZWG  — Zimbabwe Gold      (introduced April 2024, replaced ZWL)
 *   ZAR  — South African Rand (widely accepted)
 *   GBP  — British Pound
 *   EUR  — Euro
 *
 * Historical (no longer legal tender but may appear in legacy data):
 *   ZWL  — Zimbabwe Dollar (2019–2024, replaced by ZWG)
 *   ZWD  — Zimbabwe Dollar (pre-2009 hyperinflation era)
 *
 * Reference: RBZ (Reserve Bank of Zimbabwe) and ZIMRA VAT Act
 */
public enum ZimbabweCurrency {

    // All 7 required ISO 4217 codes are present (Requirements 13.1, 13.2):
    // USD, ZWG, ZAR, GBP, EUR, CNY, BWP — each mapped to a non-null, non-empty symbol.
    // symbolFor(null) and symbolFor("") both return "" without throwing (see symbolFor() below).
    USD("US Dollar",          "$",    2),
    ZWG("Zimbabwe Gold",      "ZiG",  2),
    ZAR("South African Rand", "R",    2),
    GBP("British Pound",      "£",    2),
    EUR("Euro",               "€",    2),
    CNY("Chinese Yuan",       "¥",    2),
    BWP("Botswana Pula",      "P",    2),

    // Legacy — kept for historical invoice processing
    ZWL("Zimbabwe Dollar (legacy 2019-2024)", "ZWL", 2);

    public final String displayName;
    /** Commonly used symbol for display in emails and PDFs */
    public final String symbol;
    /** Standard decimal places for this currency */
    public final int decimalPlaces;

    ZimbabweCurrency(String displayName, String symbol, int decimalPlaces) {
        this.displayName = displayName;
        this.symbol = symbol;
        this.decimalPlaces = decimalPlaces;
    }

    /**
     * Returns the symbol for a given ISO 4217 code, or the code itself if unknown.
     * Safe to call with any string — never throws.
     */
    public static String symbolFor(String isoCode) {
        if (isoCode == null || isoCode.isBlank()) return "";
        try {
            return ZimbabweCurrency.valueOf(isoCode.toUpperCase()).symbol;
        } catch (IllegalArgumentException e) {
            return isoCode; // unknown currency — return the code as-is
        }
    }

    /**
     * Returns true if the given ISO code is a known Zimbabwe-context currency.
     */
    public static boolean isKnown(String isoCode) {
        if (isoCode == null) return false;
        try {
            ZimbabweCurrency.valueOf(isoCode.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
