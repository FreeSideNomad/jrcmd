package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BatchOptions")
class BatchOptionsTest {

    @Test
    @DisplayName("defaults should create deferred execution options")
    void defaultsShouldCreateDeferredExecutionOptions() {
        BatchOptions options = BatchOptions.defaults();

        assertFalse(options.isExecuteImmediately());
        assertTrue(options.isDeferred());
    }

    @Test
    @DisplayName("immediate should create immediate execution options")
    void immediateShouldCreateImmediateExecutionOptions() {
        BatchOptions options = BatchOptions.immediate();

        assertTrue(options.isExecuteImmediately());
        assertFalse(options.isDeferred());
    }

    @Test
    @DisplayName("builder should create deferred execution by default")
    void builderShouldCreateDeferredExecutionByDefault() {
        BatchOptions options = BatchOptions.builder().build();

        assertFalse(options.isExecuteImmediately());
        assertTrue(options.isDeferred());
    }

    @Test
    @DisplayName("builder should allow setting executeImmediately to true")
    void builderShouldAllowSettingExecuteImmediatelyToTrue() {
        BatchOptions options = BatchOptions.builder()
            .executeImmediately(true)
            .build();

        assertTrue(options.isExecuteImmediately());
        assertFalse(options.isDeferred());
    }

    @Test
    @DisplayName("BatchOptions defaults should differ from StartOptions defaults")
    void batchOptionsDefaultsShouldDifferFromStartOptionsDefaults() {
        // This is an important behavioral distinction:
        // - StartOptions.defaults() = immediate (good for API calls)
        // - BatchOptions.defaults() = deferred (good for background processing)

        StartOptions startOptions = StartOptions.defaults();
        BatchOptions batchOptions = BatchOptions.defaults();

        assertTrue(startOptions.isExecuteImmediately(), "StartOptions should default to immediate");
        assertFalse(batchOptions.isExecuteImmediately(), "BatchOptions should default to deferred");
    }

    @Test
    @DisplayName("isExecuteImmediately and isDeferred should be mutually exclusive")
    void isExecuteImmediatelyAndIsDeferredShouldBeMutuallyExclusive() {
        BatchOptions deferred = BatchOptions.defaults();
        assertFalse(deferred.isExecuteImmediately());
        assertTrue(deferred.isDeferred());

        BatchOptions immediate = BatchOptions.immediate();
        assertTrue(immediate.isExecuteImmediately());
        assertFalse(immediate.isDeferred());
    }
}
