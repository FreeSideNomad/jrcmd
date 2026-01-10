package com.commandbus.e2e.dto;

/**
 * Command statistics by status for queue stats page.
 */
public record CommandStatusStats(
    long pending,
    long inProgress,
    long completed,
    long failed,
    long tsqCount
) {}
