package com.commandbus.ops;

import com.commandbus.model.TroubleshootingItem;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operations for managing commands in the troubleshooting queue.
 *
 * <p>The troubleshooting queue contains commands that:
 * <ul>
 *   <li>Failed with a permanent error</li>
 *   <li>Exhausted all retry attempts</li>
 * </ul>
 *
 * <p>Operators can:
 * <ul>
 *   <li>List failed commands</li>
 *   <li>Retry commands (re-enqueue with reset attempts)</li>
 *   <li>Cancel commands (mark as canceled, send reply)</li>
 *   <li>Complete commands manually (mark as completed, send success reply)</li>
 * </ul>
 *
 * <p>Example:
 * <pre>
 * // List failed commands
 * List&lt;TroubleshootingItem&gt; items = tsq.listTroubleshooting("payments", null, 50, 0);
 *
 * // Retry a command
 * long newMsgId = tsq.operatorRetry("payments", commandId, "admin");
 *
 * // Cancel with reason
 * tsq.operatorCancel("payments", commandId, "Invalid account", "admin");
 *
 * // Manually complete
 * tsq.operatorComplete("payments", commandId, Map.of("manual", true), "admin");
 * </pre>
 */
public interface TroubleshootingQueue {

    /**
     * List commands in the troubleshooting queue for a domain.
     *
     * @param domain The domain to list troubleshooting items for
     * @param commandType Optional filter by command type (nullable)
     * @param limit Maximum number of items to return
     * @param offset Number of items to skip for pagination
     * @return List of TroubleshootingItem objects ordered by updated_at DESC
     */
    List<TroubleshootingItem> listTroubleshooting(
        String domain,
        String commandType,
        int limit,
        int offset
    );

    /**
     * Count commands in the troubleshooting queue for a domain.
     *
     * @param domain The domain
     * @param commandType Optional filter by command type (nullable)
     * @return Number of commands in troubleshooting queue
     */
    int countTroubleshooting(String domain, String commandType);

    /**
     * List domains that have commands in the troubleshooting queue.
     *
     * @return List of domain names
     */
    List<String> listDomains();

    /**
     * List all TSQ entries across domains with pagination.
     *
     * @param limit Maximum items to return
     * @param offset Items to skip
     * @param domain Optional domain filter (nullable)
     * @return TroubleshootingListResult with items, total count, and command IDs
     */
    TroubleshootingListResult listAllTroubleshooting(int limit, int offset, String domain);

    /**
     * Get the domain for a command ID.
     *
     * @param commandId The command ID
     * @return Domain name
     * @throws com.commandbus.exception.CommandNotFoundException if not found
     */
    String getCommandDomain(UUID commandId);

    /**
     * Retry a command from the troubleshooting queue.
     *
     * <p>Retrieves the original payload from archive, re-enqueues to PGMQ,
     * resets attempts to 0, sets status to PENDING.
     *
     * @param domain The domain of the command
     * @param commandId The command ID to retry
     * @param operator Optional operator identity for audit trail (nullable)
     * @return New PGMQ message ID
     * @throws com.commandbus.exception.CommandNotFoundException if command not found
     * @throws com.commandbus.exception.InvalidOperationException if not in TSQ
     */
    long operatorRetry(String domain, UUID commandId, String operator);

    /**
     * Cancel a command in the troubleshooting queue.
     *
     * <p>Sets status to CANCELED, sends CANCELED reply if reply_to configured.
     *
     * @param domain The domain of the command
     * @param commandId The command ID to cancel
     * @param reason Reason for cancellation (required)
     * @param operator Optional operator identity for audit trail (nullable)
     * @throws com.commandbus.exception.CommandNotFoundException if command not found
     * @throws com.commandbus.exception.InvalidOperationException if not in TSQ
     */
    void operatorCancel(String domain, UUID commandId, String reason, String operator);

    /**
     * Manually complete a command in the troubleshooting queue.
     *
     * <p>Sets status to COMPLETED, sends SUCCESS reply if reply_to configured.
     *
     * @param domain The domain of the command
     * @param commandId The command ID to complete
     * @param resultData Optional result data to include in reply (nullable)
     * @param operator Optional operator identity for audit trail (nullable)
     * @throws com.commandbus.exception.CommandNotFoundException if command not found
     * @throws com.commandbus.exception.InvalidOperationException if not in TSQ
     */
    void operatorComplete(String domain, UUID commandId, Map<String, Object> resultData, String operator);

    /**
     * Result of listing troubleshooting items across domains.
     *
     * @param items List of troubleshooting items
     * @param totalCount Total count across all domains
     * @param commandIds All command IDs in TSQ
     */
    record TroubleshootingListResult(
        List<TroubleshootingItem> items,
        int totalCount,
        List<UUID> commandIds
    ) {}
}
