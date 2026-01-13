package com.ivamare.commandbus.process;

import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.model.Reply;
import com.ivamare.commandbus.model.ReplyOutcome;
import com.ivamare.commandbus.model.SendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BaseProcessManager")
class BaseProcessManagerTest {

    // Test step enum
    enum OrderStep {
        RESERVE_INVENTORY,
        PROCESS_PAYMENT,
        SHIP_ORDER,
        // Compensation steps
        RELEASE_INVENTORY,
        REFUND_PAYMENT
    }

    // Test state
    record OrderState(
        String orderId,
        String customerId,
        int amount,
        String reservationId,
        String paymentId,
        String shipmentId
    ) implements ProcessState {
        @Override
        public Map<String, Object> toMap() {
            return Map.of(
                "orderId", orderId,
                "customerId", customerId,
                "amount", amount,
                "reservationId", reservationId != null ? reservationId : "",
                "paymentId", paymentId != null ? paymentId : "",
                "shipmentId", shipmentId != null ? shipmentId : ""
            );
        }

        public static OrderState fromMap(Map<String, Object> data) {
            return new OrderState(
                (String) data.get("orderId"),
                (String) data.get("customerId"),
                data.get("amount") instanceof Integer i ? i : Integer.parseInt(data.get("amount").toString()),
                nullIfEmpty((String) data.get("reservationId")),
                nullIfEmpty((String) data.get("paymentId")),
                nullIfEmpty((String) data.get("shipmentId"))
            );
        }

        private static String nullIfEmpty(String s) {
            return s != null && !s.isEmpty() ? s : null;
        }

        public OrderState withReservationId(String id) {
            return new OrderState(orderId, customerId, amount, id, paymentId, shipmentId);
        }

        public OrderState withPaymentId(String id) {
            return new OrderState(orderId, customerId, amount, reservationId, id, shipmentId);
        }

        public OrderState withShipmentId(String id) {
            return new OrderState(orderId, customerId, amount, reservationId, paymentId, id);
        }
    }

    // Test process manager
    static class TestOrderProcess extends BaseProcessManager<OrderState, OrderStep> {

        public TestOrderProcess(
                CommandBus commandBus,
                ProcessRepository processRepo,
                JdbcTemplate jdbcTemplate,
                TransactionTemplate transactionTemplate) {
            super(commandBus, processRepo, "order_replies", jdbcTemplate, transactionTemplate);
        }

        @Override
        public String getProcessType() {
            return "ORDER_FULFILLMENT";
        }

        @Override
        public String getDomain() {
            return "orders";
        }

        @Override
        public Class<OrderState> getStateClass() {
            return OrderState.class;
        }

        @Override
        public Class<OrderStep> getStepClass() {
            return OrderStep.class;
        }

        @Override
        public OrderState createInitialState(Map<String, Object> initialData) {
            return new OrderState(
                (String) initialData.get("orderId"),
                (String) initialData.get("customerId"),
                (Integer) initialData.get("amount"),
                null, null, null
            );
        }

        @Override
        public OrderStep getFirstStep(OrderState state) {
            return OrderStep.RESERVE_INVENTORY;
        }

        @Override
        public ProcessCommand<?> buildCommand(OrderStep step, OrderState state) {
            return switch (step) {
                case RESERVE_INVENTORY -> new ProcessCommand<>(
                    "ReserveInventory",
                    Map.of("orderId", state.orderId(), "amount", state.amount())
                );
                case PROCESS_PAYMENT -> new ProcessCommand<>(
                    "ProcessPayment",
                    Map.of("orderId", state.orderId(), "amount", state.amount())
                );
                case SHIP_ORDER -> new ProcessCommand<>(
                    "ShipOrder",
                    Map.of("orderId", state.orderId(), "reservationId", state.reservationId())
                );
                case RELEASE_INVENTORY -> new ProcessCommand<>(
                    "ReleaseInventory",
                    Map.of("reservationId", state.reservationId())
                );
                case REFUND_PAYMENT -> new ProcessCommand<>(
                    "RefundPayment",
                    Map.of("paymentId", state.paymentId())
                );
            };
        }

