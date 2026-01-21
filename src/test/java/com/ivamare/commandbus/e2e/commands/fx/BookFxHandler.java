package com.ivamare.commandbus.e2e.commands.fx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivamare.commandbus.exception.BusinessRuleException;
import com.ivamare.commandbus.exception.PermanentCommandException;
import com.ivamare.commandbus.exception.TransientCommandException;
import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.model.Command;
import com.ivamare.commandbus.model.HandlerContext;
import com.ivamare.commandbus.process.step.ExpectedOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Command handler for FX booking operations.
 *
 * <p>This handler processes BookFx commands from the payments domain.
 * It simulates various outcomes based on the ExpectedOutcome field in the command:
 * <ul>
 *   <li>SUCCESS - Returns FX rate and contract ID</li>
 *   <li>TIMEOUT - Simulates network timeout (blocks for 60s)</li>
 *   <li>TRANSIENT_FAILURE - Throws TransientCommandException (will retry)</li>
 *   <li>PERMANENT_FAILURE - Throws PermanentCommandException (goes to TSQ)</li>
 *   <li>BUSINESS_ERROR - Throws BusinessRuleException (triggers compensation)</li>
 * </ul>
 *
 * <p>This handler is designed to run in the FX domain worker, receiving commands
 * from the payments domain's ProcessStepManager via commandStep().
 */
public class BookFxHandler {

    private static final Logger log = LoggerFactory.getLogger(BookFxHandler.class);

    public static final String DOMAIN = "fx";
    public static final String COMMAND_TYPE = "BookFx";

    private final ObjectMapper objectMapper;
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

    public BookFxHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Register the handler with a HandlerRegistry.
     *
     * @param registry The handler registry
     */
    public void register(HandlerRegistry registry) {
        registry.register(DOMAIN, COMMAND_TYPE, this::handleBookFx);
        log.info("Registered {} handler in {} domain", COMMAND_TYPE, DOMAIN);
    }

    /**
     * Handle a BookFx command.
     *
     * @param command The command to process
     * @param context The handler context
     * @return The result map (serialized FxResult)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleBookFx(Command command, HandlerContext context) {
        log.info("Processing BookFx command: {}", command.commandId());

        Map<String, Object> data = command.data();

        // Extract the request data from the nested structure
        Map<String, Object> requestData = (Map<String, Object>) data.get("request");
        if (requestData == null) {
            requestData = data;  // Fallback for direct data
        }

        // Parse the expected outcome
        ExpectedOutcome outcome = parseExpectedOutcome(requestData);

        // Simulate based on expected outcome
        switch (outcome) {
            case TIMEOUT -> {
                log.info("Simulating timeout for command {}", command.commandId());
                simulateTimeout();
            }
            case TRANSIENT_FAILURE -> {
                log.info("Simulating transient failure for command {}", command.commandId());
                throw new TransientCommandException("FX_SERVICE_UNAVAILABLE",
                    "FX service temporarily unavailable");
            }
            case PERMANENT_FAILURE -> {
                log.info("Simulating permanent failure for command {}", command.commandId());
                throw new PermanentCommandException("INVALID_CURRENCY_PAIR",
                    "Currency pair not supported for FX conversion");
            }
            case BUSINESS_ERROR -> {
                log.info("Simulating business error for command {}", command.commandId());
                throw new BusinessRuleException("INSUFFICIENT_MARGIN",
                    "Insufficient margin for FX booking");
            }
            case SUCCESS -> {
                // Continue with normal processing
                log.debug("Processing BookFx with SUCCESS outcome");
            }
        }

        // Extract request fields
        String sourceCurrency = (String) requestData.get("sourceCurrency");
        String targetCurrency = (String) requestData.get("targetCurrency");
        BigDecimal amount = parseAmount(requestData.get("amount"));

        if (sourceCurrency == null) sourceCurrency = "USD";
        if (targetCurrency == null) targetCurrency = "EUR";
        if (amount == null) amount = BigDecimal.ZERO;

        // Calculate FX rate and convert amount
        BigDecimal fxRate = calculateFxRate(sourceCurrency, targetCurrency);
        BigDecimal convertedAmount = amount.multiply(fxRate).setScale(2, RoundingMode.HALF_UP);
        Long contractId = Math.abs(random.nextLong()) % 1000000;

        // Add some realistic delay
        simulateDuration(50, 200);

        // Create result
        FxResult result = FxResult.of(
            contractId,
            fxRate,
            convertedAmount,
            sourceCurrency,
            targetCurrency
        );

        log.info("FX booking completed: {} {} -> {} {} at rate {} (contract={})",
            amount, sourceCurrency, convertedAmount, targetCurrency, fxRate, contractId);

        // Convert to map for response
        return resultToMap(result);
    }

    /**
     * Parse ExpectedOutcome from request data.
     */
    private ExpectedOutcome parseExpectedOutcome(Map<String, Object> requestData) {
        Object outcomeValue = requestData.get("expectedOutcome");
        if (outcomeValue == null) {
            return ExpectedOutcome.SUCCESS;
        }

        if (outcomeValue instanceof String outcomeStr) {
            try {
                return ExpectedOutcome.valueOf(outcomeStr);
            } catch (IllegalArgumentException e) {
                return ExpectedOutcome.SUCCESS;
            }
        }

        return ExpectedOutcome.SUCCESS;
    }

    /**
     * Parse amount from various types.
     */
    private BigDecimal parseAmount(Object amountValue) {
        if (amountValue == null) {
            return BigDecimal.ZERO;
        }
        if (amountValue instanceof BigDecimal bd) {
            return bd;
        }
        if (amountValue instanceof Number num) {
            return BigDecimal.valueOf(num.doubleValue());
        }
        if (amountValue instanceof String str) {
            try {
                return new BigDecimal(str);
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Calculate FX rate between currencies.
     */
    private BigDecimal calculateFxRate(String fromCurrency, String toCurrency) {
        BigDecimal fromRate = FX_RATES.getOrDefault(fromCurrency, BigDecimal.ONE);
        BigDecimal toRate = FX_RATES.getOrDefault(toCurrency, BigDecimal.ONE);
        return toRate.divide(fromRate, 6, RoundingMode.HALF_UP);
    }

    /**
     * Simulate timeout by blocking.
     */
    private void simulateTimeout() {
        try {
            // Block for longer than typical visibility timeout
            Thread.sleep(60000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Simulate processing duration.
     */
    private void simulateDuration(int minMs, int maxMs) {
        int duration = minMs + random.nextInt(Math.max(1, maxMs - minMs));
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Convert FxResult to map for response.
     */
    private Map<String, Object> resultToMap(FxResult result) {
        Map<String, Object> map = new HashMap<>();
        map.put("contractId", result.contractId());
        map.put("rate", result.rate().toString());
        map.put("convertedAmount", result.convertedAmount().toString());
        map.put("sourceCurrency", result.sourceCurrency());
        map.put("targetCurrency", result.targetCurrency());
        return map;
    }
}
