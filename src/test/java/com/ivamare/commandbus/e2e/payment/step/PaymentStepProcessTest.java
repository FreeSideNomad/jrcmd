package com.ivamare.commandbus.e2e.payment.step;

import com.ivamare.commandbus.e2e.payment.PaymentStepBehavior;
import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;
import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.ProcessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PaymentStepProcess.
 *
 * <p>Tests the step-based workflow execution with deterministic replay
 * and async response handling.
 */
@DisplayName("PaymentStepProcess")
class PaymentStepProcessTest {

    @Mock
    private ProcessRepository processRepo;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    private PaymentStepProcess paymentProcess;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Configure transaction template to execute synchronously
        lenient().doAnswer(invocation -> {
            var action = invocation.getArgument(0);
            if (action instanceof org.springframework.transaction.support.TransactionCallback<?> callback) {
                return callback.doInTransaction(null);
            }
            return null;
        }).when(transactionTemplate).execute(any());

        lenient().doAnswer(invocation -> {
            var consumer = invocation.getArgument(0, java.util.function.Consumer.class);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        paymentProcess = new PaymentStepProcess(processRepo, jdbcTemplate, transactionTemplate);
    }

    @Test
    @DisplayName("should return correct process type")
    void shouldReturnCorrectProcessType() {
        assertEquals("PAYMENT_STEP", paymentProcess.getProcessType());
    }

    @Test
    @DisplayName("should return correct domain")
    void shouldReturnCorrectDomain() {
        assertEquals("payments", paymentProcess.getDomain());
    }

    @Test
    @DisplayName("should return correct state class")
    void shouldReturnCorrectStateClass() {
        assertEquals(PaymentStepState.class, paymentProcess.getStateClass());
    }

    @Test
    @DisplayName("should return correct execution model")
    void shouldReturnCorrectExecutionModel() {
        assertEquals("PROCESS_STEP", paymentProcess.getExecutionModel());
    }

    @Test
    @DisplayName("PaymentStepState should track L1-L4 success")
    void paymentStepStateShouldTrackL1L4Success() {
        PaymentStepState state = new PaymentStepState(UUID.randomUUID());

        assertFalse(state.allLevelsComplete());
        assertEquals(0, state.completedLevel());

        state.recordL1Success("L1-REF");
        assertEquals(1, state.completedLevel());
        assertEquals("L1-REF", state.getL1Reference());
        assertNotNull(state.getL1CompletedAt());

        state.recordL2Success("L2-REF");
        assertEquals(2, state.completedLevel());

        state.recordL3Success("L3-REF");
        assertEquals(3, state.completedLevel());

        state.recordL4Success("L4-REF");
        assertEquals(4, state.completedLevel());
        assertTrue(state.allLevelsComplete());
        assertTrue(state.isL4Success());
    }

    @Test
    @DisplayName("PaymentStepState should track L1-L4 errors")
    void paymentStepStateShouldTrackL1L4Errors() {
        PaymentStepState state = new PaymentStepState(UUID.randomUUID());

        state.recordL1Success("L1-REF");
        state.recordL2Error("ERR_001", "L2 failed");

        assertTrue(state.hasAnyError());
        assertNotNull(state.getL2CompletedAt());
        assertEquals("ERR_001", state.getL2ErrorCode());
        assertEquals("L2 failed", state.getL2ErrorMessage());
    }

    @Test
    @DisplayName("PaymentStepState builder should work correctly")
    void paymentStepStateBuilderShouldWorkCorrectly() {
        UUID paymentId = UUID.randomUUID();
        PaymentStepBehavior behavior = PaymentStepBehavior.successBehavior();

        PaymentStepState state = PaymentStepState.builder()
            .paymentId(paymentId)
            .behavior(behavior)
            .build();

        assertEquals(paymentId, state.getPaymentId());
        assertEquals(behavior, state.getBehavior());
    }

    @Test
    @DisplayName("PaymentStepState should serialize risk info")
    void paymentStepStateShouldSerializeRiskInfo() {
        PaymentStepState state = new PaymentStepState(UUID.randomUUID());
        state.setRiskStatus("APPROVED");
        state.setRiskMethod("AVAILABLE_BALANCE");
        state.setRiskReference("RISK-12345");

        assertEquals("APPROVED", state.getRiskStatus());
        assertEquals("AVAILABLE_BALANCE", state.getRiskMethod());
        assertEquals("RISK-12345", state.getRiskReference());
    }

    @Test
    @DisplayName("PaymentStepState should serialize FX info")
    void paymentStepStateShouldSerializeFxInfo() {
        PaymentStepState state = new PaymentStepState(UUID.randomUUID());
        state.setFxRequired(true);
        state.setFxContractId(123456L);
        state.setFxRate(new java.math.BigDecimal("1.2345"));
        state.setCreditAmount(new java.math.BigDecimal("1000.00"));

        assertTrue(state.isFxRequired());
        assertEquals(123456L, state.getFxContractId());
        assertEquals(new java.math.BigDecimal("1.2345"), state.getFxRate());
        assertEquals(new java.math.BigDecimal("1000.00"), state.getCreditAmount());
    }

    @Test
    @DisplayName("PaymentStepState should count received levels correctly")
    void paymentStepStateShouldCountReceivedLevelsCorrectly() {
        PaymentStepState state = new PaymentStepState(UUID.randomUUID());

        assertEquals(0, state.receivedLevelCount());

        state.recordL1Success("L1-REF");
        assertEquals(1, state.receivedLevelCount());

        state.recordL3Success("L3-REF");  // Skip L2
        assertEquals(2, state.receivedLevelCount());

        // completedLevel should still be 1 since L2 is missing
        assertEquals(1, state.completedLevel());
    }

    @Test
    @DisplayName("PaymentStepBehavior successBehavior should have low decline rate")
    void paymentStepBehaviorSuccessBehaviorShouldHaveLowDeclineRate() {
        PaymentStepBehavior behavior = PaymentStepBehavior.successBehavior();

        assertNotNull(behavior.bookRisk());
        // RiskBehavior uses declinedPct instead of failBusinessRulePct
        // Default has 5% decline rate which triggers saga compensation
        assertEquals(5, behavior.bookRisk().declinedPct());
        // Convert to ProbabilisticBehavior to check standard failure rates
        ProbabilisticBehavior probabilistic = behavior.bookRisk().toProbabilistic();
        assertEquals(0, probabilistic.failPermanentPct());
        assertEquals(0, probabilistic.failTransientPct());
    }

    @Test
    @DisplayName("PaymentStepBehavior fastPathBehavior should have zero delays")
    void paymentStepBehaviorFastPathBehaviorShouldHaveZeroDelays() {
        PaymentStepBehavior behavior = PaymentStepBehavior.fastPathBehavior();

        assertEquals(0, behavior.awaitL1().minDurationMs());
        assertEquals(0, behavior.awaitL1().maxDurationMs());
        assertEquals(0, behavior.awaitL4().minDurationMs());
        assertEquals(0, behavior.awaitL4().maxDurationMs());
    }

    @Test
    @DisplayName("StepPaymentNetworkSimulator should send L1-L4 success responses")
    void stepPaymentNetworkSimulatorShouldSendL1L4SuccessResponses() throws InterruptedException {
        // Create a state holder to verify updates
        AtomicReference<PaymentStepState> capturedState = new AtomicReference<>();
        UUID processId = UUID.randomUUID();

        // Mock the process manager's processAsyncResponse to capture state updates
        PaymentStepProcess mockProcess = mock(PaymentStepProcess.class);

        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            java.util.function.Consumer<PaymentStepState> updater = invocation.getArgument(1);
            PaymentStepState state = capturedState.get();
            if (state == null) {
                state = new PaymentStepState(id);
                capturedState.set(state);
            }
            updater.accept(state);
            return null;
        }).when(mockProcess).processAsyncResponse(any(UUID.class), any());

