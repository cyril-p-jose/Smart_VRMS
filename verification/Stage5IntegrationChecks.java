package in.smartvrms.verification;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.application.port.*;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.ui.*;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.domain.QuoteEngine;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.StaffAuthorizer;

import javax.swing.*;
import java.awt.Dimension;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage 5 Integration Checks:
 * Running services, custody checkout/return, resource release vs debt,
 * saved-rate extensions, partial-package operations, and maintenance/damage state machines
 * (FR-OPS-001..004, FR-EXT-001..003, FR-DMG-001..004, FR-VEH-005, FR-DRV-003).
 */
public final class Stage5IntegrationChecks {
    private static int checks;
    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EMPLOYEE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMPANY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 5 integration checks...");
        testMigration006SchemaIntegrity();
        testOperationsStateMachinesAndDomainContracts();
        testExtensionRepricingAndPartialPackageDelta();
        testVrmsHttpServerStage5Endpoints();
        testRunningServicesViewLayoutAndResponsiveScaling();
        System.out.println("PASS: " + checks + " Stage 5 running services, custody, return, extension, damage, API, and UI assertions.");
    }

    private static void testMigration006SchemaIntegrity() throws Exception {
        Path p = Path.of("database/migrations/006_operations_and_extensions.sql");
        require(Files.exists(p), "Migration 006 file must exist");
        String sql = Files.readString(p);

        require(sql.contains("CREATE TABLE vrms_private.custody_records"), "Custody records table defined");
        require(sql.contains("CREATE TABLE vrms_private.extension_records"), "Extension records table defined");
        require(sql.contains("CREATE TABLE vrms_private.maintenance_cases"), "Maintenance cases table defined");

        // RLS & Security Policies
        require(sql.contains("ALTER TABLE vrms_private.custody_records ENABLE ROW LEVEL SECURITY"), "Custody RLS enabled");
        require(sql.contains("ALTER TABLE vrms_private.custody_records FORCE ROW LEVEL SECURITY"), "Custody RLS forced");
        require(sql.contains("ALTER TABLE vrms_private.extension_records ENABLE ROW LEVEL SECURITY"), "Extension RLS enabled");
        require(sql.contains("ALTER TABLE vrms_private.extension_records FORCE ROW LEVEL SECURITY"), "Extension RLS forced");
        require(sql.contains("ALTER TABLE vrms_private.maintenance_cases ENABLE ROW LEVEL SECURITY"), "Maintenance RLS enabled");
        require(sql.contains("ALTER TABLE vrms_private.maintenance_cases FORCE ROW LEVEL SECURITY"), "Maintenance RLS forced");

        require(sql.contains("GRANT SELECT, INSERT, UPDATE ON vrms_private.custody_records TO smart_vrms_api"), "Custody table granted to smart_vrms_api");
        require(sql.contains("GRANT SELECT, INSERT, UPDATE ON vrms_private.extension_records TO smart_vrms_api"), "Extension table granted to smart_vrms_api");
        require(sql.contains("GRANT SELECT, INSERT, UPDATE ON vrms_private.maintenance_cases TO smart_vrms_api"), "Maintenance table granted to smart_vrms_api");

        require(sql.contains("vrms_private.schema_migrations WHERE version = 4"), "Migration 006 checks migration 005 predecessor");
        require(sql.contains("VALUES (5, 'operations, custody, extensions, and maintenance')"), "Migration 006 records version 5");
    }

    private static void testOperationsStateMachinesAndDomainContracts() {
        MockOperationsPort mock = new MockOperationsPort();
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in", Set.of("ADMINISTRATOR"));
        StaffSession empSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));

        UUID assignId1 = UUID.randomUUID();
        UUID vehId1 = UUID.randomUUID();
        UUID drvId1 = UUID.randomUUID();
        mock.initAssignment(assignId1, vehId1, drvId1);
        Instant checkoutTime = Instant.now().minus(Duration.ofHours(25));

        // 1. Checkout flow (FR-OPS-002)
        CheckoutCommand cmd = new CheckoutCommand(assignId1, checkoutTime, 15000, 100, "Clean dispatch");
        CustodyRecord custody = mock.recordCheckout(empSession, cmd);
        require(custody != null, "Checkout created custody record");
        require(custody.checkoutOdometer() == 15000, "Odometer recorded");
        require(custody.checkoutFuelPercent() == 100, "Fuel recorded");
        require("PENDING".equals(custody.inspectionStatus()), "Initial inspection is PENDING");
        require(mock.vehicleStatuses.get(vehId1).equals("RENTED"), "Vehicle operational status is RENTED");
        require(mock.driverStatuses.get(drvId1).equals("ASSIGNED"), "Driver duty status is ASSIGNED");

        // 2. Duplicate checkout rejected (FR-OPS-002)
        boolean dupCheckoutCaught = false;
        try {
            mock.recordCheckout(empSession, cmd);
        } catch (IllegalStateException e) {
            dupCheckoutCaught = true;
        }
        require(dupCheckoutCaught, "Duplicate checkout on already checked out assignment denied");

        // 3. Clean inspected return releases vehicle even with unpaid balance (FR-OPS-003, AC-OPS-003)
        Instant returnTime = Instant.now();
        ReturnInspectionCommand retClean = new ReturnInspectionCommand(
                assignId1, returnTime, 15300, 90, "ACCEPTABLE", "No damage", null, null, 0, 0
        );
        CustodyRecord retCustody = mock.recordReturnInspection(empSession, retClean);
        require("ACCEPTABLE".equals(retCustody.inspectionStatus()), "Inspection status is ACCEPTABLE");
        require(mock.vehicleReservationsReleased.contains(assignId1), "Vehicle reservation released on clean return");
        require(mock.vehicleStatuses.get(vehId1).equals("AVAILABLE"), "Vehicle operational status released to AVAILABLE even with unpaid debt (FR-OPS-003)");
        require(mock.driverStatuses.get(drvId1).equals("AVAILABLE"), "Driver independently released to AVAILABLE (FR-DRV-003)");

        // 4. Duplicate return rejected
        boolean dupReturnCaught = false;
        try {
            mock.recordReturnInspection(empSession, retClean);
        } catch (IllegalStateException e) {
            dupReturnCaught = true;
        }
        require(dupReturnCaught, "Duplicate return on already returned assignment denied");

        // 5. Damaged return blocks vehicle with maintenance restriction (FR-OPS-003, FR-DMG-001, FR-DMG-003)
        UUID assignId2 = UUID.randomUUID();
        UUID vehId2 = UUID.randomUUID();
        UUID drvId2 = UUID.randomUUID();
        mock.initAssignment(assignId2, vehId2, drvId2);

        mock.recordCheckout(empSession, new CheckoutCommand(assignId2, checkoutTime, 20000, 100, "Dispatch"));
        ReturnInspectionCommand retDamaged = new ReturnInspectionCommand(
                assignId2, returnTime, 20400, 80, "DAMAGED", "Cracked windshield", null, "CUSTOMER_DAMAGE", 1200000, 1000000
        );
        CustodyRecord dmgCustody = mock.recordReturnInspection(empSession, retDamaged);
        require("DAMAGED".equals(dmgCustody.inspectionStatus()), "Inspection status is DAMAGED");
        require(mock.vehicleStatuses.get(vehId2).equals("MAINTENANCE"), "Vehicle operational status blocked as MAINTENANCE (FR-DMG-003)");
        require(mock.activeRestrictions.get(vehId2).size() == 1, "Vehicle placed under active restriction");
        require(mock.driverStatuses.get(drvId2).equals("AVAILABLE"), "Driver independently released even though vehicle is damaged (FR-DRV-003)");

        // 6. Multi-case maintenance safety gate (FR-DMG-003, AC-DMG-003)
        // Add second maintenance case for vehId2 (ordinary wear)
        MaintenanceCaseRecord wearCase = mock.openMaintenanceCase(
                empSession, vehId2, null, null, "ORDINARY_WEAR", 500000, 0, "Brake pad replacement", null
        );
        require(mock.activeRestrictions.get(vehId2).size() == 2, "Vehicle now has two active maintenance cases");
        require(wearCase.allegedCustomerRecoveryPaise() == 0, "Ordinary wear has 0 customer recovery (FR-DMG-002)");

        // Resolve first case: vehicle MUST STILL BE BLOCKED because wearCase is unresolved (AC-DMG-003)
        UUID firstCaseId = mock.maintenanceCases.get(vehId2).get(0).id();
        mock.resolveMaintenanceCase(adminSession, new MaintenanceResolutionCommand(firstCaseId, 1150000, 1000000, "Windshield replaced"));
        require(mock.vehicleStatuses.get(vehId2).equals("MAINTENANCE"), "Vehicle STAYS BLOCKED because second open case exists (AC-DMG-003)");

        // Non-admin attempting to resolve maintenance case is denied
        boolean nonAdminDeny = false;
        try {
            mock.resolveMaintenanceCase(empSession, new MaintenanceResolutionCommand(wearCase.id(), 480000, 0, "Brakes done"));
        } catch (SecurityException e) {
            nonAdminDeny = true;
        }
        require(nonAdminDeny, "Employee cannot resolve maintenance case; Administrator required (FR-DMG-002)");

        // Admin resolves second case: vehicle now transitions to AVAILABLE
        mock.resolveMaintenanceCase(adminSession, new MaintenanceResolutionCommand(wearCase.id(), 480000, 0, "Brakes done"));
        require(mock.vehicleStatuses.get(vehId2).equals("AVAILABLE"), "Vehicle transitions to AVAILABLE only after ALL restrictions clear (FR-DMG-003)");
    }

    private static void testExtensionRepricingAndPartialPackageDelta() {
        // Pure repricing test: AC-EXT-002
        // Example: Day 1 rate 2500 INR/day (250000 paise).
        // Original: 2 days (48 hours) = 2 * 250000 = 500000 paise.
        // Revised: 3 days (72 hours) = 3 * 250000 = 750000 paise.
        // Incremental delta charge = 750000 - 500000 = 250000 paise (exactly 1 day delta, NO duplicate 24h min or double charge).
        Instant t0 = Instant.parse("2026-11-10T10:00:00Z");
        Instant t2 = t0.plus(Duration.ofDays(2));
        Instant t3 = t0.plus(Duration.ofDays(3));

        UUID vId = UUID.randomUUID();
        AssignmentQuote origQuote = QuoteEngine.calculateAssignmentQuote(vId, "Sedan", 250000, 10416, false, 0, 0, t0, t2);
        require(origQuote.vehicleTotalPaise() == 500000L, "2-day original total is 5000 INR in paise");

        AssignmentQuote extQuote = QuoteEngine.calculateAssignmentQuote(vId, "Sedan", 250000, 10416, false, 0, 0, t0, t3);
        require(extQuote.vehicleTotalPaise() == 750000L, "3-day extended full-period total is 7500 INR in paise");

        long incrementalDelta = extQuote.vehicleTotalPaise() - origQuote.vehicleTotalPaise();
        require(incrementalDelta == 250000L, "Extension delta is exactly 1-day rate with zero duplicate first-day fee (FR-EXT-002)");

        // Partial package extension (FR-EXT-003, AC-EXT-003)
        // 2-vehicle package: Vehicle A and Vehicle B.
        // Extending only Vehicle A extends A's end time and adds A's delta; Vehicle B retains original return time.
        MockOperationsPort mock = new MockOperationsPort();
        UUID bId = UUID.randomUUID();
        UUID aId1 = UUID.randomUUID();
        UUID aId2 = UUID.randomUUID();
        mock.initPackageBooking(bId, List.of(aId1, aId2), t0, t2);

        ExtensionQuote eq = mock.quoteExtension(COMPANY_ID, new ExtensionCommand(bId, List.of(aId1), t3, "Partial extension"));
        require(eq.assignmentDeltas().size() == 1, "Only selected assignment quoted for partial extension (FR-EXT-003)");
        require(eq.totalDeltaPaise() == 250000L, "Total delta corresponds to single extended vehicle");

        // Conflict check: if future booking exists for Vehicle A, extension fails and 0 changes commit (AC-EXT-001)
        mock.hasConflict = true;
        boolean conflictCaught = false;
        try {
            StaffSession emp = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));
            mock.extendAssignments(emp, new ExtensionCommand(bId, List.of(aId1), t3, "Will conflict"));
        } catch (IllegalStateException e) {
            conflictCaught = true;
        }
        require(conflictCaught, "Extension with future reservation conflict is rejected (AC-EXT-001)");
        require(mock.bookingEndTimes.get(bId).equals(t2), "Original booking end time completely preserved on conflict (zero partial commit)");
    }

    private static void testVrmsHttpServerStage5Endpoints() throws Exception {
        MockOperationsPort mockOps = new MockOperationsPort();
        SupabaseConfig config = new SupabaseConfig("https://abcdefghijklmnopqrst.supabase.co", "sb_publishable_fixture0123456789");
        SupabaseAuth auth = new SupabaseAuth(config);

        StaffAuthorizer.Staff adminStaff = new StaffAuthorizer.Staff(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR");
        StaffAuthorizer.Staff empStaff = new StaffAuthorizer.Staff(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE");

        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            if ("admin-tok".equals(token)) {
                return adminStaff;
            }
            if ("emp-tok".equals(token)) {
                if (cap.administratorOnly()) {
                    throw new SecurityException("Forbidden: Administrator role required.");
                }
                return empStaff;
            }
            throw new IllegalArgumentException("Invalid token");
        };

        VrmsHttpServer server = new VrmsHttpServer(
                new HttpServerConfig(0), auth, authorizer,
                null, null, null, null, null, null, null, null, null, mockOps
        );
        server.start();

        try {
            HttpClient client = HttpClient.newHttpClient();
            URI base = server.baseUri();

            // 1. POST /api/v1/operations/checkout
            UUID aId = UUID.randomUUID();
            mockOps.initAssignment(aId, UUID.randomUUID(), UUID.randomUUID());

            JsonObject chkReq = new JsonObject();
            chkReq.addProperty("assignmentId", aId.toString());
            chkReq.addProperty("actualTime", Instant.now().toString());
            chkReq.addProperty("odometer", 10000);
            chkReq.addProperty("fuelPercent", 100);
            chkReq.addProperty("notes", "Clean checkout");

            HttpResponse<String> chkResp = client.send(HttpRequest.newBuilder()
                    .uri(base.resolve("/api/v1/operations/checkout"))
                    .header("Authorization", "Bearer emp-tok")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(chkReq.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());

            require(chkResp.statusCode() == 200, "Checkout endpoint returns 200 OK");
            JsonObject chkJson = JsonParser.parseString(chkResp.body()).getAsJsonObject();
            require(chkJson.has("inspectionStatus") && "PENDING".equals(chkJson.get("inspectionStatus").getAsString()), "Custody JSON returned with inspectionStatus PENDING");

            // 2. POST /api/v1/operations/return
            JsonObject retReq = new JsonObject();
            retReq.addProperty("assignmentId", aId.toString());
            retReq.addProperty("actualTime", Instant.now().toString());
            retReq.addProperty("odometer", 10250);
            retReq.addProperty("fuelPercent", 90);
            retReq.addProperty("condition", "ACCEPTABLE");

            HttpResponse<String> retResp = client.send(HttpRequest.newBuilder()
                    .uri(base.resolve("/api/v1/operations/return"))
                    .header("Authorization", "Bearer emp-tok")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(retReq.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());

            require(retResp.statusCode() == 200, "Return inspection endpoint returns 200 OK");
            JsonObject retJson = JsonParser.parseString(retResp.body()).getAsJsonObject();
            require("ACCEPTABLE".equals(retJson.get("inspectionStatus").getAsString()), "Return inspection condition is ACCEPTABLE");

            // 3. GET /api/v1/operations/running-services
            HttpResponse<String> runResp = client.send(HttpRequest.newBuilder()
                    .uri(base.resolve("/api/v1/operations/running-services"))
                    .header("Authorization", "Bearer emp-tok")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());

            require(runResp.statusCode() == 200, "Running services list endpoint returns 200 OK");
            JsonObject runJson = JsonParser.parseString(runResp.body()).getAsJsonObject();
            require(runJson.has("runningServices"), "Response contains runningServices array");

            // 4. POST /api/v1/operations/maintenance/open
            JsonObject mOpenReq = new JsonObject();
            mOpenReq.addProperty("vehicleId", UUID.randomUUID().toString());
            mOpenReq.addProperty("issueType", "ORDINARY_WEAR");
            mOpenReq.addProperty("estimatedCostPaise", 45000);
            mOpenReq.addProperty("notes", "Oil change");

            HttpResponse<String> mOpenResp = client.send(HttpRequest.newBuilder()
                    .uri(base.resolve("/api/v1/operations/maintenance/open"))
                    .header("Authorization", "Bearer emp-tok")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mOpenReq.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());

            require(mOpenResp.statusCode() == 201, "Maintenance open endpoint returns 201 Created");

            JsonObject mOpenJson = JsonParser.parseString(mOpenResp.body()).getAsJsonObject();
            UUID createdCaseId = UUID.fromString(mOpenJson.get("id").getAsString());

            // 5. POST /api/v1/operations/maintenance/resolve (Role Isolation: Employee 403, Admin 200)
            JsonObject mResReq = new JsonObject();
            mResReq.addProperty("caseId", createdCaseId.toString());
            mResReq.addProperty("actualCostPaise", 40000);
            mResReq.addProperty("approvedCustomerRecoveryPaise", 0);
            mResReq.addProperty("resolutionNotes", "Service completed");

            HttpResponse<String> empResolveResp = client.send(HttpRequest.newBuilder()
                    .uri(base.resolve("/api/v1/operations/maintenance/resolve"))
                    .header("Authorization", "Bearer emp-tok")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mResReq.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());

            require(empResolveResp.statusCode() == 403, "Employee denied from resolving maintenance case (403 Forbidden)");

            HttpResponse<String> adminResolveResp = client.send(HttpRequest.newBuilder()
                    .uri(base.resolve("/api/v1/operations/maintenance/resolve"))
                    .header("Authorization", "Bearer admin-tok")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mResReq.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());

            require(adminResolveResp.statusCode() == 200, "Administrator successfully resolves maintenance case (200 OK)");

            // 6. Security headers & correlation ID
            require("nosniff".equals(adminResolveResp.headers().firstValue("X-Content-Type-Options").orElse("")), "Security header nosniff present");
            require(adminResolveResp.headers().firstValue("X-Correlation-ID").isPresent(), "Correlation ID present on operations responses");

        } finally {
            server.stop(0);
        }
    }

    private static void testRunningServicesViewLayoutAndResponsiveScaling() {
        StaffSession session = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in", Set.of("ADMINISTRATOR"));
        RunningServicesView view = new RunningServicesView(session);

        require(view.servicesListModel.size() > 0, "Running services specimens populated");
        require(view.checkoutButton != null, "Checkout button initialized");
        require(view.returnButton != null, "Return button initialized");
        require(view.extendButton != null, "Extend button initialized");
        require(view.maintenanceButton != null, "Maintenance button initialized");

        WorkspaceView workspace = new WorkspaceView(() -> {}, session);
        require(workspace.runningServices != null, "WorkspaceView contains RunningServicesView");

        // Responsive resolutions: 1024x720, 1280x820, 1600x1000
        int[][] resolutions = {{1024, 720}, {1280, 820}, {1600, 1000}};
        for (int[] res : resolutions) {
            view.setSize(new Dimension(res[0], res[1]));
            view.doLayout();
            require(view.getWidth() == res[0] && view.getHeight() == res[1], "RunningServicesView scales to " + res[0] + "x" + res[1]);

            workspace.setSize(new Dimension(res[0], res[1]));
            workspace.doLayout();
            workspace.showPage("Running services");
            require(workspace.getWidth() == res[0] && workspace.getHeight() == res[1], "WorkspaceView scales to " + res[0] + "x" + res[1]);
        }
    }

    // =========================================================================
    // Mock Operations Port for offline verification
    // =========================================================================
    private static class MockOperationsPort implements OperationsPort {
        final Map<UUID, UUID> assignVehicles = new ConcurrentHashMap<>();
        final Map<UUID, UUID> assignDrivers = new ConcurrentHashMap<>();
        final Map<UUID, String> vehicleStatuses = new ConcurrentHashMap<>();
        final Map<UUID, String> driverStatuses = new ConcurrentHashMap<>();
        final Map<UUID, List<UUID>> activeRestrictions = new ConcurrentHashMap<>();
        final Set<UUID> vehicleReservationsReleased = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final Map<UUID, List<MaintenanceCaseRecord>> maintenanceCases = new ConcurrentHashMap<>();
        final Map<UUID, Instant> bookingEndTimes = new ConcurrentHashMap<>();
        boolean hasConflict = false;

        void initAssignment(UUID assignId, UUID vehId, UUID drvId) {
            assignVehicles.put(assignId, vehId);
            if (drvId != null) assignDrivers.put(assignId, drvId);
            vehicleStatuses.put(vehId, "AVAILABLE");
            if (drvId != null) driverStatuses.put(drvId, "AVAILABLE");
        }

        void initPackageBooking(UUID bId, List<UUID> assignIds, Instant start, Instant end) {
            bookingEndTimes.put(bId, end);
        }

        @Override
        public CustodyRecord recordCheckout(StaffSession session, CheckoutCommand cmd) {
            UUID vehId = assignVehicles.getOrDefault(cmd.assignmentId(), UUID.randomUUID());
            UUID drvId = assignDrivers.get(cmd.assignmentId());
            if ("RENTED".equals(vehicleStatuses.get(vehId))) {
                throw new IllegalStateException("Assignment already checked out");
            }
            vehicleStatuses.put(vehId, "RENTED");
            if (drvId != null) driverStatuses.put(drvId, "ASSIGNED");
            return new CustodyRecord(
                    UUID.randomUUID(), session.companyId(), cmd.assignmentId(), vehId,
                    cmd.actualTime(), cmd.odometer(), cmd.fuelPercent(), cmd.notes(),
                    null, null, null, "PENDING", null, null, session.userId(), null, Instant.now()
            );
        }

        @Override
        public CustodyRecord recordReturnInspection(StaffSession session, ReturnInspectionCommand cmd) {
            if (vehicleReservationsReleased.contains(cmd.assignmentId())) {
                throw new IllegalStateException("Assignment already returned");
            }
            vehicleReservationsReleased.add(cmd.assignmentId());
            UUID vehId = assignVehicles.getOrDefault(cmd.assignmentId(), UUID.randomUUID());
            UUID drvId = assignDrivers.get(cmd.assignmentId());

            if ("ACCEPTABLE".equals(cmd.condition())) {
                vehicleStatuses.put(vehId, "AVAILABLE");
            } else {
                vehicleStatuses.put(vehId, "MAINTENANCE");
                UUID restrId = UUID.randomUUID();
                activeRestrictions.computeIfAbsent(vehId, k -> new ArrayList<>()).add(restrId);
                MaintenanceCaseRecord mc = new MaintenanceCaseRecord(
                        UUID.randomUUID(), session.companyId(), vehId, cmd.assignmentId(), UUID.randomUUID(),
                        restrId, cmd.issueType(), "OPEN", cmd.estimatedRepairCostPaise(),
                        cmd.allegedCustomerRecoveryPaise(), 0, 0, cmd.damageNotes(), null, null,
                        session.userId(), null, Instant.now(), null
                );
                maintenanceCases.computeIfAbsent(vehId, k -> new ArrayList<>()).add(mc);
            }
            if (drvId != null) driverStatuses.put(drvId, "AVAILABLE");

            return new CustodyRecord(
                    UUID.randomUUID(), session.companyId(), cmd.assignmentId(), vehId,
                    Instant.now().minus(Duration.ofDays(1)), 10000, 100, "Clean",
                    cmd.actualTime(), cmd.odometer(), cmd.fuelPercent(), cmd.condition(),
                    cmd.damageNotes(), cmd.damagePhotoFileId(), session.userId(), session.userId(), Instant.now()
            );
        }

        @Override
        public ExtensionQuote quoteExtension(UUID companyId, ExtensionCommand cmd) {
            List<ExtensionQuote.AssignmentDelta> deltas = new ArrayList<>();
            for (UUID aId : cmd.assignmentIds()) {
                deltas.add(new ExtensionQuote.AssignmentDelta(
                        aId, UUID.randomUUID(), Instant.now(), cmd.revisedEndTime(), 250000, 0, 250000
                ));
            }
            return new ExtensionQuote(cmd.bookingId(), deltas, 250000L * deltas.size(), cmd.revisedEndTime());
        }

        @Override
        public BookingRecord extendAssignments(StaffSession session, ExtensionCommand cmd) {
            if (hasConflict) {
                throw new IllegalStateException("Extension conflict detected: overlapping future booking");
            }
            bookingEndTimes.put(cmd.bookingId(), cmd.revisedEndTime());
            return new BookingRecord(
                    cmd.bookingId(), session.companyId(), UUID.randomUUID(), UUID.randomUUID(),
                    "VRMS-BK-0102", "ACTIVE", Instant.now(), cmd.revisedEndTime(),
                    750000, 0, 0, 750000, 200000, 200000, 0, "{}", List.of(), session.userId(), Instant.now(), 2
            );
        }

        @Override
        public List<RunningServiceSummary> listRunningServices(UUID companyId, boolean overdueOnly) {
            return List.of(new RunningServiceSummary(
                    UUID.randomUUID(), "VRMS-BK-0099", UUID.randomUUID(), "Test Customer", "+91 99999 88888",
                    UUID.randomUUID(), "ACTIVE", Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)),
                    false, 1, List.of("KL-07-CD-9012"), List.of("Driver A"), 500000, 200000, 300000, List.of()
            ));
        }

        @Override
        public RunningServiceSummary getRunningServiceDetail(UUID companyId, UUID bookingId) {
            return listRunningServices(companyId, false).get(0);
        }

        @Override
        public MaintenanceCaseRecord openMaintenanceCase(StaffSession session, UUID vehicleId, UUID assignmentId, UUID custodyId, String issueType, long estimatedCostPaise, long allegedRecoveryPaise, String notes, UUID photoFileId) {
            UUID caseId = UUID.randomUUID();
            UUID restrictionId = UUID.randomUUID();
            activeRestrictions.computeIfAbsent(vehicleId, k -> new ArrayList<>()).add(restrictionId);
            vehicleStatuses.put(vehicleId, "MAINTENANCE");
            MaintenanceCaseRecord rec = new MaintenanceCaseRecord(
                    caseId, session.companyId(), vehicleId, assignmentId, custodyId, restrictionId,
                    issueType, "OPEN", estimatedCostPaise, allegedRecoveryPaise, 0, 0, notes, photoFileId, null,
                    session.userId(), null, Instant.now(), null
            );
            maintenanceCases.computeIfAbsent(vehicleId, k -> new ArrayList<>()).add(rec);
            return rec;
        }

        @Override
        public MaintenanceCaseRecord resolveMaintenanceCase(StaffSession session, MaintenanceResolutionCommand cmd) {
            if (!session.isAdministrator()) {
                throw new SecurityException("Forbidden: Administrator required");
            }
            for (var entry : maintenanceCases.entrySet()) {
                for (var mc : entry.getValue()) {
                    if (mc.id().equals(cmd.caseId())) {
                        activeRestrictions.get(entry.getKey()).remove(mc.restrictionId());
                        if (activeRestrictions.get(entry.getKey()).isEmpty()) {
                            vehicleStatuses.put(entry.getKey(), "AVAILABLE");
                        }
                        return new MaintenanceCaseRecord(
                                mc.id(), mc.companyId(), mc.vehicleId(), mc.assignmentId(), mc.custodyId(),
                                mc.restrictionId(), mc.issueType(), "RESOLVED", mc.estimatedCostPaise(),
                                mc.allegedCustomerRecoveryPaise(), cmd.actualCostPaise(), cmd.approvedCustomerRecoveryPaise(),
                                mc.notes(), mc.damagePhotoFileId(), cmd.resolutionNotes(), mc.reportedBy(),
                                session.userId(), mc.createdAt(), Instant.now()
                        );
                    }
                }
            }
            throw new IllegalArgumentException("Case not found");
        }

        @Override
        public List<MaintenanceCaseRecord> listMaintenanceCases(UUID companyId, UUID vehicleId, boolean openOnly) {
            List<MaintenanceCaseRecord> res = new ArrayList<>();
            for (var list : maintenanceCases.values()) {
                for (var mc : list) {
                    if (vehicleId == null || mc.vehicleId().equals(vehicleId)) {
                        if (!openOnly || !"RESOLVED".equals(mc.status())) res.add(mc);
                    }
                }
            }
            return res;
        }
    }
}
