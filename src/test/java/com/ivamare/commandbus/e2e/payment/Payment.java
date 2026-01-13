package com.ivamare.commandbus.e2e.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payment entity representing an international or domestic payment.
 *
 * @param paymentId       Unique payment identifier
 * @param actionDate      Date payment was initiated
 * @param valueDate       Settlement date
 * @param debitCurrency   ISO 4217 currency code for debit
 * @param creditCurrency  ISO 4217 currency code for credit
 * @param debitAccount    Source account (transit + account number)
 * @param creditAccount   Destination account (BIC + IBAN)
 * @param debitAmount     Amount debited (18,2 precision)
 * @param creditAmount    Amount credited (18,2 precision), null until FX booked for cross-currency
 * @param fxContractId    FX contract reference (nullable, only for cross-currency)
 * @param fxRate          Exchange rate (12,6 precision, nullable)
 * @param status          Current payment status
 * @param cutoffTimestamp Payment must complete by this time
 * @param createdAt       Creation timestamp
 * @param updatedAt       Last update timestamp
 */
public record Payment(
    UUID paymentId,
    LocalDate actionDate,
    LocalDate valueDate,
    Currency debitCurrency,
    Currency creditCurrency,
    DebitAccount debitAccount,
    CreditAccount creditAccount,
    BigDecimal debitAmount,
    BigDecimal creditAmount,
    Long fxContractId,
    BigDecimal fxRate,
    PaymentStatus status,
    Instant cutoffTimestamp,
    Instant createdAt,
    Instant updatedAt
) {
    /**
     * Check if this payment requires FX (cross-currency).
     */
    public boolean requiresFx() {
        return debitCurrency != creditCurrency;
    }

    /**
     * Create a new payment with updated status.
     */
    public Payment withStatus(PaymentStatus newStatus) {
        return new Payment(
            paymentId, actionDate, valueDate, debitCurrency, creditCurrency,
            debitAccount, creditAccount, debitAmount, creditAmount,
            fxContractId, fxRate, newStatus, cutoffTimestamp, createdAt, Instant.now()
        );
    }

    /**
     * Create a new payment with FX details populated.
     */
    public Payment withFxDetails(Long fxContractId, BigDecimal fxRate, BigDecimal creditAmount) {
        return new Payment(
            paymentId, actionDate, valueDate, debitCurrency, creditCurrency,
            debitAccount, creditAccount, debitAmount, creditAmount,
            fxContractId, fxRate, status, cutoffTimestamp, createdAt, Instant.now()
        );
    }

    /**
     * Create a new payment with credit amount (for same-currency payments).
     */
    public Payment withCreditAmount(BigDecimal creditAmount) {
        return new Payment(
            paymentId, actionDate, valueDate, debitCurrency, creditCurrency,
            debitAccount, creditAccount, debitAmount, creditAmount,
            fxContractId, fxRate, status, cutoffTimestamp, createdAt, Instant.now()
        );
    }

    /**
     * Builder pattern for creating payments.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID paymentId;
        private LocalDate actionDate;
        private LocalDate valueDate;
        private Currency debitCurrency;
        private Currency creditCurrency;
        private DebitAccount debitAccount;
        private CreditAccount creditAccount;
        private BigDecimal debitAmount;
        private BigDecimal creditAmount;
        private Long fxContractId;
        private BigDecimal fxRate;
        private PaymentStatus status = PaymentStatus.DRAFT;
        private Instant cutoffTimestamp;
        private Instant createdAt;
        private Instant updatedAt;

        public Builder paymentId(UUID paymentId) {
            this.paymentId = paymentId;
            return this;
        }

        public Builder actionDate(LocalDate actionDate) {
            this.actionDate = actionDate;
            return this;
        }

        public Builder valueDate(LocalDate valueDate) {
            this.valueDate = valueDate;
            return this;
        }

        public Builder debitCurrency(Currency debitCurrency) {
            this.debitCurrency = debitCurrency;
            return this;
        }

        public Builder creditCurrency(Currency creditCurrency) {
            this.creditCurrency = creditCurrency;
            return this;
        }

        public Builder debitAccount(DebitAccount debitAccount) {
            this.debitAccount = debitAccount;
            return this;
        }

        public Builder creditAccount(CreditAccount creditAccount) {
            this.creditAccount = creditAccount;
            return this;
        }

        public Builder debitAmount(BigDecimal debitAmount) {
            this.debitAmount = debitAmount;
            return this;
        }

        public Builder creditAmount(BigDecimal creditAmount) {
            this.creditAmount = creditAmount;
            return this;
        }

        public Builder fxContractId(Long fxContractId) {
            this.fxContractId = fxContractId;
            return this;
        }

        public Builder fxRate(BigDecimal fxRate) {
            this.fxRate = fxRate;
            return this;
        }

        public Builder status(PaymentStatus status) {
            this.status = status;
            return this;
        }

        public Builder cutoffTimestamp(Instant cutoffTimestamp) {
            this.cutoffTimestamp = cutoffTimestamp;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Payment build() {
            if (paymentId == null) {
                paymentId = UUID.randomUUID();
            }
            if (actionDate == null) {
                actionDate = LocalDate.now();
            }
            if (valueDate == null) {
                valueDate = actionDate.plusDays(1);
            }
            if (createdAt == null) {
                createdAt = Instant.now();
            }
            if (updatedAt == null) {
                updatedAt = createdAt;
            }
            if (cutoffTimestamp == null) {
                // Default cutoff: end of value date in UTC
                cutoffTimestamp = valueDate.plusDays(1).atStartOfDay()
                    .atZone(java.time.ZoneOffset.UTC).toInstant();
            }
            // For same currency, credit amount equals debit amount
            if (!debitCurrency.equals(creditCurrency) && creditAmount == null) {
                // Cross-currency: creditAmount will be set after FX booking
            } else if (creditAmount == null) {
                creditAmount = debitAmount;
            }

            return new Payment(
                paymentId, actionDate, valueDate, debitCurrency, creditCurrency,
                debitAccount, creditAccount, debitAmount, creditAmount,
                fxContractId, fxRate, status, cutoffTimestamp, createdAt, updatedAt
            );
        }
    }
}
