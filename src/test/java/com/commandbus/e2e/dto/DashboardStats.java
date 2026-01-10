package com.commandbus.e2e.dto;

/**
 * Dashboard statistics overview.
 */
public record DashboardStats(
    long pendingCommands,
    long inProgressCommands,
    long tsqCount,
    long activeBatches,
    long completedBatches,
    long runningProcesses,
    long waitingProcesses,
    long totalQueues,
    long totalMessages
) {}
