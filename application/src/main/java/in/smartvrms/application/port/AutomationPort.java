package in.smartvrms.application.port;

import in.smartvrms.contracts.DeliveryAttemptRecord;
import in.smartvrms.contracts.OutboxMessageRecord;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.contracts.WorkerHealthStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port for transactional outbox management, worker job claims, and document expiry scans.
 */
public interface AutomationPort {
    /**
     * Enqueues an outbox message within a transaction with an idempotency key.
     */
    OutboxMessageRecord enqueueMessage(
            StaffSession session,
            String eventType,
            String idempotencyKey,
            String recipientEmail,
            String recipientRole,
            String subject,
            String templateName,
            String payloadJson,
            UUID attachmentDocId
    );

    /**
     * Claims a batch of ready messages for processing using a lease.
     */
    List<OutboxMessageRecord> claimBatch(String workerId, int limit, int leaseDurationSeconds);

    /**
     * Records successful provider acceptance of a message.
     */
    void markAccepted(UUID messageId, String providerName, int responseCode, String messageIdRef);

    /**
     * Records a failure with retry backoff or permanent failure.
     */
    void markFailed(UUID messageId, String providerName, String errorMessage, boolean permanent);

    /**
     * Scans vehicle documents against current time in Asia/Kolkata and queues reminders (FR-DOC-003, FR-DOC-004).
     * Strictly enforces:
     * - Exactly 72h excluded
     * - 0 < remaining < 72h queued as LESS_THAN_72H (once per version)
     * - remaining <= 0 queued as EXPIRED (once per version)
     * - Supersedes pending older-version reminder if document was renewed
     * Returns count of newly queued reminder messages.
     */
    int scanDocumentReminders(StaffSession session, Instant clockTime);

    /**
     * Retrieves health status and queue lag of the outbox processor.
     */
    WorkerHealthStatus getWorkerHealth(StaffSession session);

    /**
     * Retrieves recent delivery attempts for an outbox message.
     */
    List<DeliveryAttemptRecord> listAttempts(StaffSession session, UUID messageId);

    /**
     * Lists recent outbox messages for the authenticated company.
     */
    default List<OutboxMessageRecord> listRecentMessages(StaffSession session, int limit) {
        return List.of();
    }
}
