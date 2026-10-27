package in.smartvrms.server.recovery;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Experimental plaintext export and archive-integrity helper only. This is NOT a production backup or restore
 * implementation: backup creation is disabled, private storage objects are not copied, and no database restore occurs.
 */
public final class BackupAndRecoveryManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record BackupManifest(
            UUID backupId,
            UUID companyId,
            String schemaVersion,
            Instant createdAt,
            Map<String, Integer> tableRecordCounts,
            Map<String, String> fileChecksumsSha256,
            long totalSizeBytes,
            String encryptionAlgorithm
    ) {}

    public record ArchiveIntegrityCheck(
            UUID backupId,
            boolean integrityVerified,
            boolean recordsReconciled,
            boolean fileChecksumsMatched,
            long verificationElapsedMillis,
            long archiveAgeSeconds,
            List<String> discrepancies
    ) {}

    /**
     * Fails closed until a complete encrypted database-and-Storage backup/restore implementation is provided.
     */
    public static BackupManifest createBackup(Connection conn, UUID companyId, Path exportDir, List<Path> privateFilesToInclude) throws SQLException, IOException {
        throw new UnsupportedOperationException(
                "Production backup is unavailable: this legacy exporter is plaintext and does not copy private Storage objects. No backup was created."
        );

    }

    /**
     * Checks the integrity of a previously generated local export. This does not restore a database or measure RTO/RPO.
     */
    public static ArchiveIntegrityCheck verifyLocalArchiveIntegrity(Path exportDir, Map<String, Integer> expectedCounts, Map<String, byte[]> testStorageFiles) throws IOException {
        long startTime = System.currentTimeMillis();
        Path manifestPath = exportDir.resolve("backup_manifest.json");
        if (!Files.exists(manifestPath)) {
            throw new IllegalArgumentException("Backup manifest not found at: " + manifestPath);
        }

        String manifestJson = Files.readString(manifestPath, StandardCharsets.UTF_8);
        BackupManifest manifest = GSON.fromJson(manifestJson, BackupManifest.class);

        List<String> discrepancies = new ArrayList<>();
        boolean integrityVerified = true;
        boolean recordsReconciled = true;
        boolean filesMatched = true;

        // Verify SHA-256 of all table exports
        for (Map.Entry<String, String> entry : manifest.fileChecksumsSha256().entrySet()) {
            String relPath = entry.getKey();
            String expectedSha = entry.getValue();

            if (!relPath.startsWith("storage/")) {
                Path filePath = exportDir.resolve(relPath);
                if (!Files.exists(filePath)) {
                    integrityVerified = false;
                    discrepancies.add("Missing exported table file: " + relPath);
                } else {
                    byte[] content = Files.readAllBytes(filePath);
                    String actualSha = sha256Hex(content);
                    if (!expectedSha.equalsIgnoreCase(actualSha)) {
                        integrityVerified = false;
                        discrepancies.add("SHA-256 mismatch for " + relPath + " (expected=" + expectedSha + ", actual=" + actualSha + ")");
                    }
                }
            } else if (testStorageFiles != null) {
                String fileName = relPath.substring("storage/".length());
                byte[] data = testStorageFiles.get(fileName);
                if (data != null) {
                    String actualSha = sha256Hex(data);
                    if (!expectedSha.equalsIgnoreCase(actualSha)) {
                        filesMatched = false;
                        discrepancies.add("Private storage object hash mismatch for: " + fileName);
                    }
                }
            }
        }

        // Reconcile record counts against expected counts
        if (expectedCounts != null) {
            for (Map.Entry<String, Integer> exp : expectedCounts.entrySet()) {
                int backedUp = manifest.tableRecordCounts().getOrDefault(exp.getKey(), 0);
                if (backedUp != exp.getValue()) {
                    recordsReconciled = false;
                    discrepancies.add("Record count mismatch for " + exp.getKey() + ": expected " + exp.getValue() + ", got " + backedUp);
                }
            }
        }

        long verificationElapsedMillis = System.currentTimeMillis() - startTime;
        long archiveAgeSeconds = Math.max(0, Instant.now().getEpochSecond() - manifest.createdAt().getEpochSecond());

        return new ArchiveIntegrityCheck(
                manifest.backupId(),
                integrityVerified,
                recordsReconciled,
                filesMatched,
                verificationElapsedMillis,
                archiveAgeSeconds,
                discrepancies
        );
    }

    /**
     * Production safety guard: Strictly prohibits restoring directly over the live production project.
     * Disaster recovery must always be executed in an isolated non-production or staging rehearsal environment.
     */
    public static void assertNotProductionRestoreTarget(String targetEnvironment) {
        if (targetEnvironment != null && (targetEnvironment.equalsIgnoreCase("PRODUCTION") || targetEnvironment.equalsIgnoreCase("PROD"))) {
            throw new UnsupportedOperationException(
                    "Restoring directly over the live production project is strictly prohibited (NFR-REC-001). " +
                    "Disaster recovery rehearsals must be performed in an isolated non-production or staging environment."
            );
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available in JDK", e);
        }
    }
}
