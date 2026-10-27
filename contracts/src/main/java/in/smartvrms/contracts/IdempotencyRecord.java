package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IdempotencyRecord(
    UUID id,
    UUID companyId,
    String idempotencyKey,
    UUID actorUserId,
    String operation,
    String requestHash,
    String status,
    Integer responseStatus,
    String responseBody,
    Instant createdAt,
    Instant expiresAt
) {
    public IdempotencyRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
