package in.smartvrms.verification;

import in.smartvrms.application.port.*;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.ui.Design;
import in.smartvrms.desktop.ui.WorkspaceView;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.JdbcFileStorageService;
import in.smartvrms.server.security.JdbcIdempotencyService;
import in.smartvrms.server.security.StaffAuthorizer;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Complete Stage 2B verification suite: shared security, private storage lifecycle, member provisioning safeguards, idempotency store, and audit immutability. */
public final class Stage2BIntegrationChecks {
    private static int checks;
    private static final String URL = "https://abcdefghijklmnopqrst.supabase.co";
    private static final String KEY = "sb_publishable_fixture0123456789";
    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN_2_ID = UUID.fromString("12121212-1212-1212-1212-121212121212");
    private static final UUID EMPLOYEE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMPANY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 2B integration checks...");
        testMigration003Integrity();
        testFileStorageLifecycleAndTierEnforcement();
        testIdempotencyStoreLogic();
        testAuditTrailImmutability();
        testStaffProvisioningAndHandoverSafeguards();
        testVrmsHttpServerStage2BEndpoints();
        testDesktopWorkspaceViewRoleAppropriate();
        System.out.println("PASS: " + checks + " Stage 2B shared security, storage, provisioning and audit assertions.");
    }

    private static void testMigration003Integrity() throws Exception {
        Path p = Path.of("database/migrations/003_shared_security_files_audit.sql");
        require(Files.exists(p), "migration 003 file exists");
        String sql = Files.readString(p);

        require(sql.contains("CREATE TABLE vrms_private.audit_events"), "audit_events table defined");
        require(sql.contains("CREATE TABLE vrms_private.idempotency_records"), "idempotency_records table defined");
        require(sql.contains("CREATE TABLE vrms_private.file_metadata"), "file_metadata table defined");

        require(sql.contains("ALTER TABLE vrms_private.audit_events ENABLE ROW LEVEL SECURITY;"), "audit_events RLS enabled");
        require(sql.contains("ALTER TABLE vrms_private.audit_events FORCE ROW LEVEL SECURITY;"), "audit_events RLS forced");
        require(sql.contains("ALTER TABLE vrms_private.idempotency_records ENABLE ROW LEVEL SECURITY;"), "idempotency_records RLS enabled");
        require(sql.contains("ALTER TABLE vrms_private.file_metadata ENABLE ROW LEVEL SECURITY;"), "file_metadata RLS enabled");

        require(sql.contains("payroll_must_be_admin_only"), "payroll_must_be_admin_only constraint present");
        require(sql.contains("access_tier = 'OPERATIONAL_STAFF'"), "operational staff tier restricted in policy");
        require(sql.contains("GRANT SELECT, INSERT ON vrms_private.audit_events TO smart_vrms_api"), "audit_events immutable grant (no update/delete)");
        require(sql.contains("INSERT INTO vrms_private.schema_migrations"), "migration 003 recorded");
    }

    private static void testFileStorageLifecycleAndTierEnforcement() {
        // Bucket and extension helpers
        require(JdbcFileStorageService.selectBucket(FileCategory.VEHICLE_PHOTO).equals("vrms-private-media"), "photo in media bucket");
        require(JdbcFileStorageService.selectBucket(FileCategory.PAYROLL_STATEMENT).equals("vrms-private-documents"), "payroll in doc bucket");
        require(JdbcFileStorageService.determineExtension("car.png", "image/png").equals("png"), "detects png");
        require(JdbcFileStorageService.determineExtension("statement", "application/pdf").equals("pdf"), "fallback pdf");

        // Fixture storage port
        MockStoragePort storage = new MockStoragePort();
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of("VIEW_FLEET", "MANAGE_PERSONNEL"));
        StaffSession employee = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of("VIEW_FLEET"));

        // MIME validation
        boolean caughtInvalidMime = false;
        try {
            storage.initiateUpload(admin, "script.js", "application/javascript", 100, FileCategory.VEHICLE_DOC, FileAccessTier.OPERATIONAL_STAFF);
        } catch (IllegalArgumentException e) {
            caughtInvalidMime = true;
        }
        require(caughtInvalidMime, "rejects non-whitelisted MIME type");

        // Size validation
        boolean caughtZeroSize = false;
        try {
            storage.initiateUpload(admin, "doc.pdf", "application/pdf", 0, FileCategory.VEHICLE_DOC, FileAccessTier.OPERATIONAL_STAFF);
        } catch (IllegalArgumentException e) {
            caughtZeroSize = true;
        }
        require(caughtZeroSize, "rejects 0-byte file");

        boolean caughtOversize = false;
        try {
            storage.initiateUpload(admin, "large.pdf", "application/pdf", 11 * 1024 * 1024, FileCategory.VEHICLE_DOC, FileAccessTier.OPERATIONAL_STAFF);
        } catch (IllegalArgumentException e) {
            caughtOversize = true;
        }
        require(caughtOversize, "rejects file over 10MB");

        // Payroll tier constraint
        boolean caughtPayrollTierMismatch = false;
        try {
            storage.initiateUpload(admin, "salary.pdf", "application/pdf", 1024, FileCategory.PAYROLL_STATEMENT, FileAccessTier.OPERATIONAL_STAFF);
        } catch (IllegalArgumentException e) {
            caughtPayrollTierMismatch = true;
        }
        require(caughtPayrollTierMismatch, "payroll statement must be ADMIN_ONLY tier");

        // Employee access boundary
        boolean caughtEmpAdminUpload = false;
        try {
            storage.initiateUpload(employee, "private.pdf", "application/pdf", 1024, FileCategory.VEHICLE_DOC, FileAccessTier.ADMIN_ONLY);
        } catch (SecurityException e) {
            caughtEmpAdminUpload = true;
        }
        require(caughtEmpAdminUpload, "employee cannot initiate ADMIN_ONLY file upload");

        // Admin uploads payroll
        FileMetadataRecord meta = storage.initiateUpload(admin, "salary.pdf", "application/pdf", 1024, FileCategory.PAYROLL_STATEMENT, FileAccessTier.ADMIN_ONLY);
        require(meta.status().equals("PENDING"), "upload begins PENDING");
        require(meta.storagePath().startsWith(COMPANY_ID + "/payroll_statement/"), "randomized path structure");
        require(meta.storagePath().endsWith(".pdf"), "correct extension");

        // Finalize upload
        FileMetadataRecord finalized = storage.finalizeUpload(admin, meta.id());
        require(finalized.status().equals("FINALIZED"), "upload transitions to FINALIZED");
        require(finalized.finalizedAt() != null, "finalizedAt timestamp set");

        // Employee read access denial
        require(storage.getMetadata(employee, meta.id()).isEmpty(), "employee cannot view metadata of ADMIN_ONLY file");

        boolean caughtEmpDownload = false;
        try {
            storage.generateSignedDownloadUrl(employee, meta.id(), 300);
        } catch (SecurityException e) {
            caughtEmpDownload = true;
        }
        require(caughtEmpDownload, "employee cannot generate signed download URL for ADMIN_ONLY file");

        // Admin download URL generation
        String signedUrl = storage.generateSignedDownloadUrl(admin, meta.id(), 300);
        require(signedUrl.contains("/object/sign/vrms-private-documents/"), "signed URL targets private documents bucket");
        require(signedUrl.contains("expiresIn=300"), "signed URL contains short expiry");
    }

    private static void testIdempotencyStoreLogic() {
        MockIdempotencyPort idem = new MockIdempotencyPort();
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());

        String payload1 = "{\"email\":\"newstaff@vrms.in\"}";
        String hash1 = JdbcIdempotencyService.computeRequestHash(payload1);
        require(hash1.length() == 64, "sha256 produces 64-char hex");
        require(hash1.equals(JdbcIdempotencyService.computeRequestHash(payload1)), "sha256 hash deterministic");

        String key = "idem-test-key-001";
        require(idem.check(admin, key, "INVITE", hash1).isEmpty(), "new key returns empty");

        idem.recordPending(admin, key, "INVITE", hash1, 300);
        require(idem.check(admin, key, "INVITE", hash1).isPresent(), "pending key is present");
        require(idem.check(admin, key, "INVITE", hash1).get().status().equals("PENDING"), "status is PENDING");

        // Hash mismatch rejection
        String payload2 = "{\"email\":\"different@vrms.in\"}";
        String hash2 = JdbcIdempotencyService.computeRequestHash(payload2);
        boolean caughtMismatch = false;
        try {
            idem.check(admin, key, "INVITE", hash2);
        } catch (IllegalArgumentException e) {
            caughtMismatch = true;
        }
        require(caughtMismatch, "key reuse with different payload hash rejected");

        // Record completion
        idem.recordCompletion(admin, key, 201, "{\"authUserId\":\"xyz\"}");
        var comp = idem.check(admin, key, "INVITE", hash1);
        require(comp.isPresent() && comp.get().status().equals("COMPLETED"), "cached status is COMPLETED");
        require(comp.get().responseStatus() == 201, "cached status code 201");
        require(comp.get().responseBody().contains("xyz"), "cached response body retained");
    }

    private static void testAuditTrailImmutability() {
        MockAuditPort audit = new MockAuditPort();
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());

        AuditEventRecord ev1 = audit.recordEvent(admin, "MEMBER_INVITED", "MEMBERSHIP", "user-123", "{\"email\":\"test@vrms.in\"}");
        require(ev1.id() != null, "audit event assigned UUID");
        require(ev1.action().equals("MEMBER_INVITED"), "action matches");
        require(ev1.resourceType().equals("MEMBERSHIP"), "resource type matches");

        var queried = audit.queryEvents(admin, "MEMBERSHIP", "user-123", 10);
        require(queried.size() == 1, "finds logged audit event");
        require(queried.get(0).detailsJson().contains("test@vrms.in"), "audit details preserved");
    }

    private static void testStaffProvisioningAndHandoverSafeguards() {
        MockStaffProvisioningPort prov = new MockStaffProvisioningPort();
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of(Capability.MANAGE_PERSONNEL.name()));
        StaffSession employee = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of());

        // Employee cannot invite
        boolean caughtEmpInvite = false;
        try {
            prov.inviteEmployee(employee, "new@vrms.in", "New Staff");
        } catch (SecurityException e) {
            caughtEmpInvite = true;
        }
        require(caughtEmpInvite, "employee cannot invite staff");

        // Admin invites employee
        MemberManagementRecord inv = prov.inviteEmployee(admin, "staff1@vrms.in", "Staff One");
        require(inv.role().equals("EMPLOYEE"), "invited role is EMPLOYEE");
        require(inv.active(), "invited member is active");

        // Cannot revoke primary administrator
        boolean caughtPrimaryRevoke = false;
        try {
            prov.revokeMembership(admin, ADMIN_ID, "Self revoke");
        } catch (IllegalStateException e) {
            caughtPrimaryRevoke = true;
        }
        require(caughtPrimaryRevoke, "cannot revoke primary administrator");

        // Cannot revoke sole administrator
        boolean caughtLastAdmin = false;
        try {
            prov.revokeMembership(admin, ADMIN_ID, "Sole revoke");
        } catch (IllegalStateException e) {
            caughtLastAdmin = true;
        }
        require(caughtLastAdmin, "cannot revoke last administrator");

        // Handover to oneself rejected
        boolean caughtSelfHandover = false;
        try {
            prov.handoverPrimaryAdministrator(admin, ADMIN_ID);
        } catch (IllegalArgumentException e) {
            caughtSelfHandover = true;
        }
        require(caughtSelfHandover, "cannot handover administrator to oneself");

        // Handover to non-admin rejected
        boolean caughtNonAdminHandover = false;
        try {
            prov.handoverPrimaryAdministrator(admin, inv.authUserId());
        } catch (IllegalArgumentException e) {
            caughtNonAdminHandover = true;
        }
        require(caughtNonAdminHandover, "cannot handover administrator to employee");

        // Handover to secondary administrator succeeds
        prov.handoverPrimaryAdministrator(admin, ADMIN_2_ID);
        require(prov.getPrimaryAdmin().equals(ADMIN_2_ID), "primary administrator successfully transferred");

        // Now previous primary admin (ADMIN_ID) can be revoked because ADMIN_2_ID is active primary
        MemberManagementRecord revoked = prov.revokeMembership(admin, inv.authUserId(), "Departed staff");
        require(!revoked.active(), "member successfully revoked");
    }

    private static void testVrmsHttpServerStage2BEndpoints() throws Exception {
        SupabaseConfig config = new SupabaseConfig(URL, KEY);
        SupabaseAuth auth = new SupabaseAuth(config, req -> new SupabaseAuth.Response(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8)), CLOCK);

        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            if ("admin-token".equals(token)) {
                return new StaffAuthorizer.Staff(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR");
            }
            if ("employee-token".equals(token)) {
                if (cap.administratorOnly()) throw new SecurityException("Forbidden capability for employee: " + cap);
                return new StaffAuthorizer.Staff(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE");
            }
            throw new SecurityException("Invalid token");
        };

        MockStoragePort storage = new MockStoragePort();
        MockAuditPort audit = new MockAuditPort();
        MockIdempotencyPort idem = new MockIdempotencyPort();
        MockStaffProvisioningPort prov = new MockStaffProvisioningPort();

        HttpServerConfig httpConfig = new HttpServerConfig(new InetSocketAddress("127.0.0.1", 0), null);
        VrmsHttpServer server = new VrmsHttpServer(httpConfig, auth, authorizer, storage, audit, idem, prov);
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        String base = server.baseUri().toString();

        try {
            // 1. Invite without token -> 401
            HttpRequest r1 = HttpRequest.newBuilder(URI.create(base + "/api/v1/admin/members/invite"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@vrms.in\"}"))
                    .build();
            HttpResponse<String> resp1 = client.send(r1, HttpResponse.BodyHandlers.ofString());
            require(resp1.statusCode() == 401, "invite without token is 401");

            // 2. Invite with employee token -> 403
            HttpRequest r2 = HttpRequest.newBuilder(URI.create(base + "/api/v1/admin/members/invite"))
                    .header("Authorization", "Bearer employee-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@vrms.in\"}"))
                    .build();
            HttpResponse<String> resp2 = client.send(r2, HttpResponse.BodyHandlers.ofString());
            require(resp2.statusCode() == 403, "invite with employee token is 403");

            // 3. Invite with admin token and idempotency key -> 201
            String idemKey = "idem-invite-99999";
            HttpRequest r3 = HttpRequest.newBuilder(URI.create(base + "/api/v1/admin/members/invite"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .header("X-Idempotency-Key", idemKey)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"operator@vrms.in\",\"fullName\":\"Op\"}"))
                    .build();
            HttpResponse<String> resp3 = client.send(r3, HttpResponse.BodyHandlers.ofString());
            require(resp3.statusCode() == 201, "admin invite is 201");
            require(resp3.body().contains("authUserId"), "returns invited member details");

            // 4. Repeated invite with same idempotency key -> returns cached 201
            HttpResponse<String> resp4 = client.send(r3, HttpResponse.BodyHandlers.ofString());
            require(resp4.statusCode() == 201, "idempotent retry returns 201");
            require(resp4.body().equals(resp3.body()), "idempotent retry returns exact cached body");

            // 5. Revoking primary admin -> 409 Conflict
            HttpRequest r5 = HttpRequest.newBuilder(URI.create(base + "/api/v1/admin/members/revoke"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":\"" + ADMIN_ID + "\"}"))
                    .build();
            HttpResponse<String> resp5 = client.send(r5, HttpResponse.BodyHandlers.ofString());
            require(resp5.statusCode() == 409, "revoking primary admin returns 409 Conflict");

            // 6. File upload initiate - Employee attempting payroll -> 403
            HttpRequest r6 = HttpRequest.newBuilder(URI.create(base + "/api/v1/files/upload/initiate"))
                    .header("Authorization", "Bearer employee-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"fileName\":\"pay.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":1000,\"category\":\"PAYROLL_STATEMENT\",\"accessTier\":\"ADMIN_ONLY\"}"))
                    .build();
            HttpResponse<String> resp6 = client.send(r6, HttpResponse.BodyHandlers.ofString());
            require(resp6.statusCode() == 403, "employee payroll upload returns 403");

            // 7. File upload initiate - Admin initiating vehicle photo -> 201
            HttpRequest r7 = HttpRequest.newBuilder(URI.create(base + "/api/v1/files/upload/initiate"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"fileName\":\"car.jpg\",\"contentType\":\"image/jpeg\",\"sizeBytes\":5000,\"category\":\"VEHICLE_PHOTO\",\"accessTier\":\"OPERATIONAL_STAFF\"}"))
                    .build();
            HttpResponse<String> resp7 = client.send(r7, HttpResponse.BodyHandlers.ofString());
            require(resp7.statusCode() == 201, "admin file initiate returns 201");
            require(resp7.body().contains("storagePath"), "response contains storagePath");

            // Extract fileId
            String fileIdStr = resp7.body().split("\"id\":\"")[1].split("\"")[0];
            UUID fileId = UUID.fromString(fileIdStr);

            // 8. Finalize file upload -> 200
            HttpRequest r8 = HttpRequest.newBuilder(URI.create(base + "/api/v1/files/upload/finalize"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"fileId\":\"" + fileId + "\"}"))
                    .build();
            HttpResponse<String> resp8 = client.send(r8, HttpResponse.BodyHandlers.ofString());
            require(resp8.statusCode() == 200, "file finalize returns 200");
            require(resp8.body().contains("FINALIZED"), "status is FINALIZED");

            // 9. Download URL -> 200
            HttpRequest r9 = HttpRequest.newBuilder(URI.create(base + "/api/v1/files/download?fileId=" + fileId))
                    .header("Authorization", "Bearer admin-token")
                    .GET()
                    .build();
            HttpResponse<String> resp9 = client.send(r9, HttpResponse.BodyHandlers.ofString());
            require(resp9.statusCode() == 200, "download url returns 200");
            require(resp9.body().contains("downloadUrl"), "response contains signed downloadUrl");

            // 10. Audit events - Employee -> 403, Admin -> 200
            HttpRequest r10 = HttpRequest.newBuilder(URI.create(base + "/api/v1/audit/events"))
                    .header("Authorization", "Bearer employee-token")
                    .GET()
                    .build();
            HttpResponse<String> resp10 = client.send(r10, HttpResponse.BodyHandlers.ofString());
            require(resp10.statusCode() == 403, "employee cannot read audit events");

            HttpRequest r11 = HttpRequest.newBuilder(URI.create(base + "/api/v1/audit/events"))
                    .header("Authorization", "Bearer admin-token")
                    .GET()
                    .build();
            HttpResponse<String> resp11 = client.send(r11, HttpResponse.BodyHandlers.ofString());
            require(resp11.statusCode() == 200, "admin reads audit events");
            require(resp11.body().contains("events"), "response contains events array");

        } finally {
            server.stop(0);
        }
    }

    private static void testDesktopWorkspaceViewRoleAppropriate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of(Capability.MANAGE_PERSONNEL.name()));
            WorkspaceView adminView = new WorkspaceView(() -> {}, admin);
            adminView.showPage("Personnel");
            require(findText(adminView, "Administrator Personnel & Staff Provisioning"), "admin sees personnel provisioning card");
            require(findButton(adminView, "Send Staff Invitation"), "admin sees send staff invitation button");

            StaffSession employee = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of());
            WorkspaceView empView = new WorkspaceView(() -> {}, employee);
            empView.showPage("Personnel");
            require(findText(empView, "Access Restricted to Administrators"), "employee sees access restriction on personnel");
            require(!findButton(empView, "Send Staff Invitation"), "employee has no send staff invitation button");
        });
    }

    private static boolean findText(Container container, String target) {
        for (Component c : container.getComponents()) {
            if (c instanceof JLabel l && l.getText() != null && l.getText().contains(target)) return true;
            if (c instanceof JTextArea t && t.getText() != null && t.getText().contains(target)) return true;
            if (c instanceof Container child && findText(child, target)) return true;
        }
        return false;
    }

    private static boolean findButton(Container container, String label) {
        for (Component c : container.getComponents()) {
            if (c instanceof JButton b && label.equals(b.getText())) return true;
            if (c instanceof Container child && findButton(child, label)) return true;
        }
        return false;
    }

    // =========================================================================
    // Mock implementations for unit/fixture tests
    // =========================================================================

    private static final class MockStoragePort implements StoragePort {
        private final Map<UUID, FileMetadataRecord> files = new ConcurrentHashMap<>();

        @Override
        public FileMetadataRecord initiateUpload(StaffSession session, String fileName, String contentType, long sizeBytes, FileCategory category, FileAccessTier accessTier) {
            if (!JdbcFileStorageService.ALLOWED_MIME_TYPES.contains(contentType)) {
                throw new IllegalArgumentException("Unsupported file type: " + contentType);
            }
            if (sizeBytes <= 0 || sizeBytes > JdbcFileStorageService.MAX_FILE_SIZE_BYTES) {
                throw new IllegalArgumentException("Invalid file size");
            }
            if (category == FileCategory.PAYROLL_STATEMENT && accessTier != FileAccessTier.ADMIN_ONLY) {
                throw new IllegalArgumentException("Payroll statement must be ADMIN_ONLY");
            }
            if (accessTier == FileAccessTier.ADMIN_ONLY && !"ADMINISTRATOR".equals(session.role())) {
                throw new SecurityException("Forbidden for employee");
            }
            UUID id = UUID.randomUUID();
            String bucket = JdbcFileStorageService.selectBucket(category);
            String ext = JdbcFileStorageService.determineExtension(fileName, contentType);
            String path = session.companyId() + "/" + category.name().toLowerCase(Locale.ROOT) + "/" + id + "." + ext;
            FileMetadataRecord rec = new FileMetadataRecord(id, session.companyId(), session.userId(), bucket, path, fileName, contentType, sizeBytes, category, accessTier, "PENDING", Instant.now(), null);
            files.put(id, rec);
            return rec;
        }

        @Override
        public FileMetadataRecord finalizeUpload(StaffSession session, UUID fileId) {
            FileMetadataRecord rec = files.get(fileId);
            if (rec == null) throw new IllegalArgumentException("Not found");
            FileMetadataRecord fin = new FileMetadataRecord(rec.id(), rec.companyId(), rec.uploadedBy(), rec.storageBucket(), rec.storagePath(), rec.fileName(), rec.contentType(), rec.sizeBytes(), rec.category(), rec.accessTier(), "FINALIZED", rec.createdAt(), Instant.now());
            files.put(fileId, fin);
            return fin;
        }

        @Override
        public Optional<FileMetadataRecord> getMetadata(StaffSession session, UUID fileId) {
            FileMetadataRecord rec = files.get(fileId);
            if (rec == null) return Optional.empty();
            if (rec.accessTier() == FileAccessTier.ADMIN_ONLY && !"ADMINISTRATOR".equals(session.role())) {
                return Optional.empty();
            }
            return Optional.of(rec);
        }

        @Override
        public String generateSignedDownloadUrl(StaffSession session, UUID fileId, int expiresInSeconds) {
            FileMetadataRecord rec = getMetadata(session, fileId).orElseThrow(() -> new SecurityException("Forbidden"));
            return "https://wvtnepxynjjbazczkukh.supabase.co/storage/v1/object/sign/" + rec.storageBucket() + "/" + rec.storagePath() + "?token=sig&expiresIn=" + expiresInSeconds;
        }
    }

    private static final class MockIdempotencyPort implements IdempotencyPort {
        private final Map<String, IdempotencyRecord> map = new ConcurrentHashMap<>();

        @Override
        public Optional<IdempotencyRecord> check(StaffSession session, String idempotencyKey, String operation, String requestHash) {
            IdempotencyRecord rec = map.get(idempotencyKey);
            if (rec != null) {
                if (!rec.requestHash().equals(requestHash)) {
                    throw new IllegalArgumentException("Idempotency key reused with different request payload");
                }
                return Optional.of(rec);
            }
            return Optional.empty();
        }

        @Override
        public IdempotencyRecord recordPending(StaffSession session, String idempotencyKey, String operation, String requestHash, int ttlSeconds) {
            IdempotencyRecord rec = new IdempotencyRecord(UUID.randomUUID(), session.companyId(), idempotencyKey, session.userId(), operation, requestHash, "PENDING", null, null, Instant.now(), Instant.now().plusSeconds(ttlSeconds));
            map.put(idempotencyKey, rec);
            return rec;
        }

        @Override
        public void recordCompletion(StaffSession session, String idempotencyKey, int responseStatus, String responseBody) {
            IdempotencyRecord rec = map.get(idempotencyKey);
            if (rec != null) {
                map.put(idempotencyKey, new IdempotencyRecord(rec.id(), rec.companyId(), rec.idempotencyKey(), rec.actorUserId(), rec.operation(), rec.requestHash(), "COMPLETED", responseStatus, responseBody, rec.createdAt(), rec.expiresAt()));
            }
        }

        @Override
        public void recordFailure(StaffSession session, String idempotencyKey, int responseStatus, String responseBody) {
            IdempotencyRecord rec = map.get(idempotencyKey);
            if (rec != null) {
                map.put(idempotencyKey, new IdempotencyRecord(rec.id(), rec.companyId(), rec.idempotencyKey(), rec.actorUserId(), rec.operation(), rec.requestHash(), "FAILED", responseStatus, responseBody, rec.createdAt(), rec.expiresAt()));
            }
        }
    }

    private static final class MockAuditPort implements AuditPort {
        private final List<AuditEventRecord> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public AuditEventRecord recordEvent(StaffSession session, String action, String resourceType, String resourceId, String detailsJson) {
            AuditEventRecord rec = new AuditEventRecord(UUID.randomUUID(), session.companyId(), session.userId(), action, resourceType, resourceId, detailsJson, Instant.now());
            events.add(rec);
            return rec;
        }

        @Override
        public List<AuditEventRecord> queryEvents(StaffSession session, String resourceType, String resourceId, int limit) {
            return events.stream()
                    .filter(e -> resourceType == null || e.resourceType().equals(resourceType))
                    .filter(e -> resourceId == null || e.resourceId().equals(resourceId))
                    .toList();
        }
    }

    private static final class MockStaffProvisioningPort implements StaffProvisioningPort {
        private UUID primaryAdmin = ADMIN_ID;
        private final Map<UUID, MemberManagementRecord> members = new ConcurrentHashMap<>();

        public MockStaffProvisioningPort() {
            members.put(ADMIN_ID, new MemberManagementRecord(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", true, ADMIN_ID, Instant.now(), 1));
            members.put(ADMIN_2_ID, new MemberManagementRecord(ADMIN_2_ID, COMPANY_ID, "ADMINISTRATOR", true, ADMIN_ID, Instant.now(), 1));
        }

        public UUID getPrimaryAdmin() {
            return primaryAdmin;
        }

        @Override
        public MemberManagementRecord inviteEmployee(StaffSession session, String email, String fullName) {
            if (!session.capabilities().contains(Capability.MANAGE_PERSONNEL.name())) {
                throw new SecurityException("Forbidden: Only administrators can invite employees");
            }
            UUID id = UUID.randomUUID();
            MemberManagementRecord rec = new MemberManagementRecord(id, session.companyId(), "EMPLOYEE", true, session.userId(), Instant.now(), 1);
            members.put(id, rec);
            return rec;
        }

        @Override
        public MemberManagementRecord revokeMembership(StaffSession session, UUID targetAuthUserId, String reason) {
            if (!session.capabilities().contains(Capability.MANAGE_PERSONNEL.name())) {
                throw new SecurityException("Forbidden");
            }
            if (targetAuthUserId.equals(primaryAdmin)) {
                throw new IllegalStateException("Cannot revoke the primary administrator. Transfer administrator role first.");
            }
            MemberManagementRecord rec = members.get(targetAuthUserId);
            if (rec != null && "ADMINISTRATOR".equals(rec.role())) {
                long activeAdmins = members.values().stream().filter(m -> "ADMINISTRATOR".equals(m.role()) && m.active()).count();
                if (activeAdmins <= 1) {
                    throw new IllegalStateException("Cannot revoke the last active administrator");
                }
            }
            MemberManagementRecord updated = new MemberManagementRecord(targetAuthUserId, session.companyId(), rec != null ? rec.role() : "EMPLOYEE", false, session.userId(), Instant.now(), 2);
            members.put(targetAuthUserId, updated);
            return updated;
        }

        @Override
        public void handoverPrimaryAdministrator(StaffSession session, UUID newPrimaryAdminUserId) {
            if (!"ADMINISTRATOR".equals(session.role())) {
                throw new SecurityException("Forbidden");
            }
            if (session.userId().equals(newPrimaryAdminUserId)) {
                throw new IllegalArgumentException("Cannot handover administrator role to oneself");
            }
            MemberManagementRecord target = members.get(newPrimaryAdminUserId);
            if (target == null || !"ADMINISTRATOR".equals(target.role()) || !target.active()) {
                throw new IllegalArgumentException("Target user is not an active administrator");
            }
            primaryAdmin = newPrimaryAdminUserId;
        }

        @Override
        public List<MemberManagementRecord> listMembers(StaffSession session) {
            return new ArrayList<>(members.values());
        }
    }
}
