package in.smartvrms.contracts;

import java.time.Instant;

/**
 * Health and lag metrics for the outbox worker and automation scheduling (FR-MSG-001, NFR-HOST-001).
 */
public record WorkerHealthStatus(
        Instant lastScanTime,
        int pendingMessagesCount,
        int claimedMessagesCount,
        int failedMessagesCount,
        int acceptedMessagesCount,
        long oldestPendingAgeSeconds,
        boolean isLagging
) {
}
