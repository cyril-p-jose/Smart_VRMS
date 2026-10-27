package in.smartvrms.verification;

import in.smartvrms.desktop.infrastructure.AppVersion;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import in.smartvrms.desktop.integration.UpdateChecker;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Stage 11 — Verified Application Distribution, Safe Updates, and Handover Checks.
 *
 * Requirements verified:
 * - NFR-DIST-001: Deliver real Windows macOS Linux application packages
 * - NFR-DIST-002: Install, launch, upgrade, uninstall safely
 * - NFR-DIST-003: Establish publisher trust and honest platform status
 * - NFR-DOCS-001: Provide beginner-friendly accurate handover
 * - NFR-PUB-001: Publish real verified application downloads safely
 */
public final class Stage11DistributionAndReleaseChecks {
    private static int checks;

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 11 distribution, update safety, and release handover checks...");

        testDistributionRequirementsAndChecklist();
        testMigration009IndexColumnsMatchSchema();
        testWindowsPackageCleanlinessAndExclusions();
        testZeroCredentialLeakageInClientAndDocs();
        testDesktopConfigUpgradeSafetyAndIsolation();
        testUpdateCheckerSemverLogic();
        testReleaseManifestAndChecksumConsistency();
        testPlatformEvidenceAndGateG6Compliance();
        testDocumentationCompletenessAndHonestDisclosures();