        StepPaymentNetworkSimulator simulator = new StepPaymentNetworkSimulator(mockProcess);

        // Use fast path behavior (zero delays)
        PaymentStepBehavior behavior = PaymentStepBehavior.fastPathBehavior();
        simulator.simulatePaymentConfirmations(UUID.randomUUID(), processId, behavior);

        // Wait for async completion
        Thread.sleep(200);

        PaymentStepState finalState = capturedState.get();
        assertNotNull(finalState);
        assertTrue(finalState.allLevelsComplete());
        assertTrue(finalState.isL4Success());
        assertFalse(finalState.hasAnyError());

        simulator.shutdown();
    }

    @Test
    @DisplayName("StepPaymentNetworkSimulator should handle L1 failure")
    void stepPaymentNetworkSimulatorShouldHandleL1Failure() throws InterruptedException {
        AtomicReference<PaymentStepState> capturedState = new AtomicReference<>();
        UUID processId = UUID.randomUUID();

        PaymentStepProcess mockProcess = mock(PaymentStepProcess.class);

        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            java.util.function.Consumer<PaymentStepState> updater = invocation.getArgument(1);
            PaymentStepState state = capturedState.get();
            if (state == null) {
                state = new PaymentStepState(id);
                capturedState.set(state);
            }
            updater.accept(state);
            return null;
        }).when(mockProcess).processAsyncResponse(any(UUID.class), any());

        StepPaymentNetworkSimulator simulator = new StepPaymentNetworkSimulator(mockProcess);

        // Configure L1 to always fail
        PaymentStepBehavior behavior = PaymentStepBehavior.builder()
            .awaitL1(ProbabilisticBehavior.builder()
                .failPermanentPct(100)  // Always fail L1
                .minDurationMs(0)
                .maxDurationMs(0)
                .build())
            .awaitL2(ProbabilisticBehavior.zeroDuration())
            .awaitL3(ProbabilisticBehavior.zeroDuration())
            .awaitL4(ProbabilisticBehavior.zeroDuration())
            .build();

        simulator.simulatePaymentConfirmations(UUID.randomUUID(), processId, behavior);

        // Wait for async completion
        Thread.sleep(200);

        PaymentStepState finalState = capturedState.get();
        assertNotNull(finalState);
        assertTrue(finalState.hasAnyError());
        assertNotNull(finalState.getL1ErrorCode());
        assertEquals("PERM_ERROR", finalState.getL1ErrorCode());

        // L2-L4 should not be completed
        assertNull(finalState.getL2CompletedAt());
        assertNull(finalState.getL3CompletedAt());
        assertNull(finalState.getL4CompletedAt());

        simulator.shutdown();
    }

    @Test
    @DisplayName("StepPaymentNetworkSimulator should chain responses correctly")
    void stepPaymentNetworkSimulatorShouldChainResponsesCorrectly() throws InterruptedException {
        CountDownLatch l4Latch = new CountDownLatch(1);
        AtomicReference<PaymentStepState> capturedState = new AtomicReference<>();
        UUID processId = UUID.randomUUID();

        PaymentStepProcess mockProcess = mock(PaymentStepProcess.class);

        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            java.util.function.Consumer<PaymentStepState> updater = invocation.getArgument(1);
            PaymentStepState state = capturedState.get();
            if (state == null) {
                state = new PaymentStepState(id);
                capturedState.set(state);
            }
            updater.accept(state);

            // Signal when L4 is received
            if (state.getL4CompletedAt() != null) {
                l4Latch.countDown();
            }
            return null;
        }).when(mockProcess).processAsyncResponse(any(UUID.class), any());

        StepPaymentNetworkSimulator simulator = new StepPaymentNetworkSimulator(mockProcess);

        // Use small delays to test chaining
        PaymentStepBehavior behavior = PaymentStepBehavior.builder()
            .awaitL1(ProbabilisticBehavior.builder().minDurationMs(10).maxDurationMs(20).build())
            .awaitL2(ProbabilisticBehavior.builder().minDurationMs(10).maxDurationMs(20).build())
            .awaitL3(ProbabilisticBehavior.builder().minDurationMs(10).maxDurationMs(20).build())
            .awaitL4(ProbabilisticBehavior.builder().minDurationMs(10).maxDurationMs(20).build())
            .build();

        simulator.simulatePaymentConfirmations(UUID.randomUUID(), processId, behavior);

        // Wait for L4 with timeout
        assertTrue(l4Latch.await(2, TimeUnit.SECONDS), "L4 should complete within timeout");

        PaymentStepState finalState = capturedState.get();
        assertNotNull(finalState.getL1CompletedAt());
        assertNotNull(finalState.getL2CompletedAt());
        assertNotNull(finalState.getL3CompletedAt());
        assertNotNull(finalState.getL4CompletedAt());

        // Verify order: L1 before L2 before L3 before L4
        assertTrue(finalState.getL1CompletedAt().compareTo(finalState.getL2CompletedAt()) <= 0);
        assertTrue(finalState.getL2CompletedAt().compareTo(finalState.getL3CompletedAt()) <= 0);
        assertTrue(finalState.getL3CompletedAt().compareTo(finalState.getL4CompletedAt()) <= 0);

        simulator.shutdown();
    }

    @Test
    @DisplayName("StepPaymentNetworkSimulator should handle manual approval")
    void stepPaymentNetworkSimulatorShouldHandleManualApproval() throws InterruptedException {
        AtomicReference<PaymentStepState> capturedState = new AtomicReference<>(new PaymentStepState(UUID.randomUUID()));
        UUID processId = UUID.randomUUID();

        PaymentStepProcess mockProcess = mock(PaymentStepProcess.class);

        doAnswer(invocation -> {
            java.util.function.Consumer<PaymentStepState> updater = invocation.getArgument(1);
            updater.accept(capturedState.get());
            return null;
        }).when(mockProcess).processAsyncResponse(any(UUID.class), any());

        StepPaymentNetworkSimulator simulator = new StepPaymentNetworkSimulator(mockProcess);

        // Manually trigger L3 approval
        simulator.sendApprovedResponse(processId, 3);

        Thread.sleep(100);

        PaymentStepState finalState = capturedState.get();
        assertNotNull(finalState.getL3Reference());

        simulator.shutdown();
    }

    @Test
    @DisplayName("StepPaymentNetworkSimulator should handle manual rejection")
    void stepPaymentNetworkSimulatorShouldHandleManualRejection() throws InterruptedException {
        AtomicReference<PaymentStepState> capturedState = new AtomicReference<>(new PaymentStepState(UUID.randomUUID()));
        UUID processId = UUID.randomUUID();

        PaymentStepProcess mockProcess = mock(PaymentStepProcess.class);

        doAnswer(invocation -> {
            java.util.function.Consumer<PaymentStepState> updater = invocation.getArgument(1);
            updater.accept(capturedState.get());
            return null;
        }).when(mockProcess).processAsyncResponse(any(UUID.class), any());

        StepPaymentNetworkSimulator simulator = new StepPaymentNetworkSimulator(mockProcess);

        // Manually trigger L3 rejection
        simulator.sendRejectedResponse(processId, 3);

        Thread.sleep(100);

        PaymentStepState finalState = capturedState.get();
        assertEquals("OPERATOR_REJECTED", finalState.getL3ErrorCode());

        simulator.shutdown();
    }
}
