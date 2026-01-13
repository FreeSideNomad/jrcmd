package com.ivamare.commandbus.e2e.payment;

import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;
import com.ivamare.commandbus.exception.BusinessRuleException;
import com.ivamare.commandbus.exception.PermanentCommandException;
import com.ivamare.commandbus.exception.TransientCommandException;
import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.model.Command;
import com.ivamare.commandbus.model.HandlerContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Command handlers for the payments domain.
 *
 * <p>Handles the payment processing workflow:
 * <ul>
 *   <li>BookTransactionRisk - Risk assessment and approval</li>
 *   <li>BookFx - Foreign exchange booking</li>
 *   <li>SubmitPayment - Submit payment to network</li>
 *   <li>UnwindTransactionRisk - Reverse risk booking (compensation)</li>
 *   <li>UnwindFx - Reverse FX booking (compensation)</li>
 * </ul>
 *
 * <p>Only active when running with the 'worker' profile.
 */
@Component
@Profile("worker")
public class PaymentHandlers {

    private static final Logger log = LoggerFactory.getLogger(PaymentHandlers.class);
    private static final String DOMAIN = "payments";

    private final HandlerRegistry handlerRegistry;
    private final PaymentNetworkSimulator networkSimulator;
    private final PendingApprovalRepository pendingApprovalRepository;
    private final Random random = new Random();

    // Simulated FX rates (base currency USD)
    private static final Map<String, BigDecimal> FX_RATES = Map.of(
        "EUR", new BigDecimal("0.92"),
        "GBP", new BigDecimal("0.79"),
        "JPY", new BigDecimal("149.50"),
        "CHF", new BigDecimal("0.88"),
        "CAD", new BigDecimal("1.36"),
        "AUD", new BigDecimal("1.53"),
        "USD", BigDecimal.ONE
    );

    public PaymentHandlers(
            HandlerRegistry handlerRegistry,
            PaymentNetworkSimulator networkSimulator,
            PendingApprovalRepository pendingApprovalRepository) {
        this.handlerRegistry = handlerRegistry;
        this.networkSimulator = networkSimulator;
        this.pendingApprovalRepository = pendingApprovalRepository;
    }

    @PostConstruct
    public void registerHandlers() {
        handlerRegistry.register(DOMAIN, "BookTransactionRisk", this::handleBookTransactionRisk);
        handlerRegistry.register(DOMAIN, "BookFx", this::handleBookFx);
        handlerRegistry.register(DOMAIN, "SubmitPayment", this::handleSubmitPayment);
        handlerRegistry.register(DOMAIN, "UnwindTransactionRisk", this::handleUnwindTransactionRisk);
        handlerRegistry.register(DOMAIN, "UnwindFx", this::handleUnwindFx);
        log.info("Registered payments domain handlers: BookTransactionRisk, BookFx, SubmitPayment, UnwindTransactionRisk, UnwindFx");
    }

