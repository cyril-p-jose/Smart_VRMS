package in.smartvrms.contracts;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record EmployeeRecord(
    UUID id,
    UUID companyId,
    UUID authUserId,
    String fullName,
    String contactNumber,
    String email,
    String address,
    LocalDate joiningDate,
    String employmentStatus,
    boolean isDriver,
    UUID photoFileId,
    long version
) {
    public EmployeeRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(contactNumber, "contactNumber");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(joiningDate, "joiningDate");
        Objects.requireNonNull(employmentStatus, "employmentStatus");
    }
}
