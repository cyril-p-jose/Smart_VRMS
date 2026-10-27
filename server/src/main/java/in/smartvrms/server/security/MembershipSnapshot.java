package in.smartvrms.server.security;

import java.util.Objects;
import java.util.UUID;

/** Server-internal result of a future authoritative membership lookup, never a request DTO. */
record MembershipSnapshot(UUID authUserId,UUID companyId,Role role,boolean active) {
    enum Role { ADMINISTRATOR, EMPLOYEE }
    MembershipSnapshot {
        Objects.requireNonNull(authUserId); Objects.requireNonNull(companyId); Objects.requireNonNull(role);
    }
}
