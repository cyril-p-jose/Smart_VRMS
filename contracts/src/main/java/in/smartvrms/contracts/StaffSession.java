package in.smartvrms.contracts;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Authoritative active staff session. Role and company are resolved server-side. */
public record StaffSession(UUID userId, UUID companyId, String role, String email, Set<String> capabilities) {
    public StaffSession {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(role, "role");
        capabilities = capabilities == null ? Set.of() : Collections.unmodifiableSet(Set.copyOf(capabilities));
    }
    public boolean isAdministrator() {
        return "ADMINISTRATOR".equalsIgnoreCase(role);
    }
    public boolean isEmployee() {
        return "EMPLOYEE".equalsIgnoreCase(role);
    }
    public boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }
    @Override
    public String toString() {
        return "StaffSession[userId=" + userId + ", companyId=" + companyId + ", role=" + role + "]";
    }
}
