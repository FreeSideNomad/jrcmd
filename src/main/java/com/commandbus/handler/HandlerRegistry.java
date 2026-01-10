package com.commandbus.handler;

import com.commandbus.model.Command;
import com.commandbus.model.HandlerContext;

import java.util.List;
import java.util.Optional;

/**
 * Registry for command handlers.
 *
 * <p>Maps (domain, commandType) pairs to handler functions. The registry
 * discovers handlers via Spring component scanning and @Handler annotations.
 */
public interface HandlerRegistry {

    /**
     * Register a handler for a command type.
     *
     * @param domain The domain (e.g., "payments")
     * @param commandType The command type (e.g., "DebitAccount")
     * @param handler The handler function
     * @throws com.commandbus.exception.HandlerAlreadyRegisteredException if handler exists
     */
    void register(String domain, String commandType, CommandHandler handler);

    /**
     * Get the handler for a command type.
     *
     * @param domain The domain
     * @param commandType The command type
     * @return Optional containing the handler if found
     */
    Optional<CommandHandler> get(String domain, String commandType);

    /**
     * Get the handler for a command type, throwing if not found.
     *
     * @param domain The domain
     * @param commandType The command type
     * @return The handler
     * @throws com.commandbus.exception.HandlerNotFoundException if not found
     */
    CommandHandler getOrThrow(String domain, String commandType);

    /**
     * Dispatch a command to its registered handler.
     *
     * @param command The command to dispatch
     * @param context Handler context
     * @return Result from handler (may be null)
     * @throws com.commandbus.exception.HandlerNotFoundException if no handler registered
     * @throws Exception from handler execution
     */
    Object dispatch(Command command, HandlerContext context) throws Exception;

    /**
     * Check if a handler is registered.
     *
     * @param domain The domain
     * @param commandType The command type
     * @return true if handler is registered
     */
    boolean hasHandler(String domain, String commandType);

    /**
     * Get list of all registered (domain, commandType) pairs.
     *
     * @return List of registered handler keys
     */
    List<HandlerKey> registeredHandlers();

    /**
     * Remove all handlers. Useful for testing.
     */
    void clear();

    /**
     * Scan a Spring bean for @Handler annotated methods and register them.
     *
     * @param bean The bean to scan
     * @return List of registered handler keys
     */
    List<HandlerKey> registerBean(Object bean);

    /**
     * Key for handler lookup.
     *
     * @param domain The domain
     * @param commandType The command type
     */
    record HandlerKey(String domain, String commandType) {}
}
