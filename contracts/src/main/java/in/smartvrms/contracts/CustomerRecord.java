package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CustomerRecord(
    UUID id,
    UUID companyId,
    String fullName,
    String mobileNumber,
    String email,
    String address,
    Instant createdAt,
    long version
) {
    public CustomerRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(mobileNumber, "mobileNumber");
        Objects.requireNonNull(address, "address");
    }
}
