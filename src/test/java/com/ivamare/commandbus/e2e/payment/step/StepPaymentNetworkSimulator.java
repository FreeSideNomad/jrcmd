package com.ivamare.commandbus.e2e.payment.step;

import com.ivamare.commandbus.e2e.payment.PaymentStepBehavior;
import com.ivamare.commandbus.e2e.payment.PendingNetworkResponse;
import com.ivamare.commandbus.e2e.payment.PendingNetworkResponseRepository;
import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 *   <li>Calculates L1-L4 delays from a common start time</li>
 *   <li>Sends confirmations in order of their calculated delays (may be out of L1-L4 order)</li>
 *   <li>Supports probabilistic failures for each level</li>
 *   <li>Updates both process state and Payment entity via confirmL1-L4 methods</li>
 *   <li>L3/L4 can be held for manual release if configured</li>
 * </ul>
 */
public class StepPaymentNetworkSimulator {

    private static final Logger log = LoggerFactory.getLogger(StepPaymentNetworkSimulator.class);

    private final PaymentStepProcess processManager;
    private final PendingNetworkResponseRepository pendingNetworkResponseRepository;
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

    public StepPaymentNetworkSimulator(
            PaymentStepProcess processManager,
            PendingNetworkResponseRepository pendingNetworkResponseRepository) {
        this.processManager = processManager;
        this.pendingNetworkResponseRepository = pendingNetworkResponseRepository;
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
     * All delays are calculated from the same starting timestamp, so confirmations
     * may arrive in any order (e.g., L2 before L1 if L2's delay is shorter).
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
            log.info("stepBehavior is NULL for process {} - using default successBehavior", processId);
            stepBehavior = PaymentStepBehavior.successBehavior();
        } else {
            log.info("stepBehavior for process {}: L3 pendingPct={}, L4 pendingPct={}",
                processId,
                stepBehavior.awaitL3() != null ? stepBehavior.awaitL3().pendingPct() : "null",
                stepBehavior.awaitL4() != null ? stepBehavior.awaitL4().pendingPct() : "null");
        }

        boolean zeroDelay = isZeroDelayMode(stepBehavior);
        log.info("Starting payment confirmation simulation for process {} (command={}), zeroDelay={}",
            processId, commandId, zeroDelay);

        final PaymentStepBehavior behavior = stepBehavior;

        if (zeroDelay) {
            // Fast path: send all responses in order on executor thread (for performance tests)
            executor.submit(() -> {
                try {
                    sendAllResponsesInRandomOrder(processId, behavior, true);
                } catch (Exception e) {
                    log.error("Exception in fast-path response sender for process {}", processId, e);
                }
            });
        } else {
            // Normal path: schedule all confirmations with delays from common start time
            scheduleAllConfirmations(processId, behavior);
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
     * Record for tracking scheduled confirmations with their calculated delays.
     */
    private record ScheduledConfirmation(int level, int delayMs, ProbabilisticBehavior behavior) {}

    /**
     * Fast path: send all responses sorted by their random delays (for zero-delay mode).
     */
    private void sendAllResponsesInRandomOrder(UUID processId, PaymentStepBehavior stepBehavior, boolean zeroDelay) {
        try {
            // Small delay to ensure process has transitioned to waiting state
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // Calculate delays for all levels
        List<ScheduledConfirmation> confirmations = new ArrayList<>();
        confirmations.add(new ScheduledConfirmation(1,
            zeroDelay ? random.nextInt(100) : getDelay(stepBehavior.awaitL1(), DEFAULT_L1_DELAY_MIN, DEFAULT_L1_DELAY_MAX),
            stepBehavior.awaitL1()));
        confirmations.add(new ScheduledConfirmation(2,
            zeroDelay ? random.nextInt(100) : getDelay(stepBehavior.awaitL2(), DEFAULT_L2_DELAY_MIN, DEFAULT_L2_DELAY_MAX),
            stepBehavior.awaitL2()));
        confirmations.add(new ScheduledConfirmation(3,
            zeroDelay ? random.nextInt(100) : getDelay(stepBehavior.awaitL3(), DEFAULT_L3_DELAY_MIN, DEFAULT_L3_DELAY_MAX),
            stepBehavior.awaitL3()));
        confirmations.add(new ScheduledConfirmation(4,
            zeroDelay ? random.nextInt(100) : getDelay(stepBehavior.awaitL4(), DEFAULT_L4_DELAY_MIN, DEFAULT_L4_DELAY_MAX),
            stepBehavior.awaitL4()));

        // Sort by delay (lowest first)
        confirmations.sort(Comparator.comparingInt(ScheduledConfirmation::delayMs));

        log.info("Sending L1-L4 confirmations for process {} in order: {}",
            processId, confirmations.stream().map(c -> "L" + c.level()).toList());

        // Send in order of calculated delays
        for (ScheduledConfirmation conf : confirmations) {
            sendLevelResponse(processId, conf.level(), conf.behavior());
        }
    }

    /**
     * Schedule all L1-L4 confirmations from a common start time.
     * Confirmations arrive in order of their calculated delays, not L1→L2→L3→L4 order.
     */
    private void scheduleAllConfirmations(UUID processId, PaymentStepBehavior stepBehavior) {
        // Calculate delays for all levels from common start time
        List<ScheduledConfirmation> confirmations = new ArrayList<>();
        confirmations.add(new ScheduledConfirmation(1,
            getDelay(stepBehavior.awaitL1(), DEFAULT_L1_DELAY_MIN, DEFAULT_L1_DELAY_MAX),
            stepBehavior.awaitL1()));
        confirmations.add(new ScheduledConfirmation(2,
            getDelay(stepBehavior.awaitL2(), DEFAULT_L2_DELAY_MIN, DEFAULT_L2_DELAY_MAX),
            stepBehavior.awaitL2()));
        confirmations.add(new ScheduledConfirmation(3,
            getDelay(stepBehavior.awaitL3(), DEFAULT_L3_DELAY_MIN, DEFAULT_L3_DELAY_MAX),
            stepBehavior.awaitL3()));
        confirmations.add(new ScheduledConfirmation(4,
            getDelay(stepBehavior.awaitL4(), DEFAULT_L4_DELAY_MIN, DEFAULT_L4_DELAY_MAX),
            stepBehavior.awaitL4()));

        // Sort by delay to show arrival order in logs
        List<ScheduledConfirmation> sorted = new ArrayList<>(confirmations);
        sorted.sort(Comparator.comparingInt(ScheduledConfirmation::delayMs));
        log.info("Scheduling L1-L4 confirmations for process {} - arrival order will be: {} (delays: L1={}ms, L2={}ms, L3={}ms, L4={}ms)",
            processId,
            sorted.stream().map(c -> "L" + c.level()).toList(),
            confirmations.get(0).delayMs(),
            confirmations.get(1).delayMs(),
            confirmations.get(2).delayMs(),
            confirmations.get(3).delayMs());

        // Schedule each confirmation independently from the common start time
        for (ScheduledConfirmation conf : confirmations) {
            scheduler.schedule(() -> {
                try {
                    sendLevelResponse(processId, conf.level(), conf.behavior());
                } catch (Exception e) {
                    log.error("Exception sending L{} for process {}", conf.level(), processId, e);
                }
            }, conf.delayMs(), TimeUnit.MILLISECONDS);
        }
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
            // Debug logging for L3/L4 pending behavior
            if (level == 3 || level == 4) {
                log.info("L{} behavior for process {}: pendingPct={}, behavior={}",
                    level, processId,
                    behavior != null ? behavior.pendingPct() : "null",
                    behavior);
            }

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

                // Check for pending (only for L3/L4) - requires operator approval
                if ((level == 3 || level == 4) && behavior.pendingPct() > 0) {
                    cumulative += behavior.pendingPct();
                    if (roll < cumulative) {
                        createPendingNetworkResponse(processId, level);
                        return false;
                    }
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

    /**
     * Create a pending network response record for operator approval.
     * The process will wait until operator approves/rejects via the UI.
     */
    private void createPendingNetworkResponse(UUID processId, int level) {
        // For PROCESS_STEP execution model, processId is the payment ID
        // commandId and correlationId are not used for PROCESS_STEP dispatch
        PendingNetworkResponse pending = PendingNetworkResponse.create(
            processId,  // paymentId
            processId,  // processId
            null,       // correlationId - not needed for PROCESS_STEP
            null,       // commandId - not needed for PROCESS_STEP
            level,
            "PROCESS_STEP"
        );

        pendingNetworkResponseRepository.save(pending);
        log.info("Created pending L{} network response for process {} - awaiting operator approval",
            level, processId);
    }

    private void sendSuccessResponse(UUID processId, int level) {
        String reference = "L" + level + "-REF-" + UUID.randomUUID().toString().substring(0, 8);

        // Use confirmL1-L4 methods which update both process state AND Payment entity
        switch (level) {
            case 1 -> processManager.confirmL1(processId, reference);
            case 2 -> processManager.confirmL2(processId, reference);
            case 3 -> processManager.confirmL3(processId, reference);
            case 4 -> processManager.confirmL4(processId, reference);
        }

        log.info("Sent L{} success response for process {} (ref={})", level, processId, reference);
    }

    private void sendFailureResponse(UUID processId, int level, String errorCode, String errorMessage) {
        // Use failL1-L4 methods
        switch (level) {
            case 1 -> processManager.failL1(processId, errorCode, errorMessage);
            case 2 -> processManager.failL2(processId, errorCode, errorMessage);
            case 3 -> processManager.failL3(processId, errorCode, errorMessage);
            case 4 -> processManager.failL4(processId, errorCode, errorMessage);
        }

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
