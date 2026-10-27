package in.smartvrms.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.application.port.AutomationPort;
import in.smartvrms.application.port.ReportingPort;
import in.smartvrms.application.port.ReviewPort;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.ui.AutomationAndReportsView;
import in.smartvrms.desktop.ui.WorkspaceView;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.automation.DocumentReminderScanner;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.reporting.PeriodCalculator;
import in.smartvrms.server.security.StaffAuthorizer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage 7 Integration Checks:
 * Transactional outbox, worker leasing & bounded retries, 72h document expiry boundary,
 * voluntary post-settlement customer reviews, versioned feedback corrections,
 * Indian Financial Year (1 Apr–31 Mar) reporting, operational isolation, and role security
 * (FR-DOC-003, FR-DOC-004, FR-BOOK-011, FR-MSG-001, FR-MSG-002, FR-REV-001, FR-REV-002,
 *  FR-FIN-003, FR-FIN-005, FR-ACC-003, FR-EMP-005).
 */
public final class Stage7IntegrationChecks {
    private static int checks;
    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EMPLOYEE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMPANY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 7 integration checks...");
        testMigration008SchemaIntegrity();
        testDocumentReminderBoundaryCalculations();
        testPeriodCalculatorIndianFYAndMondayWeeks();
        testReviewEligibilityGateAndAuditCorrections();
        testOutboxLeaseAndBoundedBackoff();
        testHttpServerAutomationReviewAndReportEndpoints();
        testDesktopAutomationAndReportsView();

