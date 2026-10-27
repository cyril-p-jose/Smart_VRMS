package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable representation of a queued transactional outbox message (FR-BOOK-011, FR-MSG-001, FR-MSG-002).
 */
public record OutboxMessageRecord(
        UUID id,
        UUID companyId,
        String eventType,
        String idempotencyKey,
        String recipientEmail,
        String recipientRole,
        String subject,
        String templateName,
        String payloadJson,
        UUID attachmentDocId,
        String status,
        Instant leaseUntil,
        String claimedBy,
        int attemptsCount,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        String lastError,
        Instant createdAt
) {
    public OutboxMessageRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(recipientEmail, "recipientEmail");
        Objects.requireNonNull(recipientRole, "recipientRole");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(templateName, "templateName");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(status, "status");
    }
}