    /**
     * Handle BookTransactionRisk command - assess risk and reserve credit limit.
     *
     * <p>If manual approval is required, creates a pending approval entry and returns
     * PENDING_APPROVAL status. The process will wait at AWAIT_RISK_APPROVAL step
     * until an operator approves via the approval queue UI.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleBookTransactionRisk(Command command, HandlerContext context) {
        log.info("Processing BookTransactionRisk: {}", command.commandId());

        Map<String, Object> data = command.data();
        String paymentId = (String) data.get("payment_id");

        // Get risk-specific behavior if configured
        Map<String, Object> riskBehaviorMap = (Map<String, Object>) data.get("risk_behavior");
        RiskBehavior riskBehavior = riskBehaviorMap != null
            ? RiskBehavior.fromMap(riskBehaviorMap)
            : RiskBehavior.defaults();

        // Simulate processing duration
        simulateDuration(riskBehavior.minDurationMs(), riskBehavior.maxDurationMs());

        // Roll for approval method
        double roll = random.nextDouble() * 100;
        String approvalMethod = riskBehavior.determineApprovalMethod(roll);

        if (approvalMethod == null) {
            // Declined
            log.warn("Risk declined for payment {} (roll={})", paymentId, roll);
            throw new BusinessRuleException("RISK_DECLINED", "Transaction risk assessment declined");
        }

        String riskReference = "RISK-" + System.currentTimeMillis();

        // Manual approval - create pending approval entry
        if ("MANUAL".equals(approvalMethod)) {
            log.info("Payment {} requires manual approval, creating pending entry", paymentId);

            // Extract payment details for display
            BigDecimal amount = data.get("debit_amount") != null
                ? new BigDecimal(data.get("debit_amount").toString()) : BigDecimal.ZERO;
            String currency = (String) data.get("debit_currency");

            @SuppressWarnings("unchecked")
            Map<String, Object> debitAccountMap = (Map<String, Object>) data.get("debit_account");
            String debitAccount = debitAccountMap != null
                ? debitAccountMap.get("transit") + "-" + debitAccountMap.get("account_number")
                : "unknown";

            // Create pending approval entry
            PendingApproval pending = PendingApproval.create(
                UUID.fromString(paymentId),
                command.correlationId(),  // processId
                command.correlationId(),  // correlationId for routing reply
                command.commandId(),
                amount,
                currency != null ? currency : "USD",
                debitAccount,
                null  // creditAccount not critical for approval display
            );
            pendingApprovalRepository.save(pending);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "PENDING_APPROVAL");
            result.put("method", approvalMethod);
            result.put("risk_reference", riskReference);
            result.put("pending_approval_id", pending.id().toString());

            log.info("Pending approval {} created for payment {} (ref={})",
                pending.id(), paymentId, riskReference);
            return result;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "APPROVED");
        result.put("method", approvalMethod);
        result.put("risk_reference", riskReference);

        log.info("Risk approved for payment {} via {} (ref={})", paymentId, approvalMethod, riskReference);
        return result;
    }

    /**
     * Handle BookFx command - book FX contract for cross-currency payment.
     */
    public Map<String, Object> handleBookFx(Command command, HandlerContext context) {
        log.info("Processing BookFx: {}", command.commandId());

        evaluateBehavior(command);

        Map<String, Object> data = command.data();
        String paymentId = (String) data.get("payment_id");
        String debitCurrency = (String) data.get("debit_currency");
        String creditCurrency = (String) data.get("credit_currency");
        BigDecimal debitAmount = new BigDecimal((String) data.get("debit_amount"));

        // Calculate FX rate (simulated)
        BigDecimal fxRate = calculateFxRate(debitCurrency, creditCurrency);
        BigDecimal creditAmount = debitAmount.multiply(fxRate).setScale(2, RoundingMode.HALF_UP);
        Long fxContractId = System.currentTimeMillis();

        Map<String, Object> result = new HashMap<>();
        result.put("fx_contract_id", fxContractId);
        result.put("fx_rate", fxRate.toString());
        result.put("credit_amount", creditAmount.toString());

        log.info("FX booked for payment {}: {} {} -> {} {} at rate {} (contract={})",
            paymentId, debitAmount, debitCurrency, creditAmount, creditCurrency, fxRate, fxContractId);
        return result;
    }

