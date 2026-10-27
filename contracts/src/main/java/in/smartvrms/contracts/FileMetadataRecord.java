package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FileMetadataRecord(
    UUID id,
    UUID companyId,
    UUID uploadedBy,
    String storageBucket,
    String storagePath,
    String fileName,
    String contentType,
    long sizeBytes,
    FileCategory category,
    FileAccessTier accessTier,
    String status,
    Instant createdAt,
    Instant finalizedAt
) {
    public FileMetadataRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(storageBucket, "storageBucket");
        Objects.requireNonNull(storagePath, "storagePath");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(accessTier, "accessTier");
        Objects.requireNonNull(status, "status");
    }
}
