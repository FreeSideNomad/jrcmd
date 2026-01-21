package com.ivamare.commandbus.e2e.commands.network;

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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Command handler for payment submission to the network.
 *
 * <p>This handler processes SubmitPayment commands from the payments domain.
 * It simulates various outcomes based on the ExpectedOutcome field in the command:
 * <ul>
 *   <li>SUCCESS - Returns submission reference and status</li>
 *   <li>TIMEOUT - Simulates network timeout (blocks for 60s)</li>
 *   <li>TRANSIENT_FAILURE - Throws TransientCommandException (will retry)</li>
 *   <li>PERMANENT_FAILURE - Throws PermanentCommandException (goes to TSQ)</li>
 *   <li>BUSINESS_ERROR - Throws BusinessRuleException (triggers compensation)</li>
 * </ul>
 *
 * <p>This handler is designed to run in the network domain worker, receiving commands
 * from the payments domain's ProcessStepManager via commandStep().
 *
 * <p>Note: This handler only handles the initial submission. L1-L4 confirmations
 * are sent asynchronously by a separate network simulator and processed by the
 * process's wait() conditions.
 */
public class SubmitPaymentHandler {

    private static final Logger log = LoggerFactory.getLogger(SubmitPaymentHandler.class);

    public static final String DOMAIN = "network";
    public static final String COMMAND_TYPE = "SubmitPayment";

    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    public SubmitPaymentHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Register the handler with a HandlerRegistry.
     *
     * @param registry The handler registry
     */
    public void register(HandlerRegistry registry) {
        registry.register(DOMAIN, COMMAND_TYPE, this::handleSubmitPayment);
        log.info("Registered {} handler in {} domain", COMMAND_TYPE, DOMAIN);
    }

    /**
     * Handle a SubmitPayment command.
     *
     * @param command The command to process
     * @param context The handler context
     * @return The result map (serialized SubmitPaymentResult)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleSubmitPayment(Command command, HandlerContext context) {
        log.info("Processing SubmitPayment command: {}", command.commandId());

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
                throw new TransientCommandException("NETWORK_UNAVAILABLE",
                    "Payment network temporarily unavailable");
            }
            case PERMANENT_FAILURE -> {
                log.info("Simulating permanent failure for command {}", command.commandId());
                throw new PermanentCommandException("INVALID_PAYMENT_DETAILS",
                    "Payment details are invalid or account does not exist");
            }
            case BUSINESS_ERROR -> {
                log.info("Simulating business error for command {}", command.commandId());
                throw new BusinessRuleException("ACCOUNT_BLOCKED",
                    "Debit account is blocked or has insufficient funds");
            }
            case SUCCESS -> {
                // Continue with normal processing
                log.debug("Processing SubmitPayment with SUCCESS outcome");
            }
        }

        // Extract payment ID
        String paymentIdStr = (String) requestData.get("paymentId");
        UUID paymentId = paymentIdStr != null ? UUID.fromString(paymentIdStr) : UUID.randomUUID();

        // Generate submission reference
        String submissionReference = "NET-" + System.currentTimeMillis() + "-" +
            UUID.randomUUID().toString().substring(0, 8);

        // Add some realistic delay
        simulateDuration(100, 500);

        // Create result
        SubmitPaymentResult result = SubmitPaymentResult.submitted(
            submissionReference,
            command.commandId()
        );

        log.info("Payment {} submitted to network with reference {}",
            paymentId, submissionReference);

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
     * Convert SubmitPaymentResult to map for response.
     */
    private Map<String, Object> resultToMap(SubmitPaymentResult result) {
        Map<String, Object> map = new HashMap<>();
        map.put("submissionReference", result.submissionReference());
        map.put("commandId", result.commandId().toString());
        map.put("status", result.status());
        map.put("submittedAt", result.submittedAt().toString());
        return map;
    }
}
