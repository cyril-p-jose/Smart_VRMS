package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CustomerVisitRecord(
    UUID id,
    UUID companyId,
    UUID customerId,
    int ageAtVisit,
    Instant visitDate,
    String purpose
) {
    public CustomerVisitRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(visitDate, "visitDate");
        if (ageAtVisit < 18 || ageAtVisit > 120) {
            throw new IllegalArgumentException("Age at visit must be between 18 and 120 (got " + ageAtVisit + ")");
        }
    }
}
