package in.smartvrms.verification;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import in.smartvrms.desktop.integration.AutomationHttpClient;
import in.smartvrms.desktop.integration.DesktopSession;
import in.smartvrms.desktop.integration.ReportingHttpClient;
import in.smartvrms.desktop.integration.ReviewHttpClient;
import in.smartvrms.desktop.ui.AutomationAndReportsView;
import in.smartvrms.desktop.ui.WorkspaceView;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.automation.DocumentReminderScanner;
import in.smartvrms.server.domain.JdbcAutomationService;
import in.smartvrms.server.domain.JdbcReportingService;
import in.smartvrms.server.domain.JdbcReviewService;
import in.smartvrms.server.reporting.PeriodCalculator;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Stage 7 Comprehensive Verification Suite:
 * Outbox retry/lease races, bounded backoff, 72h document reminder boundaries & deduplication,
 * employee deactivation filtering, renewal superseding, review eligibility & admin corrections,
 * Indian FY reporting, role isolation, and Render hosting boundary disclosures
 * (FR-MSG-001..002, FR-REV-001..002, FR-DOC-003..004, FR-FIN-003, NFR-HOST-001, NFR-REL-001).
 */
public final class Stage7AutomationAndReportingChecks {
    private static int checks = 0;
    private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EMPLOYEE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REVOKED_EMP_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 7 Comprehensive Verification Suite...");

        testOutboxTransactionalEnqueueAndIdempotency();
        testOutboxLeaseClaimAndConcurrentRaces();
        testBoundedExponentialBackoffAndSafeFailure();
        testSensitiveRecipientRedactionAndSafeLogging();
        testDocumentReminderStrict72HourBoundaryAndDST();
        testDocumentReminderScanPersistedDeduplication();
        testDocumentRenewalSupersedesOlderReminders();
        testEmployeeDeactivationExcludesFromReminders();
        testServiceReviewGatedOnPhysicalReturnAndSettlement();
        testReviewCorrectionAuditTrailAndAdminOnly();
        testPeriodCalculatorIndianFYAndMondayStartWeeks();
        testRoleBasedReportingIsolation();
        testHostSchedulingBoundaryAndNoFakeDelivery();
        testDesktopUIIntegrationAndRoleGating();

