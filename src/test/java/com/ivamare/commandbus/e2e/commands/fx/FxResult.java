package com.ivamare.commandbus.e2e.commands.fx;

import java.math.BigDecimal;

/**
 * Result of a successful FX booking.
 *
 * @param contractId The FX contract ID
 * @param rate The exchange rate applied
 * @param convertedAmount The amount after conversion
 * @param sourceCurrency The source currency
 * @param targetCurrency The target currency
 */
public record FxResult(
    Long contractId,
    BigDecimal rate,
    BigDecimal convertedAmount,
    String sourceCurrency,
    String targetCurrency
) {
    /**
     * Create a new FX result.
     */
    public static FxResult of(
            Long contractId,
            BigDecimal rate,
            BigDecimal convertedAmount,
            String sourceCurrency,
            String targetCurrency) {
        return new FxResult(contractId, rate, convertedAmount, sourceCurrency, targetCurrency);
    }
}
