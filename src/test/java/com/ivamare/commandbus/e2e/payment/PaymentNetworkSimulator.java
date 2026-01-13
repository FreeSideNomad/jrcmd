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
import java.util.concurrent.ExecutorService;
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
    private final ExecutorService executor;
    private final Random random = new Random();

    // Default delays for each level (milliseconds) - used when behavior not specified
    private static final int DEFAULT_L1_DELAY_MIN = 100;
    private static final int DEFAULT_L1_DELAY_MAX = 500;
    private static final int DEFAULT_L2_DELAY_MIN = 200;
    private static final int DEFAULT_L2_DELAY_MAX = 800;
    private static final int DEFAULT_L3_DELAY_MIN = 300;
    private static final int DEFAULT_L3_DELAY_MAX = 1000;
    private static final int DEFAULT_L4_DELAY_MIN = 400;
    private static final int DEFAULT_L4_DELAY_MAX = 1200;

    public PaymentNetworkSimulator(
            PgmqClient pgmqClient,
            PendingNetworkResponseRepository pendingNetworkResponseRepository,
            @Value("${commandbus.process.payment.reply-queue:payments__process_replies}") String replyQueue) {
        this.pgmqClient = pgmqClient;
        this.pendingNetworkResponseRepository = pendingNetworkResponseRepository;
        this.replyQueue = replyQueue;
        // Scheduler for realistic delays (when delays > 0)
        // Use 100 threads to handle high-volume batches without backlog
        this.scheduler = Executors.newScheduledThreadPool(100, r -> {
            Thread t = new Thread(r, "payment-network-scheduler");
            t.setDaemon(true);
            return t;
        });
        // Simple executor for fast path (when all delays = 0)
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "payment-network-exec");
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

        boolean zeroDelay = isZeroDelayMode(stepBehavior);
        log.debug("Starting payment confirmation simulation for process {} (command={}), zeroDelay={}",
            processId, commandId, zeroDelay);

        // Check if all delays are zero (fast path for performance testing)
        if (zeroDelay) {
            // Fast path: send all replies sequentially on executor thread
            executor.submit(() -> {
                try {
                    sendAllRepliesSequentially(commandId, processId, stepBehavior);
                } catch (Exception e) {
                    log.error("Exception in fast-path reply sender for process {}", processId, e);
                }
            });
        } else {
            // Normal path: use scheduler with delays
            scheduleL1(commandId, processId, stepBehavior);
        }
    }

    /**
     * Check if all L1-L4 delays are configured as zero (performance testing mode).
     */
    private boolean isZeroDelayMode(PaymentStepBehavior behavior) {
        return getDelay(behavior.awaitL1(), DEFAULT_L1_DELAY_MIN, DEFAULT_L1_DELAY_MAX) == 0
            && getDelay(behavior.awaitL2(), DEFAULT_L2_DELAY_MIN, DEFAULT_L2_DELAY_MAX) == 0
            && getDelay(behavior.awaitL3(), DEFAULT_L3_DELAY_MIN, DEFAULT_L3_DELAY_MAX) == 0
            && getDelay(behavior.awaitL4(), DEFAULT_L4_DELAY_MIN, DEFAULT_L4_DELAY_MAX) == 0;
    }

    /**
     * Fast path: send all L1-L4 replies sequentially without scheduling.
     * Used when all delays are 0 for performance testing.
     */
    private void sendAllRepliesSequentially(UUID commandId, UUID processId, PaymentStepBehavior stepBehavior) {
        try {
            // Small delay to ensure handler has returned and process has transitioned
            // to AWAIT_CONFIRMATIONS before we send replies
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!sendLevelReply(commandId, processId, 1, stepBehavior.awaitL1())) return;
        if (!sendLevelReply(commandId, processId, 2, stepBehavior.awaitL2())) return;
        if (!sendLevelReply(commandId, processId, 3, stepBehavior.awaitL3())) return;
        sendLevelReply(commandId, processId, 4, stepBehavior.awaitL4());
    }

    /**
     * Normal path: schedule L1 reply with delay, then chain L2-L4.
     */
    private void scheduleL1(UUID commandId, UUID processId, PaymentStepBehavior stepBehavior) {
        int l1Delay = getDelay(stepBehavior.awaitL1(), DEFAULT_L1_DELAY_MIN, DEFAULT_L1_DELAY_MAX);
        scheduler.schedule(() -> {
            try {
                boolean l1Success = sendLevelReply(commandId, processId, 1, stepBehavior.awaitL1());

                if (l1Success) {
                    int l2Delay = getDelay(stepBehavior.awaitL2(), DEFAULT_L2_DELAY_MIN, DEFAULT_L2_DELAY_MAX);
                    scheduler.schedule(() -> {
                        try {
                            boolean l2Success = sendLevelReply(commandId, processId, 2, stepBehavior.awaitL2());

                            if (l2Success) {
                                int l3Delay = getDelay(stepBehavior.awaitL3(), DEFAULT_L3_DELAY_MIN, DEFAULT_L3_DELAY_MAX);
                                scheduler.schedule(() -> {
                                    try {
                                        boolean l3Success = sendLevelReply(commandId, processId, 3, stepBehavior.awaitL3());

                                        if (l3Success) {
                                            int l4Delay = getDelay(stepBehavior.awaitL4(), DEFAULT_L4_DELAY_MIN, DEFAULT_L4_DELAY_MAX);
                                            scheduler.schedule(() -> {
                                                try {
                                                    sendLevelReply(commandId, processId, 4, stepBehavior.awaitL4());
                                                } catch (Exception e) {
                                                    log.error("Exception sending L4 for process {}", processId, e);
                                                }
                                            }, l4Delay, TimeUnit.MILLISECONDS);
                                        }
                                    } catch (Exception e) {
                                        log.error("Exception sending L3 for process {}", processId, e);
                                    }
                                }, l3Delay, TimeUnit.MILLISECONDS);
                            }
                        } catch (Exception e) {
                            log.error("Exception sending L2 for process {}", processId, e);
                        }
                    }, l2Delay, TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                log.error("Exception sending L1 for process {}", processId, e);
            }
        }, l1Delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Get delay from behavior duration settings, falling back to defaults if not specified.
     */
    private int getDelay(ProbabilisticBehavior behavior, int defaultMin, int defaultMax) {
        if (behavior != null) {
            return randomDelay(behavior.minDurationMs(), behavior.maxDurationMs());
        }
        return randomDelay(defaultMin, defaultMax);
    }

    /**
     * Send a level confirmation reply.
     *
     * @return true if a success reply was sent (next level should proceed),
     *         false if failed, timed out, or placed in pending queue
     */
    private boolean sendLevelReply(UUID commandId, UUID processId, int level, ProbabilisticBehavior behavior) {
        try {
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
                log.info("Simulating timeout at L{} for process {}", level, processId);
                return false;
            }

            // Check for pending (only for L3 and L4)
            if ((level == 3 || level == 4) && behavior.pendingPct() > 0) {
                cumulative += behavior.pendingPct();
                if (roll < cumulative) {
                    PendingNetworkResponse pending = PendingNetworkResponse.create(
                        null, processId, processId, commandId, level
                    );
                    pendingNetworkResponseRepository.save(pending);
                    log.info("L{} response for process {} placed in pending queue (pending_id={})",
                        level, processId, pending.id());
                    return false;
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

        log.debug("Sent L{} success reply for process {} (command={})", level, processId, commandId);
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
        if (min == 0 && max == 0) return 0;
        return min + random.nextInt(Math.max(1, max - min));
    }

    /**
     * Send a success reply for a pending network response.
     * Called when operator approves a pending L3/L4 response.
     */
    public void sendApprovedReply(UUID commandId, UUID processId, int level) {
        sendSuccessReply(commandId, processId, level);

        // If L3 was approved, schedule L4 automatically
        if (level == 3) {
            int l4Delay = randomDelay(DEFAULT_L4_DELAY_MIN, DEFAULT_L4_DELAY_MAX);
            scheduler.schedule(() -> {
                var pendingL4 = pendingNetworkResponseRepository.findByProcessIdAndLevel(processId, 4);
                if (pendingL4.isEmpty()) {
                    sendSuccessReply(commandId, processId, 4);
                }
            }, l4Delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Send a failure reply for a pending network response.
     */
    public void sendRejectedReply(UUID commandId, UUID processId, int level) {
        sendBusinessRuleFailureReply(commandId, processId, level,
            "OPERATOR_REJECTED", "Network response rejected by operator at L" + level);
    }

    /**
     * Send an approval reply for a pending risk approval.
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
        executor.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
