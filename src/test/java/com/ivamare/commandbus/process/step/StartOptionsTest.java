package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StartOptions")
class StartOptionsTest {

    @Test
    @DisplayName("defaults should create immediate execution options")
    void defaultsShouldCreateImmediateExecutionOptions() {
        StartOptions options = StartOptions.defaults();

        assertTrue(options.isExecuteImmediately());
        assertFalse(options.isDeferred());
    }

    @Test
    @DisplayName("deferred should create deferred execution options")
    void deferredShouldCreateDeferredExecutionOptions() {
        StartOptions options = StartOptions.deferred();

        assertFalse(options.isExecuteImmediately());
        assertTrue(options.isDeferred());
    }

    @Test
    @DisplayName("builder should create immediate execution by default")
    void builderShouldCreateImmediateExecutionByDefault() {
        StartOptions options = StartOptions.builder().build();

        assertTrue(options.isExecuteImmediately());
    }

    @Test
    @DisplayName("builder should allow setting executeImmediately to false")
    void builderShouldAllowSettingExecuteImmediatelyToFalse() {
        StartOptions options = StartOptions.builder()
            .executeImmediately(false)
            .build();

        assertFalse(options.isExecuteImmediately());
        assertTrue(options.isDeferred());
    }

    @Test
    @DisplayName("builder deferred method should set deferred execution")
    void builderDeferredMethodShouldSetDeferredExecution() {
        StartOptions options = StartOptions.builder()
            .deferred()
            .build();

        assertFalse(options.isExecuteImmediately());
        assertTrue(options.isDeferred());
    }

    @Test
    @DisplayName("isExecuteImmediately and isDeferred should be mutually exclusive")
    void isExecuteImmediatelyAndIsDeferredShouldBeMutuallyExclusive() {
        StartOptions immediate = StartOptions.defaults();
        assertTrue(immediate.isExecuteImmediately());
        assertFalse(immediate.isDeferred());

        StartOptions deferred = StartOptions.deferred();
        assertFalse(deferred.isExecuteImmediately());
        assertTrue(deferred.isDeferred());
    }
}
