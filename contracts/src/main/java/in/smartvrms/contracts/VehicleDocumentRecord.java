package in.smartvrms.contracts;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record VehicleDocumentRecord(
    UUID id,
    UUID companyId,
    UUID vehicleId,
    String docType,
    UUID fileId,
    LocalDate issueDate,
    LocalDate expiryDate,
    int version
) {
    public VehicleDocumentRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(docType, "docType");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(issueDate, "issueDate");
    }
}
