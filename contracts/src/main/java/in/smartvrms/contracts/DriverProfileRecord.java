package in.smartvrms.contracts;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DriverProfileRecord(
    UUID id,
    UUID companyId,
    UUID employeeId,
    String fullName,
    String contactNumber,
    String licenceNumber,
    List<String> licenceClasses,
    LocalDate licenceExpiry,
    int experienceYears,
    String dutyStatus,
    boolean isEmployed
) {
    public DriverProfileRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(contactNumber, "contactNumber");
        Objects.requireNonNull(licenceNumber, "licenceNumber");
        licenceClasses = licenceClasses == null ? List.of() : List.copyOf(licenceClasses);
        Objects.requireNonNull(licenceExpiry, "licenceExpiry");
        Objects.requireNonNull(dutyStatus, "dutyStatus");
    }
}