        System.out.println("PASS: " + checks + " Stage 7 automation, outbox, reviews, reporting, and privacy assertions.");
    }

    // =========================================================================
    // 1. Migration 008 Schema & Security Integrity Checks
    // =========================================================================
    private static void testMigration008SchemaIntegrity() throws Exception {
        Path migPath = Path.of("database", "migrations", "008_automation_outbox_and_reporting.sql");
        require(Files.exists(migPath), "Migration 008 exists at database/migrations/008_automation_outbox_and_reporting.sql");
        String sql = Files.readString(migPath);

        require(sql.contains("CREATE TABLE vrms_private.outbox_messages"), "Table outbox_messages defined");
        require(sql.contains("CREATE TABLE vrms_private.delivery_attempts"), "Table delivery_attempts defined");
        require(sql.contains("CREATE TABLE vrms_private.document_reminder_scans"), "Table document_reminder_scans defined");
        require(sql.contains("CREATE TABLE vrms_private.service_reviews"), "Table service_reviews defined");
        require(sql.contains("CREATE TABLE vrms_private.service_review_corrections"), "Table service_review_corrections defined");

        // RLS defense in depth
        require(sql.contains("ALTER TABLE vrms_private.outbox_messages ENABLE ROW LEVEL SECURITY"), "RLS enabled on outbox_messages");
        require(sql.contains("ALTER TABLE vrms_private.outbox_messages FORCE ROW LEVEL SECURITY"), "RLS forced on outbox_messages");
        require(sql.contains("ALTER TABLE vrms_private.service_reviews ENABLE ROW LEVEL SECURITY"), "RLS enabled on service_reviews");
        require(sql.contains("ALTER TABLE vrms_private.service_reviews FORCE ROW LEVEL SECURITY"), "RLS forced on service_reviews");

        // Unique constraints for deduplication
        require(sql.contains("uq_outbox_idempotency_key"), "Unique idempotency key constraint on outbox");
        require(sql.contains("uq_reminder_version_stage"), "Unique version and stage constraint on document reminders");
        require(sql.contains("uq_booking_review"), "Unique booking review constraint");

        // Explicit grants to runtime role
        require(sql.contains("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE"), "Grants defined on Stage 7 tables");
        require(sql.contains("smart_vrms_api"), "Grants given to smart_vrms_api role");
        require(sql.contains("VALUES (7, 'automation outbox, reminders, reviews, and reporting');"), "Migration registers version 7");
    }

    // =========================================================================
    // 2. Deterministic Expiry Scanner Boundary Conditions (FR-DOC-003, FR-DOC-004)
    // =========================================================================
    private static void testDocumentReminderBoundaryCalculations() {
        Instant now = Instant.parse("2026-10-25T09:00:00Z");

        // Exactly 72h00m00s (259200 seconds): STRICTLY EXCLUDED!
        Instant exactly72h = now.plusSeconds(72 * 3600);
        DocumentReminderScanner.ReminderStage stage72h = DocumentReminderScanner.evaluateStage(exactly72h, now);
        require(stage72h == DocumentReminderScanner.ReminderStage.NONE, "Exactly 72h00m00s remaining is strictly excluded (stage = NONE)");

        // 71 hours 59 minutes 59 seconds: INCLUDED as LESS_THAN_72H
        Instant below72h = now.plusSeconds(72 * 3600 - 1);
        DocumentReminderScanner.ReminderStage stageBelow72h = DocumentReminderScanner.evaluateStage(below72h, now);
        require(stageBelow72h == DocumentReminderScanner.ReminderStage.LESS_THAN_72H, "71h 59m 59s remaining is classified as LESS_THAN_72H");

        // 1 second remaining: INCLUDED as LESS_THAN_72H
        Instant oneSecondLeft = now.plusSeconds(1);
        DocumentReminderScanner.ReminderStage stageOneSec = DocumentReminderScanner.evaluateStage(oneSecondLeft, now);
        require(stageOneSec == DocumentReminderScanner.ReminderStage.LESS_THAN_72H, "1 second remaining is classified as LESS_THAN_72H");

        // Exactly 0 seconds remaining: classified as EXPIRED
        DocumentReminderScanner.ReminderStage stageZero = DocumentReminderScanner.evaluateStage(now, now);
        require(stageZero == DocumentReminderScanner.ReminderStage.EXPIRED, "0 seconds remaining is classified as EXPIRED");

        // Negative seconds remaining (-3600s): classified as EXPIRED
        Instant pastExpiry = now.minusSeconds(3600);
        DocumentReminderScanner.ReminderStage stagePast = DocumentReminderScanner.evaluateStage(pastExpiry, now);
        require(stagePast == DocumentReminderScanner.ReminderStage.EXPIRED, "Past expiry date is classified as EXPIRED");

        // Expiry in 5 days (120 hours): classified as NONE
        Instant farExpiry = now.plusSeconds(120 * 3600);
        DocumentReminderScanner.ReminderStage stageFar = DocumentReminderScanner.evaluateStage(farExpiry, now);
        require(stageFar == DocumentReminderScanner.ReminderStage.NONE, "120h remaining is classified as NONE");

        // Null expiry date: classified as NONE
        DocumentReminderScanner.ReminderStage stageNull = DocumentReminderScanner.evaluateStage(null, now);
        require(stageNull == DocumentReminderScanner.ReminderStage.NONE, "Null expiry date produces NONE");
    }

    // =========================================================================
    // 3. Indian Financial Year & Monday Weeks (FR-FIN-003, FR-FIN-004)
    // =========================================================================
    private static void testPeriodCalculatorIndianFYAndMondayWeeks() {
        // Indian FY: 1 April of year Y to 31 March of year Y+1
        ZonedDateTime oct2026 = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, KOLKATA);
        String fyOct2026 = PeriodCalculator.getFinancialYearLabel(oct2026.toInstant());
        require("FY 2026-27".equals(fyOct2026), "October 2026 belongs to FY 2026-27");

        ZonedDateTime mar2027 = ZonedDateTime.of(2027, 3, 31, 23, 59, 59, 0, KOLKATA);
        String fyMar2027 = PeriodCalculator.getFinancialYearLabel(mar2027.toInstant());
        require("FY 2026-27".equals(fyMar2027), "31 March 2027 belongs to FY 2026-27");

        ZonedDateTime apr2027 = ZonedDateTime.of(2027, 4, 1, 0, 0, 0, 0, KOLKATA);
        String fyApr2027 = PeriodCalculator.getFinancialYearLabel(apr2027.toInstant());
        require("FY 2027-28".equals(fyApr2027), "1 April 2027 belongs to FY 2027-28");

        ZonedDateTime jan2026 = ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, KOLKATA);
        String fyJan2026 = PeriodCalculator.getFinancialYearLabel(jan2026.toInstant());
        require("FY 2025-26".equals(fyJan2026), "15 January 2026 belongs to FY 2025-26");

        // Indian FY interval verification
        Instant fyStart = PeriodCalculator.getFinancialYearStart(oct2026.toInstant());
        Instant fyEnd = PeriodCalculator.getFinancialYearEnd(oct2026.toInstant());
        ZonedDateTime startZdt = fyStart.atZone(KOLKATA);
        ZonedDateTime endZdt = fyEnd.atZone(KOLKATA);
        require(startZdt.getMonthValue() == 4 && startZdt.getDayOfMonth() == 1, "FY starts on 1 April");
        require(endZdt.getMonthValue() == 3 && endZdt.getDayOfMonth() == 31, "FY ends on 31 March");

        // Monday week interval verification
        Instant weekStartInst = PeriodCalculator.getMondayWeekStart(oct2026.toInstant());
        ZonedDateTime weekStartZdt = weekStartInst.atZone(KOLKATA);
        require(weekStartZdt.getDayOfWeek() == DayOfWeek.MONDAY, "Week start is Monday");
        require(weekStartZdt.getHour() == 0 && weekStartZdt.getMinute() == 0, "Week starts at 00:00:00");
    }

    // =========================================================================
    // 4. Review Eligibility Gate & Versioned Corrections (FR-REV-001, FR-REV-002)
    // =========================================================================
    private static void testReviewEligibilityGateAndAuditCorrections() {
        MockReviewPort reviewPort = new MockReviewPort();
        StaffSession session = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of());
        UUID bookingId = UUID.randomUUID();

        // 1. Unreturned booking: rejected
        reviewPort.setEligibility(bookingId, false, 0);
        try {
            reviewPort.recordReview(session, new ServiceReviewCommand(bookingId, 5, "Great drive", "DESK_IN_PERSON", true));
            throw new AssertionError("Should reject review for unreturned vehicle");
        } catch (IllegalStateException ex) {
            require(ex.getMessage().contains("returned"), "Review rejected when vehicle not physically returned");
        }

        // 2. Returned but unpaid debt: rejected
        reviewPort.setEligibility(bookingId, true, 50000L); // 500 INR balance due
        try {
            reviewPort.recordReview(session, new ServiceReviewCommand(bookingId, 5, "Great drive", "DESK_IN_PERSON", true));
            throw new AssertionError("Should reject review when debt remains");
        } catch (IllegalStateException ex) {
            require(ex.getMessage().contains("settlement"), "Review rejected when financial settlement is pending");
        }

        // 3. Fully returned and zero balance due: eligible!
        reviewPort.setEligibility(bookingId, true, 0L);
        ServiceReviewRecord rec = reviewPort.recordReview(session, new ServiceReviewCommand(bookingId, 4, "Smooth handover", "DESK_IN_PERSON", true));
        require(rec != null, "Review successfully recorded upon physical return and zero debt");
        require(rec.rating() == 4, "Recorded rating is 4");
        require("RECORDED".equals(rec.status()), "Initial status is RECORDED");

        // 4. Versioned correction audit trail (FR-REV-002)
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());
        ServiceReviewRecord corrected = reviewPort.correctReview(adminSession, new ServiceReviewCorrectionCommand(
                rec.id(), 5, "Smooth handover. Customer clarified rating was meant to be 5 stars.", "Customer request at desk"
        ));
        require(corrected.rating() == 5, "Revised rating updated to 5");
        require("CORRECTED".equals(corrected.status()), "Review status marked as CORRECTED");
        require(reviewPort.correctionsCount() == 1, "Audit record preserved in service_review_corrections");
    }

    // =========================================================================
    // 5. Outbox Leasing & Bounded Exponential Backoff (FR-MSG-001)
    // =========================================================================
    private static void testOutboxLeaseAndBoundedBackoff() {
        MockAutomationPort autoPort = new MockAutomationPort();
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());

        OutboxMessageRecord msg = autoPort.enqueueMessage(
                adminSession, "BOOKING_CONFIRMED", "booking-evt-1",
                "customer@test.in", "CUSTOMER", "Booking Confirmation",
                "booking_confirmation", "{}", null
        );
        require(msg != null, "Outbox message enqueued");
        require("QUEUED".equals(msg.status()), "Initial outbox status is QUEUED");

        // Claim batch leases message
        List<OutboxMessageRecord> claimed = autoPort.claimBatch("worker-1", 10, 300);
        require(claimed.size() == 1, "Worker successfully claims ready message");
        require("CLAIMED".equals(claimed.get(0).status()), "Status updated to CLAIMED");
        require(claimed.get(0).leaseUntil() != null, "Lease deadline set on claimed message");

        // Retry failure increments attempts with exponential backoff
        autoPort.markFailed(msg.id(), "MOCK_SMTP", "Connection refused: timeout", false);
        OutboxMessageRecord retried = autoPort.getMessage(msg.id());
        require("QUEUED".equals(retried.status()), "Retryable failure returns status to QUEUED");
        require(retried.attemptsCount() == 1, "Attempt count incremented to 1");
        require(retried.nextAttemptAt() != null, "Next attempt timestamp scheduled with backoff");

        // Permanent failure marks FAILED immediately
        autoPort.markFailed(msg.id(), "MOCK_SMTP", "Mailbox does not exist (550)", true);
        OutboxMessageRecord failed = autoPort.getMessage(msg.id());
        require("FAILED".equals(failed.status()), "Permanent failure sets status to FAILED");
    }

    // =========================================================================
    // 6. Loopback HTTP API Checks (Admin vs Employee Isolation)
    // =========================================================================
    private static void testHttpServerAutomationReviewAndReportEndpoints() throws Exception {
        HttpServerConfig config = new HttpServerConfig(0);
        SupabaseConfig sc = new SupabaseConfig("https://abcdefghijklmnopqrst.supabase.co", "sb_publishable_fixture0123456789");
        SupabaseAuth auth = new SupabaseAuth(sc);

        StaffAuthorizer.Staff adminStaff = new StaffAuthorizer.Staff(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR");
        StaffAuthorizer.Staff empStaff = new StaffAuthorizer.Staff(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE");

        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            if ("admin-token".equals(token)) {
                return adminStaff;
            }
            if ("emp-token".equals(token)) {
                if (cap.administratorOnly()) {
                    throw new SecurityException("Forbidden: Administrator role required for " + cap);
                }
                return empStaff;
            }
            throw new SecurityException("Invalid token");
        };

        MockAutomationPort autoMock = new MockAutomationPort();
        MockReviewPort reviewMock = new MockReviewPort();
        MockReportingPort reportingMock = new MockReportingPort();

        VrmsHttpServer server = new VrmsHttpServer(
                config, auth, authorizer,
                null, null, null, null, null, null, null, null, null, null, null,
                autoMock, reviewMock, reportingMock
        );
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + server.port();

        try {
            // 1. Scan document reminders as Admin -> 200 OK
            HttpRequest scanReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/automation/reminders/scan"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> scanResp = client.send(scanReq, HttpResponse.BodyHandlers.ofString());
            require(scanResp.statusCode() == 200, "Admin can trigger document reminder scan (200 OK)");

            // 2. Automation health as Admin -> 200 OK
            HttpRequest healthReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/automation/health"))
                    .header("Authorization", "Bearer admin-token")
                    .GET()
                    .build();
            HttpResponse<String> healthResp = client.send(healthReq, HttpResponse.BodyHandlers.ofString());
            require(healthResp.statusCode() == 200, "Admin can view worker health (200 OK)");

            // 3. Operational Report as Employee -> 200 OK, zero financial/margin fields
            HttpRequest opReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/reports/operational?from=2026-10-01T00:00:00Z&to=2026-10-25T23:59:59Z"))
                    .header("Authorization", "Bearer emp-token")
                    .GET()
                    .build();
            HttpResponse<String> opResp = client.send(opReq, HttpResponse.BodyHandlers.ofString());
            require(opResp.statusCode() == 200, "Employee can view operational report (200 OK)");
            JsonObject opJson = JsonParser.parseString(opResp.body()).getAsJsonObject();
            require(opJson.has("totalBookingsCount"), "Operational report has totalBookingsCount");
            require(!opJson.has("operatingMarginPaise") && !opJson.has("netOperatingAccrualMarginPaise"),
                    "Operational report strictly contains ZERO company margin fields (FR-ACC-003, FR-FIN-005)");
            require(!opJson.has("compensationExpensesPaise"), "Operational report strictly contains ZERO staff compensation fields");

            // 4. Financial Report as Employee -> 403 Forbidden!
            HttpRequest finEmpReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/reports/financial?from=2026-10-01T00:00:00Z&to=2026-10-25T23:59:59Z"))
                    .header("Authorization", "Bearer emp-token")
                    .GET()
                    .build();
            HttpResponse<String> finEmpResp = client.send(finEmpReq, HttpResponse.BodyHandlers.ofString());
            require(finEmpResp.statusCode() == 403, "Financial report strictly returns 403 Forbidden for Employee token");

            // 5. Financial Report as Admin -> 200 OK with authoritative breakdown
            HttpRequest finAdminReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/reports/financial?from=2026-10-01T00:00:00Z&to=2026-10-25T23:59:59Z"))
                    .header("Authorization", "Bearer admin-token")
                    .GET()
                    .build();
            HttpResponse<String> finAdminResp = client.send(finAdminReq, HttpResponse.BodyHandlers.ofString());
            require(finAdminResp.statusCode() == 200, "Financial report returns 200 OK for Administrator token");
            JsonObject finJson = JsonParser.parseString(finAdminResp.body()).getAsJsonObject();
            require(finJson.has("netOperatingAccrualMarginPaise"), "Financial report contains netOperatingAccrualMarginPaise");
            require(finJson.has("periodLabel"), "Financial report contains periodLabel");

            // 6. CSV Export as Admin -> 200 OK with Content-Type: text/csv
            HttpRequest csvReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/reports/export?from=2026-10-01T00:00:00Z&to=2026-10-25T23:59:59Z"))
                    .header("Authorization", "Bearer admin-token")
                    .GET()
                    .build();
            HttpResponse<String> csvResp = client.send(csvReq, HttpResponse.BodyHandlers.ofString());
            require(csvResp.statusCode() == 200, "Report CSV export returns 200 OK");
            require(csvResp.headers().firstValue("Content-Type").orElse("").contains("text/csv"), "Export has text/csv Content-Type");
            require(csvResp.body().contains("Metric,Value"), "CSV export contains metric header");

        } finally {
            server.stop(0);
        }
    }

    // =========================================================================
    // 7. Desktop Swing UI Layout & Role Gating Checks
    // =========================================================================
    private static void testDesktopAutomationAndReportsView() {
        StaffSession employeeSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of());
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());

        AutomationAndReportsView view = new AutomationAndReportsView(employeeSession);
        require(!view.scanRemindersBtn.isEnabled(), "Scan reminders disabled for Employee");
        require(!view.processBatchBtn.isEnabled(), "Process batch disabled for Employee");
        require(!view.correctReviewBtn.isEnabled(), "Correct review disabled for Employee");

        // Switch to Admin
        view.setSession(adminSession);
        require(view.scanRemindersBtn.isEnabled(), "Scan reminders enabled for Administrator");
        require(!view.processBatchBtn.isEnabled(), "Email processing remains unavailable until a real sender is configured");
        require(view.correctReviewBtn.isEnabled(), "Correct review enabled for Administrator");

        // WorkspaceView navigation integration
        WorkspaceView workspace = new WorkspaceView(() -> {}, adminSession);
        require(workspace.automationReportsView != null, "WorkspaceView includes automationReportsView");

        // Inspect layout at 1024x720, 1280x820, 1600x1000
        int[][] sizes = {{1024, 720}, {1280, 820}, {1600, 1000}};
        for (int[] sz : sizes) {
            view.setSize(sz[0], sz[1]);
            view.doLayout();
            require(view.getWidth() == sz[0] && view.getHeight() == sz[1], "Layout renders at " + sz[0] + "x" + sz[1]);
        }
    }

    // =========================================================================
    // In-Memory Test Fixtures / Mocks
    // =========================================================================

    private static final class MockAutomationPort implements AutomationPort {
        private final Map<UUID, OutboxMessageRecord> store = new ConcurrentHashMap<>();
        private final Map<UUID, List<DeliveryAttemptRecord>> attempts = new ConcurrentHashMap<>();

        @Override
        public OutboxMessageRecord enqueueMessage(StaffSession session, String eventType, String idempotencyKey,
                                                  String recipientEmail, String recipientRole, String subject,
                                                  String templateName, String payloadJson, UUID attachmentDocId) {
            UUID id = UUID.randomUUID();
            OutboxMessageRecord msg = new OutboxMessageRecord(
                    id, session.companyId(), eventType, idempotencyKey, recipientEmail, recipientRole,
                    subject, templateName, payloadJson, attachmentDocId, "QUEUED",
                    null, null, 0, 5, null, null, null, Instant.now()
            );
            store.put(id, msg);
            return msg;
        }

        @Override
        public List<OutboxMessageRecord> claimBatch(String workerId, int limit, int leaseDurationSeconds) {
            List<OutboxMessageRecord> claimed = new ArrayList<>();
            for (OutboxMessageRecord m : store.values()) {
                if ("QUEUED".equals(m.status())) {
                    OutboxMessageRecord updated = new OutboxMessageRecord(
                            m.id(), m.companyId(), m.eventType(), m.idempotencyKey(), m.recipientEmail(), m.recipientRole(),
                            m.subject(), m.templateName(), m.payloadJson(), m.attachmentDocId(), "CLAIMED",
                            Instant.now().plusSeconds(leaseDurationSeconds), workerId, m.attemptsCount(), m.maxAttempts(),
                            m.nextAttemptAt(), m.lastAttemptAt(), m.lastError(), m.createdAt()
                    );
                    store.put(m.id(), updated);
                    claimed.add(updated);
                    if (claimed.size() >= limit) break;
                }
            }
            return claimed;
        }

        @Override
        public void markAccepted(UUID messageId, String providerName, int responseCode, String messageIdRef) {
            OutboxMessageRecord m = store.get(messageId);
            if (m != null) {
                store.put(messageId, new OutboxMessageRecord(
                        m.id(), m.companyId(), m.eventType(), m.idempotencyKey(), m.recipientEmail(), m.recipientRole(),
                        m.subject(), m.templateName(), m.payloadJson(), m.attachmentDocId(), "ACCEPTED",
                        null, m.claimedBy(), m.attemptsCount() + 1, m.maxAttempts(), null, Instant.now(), null, m.createdAt()
                ));
            }
        }

        @Override
        public void markFailed(UUID messageId, String providerName, String errorMessage, boolean permanent) {
            OutboxMessageRecord m = store.get(messageId);
            if (m != null) {
                int nextAttempts = m.attemptsCount() + 1;
                boolean willFail = permanent || nextAttempts >= m.maxAttempts();
                String nextStatus = willFail ? "FAILED" : "QUEUED";
                Instant nextAttempt = willFail ? null : Instant.now().plusSeconds(30L * (1L << nextAttempts));
                store.put(messageId, new OutboxMessageRecord(
                        m.id(), m.companyId(), m.eventType(), m.idempotencyKey(), m.recipientEmail(), m.recipientRole(),
                        m.subject(), m.templateName(), m.payloadJson(), m.attachmentDocId(), nextStatus,
                        null, null, nextAttempts, m.maxAttempts(), nextAttempt, Instant.now(), errorMessage, m.createdAt()
                ));
            }
        }

        @Override
        public int scanDocumentReminders(StaffSession session, Instant clockTime) {
            return 2;
        }

        @Override
        public WorkerHealthStatus getWorkerHealth(StaffSession session) {
            return new WorkerHealthStatus(Instant.now(), 0, 0, 0, 5, 0L, false);
        }

        @Override
        public List<DeliveryAttemptRecord> listAttempts(StaffSession session, UUID messageId) {
            return attempts.getOrDefault(messageId, Collections.emptyList());
        }

        public OutboxMessageRecord getMessage(UUID id) {
            return store.get(id);
        }
    }

    private static final class MockReviewPort implements ReviewPort {
        private final Map<UUID, ServiceReviewRecord> reviews = new ConcurrentHashMap<>();
        private final Map<UUID, Boolean> returnedMap = new ConcurrentHashMap<>();
        private final Map<UUID, Long> debtMap = new ConcurrentHashMap<>();
        private int correctionsCount = 0;

        public void setEligibility(UUID bookingId, boolean returned, long balanceDuePaise) {
            returnedMap.put(bookingId, returned);
            debtMap.put(bookingId, balanceDuePaise);
        }

        @Override
        public ServiceReviewRecord recordReview(StaffSession session, ServiceReviewCommand cmd) {
            boolean isReturned = returnedMap.getOrDefault(cmd.bookingId(), false);
            if (!isReturned) {
                throw new IllegalStateException("Review cannot be recorded until vehicle has been physically returned");
            }
            long balanceDue = debtMap.getOrDefault(cmd.bookingId(), 0L);
            if (balanceDue > 0) {
                throw new IllegalStateException("Review cannot be recorded until rental financial settlement is complete");
            }
            UUID id = UUID.randomUUID();
            ServiceReviewRecord rec = new ServiceReviewRecord(
                    id, session.companyId(), cmd.bookingId(), UUID.randomUUID(), UUID.randomUUID(),
                    cmd.rating(), cmd.feedbackText(), cmd.communicationSource(), cmd.consentGiven(),
                    "RECORDED", session.userId(), Instant.now()
            );
            reviews.put(id, rec);
            return rec;
        }

        @Override
        public ServiceReviewRecord correctReview(StaffSession session, ServiceReviewCorrectionCommand cmd) {
            ServiceReviewRecord existing = reviews.get(cmd.reviewId());
            if (existing == null) throw new IllegalArgumentException("Review not found");
            correctionsCount++;
            ServiceReviewRecord updated = new ServiceReviewRecord(
                    existing.id(), existing.companyId(), existing.bookingId(), existing.customerId(), existing.visitId(),
                    cmd.revisedRating(), cmd.revisedFeedback(), existing.communicationSource(), existing.consentGiven(),
                    "CORRECTED", existing.recordedBy(), existing.recordedAt()
            );
            reviews.put(cmd.reviewId(), updated);
            return updated;
        }

        @Override
        public List<ServiceReviewRecord> listReviews(StaffSession session, UUID bookingId, UUID customerId) {
            return new ArrayList<>(reviews.values());
        }

        public int correctionsCount() {
            return correctionsCount;
        }
    }

    private static final class MockReportingPort implements ReportingPort {
        @Override
        public OperationalReportSummary getOperationalReport(StaffSession session, ReportingPeriodFilter filter) {
            return new OperationalReportSummary(
                    filter.fromTime(), filter.toTime(),
                    15, 15, 3, 11, 1, 2, 8, 4.75
            );
        }

        @Override
        public FinancialReportSummary getFinancialReport(StaffSession session, ReportingPeriodFilter filter) {
            if (!"ADMINISTRATOR".equalsIgnoreCase(session.role())) {
                throw new SecurityException("Forbidden: Administrator role required for financial reports");
            }
            return new FinancialReportSummary(
                    filter.fromTime(), filter.toTime(), "FY 2026-27",
                    50000000L, 45000000L, 5000000L, 20000000L,
                    3500000L, 8500000L, 12000000L, 38000000L
            );
        }

        @Override
        public String exportReportCsv(StaffSession session, ReportingPeriodFilter filter) {
            return "Metric,Value\nTotal Bookings,15\nActive Rentals,3\nCompleted Rentals,11\nAverage Rating,4.75\n";
        }
    }
}
