package com.ivamare.commandbus.e2e.payment.step;

import com.ivamare.commandbus.e2e.payment.PaymentStepBehavior;
import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simulates an external payment network sending L1-L4 confirmation responses.
 *
 * <p>Unlike the command-based PaymentNetworkSimulator which sends replies to PGMQ queues,
 * this simulator works with ProcessStepManager by calling processAsyncResponse() directly.
 *
 * <p>The simulator:
 * <ul>
 *   <li>Schedules L1-L4 responses with configurable delays</li>
 *   <li>Supports probabilistic failures for each level</li>
 *   <li>Updates process state directly via processAsyncResponse()</li>
 * </ul>
 */
public class StepPaymentNetworkSimulator {

    private static final Logger log = LoggerFactory.getLogger(StepPaymentNetworkSimulator.class);

    private final PaymentStepProcess processManager;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;
    private final Random random = new Random();

    // Default delays for each level (milliseconds)
    private static final int DEFAULT_L1_DELAY_MIN = 50;
    private static final int DEFAULT_L1_DELAY_MAX = 200;
    private static final int DEFAULT_L2_DELAY_MIN = 100;
    private static final int DEFAULT_L2_DELAY_MAX = 400;
    private static final int DEFAULT_L3_DELAY_MIN = 150;
    private static final int DEFAULT_L3_DELAY_MAX = 600;
    private static final int DEFAULT_L4_DELAY_MIN = 200;
    private static final int DEFAULT_L4_DELAY_MAX = 800;

