package com.ivamare.commandbus.e2e.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.model.Command;
import com.ivamare.commandbus.model.HandlerContext;
import com.ivamare.commandbus.pgmq.PgmqClient;
import com.ivamare.commandbus.e2e.commands.fx.BookFxHandler;
import com.ivamare.commandbus.e2e.commands.network.SubmitPaymentHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Command handlers for the payment_process domain.
 *
 * <p>This domain handles external service calls from PaymentCommandStepProcess:
 * <ul>
 *   <li>BookFx - FX booking simulation</li>
 *   <li>SubmitPayment - Payment network submission simulation</li>
 * </ul>
 *
 * <p>Using a common domain for both command types simplifies the E2E setup
 * by requiring only one worker instead of separate fx and network workers.
 *
 * <p>Only active when running with the 'worker' profile (not 'ui' only mode).
 */
@Component
@Profile("!ui")
public class PaymentProcessHandlers {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessHandlers.class);
    private static final String DOMAIN = "payment_process";

    private final HandlerRegistry handlerRegistry;
    private final PgmqClient pgmqClient;
    private final ObjectMapper objectMapper;

    // Delegate handlers for actual processing
    private final BookFxHandler bookFxHandler;
    private final SubmitPaymentHandler submitPaymentHandler;

    public PaymentProcessHandlers(
            HandlerRegistry handlerRegistry,
            PgmqClient pgmqClient,
            ObjectMapper objectMapper) {
        this.handlerRegistry = handlerRegistry;
        this.pgmqClient = pgmqClient;
        this.objectMapper = objectMapper;
        this.bookFxHandler = new BookFxHandler(objectMapper);
        this.submitPaymentHandler = new SubmitPaymentHandler(objectMapper);
    }

    @PostConstruct
    public void initialize() {
        // Create the queue if it doesn't exist
        createQueueIfNotExists();

        // Register handlers for both command types in the payment_process domain
        handlerRegistry.register(DOMAIN, "BookFx", this::handleBookFx);
        handlerRegistry.register(DOMAIN, "SubmitPayment", this::handleSubmitPayment);

        log.info("Registered payment_process domain handlers: BookFx, SubmitPayment");
    }

    private void createQueueIfNotExists() {
        String queueName = DOMAIN + "__commands";
        try {
            pgmqClient.createQueue(queueName);
            log.info("Created queue: {}", queueName);
        } catch (Exception e) {
            // Queue might already exist, which is fine
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                log.debug("Queue {} already exists", queueName);
            } else {
                log.warn("Error creating queue {}: {}", queueName, e.getMessage());
            }
        }
    }

    /**
     * Handle BookFx command - delegates to BookFxHandler.
     */
    public Map<String, Object> handleBookFx(Command command, HandlerContext context) {
        log.info("Processing BookFx in payment_process domain: {}", command.commandId());
        return bookFxHandler.handleBookFx(command, context);
    }

    /**
     * Handle SubmitPayment command - delegates to SubmitPaymentHandler.
     */
    public Map<String, Object> handleSubmitPayment(Command command, HandlerContext context) {
        log.info("Processing SubmitPayment in payment_process domain: {}", command.commandId());
        return submitPaymentHandler.handleSubmitPayment(command, context);
    }
}
