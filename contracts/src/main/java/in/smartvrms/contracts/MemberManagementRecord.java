package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MemberManagementRecord(
    UUID authUserId,
    UUID companyId,
    String role,
    boolean active,
    UUID createdBy,
    Instant createdAt,
    long version
) {
    public MemberManagementRecord {
        Objects.requireNonNull(authUserId, "authUserId");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(role, "role");
    }
}