    public StepPaymentNetworkSimulator(PaymentStepProcess processManager) {
        this.processManager = processManager;
        this.scheduler = Executors.newScheduledThreadPool(10, r -> {
            Thread t = new Thread(r, "step-network-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "step-network-exec");
            t.setDaemon(true);
            return t;
        });
        log.info("StepPaymentNetworkSimulator initialized");
    }

    /**
     * Simulate payment confirmation flow (L1-L4 responses).
     *
     * @param commandId    Original command ID (for correlation)
     * @param processId    Process ID (payment ID)
     * @param stepBehavior Behavior configuration for each level
     */
    public void simulatePaymentConfirmations(
            UUID commandId,
            UUID processId,
            PaymentStepBehavior stepBehavior) {

        if (stepBehavior == null) {
            stepBehavior = PaymentStepBehavior.successBehavior();
        }

        boolean zeroDelay = isZeroDelayMode(stepBehavior);
        log.info("Starting payment confirmation simulation for process {} (command={}), zeroDelay={}",
            processId, commandId, zeroDelay);

        final PaymentStepBehavior behavior = stepBehavior;

        if (zeroDelay) {
            // Fast path: send all responses sequentially on executor thread
            executor.submit(() -> {
                try {
                    sendAllResponsesSequentially(processId, behavior);
                } catch (Exception e) {
                    log.error("Exception in fast-path response sender for process {}", processId, e);
                }
            });
        } else {
            // Normal path: use scheduler with delays
            scheduleL1(processId, behavior);
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
     * Fast path: send all L1-L4 responses sequentially without scheduling.
     */
    private void sendAllResponsesSequentially(UUID processId, PaymentStepBehavior stepBehavior) {
        try {
            // Small delay to ensure process has transitioned to waiting state
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!sendLevelResponse(processId, 1, stepBehavior.awaitL1())) return;
        if (!sendLevelResponse(processId, 2, stepBehavior.awaitL2())) return;
        if (!sendLevelResponse(processId, 3, stepBehavior.awaitL3())) return;
        sendLevelResponse(processId, 4, stepBehavior.awaitL4());
    }

    /**
     * Normal path: schedule L1 response with delay, then chain L2-L4.
     */
    private void scheduleL1(UUID processId, PaymentStepBehavior stepBehavior) {
        int l1Delay = getDelay(stepBehavior.awaitL1(), DEFAULT_L1_DELAY_MIN, DEFAULT_L1_DELAY_MAX);
        scheduler.schedule(() -> {
            try {
                boolean l1Success = sendLevelResponse(processId, 1, stepBehavior.awaitL1());

                if (l1Success) {
                    int l2Delay = getDelay(stepBehavior.awaitL2(), DEFAULT_L2_DELAY_MIN, DEFAULT_L2_DELAY_MAX);
                    scheduler.schedule(() -> {
                        try {
                            boolean l2Success = sendLevelResponse(processId, 2, stepBehavior.awaitL2());

                            if (l2Success) {
                                int l3Delay = getDelay(stepBehavior.awaitL3(), DEFAULT_L3_DELAY_MIN, DEFAULT_L3_DELAY_MAX);
                                scheduler.schedule(() -> {
                                    try {
                                        boolean l3Success = sendLevelResponse(processId, 3, stepBehavior.awaitL3());

                                        if (l3Success) {
                                            int l4Delay = getDelay(stepBehavior.awaitL4(), DEFAULT_L4_DELAY_MIN, DEFAULT_L4_DELAY_MAX);
                                            scheduler.schedule(() -> {
                                                try {
                                                    sendLevelResponse(processId, 4, stepBehavior.awaitL4());
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
     * Send a level confirmation response.
     *
     * @return true if a success response was sent (next level should proceed),
     *         false if failed, timed out, or placed in pending queue
     */
    private boolean sendLevelResponse(UUID processId, int level, ProbabilisticBehavior behavior) {
        try {
            double roll = random.nextDouble() * 100;
            double cumulative = 0;

            if (behavior != null) {
                // Check for permanent failure
                cumulative += behavior.failPermanentPct();
                if (roll < cumulative) {
                    sendFailureResponse(processId, level, "PERM_ERROR", "Permanent network failure at L" + level);
                    return false;
                }

                // Check for transient failure (treat as timeout for now)
                cumulative += behavior.failTransientPct();
                if (roll < cumulative) {
                    sendFailureResponse(processId, level, "TRANS_ERROR", "Transient network failure at L" + level);
                    return false;
                }

                // Check for business rule failure
                cumulative += behavior.failBusinessRulePct();
                if (roll < cumulative) {
                    sendFailureResponse(processId, level, "BIZ_RULE", "Business rule failure at L" + level);
                    return false;
                }

                // Check for timeout (no response sent)
                cumulative += behavior.timeoutPct();
                if (roll < cumulative) {
                    log.info("Simulating timeout at L{} for process {}", level, processId);
                    return false;
                }
            }

            // Success
            sendSuccessResponse(processId, level);
            return true;

        } catch (Exception e) {
            log.error("Failed to send L{} response for process {}", level, processId, e);
            return false;
        }
    }

    private void sendSuccessResponse(UUID processId, int level) {
        String reference = "L" + level + "-REF-" + UUID.randomUUID().toString().substring(0, 8);

        processManager.processAsyncResponse(processId, state -> {
            switch (level) {
                case 1 -> state.recordL1Success(reference);
                case 2 -> state.recordL2Success(reference);
                case 3 -> state.recordL3Success(reference);
                case 4 -> state.recordL4Success(reference);
            }
        });

        log.info("Sent L{} success response for process {} (ref={})", level, processId, reference);
    }

    private void sendFailureResponse(UUID processId, int level, String errorCode, String errorMessage) {
        processManager.processAsyncResponse(processId, state -> {
            switch (level) {
                case 1 -> state.recordL1Error(errorCode, errorMessage);
                case 2 -> state.recordL2Error(errorCode, errorMessage);
                case 3 -> state.recordL3Error(errorCode, errorMessage);
                case 4 -> state.recordL4Error(errorCode, errorMessage);
            }
        });

        log.info("Sent L{} failure response for process {} (code={})", level, processId, errorCode);
    }

    private int randomDelay(int min, int max) {
        if (min == 0 && max == 0) return 0;
        return min + random.nextInt(Math.max(1, max - min));
    }

    /**
     * Send an approved response for a specific level.
     * Used for manual operator approval of pending responses.
     */
    public void sendApprovedResponse(UUID processId, int level) {
        sendSuccessResponse(processId, level);

        // If L3 was approved, schedule L4 automatically
        if (level == 3) {
            int l4Delay = randomDelay(DEFAULT_L4_DELAY_MIN, DEFAULT_L4_DELAY_MAX);
            scheduler.schedule(() -> sendSuccessResponse(processId, 4), l4Delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Send a rejected response for a specific level.
     */
    public void sendRejectedResponse(UUID processId, int level) {
        sendFailureResponse(processId, level, "OPERATOR_REJECTED",
            "Network response rejected by operator at L" + level);
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
