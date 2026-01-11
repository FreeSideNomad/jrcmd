package com.ivamare.commandbus.process;

import com.ivamare.commandbus.model.Reply;
import com.ivamare.commandbus.model.ReplyOutcome;

import java.util.Map;
import java.util.function.Function;

/**
 * Typed wrapper for command response data.
 *
 * @param <TResult> The result data type
 * @param outcome The reply outcome
 * @param result The typed result data (nullable)
 * @param errorCode Application error code (nullable)
 * @param errorMessage Error message (nullable)
 */
public record ProcessResponse<TResult>(
    ReplyOutcome outcome,
    TResult result,
    String errorCode,
    String errorMessage
) {
    /**
     * Create from a Reply object with type conversion.
     *
     * @param reply The raw reply
     * @param resultMapper Function to convert reply data map to typed result
     * @param <TResult> The result type
     * @return Typed process response
     */
    public static <TResult> ProcessResponse<TResult> fromReply(
            Reply reply,
            Function<Map<String, Object>, TResult> resultMapper) {
        TResult result = null;
        if (reply.data() != null) {
            result = resultMapper.apply(reply.data());
        }
        return new ProcessResponse<>(
            reply.outcome(),
            result,
            reply.errorCode(),
            reply.errorMessage()
        );
    }

    /**
     * Create a success response with typed result.
     */
    public static <TResult> ProcessResponse<TResult> success(TResult result) {
        return new ProcessResponse<>(ReplyOutcome.SUCCESS, result, null, null);
    }

    /**
     * Create a failed response with error information.
     */
    public static <TResult> ProcessResponse<TResult> failed(String errorCode, String errorMessage) {
        return new ProcessResponse<>(ReplyOutcome.FAILED, null, errorCode, errorMessage);
    }

    /**
     * Check if response indicates success.
     */
    public boolean isSuccess() {
        return outcome == ReplyOutcome.SUCCESS;
    }

    /**
     * Check if response indicates failure.
     */
    public boolean isFailed() {
        return outcome == ReplyOutcome.FAILED;
    }

    /**
     * Check if response indicates cancellation.
     */
    public boolean isCanceled() {
        return outcome == ReplyOutcome.CANCELED;
    }
}
