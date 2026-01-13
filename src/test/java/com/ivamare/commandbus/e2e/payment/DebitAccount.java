package com.ivamare.commandbus.e2e.payment;

import java.util.Map;

/**
 * Debit account value object representing a source account for payment.
 *
 * @param transit       5-digit transit number with leading zeros (e.g., "00123")
 * @param accountNumber Account number
 */
public record DebitAccount(
    String transit,
    String accountNumber
) {
    public DebitAccount {
        if (transit == null || !transit.matches("\\d{5}")) {
            throw new IllegalArgumentException("Transit must be 5 digits, got: " + transit);
        }
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("Account number is required");
        }
    }

    /**
     * Create a DebitAccount from transit and account number strings.
     */
    public static DebitAccount of(String transit, String accountNumber) {
        return new DebitAccount(transit, accountNumber);
    }

    public Map<String, Object> toMap() {
        return Map.of(
            "transit", transit,
            "account_number", accountNumber
        );
    }

    public static DebitAccount fromMap(Map<String, Object> map) {
        if (map == null) return null;
        return new DebitAccount(
            (String) map.get("transit"),
            (String) map.get("account_number")
        );
    }

    @Override
    public String toString() {
        return transit + "-" + accountNumber;
    }
}
