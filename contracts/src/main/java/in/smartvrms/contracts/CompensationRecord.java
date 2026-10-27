package in.smartvrms.contracts;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record CompensationRecord(
    UUID id,
    UUID companyId,
    UUID employeeId,
    String arrangementType,
    long baseRatePaise,
    long tripAllowancePaise,
    LocalDate effectiveFrom
) {
    public CompensationRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(arrangementType, "arrangementType");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    }
}
