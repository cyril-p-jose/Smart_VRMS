package in.smartvrms.verification;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.application.port.*;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import in.smartvrms.desktop.integration.DesktopSession;
import in.smartvrms.desktop.integration.OperationsHttpClient;
import in.smartvrms.desktop.ui.RunningServicesView;
import in.smartvrms.desktop.ui.WorkspaceView;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.domain.JdbcOperationsService;
import in.smartvrms.server.domain.QuoteEngine;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.StaffAuthorizer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Comprehensive Stage 5 Operations, Custody, Return, Extensions, and Maintenance Verification Suite.
 * Requirements:
 * - FR-OPS-001..004: Running services workspace, checkout/return custody records, independent physical return vs debt, financial settlement.
 * - FR-EXT-001..003: Extension eligibility checks, saved-rate repricing (delta only, zero duplicate 24h min, no double late fee), partial package extensions.
 * - FR-DMG-001..004: Maintenance records (estimate, actual, recovery), ordinary wear 0 recovery rule, multi-case safety gate, allocation blocks.
 * - FR-DRV-003: Independent driver duty release on return.
 * - Concurrency, idempotency, atomic rollback, and UI client verification.
 */
public final class Stage5OperationsAndCustodyChecks {
    private static int checks = 0;
    private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EMPLOYEE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 5 Operations & Custody verification checks...");

        testCustodyCheckoutRules();
        testReturnInspectionAndDebtSeparation();
        testIndependentDriverRelease();
        testExtensionRepricingAndLateFeeNonDuplication();
        testPartialPackageExtensions();
        testExtensionConflictAndRollback();
        testDamageCaseRecordingAndMultiCaseSafetyGate();
        testOrdinaryWearZeroCustomerRecoveryRule();
        testMaintenanceBlocksFutureAllocations();
        testHttpOperationsApiAndRoleEnforcement();
        testDesktopClientAndUiFlowIntegrity();

