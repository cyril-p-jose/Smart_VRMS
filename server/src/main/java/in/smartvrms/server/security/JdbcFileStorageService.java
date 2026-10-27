package in.smartvrms.server.security;

import in.smartvrms.application.port.StoragePort;
import in.smartvrms.contracts.FileAccessTier;
import in.smartvrms.contracts.FileCategory;
import in.smartvrms.contracts.FileMetadataRecord;
import in.smartvrms.contracts.StaffSession;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class JdbcFileStorageService implements StoragePort {
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB
    public static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "application/pdf");

    private final DataSource dataSource;
    private final String storageBaseUrl;
    private final String serviceRoleKey;

    public JdbcFileStorageService(DataSource dataSource) {
        this(dataSource, "https://wvtnepxynjjbazczkukh.supabase.co/storage/v1", System.getenv("SUPABASE_SERVICE_ROLE_KEY"));
    }

    public JdbcFileStorageService(DataSource dataSource, String storageBaseUrl) {
        this(dataSource, storageBaseUrl, System.getenv("SUPABASE_SERVICE_ROLE_KEY"));
    }

    public JdbcFileStorageService(DataSource dataSource, String storageBaseUrl, String serviceRoleKey) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.storageBaseUrl = Objects.requireNonNull(storageBaseUrl, "storageBaseUrl");
        this.serviceRoleKey = serviceRoleKey;
    }

    public static String selectBucket(FileCategory category) {
        return switch (category) {
            case VEHICLE_PHOTO, STAFF_PHOTO, RECEIPT_IMAGE -> "vrms-private-media";
            case VEHICLE_DOC, DRIVER_DOC, CUSTOMER_DOC, INVOICE_PDF, PAYROLL_STATEMENT -> "vrms-private-documents";
        };
    }

    public static String determineExtension(String fileName, String contentType) {
        if (fileName != null && fileName.contains(".")) {
            String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (ext.matches("^[a-z0-9]{2,4}$")) return ext;
        }
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }

    @Override
    public FileMetadataRecord initiateUpload(StaffSession session, String fileName, String contentType, long sizeBytes, FileCategory category, FileAccessTier accessTier) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(accessTier, "accessTier");

        if (!ALLOWED_MIME_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported file type: " + contentType + ". Allowed: " + ALLOWED_MIME_TYPES);
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File size must be between 1 byte and 10MB (got " + sizeBytes + ")");
        }
        if (category == FileCategory.PAYROLL_STATEMENT && accessTier != FileAccessTier.ADMIN_ONLY) {
            throw new IllegalArgumentException("Payroll statement must be classified as ADMIN_ONLY");
        }
        if (accessTier == FileAccessTier.ADMIN_ONLY && !"ADMINISTRATOR".equals(session.role())) {
            throw new SecurityException("Forbidden: Employees cannot initiate ADMIN_ONLY file uploads");
        }

        String bucket = selectBucket(category);
        String ext = determineExtension(fileName, contentType);
        String storagePath = session.companyId() + "/" + category.name().toLowerCase(Locale.ROOT) + "/" + UUID.randomUUID() + "." + ext;

        String sql = """
            INSERT INTO vrms_private.file_metadata
                (company_id, uploaded_by, storage_bucket, storage_path, file_name, content_type, size_bytes, category, access_tier, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
            RETURNING id, created_at
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setObject(1, session.companyId());
                    s.setObject(2, session.userId());
                    s.setString(3, bucket);
                    s.setString(4, storagePath);
                    s.setString(5, fileName);
                    s.setString(6, contentType);
                    s.setLong(7, sizeBytes);
                    s.setString(8, category.name());
                    s.setString(9, accessTier.name());
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new IllegalStateException("Failed to insert file metadata");
                        UUID id = r.getObject(1, UUID.class);
                        Instant createdAt = r.getTimestamp(2).toInstant();
                        c.commit();
                        return new FileMetadataRecord(
                            id, session.companyId(), session.userId(), bucket, storagePath,
                            fileName, contentType, sizeBytes, category, accessTier, "PENDING", createdAt, null
                        );
                    }
                }
            } catch (Exception e) {
                c.rollback();
                throw new RuntimeException("Failed to initiate file upload", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error initiating upload", e);
        }
    }

    @Override
    public FileMetadataRecord finalizeUpload(StaffSession session, UUID fileId) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(fileId, "fileId");

        String sql = """
            UPDATE vrms_private.file_metadata
            SET status = 'FINALIZED', finalized_at = now()
            WHERE id = ? AND company_id = ? AND status = 'PENDING'
            RETURNING id, company_id, uploaded_by, storage_bucket, storage_path, file_name, content_type, size_bytes, category, access_tier, status, created_at, finalized_at
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setObject(1, fileId);
                    s.setObject(2, session.companyId());
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Pending file not found or already finalized: " + fileId);
                        FileMetadataRecord record = new FileMetadataRecord(
                            r.getObject(1, UUID.class),
                            r.getObject(2, UUID.class),
                            r.getObject(3, UUID.class),
                            r.getString(4),
                            r.getString(5),
                            r.getString(6),
                            r.getString(7),
                            r.getLong(8),
                            FileCategory.valueOf(r.getString(9)),
                            FileAccessTier.valueOf(r.getString(10)),
                            r.getString(11),
                            r.getTimestamp(12).toInstant(),
                            r.getTimestamp(13) != null ? r.getTimestamp(13).toInstant() : null
                        );
                        c.commit();
                        return record;
                    }
                }
            } catch (Exception e) {
                c.rollback();
                if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
                throw new RuntimeException("Failed to finalize upload", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error finalizing upload", e);
        }
    }

    @Override
    public Optional<FileMetadataRecord> getMetadata(StaffSession session, UUID fileId) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(fileId, "fileId");

        String sql = """
            SELECT id, company_id, uploaded_by, storage_bucket, storage_path, file_name, content_type, size_bytes, category, access_tier, status, created_at, finalized_at
            FROM vrms_private.file_metadata
            WHERE id = ? AND company_id = ?
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setObject(1, fileId);
                    s.setObject(2, session.companyId());
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            FileAccessTier tier = FileAccessTier.valueOf(r.getString("access_tier"));
                            if (tier == FileAccessTier.ADMIN_ONLY && !"ADMINISTRATOR".equals(session.role())) {
                                c.commit();
                                return Optional.empty(); // RLS / Security isolation for Employees
                            }
                            FileMetadataRecord record = new FileMetadataRecord(
                                r.getObject("id", UUID.class),
                                r.getObject("company_id", UUID.class),
                                r.getObject("uploaded_by", UUID.class),
                                r.getString("storage_bucket"),
                                r.getString("storage_path"),
                                r.getString("file_name"),
                                r.getString("content_type"),
                                r.getLong("size_bytes"),
                                FileCategory.valueOf(r.getString("category")),
                                tier,
                                r.getString("status"),
                                r.getTimestamp("created_at").toInstant(),
                                r.getTimestamp("finalized_at") != null ? r.getTimestamp("finalized_at").toInstant() : null
                            );
                            c.commit();
                            return Optional.of(record);
                        }
                    }
                }
                c.commit();
                return Optional.empty();
            } catch (Exception e) {
                c.rollback();
                throw new RuntimeException("Failed to read file metadata", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error reading metadata", e);
        }
    }

    @Override
    public String generateSignedDownloadUrl(StaffSession session, UUID fileId, int expiresInSeconds) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(fileId, "fileId");

        FileMetadataRecord meta = getMetadata(session, fileId)
            .orElseThrow(() -> new SecurityException("Forbidden or file metadata not found"));

        if (meta.accessTier() == FileAccessTier.ADMIN_ONLY && !"ADMINISTRATOR".equals(session.role())) {
            throw new SecurityException("Forbidden: Employees cannot access ADMIN_ONLY files");
        }

        if (serviceRoleKey == null || serviceRoleKey.isBlank()) {
            throw new IllegalStateException("Private Storage signing is not configured. SUPABASE_SERVICE_ROLE_KEY is required to generate short-lived signed download URLs.");
        }

        try {
            int duration = Math.max(60, Math.min(expiresInSeconds, 3600));
            String signEndpoint = storageBaseUrl.replaceAll("/+$", "") + "/object/sign/" + meta.storageBucket() + "/" + meta.storagePath();
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            String payload = "{\"expiresIn\":" + duration + "}";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(signEndpoint))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .header("apikey", serviceRoleKey)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Storage provider rejected signed URL generation (status " + response.statusCode() + ").");
            }
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("signedURL")) {
                throw new IllegalStateException("Storage provider response did not contain signedURL.");
            }
            String signedPath = json.get("signedURL").getAsString();
            if (signedPath.startsWith("http://") || signedPath.startsWith("https://")) {
                return signedPath;
            }
            return storageBaseUrl.replaceAll("/+$", "") + (signedPath.startsWith("/") ? "" : "/") + signedPath;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Storage URL signing interrupted", ie);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException ise) throw ise;
            throw new IllegalStateException("Failed to communicate with storage provider", ex);
        }
    }

    private static void setContext(Connection c, StaffSession session) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT set_config('vrms.user_id', ?, true), set_config('vrms.company_id', ?, true), set_config('vrms.role', ?, true)")) {
            s.setString(1, session.userId().toString());
            s.setString(2, session.companyId().toString());
            s.setString(3, session.role());
            s.execute();
        }
    }
}
