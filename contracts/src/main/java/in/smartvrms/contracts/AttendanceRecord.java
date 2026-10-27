package in.smartvrms.contracts;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record AttendanceRecord(
    UUID id,
    UUID companyId,
    UUID employeeId,
    LocalDate recordDate,
    String status,
    String notes,
    UUID recordedBy
) {
    public AttendanceRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(recordDate, "recordDate");
        Objects.requireNonNull(status, "status");
    }
}
