package com.ivamare.commandbus.e2e.payment;

import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;
import com.ivamare.commandbus.model.ReplyOutcome;
import com.ivamare.commandbus.pgmq.PgmqClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simulates an external payment network sending L1-L4 confirmation replies.
 *
 * <p>In production, an adapter would receive messages from the real payment network
 * and send replies. This simulator mimics that behavior for e2e testing.
 *
 * <p>The simulator:
 * <ul>
 *   <li>Schedules L1-L4 replies with configurable delays</li>
 *   <li>Supports probabilistic failures for each level</li>
 *   <li>Sends replies to the process reply queue</li>
 * </ul>
 */
@Component
public class PaymentNetworkSimulator {

    private static final Logger log = LoggerFactory.getLogger(PaymentNetworkSimulator.class);

    private final PgmqClient pgmqClient;
    private final PendingNetworkResponseRepository pendingNetworkResponseRepository;
    private final String replyQueue;
    private final ScheduledExecutorService scheduler;
    private final Random random = new Random();

    // Default delays for each level (milliseconds)
    private static final int L1_DELAY_MIN = 100;
    private static final int L1_DELAY_MAX = 500;
    private static final int L2_DELAY_MIN = 200;
    private static final int L2_DELAY_MAX = 800;
    private static final int L3_DELAY_MIN = 300;
    private static final int L3_DELAY_MAX = 1000;
    private static final int L4_DELAY_MIN = 400;
    private static final int L4_DELAY_MAX = 1200;

