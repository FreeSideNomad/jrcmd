package com.ivamare.commandbus.process;

import java.util.Map;

/**
 * Protocol for typed process state.
 *
 * <p>Process state classes must implement toMap() for JSON serialization
 * to/from database storage. Implementations should also provide a static
 * {@code fromMap(Map<String, Object>)} factory method for deserialization.
 *
 * <p>Example implementation:
 * <pre>{@code
 * public record OrderState(
 *     String orderId,
 *     String customerId,
 *     BigDecimal amount
 * ) implements ProcessState {
 *
 *     @Override
 *     public Map<String, Object> toMap() {
 *         return Map.of(
 *             "orderId", orderId,
 *             "customerId", customerId,
 *             "amount", amount.toString()
 *         );
 *     }
 *
 *     public static OrderState fromMap(Map<String, Object> data) {
 *         return new OrderState(
 *             (String) data.get("orderId"),
 *             (String) data.get("customerId"),
 *             new BigDecimal((String) data.get("amount"))
 *         );
 *     }
 * }
 * }</pre>
 */
public interface ProcessState {

    /**
     * Serialize state to JSON-compatible map.
     *
     * @return Map representation suitable for JSON serialization
     */
    Map<String, Object> toMap();
}
