package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of an outbox message delivery attempt (FR-MSG-001).
 */
public record DeliveryAttemptRecord(
        UUID id,
        UUID companyId,
        UUID outboxId,
        int attemptNumber,
        Instant startedAt,
        Instant finishedAt,
        String providerName,
        Integer providerResponseCode,
        String providerMessageId,
        String errorMessage,
        String outcome
) {
    public DeliveryAttemptRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(outboxId, "outboxId");
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(outcome, "outcome");
    }
}
