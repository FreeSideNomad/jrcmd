package com.commandbus.process;

import com.commandbus.api.CommandBus;
import com.commandbus.model.Reply;
import com.commandbus.model.ReplyOutcome;
import com.commandbus.model.SendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

        // Verify process was updated to WAITING_FOR_REPLY
        verify(processRepo).update(argThat(process ->
            process.status() == ProcessStatus.WAITING_FOR_REPLY &&
            process.currentStep() == OrderStep.RESERVE_INVENTORY
        ), eq(jdbcTemplate));

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

        // Verify process was updated with new step
        verify(processRepo).update(argThat(p ->
            p.status() == ProcessStatus.WAITING_FOR_REPLY &&
            p.currentStep() == OrderStep.PROCESS_PAYMENT
        ), eq(jdbcTemplate));
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

        // Verify process marked as completed
        verify(processRepo).update(argThat(p ->
            p.status() == ProcessStatus.COMPLETED &&
            p.completedAt() != null
        ), eq(jdbcTemplate));
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

        // Verify process marked as waiting for TSQ
        verify(processRepo).update(argThat(p ->
            p.status() == ProcessStatus.WAITING_FOR_TSQ &&
            "PAYMENT_DECLINED".equals(p.errorCode()) &&
            "Insufficient funds".equals(p.errorMessage())
        ), eq(jdbcTemplate));
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

        // Verify status changed to COMPENSATING
        verify(processRepo, atLeastOnce()).update(argThat(p ->
            p.status() == ProcessStatus.COMPENSATING
        ), eq(jdbcTemplate));

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
        verify(processRepo, atLeastOnce()).update(argThat(p ->
            p.status() == ProcessStatus.COMPENSATED
        ), eq(jdbcTemplate));
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
}