    /**
     * Handle SubmitPayment command - submit payment to external network.
     * Triggers the PaymentNetworkSimulator to send L1-L4 replies.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleSubmitPayment(Command command, HandlerContext context) {
        log.info("Processing SubmitPayment: {}", command.commandId());

        evaluateBehavior(command);

        Map<String, Object> data = command.data();
        String paymentId = (String) data.get("payment_id");
        UUID correlationId = command.correlationId();

        String submissionReference = "SUB-" + System.currentTimeMillis();

        // Get behavior for L1-L4 simulation
        Map<String, Object> behaviorMap = (Map<String, Object>) data.get("behavior");
        PaymentStepBehavior stepBehavior = behaviorMap != null
            ? PaymentStepBehavior.fromMap(behaviorMap)
            : PaymentStepBehavior.defaults();

        // Trigger network simulator to send L1-L4 replies asynchronously
        networkSimulator.simulatePaymentConfirmations(
            command.commandId(),
            correlationId,
            stepBehavior
        );

        Map<String, Object> result = new HashMap<>();
        result.put("submission_reference", submissionReference);
        result.put("command_id", command.commandId().toString());
        result.put("status", "SUBMITTED");

        log.info("Payment {} submitted to network (ref={}, correlationId={})",
            paymentId, submissionReference, correlationId);
        return result;
    }

    /**
     * Handle UnwindTransactionRisk command - reverse risk booking.
     */
    public Map<String, Object> handleUnwindTransactionRisk(Command command, HandlerContext context) {
        log.info("Processing UnwindTransactionRisk: {}", command.commandId());

        evaluateBehavior(command);

        Map<String, Object> data = command.data();
        String paymentId = (String) data.get("payment_id");
        String riskReference = (String) data.get("risk_reference");

        // Simulate reversal
        simulateDuration(50, 150);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "REVERSED");
        result.put("risk_reference", riskReference);

        log.info("Risk reversed for payment {} (ref={})", paymentId, riskReference);
        return result;
    }

    /**
     * Handle UnwindFx command - reverse FX booking.
     */
    public Map<String, Object> handleUnwindFx(Command command, HandlerContext context) {
        log.info("Processing UnwindFx: {}", command.commandId());

        evaluateBehavior(command);

        Map<String, Object> data = command.data();
        String paymentId = (String) data.get("payment_id");
        Long fxContractId = data.get("fx_contract_id") != null
            ? ((Number) data.get("fx_contract_id")).longValue() : null;

        // Simulate reversal
        simulateDuration(50, 150);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "REVERSED");
        result.put("fx_contract_id", fxContractId);

        log.info("FX reversed for payment {} (contract={})", paymentId, fxContractId);
        return result;
    }

    private BigDecimal calculateFxRate(String fromCurrency, String toCurrency) {
        BigDecimal fromRate = FX_RATES.getOrDefault(fromCurrency, BigDecimal.ONE);
        BigDecimal toRate = FX_RATES.getOrDefault(toCurrency, BigDecimal.ONE);
        // Convert: amount_to = amount_from * (toRate / fromRate)
        return toRate.divide(fromRate, 6, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private void evaluateBehavior(Command command) {
        Map<String, Object> behaviorMap = (Map<String, Object>) command.data().get("behavior");
        if (behaviorMap == null) {
            simulateDuration(10, 100);
            return;
        }

        ProbabilisticBehavior behavior = ProbabilisticBehavior.fromMap(behaviorMap);

        double roll = random.nextDouble() * 100;
        double cumulative = 0;

        cumulative += behavior.failPermanentPct();
        if (roll < cumulative) {
            log.warn("Simulating permanent failure (roll={}, threshold={})", roll, cumulative);
            throw new PermanentCommandException("PERM_ERROR", "Simulated permanent error");
        }

        cumulative += behavior.failTransientPct();
        if (roll < cumulative) {
            log.warn("Simulating transient failure (roll={}, threshold={})", roll, cumulative);
            throw new TransientCommandException("TRANS_ERROR", "Simulated transient error");
        }

        cumulative += behavior.failBusinessRulePct();
        if (roll < cumulative) {
            log.warn("Simulating business rule failure (roll={}, threshold={})", roll, cumulative);
            throw new BusinessRuleException("BIZ_RULE", "Simulated business rule violation");
        }

        cumulative += behavior.timeoutPct();
        if (roll < cumulative) {
            log.warn("Simulating timeout (roll={}, threshold={})", roll, cumulative);
            try {
                Thread.sleep(60000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        simulateDuration(behavior.minDurationMs(), behavior.maxDurationMs());
    }

    private void simulateDuration(int minMs, int maxMs) {
        int duration = minMs + random.nextInt(Math.max(1, maxMs - minMs));
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
