package com.ivamare.commandbus.handler;

import com.ivamare.commandbus.model.Command;
import com.ivamare.commandbus.model.HandlerContext;
import com.ivamare.commandbus.exception.PermanentCommandException;
import com.ivamare.commandbus.exception.TransientCommandException;

/**
 * Functional interface for command handlers.
 *
 * <p>Handlers process commands and optionally return a result that is
 * included in the reply message.
 *
 * <p>Handlers can throw:
 * <ul>
 *   <li>{@link TransientCommandException} - for retryable failures</li>
 *   <li>{@link PermanentCommandException} - for non-retryable failures</li>
 *   <li>Any other exception - treated as transient failure</li>
 * </ul>
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * Process a command.
     *
     * @param command The command to process
     * @param context Handler context with metadata and utilities
     * @return Optional result to include in reply (may be null)
     * @throws Exception on processing failure
     */
    Object handle(Command command, HandlerContext context) throws Exception;
}