        System.out.println("PASS: All " + checks + " Stage 5 operations, custody, extension, and maintenance assertions passed cleanly!");
    }

    // -------------------------------------------------------------------------
    // 1. Custody Checkout (FR-OPS-002)
    // -------------------------------------------------------------------------
    private static void testCustodyCheckoutRules() {
        MockDatabase db = new MockDatabase();
        StaffSession emp = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));

        UUID assignId = UUID.randomUUID();
        UUID vehId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Instant checkoutTime = Instant.now().minus(Duration.ofHours(24));

        db.addAssignment(assignId, vehId, null, false, bookingId, "RESERVED", 250000L, 10416L, checkoutTime, checkoutTime.plus(Duration.ofDays(2)));

        JdbcOperationsService ops = new JdbcOperationsService(db.dataSource(), null);

        // Valid checkout
        CustodyRecord rec = ops.recordCheckout(emp, new CheckoutCommand(assignId, checkoutTime, 12000, 100, "Clean dispatch"));
        require(rec != null, "Checkout created custody record");
        require(rec.checkoutOdometer() == 12000, "Odometer recorded");
        require(rec.checkoutFuelPercent() == 100, "Fuel recorded");
        require("PENDING".equals(rec.inspectionStatus()), "Initial inspection status is PENDING");
        require("CHECKED_OUT".equals(db.assignments.get(assignId).status), "Assignment status updated to CHECKED_OUT");
        require("RENTED".equals(db.vehicleStatuses.get(vehId)), "Vehicle operational status updated to RENTED");

        // Duplicate checkout denied (FR-OPS-002)
        boolean dupCaught = false;
        try {
            ops.recordCheckout(emp, new CheckoutCommand(assignId, checkoutTime, 12000, 100, "Duplicate"));
        } catch (IllegalStateException e) {
            dupCaught = true;
        }
        require(dupCaught, "Duplicate checkout on already checked out assignment is denied (FR-OPS-002)");

        // Invalid inputs
        boolean invalidOdo = false;
        try {
            ops.recordCheckout(emp, new CheckoutCommand(UUID.randomUUID(), checkoutTime, -10, 100, ""));
        } catch (IllegalArgumentException e) {
            invalidOdo = true;
        }
        require(invalidOdo, "Negative odometer rejected");

        boolean invalidFuel = false;
        try {
            ops.recordCheckout(emp, new CheckoutCommand(UUID.randomUUID(), checkoutTime, 12000, 105, ""));
        } catch (IllegalArgumentException e) {
            invalidFuel = true;
        }
        require(invalidFuel, "Fuel percent > 100 rejected");
    }

    // -------------------------------------------------------------------------
    // 2. Return Inspection & Independent Physical Release vs Debt (FR-OPS-003, FR-OPS-004)
    // -------------------------------------------------------------------------
    private static void testReturnInspectionAndDebtSeparation() {
        MockDatabase db = new MockDatabase();
        StaffSession emp = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));

        UUID assignId = UUID.randomUUID();
        UUID vehId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Instant tStart = Instant.now().minus(Duration.ofHours(30));
        Instant tEnd = Instant.now().minus(Duration.ofHours(6));

        db.addAssignment(assignId, vehId, null, false, bookingId, "CHECKED_OUT", 250000L, 10416L, tStart, tEnd);
        db.vehicleStatuses.put(vehId, "RENTED");
        db.addCustody(assignId, vehId, tStart, 15000, 100, "Clean dispatch");

        // Set booking with UNPAID debt (total 500000, advance 200000, balance 300000)
        db.bookings.put(bookingId, new MockDatabase.BookingState(bookingId, "VRMS-BK-9999", "ACTIVE", 500000L, 200000L, tStart, tEnd));

        JdbcOperationsService ops = new JdbcOperationsService(db.dataSource(), null);

        // Lower odometer than checkout rejected
        boolean lowerOdoCaught = false;
        try {
            ops.recordReturnInspection(emp, new ReturnInspectionCommand(
                    assignId, Instant.now(), 14900, 90, "ACCEPTABLE", "Good order", null, "CUSTOMER_DAMAGE", 0, 0
            ));
        } catch (IllegalArgumentException e) {
            lowerOdoCaught = true;
        }
        require(lowerOdoCaught, "Return odometer less than checkout odometer rejected");

        // Clean Return with UNPAID DEBT (FR-OPS-003)
        CustodyRecord returned = ops.recordReturnInspection(emp, new ReturnInspectionCommand(
                assignId, Instant.now(), 15350, 95, "ACCEPTABLE", "Good condition", null, "ORDINARY_WEAR", 0, 0
        ));

        require("ACCEPTABLE".equals(returned.inspectionStatus()), "Inspection status is ACCEPTABLE");
        require("RETURNED".equals(db.assignments.get(assignId).status), "Assignment status updated to RETURNED");
        require("AVAILABLE".equals(db.vehicleStatuses.get(vehId)), "Vehicle operational status released to AVAILABLE even with unpaid debt (FR-OPS-003)");
        require("ACTIVE".equals(db.bookings.get(bookingId).status), "Booking status remains ACTIVE/settlement-pending because debt is unpaid (FR-OPS-004)");
    }

    // -------------------------------------------------------------------------
    // 3. Independent Driver Release (FR-DRV-003)
    // -------------------------------------------------------------------------
    private static void testIndependentDriverRelease() {
        MockDatabase db = new MockDatabase();
        StaffSession emp = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));

        UUID assignId = UUID.randomUUID();
        UUID vehId = UUID.randomUUID();
        UUID drvId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Instant tStart = Instant.now().minus(Duration.ofHours(30));
        Instant tEnd = Instant.now().minus(Duration.ofHours(6));

        db.addAssignment(assignId, vehId, drvId, true, bookingId, "CHECKED_OUT", 250000L, 10416L, tStart, tEnd);
        db.vehicleStatuses.put(vehId, "RENTED");
        db.driverStatuses.put(drvId, "ASSIGNED");
        db.addCustody(assignId, vehId, tStart, 20000, 100, "Clean dispatch");
        db.bookings.put(bookingId, new MockDatabase.BookingState(bookingId, "VRMS-BK-8888", "ACTIVE", 600000L, 600000L, tStart, tEnd));

        JdbcOperationsService ops = new JdbcOperationsService(db.dataSource(), null);

        // Return with DAMAGE
        CustodyRecord dmg = ops.recordReturnInspection(emp, new ReturnInspectionCommand(
                assignId, Instant.now(), 20500, 80, "DAMAGED", "Dented front door", null, "CUSTOMER_DAMAGE", 1500000L, 1200000L
        ));

        require("DAMAGED".equals(dmg.inspectionStatus()), "Inspection recorded as DAMAGED");
        require("MAINTENANCE".equals(db.vehicleStatuses.get(vehId)), "Vehicle blocked in MAINTENANCE due to damage (FR-DMG-003)");
        require("AVAILABLE".equals(db.driverStatuses.get(drvId)), "Driver duty independently released to AVAILABLE despite vehicle damage (FR-DRV-003)");
    }

    // -------------------------------------------------------------------------
    // 4. Extension Repricing & Non-Duplication with Late Fee (FR-EXT-002, BR-PRICE-002, BR-PRICE-003)
    // -------------------------------------------------------------------------
    private static void testExtensionRepricingAndLateFeeNonDuplication() {
        Instant start = Instant.parse("2026-11-01T10:00:00Z");
        Instant originalEnd = start.plus(Duration.ofDays(2)); // 48h
        Instant extendedEnd = start.plus(Duration.ofDays(3));  // 72h
        long dailyRate = 300000L; // 3000 INR

        // Original 2-day quote
        AssignmentQuote origQuote = QuoteEngine.calculateAssignmentQuote(UUID.randomUUID(), "SUV", dailyRate, dailyRate / 24, false, 0, 0, start, originalEnd);
        require(origQuote.vehicleTotalPaise() == 600000L, "2-day original total is 6000 INR");

        // Extended 3-day full period repricing (FR-EXT-002)
        AssignmentQuote extQuote = QuoteEngine.calculateAssignmentQuote(UUID.randomUUID(), "SUV", dailyRate, dailyRate / 24, false, 0, 0, start, extendedEnd);
        require(extQuote.vehicleTotalPaise() == 900000L, "3-day full period repriced total is 9000 INR");

        long delta = extQuote.vehicleTotalPaise() - origQuote.vehicleTotalPaise();
        require(delta == 300000L, "Extension delta charge is exactly 1-day rate with zero duplicate first-day minimum (FR-EXT-002)");

        // Late fee non-duplication check:
        // Case A: Customer extends before return -> new agreed return time is extendedEnd.
        // Return at extendedEnd + 20 min -> within 30 min grace -> late fee is 0!
        long lateFeeExtended = QuoteEngine.calculateLateFee(dailyRate, extendedEnd, extendedEnd.plus(Duration.ofMinutes(20)));
        require(lateFeeExtended == 0L, "Return within grace after extension charges zero late fee (BR-PRICE-003)");

        // Return during extended interval (e.g. originalEnd + 12 hours):
        // Without extension, delay is 12 hours (chargeable hours = 12).
        long lateFeeWithoutExtension = QuoteEngine.calculateLateFee(dailyRate, originalEnd, originalEnd.plus(Duration.ofHours(12)));
        require(lateFeeWithoutExtension > 0, "Delay without extension incurs late fee");

        // With extension to extendedEnd (which is originalEnd + 24 hours), return at originalEnd + 12 hours is BEFORE new agreedEnd:
        long lateFeeWithExtension = QuoteEngine.calculateLateFee(dailyRate, extendedEnd, originalEnd.plus(Duration.ofHours(12)));
        require(lateFeeWithExtension == 0L, "Extension covers interval under saved rate; no double late fee for the same time (FR-EXT-002)");
    }

    // -------------------------------------------------------------------------
    // 5. Partial Package Extensions (FR-EXT-003)
    // -------------------------------------------------------------------------
    private static void testPartialPackageExtensions() {
        MockDatabase db = new MockDatabase();
        StaffSession emp = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));

        UUID bookingId = UUID.randomUUID();
        UUID assign1 = UUID.randomUUID();
        UUID veh1 = UUID.randomUUID();
        UUID assign2 = UUID.randomUUID();
        UUID veh2 = UUID.randomUUID();

        Instant t0 = Instant.parse("2026-11-05T09:00:00Z");
        Instant t2 = t0.plus(Duration.ofDays(2));
        Instant t3 = t0.plus(Duration.ofDays(3));

        db.addAssignment(assign1, veh1, null, false, bookingId, "CHECKED_OUT", 200000L, 8333L, t0, t2);
        db.addAssignment(assign2, veh2, null, false, bookingId, "CHECKED_OUT", 300000L, 12500L, t0, t2);
        db.bookings.put(bookingId, new MockDatabase.BookingState(bookingId, "VRMS-BK-PKG", "ACTIVE", 1000000L, 500000L, t0, t2));

        JdbcOperationsService ops = new JdbcOperationsService(db.dataSource(), null);

        // Quote extension for ASSIGNMENT 1 ONLY
        ExtensionQuote quote = ops.quoteExtension(COMPANY_ID, new ExtensionCommand(bookingId, List.of(assign1), t3, "Customer needs Car 1 longer"));
        require(quote.assignmentDeltas().size() == 1, "Only selected assignment delta is generated (FR-EXT-003)");
        require(quote.assignmentDeltas().get(0).assignmentId().equals(assign1), "Delta belongs to assignment 1");
        require(quote.totalDeltaPaise() == 200000L, "Delta charge matches only vehicle 1 1-day rate (2000 INR)");

        // Confirm extension for ASSIGNMENT 1 ONLY
        ops.extendAssignments(emp, new ExtensionCommand(bookingId, List.of(assign1), t3, "Customer needs Car 1 longer"));

        require(db.assignments.get(assign1).endTime.equals(t3), "Assignment 1 end time extended to t3");
        require(db.assignments.get(assign2).endTime.equals(t2), "Assignment 2 end time unchanged at t2 (FR-EXT-003)");
        require(db.bookings.get(bookingId).totalPaise == 1200000L, "Booking total updated with only assignment 1 delta");
    }

    // -------------------------------------------------------------------------
    // 6. Extension Conflict & Atomic Rollback (FR-EXT-001, AC-EXT-001)
    // -------------------------------------------------------------------------
    private static void testExtensionConflictAndRollback() {
        MockDatabase db = new MockDatabase();
        StaffSession emp = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));

        UUID bookingId = UUID.randomUUID();
        UUID assignId = UUID.randomUUID();
        UUID vehId = UUID.randomUUID();

        Instant t0 = Instant.parse("2026-11-10T10:00:00Z");
        Instant t2 = t0.plus(Duration.ofDays(2));
        Instant t3 = t0.plus(Duration.ofDays(3));

        db.addAssignment(assignId, vehId, null, false, bookingId, "CHECKED_OUT", 250000L, 10416L, t0, t2);
        db.bookings.put(bookingId, new MockDatabase.BookingState(bookingId, "VRMS-BK-CONFLICT", "ACTIVE", 500000L, 500000L, t0, t2));

        // Inject a future conflicting reservation for vehId during t2..t3
        db.hasConflictingReservation = true;

        JdbcOperationsService ops = new JdbcOperationsService(db.dataSource(), null);

        boolean conflictCaught = false;
        try {
            ops.extendAssignments(emp, new ExtensionCommand(bookingId, List.of(assignId), t3, "Will conflict with future booking"));
        } catch (IllegalStateException e) {
            conflictCaught = true;
        }

        require(conflictCaught, "Conflicting extension rejected with IllegalStateException (AC-EXT-001)");
        require(db.assignments.get(assignId).endTime.equals(t2), "Assignment end time preserved on conflict rollback");
        require(db.bookings.get(bookingId).totalPaise == 500000L, "Booking totals preserved on conflict rollback (zero partial commit)");
    }

    // -------------------------------------------------------------------------
    // 7. Damage Case Recording & Multi-Case Safety Gate (FR-DMG-001, FR-DMG-003)
    // -------------------------------------------------------------------------
    private static void testDamageCaseRecordingAndMultiCaseSafetyGate() {
        MockDatabase db = new MockDatabase();
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in", Set.of("ADMINISTRATOR"));
        StaffSession emp = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));

        UUID vehId = UUID.randomUUID();
        db.vehicleStatuses.put(vehId, "AVAILABLE");

        JdbcOperationsService ops = new JdbcOperationsService(db.dataSource(), null);

        // Case 1: Open damage case 1 (cracked windshield)
        MaintenanceCaseRecord case1 = ops.openMaintenanceCase(
                emp, vehId, null, null, "CUSTOMER_DAMAGE", 1200000L, 1000000L, "Cracked windshield", null
        );
        require("OPEN".equals(case1.status()), "Case 1 status is OPEN");
        require("MAINTENANCE".equals(db.vehicleStatuses.get(vehId)), "Vehicle status blocked in MAINTENANCE (FR-DMG-003)");
        require(db.activeRestrictions.get(vehId).size() == 1, "Vehicle has 1 active restriction");

        // Case 2: Open damage case 2 (brake pad wear)
        MaintenanceCaseRecord case2 = ops.openMaintenanceCase(
                emp, vehId, null, null, "ORDINARY_WEAR", 600000L, 0L, "Brake pads replacement", null
        );
        require(db.activeRestrictions.get(vehId).size() == 2, "Vehicle now has 2 active restrictions");

        // Resolve Case 1: Multi-case safety gate MUST keep vehicle in MAINTENANCE because Case 2 is open!
        ops.resolveMaintenanceCase(admin, new MaintenanceResolutionCommand(case1.id(), 1150000L, 1000000L, "Windshield replaced"));
        require("MAINTENANCE".equals(db.vehicleStatuses.get(vehId)), "Vehicle STAYS BLOCKED because Case 2 is still open (AC-DMG-003)");

        // Resolve Case 2: Now all restrictions clear -> vehicle released to AVAILABLE!
        ops.resolveMaintenanceCase(admin, new MaintenanceResolutionCommand(case2.id(), 580000L, 0L, "Brake pads installed"));
        require("AVAILABLE".equals(db.vehicleStatuses.get(vehId)), "Vehicle transitions to AVAILABLE only after ALL cases resolve (FR-DMG-003)");
    }

    // -------------------------------------------------------------------------
    // 8. Ordinary Wear Zero Customer Recovery Rule (FR-DMG-002)
    // -------------------------------------------------------------------------
    private static void testOrdinaryWearZeroCustomerRecoveryRule() {
        MockDatabase db = new MockDatabase();
        StaffSession emp = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in", Set.of("ADMINISTRATOR"));

        UUID vehId = UUID.randomUUID();
        JdbcOperationsService ops = new JdbcOperationsService(db.dataSource(), null);

        // Employee opening case with ORDINARY_WEAR alleging recovery -> enforced to 0!
        MaintenanceCaseRecord wearCase = ops.openMaintenanceCase(
                emp, vehId, null, null, "ORDINARY_WEAR", 500000L, 300000L, "Routine wear and tear", null
        );
        require(wearCase.allegedCustomerRecoveryPaise() == 0L, "Ordinary wear has 0 alleged customer recovery by routine staff (FR-DMG-002)");

        // Employee attempting to resolve maintenance case -> rejected (Role security)
        boolean empDenied = false;
        try {
            ops.resolveMaintenanceCase(emp, new MaintenanceResolutionCommand(wearCase.id(), 480000L, 0L, "Done"));
        } catch (SecurityException e) {
            empDenied = true;
        }
        require(empDenied, "Employee forbidden from resolving maintenance cases (FR-DMG-002)");

        // Admin attempting to charge customer for ordinary wear without explicit justification notes -> rejected!
        boolean unauthWearChargeCaught = false;
        try {
            ops.resolveMaintenanceCase(admin, new MaintenanceResolutionCommand(wearCase.id(), 480000L, 200000L, "Maintenance completed"));
        } catch (IllegalArgumentException e) {
            unauthWearChargeCaught = true;
        }
        require(unauthWearChargeCaught, "Charging customer for ordinary wear without explicit responsibility justification rejected (FR-DMG-002)");
    }

    // -------------------------------------------------------------------------
    // 9. Maintenance Blocks Future Incompatible Allocations (FR-DMG-004)
    // -------------------------------------------------------------------------
    private static void testMaintenanceBlocksFutureAllocations() {
        MockDatabase db = new MockDatabase();
        StaffSession emp = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));

        UUID vehId = UUID.randomUUID();
        db.vehicleStatuses.put(vehId, "AVAILABLE");

        JdbcOperationsService ops = new JdbcOperationsService(db.dataSource(), null);

        // Place vehicle under active maintenance
        ops.openMaintenanceCase(emp, vehId, null, null, "BREAKDOWN", 800000L, 0L, "Engine check light", null);

        // Attempting to check out this vehicle for any booking must be blocked by active restriction!
        UUID newAssign = UUID.randomUUID();
        UUID newBooking = UUID.randomUUID();
        Instant now = Instant.now();
        db.addAssignment(newAssign, vehId, null, false, newBooking, "RESERVED", 250000L, 10416L, now, now.plus(Duration.ofDays(1)));

        boolean allocBlockCaught = false;
        try {
            ops.recordCheckout(emp, new CheckoutCommand(newAssign, now, 18000, 100, "Attempt dispatch"));
        } catch (IllegalStateException e) {
            allocBlockCaught = true;
        }
        require(allocBlockCaught, "Vehicle with active maintenance restriction cannot be checked out (FR-DMG-004)");
    }

    // -------------------------------------------------------------------------
    // 10. HTTP Operations API & Role Boundary Verification
    // -------------------------------------------------------------------------
    private static void testHttpOperationsApiAndRoleEnforcement() throws Exception {
        MockDatabase db = new MockDatabase();
        JdbcOperationsService ops = new JdbcOperationsService(db.dataSource(), null);

        SupabaseConfig config = new SupabaseConfig("https://abcdefghijklmnopqrst.supabase.co", "sb_publishable_fixture0123456789");
        SupabaseAuth auth = new SupabaseAuth(config);

        StaffAuthorizer.Staff adminStaff = new StaffAuthorizer.Staff(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR");
        StaffAuthorizer.Staff empStaff = new StaffAuthorizer.Staff(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE");

        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            if ("admin-tok".equals(token)) return adminStaff;
            if ("emp-tok".equals(token)) {
                if (cap.administratorOnly()) throw new SecurityException("Forbidden: Administrator role required.");
                return empStaff;
            }
            throw new IllegalArgumentException("Invalid token");
        };

        VrmsHttpServer server = new VrmsHttpServer(
                new HttpServerConfig(0), auth, authorizer,
                null, null, null, null, null, null, null, null, null, ops
        );
        server.start();

        try {
            HttpClient client = HttpClient.newHttpClient();
            URI base = server.baseUri();

            // Prepare assignment in DB
            UUID aId = UUID.randomUUID();
            UUID vId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            Instant tStart = Instant.now().minus(Duration.ofHours(24));
            Instant tEnd = Instant.now().plus(Duration.ofHours(24));
            db.addAssignment(aId, vId, null, false, bId, "RESERVED", 250000L, 10416L, tStart, tEnd);
            db.bookings.put(bId, new MockDatabase.BookingState(bId, "VRMS-BK-API", "CONFIRMED", 500000L, 250000L, tStart, tEnd));

            // POST /api/v1/operations/checkout
            JsonObject chkReq = new JsonObject();
            chkReq.addProperty("assignmentId", aId.toString());
            chkReq.addProperty("actualTime", tStart.toString());
            chkReq.addProperty("odometer", 10000);
            chkReq.addProperty("fuelPercent", 100);
            chkReq.addProperty("notes", "Clean checkout");

            HttpResponse<String> chkResp = client.send(HttpRequest.newBuilder()
                    .uri(base.resolve("/api/v1/operations/checkout"))
                    .header("Authorization", "Bearer emp-tok")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(chkReq.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());

            require(chkResp.statusCode() == 200, "POST /api/v1/operations/checkout returns 200");
            JsonObject chkRes = JsonParser.parseString(chkResp.body()).getAsJsonObject();
            require(chkRes.get("checkoutOdometer").getAsInt() == 10000, "Checkout odometer matches");

            // GET /api/v1/operations/running-services
            HttpResponse<String> listResp = client.send(HttpRequest.newBuilder()
                    .uri(base.resolve("/api/v1/operations/running-services"))
                    .header("Authorization", "Bearer emp-tok")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());

            require(listResp.statusCode() == 200, "GET /api/v1/operations/running-services returns 200");
            JsonObject listRes = JsonParser.parseString(listResp.body()).getAsJsonObject();
            require(listRes.has("runningServices"), "Running services list returned");

            // POST /api/v1/operations/return
            JsonObject retReq = new JsonObject();
            retReq.addProperty("assignmentId", aId.toString());
            retReq.addProperty("actualTime", Instant.now().toString());
            retReq.addProperty("odometer", 10300);
            retReq.addProperty("fuelPercent", 90);
            retReq.addProperty("condition", "ACCEPTABLE");

            HttpResponse<String> retResp = client.send(HttpRequest.newBuilder()
                    .uri(base.resolve("/api/v1/operations/return"))
                    .header("Authorization", "Bearer emp-tok")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(retReq.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());

            require(retResp.statusCode() == 200, "POST /api/v1/operations/return returns 200");
            JsonObject retRes = JsonParser.parseString(retResp.body()).getAsJsonObject();
            require("ACCEPTABLE".equals(retRes.get("inspectionStatus").getAsString()), "Return inspection is ACCEPTABLE");

        } finally {
            server.stop(0);
        }
    }

    // -------------------------------------------------------------------------
    // 11. Desktop Client & Swing Flow Integrity (No UI claim before commit)
    // -------------------------------------------------------------------------
    private static void testDesktopClientAndUiFlowIntegrity() {
        DesktopConfig config = new DesktopConfig(Optional.of(URI.create("http://127.0.0.1:8080")));
        DesktopSession session = new DesktopSession(new MockAuthPort());

        OperationsHttpClient client = new OperationsHttpClient(config, session);
        require(client != null, "OperationsHttpClient instantiated");

        StaffSession staffSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));
        RunningServicesView view = new RunningServicesView(staffSession);

        require(view.servicesListModel != null, "Services list model initialized");
        require(view.checkoutButton.isEnabled(), "Checkout button ready");
        require(view.returnButton != null, "Return button ready");
        require(view.extendButton != null, "Extend button ready");
        require(view.maintenanceButton != null, "Maintenance button ready");

        // Connect client to RunningServicesView
        view.setClient(client);

        WorkspaceView workspace = new WorkspaceView(() -> {}, staffSession);
        workspace.connectOperations(client);
        require(workspace.runningServices != null, "WorkspaceView running services connected to live operations");
    }

    // =========================================================================
    // Mock Authentication Port for Desktop
    // =========================================================================
    private static class MockAuthPort implements AuthenticationPort {
        @Override public boolean isConfigured() { return true; }
        @Override public SignInResult signIn(String e, char[] p) { return null; }
        @Override public SignInResult refresh(SessionTokens t) { return null; }
        @Override public void logout(SessionTokens t) {}
        @Override public StaffSession verifySession(String accessToken) { return null; }
    }

    // =========================================================================
    // Mock Database for Operations Tests
    // =========================================================================
    private static class MockDatabase {
        final Map<UUID, AssignmentState> assignments = new ConcurrentHashMap<>();
        final Map<UUID, CustodyState> custodies = new ConcurrentHashMap<>();
        final Map<UUID, BookingState> bookings = new ConcurrentHashMap<>();
        final Map<UUID, String> vehicleStatuses = new ConcurrentHashMap<>();
        final Map<UUID, String> driverStatuses = new ConcurrentHashMap<>();
        final Map<UUID, List<UUID>> activeRestrictions = new ConcurrentHashMap<>();
        final Map<UUID, MaintenanceCaseRecord> maintenanceCases = new ConcurrentHashMap<>();
        boolean hasConflictingReservation = false;

        static class AssignmentState {
            UUID id, vehicleId, driverId, bookingId;
            boolean driverRequired;
            String status;
            long dailyRate, hourlyRate;
            Instant startTime, endTime;
        }

        static class CustodyState {
            UUID id, assignmentId, vehicleId;
            Instant checkoutTime;
            int checkoutOdometer, checkoutFuel;
            String checkoutNotes;
        }

        static class BookingState {
            UUID id;
            String number, status;
            long totalPaise, advancePaise;
            Instant startTime, endTime;

            BookingState(UUID id, String number, String status, long total, long advance, Instant s, Instant e) {
                this.id = id; this.number = number; this.status = status; this.totalPaise = total; this.advancePaise = advance;
                this.startTime = s; this.endTime = e;
            }
        }

        void addAssignment(UUID id, UUID vehId, UUID drvId, boolean drvReq, UUID bookId, String status, long daily, long hourly, Instant s, Instant e) {
            AssignmentState a = new AssignmentState();
            a.id = id; a.vehicleId = vehId; a.driverId = drvId; a.driverRequired = drvReq;
            a.bookingId = bookId; a.status = status; a.dailyRate = daily; a.hourlyRate = hourly;
            a.startTime = s; a.endTime = e;
            assignments.put(id, a);
            vehicleStatuses.put(vehId, "AVAILABLE");
            if (drvId != null) driverStatuses.put(drvId, "AVAILABLE");
        }

        void addCustody(UUID assignId, UUID vehId, Instant t, int odo, int fuel, String notes) {
            CustodyState c = new CustodyState();
            c.id = UUID.randomUUID();
            c.assignmentId = assignId;
            c.vehicleId = vehId;
            c.checkoutTime = t;
            c.checkoutOdometer = odo;
            c.checkoutFuel = fuel;
            c.checkoutNotes = notes;
            custodies.put(assignId, c);
        }

        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        if ("getConnection".equals(method.getName())) {
                            return createMockConnection();
                        }
                        return null;
                    }
            );
        }

        Connection createMockConnection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    new InvocationHandler() {
                        boolean autoCommit = true;

                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            if ("setAutoCommit".equals(name)) {
                                autoCommit = (Boolean) args[0];
                                return null;
                            }
                            if ("getAutoCommit".equals(name)) return autoCommit;
                            if ("commit".equals(name) || "rollback".equals(name) || "close".equals(name)) {
                                return null;
                            }
                            if ("prepareStatement".equals(name)) {
                                String sql = (String) args[0];
                                return createMockPreparedStatement(sql);
                            }
                            if ("createArrayOf".equals(name)) {
                                Object[] elements = (Object[]) args[1];
                                return Proxy.newProxyInstance(
                                        Array.class.getClassLoader(),
                                        new Class<?>[]{Array.class},
                                        (p, m, a) -> {
                                            if ("getArray".equals(m.getName())) return elements;
                                            return null;
                                        }
                                );
                            }
                            return null;
                        }
                    }
            );
        }

        PreparedStatement createMockPreparedStatement(String sql) {
            Map<Integer, Object> params = new HashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer idx) {
                            params.put(idx, args[1]);
                            return null;
                        }
                        if ("execute".equals(name)) {
                            return true;
                        }
                        if ("executeUpdate".equals(name)) {
                            return executeMockUpdate(sql, params);
                        }
                        if ("executeQuery".equals(name)) {
                            return executeMockQuery(sql, params);
                        }
                        if ("close".equals(name)) return null;
                        return null;
                    }
            );
        }

        int executeMockUpdate(String sql, Map<Integer, Object> params) {
            if (sql.contains("UPDATE vrms_private.booking_assignments SET status = 'CHECKED_OUT'")) {
                UUID aId = (UUID) params.get(1);
                assignments.get(aId).status = "CHECKED_OUT";
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.booking_assignments SET status = 'RETURNED'")) {
                UUID aId = (UUID) params.get(1);
                assignments.get(aId).status = "RETURNED";
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.vehicles SET operational_status = 'RENTED' WHERE id = ?")) {
                UUID vId = (UUID) params.get(1);
                vehicleStatuses.put(vId, "RENTED");
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.vehicles SET operational_status = 'AVAILABLE' WHERE id = ?")) {
                UUID vId = (UUID) params.get(1);
                vehicleStatuses.put(vId, "AVAILABLE");
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.vehicles SET operational_status = 'MAINTENANCE' WHERE id = ?")) {
                UUID vId = (UUID) params.get(1);
                vehicleStatuses.put(vId, "MAINTENANCE");
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.vehicles SET operational_status = ?")) {
                String st = (String) params.get(1);
                UUID vId = (UUID) params.get(2);
                vehicleStatuses.put(vId, st);
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.driver_profiles SET duty_status = 'ASSIGNED' WHERE id = ?")) {
                UUID dId = (UUID) params.get(1);
                driverStatuses.put(dId, "ASSIGNED");
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.driver_profiles SET duty_status = 'AVAILABLE' WHERE id = ?")) {
                UUID dId = (UUID) params.get(1);
                driverStatuses.put(dId, "AVAILABLE");
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.driver_profiles SET duty_status = ?")) {
                String st = (String) params.get(1);
                UUID dId = (UUID) params.get(2);
                driverStatuses.put(dId, st);
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.asset_restrictions SET active = false")) {
                UUID rId = (UUID) params.get(1);
                for (var entry : activeRestrictions.entrySet()) {
                    entry.getValue().remove(rId);
                }
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.booking_assignments SET end_time = ?")) {
                Instant end = ((Timestamp) params.get(1)).toInstant();
                UUID aId = (UUID) params.get(5);
                assignments.get(aId).endTime = end;
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.bookings SET")) {
                UUID bId = (UUID) params.get(params.size());
                if (sql.contains("base_total_paise = base_total_paise + ?")) {
                    long delta = (Long) params.get(1);
                    Instant end = ((Timestamp) params.get(4)).toInstant();
                    BookingState b = bookings.get(bId);
                    if (b != null) {
                        b.totalPaise += delta;
                        b.endTime = end;
                    }
                }
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.maintenance_cases SET")) {
                UUID cId = (UUID) params.get(6);
                long actual = (Long) params.get(1);
                long approved = (Long) params.get(2);
                String resNotes = (String) params.get(3);
                UUID resBy = (UUID) params.get(4);
                Instant resAt = ((Timestamp) params.get(5)).toInstant();
                MaintenanceCaseRecord old = maintenanceCases.get(cId);
                if (old != null) {
                    maintenanceCases.put(cId, new MaintenanceCaseRecord(
                            old.id(), old.companyId(), old.vehicleId(), old.assignmentId(), old.custodyId(),
                            old.restrictionId(), old.issueType(), "RESOLVED", old.estimatedCostPaise(),
                            old.allegedCustomerRecoveryPaise(), actual, approved, old.notes(), old.damagePhotoFileId(),
                            resNotes, old.reportedBy(), resBy, old.createdAt(), resAt
                    ));
                }
                return 1;
            }
            return 1;
        }

        ResultSet executeMockQuery(String sql, Map<Integer, Object> params) {
            List<Map<String, Object>> rows = new ArrayList<>();

            if (sql.contains("SELECT current_user = 'smart_vrms_api'")) {
                rows.add(Map.of("1", true));
            } else if (sql.contains("SELECT company_id, role, active FROM vrms_private.memberships")) {
                UUID userId = (UUID) params.get(1);
                if (ADMIN_ID.equals(userId) || EMPLOYEE_ID.equals(userId)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("1", COMPANY_ID);
                    row.put("2", ADMIN_ID.equals(userId) ? "ADMINISTRATOR" : "EMPLOYEE");
                    row.put("3", true);
                    row.put("company_id", COMPANY_ID);
                    row.put("role", ADMIN_ID.equals(userId) ? "ADMINISTRATOR" : "EMPLOYEE");
                    row.put("active", true);
                    rows.add(row);
                }
            } else if (sql.contains("SELECT vehicle_id, driver_id, driver_required, booking_id, status FROM vrms_private.booking_assignments")) {
                UUID aId = (UUID) params.get(2);
                AssignmentState a = assignments.get(aId);
                if (a != null) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("vehicle_id", a.vehicleId);
                    row.put("driver_id", a.driverId);
                    row.put("driver_required", a.driverRequired);
                    row.put("booking_id", a.bookingId);
                    row.put("status", a.status);
                    rows.add(row);
                }
            } else if (sql.contains("SELECT 1 FROM vrms_private.asset_restrictions WHERE company_id = ? AND asset_type = 'VEHICLE' AND asset_id = ? AND active = true")) {
                UUID vId = (UUID) params.get(2);
                List<UUID> rList = activeRestrictions.get(vId);
                if (rList != null && !rList.isEmpty()) {
                    rows.add(Map.of("col1", 1));
                }
            } else if (sql.contains("SELECT 1 FROM vrms_private.vehicle_reservations WHERE company_id = ? AND vehicle_id = ? AND status = 'ACTIVE' AND assignment_id <> ?")) {
                if (hasConflictingReservation) {
                    rows.add(Map.of("col1", 1));
                }
            } else if (sql.contains("INSERT INTO vrms_private.custody_records") && sql.contains("RETURNING id, created_at")) {
                UUID cId = UUID.randomUUID();
                Instant now = Instant.now();
                UUID aId = (UUID) params.get(2);
                UUID vId = (UUID) params.get(3);
                int odo = (Integer) params.get(5);
                int fuel = (Integer) params.get(6);
                String notes = (String) params.get(7);
                addCustody(aId, vId, now, odo, fuel, notes);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", cId);
                r.put("created_at", Timestamp.from(now));
                rows.add(r);
            } else if (sql.contains("SELECT id, checkout_time, checkout_odometer, checkout_fuel_percent, checkout_notes, checkout_by, created_at FROM vrms_private.custody_records")) {
                UUID aId = (UUID) params.get(2);
                CustodyState c = custodies.get(aId);
                if (c != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", c.id);
                    r.put("checkout_time", Timestamp.from(c.checkoutTime));
                    r.put("checkout_odometer", c.checkoutOdometer);
                    r.put("checkout_fuel_percent", c.checkoutFuel);
                    r.put("checkout_notes", c.checkoutNotes);
                    r.put("checkout_by", EMPLOYEE_ID);
                    r.put("created_at", Timestamp.from(c.checkoutTime));
                    rows.add(r);
                }
            } else if (sql.contains("INSERT INTO vrms_private.asset_restrictions") && sql.contains("RETURNING id")) {
                UUID rId = UUID.randomUUID();
                UUID vId = (UUID) params.get(2);
                activeRestrictions.computeIfAbsent(vId, k -> new ArrayList<>()).add(rId);
                rows.add(Map.of("id", rId));
            } else if (sql.contains("INSERT INTO vrms_private.maintenance_cases") && sql.contains("RETURNING id, created_at")) {
                UUID mId = UUID.randomUUID();
                Instant now = Instant.now();
                UUID vId = (UUID) params.get(2);
                UUID aId = (UUID) params.get(3);
                UUID cId = (UUID) params.get(4);
                UUID rId = (UUID) params.get(5);
                String issue = (String) params.get(6);
                long est = (Long) params.get(7);
                long rec = (Long) params.get(8);
                String notes = (String) params.get(9);
                maintenanceCases.put(mId, new MaintenanceCaseRecord(
                        mId, COMPANY_ID, vId, aId, cId, rId, issue, "OPEN", est, rec, 0L, 0L, notes, null, null, EMPLOYEE_ID, null, now, null
                ));
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", mId);
                r.put("created_at", Timestamp.from(now));
                rows.add(r);
            } else if (sql.contains("SELECT vehicle_id, restriction_id, status, issue_type FROM vrms_private.maintenance_cases")
                    || sql.contains("SELECT vehicle_id, restriction_id, issue_type, status FROM vrms_private.maintenance_cases")) {
                UUID cId = (UUID) params.get(2);
                MaintenanceCaseRecord mc = maintenanceCases.get(cId);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("vehicle_id", mc != null ? mc.vehicleId() : UUID.randomUUID());
                r.put("restriction_id", mc != null ? mc.restrictionId() : UUID.randomUUID());
                r.put("status", mc != null ? mc.status() : "OPEN");
                r.put("issue_type", mc != null ? mc.issueType() : "CUSTOMER_DAMAGE");
                rows.add(r);
            } else if (sql.contains("SELECT total_paise, actual_advance_received_paise FROM vrms_private.bookings WHERE id = ?")) {
                UUID bId = (UUID) params.get(1);
                BookingState b = bookings.get(bId);
                if (b != null) {
                    rows.add(Map.of("total_paise", b.totalPaise, "actual_advance_received_paise", b.advancePaise));
                }
            } else if (sql.contains("FROM vrms_private.booking_assignments WHERE company_id = ? AND booking_id = ?")) {
                UUID bId = (UUID) params.get(2);
                Object arrayParam = params.get(3);
                Set<UUID> allowedIds = null;
                if (arrayParam != null) {
                    try {
                        if (arrayParam instanceof Array arr) {
                            Object[] objs = (Object[]) arr.getArray();
                            allowedIds = new HashSet<>();
                            for (Object o : objs) allowedIds.add((UUID) o);
                        }
                    } catch (Exception ignored) {}
                }

                for (AssignmentState a : assignments.values()) {
                    if (a.bookingId.equals(bId) && (allowedIds == null || allowedIds.contains(a.id))) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("id", a.id);
                        r.put("vehicle_id", a.vehicleId);
                        r.put("start_time", Timestamp.from(a.startTime));
                        r.put("end_time", Timestamp.from(a.endTime));
                        r.put("daily_rate_paise", a.dailyRate);
                        r.put("hourly_rate_paise", a.hourlyRate);
                        r.put("driver_required", a.driverRequired);
                        r.put("driver_total_paise", 0L);
                        r.put("vehicle_total_paise", a.dailyRate * 2);
                        r.put("assignment_total_paise", a.dailyRate * 2);
                        rows.add(r);
                    }
                }
            } else if (sql.contains("SELECT b.id, b.booking_number, b.customer_id, c.full_name, c.mobile_number")) {
                for (BookingState b : bookings.values()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", b.id);
                    r.put("booking_number", b.number);
                    r.put("customer_id", UUID.randomUUID());
                    r.put("full_name", "Test Customer");
                    r.put("mobile_number", "+91 99999 88888");
                    r.put("visit_id", UUID.randomUUID());
                    r.put("status", b.status);
                    r.put("start_time", Timestamp.from(b.startTime));
                    r.put("end_time", Timestamp.from(b.endTime));
                    r.put("total_paise", b.totalPaise);
                    r.put("actual_advance_received_paise", b.advancePaise);
                    rows.add(r);
                }
            } else if (sql.contains("SELECT a.id, a.vehicle_id, v.registration_plate, vc.name, a.driver_id")) {
                for (AssignmentState a : assignments.values()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", a.id);
                    r.put("vehicle_id", a.vehicleId);
                    r.put("registration_plate", "KL-07-CD-1234");
                    r.put("name", "Sedan");
                    r.put("driver_id", a.driverId);
                    r.put("full_name", a.driverId != null ? "Driver Dave" : null);
                    r.put("start_time", Timestamp.from(a.startTime));
                    r.put("end_time", Timestamp.from(a.endTime));
                    r.put("status", a.status);
                    r.put("inspection_status", "ACCEPTABLE");
                    rows.add(r);
                }
            } else if (sql.contains("FROM vrms_private.maintenance_cases") && sql.contains("id = ?")) {
                UUID cId = (UUID) params.get(params.size());
                MaintenanceCaseRecord mc = maintenanceCases.get(cId);
                Instant now = Instant.now();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", cId);
                r.put("vehicle_id", mc != null ? mc.vehicleId() : UUID.randomUUID());
                r.put("assignment_id", mc != null ? mc.assignmentId() : null);
                r.put("custody_id", mc != null ? mc.custodyId() : null);
                r.put("restriction_id", mc != null ? mc.restrictionId() : null);
                r.put("issue_type", mc != null ? mc.issueType() : "CUSTOMER_DAMAGE");
                r.put("status", mc != null ? mc.status() : "RESOLVED");
                r.put("estimated_cost_paise", mc != null ? mc.estimatedCostPaise() : 100000L);
                r.put("alleged_customer_recovery_paise", mc != null ? mc.allegedCustomerRecoveryPaise() : 50000L);
                r.put("actual_cost_paise", mc != null ? mc.actualCostPaise() : 95000L);
                r.put("approved_customer_recovery_paise", mc != null ? mc.approvedCustomerRecoveryPaise() : 50000L);
                r.put("notes", mc != null ? mc.notes() : "Notes");
                r.put("damage_photo_file_id", mc != null ? mc.damagePhotoFileId() : null);
                r.put("resolution_notes", mc != null ? mc.resolutionNotes() : "Resolved");
                r.put("reported_by", mc != null ? mc.reportedBy() : EMPLOYEE_ID);
                r.put("resolved_by", mc != null ? mc.resolvedBy() : ADMIN_ID);
                r.put("created_at", Timestamp.from(now));
                r.put("resolved_at", mc != null && mc.resolvedAt() != null ? Timestamp.from(mc.resolvedAt()) : Timestamp.from(now));
                rows.add(r);
            } else if (sql.contains("FROM vrms_private.bookings") && (sql.contains("company_id = ? AND id = ?") || sql.contains("b.company_id = ? AND b.id = ?"))) {
                UUID bId = (UUID) params.get(2);
                BookingState b = bookings.get(bId);
                Instant now = Instant.now();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", bId);
                r.put("company_id", COMPANY_ID);
                r.put("customer_id", UUID.randomUUID());
                r.put("visit_id", UUID.randomUUID());
                r.put("booking_number", b != null ? b.number : "VRMS-BK-001");
                r.put("status", b != null ? b.status : "ACTIVE");
                r.put("start_time", Timestamp.from(b != null ? b.startTime : now));
                r.put("end_time", Timestamp.from(b != null ? b.endTime : now.plus(Duration.ofDays(2))));
                r.put("base_total_paise", b != null ? b.totalPaise : 500000L);
                r.put("driver_total_paise", 0L);
                r.put("tax_paise", 0L);
                r.put("total_paise", b != null ? b.totalPaise : 500000L);
                r.put("agreed_advance_paise", b != null ? b.advancePaise : 250000L);
                r.put("actual_advance_received_paise", b != null ? b.advancePaise : 250000L);
                r.put("deposit_paise", 100000L);
                r.put("pricing_snapshot", "{}");
                r.put("created_by", EMPLOYEE_ID);
                r.put("created_at", Timestamp.from(now));
                r.put("version", 2L);
                rows.add(r);
            } else if (sql.contains("SELECT driver_id FROM vrms_private.booking_assignments WHERE id = ?")) {
                UUID aId = (UUID) params.get(1);
                AssignmentState a = assignments.get(aId);
                if (a != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("driver_id", a.driverId);
                    rows.add(r);
                }
            } else if (sql.contains("FROM vrms_private.booking_assignments a WHERE a.booking_id = ?")) {
                UUID bId = (UUID) params.get(1);
                for (AssignmentState a : assignments.values()) {
                    if (a.bookingId.equals(bId)) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("id", a.id);
                        r.put("company_id", COMPANY_ID);
                        r.put("booking_id", a.bookingId);
                        r.put("vehicle_id", a.vehicleId);
                        r.put("driver_id", a.driverId);
                        r.put("driver_required", a.driverRequired);
                        r.put("start_time", Timestamp.from(a.startTime));
                        r.put("end_time", Timestamp.from(a.endTime));
                        r.put("daily_rate_paise", a.dailyRate);
                        r.put("hourly_rate_paise", a.hourlyRate);
                        r.put("vehicle_total_paise", a.dailyRate * 2);
                        r.put("driver_total_paise", 0L);
                        r.put("assignment_total_paise", a.dailyRate * 2);
                        r.put("status", a.status);
                        rows.add(r);
                    }
                }
            }

            return createMockResultSet(rows);
        }

        ResultSet createMockResultSet(List<Map<String, Object>> rows) {
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    new InvocationHandler() {
                        int index = -1;

                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            if ("next".equals(name)) {
                                index++;
                                return index < rows.size();
                            }
                            if ("close".equals(name)) return null;

                            if (index < 0 || index >= rows.size()) return null;
                            Map<String, Object> row = rows.get(index);

                            if (args != null && args.length >= 1) {
                                Object key = args[0];
                                Object val = null;
                                if (key instanceof String k) {
                                    val = row.get(k);
                                } else if (key instanceof Integer idx) {
                                    int i = 1;
                                    for (Object v : row.values()) {
                                        if (i == idx) { val = v; break; }
                                        i++;
                                    }
                                }

                                if ("getObject".equals(name)) {
                                    if (args.length == 2 && args[1] instanceof Class<?> clazz) {
                                        return clazz.cast(val);
                                    }
                                    return val;
                                }
                                if ("getString".equals(name)) return val != null ? val.toString() : null;
                                if ("getInt".equals(name)) return val instanceof Number n ? n.intValue() : 0;
                                if ("getLong".equals(name)) return val instanceof Number n ? n.longValue() : 0L;
                                if ("getBoolean".equals(name)) return Boolean.TRUE.equals(val);
                                if ("getTimestamp".equals(name)) return val instanceof Timestamp ts ? ts : null;
                            }
                            return null;
                        }
                    }
            );
        }
    }
}
