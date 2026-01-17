package com.ivamare.commandbus.process.step.exceptions;

/**
 * Control flow exception thrown when a wait condition is not yet satisfied.
 *
 * <p>This exception is used for control flow in the ProcessStepManager
 * to pause execution until an async response arrives. It is caught by
 * the framework and triggers:
 * <ul>
 *   <li>State persistence with WAITING_FOR_ASYNC status</li>
 *   <li>Return of control to caller</li>
 *   <li>Process will resume when processAsyncResponse() is called</li>
 * </ul>
 *
 * <p>This is similar to coroutine suspension in languages like Kotlin.
 */
public class WaitConditionNotMetException extends RuntimeException {

    private final String waitName;

    /**
     * Create a wait condition not met exception.
     *
     * @param waitName The name of the wait condition that is pending
     */
    public WaitConditionNotMetException(String waitName) {
        super("Wait condition not met: " + waitName);
        this.waitName = waitName;
    }

    /**
     * Get the name of the wait condition.
     */
    public String getWaitName() {
        return waitName;
    }
}