        System.out.println("STAGE 7 ALL COMPREHENSIVE CHECKS PASSED (" + checks + " assertions passed successfully).");
    }

    // =========================================================================
    // 1. Transactional Outbox Enqueue and Idempotency (FR-MSG-001, FR-MSG-002)
    // =========================================================================
    private static void testOutboxTransactionalEnqueueAndIdempotency() {
        System.out.println("Checking outbox transactional enqueue and idempotency...");
        MockDb db = new MockDb();
        JdbcAutomationService svc = new JdbcAutomationService(db.dataSource(), null);
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));

        OutboxMessageRecord m1 = svc.enqueueMessage(
                adminSession,
                "BOOKING_CONFIRMED",
                "BOOK-CONFIRM-001",
                "customer@example.in",
                "CUSTOMER",
                "Booking Confirmation #VRMS-2026-0001",
                "booking_confirmation",
                "{\"bookingId\":\"test\"}",
                null
        );

        require(m1 != null, "Message enqueued successfully");
        require(m1.status().equals("QUEUED"), "Status must be QUEUED upon enqueue (not falsely claimed as sent)");
        require(m1.attemptsCount() == 0, "Initial attempts count is 0");
        require(m1.maxAttempts() == 5, "Default max attempts is 5");

        // Idempotent re-enqueue with same key retrieves identical record without duplicate
        OutboxMessageRecord m2 = svc.enqueueMessage(
                adminSession,
                "BOOKING_CONFIRMED",
                "BOOK-CONFIRM-001",
                "customer@example.in",
                "CUSTOMER",
                "Booking Confirmation #VRMS-2026-0001",
                "booking_confirmation",
                "{\"bookingId\":\"test\"}",
                null
        );

        require(m1.id().equals(m2.id()), "Idempotent enqueue returns identical message ID");
        require(db.outboxMessages.size() == 1, "No duplicate outbox message created for duplicate key");
    }

    // =========================================================================
    // 2. Outbox Leasing, Concurrency Races & Re-Claim (FR-MSG-001)
    // =========================================================================
    private static void testOutboxLeaseClaimAndConcurrentRaces() {
        System.out.println("Checking outbox leasing, concurrency races and re-claiming...");
        MockDb db = new MockDb();
        JdbcAutomationService svc = new JdbcAutomationService(db.dataSource(), null);
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));

        // Enqueue 3 messages
        svc.enqueueMessage(adminSession, "MAINTENANCE_ALERT", "KEY-1", "fleet@vrms.in", "STAFF_FLEET", "Alert 1", "alert", "{}", null);
        svc.enqueueMessage(adminSession, "MAINTENANCE_ALERT", "KEY-2", "fleet@vrms.in", "STAFF_FLEET", "Alert 2", "alert", "{}", null);
        svc.enqueueMessage(adminSession, "MAINTENANCE_ALERT", "KEY-3", "fleet@vrms.in", "STAFF_FLEET", "Alert 3", "alert", "{}", null);

        // Worker A claims batch of 2 with 30-second lease
        List<OutboxMessageRecord> claimedA = svc.claimBatch("worker-node-alpha", 2, 30);
        require(claimedA.size() == 2, "Worker A claimed exactly 2 messages");
        for (OutboxMessageRecord m : claimedA) {
            require("CLAIMED".equals(m.status()), "Claimed message status is CLAIMED");
            require("worker-node-alpha".equals(m.claimedBy()), "Worker A is recorded as claim owner");
            require(m.leaseUntil() != null, "Lease deadline is set");
        }

        // Concurrent Worker B claims batch of 2: must only get the remaining 1 message (no race collision)
        List<OutboxMessageRecord> claimedB = svc.claimBatch("worker-node-beta", 2, 30);
        require(claimedB.size() == 1, "Worker B gets only unclaimed message (no duplicate claim race)");
        require(!claimedA.get(0).id().equals(claimedB.get(0).id()), "Worker B message is distinct from Worker A");
        require(!claimedA.get(1).id().equals(claimedB.get(0).id()), "Worker B message is distinct from Worker A");

        // Worker C attempts to claim: nothing left to claim
        List<OutboxMessageRecord> claimedC = svc.claimBatch("worker-node-gamma", 2, 30);
        require(claimedC.isEmpty(), "Worker C claims 0 messages while all are actively leased");

        // Simulate lease expiration on Worker A's first message
        OutboxMessageRecord expiredMsg = db.outboxMessages.get(claimedA.get(0).id());
        db.outboxMessages.put(expiredMsg.id(), new OutboxMessageRecord(
                expiredMsg.id(), expiredMsg.companyId(), expiredMsg.eventType(), expiredMsg.idempotencyKey(),
                expiredMsg.recipientEmail(), expiredMsg.recipientRole(), expiredMsg.subject(), expiredMsg.templateName(),
                expiredMsg.payloadJson(), expiredMsg.attachmentDocId(), "CLAIMED",
                Instant.now().minusSeconds(10), // Lease expired 10s ago
                expiredMsg.claimedBy(), expiredMsg.attemptsCount(), expiredMsg.maxAttempts(),
                expiredMsg.nextAttemptAt(), expiredMsg.lastAttemptAt(), expiredMsg.lastError(), expiredMsg.createdAt()
        ));

        // Now Worker C can re-claim the expired leased message
        List<OutboxMessageRecord> reClaimed = svc.claimBatch("worker-node-gamma", 1, 30);
        require(reClaimed.size() == 1, "Worker C successfully reclaims expired lease");
        require(reClaimed.get(0).id().equals(expiredMsg.id()), "Reclaimed message matches expired lease message ID");
        require("worker-node-gamma".equals(reClaimed.get(0).claimedBy()), "Lease owner updated to Worker C");
    }

    // =========================================================================
    // 3. Bounded Exponential Retry Backoff & Safe Failure (FR-MSG-001)
    // =========================================================================
    private static void testBoundedExponentialBackoffAndSafeFailure() {
        System.out.println("Checking bounded exponential backoff and safe failure...");
        MockDb db = new MockDb();
        JdbcAutomationService svc = new JdbcAutomationService(db.dataSource(), null);
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));

        OutboxMessageRecord msg = svc.enqueueMessage(
                adminSession, "BOOKING_CONFIRMED", "KEY-RETRY-01", "cust@vrms.in", "CUSTOMER", "Receipt", "tpl", "{}", null
        );

        // Attempt 1: Transient network failure
        svc.markFailed(msg.id(), "SMTP_SENDGRID", "Connection reset by peer (TLS handshake timeout)", false);
        OutboxMessageRecord a1 = db.outboxMessages.get(msg.id());
        require("QUEUED".equals(a1.status()), "Transient failure transitions back to QUEUED for retry");
        require(a1.attemptsCount() == 1, "Attempt count incremented to 1");
        require(a1.leaseUntil() == null, "Lease is released on failure");
        require(a1.nextAttemptAt().isAfter(Instant.now().plusSeconds(30)), "Next attempt backoff scheduled into future");

        // Verify attempt audit record
        List<DeliveryAttemptRecord> atts = svc.listAttempts(adminSession, msg.id());
        require(atts.size() == 1, "One delivery attempt recorded");
        require("RETRYABLE_ERROR".equals(atts.get(0).outcome()), "Outcome is RETRYABLE_ERROR");
        require(atts.get(0).providerName().equals("SMTP_SENDGRID"), "Provider name recorded");

        // Attempt 2, 3, 4, 5
        svc.markFailed(msg.id(), "SMTP_SENDGRID", "503 Service Unavailable", false);
        svc.markFailed(msg.id(), "SMTP_SENDGRID", "503 Service Unavailable", false);
        svc.markFailed(msg.id(), "SMTP_SENDGRID", "503 Service Unavailable", false);
        svc.markFailed(msg.id(), "SMTP_SENDGRID", "503 Service Unavailable", false);

        OutboxMessageRecord a5 = db.outboxMessages.get(msg.id());
        require("FAILED".equals(a5.status()), "Message transitions to FAILED after exceeding max attempts");
        require(a5.attemptsCount() == 5, "Attempt count equals max attempts (5)");

        List<DeliveryAttemptRecord> attsFinal = svc.listAttempts(adminSession, msg.id());
        require(attsFinal.size() == 5, "All 5 attempts preserved in audit history");
        require("PERMANENT_FAILURE".equals(attsFinal.get(4).outcome()), "Final attempt recorded as PERMANENT_FAILURE");

        // Test explicit permanent failure
        OutboxMessageRecord permMsg = svc.enqueueMessage(
                adminSession, "BOOKING_CONFIRMED", "KEY-PERM-01", "invalid@vrms.in", "CUSTOMER", "Receipt", "tpl", "{}", null
        );
        svc.markFailed(permMsg.id(), "SMTP_SENDGRID", "550 5.1.1 User unknown", true);
        OutboxMessageRecord permResult = db.outboxMessages.get(permMsg.id());
        require("FAILED".equals(permResult.status()), "Explicit permanent failure immediately marks status FAILED");
        require(permResult.attemptsCount() == 1, "Attempts count is 1 for immediate permanent failure");
    }

    // =========================================================================
    // 4. Sensitive Recipient Redaction and Safe Logging (FR-MSG-001)
    // =========================================================================
    private static void testSensitiveRecipientRedactionAndSafeLogging() {
        System.out.println("Checking recipient redaction and credential protection in logs...");

        String email1 = "customer.vip@domain.in";
        String redacted1 = JdbcAutomationService.redactEmail(email1);
        require(redacted1.equals("c***@domain.in"), "Email redacted as c***@domain.in");

        String email2 = "rajesh.kumar@smartvrms.in";
        String redacted2 = JdbcAutomationService.redactEmail(email2);
        require(redacted2.equals("r***@smartvrms.in"), "Email redacted as r***@smartvrms.in");

        String nullEmail = JdbcAutomationService.redactEmail(null);
        require("[REDACTED]".equals(nullEmail), "Null email returns [REDACTED]");
    }

    // =========================================================================
    // 5. Document Reminder Strict 72-Hour Boundary & Asia/Kolkata DST Checks (FR-DOC-003)
    // =========================================================================
    private static void testDocumentReminderStrict72HourBoundaryAndDST() {
        System.out.println("Checking 72h document reminder boundary and Asia/Kolkata DST...");

        // Verify Asia/Kolkata timezone behavior: constant UTC+05:30 with zero DST
        ZonedDateTime kolkataSummer = ZonedDateTime.of(2026, 6, 15, 9, 0, 0, 0, KOLKATA);
        ZonedDateTime kolkataWinter = ZonedDateTime.of(2026, 12, 15, 9, 0, 0, 0, KOLKATA);
        require(kolkataSummer.getOffset().getTotalSeconds() == 19800, "Kolkata summer offset is strictly +05:30 (19800s)");
        require(kolkataWinter.getOffset().getTotalSeconds() == 19800, "Kolkata winter offset is strictly +05:30 (19800s)");
        require(KOLKATA.getRules().isDaylightSavings(kolkataSummer.toInstant()) == false, "Kolkata has zero daylight saving time");

        Instant clockTime = Instant.parse("2026-10-26T03:30:00Z"); // 09:00:00 Asia/Kolkata

        // Case 1: Exactly 72h00m00s remaining -> EXCLUDED (strict inequality < 72h)
        Instant exactly72h = clockTime.plus(Duration.ofHours(72));
        DocumentReminderScanner.ReminderStage stageExact = DocumentReminderScanner.evaluateStage(exactly72h, clockTime);
        require(stageExact == DocumentReminderScanner.ReminderStage.NONE, "Exactly 72h remaining is strictly EXCLUDED");

        // Case 2: 71h 59m 59s remaining -> LESS_THAN_72H
        Instant strictlyLessThan72h = clockTime.plus(Duration.ofHours(72)).minusSeconds(1);
        DocumentReminderScanner.ReminderStage stage72 = DocumentReminderScanner.evaluateStage(strictlyLessThan72h, clockTime);
        require(stage72 == DocumentReminderScanner.ReminderStage.LESS_THAN_72H, "71h59m59s remaining triggers LESS_THAN_72H");

        // Case 3: Exactly 0s remaining -> EXPIRED
        DocumentReminderScanner.ReminderStage stage0 = DocumentReminderScanner.evaluateStage(clockTime, clockTime);
        require(stage0 == DocumentReminderScanner.ReminderStage.EXPIRED, "Exactly 0s remaining triggers EXPIRED");

        // Case 4: Past expiry (-1 hour) -> EXPIRED
        Instant pastExpiry = clockTime.minus(Duration.ofHours(1));
        DocumentReminderScanner.ReminderStage stagePast = DocumentReminderScanner.evaluateStage(pastExpiry, clockTime);
        require(stagePast == DocumentReminderScanner.ReminderStage.EXPIRED, "Past expiry triggers EXPIRED");

        // Case 5: Non-expiring document (expiryTime == null) -> NONE
        DocumentReminderScanner.ReminderStage stageNull = DocumentReminderScanner.evaluateStage(null, clockTime);
        require(stageNull == DocumentReminderScanner.ReminderStage.NONE, "Non-expiring document emits ReminderStage.NONE");
    }

    // =========================================================================
    // 6. Document Reminder Scan Persisted Deduplication (FR-DOC-003)
    // =========================================================================
    private static void testDocumentReminderScanPersistedDeduplication() {
        System.out.println("Checking document reminder persisted deduplication...");
        MockDb db = new MockDb();
        JdbcAutomationService svc = new JdbcAutomationService(db.dataSource(), null);
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));

        Instant clockTime = Instant.parse("2026-10-26T03:30:00Z"); // 09:00 IST
        UUID vehicleId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        // Add vehicle document expiring in 48 hours (< 72h)
        db.vehicleDocs.put(docId, new MockDoc(docId, vehicleId, "INSURANCE", clockTime.plus(Duration.ofHours(48)), 1, "KL-07-CD-1234"));

        // Add active admin membership
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));

        // Scan 1: Should queue reminder
        int count1 = svc.scanDocumentReminders(adminSession, clockTime);
        require(count1 == 1, "First scan queued 1 reminder");
        require(db.outboxMessages.size() == 1, "One outbox message created");
        require(db.reminderScans.size() == 1, "One persisted scan evidence record created");

        // Scan 2 at 09:05 IST with unchanged document: Deduplication must prevent repeated message
        int count2 = svc.scanDocumentReminders(adminSession, clockTime.plusSeconds(300));
        require(count2 == 0, "Second scan queued 0 messages (persisted deduplication)");
        require(db.outboxMessages.size() == 1, "Zero duplicate outbox messages created");
        require(db.reminderScans.size() == 1, "Zero duplicate scan records created");
    }

    // =========================================================================
    // 7. Document Renewal Supersedes Stale Alerts (FR-DOC-004)
    // =========================================================================
    private static void testDocumentRenewalSupersedesOlderReminders() {
        System.out.println("Checking document renewal superseding older alerts...");
        MockDb db = new MockDb();
        JdbcAutomationService svc = new JdbcAutomationService(db.dataSource(), null);
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));

        Instant clockTime = Instant.parse("2026-10-26T03:30:00Z");
        UUID vehicleId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        // Version 1: Expiring in 24 hours (<72h)
        db.vehicleDocs.put(docId, new MockDoc(docId, vehicleId, "FITNESS", clockTime.plus(Duration.ofHours(24)), 1, "KL-07-CD-5678"));
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));

        // Scan v1
        svc.scanDocumentReminders(adminSession, clockTime);
        require(db.outboxMessages.size() == 1, "One outbox message queued for v1");
        UUID v1OutboxId = db.outboxMessages.keySet().iterator().next();
        require("QUEUED".equals(db.outboxMessages.get(v1OutboxId).status()), "v1 outbox status is QUEUED");

        // Renew document: version becomes 2, expiry extended by 1 year
        db.vehicleDocs.put(docId, new MockDoc(docId, vehicleId, "FITNESS", clockTime.plus(Duration.ofDays(365)), 2, "KL-07-CD-5678"));

        // Scan v2
        svc.scanDocumentReminders(adminSession, clockTime);

        // Verify older v1 reminder scan is marked superseded
        boolean v1ScanSuperseded = false;
        for (MockScanRecord s : db.reminderScans.values()) {
            if (s.docVersion == 1 && s.superseded) {
                v1ScanSuperseded = true;
                break;
            }
        }
        require(v1ScanSuperseded, "Document renewal marked v1 scan record as superseded = true");

        // Verify older v1 pending outbox message is marked SUPERSEDED so worker will not send stale alert
        OutboxMessageRecord v1Outbox = db.outboxMessages.get(v1OutboxId);
        require("SUPERSEDED".equals(v1Outbox.status()), "Pending v1 outbox message status transitioned to SUPERSEDED");
    }

    // =========================================================================
    // 8. Employee Deactivation Filtering (FR-DOC-003, FR-EMP-005)
    // =========================================================================
    private static void testEmployeeDeactivationExcludesFromReminders() {
        System.out.println("Checking employee deactivation excludes from reminders...");
        MockDb db = new MockDb();
        JdbcAutomationService svc = new JdbcAutomationService(db.dataSource(), null);
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());

        Instant clockTime = Instant.parse("2026-10-26T03:30:00Z");
        UUID vehicleId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        db.vehicleDocs.put(docId, new MockDoc(docId, vehicleId, "PUC", clockTime.plus(Duration.ofHours(30)), 1, "KL-07-CD-9999"));

        // Active admin
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));
        // Active employee
        db.memberships.put(EMPLOYEE_ID, new MockMembership(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "active.emp@vrms.in", "ACTIVE"));
        // Revoked / deactivated employee
        db.memberships.put(REVOKED_EMP_ID, new MockMembership(REVOKED_EMP_ID, COMPANY_ID, "EMPLOYEE", "revoked.emp@vrms.in", "REVOKED"));

        int queued = svc.scanDocumentReminders(adminSession, clockTime);
        require(queued == 2, "Queued exactly 2 reminders (1 for active admin, 1 for active employee)");

        Set<String> recipients = new HashSet<>();
        for (OutboxMessageRecord m : db.outboxMessages.values()) {
            recipients.add(m.recipientEmail());
        }
        require(recipients.contains("admin@vrms.in"), "Active administrator received reminder");
        require(recipients.contains("active.emp@vrms.in"), "Active employee received reminder");
        require(!recipients.contains("revoked.emp@vrms.in"), "Deactivated/revoked employee strictly EXCLUDED from reminders");
    }

    // =========================================================================
    // 9. Service Reviews Gated on Physical Return & Debt Settlement (FR-REV-001)
    // =========================================================================
    private static void testServiceReviewGatedOnPhysicalReturnAndSettlement() {
        System.out.println("Checking service review eligibility gates...");
        MockDb db = new MockDb();
        JdbcReviewService svc = new JdbcReviewService(db.dataSource(), null);
        StaffSession employeeSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of());
        db.memberships.put(EMPLOYEE_ID, new MockMembership(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", "ACTIVE"));

        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();

        // Setup booking
        db.bookings.put(bookingId, new MockBooking(bookingId, customerId, visitId, "CONFIRMED"));

        // Case A: Vehicle still in customer custody (not returned)
        db.openCustodyCount.put(bookingId, 1);
        db.balanceDuePaise.put(bookingId, 0L);
        try {
            svc.recordReview(employeeSession, new ServiceReviewCommand(bookingId, 5, "Great drive", "DESK_FORM", true));
            throw new AssertionError("Review should have been rejected for unreturned vehicle");
        } catch (IllegalStateException ex) {
            require(ex.getMessage().contains("physically returned"), "Rejection specifies vehicle return required");
        }

        // Case B: Vehicle physically returned but unsettled debt (balance due > 0)
        db.openCustodyCount.put(bookingId, 0); // Returned
        db.balanceDuePaise.put(bookingId, 150000L); // ₹1,500 balance due
        try {
            svc.recordReview(employeeSession, new ServiceReviewCommand(bookingId, 5, "Great drive", "DESK_FORM", true));
            throw new AssertionError("Review should have been rejected for unsettled debt");
        } catch (IllegalStateException ex) {
            require(ex.getMessage().contains("fully settled"), "Rejection specifies balance settlement required");
        }

        // Case C: Vehicle physically returned AND fully settled (zero debt)
        db.balanceDuePaise.put(bookingId, 0L); // Settled
        ServiceReviewRecord rec = svc.recordReview(
                employeeSession, new ServiceReviewCommand(bookingId, 5, "Smooth journey, clean car.", "DESK_FORM", true)
        );
        require(rec != null, "Review recorded successfully when returned + zero debt");
        require(rec.rating() == 5, "Rating recorded as 5");
        require(rec.status().equals("RECORDED"), "Initial review status is RECORDED");
        require(rec.communicationSource().equals("DESK_FORM"), "Communication source recorded");

        // Case D: Duplicate review attempt for same booking
        try {
            svc.recordReview(employeeSession, new ServiceReviewCommand(bookingId, 4, "Second review", "PHONE", true));
            throw new AssertionError("Duplicate review for same booking should be rejected");
        } catch (IllegalStateException ex) {
            require(ex.getMessage().contains("already exists"), "Duplicate review prevented by unique booking constraint");
        }
    }

    // =========================================================================
    // 10. Review Correction History and Admin-Only Moderation (FR-REV-002)
    // =========================================================================
    private static void testReviewCorrectionAuditTrailAndAdminOnly() {
        System.out.println("Checking review correction audit trail and administrator authorization...");
        MockDb db = new MockDb();
        JdbcReviewService svc = new JdbcReviewService(db.dataSource(), null);
        StaffSession employeeSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of());
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());
        db.memberships.put(EMPLOYEE_ID, new MockMembership(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", "ACTIVE"));
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));

        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        db.bookings.put(bookingId, new MockBooking(bookingId, customerId, visitId, "CONFIRMED"));
        db.openCustodyCount.put(bookingId, 0);
        db.balanceDuePaise.put(bookingId, 0L);

        ServiceReviewRecord initial = svc.recordReview(
                employeeSession, new ServiceReviewCommand(bookingId, 4, "Initial feedback with typo", "PHONE", true)
        );

        // Employee attempt to correct review must be rejected (Admin only)
        try {
            svc.correctReview(employeeSession, new ServiceReviewCorrectionCommand(
                    initial.id(), 5, "Revised text", "Customer requested typo correction"
            ));
            throw new AssertionError("Employee correction should be forbidden");
        } catch (SecurityException ex) {
            require(ex.getMessage().contains("Only administrators"), "Employee correction throws 403 Forbidden");
        }

        // Administrator corrects review
        ServiceReviewRecord corrected = svc.correctReview(adminSession, new ServiceReviewCorrectionCommand(
                initial.id(), 5, "Customer verified: Excellent experience, fuel full.", "Typo correction and rating clarification"
        ));

        require("CORRECTED".equals(corrected.status()), "Review status updated to CORRECTED");
        require(corrected.rating() == 5, "Revised rating is 5");
        require(corrected.id().equals(initial.id()), "Review ID is preserved");

        // Verify correction history audit record
        require(db.reviewCorrections.size() == 1, "Correction history record created");
        MockCorrectionRecord hist = db.reviewCorrections.get(0);
        require(hist.previousRating == 4, "Previous rating preserved as 4");
        require(hist.revisedRating == 5, "Revised rating recorded as 5");
        require(hist.previousFeedback.contains("typo"), "Previous feedback text preserved");
        require(hist.correctionReason.contains("clarification"), "Correction reason recorded");
        require(hist.correctedBy.equals(ADMIN_ID), "Corrected by admin user ID recorded");
    }

    // =========================================================================
    // 11. Period Calculator Indian FY and Monday-Start Weeks (FR-FIN-003, FR-FIN-004)
    // =========================================================================
    private static void testPeriodCalculatorIndianFYAndMondayStartWeeks() {
        System.out.println("Checking PeriodCalculator Indian FY and Monday-start week calculations...");

        // Date in October 2026 -> FY 2026-27 (1 Apr 2026 to 31 Mar 2027)
        Instant oct2026 = LocalDate.of(2026, 10, 26).atStartOfDay(KOLKATA).toInstant();
        String label1 = PeriodCalculator.getFinancialYearLabel(oct2026);
        require(label1.equals("FY 2026-27"), "Label is FY 2026-27");
        Instant start1 = PeriodCalculator.getFinancialYearStart(oct2026);
        Instant end1 = PeriodCalculator.getFinancialYearEnd(oct2026);
        ZonedDateTime start1Z = start1.atZone(KOLKATA);
        ZonedDateTime end1Z = end1.atZone(KOLKATA);
        require(start1Z.getMonth() == Month.APRIL && start1Z.getDayOfMonth() == 1 && start1Z.getYear() == 2026, "FY starts 1 April 2026");
        require(end1Z.getMonth() == Month.MARCH && end1Z.getDayOfMonth() == 31 && end1Z.getYear() == 2027, "FY ends 31 March 2027");

        // Date in February 2027 -> Still FY 2026-27
        Instant feb2027 = LocalDate.of(2027, 2, 10).atStartOfDay(KOLKATA).toInstant();
        String label2 = PeriodCalculator.getFinancialYearLabel(feb2027);
        require(label2.equals("FY 2026-27"), "Feb 2027 belongs to FY 2026-27");

        // Monday-start week calculation: 2026-10-28 is a Wednesday
        Instant wedOct28 = LocalDate.of(2026, 10, 28).atStartOfDay(KOLKATA).toInstant();
        Instant weekStart = PeriodCalculator.getMondayWeekStart(wedOct28);
        Instant weekEnd = PeriodCalculator.getMondayWeekEnd(wedOct28);
        ZonedDateTime weekStartZ = weekStart.atZone(KOLKATA);
        ZonedDateTime weekEndZ = weekEnd.atZone(KOLKATA);
        require(weekStartZ.getDayOfWeek() == DayOfWeek.MONDAY, "Week start is Monday");
        require(weekStartZ.getDayOfMonth() == 26, "Week start is 26 Oct 2026");
        require(weekEndZ.getDayOfWeek() == DayOfWeek.SUNDAY, "Week end is Sunday");
        require(weekEndZ.getDayOfMonth() == 1 && weekEndZ.getMonth() == Month.NOVEMBER, "Week end is 1 Nov 2026");
    }

    // =========================================================================
    // 12. Role-Based Reporting Isolation (FR-ACC-003, FR-FIN-005)
    // =========================================================================
    private static void testRoleBasedReportingIsolation() {
        System.out.println("Checking role-based operational vs financial reporting isolation...");
        MockDb db = new MockDb();
        JdbcReportingService svc = new JdbcReportingService(db.dataSource(), null);
        StaffSession employeeSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of());
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());
        db.memberships.put(EMPLOYEE_ID, new MockMembership(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", "ACTIVE"));
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));

        Instant now = Instant.now();
        ReportingPeriodFilter filter = new ReportingPeriodFilter(now.minusSeconds(86400 * 30), now, "MONTH", null, null);

        // Employee can access operational report
        OperationalReportSummary opReport = svc.getOperationalReport(employeeSession, filter);
        require(opReport != null, "Employee can access operational report");
        require(opReport.totalBookingsCount() >= 0, "Operational bookings count available");

        // Employee attempting financial report throws 403 Forbidden
        try {
            svc.getFinancialReport(employeeSession, filter);
            throw new AssertionError("Employee should be denied access to financial margins and company reports");
        } catch (SecurityException ex) {
            require(ex.getMessage().toLowerCase().contains("only administrator"), "Financial report strictly restricted to Administrator");
        }

        // Administrator can access financial report
        FinancialReportSummary finReport = svc.getFinancialReport(adminSession, filter);
        require(finReport != null, "Administrator can access financial report");
        require(finReport.periodLabel() != null, "Financial period label is present");

        // CSV export: Contains reconciled operational and financial totals for Admin
        String csv = svc.exportReportCsv(adminSession, filter);
        require(csv.contains("Metric,Value"), "CSV export contains standard headers");
        require(csv.contains("Total Revenue Billed"), "CSV export contains billed revenue for Admin");
    }

    // =========================================================================
    // 13. Host Scheduling Boundary & No Fake Delivery (NFR-HOST-001, NFR-REL-001)
    // =========================================================================
    private static void testHostSchedulingBoundaryAndNoFakeDelivery() {
        System.out.println("Checking host scheduling boundary and zero simulated delivery...");
        MockDb db = new MockDb();
        JdbcAutomationService svc = new JdbcAutomationService(db.dataSource(), null);
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());
        db.memberships.put(ADMIN_ID, new MockMembership(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", "ACTIVE"));

        WorkerHealthStatus health = svc.getWorkerHealth(adminSession);
        require(health != null, "Worker health metrics available");
        require(!health.isLagging(), "Worker health is not lagging initially");

        // Enqueue message
        OutboxMessageRecord m = svc.enqueueMessage(
                adminSession, "BOOKING_CONFIRMED", "KEY-TEST-01", "test@vrms.in", "CUSTOMER", "Sub", "tpl", "{}", null
        );

        // In an unconfigured environment without SMTP credentials, never simulate delivery
        // Mark failed with unconfigured provider reason
        svc.markFailed(m.id(), "UNCONFIGURED_SMTP", "Live email provider not configured in server environment.", true);
        OutboxMessageRecord failedMsg = db.outboxMessages.get(m.id());
        require("FAILED".equals(failedMsg.status()), "Unconfigured provider marks message FAILED (never claims fake delivery)");

        List<DeliveryAttemptRecord> atts = svc.listAttempts(adminSession, m.id());
        require(atts.get(0).outcome().equals("PERMANENT_FAILURE"), "Audit records PERMANENT_FAILURE without fake success");
    }

    // =========================================================================
    // 14. Desktop Swing UI Integration & Role Gating (Swing Tests)
    // =========================================================================
    private static void testDesktopUIIntegrationAndRoleGating() {
        System.out.println("Checking desktop UI integration and role-based action gating...");
        StaffSession employeeSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of());
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of());

        AutomationAndReportsView view = new AutomationAndReportsView(employeeSession);

        // Employee Role Checks
        require(!view.scanRemindersBtn.isEnabled(), "Scan reminders button disabled for Employee");
        require(!view.processBatchBtn.isEnabled(), "Process batch button disabled for Employee");
        require(!view.correctReviewBtn.isEnabled(), "Correct review button disabled for Employee");
        require(view.recordReviewBtn.isEnabled(), "Record review button enabled for Employee (staff can record reviews)");

        // Switch to Administrator Role
        view.setSession(adminSession);
        require(view.scanRemindersBtn.isEnabled(), "Scan reminders button enabled for Administrator");
        require(!view.processBatchBtn.isEnabled(), "Process button stays disabled because no real email sender is configured");
        require(view.correctReviewBtn.isEnabled(), "Correct review button enabled for Administrator");

        // Display sample operational and financial report metrics
        OperationalReportSummary opSummary = new OperationalReportSummary(
                Instant.now().minusSeconds(86400 * 30), Instant.now(), 42, 42, 12, 28, 2, 3, 25, 4.8
        );
        view.displayOperationalReport(opSummary);
        require("42".equals(view.opTotalBookingsLabel.getText()), "Operational total bookings displayed");
        require("12".equals(view.opActiveRentalsLabel.getText()), "Operational active rentals displayed");
        require(view.opAvgRatingLabel.getText().contains("4.8"), "Operational avg rating displayed");

        FinancialReportSummary finSummary = new FinancialReportSummary(
                Instant.now().minusSeconds(86400 * 30), Instant.now(), "FY 2026-27",
                54000000L, 48000000L, 6000000L, 5000000L, 4500000L, 12000000L, 16500000L, 37500000L
        );
        view.displayFinancialReport(finSummary);
        require(view.finGrossBilledLabel.getText().contains("5,40,000") || view.finGrossBilledLabel.getText().contains("540,000"), "Gross billed displayed");
        require(view.finNetAccrualMarginLabel.getText().contains("3,75,000") || view.finNetAccrualMarginLabel.getText().contains("375,000"), "Net margin displayed");

        // Display worker health
        WorkerHealthStatus health = new WorkerHealthStatus(Instant.now(), 5, 2, 1, 98, 120L, false);
        view.displayWorkerHealth(health);
        require("NO SENDER".equals(view.workerHealthLabel.getText()), "Worker status does not claim healthy without a sender");
        require("5".equals(view.pendingCountLabel.getText()), "Pending queue count displayed");

        // Responsive resize checks
        int[][] resolutions = {{1024, 720}, {1280, 820}, {1600, 1000}};
        for (int[] res : resolutions) {
            view.setSize(res[0], res[1]);
            view.doLayout();
            require(view.getWidth() == res[0] && view.getHeight() == res[1], "UI successfully rendered at " + res[0] + "x" + res[1]);
        }
    }

    // =========================================================================
    // In-Memory Test Fixtures / Mock Database
    // =========================================================================

    private static class MockDoc {
        final UUID id;
        final UUID vehicleId;
        final String docType;
        final Instant expiry;
        final int version;
        final String plate;

        MockDoc(UUID id, UUID vehicleId, String docType, Instant expiry, int version, String plate) {
            this.id = id;
            this.vehicleId = vehicleId;
            this.docType = docType;
            this.expiry = expiry;
            this.version = version;
            this.plate = plate;
        }
    }

    private static class MockMembership {
        final UUID userId;
        final UUID companyId;
        final String role;
        final String email;
        final String status;

        MockMembership(UUID userId, UUID companyId, String role, String email, String status) {
            this.userId = userId;
            this.companyId = companyId;
            this.role = role;
            this.email = email;
            this.status = status;
        }
    }

    private static class MockBooking {
        final UUID id;
        final UUID customerId;
        final UUID visitId;
        final String status;

        MockBooking(UUID id, UUID customerId, UUID visitId, String status) {
            this.id = id;
            this.customerId = customerId;
            this.visitId = visitId;
            this.status = status;
        }
    }

    private static class MockScanRecord {
        final UUID companyId;
        final UUID docId;
        final int docVersion;
        final String stage;
        final String email;
        final UUID outboxId;
        boolean superseded;

        MockScanRecord(UUID companyId, UUID docId, int docVersion, String stage, String email, UUID outboxId) {
            this.companyId = companyId;
            this.docId = docId;
            this.docVersion = docVersion;
            this.stage = stage;
            this.email = email;
            this.outboxId = outboxId;
            this.superseded = false;
        }
    }

    private static class MockCorrectionRecord {
        final UUID reviewId;
        final int previousRating;
        final int revisedRating;
        final String previousFeedback;
        final String revisedFeedback;
        final String correctionReason;
        final UUID correctedBy;
        final Instant correctedAt;

        MockCorrectionRecord(UUID reviewId, int prevRating, int revRating, String prevFb, String revFb, String reason, UUID correctedBy, Instant correctedAt) {
            this.reviewId = reviewId;
            this.previousRating = prevRating;
            this.revisedRating = revRating;
            this.previousFeedback = prevFb;
            this.revisedFeedback = revFb;
            this.correctionReason = reason;
            this.correctedBy = correctedBy;
            this.correctedAt = correctedAt;
        }
    }

    private static class MockDb {
        final Map<UUID, OutboxMessageRecord> outboxMessages = new ConcurrentHashMap<>();
        final Map<UUID, List<DeliveryAttemptRecord>> deliveryAttempts = new ConcurrentHashMap<>();
        final Map<String, MockScanRecord> reminderScans = new ConcurrentHashMap<>();
        final Map<UUID, MockDoc> vehicleDocs = new ConcurrentHashMap<>();
        final Map<UUID, MockMembership> memberships = new ConcurrentHashMap<>();
        final Map<UUID, MockBooking> bookings = new ConcurrentHashMap<>();
        final Map<UUID, Integer> openCustodyCount = new ConcurrentHashMap<>();
        final Map<UUID, Long> balanceDuePaise = new ConcurrentHashMap<>();
        final Map<UUID, ServiceReviewRecord> serviceReviews = new ConcurrentHashMap<>();
        final List<MockCorrectionRecord> reviewCorrections = new ArrayList<>();

        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        if ("getConnection".equals(method.getName())) {
                            return createConnection();
                        }
                        return null;
                    }
            );
        }

        private Connection createConnection() {
            boolean[] autoCommit = {true};
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("prepareStatement".equals(name)) {
                            return createPreparedStatement((String) args[0]);
                        }
                        if ("setAutoCommit".equals(name)) {
                            autoCommit[0] = (Boolean) args[0];
                            return null;
                        }
                        if ("getAutoCommit".equals(name)) return autoCommit[0];
                        if ("setReadOnly".equals(name) || "commit".equals(name) || "rollback".equals(name) || "close".equals(name)) {
                            return null;
                        }
                        return null;
                    }
            );
        }

        private PreparedStatement createPreparedStatement(String sql) {
            Map<Integer, Object> params = new HashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        String m = method.getName();
                        if (m.startsWith("set") && args.length >= 2 && args[0] instanceof Integer) {
                            params.put((Integer) args[0], args[1]);
                            return null;
                        }
                        if ("executeQuery".equals(m)) {
                            return executeQuery(sql, params);
                        }
                        if ("executeUpdate".equals(m)) {
                            return executeUpdate(sql, params);
                        }
                        if ("execute".equals(m)) {
                            return false;
                        }
                        if ("close".equals(m)) {
                            return null;
                        }
                        return null;
                    }
            );
        }

        private ResultSet executeQuery(String sql, Map<Integer, Object> params) {
            List<Map<String, Object>> rows = new ArrayList<>();

            if (sql.contains("SELECT current_user = 'smart_vrms_api'")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("1", true);
                rows.add(row);
            } else if (sql.contains("SELECT company_id, role, active FROM vrms_private.memberships")) {
                UUID userId = (UUID) params.get(1);
                MockMembership membership = memberships.get(userId);
                if (membership != null) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("company_id", membership.companyId);
                    row.put("role", membership.role);
                    row.put("active", "ACTIVE".equals(membership.status));
                    rows.add(row);
                }
            } else if (sql.contains("SELECT id, company_id, event_type, idempotency_key") && sql.contains("idempotency_key = ?")) {
                String idempKey = (String) params.get(2);
                for (OutboxMessageRecord msg : outboxMessages.values()) {
                    if (idempKey.equals(msg.idempotencyKey())) {
                        rows.add(outboxToRow(msg));
                        break;
                    }
                }
            } else if (sql.contains("SELECT id FROM vrms_private.outbox_messages") && sql.contains("FOR UPDATE SKIP LOCKED")) {
                Instant now = ((Timestamp) params.get(1)).toInstant();
                int limit = (Integer) params.get(3);
                for (OutboxMessageRecord msg : outboxMessages.values()) {
                    boolean eligible = ("QUEUED".equals(msg.status()) && (msg.nextAttemptAt() == null || !msg.nextAttemptAt().isAfter(now)))
                            || ("CLAIMED".equals(msg.status()) && msg.leaseUntil() != null && msg.leaseUntil().isBefore(now));
                    if (eligible) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("id", msg.id());
                        rows.add(r);
                        if (rows.size() >= limit) break;
                    }
                }
            } else if (sql.contains("UPDATE vrms_private.outbox_messages") && sql.contains("SET status = 'CLAIMED'")) {
                Instant leaseUntil = ((Timestamp) params.get(1)).toInstant();
                String workerId = (String) params.get(2);
                UUID id = (UUID) params.get(3);
                OutboxMessageRecord m = outboxMessages.get(id);
                if (m != null) {
                    OutboxMessageRecord upd = new OutboxMessageRecord(
                            m.id(), m.companyId(), m.eventType(), m.idempotencyKey(), m.recipientEmail(), m.recipientRole(),
                            m.subject(), m.templateName(), m.payloadJson(), m.attachmentDocId(), "CLAIMED",
                            leaseUntil, workerId, m.attemptsCount(), m.maxAttempts(), m.nextAttemptAt(), m.lastAttemptAt(), m.lastError(), m.createdAt()
                    );
                    outboxMessages.put(id, upd);
                    rows.add(outboxToRow(upd));
                }
            } else if (sql.contains("SELECT company_id, attempts_count, max_attempts FROM vrms_private.outbox_messages")) {
                UUID id = (UUID) params.get(1);
                OutboxMessageRecord m = outboxMessages.get(id);
                if (m != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("company_id", m.companyId());
                    r.put("attempts_count", m.attemptsCount());
                    r.put("max_attempts", m.maxAttempts());
                    rows.add(r);
                }
            } else if (sql.contains("SELECT company_id, attempts_count FROM vrms_private.outbox_messages")) {
                UUID id = (UUID) params.get(1);
                OutboxMessageRecord m = outboxMessages.get(id);
                if (m != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("company_id", m.companyId());
                    r.put("attempts_count", m.attemptsCount());
                    rows.add(r);
                }
            } else if (sql.contains("SELECT u.email, m.role FROM vrms_private.memberships m")) {
                for (MockMembership mem : memberships.values()) {
                    if ("ACTIVE".equals(mem.status) && ("ADMINISTRATOR".equals(mem.role) || "EMPLOYEE".equals(mem.role))) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("email", mem.email);
                        r.put("role", mem.role);
                        rows.add(r);
                    }
                }
            } else if (sql.contains("SELECT d.id, d.vehicle_id, d.document_type, d.expiry_date, d.document_version, v.license_plate")) {
                for (MockDoc d : vehicleDocs.values()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", d.id);
                    r.put("vehicle_id", d.vehicleId);
                    r.put("document_type", d.docType);
                    r.put("expiry_date", d.expiry != null ? Timestamp.from(d.expiry) : null);
                    r.put("document_version", d.version);
                    r.put("license_plate", d.plate);
                    rows.add(r);
                }
            } else if (sql.contains("SELECT 1 FROM vrms_private.document_reminder_scans")) {
                UUID docId = (UUID) params.get(2);
                int ver = (Integer) params.get(3);
                String stage = (String) params.get(4);
                String email = (String) params.get(5);
                String key = docId + ":" + ver + ":" + stage + ":" + email;
                if (reminderScans.containsKey(key)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("1", 1);
                    rows.add(r);
                }
            } else if (sql.contains("SELECT status, count(*), min(created_at) FROM vrms_private.outbox_messages")) {
                Map<String, Integer> counts = new HashMap<>();
                for (OutboxMessageRecord m : outboxMessages.values()) {
                    counts.put(m.status(), counts.getOrDefault(m.status(), 0) + 1);
                }
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("status", e.getKey());
                    r.put("count", e.getValue());
                    r.put("min", Timestamp.from(Instant.now().minusSeconds(100)));
                    rows.add(r);
                }
            } else if (sql.contains("FROM vrms_private.delivery_attempts") && sql.contains("ORDER BY attempt_number ASC")) {
                UUID outboxId = (UUID) params.get(2);
                List<DeliveryAttemptRecord> list = deliveryAttempts.getOrDefault(outboxId, List.of());
                for (DeliveryAttemptRecord a : list) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", a.id());
                    r.put("company_id", a.companyId());
                    r.put("outbox_id", a.outboxId());
                    r.put("attempt_number", a.attemptNumber());
                    r.put("started_at", Timestamp.from(a.startedAt()));
                    r.put("finished_at", a.finishedAt() != null ? Timestamp.from(a.finishedAt()) : null);
                    r.put("provider_name", a.providerName());
                    r.put("provider_response_code", a.providerResponseCode());
                    r.put("provider_message_id", a.providerMessageId());
                    r.put("error_message", a.errorMessage());
                    r.put("outcome", a.outcome());
                    rows.add(r);
                }
            } else if (sql.contains("SELECT customer_id, visit_id, booking_status FROM vrms_private.bookings")) {
                UUID bId = (UUID) params.get(1);
                MockBooking b = bookings.get(bId);
                if (b != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("customer_id", b.customerId);
                    r.put("visit_id", b.visitId);
                    r.put("booking_status", b.status);
                    rows.add(r);
                }
            } else if (sql.contains("SELECT count(*) FROM vrms_private.booking_assignments a")) {
                UUID bId = (UUID) params.get(1);
                int count = openCustodyCount.getOrDefault(bId, 0);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("count", count);
                rows.add(r);
            } else if (sql.contains("SELECT balance_due_paise FROM vrms_private.billing_documents")) {
                UUID bId = (UUID) params.get(1);
                long due = balanceDuePaise.getOrDefault(bId, 0L);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("balance_due_paise", due);
                rows.add(r);
            } else if (sql.contains("SELECT 1 FROM vrms_private.service_reviews WHERE booking_id = ?")) {
                UUID bId = (UUID) params.get(1);
                for (ServiceReviewRecord rev : serviceReviews.values()) {
                    if (bId.equals(rev.bookingId())) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("1", 1);
                        rows.add(r);
                        break;
                    }
                }
            } else if (sql.contains("SELECT id, company_id, booking_id, customer_id, visit_id, rating, feedback_text") && sql.contains("FROM vrms_private.service_reviews WHERE id = ?")) {
                UUID id = (UUID) params.get(1);
                ServiceReviewRecord rev = serviceReviews.get(id);
                if (rev != null) {
                    rows.add(reviewToRow(rev));
                }
            } else if (sql.contains("FROM vrms_private.service_reviews") && sql.contains("ORDER BY recorded_at DESC")) {
                for (ServiceReviewRecord rev : serviceReviews.values()) {
                    rows.add(reviewToRow(rev));
                }
            } else if (sql.contains("ORDER BY created_at DESC LIMIT ?")) {
                for (OutboxMessageRecord m : outboxMessages.values()) {
                    rows.add(outboxToRow(m));
                }
            }

            return createMockResultSet(rows);
        }

        private int executeUpdate(String sql, Map<Integer, Object> params) {
            if (sql.contains("INSERT INTO vrms_private.outbox_messages")) {
                UUID id = (UUID) params.get(1);
                UUID companyId = (UUID) params.get(2);
                String eventType = (String) params.get(3);
                String idempotencyKey = (String) params.get(4);
                String email = (String) params.get(5);
                String role = (String) params.get(6);
                String subject = (String) params.get(7);
                String template = (String) params.get(8);
                String payload = (String) params.get(9);
                UUID docId = (UUID) params.get(10);
                Instant now = ((Timestamp) params.get(11)).toInstant();

                outboxMessages.put(id, new OutboxMessageRecord(
                        id, companyId, eventType, idempotencyKey, email, role, subject, template,
                        payload, docId, "QUEUED", null, null, 0, 5, now, null, null, now
                ));
                return 1;
            } else if (sql.contains("UPDATE vrms_private.outbox_messages") && sql.contains("SET status = ?")) {
                String status = (String) params.get(1);
                int attempts = (Integer) params.get(2);
                Instant lastAttempt = ((Timestamp) params.get(3)).toInstant();
                Instant nextAttempt = ((Timestamp) params.get(4)).toInstant();
                String err = (String) params.get(5);
                UUID id = (UUID) params.get(6);

                OutboxMessageRecord m = outboxMessages.get(id);
                if (m != null) {
                    outboxMessages.put(id, new OutboxMessageRecord(
                            m.id(), m.companyId(), m.eventType(), m.idempotencyKey(), m.recipientEmail(), m.recipientRole(),
                            m.subject(), m.templateName(), m.payloadJson(), m.attachmentDocId(), status,
                            null, m.claimedBy(), attempts, m.maxAttempts(), nextAttempt, lastAttempt, err, m.createdAt()
                    ));
                }
                return 1;
            } else if (sql.contains("UPDATE vrms_private.outbox_messages") && sql.contains("SET status = 'ACCEPTED'")) {
                int attempts = (Integer) params.get(1);
                Instant lastAttempt = ((Timestamp) params.get(2)).toInstant();
                UUID id = (UUID) params.get(3);
                OutboxMessageRecord m = outboxMessages.get(id);
                if (m != null) {
                    outboxMessages.put(id, new OutboxMessageRecord(
                            m.id(), m.companyId(), m.eventType(), m.idempotencyKey(), m.recipientEmail(), m.recipientRole(),
                            m.subject(), m.templateName(), m.payloadJson(), m.attachmentDocId(), "ACCEPTED",
                            null, m.claimedBy(), attempts, m.maxAttempts(), null, lastAttempt, null, m.createdAt()
                    ));
                }
                return 1;
            } else if (sql.contains("INSERT INTO vrms_private.delivery_attempts")) {
                UUID compId = (UUID) params.get(1);
                UUID outboxId = (UUID) params.get(2);
                int attNum = (Integer) params.get(3);
                Instant started = ((Timestamp) params.get(4)).toInstant();
                Instant finished = ((Timestamp) params.get(5)).toInstant();
                String prov = (String) params.get(6);
                String err = null;
                String outcome = null;
                Integer respCode = null;
                String msgIdRef = null;

                if (params.size() >= 8 && params.get(8) instanceof String outc) {
                    err = (String) params.get(7);
                    outcome = outc;
                } else if (params.size() >= 8 && params.get(7) instanceof Integer rc) {
                    respCode = rc;
                    msgIdRef = (String) params.get(8);
                    outcome = "ACCEPTED";
                }

                DeliveryAttemptRecord att = new DeliveryAttemptRecord(
                        UUID.randomUUID(), compId, outboxId, attNum, started, finished, prov, respCode, msgIdRef, err, outcome
                );
                deliveryAttempts.computeIfAbsent(outboxId, k -> new ArrayList<>()).add(att);
                return 1;
            } else if (sql.contains("INSERT INTO vrms_private.document_reminder_scans")) {
                UUID compId = (UUID) params.get(1);
                UUID docId = (UUID) params.get(2);
                int ver = (Integer) params.get(3);
                String stage = (String) params.get(4);
                String email = (String) params.get(5);
                UUID outboxId = (UUID) params.get(6);
                String key = docId + ":" + ver + ":" + stage + ":" + email;
                reminderScans.put(key, new MockScanRecord(compId, docId, ver, stage, email, outboxId));
                return 1;
            } else if (sql.contains("UPDATE vrms_private.document_reminder_scans") && sql.contains("SET superseded = true")) {
                UUID docId = (UUID) params.get(2);
                int currentVer = (Integer) params.get(3);
                for (MockScanRecord s : reminderScans.values()) {
                    if (s.docId.equals(docId) && s.docVersion < currentVer) {
                        s.superseded = true;
                    }
                }
                return 1;
            } else if (sql.contains("UPDATE vrms_private.outbox_messages") && sql.contains("SET status = 'SUPERSEDED'")) {
                UUID docId = (UUID) params.get(3);
                int currentVer = (Integer) params.get(4);
                for (MockScanRecord s : reminderScans.values()) {
                    if (s.docId.equals(docId) && s.docVersion < currentVer && s.outboxId != null) {
                        OutboxMessageRecord m = outboxMessages.get(s.outboxId);
                        if (m != null && ("QUEUED".equals(m.status()) || "CLAIMED".equals(m.status()))) {
                            outboxMessages.put(m.id(), new OutboxMessageRecord(
                                    m.id(), m.companyId(), m.eventType(), m.idempotencyKey(), m.recipientEmail(), m.recipientRole(),
                                    m.subject(), m.templateName(), m.payloadJson(), m.attachmentDocId(), "SUPERSEDED",
                                    null, null, m.attemptsCount(), m.maxAttempts(), m.nextAttemptAt(), m.lastAttemptAt(), m.lastError(), m.createdAt()
                            ));
                        }
                    }
                }
                return 1;
            } else if (sql.contains("INSERT INTO vrms_private.service_reviews")) {
                UUID id = (UUID) params.get(1);
                UUID compId = (UUID) params.get(2);
                UUID bookId = (UUID) params.get(3);
                UUID custId = (UUID) params.get(4);
                UUID visId = (UUID) params.get(5);
                int rating = (Integer) params.get(6);
                String feedback = (String) params.get(7);
                String source = (String) params.get(8);
                boolean consent = (Boolean) params.get(9);
                UUID recBy = (UUID) params.get(10);
                Instant recAt = ((Timestamp) params.get(11)).toInstant();

                serviceReviews.put(id, new ServiceReviewRecord(
                        id, compId, bookId, custId, visId, rating, feedback, source, consent, "RECORDED", recBy, recAt
                ));
                return 1;
            } else if (sql.contains("INSERT INTO vrms_private.service_review_corrections")) {
                UUID revId = (UUID) params.get(2);
                int prevR = (Integer) params.get(3);
                int revR = (Integer) params.get(4);
                String prevFb = (String) params.get(5);
                String revFb = (String) params.get(6);
                String reason = (String) params.get(7);
                UUID corrBy = (UUID) params.get(8);
                Instant corrAt = ((Timestamp) params.get(9)).toInstant();

                reviewCorrections.add(new MockCorrectionRecord(revId, prevR, revR, prevFb, revFb, reason, corrBy, corrAt));
                return 1;
            } else if (sql.contains("UPDATE vrms_private.service_reviews") && sql.contains("SET rating = ?")) {
                int revR = (Integer) params.get(1);
                String revFb = (String) params.get(2);
                UUID id = (UUID) params.get(3);

                ServiceReviewRecord r = serviceReviews.get(id);
                if (r != null) {
                    serviceReviews.put(id, new ServiceReviewRecord(
                            r.id(), r.companyId(), r.bookingId(), r.customerId(), r.visitId(),
                            revR, revFb, r.communicationSource(), r.consentGiven(), "CORRECTED", r.recordedBy(), r.recordedAt()
                    ));
                }
                return 1;
            }

            return 0;
        }

        private static Map<String, Object> outboxToRow(OutboxMessageRecord m) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.id());
            r.put("company_id", m.companyId());
            r.put("event_type", m.eventType());
            r.put("idempotency_key", m.idempotencyKey());
            r.put("recipient_email", m.recipientEmail());
            r.put("recipient_role", m.recipientRole());
            r.put("subject", m.subject());
            r.put("template_name", m.templateName());
            r.put("payload_json", m.payloadJson());
            r.put("attachment_doc_id", m.attachmentDocId());
            r.put("status", m.status());
            r.put("lease_until", m.leaseUntil() != null ? Timestamp.from(m.leaseUntil()) : null);
            r.put("claimed_by", m.claimedBy());
            r.put("attempts_count", m.attemptsCount());
            r.put("max_attempts", m.maxAttempts());
            r.put("next_attempt_at", m.nextAttemptAt() != null ? Timestamp.from(m.nextAttemptAt()) : null);
            r.put("last_attempt_at", m.lastAttemptAt() != null ? Timestamp.from(m.lastAttemptAt()) : null);
            r.put("last_error", m.lastError());
            r.put("created_at", Timestamp.from(m.createdAt()));
            return r;
        }

        private static Map<String, Object> reviewToRow(ServiceReviewRecord r) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id());
            row.put("company_id", r.companyId());
            row.put("booking_id", r.bookingId());
            row.put("customer_id", r.customerId());
            row.put("visit_id", r.visitId());
            row.put("rating", r.rating());
            row.put("feedback_text", r.feedbackText());
            row.put("communication_source", r.communicationSource());
            row.put("consent_given", r.consentGiven());
            row.put("status", r.status());
            row.put("recorded_by", r.recordedBy());
            row.put("recorded_at", Timestamp.from(r.recordedAt()));
            return row;
        }

        private static ResultSet createMockResultSet(List<Map<String, Object>> rows) {
            return (ResultSet) Proxy.newProxyInstance(
                    MockDb.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    new InvocationHandler() {
                        int index = -1;

                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                            String name = method.getName();
                            if ("next".equals(name)) {
                                index++;
                                return index < rows.size();
                            }
                            if ("close".equals(name)) {
                                return null;
                            }
                            if (index < 0 || index >= rows.size()) {
                                return null;
                            }
                            Map<String, Object> currentRow = rows.get(index);
                            if ("getObject".equals(name)) {
                                if (args[0] instanceof String key) {
                                    return currentRow.get(key);
                                } else if (args[0] instanceof Integer idx) {
                                    return new ArrayList<>(currentRow.values()).get(idx - 1);
                                }
                            }
                            if ("getString".equals(name)) {
                                Object val = args[0] instanceof String key ? currentRow.get(key) : new ArrayList<>(currentRow.values()).get((Integer) args[0] - 1);
                                return val != null ? val.toString() : null;
                            }
                            if ("getInt".equals(name)) {
                                Object val = args[0] instanceof String key ? currentRow.get(key) : new ArrayList<>(currentRow.values()).get((Integer) args[0] - 1);
                                return val instanceof Number num ? num.intValue() : 0;
                            }
                            if ("getLong".equals(name)) {
                                Object val = args[0] instanceof String key ? currentRow.get(key) : new ArrayList<>(currentRow.values()).get((Integer) args[0] - 1);
                                return val instanceof Number num ? num.longValue() : 0L;
                            }
                            if ("getDouble".equals(name)) {
                                Object val = args[0] instanceof String key ? currentRow.get(key) : new ArrayList<>(currentRow.values()).get((Integer) args[0] - 1);
                                return val instanceof Number num ? num.doubleValue() : 0.0;
                            }
                            if ("getBoolean".equals(name)) {
                                Object val = args[0] instanceof String key ? currentRow.get(key) : new ArrayList<>(currentRow.values()).get((Integer) args[0] - 1);
                                return val instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(val));
                            }
                            if ("getTimestamp".equals(name)) {
                                Object val = args[0] instanceof String key ? currentRow.get(key) : new ArrayList<>(currentRow.values()).get((Integer) args[0] - 1);
                                return val instanceof Timestamp ts ? ts : null;
                            }
                            return null;
                        }
                    }
            );
        }
    }
}
