package com.ivamare.commandbus.handler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a command handler.
 *
 * <p>Methods annotated with @Handler are automatically discovered and registered
 * by the HandlerRegistry when component scanning is enabled.
 *
 * <p>Handler methods must have the signature:
 * <pre>
 * Object handleXxx(Command command, HandlerContext context)
 * </pre>
 *
 * <p>The return value is serialized as JSON and included in the reply message
 * if reply_to is configured. Return null for no result.
 *
 * <p>Example:
 * <pre>
 * {@literal @}Component
 * public class PaymentHandlers {
 *
 *     {@literal @}Handler(domain = "payments", commandType = "DebitAccount")
 *     public Map&lt;String, Object&gt; handleDebit(Command command, HandlerContext context) {
 *         var amount = (Integer) command.data().get("amount");
 *         // Process debit...
 *         return Map.of("status", "debited", "balance", 900);
 *     }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Handler {

    /**
     * The domain this handler processes commands for.
     *
     * @return domain name (e.g., "payments")
     */
    String domain();

    /**
     * The command type this handler processes.
     *
     * @return command type (e.g., "DebitAccount")
     */
    String commandType();
}
