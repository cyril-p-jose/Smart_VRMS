package in.smartvrms.application.port;

import in.smartvrms.contracts.FileAccessTier;
import in.smartvrms.contracts.FileCategory;
import in.smartvrms.contracts.FileMetadataRecord;
import in.smartvrms.contracts.StaffSession;

import java.util.Optional;
import java.util.UUID;

public interface StoragePort {
    FileMetadataRecord initiateUpload(StaffSession session, String fileName, String contentType, long sizeBytes, FileCategory category, FileAccessTier accessTier);
    FileMetadataRecord finalizeUpload(StaffSession session, UUID fileId);
    Optional<FileMetadataRecord> getMetadata(StaffSession session, UUID fileId);
    String generateSignedDownloadUrl(StaffSession session, UUID fileId, int expiresInSeconds);
}
