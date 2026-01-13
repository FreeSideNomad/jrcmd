package com.ivamare.commandbus.e2e.payment;

import java.util.Map;

/**
 * Credit account value object representing a destination account for payment.
 * Uses SWIFT/BIC and IBAN for international payments.
 *
 * @param bic  SWIFT/BIC code (8 or 11 characters)
 * @param iban IBAN (up to 34 alphanumeric characters)
 */
public record CreditAccount(
    String bic,
    String iban
) {
    private static final String BIC_PATTERN = "[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?";
    private static final String IBAN_PATTERN = "[A-Z]{2}\\d{2}[A-Z0-9]{1,30}";

    public CreditAccount {
        if (bic == null || !bic.matches(BIC_PATTERN)) {
            throw new IllegalArgumentException(
                "Invalid BIC format. Must be 8 or 11 alphanumeric characters (e.g., DEUTDEFF or DEUTDEFFXXX), got: " + bic);
        }
        if (iban == null || !iban.matches(IBAN_PATTERN)) {
            throw new IllegalArgumentException(
                "Invalid IBAN format. Must start with 2-letter country code, 2 check digits, then up to 30 alphanumeric characters, got: " + iban);
        }
    }

    /**
     * Create a CreditAccount from BIC and IBAN strings.
     */
    public static CreditAccount of(String bic, String iban) {
        return new CreditAccount(bic, iban);
    }

    public Map<String, Object> toMap() {
        return Map.of(
            "bic", bic,
            "iban", iban
        );
    }

    public static CreditAccount fromMap(Map<String, Object> map) {
        if (map == null) return null;
        return new CreditAccount(
            (String) map.get("bic"),
            (String) map.get("iban")
        );
    }

    @Override
    public String toString() {
        return bic + " / " + iban;
    }
}
