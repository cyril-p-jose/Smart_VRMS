package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditEventRecord(
    UUID id,
    UUID companyId,
    UUID actorUserId,
    String action,
    String resourceType,
    String resourceId,
    String detailsJson,
    Instant occurredAt
) {
    public AuditEventRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        if (detailsJson == null) detailsJson = "{}";
    }
}
