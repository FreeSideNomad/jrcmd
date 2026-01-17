package com.ivamare.commandbus.process.step;

import com.ivamare.commandbus.process.ProcessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ProcessStepWorker")
class ProcessStepWorkerTest {

    @Mock
    private ProcessRepository processRepo;

    @Mock
    private ProcessStepManager<?> processManager;

    private ProcessStepWorker worker;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(processManager.getDomain()).thenReturn("test-domain");
        when(processManager.getProcessType()).thenReturn("test-type");
        when(processManager.getExecutionModel()).thenReturn("PROCESS_STEP");

        worker = new ProcessStepWorker(List.of(processManager), processRepo);
    }

    @Test
    @DisplayName("should start and stop gracefully")
    void shouldStartAndStopGracefully() {
        assertFalse(worker.isRunning());

        worker.start();
        assertTrue(worker.isRunning());

        worker.stop();
        assertFalse(worker.isRunning());
    }

    @Test
    @DisplayName("should poll pending processes and execute")
    void shouldPollPendingProcessesAndExecute() throws InterruptedException {
        UUID processId = UUID.randomUUID();
        when(processRepo.claimPendingProcesses(eq("test-domain"), eq("test-type"), anyInt()))
            .thenReturn(List.of(processId));

        worker.start();
        worker.pollPendingProcesses();

        // Wait for virtual thread execution
        Thread.sleep(100);

        verify(processManager).resume(processId);
    }

    @Test
    @DisplayName("should poll retries and execute")
    void shouldPollRetriesAndExecute() throws InterruptedException {
        UUID processId = UUID.randomUUID();
        when(processRepo.claimDueForRetry(eq("test-domain"), eq("test-type"), any(Instant.class), anyInt()))
            .thenReturn(List.of(processId));

        worker.start();
        worker.pollRetries();

        // Wait for virtual thread execution
        Thread.sleep(100);

        verify(processManager).resume(processId);
    }

    @Test
    @DisplayName("should check wait timeouts and handle")
    void shouldCheckWaitTimeoutsAndHandle() throws InterruptedException {
        UUID processId = UUID.randomUUID();
        when(processRepo.findExpiredWaits(eq("test-domain"), eq("test-type"), any(Instant.class)))
            .thenReturn(List.of(processId));

        worker.start();
        worker.checkWaitTimeouts();

        // Wait for virtual thread execution
        Thread.sleep(100);

        verify(processManager).handleWaitTimeout(processId);
    }

    @Test
    @DisplayName("should check deadlines and handle")
    void shouldCheckDeadlinesAndHandle() throws InterruptedException {
        UUID processId = UUID.randomUUID();
        when(processRepo.findExpiredDeadlines(eq("test-domain"), eq("test-type"), any(Instant.class)))
            .thenReturn(List.of(processId));

        worker.start();
        worker.checkDeadlines();

        // Wait for virtual thread execution
        Thread.sleep(100);

        verify(processManager).handleDeadlineExceeded(processId);
    }

    @Test
    @DisplayName("should not poll when stopped")
    void shouldNotPollWhenStopped() {
        // Worker not started

        worker.pollPendingProcesses();
        worker.pollRetries();
        worker.checkWaitTimeouts();
        worker.checkDeadlines();

        verifyNoInteractions(processRepo);
    }

    @Test
    @DisplayName("should skip managers with different execution model")
    void shouldSkipManagersWithDifferentExecutionModel() {
        when(processManager.getExecutionModel()).thenReturn("STEP_BASED");

        worker.start();
        worker.pollPendingProcesses();

        verifyNoInteractions(processRepo);
    }

    @Test
    @DisplayName("should handle exceptions during process execution")
    void shouldHandleExceptionsDuringProcessExecution() throws InterruptedException {
        UUID processId = UUID.randomUUID();
        when(processRepo.claimPendingProcesses(eq("test-domain"), eq("test-type"), anyInt()))
            .thenReturn(List.of(processId));
        doThrow(new RuntimeException("Test error")).when(processManager).resume(processId);

        worker.start();
        worker.pollPendingProcesses();

        // Wait for virtual thread execution - should not throw
        Thread.sleep(100);

        // Verify it was called even though it threw
        verify(processManager).resume(processId);
    }

    @Test
    @DisplayName("should handle exceptions during repository queries")
    void shouldHandleExceptionsDuringRepositoryQueries() {
        when(processRepo.claimPendingProcesses(anyString(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("Database error"));

        worker.start();

        // Should not throw
        assertDoesNotThrow(() -> worker.pollPendingProcesses());
    }

    @Test
    @DisplayName("should process multiple pending processes concurrently")
    void shouldProcessMultiplePendingProcessesConcurrently() throws InterruptedException {
        UUID processId1 = UUID.randomUUID();
        UUID processId2 = UUID.randomUUID();
        UUID processId3 = UUID.randomUUID();

        when(processRepo.claimPendingProcesses(eq("test-domain"), eq("test-type"), anyInt()))
            .thenReturn(List.of(processId1, processId2, processId3));

        AtomicInteger executionCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        doAnswer(invocation -> {
            executionCount.incrementAndGet();
            latch.countDown();
            return null;
        }).when(processManager).resume(any(UUID.class));

        worker.start();
        worker.pollPendingProcesses();

        // Wait for all virtual threads to complete
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(3, executionCount.get());
    }

    @Test
    @DisplayName("should work with multiple managers")
    void shouldWorkWithMultipleManagers() throws InterruptedException {
        ProcessStepManager<?> manager2 = mock(ProcessStepManager.class);
        when(manager2.getDomain()).thenReturn("domain2");
        when(manager2.getProcessType()).thenReturn("type2");
        when(manager2.getExecutionModel()).thenReturn("PROCESS_STEP");

        ProcessStepWorker multiWorker = new ProcessStepWorker(
            List.of(processManager, manager2), processRepo);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        when(processRepo.claimPendingProcesses(eq("test-domain"), eq("test-type"), anyInt()))
            .thenReturn(List.of(id1));
        when(processRepo.claimPendingProcesses(eq("domain2"), eq("type2"), anyInt()))
            .thenReturn(List.of(id2));

        multiWorker.start();
        multiWorker.pollPendingProcesses();

        Thread.sleep(100);

        verify(processManager).resume(id1);
        verify(manager2).resume(id2);

        multiWorker.stop();
    }

    @Test
    @DisplayName("should handle empty results from pending processes poll")
    void shouldHandleEmptyPendingResults() {
        when(processRepo.claimPendingProcesses(eq("test-domain"), eq("test-type"), anyInt()))
            .thenReturn(List.of());

        worker.start();
        worker.pollPendingProcesses();

        // Verify repository was called but manager resume was not
        verify(processRepo).claimPendingProcesses(anyString(), anyString(), anyInt());
        verify(processManager, never()).resume(any());
    }

    @Test
    @DisplayName("should handle empty results from retry poll")
    void shouldHandleEmptyRetryResults() {
        when(processRepo.claimDueForRetry(eq("test-domain"), eq("test-type"), any(Instant.class), anyInt()))
            .thenReturn(List.of());

        worker.start();
        worker.pollRetries();

        verify(processRepo).claimDueForRetry(anyString(), anyString(), any(Instant.class), anyInt());
        verify(processManager, never()).resume(any());
    }

    @Test
    @DisplayName("should handle empty results from wait timeouts check")
    void shouldHandleEmptyWaitTimeoutResults() {
        when(processRepo.findExpiredWaits(eq("test-domain"), eq("test-type"), any(Instant.class)))
            .thenReturn(List.of());

        worker.start();
        worker.checkWaitTimeouts();

        verify(processRepo).findExpiredWaits(anyString(), anyString(), any(Instant.class));
        verify(processManager, never()).handleWaitTimeout(any());
    }

    @Test
    @DisplayName("should handle empty results from deadlines check")
    void shouldHandleEmptyDeadlineResults() {
        when(processRepo.findExpiredDeadlines(eq("test-domain"), eq("test-type"), any(Instant.class)))
            .thenReturn(List.of());

        worker.start();
        worker.checkDeadlines();

        verify(processRepo).findExpiredDeadlines(anyString(), anyString(), any(Instant.class));
        verify(processManager, never()).handleDeadlineExceeded(any());
    }

    @Test
    @DisplayName("should handle exception during timeout handling")
    void shouldHandleExceptionDuringTimeoutHandling() throws InterruptedException {
        UUID processId = UUID.randomUUID();
        when(processRepo.findExpiredWaits(eq("test-domain"), eq("test-type"), any(Instant.class)))
            .thenReturn(List.of(processId));
        doThrow(new RuntimeException("Timeout handling error")).when(processManager).handleWaitTimeout(processId);

        worker.start();
        worker.checkWaitTimeouts();

        Thread.sleep(100);

        verify(processManager).handleWaitTimeout(processId);
    }

    @Test
    @DisplayName("should handle exception during deadline handling")
    void shouldHandleExceptionDuringDeadlineHandling() throws InterruptedException {
        UUID processId = UUID.randomUUID();
        when(processRepo.findExpiredDeadlines(eq("test-domain"), eq("test-type"), any(Instant.class)))
            .thenReturn(List.of(processId));
        doThrow(new RuntimeException("Deadline handling error")).when(processManager).handleDeadlineExceeded(processId);

        worker.start();
        worker.checkDeadlines();

        Thread.sleep(100);

        verify(processManager).handleDeadlineExceeded(processId);
    }

    @Test
    @DisplayName("should handle repository exception during retry poll")
    void shouldHandleRepositoryExceptionDuringRetryPoll() {
        when(processRepo.claimDueForRetry(anyString(), anyString(), any(Instant.class), anyInt()))
            .thenThrow(new RuntimeException("Database error"));

        worker.start();

        assertDoesNotThrow(() -> worker.pollRetries());
    }

    @Test
    @DisplayName("should handle repository exception during wait timeouts check")
    void shouldHandleRepositoryExceptionDuringWaitTimeoutsCheck() {
        when(processRepo.findExpiredWaits(anyString(), anyString(), any(Instant.class)))
            .thenThrow(new RuntimeException("Database error"));

        worker.start();

        assertDoesNotThrow(() -> worker.checkWaitTimeouts());
    }

    @Test
    @DisplayName("should handle repository exception during deadlines check")
    void shouldHandleRepositoryExceptionDuringDeadlinesCheck() {
        when(processRepo.findExpiredDeadlines(anyString(), anyString(), any(Instant.class)))
            .thenThrow(new RuntimeException("Database error"));

        worker.start();

        assertDoesNotThrow(() -> worker.checkDeadlines());
    }
}