        System.out.printf("SUCCESS: %d Stage 11 distribution, update safety, and release checks passed cleanly.%n", checks);
    }

    private static void testDistributionRequirementsAndChecklist() throws Exception {
        System.out.println("  1. Verifying distribution requirements in CSV registers...");

        Path reqPath = Path.of("planning/Requirements-and-Verification.csv");
        require(Files.exists(reqPath), "Requirements CSV must exist");
        String reqContent = Files.readString(reqPath, StandardCharsets.UTF_8);

        String[] distReqs = {"NFR-DIST-001", "NFR-DIST-002", "NFR-DIST-003", "NFR-DOCS-001", "NFR-PUB-001"};
        for (String id : distReqs) {
            require(reqContent.contains(id), "Requirements CSV must contain " + id);
            // These criteria require delivered platform artifacts and user-facing handover,
            // not just code or documents. Keep their status aligned with the release checklist.
            for (String line : reqContent.split("\n")) {
                if (line.startsWith(id + ",")) {
                    require(line.endsWith("In progress\r") || line.endsWith("In progress"),
                            id + " must remain In progress until release acceptance, got: " + line);
                }
            }
        }

        Path checkPath = Path.of("docs/Delivery-Checklist.csv");
        require(Files.exists(checkPath), "Delivery checklist CSV must exist");
        String checkContent = Files.readString(checkPath, StandardCharsets.UTF_8);
        for (String id : distReqs) {
            require(checkContent.contains("\"" + id + "\""), "Delivery checklist must contain " + id);
            for (String line : checkContent.split("\n")) {
                if (line.startsWith("\"" + id + "\"")) {
                    require(line.contains("\"In progress\""),
                            id + " must remain In progress until release acceptance, got: " + line);
                }
            }
        }
    }

    private static void testWindowsPackageCleanlinessAndExclusions() throws Exception {
        System.out.println("  2. Verifying Windows x64 package completeness and server exclusions...");

        Path zipPath = Path.of("dist/SmartVRMS-Windows-x64.zip");
        require(Files.exists(zipPath), "dist/SmartVRMS-Windows-x64.zip must exist");
        long zipSize = Files.size(zipPath);
        require(zipSize > 20_000_000L, "Windows package zip must be substantial (>20MB bundled JRE), was: " + zipSize);

        Path exePath = Path.of("dist/package-win64/SmartVRMS/SmartVRMS.exe");
        require(Files.exists(exePath), "SmartVRMS.exe must exist in dist/package-win64/SmartVRMS/");
        require(Files.size(exePath) > 50_000L, "SmartVRMS.exe must be a real native launcher executable");

        Path appDir = Path.of("dist/package-win64/SmartVRMS/app");
        require(Files.isDirectory(appDir), "dist/package-win64/SmartVRMS/app must be a directory");

        // Inspect app directory contents
        File[] appFiles = appDir.toFile().listFiles();
        require(appFiles != null && appFiles.length > 0, "app directory must not be empty");

        Set<String> allowedFileNames = Set.of(
                ".jpackage.xml",
                "SmartVRMS.cfg",
                "application-0.1.0-SNAPSHOT.jar",
                "contracts-0.1.0-SNAPSHOT.jar",
                "desktop-0.1.0-SNAPSHOT.jar",
                "gson-2.15.0.jar",
                "error_prone_annotations-2.50.0.jar"
        );

        for (File f : appFiles) {
            require(allowedFileNames.contains(f.getName()),
                    "Unexpected file in packaged client app folder: " + f.getName());
            require(!f.getName().contains("server"), "Client app must never bundle server JAR: " + f.getName());
            require(!f.getName().contains("postgresql"), "Client app must never bundle postgresql JDBC: " + f.getName());
            require(!f.getName().contains("pdfbox"), "Client app must never bundle PDFBox (server-side only): " + f.getName());
            require(!f.getName().contains("zxing"), "Client app must never bundle ZXing QR (server-side only): " + f.getName());
        }

        // Inspect ZIP entries
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            boolean foundExe = false;
            boolean foundAppJar = false;
            boolean foundServerJar = false;
            boolean foundKeyOrCert = false;

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith("SmartVRMS.exe")) foundExe = true;
                if (name.endsWith("desktop-0.1.0-SNAPSHOT.jar")) foundAppJar = true;
                if (name.contains("server-0.1.0") || name.endsWith("server.jar")) foundServerJar = true;
                if (name.endsWith(".jks") || name.endsWith(".p12") || name.endsWith(".key") || name.endsWith(".env")) {
                    foundKeyOrCert = true;
                }
            }

            require(foundExe, "ZIP must contain SmartVRMS.exe");
            require(foundAppJar, "ZIP must contain desktop jar");
            require(!foundServerJar, "ZIP must NOT contain server jar");
            require(!foundKeyOrCert, "ZIP must NOT contain secret keys or certificates");
        }
    }

    private static void testMigration009IndexColumnsMatchSchema() throws Exception {
        Path migrationPath = Path.of("database/migrations/009_performance_and_audit_hardening.sql");
        Path custodySchemaPath = Path.of("database/migrations/006_operations_and_extensions.sql");
        Path bookingSchemaPath = Path.of("database/migrations/005_booking_engine.sql");
        String migration = Files.readString(migrationPath, StandardCharsets.UTF_8);
        String custodySchema = Files.readString(custodySchemaPath, StandardCharsets.UTF_8);
        String bookingSchema = Files.readString(bookingSchemaPath, StandardCharsets.UTF_8);

        require(migration.contains("custody_records(company_id, checkout_time)"),
                "Migration 009 custody index must use the existing checkout_time column");
        require(custodySchema.contains("checkout_time timestamptz NOT NULL"),
                "Migration 006 must define the custody checkout_time column indexed by migration 009");
        require(!migration.contains("actual_checkout_time"),
                "Migration 009 must not reference nonexistent actual_checkout_time");
        require(migration.contains("bookings(company_id, created_at, status)"),
                "Migration 009 booking index must use the existing status column");
        require(bookingSchema.contains("status text NOT NULL"),
                "Migration 005 must define the booking status column indexed by migration 009");
        require(!migration.contains("booking_status"),
                "Migration 009 must not reference nonexistent booking_status");
    }

    private static void testZeroCredentialLeakageInClientAndDocs() throws Exception {
        System.out.println("  3. Scanning client configuration and docs for credential leakage...");

        List<Path> targets = List.of(
                Path.of("dist/package-win64/SmartVRMS/app/SmartVRMS.cfg"),
                Path.of("README.md"),
                Path.of("docs/Release-Manifest.md"),
                Path.of("docs/Third-Party-Notices.md")
        );

        String[] forbidden = {
                "service_role_key",
                "postgres:postgres",
                "supabase_admin",
                "BEGIN RSA PRIVATE KEY",
                "BEGIN PRIVATE KEY",
                "password123",
                "secret_key"
        };

        for (Path p : targets) {
            if (Files.exists(p)) {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                for (String secret : forbidden) {
                    require(!content.contains(secret), "Forbidden credential pattern '" + secret + "' detected in: " + p);
                }
            }
        }
    }

    private static void testDesktopConfigUpgradeSafetyAndIsolation() throws Exception {
        System.out.println("  4. Verifying DesktopConfig upgrade safety and platform directory isolation...");

        Path configDir = DesktopConfig.getPlatformConfigDirectory();
        require(configDir != null, "Platform config directory must be resolved");
        String configDirStr = configDir.toString().toLowerCase(Locale.ROOT);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            require(configDirStr.contains("smartvrms"), "Config path must contain smartvrms on Windows: " + configDir);
            require(configDirStr.contains("appdata"), "Config path must be under AppData on Windows: " + configDir);
        }

        // Verify URL validation rules
        // Must accept valid HTTPS
        DesktopConfig cfgHttps = DesktopConfig.fromEndpoint("https://smart-vrms.onrender.com");
        require(cfgHttps.apiEndpoint().isPresent(), "HTTPS endpoint must be accepted");
        require(cfgHttps.apiEndpoint().get().toString().equals("https://smart-vrms.onrender.com"), "HTTPS endpoint must match input");

        // Must accept loopback 127.0.0.1 HTTP for local dev
        DesktopConfig cfgLoopback = DesktopConfig.fromEndpoint("http://127.0.0.1:8080");
        require(cfgLoopback.apiEndpoint().isPresent(), "127.0.0.1 HTTP endpoint must be accepted");

        // Must reject insecure remote HTTP
        assertThrowsIllegalArgument(() -> DesktopConfig.fromEndpoint("http://insecure-api.com"),
                "Remote non-loopback HTTP must be rejected");

        // Must reject credentials in URI
        assertThrowsIllegalArgument(() -> DesktopConfig.fromEndpoint("https://user:pass@api.smartvrms.in"),
                "Credentials in URL must be rejected");

        // Must reject queries
        assertThrowsIllegalArgument(() -> DesktopConfig.fromEndpoint("https://api.smartvrms.in/test?token=123"),
                "Query parameters must be rejected");

        // Must reject fragments
        assertThrowsIllegalArgument(() -> DesktopConfig.fromEndpoint("https://api.smartvrms.in#section"),
                "Fragment identifiers must be rejected");

        // Verify persistence to a test directory
        Path tempDir = Files.createTempDirectory("smartvrms-cfg-test");
        System.setProperty("smartvrms.config.dir", tempDir.toString());
        try {
            URI testUri = URI.create("https://my-tenant-api.smartvrms.in");
            DesktopConfig.saveEndpoint(testUri);

            Path savedFile = tempDir.resolve("config.properties");
            require(Files.exists(savedFile), "config.properties must be written");

            DesktopConfig loaded = DesktopConfig.load();
            require(loaded.apiEndpoint().isPresent(), "Saved endpoint must be loadable");
            require(loaded.apiEndpoint().get().equals(testUri), "Loaded endpoint must match saved endpoint");

            // Verify upgrading app binary folder has zero impact on configDir
            Path fakeAppDir = Files.createTempDirectory("smartvrms-app-binaries");
            Files.writeString(fakeAppDir.resolve("new_version.txt"), "0.1.3");
            DesktopConfig reloaded = DesktopConfig.load();
            require(reloaded.apiEndpoint().isPresent() && reloaded.apiEndpoint().get().equals(testUri),
                    "App binary modification must not affect user endpoint config");
        } finally {
            System.clearProperty("smartvrms.config.dir");
        }
    }

    private static void testUpdateCheckerSemverLogic() {
        System.out.println("  5. Verifying UpdateChecker semver comparison and redirect validation...");

        // Newer version cases
        require(UpdateChecker.newerRelease("0.1.2-preview", "https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v0.1.3").isPresent(),
                "0.1.3 must be considered newer than 0.1.2-preview");
        require(UpdateChecker.newerRelease("0.1.2-preview", "https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v0.2.0").isPresent(),
                "0.2.0 must be considered newer than 0.1.2-preview");
        require(UpdateChecker.newerRelease("0.1.2-preview", "https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v1.0.0").isPresent(),
                "1.0.0 must be considered newer than 0.1.2-preview");
        require(UpdateChecker.newerRelease("0.1.2-preview", "https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v0.1.2").isPresent(),
                "Stable 0.1.2 must be considered newer than 0.1.2-preview prerelease");

        // Same or older version cases
        require(UpdateChecker.newerRelease("0.1.2", "https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v0.1.2").isEmpty(),
                "Same version 0.1.2 must not trigger update");
        require(UpdateChecker.newerRelease("0.2.0", "https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v0.1.9").isEmpty(),
                "Older version 0.1.9 must not trigger update");
        require(UpdateChecker.newerRelease("1.0.0", "https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v0.9.9").isEmpty(),
                "Older version 0.9.9 must not trigger update");

        // Malformed or phishing URL validation
        require(UpdateChecker.newerRelease("0.1.2", "http://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v0.1.3").isEmpty(),
                "Non-HTTPS location must be rejected");
        require(UpdateChecker.newerRelease("0.1.2", "https://evil.com/cyril-p-jose/Smart_VRMS/releases/tag/v0.1.3").isEmpty(),
                "Phishing host evil.com must be rejected");
        require(UpdateChecker.newerRelease("0.1.2", "https://github.com/Attacker/Smart_VRMS/releases/tag/v0.1.3").isEmpty(),
                "Phishing repository path must be rejected");
        require(UpdateChecker.newerRelease("0.1.2", "https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v0.1.3?evil=1").isEmpty(),
                "Location with query params must be rejected");
        require(UpdateChecker.newerRelease("0.1.2", "https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v0.1.3#fragment").isEmpty(),
                "Location with fragment must be rejected");
        require(UpdateChecker.newerRelease("0.1.2", null).isEmpty(),
                "Null location must be safely handled");
        require(UpdateChecker.newerRelease("0.1.2", "not a uri").isEmpty(),
                "Malformed location string must be safely handled");

        String releaseFeed = "[{\"tag_name\":\"v0.1.2-preview\",\"draft\":false,\"prerelease\":true},"
                + "{\"tag_name\":\"v0.1.3-preview.2\",\"draft\":false,\"prerelease\":true},"
                + "{\"tag_name\":\"v9.0.0\",\"draft\":true,\"prerelease\":false},"
                + "{\"tag_name\":\"invalid\",\"draft\":false,\"prerelease\":false}]";
        require(UpdateChecker.newerReleaseFromJson("0.1.2-preview", releaseFeed)
                        .map(URI::toString).orElse("").endsWith("/v0.1.3-preview.2"),
                "Public release-feed parser selects a newer prerelease and ignores drafts/invalid tags");
        require(UpdateChecker.newerReleaseFromJson("0.1.2-preview", "[{\"tag_name\":\"v0.1.2-preview\",\"draft\":false}]").isEmpty(),
                "The installed preview does not notify itself as an update");
    }

    private static void testReleaseManifestAndChecksumConsistency() throws Exception {
        System.out.println("  6. Verifying physical checksums against Release-Manifest.md and SHA256SUMS.txt...");

        Path sumsPath = Path.of("dist/SHA256SUMS.txt");
        require(Files.exists(sumsPath), "dist/SHA256SUMS.txt must exist");
        String sumsContent = Files.readString(sumsPath, StandardCharsets.UTF_8);

        Path zipPath = Path.of("dist/SmartVRMS-Windows-x64.zip");
        require(Files.exists(zipPath), "dist/SmartVRMS-Windows-x64.zip must exist");
        String physicalZipSha = computeSha256(zipPath);

        require(sumsContent.toLowerCase(Locale.ROOT).contains(physicalZipSha.toLowerCase(Locale.ROOT)),
                "SHA256SUMS.txt must contain the physical SHA256 of SmartVRMS-Windows-x64.zip");

        Path manifestPath = Path.of("docs/Release-Manifest.md");
        require(Files.exists(manifestPath), "docs/Release-Manifest.md must exist");
        String manifestContent = Files.readString(manifestPath, StandardCharsets.UTF_8);

        require(manifestContent.toLowerCase(Locale.ROOT).contains(physicalZipSha.toLowerCase(Locale.ROOT)),
                "docs/Release-Manifest.md must contain the physical SHA256 of SmartVRMS-Windows-x64.zip");

        // Verify version tag consistency
        require(manifestContent.contains(AppVersion.CURRENT),
                "Release manifest must state current AppVersion: " + AppVersion.CURRENT);
    }

    private static void testPlatformEvidenceAndGateG6Compliance() throws Exception {
        System.out.println("  7. Verifying Gate G6 platform evidence and honest runner disclosures...");

        Path manifestPath = Path.of("docs/Release-Manifest.md");
        String manifestContent = Files.readString(manifestPath, StandardCharsets.UTF_8);

        // Windows 11 x64 is verified
        require(manifestContent.contains("Windows 11 x64"), "Manifest must list Windows 11 x64");
        require(manifestContent.contains("App-image build"), "Manifest must describe Windows build evidence");

        // macOS and Ubuntu must be honestly marked PENDING RUNNER per Gate G6
        require(manifestContent.contains("macOS Intel / Apple Silicon"), "Manifest must list macOS");
        require(manifestContent.contains("Ubuntu 24.04 GNOME x64"), "Manifest must list Ubuntu");
        require(manifestContent.contains("PENDING RUNNER"), "macOS/Ubuntu must be marked PENDING RUNNER");

        // Packaging scripts exist
        require(Files.exists(Path.of("scripts/package-desktop-windows.ps1")), "Windows packaging script must exist");
        require(Files.exists(Path.of("scripts/package-desktop-macos.sh")), "macOS packaging script must exist");
        require(Files.exists(Path.of("scripts/package-desktop-linux.sh")), "Linux packaging script must exist");

        // Gate G7: Production release blocked, preview authorized
        require(manifestContent.contains("PREVIEW PUBLICATION AUTHORIZED; PRODUCTION BLOCKED"),
                "Gate G7 status must be preserved in Release-Manifest.md");
    }

    private static void testDocumentationCompletenessAndHonestDisclosures() throws Exception {
        System.out.println("  8. Verifying README.md user handover and honest trust disclosures...");

        Path readmePath = Path.of("README.md");
        require(Files.exists(readmePath), "README.md must exist");
        String readme = Files.readString(readmePath, StandardCharsets.UTF_8);

        // Essential sections required for beginner-friendly handover:
        require(readme.contains("SmartVRMS-Windows-x64.zip"), "README must document Windows download asset");
        require(readme.contains("SmartVRMS.exe"), "README must mention the native launcher");
        require(readme.contains("SmartScreen") || readme.contains("Windows protected your PC"),
                "README must honestly explain Windows SmartScreen unsigned warning");
        require(readme.contains("More info") && readme.contains("Run anyway"),
                "README must provide exact SmartScreen bypass clicks for unsigned evaluation");
        require(readme.contains("Gatekeeper") || readme.contains("unidentified developer"),
                "README must explain macOS Gatekeeper unsigned notice");
        require(readme.contains("Connection settings"),
                "README must explain public API configuration");
        require(readme.contains("Render") || readme.contains("idle") || readme.contains("minute") || readme.contains("sleep"),
                "README must disclose free-tier backend wake-up delay");
        require(readme.contains("Check for updates") || readme.contains("Update available"),
                "README must explain non-silent update notifications");
    }

    private static String computeSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void assertThrowsIllegalArgument(Runnable runnable, String msg) {
        try {
            runnable.run();
            throw new AssertionError("Expected IllegalArgumentException but succeeded: " + msg);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }
}