        @Override
        public OrderState updateState(OrderState state, OrderStep step, Reply reply) {
            if (reply.outcome() != ReplyOutcome.SUCCESS) {
                return state;
            }
            return switch (step) {
                case RESERVE_INVENTORY -> state.withReservationId((String) reply.data().get("reservationId"));
                case PROCESS_PAYMENT -> state.withPaymentId((String) reply.data().get("paymentId"));
                case SHIP_ORDER -> state.withShipmentId((String) reply.data().get("shipmentId"));
                default -> state;
            };
        }

        @Override
        public OrderStep getNextStep(OrderStep currentStep, Reply reply, OrderState state) {
            if (reply.outcome() != ReplyOutcome.SUCCESS) {
                return null;
            }
            return switch (currentStep) {
                case RESERVE_INVENTORY -> OrderStep.PROCESS_PAYMENT;
                case PROCESS_PAYMENT -> OrderStep.SHIP_ORDER;
                case SHIP_ORDER -> null; // Complete
                default -> null;
            };
        }

        @Override
        public OrderStep getCompensationStep(OrderStep step) {
            return switch (step) {
                case RESERVE_INVENTORY -> OrderStep.RELEASE_INVENTORY;
                case PROCESS_PAYMENT -> OrderStep.REFUND_PAYMENT;
                default -> null;
            };
        }
    }

    private CommandBus commandBus;
    private ProcessRepository processRepo;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private TestOrderProcess processManager;

    @BeforeEach
    void setUp() {
        commandBus = mock(CommandBus.class);
        processRepo = mock(ProcessRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        transactionTemplate = mock(TransactionTemplate.class);

        // Make transaction template execute the callback immediately
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        });

        processManager = new TestOrderProcess(commandBus, processRepo, jdbcTemplate, transactionTemplate);