    public PaymentNetworkSimulator(
            PgmqClient pgmqClient,
            PendingNetworkResponseRepository pendingNetworkResponseRepository,
            @Value("${commandbus.process.payment.reply-queue:payments__process_replies}") String replyQueue) {
        this.pgmqClient = pgmqClient;
        this.pendingNetworkResponseRepository = pendingNetworkResponseRepository;
        this.replyQueue = replyQueue;
        this.scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "payment-network-sim");
            t.setDaemon(true);
            return t;
        });
        log.info("PaymentNetworkSimulator initialized, sending replies to queue: {}", replyQueue);
    }

    /**
     * Simulate payment confirmation flow (L1-L4 replies).
     *
     * @param commandId     Original SubmitPayment command ID
     * @param processId     Process ID (used as correlationId for routing)
     * @param stepBehavior  Behavior configuration for each level
     */
    public void simulatePaymentConfirmations(
            UUID commandId,
            UUID processId,
            PaymentStepBehavior stepBehavior) {

        log.info("Starting payment confirmation simulation for process {} (command={})",
            processId, commandId);

        // Schedule L1 reply
        int l1Delay = randomDelay(L1_DELAY_MIN, L1_DELAY_MAX);
        scheduler.schedule(() -> {
            boolean l1Success = sendLevelReply(commandId, processId, 1, stepBehavior.awaitL1());

            // If L1 succeeds, schedule L2
            if (l1Success) {
                int l2Delay = randomDelay(L2_DELAY_MIN, L2_DELAY_MAX);
                scheduler.schedule(() -> {
                    boolean l2Success = sendLevelReply(commandId, processId, 2, stepBehavior.awaitL2());

                    // If L2 succeeds, schedule L3
                    if (l2Success) {
                        int l3Delay = randomDelay(L3_DELAY_MIN, L3_DELAY_MAX);
                        scheduler.schedule(() -> {
                            boolean l3Success = sendLevelReply(commandId, processId, 3, stepBehavior.awaitL3());

                            // If L3 succeeds (not pending/failed), schedule L4
                            if (l3Success) {
                                int l4Delay = randomDelay(L4_DELAY_MIN, L4_DELAY_MAX);
                                scheduler.schedule(() -> {
                                    sendLevelReply(commandId, processId, 4, stepBehavior.awaitL4());
                                }, l4Delay, TimeUnit.MILLISECONDS);
                            }
                        }, l3Delay, TimeUnit.MILLISECONDS);
                    }
                }, l2Delay, TimeUnit.MILLISECONDS);
            }
        }, l1Delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Send a level confirmation reply.
     *
     * <p>For L3 and L4, if pendingPct is configured, the response may be placed
     * in the pending network response queue for operator intervention instead
     * of being sent automatically.
     *
     * @return true if a success reply was sent (next level should be scheduled),
     *         false if failed, timed out, or placed in pending queue
     */
    private boolean sendLevelReply(UUID commandId, UUID processId, int level, ProbabilisticBehavior behavior) {
        try {
            // Apply probabilistic behavior
            double roll = random.nextDouble() * 100;
            double cumulative = 0;

            // Check for failure
            cumulative += behavior.failPermanentPct();
            if (roll < cumulative) {
                sendFailureReply(commandId, processId, level, "PERM_ERROR", "Permanent network failure at L" + level);
                return false;
            }

            cumulative += behavior.failTransientPct();
            if (roll < cumulative) {
                sendFailureReply(commandId, processId, level, "TRANS_ERROR", "Transient network failure at L" + level);
                return false;
            }

            cumulative += behavior.failBusinessRulePct();
            if (roll < cumulative) {
                sendBusinessRuleFailureReply(commandId, processId, level, "BIZ_RULE", "Business rule failure at L" + level);
                return false;
            }

            cumulative += behavior.timeoutPct();
            if (roll < cumulative) {
                // Timeout: don't send reply, let process timeout
                log.info("Simulating timeout at L{} for process {}", level, processId);
                return false;
            }

            // Check for pending (only for L3 and L4)
            if ((level == 3 || level == 4) && behavior.pendingPct() > 0) {
                cumulative += behavior.pendingPct();
                if (roll < cumulative) {
                    // Create pending network response entry
                    PendingNetworkResponse pending = PendingNetworkResponse.create(
                        null,  // paymentId not available at this level
                        processId,
                        processId,  // correlationId
                        commandId,
                        level
                    );
                    pendingNetworkResponseRepository.save(pending);

                    log.info("L{} response for process {} placed in pending queue (pending_id={})",
                        level, processId, pending.id());
                    return false;  // Don't send reply - operator must approve
                }
            }

            // Success
            sendSuccessReply(commandId, processId, level);
            return true;

        } catch (Exception e) {
            log.error("Failed to send L{} reply for process {}", level, processId, e);
            return false;
        }
    }

    private void sendSuccessReply(UUID commandId, UUID processId, int level) {
        Map<String, Object> reply = new HashMap<>();
        reply.put("command_id", commandId.toString());
        reply.put("correlation_id", processId.toString());
        reply.put("outcome", ReplyOutcome.SUCCESS.getValue());
        reply.put("data", Map.of(
            "level", level,
            "status", "SUCCESS",
            "reference", "L" + level + "-REF-" + UUID.randomUUID().toString().substring(0, 8),
            "timestamp", java.time.Instant.now().toString()
        ));

        pgmqClient.send(replyQueue, reply);
        pgmqClient.notify(replyQueue);

        log.info("Sent L{} success reply for process {} (command={})", level, processId, commandId);
    }

    private void sendFailureReply(UUID commandId, UUID processId, int level, String errorCode, String errorMessage) {
        Map<String, Object> reply = new HashMap<>();
        reply.put("command_id", commandId.toString());
        reply.put("correlation_id", processId.toString());
        reply.put("outcome", ReplyOutcome.FAILED.getValue());
        reply.put("error_type", "PERMANENT");
        reply.put("error_code", errorCode);
        reply.put("error_message", errorMessage);
        reply.put("data", Map.of(
            "level", level,
            "status", "FAILED"
        ));

        pgmqClient.send(replyQueue, reply);
        pgmqClient.notify(replyQueue);

        log.info("Sent L{} failure reply for process {} (code={})", level, processId, errorCode);
    }

    private void sendBusinessRuleFailureReply(UUID commandId, UUID processId, int level, String errorCode, String errorMessage) {
        Map<String, Object> reply = new HashMap<>();
        reply.put("command_id", commandId.toString());
        reply.put("correlation_id", processId.toString());
        reply.put("outcome", ReplyOutcome.FAILED.getValue());
        reply.put("error_type", "BUSINESS_RULE");
        reply.put("error_code", errorCode);
        reply.put("error_message", errorMessage);
        reply.put("data", Map.of(
            "level", level,
            "status", "FAILED"
        ));

        pgmqClient.send(replyQueue, reply);
        pgmqClient.notify(replyQueue);

        log.info("Sent L{} business rule failure reply for process {} (code={})", level, processId, errorCode);
    }

    private int randomDelay(int min, int max) {
        return min + random.nextInt(Math.max(1, max - min));
    }

    /**
     * Send a success reply for a pending network response.
     * Called when operator approves a pending L3/L4 response.
     *
     * <p>If L3 is approved, L4 is automatically scheduled after a delay.
     * If L4 is approved, that completes the network confirmation chain.
     *
     * @param commandId Original command ID
     * @param processId Process ID (correlationId)
     * @param level Network confirmation level (3 or 4)
     */
    public void sendApprovedReply(UUID commandId, UUID processId, int level) {
        sendSuccessReply(commandId, processId, level);

        // If L3 was approved, schedule L4 automatically
        if (level == 3) {
            int l4Delay = randomDelay(L4_DELAY_MIN, L4_DELAY_MAX);
            scheduler.schedule(() -> {
                // Check if there's already a pending L4 for this process
                var pendingL4 = pendingNetworkResponseRepository.findByProcessIdAndLevel(processId, 4);
                if (pendingL4.isEmpty()) {
                    // No pending L4, send success directly
                    sendSuccessReply(commandId, processId, 4);
                }
                // If there's a pending L4, operator must approve it separately
            }, l4Delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Send a failure reply for a pending network response.
     * Called when operator rejects a pending L3/L4 response.
     *
     * @param commandId Original command ID
     * @param processId Process ID (correlationId)
     * @param level Network confirmation level (3 or 4)
     */
    public void sendRejectedReply(UUID commandId, UUID processId, int level) {
        sendBusinessRuleFailureReply(commandId, processId, level,
            "OPERATOR_REJECTED", "Network response rejected by operator at L" + level);
    }

    /**
     * Send an approval reply for a pending risk approval.
     * Called when operator approves a manual risk approval request.
     *
     * @param commandId Original BookTransactionRisk command ID
     * @param processId Process ID (correlationId)
     */
    public void sendApprovalApprovedReply(UUID commandId, UUID processId) {
        Map<String, Object> reply = new HashMap<>();
        reply.put("command_id", commandId.toString());
        reply.put("correlation_id", processId.toString());
        reply.put("outcome", ReplyOutcome.SUCCESS.getValue());
        reply.put("data", Map.of(
            "status", "APPROVED",
            "method", "MANUAL_APPROVED",
            "approved_by", "operator",
            "timestamp", java.time.Instant.now().toString()
        ));

        pgmqClient.send(replyQueue, reply);
        pgmqClient.notify(replyQueue);

        log.info("Sent approval success reply for process {} (command={})", processId, commandId);
    }

    /**
     * Send a rejection reply for a pending risk approval.
     * Called when operator rejects a manual risk approval request.
     *
     * @param commandId Original BookTransactionRisk command ID
     * @param processId Process ID (correlationId)
     * @param reason Rejection reason
     */
    public void sendApprovalRejectedReply(UUID commandId, UUID processId, String reason) {
        Map<String, Object> reply = new HashMap<>();
        reply.put("command_id", commandId.toString());
        reply.put("correlation_id", processId.toString());
        reply.put("outcome", ReplyOutcome.FAILED.getValue());
        reply.put("error_type", "BUSINESS_RULE");
        reply.put("error_code", "APPROVAL_REJECTED");
        reply.put("error_message", reason != null ? reason : "Risk approval rejected by operator");
        reply.put("data", Map.of(
            "status", "REJECTED",
            "rejected_by", "operator",
            "timestamp", java.time.Instant.now().toString()
        ));

        pgmqClient.send(replyQueue, reply);
        pgmqClient.notify(replyQueue);

        log.info("Sent approval rejection reply for process {} (command={})", processId, commandId);
    }

    /**
     * Shutdown the simulator.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