        // Default mock for commandBus.send
        when(commandBus.send(anyString(), anyString(), any(UUID.class), any(), any(), anyString(), any()))
            .thenReturn(new SendResult(UUID.randomUUID(), 1L));
    }

    @Test
    @DisplayName("should start process and execute first step")
    void shouldStartProcessAndExecuteFirstStep() {
        UUID processId = processManager.start(Map.of(
            "orderId", "ORD-123",
            "customerId", "CUST-456",
            "amount", 500
        ));

        assertNotNull(processId);

        // Verify process was saved
        verify(processRepo).save(argThat(process ->
            process.domain().equals("orders") &&
            process.processType().equals("ORDER_FULFILLMENT") &&
            process.status() == ProcessStatus.PENDING
        ), eq(jdbcTemplate));

        // Verify command was sent for first step
        verify(commandBus).send(
            eq("orders"),
            eq("ReserveInventory"),
            any(UUID.class),
            argThat((Map<String, Object> data) ->
                "ORD-123".equals(data.get("orderId")) &&
                Integer.valueOf(500).equals(data.get("amount"))
            ),
            eq(processId),  // correlationId is process_id
            eq("order_replies"),
            isNull()
        );

        // Verify atomic update was called with WAITING_FOR_REPLY status
        verify(processRepo).updateStateAtomic(
            eq("orders"),
            eq(processId),
            anyString(),  // state JSON
            eq("RESERVE_INVENTORY"),
            eq("WAITING_FOR_REPLY"),
            isNull(),
            isNull(),
            eq(jdbcTemplate)
        );

        // Verify audit entry was created
        verify(processRepo).logStep(
            eq("orders"),
            eq(processId),
            argThat(entry -> entry.stepName().equals("RESERVE_INVENTORY")),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("should handle success reply and advance to next step")
    void shouldHandleSuccessReplyAndAdvanceToNextStep() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, null, null, null);
        // Simulate how data comes from DB - state as MapProcessState, step as enum
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),  // Simulate DB deserialization
            ProcessStatus.WAITING_FOR_REPLY,
            OrderStep.RESERVE_INVENTORY,  // Current step (from enum conversion)
            Instant.now(), Instant.now(), null, null, null
        );

        Reply reply = Reply.success(commandId, processId, Map.of("reservationId", "RES-789"));

        processManager.handleReply(reply, process, jdbcTemplate);

        // Verify state was updated and next command sent
        verify(commandBus).send(
            eq("orders"),
            eq("ProcessPayment"),  // Next step command
            any(UUID.class),
            any(),
            eq(processId),
            eq("order_replies"),
            isNull()
        );

        // Verify atomic update was called for new step
        verify(processRepo).updateStateAtomic(
            eq("orders"),
            eq(processId),
            anyString(),  // state JSON
            eq("PROCESS_PAYMENT"),
            eq("WAITING_FOR_REPLY"),
            isNull(),
            isNull(),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("should complete process on final step success")
    void shouldCompleteProcessOnFinalStepSuccess() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, "RES-789", "PAY-111", null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.WAITING_FOR_REPLY,
            OrderStep.SHIP_ORDER,  // Final step
            Instant.now(), Instant.now(), null, null, null
        );

        Reply reply = Reply.success(commandId, processId, Map.of("shipmentId", "SHIP-222"));

        processManager.handleReply(reply, process, jdbcTemplate);

        // Verify no more commands sent
        verify(commandBus, never()).send(anyString(), anyString(), any(UUID.class), any(), any(), anyString(), any());

        // Verify atomic update was called with COMPLETED status
        verify(processRepo).updateStateAtomic(
            eq("orders"),
            eq(processId),
            anyString(),  // state JSON
            isNull(),     // step doesn't change on completion
            eq("COMPLETED"),
            isNull(),
            isNull(),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("should handle failure and wait for TSQ")
    void shouldHandleFailureAndWaitForTSQ() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, "RES-789", null, null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.WAITING_FOR_REPLY,
            OrderStep.PROCESS_PAYMENT,
            Instant.now(), Instant.now(), null, null, null
        );

        Reply reply = Reply.failed(commandId, processId, "PAYMENT_DECLINED", "Insufficient funds");

        processManager.handleReply(reply, process, jdbcTemplate);

        // Verify atomic update was called with WAITING_FOR_TSQ status and error info
        verify(processRepo).updateStateAtomic(
            eq("orders"),
            eq(processId),
            anyString(),  // state JSON
            eq("PROCESS_PAYMENT"),
            eq("WAITING_FOR_TSQ"),
            eq("PAYMENT_DECLINED"),
            eq("Insufficient funds"),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("should auto-compensate on business rule failure")
    void shouldAutoCompensateOnBusinessRuleFailure() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, "RES-789", null, null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.WAITING_FOR_REPLY,
            OrderStep.PROCESS_PAYMENT,
            Instant.now(), Instant.now(), null, null, null
        );

        // Business rule failure - should auto-compensate without TSQ
        Reply reply = Reply.businessRuleFailed(commandId, processId, "ACCOUNT_CLOSED", "Customer account is closed");

        // Completed steps that need compensation
        when(processRepo.getCompletedSteps("orders", processId, jdbcTemplate))
            .thenReturn(List.of("RESERVE_INVENTORY"));

        processManager.handleReply(reply, process, jdbcTemplate);

        // Verify atomic update was called with COMPENSATING status
        verify(processRepo, atLeastOnce()).updateStateAtomic(
            eq("orders"),
            eq(processId),
            anyString(),
            any(),  // step may vary
            eq("COMPENSATING"),
            any(),
            any(),
            eq(jdbcTemplate)
        );

        // Verify compensation command sent (RELEASE_INVENTORY for RESERVE_INVENTORY)
        verify(commandBus).send(
            eq("orders"),
            eq("ReleaseInventory"),
            any(UUID.class),
            argThat((Map<String, Object> data) -> "RES-789".equals(data.get("reservationId"))),
            eq(processId),
            eq("order_replies"),
            isNull()
        );
    }

    @Test
    @DisplayName("should run compensations on cancel from TSQ")
    void shouldRunCompensationsOnCancelFromTSQ() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, "RES-789", "PAY-111", null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.WAITING_FOR_TSQ,
            OrderStep.SHIP_ORDER,
            Instant.now(), Instant.now(), null, null, null
        );

        // Completed steps that need compensation
        when(processRepo.getCompletedSteps("orders", processId, jdbcTemplate))
            .thenReturn(List.of("RESERVE_INVENTORY", "PROCESS_PAYMENT"));

        Reply reply = Reply.canceled(commandId, processId);

        processManager.handleReply(reply, process, jdbcTemplate);

        // Verify atomic update was called with COMPENSATING status
        verify(processRepo, atLeastOnce()).updateStateAtomic(
            eq("orders"),
            eq(processId),
            anyString(),
            any(),  // step may vary
            eq("COMPENSATING"),
            any(),
            any(),
            eq(jdbcTemplate)
        );

        // Verify compensation command sent (REFUND_PAYMENT for PROCESS_PAYMENT)
        verify(commandBus).send(
            eq("orders"),
            eq("RefundPayment"),
            any(UUID.class),
            argThat((Map<String, Object> data) -> "PAY-111".equals(data.get("paymentId"))),
            eq(processId),
            eq("order_replies"),
            isNull()
        );
    }

    @Test
    @DisplayName("should return correct process type and domain")
    void shouldReturnCorrectProcessTypeAndDomain() {
        assertEquals("ORDER_FULFILLMENT", processManager.getProcessType());
        assertEquals("orders", processManager.getDomain());
    }

    @Test
    @DisplayName("should handle reply with null current step")
    void shouldHandleReplyWithNullCurrentStep() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, null, null, null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.WAITING_FOR_REPLY,
            null,  // null current step
            Instant.now(), Instant.now(), null, null, null
        );

        Reply reply = Reply.success(commandId, processId, Map.of());

        // Should log error but not throw
        processManager.handleReply(reply, process, jdbcTemplate);

        // Verify no further processing happened
        verify(commandBus, never()).send(anyString(), anyString(), any(UUID.class), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("should handle process with typed state instead of MapProcessState")
    void shouldHandleProcessWithTypedState() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, null, null, null);
        // Directly use OrderState instead of MapProcessState
        ProcessMetadata<OrderState, OrderStep> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            state,  // typed state
            ProcessStatus.WAITING_FOR_REPLY,
            OrderStep.RESERVE_INVENTORY,
            Instant.now(), Instant.now(), null, null, null
        );

        Reply reply = Reply.success(commandId, processId, Map.of("reservationId", "RES-789"));

        processManager.handleReply(reply, process, jdbcTemplate);

        // Verify next command was sent
        verify(commandBus).send(
            eq("orders"),
            eq("ProcessPayment"),
            any(UUID.class),
            any(),
            eq(processId),
            eq("order_replies"),
            isNull()
        );
    }

    @Test
    @DisplayName("should use default getCompensationStep when not overridden")
    void shouldUseDefaultGetCompensationStepWhenNotOverridden() {
        // SHIP_ORDER has no compensation defined
        assertNull(processManager.getCompensationStep(OrderStep.SHIP_ORDER));
    }

    @Test
    @DisplayName("should handle handleReply without transaction template")
    void shouldHandleReplyWithoutTransactionTemplate() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, "RES-789", "PAY-111", null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.WAITING_FOR_REPLY,
            OrderStep.SHIP_ORDER,
            Instant.now(), Instant.now(), null, null, null
        );

        Reply reply = Reply.success(commandId, processId, Map.of("shipmentId", "SHIP-222"));

        // Use the version that manages its own transaction
        processManager.handleReply(reply, process);

        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("should mark as compensated when no compensations needed")
    void shouldMarkAsCompensatedWhenNoCompensationsNeeded() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, null, null, null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.WAITING_FOR_TSQ,
            OrderStep.RESERVE_INVENTORY,
            Instant.now(), Instant.now(), null, null, null
        );

        // No completed steps that need compensation
        when(processRepo.getCompletedSteps("orders", processId, jdbcTemplate))
            .thenReturn(List.of());

        Reply reply = Reply.canceled(commandId, processId);

        processManager.handleReply(reply, process, jdbcTemplate);

        // Should mark as COMPENSATED since there's nothing to compensate
        verify(processRepo, atLeastOnce()).updateStateAtomic(
            eq("orders"),
            eq(processId),
            anyString(),
            any(),  // step may be null
            eq("COMPENSATED"),
            any(),
            any(),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("should skip steps without compensation in compensation chain")
    void shouldSkipStepsWithoutCompensationInCompensationChain() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        // State with reservation but no payment (only RESERVE_INVENTORY completed)
        OrderState state = new OrderState("ORD-123", "CUST-456", 500, "RES-789", null, null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.WAITING_FOR_TSQ,
            OrderStep.PROCESS_PAYMENT,  // Failed at payment
            Instant.now(), Instant.now(), null, null, null
        );

        // Only RESERVE_INVENTORY is completed (SHIP_ORDER not in the list)
        when(processRepo.getCompletedSteps("orders", processId, jdbcTemplate))
            .thenReturn(List.of("RESERVE_INVENTORY"));

        Reply reply = Reply.canceled(commandId, processId);

        processManager.handleReply(reply, process, jdbcTemplate);

        // Should run compensation for RESERVE_INVENTORY (RELEASE_INVENTORY)
        verify(commandBus).send(
            eq("orders"),
            eq("ReleaseInventory"),
            any(UUID.class),
            argThat((Map<String, Object> data) -> "RES-789".equals(data.get("reservationId"))),
            eq(processId),
            eq("order_replies"),
            isNull()
        );
    }

    @Test
    @DisplayName("startBatch should return empty list for empty input")
    void startBatchShouldReturnEmptyListForEmptyInput() {
        List<UUID> result = processManager.startBatch(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("startBatch should start multiple processes")
    void startBatchShouldStartMultipleProcesses() {
        // Use JdbcProcessRepository mock for batch operations
        JdbcProcessRepository jdbcProcessRepo = mock(JdbcProcessRepository.class);
        TestOrderProcess batchManager = new TestOrderProcess(commandBus, jdbcProcessRepo, jdbcTemplate, transactionTemplate);

        List<Map<String, Object>> initialDataList = List.of(
            Map.of("orderId", "ORD-001", "customerId", "CUST-A", "amount", 100),
            Map.of("orderId", "ORD-002", "customerId", "CUST-B", "amount", 200),
            Map.of("orderId", "ORD-003", "customerId", "CUST-C", "amount", 300)
        );

        List<UUID> processIds = batchManager.startBatch(initialDataList);

        assertEquals(3, processIds.size());

        // Verify batch save was called
        verify(jdbcProcessRepo).saveBatch(anyList(), eq(jdbcTemplate));

        // Verify batch send was called
        verify(commandBus).sendBatch(anyList());

        // Verify batch audit log was called
        verify(jdbcProcessRepo).logBatchSteps(eq("orders"), anyList(), eq(jdbcTemplate));
    }

    @Test
    @DisplayName("startBatch should fallback to individual saves for non-JDBC repository")
    void startBatchShouldFallbackToIndividualSavesForNonJdbcRepository() {
        List<Map<String, Object>> initialDataList = List.of(
            Map.of("orderId", "ORD-001", "customerId", "CUST-A", "amount", 100),
            Map.of("orderId", "ORD-002", "customerId", "CUST-B", "amount", 200)
        );

        List<UUID> processIds = processManager.startBatch(initialDataList);

        assertEquals(2, processIds.size());

        // Verify individual saves were called (fallback path)
        verify(processRepo, times(2)).save(any(), eq(jdbcTemplate));

        // Verify individual audit logs were called (fallback path)
        verify(processRepo, times(2)).logStep(eq("orders"), any(UUID.class), any(), eq(jdbcTemplate));
    }

    @Test
    @DisplayName("updateStateOnly should update state for terminal process")
    void updateStateOnlyShouldUpdateStateForTerminalProcess() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, "RES-789", null, null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.COMPLETED,  // Terminal status
            OrderStep.SHIP_ORDER,
            Instant.now(), Instant.now(), Instant.now(), null, null
        );

        Reply reply = Reply.success(commandId, processId, Map.of("shipmentId", "SHIP-999"));

        processManager.updateStateOnly(reply, process, jdbcTemplate);

        // Verify state was updated atomically
        verify(processRepo).updateStateAtomic(
            eq("orders"),
            eq(processId),
            anyString(),  // state JSON
            isNull(),     // step - null to preserve
            isNull(),     // status - null to preserve
            any(),        // completedAt
            any(),        // updatedAt
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("updateStateOnly should skip update if state unchanged")
    void updateStateOnlyShouldSkipUpdateIfStateUnchanged() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        // State that won't change from reply
        OrderState state = new OrderState("ORD-123", "CUST-456", 500, null, null, null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.COMPLETED,
            OrderStep.RESERVE_INVENTORY,  // This step expects reservationId, but reply has wrong key
            Instant.now(), Instant.now(), Instant.now(), null, null
        );

        // Reply with data that doesn't match expected update pattern
        Reply reply = Reply.success(commandId, processId, Map.of("someOtherKey", "value"));

        processManager.updateStateOnly(reply, process, jdbcTemplate);

        // Verify no update was called since state didn't change
        verify(processRepo, never()).updateStateAtomic(
            anyString(), any(UUID.class), anyString(), anyString(), anyString(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("updateStateOnly should handle process with no current step")
    void updateStateOnlyShouldHandleProcessWithNoCurrentStep() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, null, null, null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.COMPLETED,
            null,  // No current step
            Instant.now(), Instant.now(), Instant.now(), null, null
        );

        Reply reply = Reply.success(commandId, processId, Map.of("reservationId", "RES-123"));

        // Should not throw, just log warning
        processManager.updateStateOnly(reply, process, jdbcTemplate);

        // Verify no update was called
        verify(processRepo, never()).updateStateAtomic(
            anyString(), any(UUID.class), anyString(), anyString(), anyString(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("updateStateOnly should handle typed state directly")
    void updateStateOnlyShouldHandleTypedStateDirectly() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, "RES-789", null, null);
        // Use typed state directly instead of MapProcessState
        ProcessMetadata<OrderState, OrderStep> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            state,  // Typed state
            ProcessStatus.COMPLETED,
            OrderStep.SHIP_ORDER,
            Instant.now(), Instant.now(), Instant.now(), null, null
        );

        Reply reply = Reply.success(commandId, processId, Map.of("shipmentId", "SHIP-999"));

        processManager.updateStateOnly(reply, process, jdbcTemplate);

        // Verify state was updated
        verify(processRepo).updateStateAtomic(
            eq("orders"),
            eq(processId),
            anyString(),
            isNull(),
            isNull(),
            any(),
            any(),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("handleReply without JdbcTemplate should use transaction template")
    void handleReplyWithoutJdbcTemplateShouldUseTransactionTemplate() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, "RES-789", null, null);
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(state.toMap()),
            ProcessStatus.WAITING_FOR_REPLY,
            OrderStep.PROCESS_PAYMENT,
            Instant.now(), Instant.now(), null, null, null
        );

        Reply reply = Reply.success(commandId, processId, Map.of("paymentId", "PAY-123"));

        // Call the version without JdbcTemplate
        processManager.handleReply(reply, process);

        // Verify transaction template was used
        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("updateStateOnly should extract step from state map when step is null")
    void updateStateOnlyShouldExtractStepFromStateMapWhenStepIsNull() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        OrderState state = new OrderState("ORD-123", "CUST-456", 500, "RES-789", null, null);
        Map<String, Object> stateMapWithStep = new java.util.HashMap<>(state.toMap());
        stateMapWithStep.put("__current_step__", "SHIP_ORDER");

        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new MapProcessState(stateMapWithStep),
            ProcessStatus.COMPLETED,
            null,  // No current step set directly - should be extracted from state map
            Instant.now(), Instant.now(), Instant.now(), null, null
        );

        Reply reply = Reply.success(commandId, processId, Map.of("shipmentId", "SHIP-999"));

        processManager.updateStateOnly(reply, process, jdbcTemplate);

        // Should extract step from state map and update state
        verify(processRepo).updateStateAtomic(
            eq("orders"),
            eq(processId),
            anyString(),
            isNull(),
            isNull(),
            any(),
            any(),
            eq(jdbcTemplate)
        );
    }
}
