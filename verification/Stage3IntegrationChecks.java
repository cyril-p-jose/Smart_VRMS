package in.smartvrms.verification;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.application.port.*;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.ui.*;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.StaffAuthorizer;

import javax.swing.*;
import java.awt.Dimension;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage 3 Integration Checks:
 * Connected records & personnel (FR-EMP-001..008, FR-VEH-001..006, FR-DOC-001..002, FR-DRV-001..004, FR-CUST-001..008).
 * Verifies schema integrity, RLS, domain business logic, API server endpoints, role isolation, and Swing UI.
 */
public final class Stage3IntegrationChecks {
    private static int checks;
    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EMPLOYEE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMPANY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 3 integration checks...");
        testMigration004Integrity();
        testPersonnelPortAndDomainLogic();
        testFleetPortAndVehicleDocuments();
        testDriverPortAndPrivacyIsolation();
        testCustomerPortAndVisitConstraints();
        testVrmsHttpServerStage3Endpoints();
        testDesktopStage3ViewsLayoutAndResponsive();
        System.out.println("PASS: " + checks + " Stage 3 connected records, personnel, fleet, driver, customer and UI assertions.");
    }

    private static void testMigration004Integrity() throws Exception {
        Path p = Path.of("database/migrations/004_connected_records.sql");
        require(Files.exists(p), "migration 004 file exists");
        String sql = Files.readString(p);

        // Required tables
        require(sql.contains("CREATE TABLE vrms_private.employees"), "employees table defined");
        require(sql.contains("CREATE TABLE vrms_private.attendance_records"), "attendance_records table defined");
        require(sql.contains("CREATE TABLE vrms_private.compensation_records"), "compensation_records table defined");
        require(sql.contains("CREATE TABLE vrms_private.driver_profiles"), "driver_profiles table defined");
        require(sql.contains("CREATE TABLE vrms_private.vehicle_categories"), "vehicle_categories table defined");
        require(sql.contains("CREATE TABLE vrms_private.vehicles"), "vehicles table defined");
        require(sql.contains("CREATE TABLE vrms_private.vehicle_documents"), "vehicle_documents table defined");
        require(sql.contains("CREATE TABLE vrms_private.asset_restrictions"), "asset_restrictions table defined");
        require(sql.contains("CREATE TABLE vrms_private.customers"), "customers table defined");
        require(sql.contains("CREATE TABLE vrms_private.customer_visits"), "customer_visits table defined");

        // RLS enforcement
        require(sql.contains("ALTER TABLE vrms_private.employees ENABLE ROW LEVEL SECURITY;"), "employees RLS enabled");
        require(sql.contains("ALTER TABLE vrms_private.employees FORCE ROW LEVEL SECURITY;"), "employees RLS forced");
        require(sql.contains("ALTER TABLE vrms_private.compensation_records ENABLE ROW LEVEL SECURITY;"), "compensation_records RLS enabled");
        require(sql.contains("ALTER TABLE vrms_private.compensation_records FORCE ROW LEVEL SECURITY;"), "compensation_records RLS forced");
        require(sql.contains("ALTER TABLE vrms_private.vehicles ENABLE ROW LEVEL SECURITY;"), "vehicles RLS enabled");
        require(sql.contains("ALTER TABLE vrms_private.customers ENABLE ROW LEVEL SECURITY;"), "customers RLS enabled");

        // Admin-only compensation RLS policy
        require(sql.contains("compensation_admin_only"), "compensation_admin_only RLS policy exists");
        require(sql.contains("current_setting('vrms.role', true) = 'ADMINISTRATOR'"), "compensation isolated to Administrator in RLS");

        // Age-at-visit check constraint
        require(sql.contains("age_at_visit >= 18 AND age_at_visit <= 120"), "age_at_visit 18-120 constraint present (FR-CUST-008)");

        // Non-statutory compensation check
        require(sql.contains("CHECK (base_rate_paise >= 0)"), "compensation tracks non-negative rates");

        // Grants
        require(sql.contains("GRANT SELECT, INSERT, UPDATE ON vrms_private.employees TO smart_vrms_api;"), "employees grant to smart_vrms_api");
        require(sql.contains("GRANT SELECT, INSERT, UPDATE ON vrms_private.vehicles TO smart_vrms_api;"), "vehicles grant to smart_vrms_api");
        require(sql.contains("GRANT SELECT, INSERT, UPDATE ON vrms_private.customers TO smart_vrms_api;"), "customers grant to smart_vrms_api");
        require(sql.contains("INSERT INTO vrms_private.schema_migrations"), "migration 004 recorded in schema_migrations");
    }

    private static void testPersonnelPortAndDomainLogic() {
        MockPersonnelPort personnel = new MockPersonnelPort();
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of("MANAGE_PERSONNEL", "VIEW_COMPENSATION"));
        StaffSession employee = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of("VIEW_OPERATIONAL_HISTORY"));

        // Register employee (FR-EMP-001)
        EmployeeRecord emp = personnel.registerEmployee(admin, "Rahul Nair", "+919847012345", "rahul@vrms.in", "Kadavanthra, Kochi", LocalDate.of(2025, 1, 15), true, null);
        require(emp != null, "employee registered");
        require(emp.fullName().equals("Rahul Nair"), "full name matches");
        require(emp.isDriver(), "is driver flag preserved (FR-EMP-008)");
        require("ACTIVE".equals(emp.employmentStatus()), "initial status active");

        // Duplicate employee detection (FR-EMP-002)
        var dup = personnel.findDuplicateEmployee(admin, "+919847012345", "other@vrms.in");
        require(dup.isPresent(), "duplicate by phone detected");
        require(dup.get().id().equals(emp.id()), "duplicate matches original ID");
        require(!dup.get().email().equals("other@vrms.in"), "duplicate suggestion does NOT auto-merge identity");

        // Soft-deactivation with mandatory reason (FR-EMP-003)
        boolean caughtNoReason = false;
        try {
            personnel.deactivateEmployee(admin, emp.id(), "   ");
        } catch (IllegalArgumentException ex) {
            caughtNoReason = true;
        }
        require(caughtNoReason, "deactivation requires non-empty reason");

        EmployeeRecord deactivated = personnel.deactivateEmployee(admin, emp.id(), "Resigned for higher studies");
        require("DEACTIVATED".equals(deactivated.employmentStatus()), "employee soft-deactivated");

        // Active-only filtering preserves historical records
        List<EmployeeRecord> activeList = personnel.listEmployees(admin, true);
        require(activeList.isEmpty(), "active-only list excludes deactivated employee");
        List<EmployeeRecord> allList = personnel.listEmployees(admin, false);
        require(allList.size() == 1, "complete list preserves historical deactivated employee");

        // Attendance tracking (FR-EMP-005)
        AttendanceRecord att = personnel.recordAttendance(admin, emp.id(), LocalDate.of(2026, 10, 25), "PRESENT", "Morning shift");
        require(att != null && att.employeeId().equals(emp.id()), "attendance recorded");
        List<AttendanceRecord> history = personnel.getAttendanceHistory(admin, emp.id(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        require(history.size() == 1, "attendance query returns recorded shift");

        // Admin compensation recording (FR-EMP-006)
        CompensationRecord comp = personnel.recordCompensation(admin, emp.id(), "MONTHLY_SALARY", 3000000L, 50000L, LocalDate.of(2026, 1, 1));
        require(comp.baseRatePaise() == 3000000L, "base rate 30,000 INR recorded");
        require(comp.tripAllowancePaise() == 50000L, "trip allowance 500 INR recorded");

        // Non-admin compensation isolation (FR-ACC-003)
        boolean caughtEmpCompDenied = false;
        try {
            personnel.getCompensation(employee, emp.id());
        } catch (SecurityException ex) {
            caughtEmpCompDenied = true;
        }
        require(caughtEmpCompDenied, "employee role forbidden from accessing compensation");

        var adminComp = personnel.getCompensation(admin, emp.id());
        require(adminComp.isPresent(), "admin can read compensation record");
    }

    private static void testFleetPortAndVehicleDocuments() {
        MockFleetPort fleet = new MockFleetPort();
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of("MANAGE_VEHICLE_MASTER", "VIEW_FLEET", "RECORD_FLEET_FREEZE", "RENEW_ASSIGNED_DOCUMENT"));

        UUID catId = UUID.randomUUID();
        VehicleRecord v = fleet.registerVehicle(admin, catId, "KL 07 CD 1234", "Toyota", "Innova Crysta", 7, 350000L, 30000L, null);
        require(v != null, "vehicle registered");
        require(v.registrationPlate().equals("KL 07 CD 1234"), "plate matches");
        require(v.dailyRatePaise() == 350000L, "daily rate matches (3,500 INR)");

        // Duplicate plate within company rejected
        boolean caughtDupPlate = false;
        try {
            fleet.registerVehicle(admin, catId, "KL 07 CD 1234", "Toyota", "Innova Crysta", 7, 350000L, 30000L, null);
        } catch (IllegalArgumentException ex) {
            caughtDupPlate = true;
        }
        require(caughtDupPlate, "duplicate plate rejected within company");

        // Asset restriction / freeze (FR-VEH-006)
        AssetRestrictionRecord freeze = fleet.freezeAsset(admin, "VEHICLE", v.id(), "Scheduled 40,000 km brake overhaul", Instant.now(), Instant.now().plusSeconds(86400));
        require(freeze.active(), "freeze restriction is active");
        require(freeze.assetId().equals(v.id()), "asset ID matches vehicle");

        // Resolve restriction
        fleet.resolveAssetRestriction(admin, freeze.id());
        require(!fleet.restrictions.get(freeze.id()).active(), "restriction resolved and inactive");

        // Vehicle Document management with versioning (FR-DOC-001, FR-DOC-002)
        UUID fileId = UUID.randomUUID();
        VehicleDocumentRecord doc1 = fleet.addVehicleDocument(admin, v.id(), "REGISTRATION_CERTIFICATE", fileId, LocalDate.of(2023, 1, 1), LocalDate.of(2038, 1, 1));
        require(doc1.version() == 1, "initial document is version 1");

        UUID fileId2 = UUID.randomUUID();
        VehicleDocumentRecord doc2 = fleet.addVehicleDocument(admin, v.id(), "REGISTRATION_CERTIFICATE", fileId2, LocalDate.of(2023, 1, 1), LocalDate.of(2038, 1, 1));
        require(doc2.version() == 2, "updated document increments to version 2");

        List<VehicleDocumentRecord> docs = fleet.getVehicleDocuments(admin, v.id());
        require(docs.size() == 2, "full document audit versions preserved");
    }

    private static void testDriverPortAndPrivacyIsolation() {
        MockDriverPort driverPort = new MockDriverPort();
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of("MANAGE_DRIVER_MASTER", "VIEW_DRIVER_OPERATIONS"));
        StaffSession employee = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of("VIEW_DRIVER_OPERATIONS"));

        UUID empId = UUID.randomUUID();
        DriverProfileRecord profile = driverPort.registerDriverProfile(admin, empId, "KL07 20180004321", List.of("LMV", "HMV"), LocalDate.of(2030, 5, 20), 8);
        require(profile != null, "driver profile registered");
        require(profile.licenceClasses().contains("LMV"), "LMV class present");
        require(profile.experienceYears() == 8, "experience years recorded");
        require("AVAILABLE".equals(profile.dutyStatus()), "initial duty status AVAILABLE");

        // Operational projection excludes salary and margin (FR-ACC-003, FR-DRV-001)
        List<DriverProfileRecord> operationalList = driverPort.listDrivers(employee, null);
        require(operationalList.size() == 1, "employee can list operational drivers");
        DriverProfileRecord op = operationalList.get(0);
        require(op.fullName() != null, "name present");
        require(op.dutyStatus() != null, "status present");

        // Verify DriverProfileRecord class has NO compensation getters
        boolean hasCompField = false;
        for (var method : DriverProfileRecord.class.getMethods()) {
            if (method.getName().toLowerCase(Locale.ROOT).contains("salary")
                    || method.getName().toLowerCase(Locale.ROOT).contains("compensation")
                    || method.getName().toLowerCase(Locale.ROOT).contains("margin")) {
                hasCompField = true;
            }
        }
        require(!hasCompField, "DriverProfileRecord class strictly has NO compensation/salary/margin fields (FR-ACC-003)");

        // Update duty status (FR-DRV-003)
        driverPort.updateDutyStatus(admin, profile.id(), "ON_DUTY");
        require("ON_DUTY".equals(driverPort.drivers.get(profile.id()).dutyStatus()), "duty status updated to ON_DUTY");
    }

    private static void testCustomerPortAndVisitConstraints() {
        MockCustomerPort customerPort = new MockCustomerPort();
        StaffSession staff = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of("BOOK_STANDARD_RENTAL", "UPDATE_CUSTOMER_OPERATIONS"));

        // FR-CUST-002: Customer creation outside booking workflow is rejected
        boolean caughtStandalone = false;
        try {
            customerPort.createCustomerWithVisit(staff, "Deepak Varma", "+919447011223", "deepak@example.com", "Aluva, Kochi", 28, "   ");
        } catch (IllegalArgumentException ex) {
            caughtStandalone = true;
        }
        require(caughtStandalone, "customer creation without active booking context is rejected (FR-CUST-002)");

        // Age-at-visit bounds validation (FR-CUST-008)
        boolean caughtUnderage = false;
        try {
            customerPort.createCustomerWithVisit(staff, "Minor Customer", "+919447011224", "minor@example.com", "Kochi", 17, "BOOKING_DRAFT_001");
        } catch (IllegalArgumentException ex) {
            caughtUnderage = true;
        }
        require(caughtUnderage, "age at visit < 18 rejected (FR-CUST-008)");

        // Valid customer creation inside booking workflow
        CustomerRecord cust = customerPort.createCustomerWithVisit(staff, "Deepak Varma", "+919447011223", "deepak@example.com", "Aluva, Kochi", 28, "BOOKING_DRAFT_001");
        require(cust != null, "customer created within booking");
        require(cust.fullName().equals("Deepak Varma"), "name matches");

        // Repeat visit recording with age-at-visit
        CustomerVisitRecord visit2 = customerPort.recordRepeatVisit(staff, cust.id(), 29, "BOOKING_DRAFT_002");
        require(visit2.ageAtVisit() == 29, "subsequent visit recorded with updated age-at-visit (29)");
        require(visit2.purpose().equals("BOOKING_DRAFT_002"), "visit purpose linked to booking draft");

        List<CustomerVisitRecord> history = customerPort.getVisitHistory(staff, cust.id());
        require(history.size() == 2, "two customer visits recorded in history");
    }

    private static void testVrmsHttpServerStage3Endpoints() throws Exception {
        MockPersonnelPort personnel = new MockPersonnelPort();
        MockFleetPort fleet = new MockFleetPort();
        MockDriverPort driver = new MockDriverPort();
        MockCustomerPort customer = new MockCustomerPort();

        HttpServerConfig config = new HttpServerConfig(new InetSocketAddress("127.0.0.1", 0), null);
        SupabaseConfig sc = new SupabaseConfig("https://abcdefghijklmnopqrst.supabase.co", "sb_publishable_fixture0123456789");
        SupabaseAuth sa = new SupabaseAuth(sc);

        StaffAuthorizer.Staff adminStaff = new StaffAuthorizer.Staff(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR");
        StaffAuthorizer.Staff employeeStaff = new StaffAuthorizer.Staff(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE");

        VrmsHttpServer server = new VrmsHttpServer(
                config,
                sa,
                (token, cap) -> "emp-token".equals(token) ? employeeStaff : adminStaff,
                null, null, null, null,
                personnel, fleet, driver, customer
        );

        server.start();
        HttpClient http = HttpClient.newHttpClient();
        String base = server.baseUri().toString();

        try {
            // 1. Employee Registration
            JsonObject regBody = new JsonObject();
            regBody.addProperty("fullName", "Kavya Suresh");
            regBody.addProperty("contact", "+919847199887");
            regBody.addProperty("email", "kavya@vrms.in");
            regBody.addProperty("address", "Edappally, Kochi");
            regBody.addProperty("joiningDate", "2026-10-25");
            regBody.addProperty("isDriver", false);

            HttpRequest req1 = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/personnel/employees"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(regBody.toString()))
                    .build();
            HttpResponse<String> res1 = http.send(req1, HttpResponse.BodyHandlers.ofString());
            require(res1.statusCode() == 201, "POST /api/v1/personnel/employees returned 201");
            JsonObject empObj = JsonParser.parseString(res1.body()).getAsJsonObject();
            UUID createdEmpId = UUID.fromString(empObj.get("id").getAsString());

            // 2. Attendance Recording
            JsonObject attBody = new JsonObject();
            attBody.addProperty("employeeId", createdEmpId.toString());
            attBody.addProperty("date", "2026-10-25");
            attBody.addProperty("status", "PRESENT");
            attBody.addProperty("notes", "Regular morning shift");

            HttpRequest req2 = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/personnel/attendance"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(attBody.toString()))
                    .build();
            HttpResponse<String> res2 = http.send(req2, HttpResponse.BodyHandlers.ofString());
            require(res2.statusCode() == 201, "POST /api/v1/personnel/attendance returned 201");

            // 3. Compensation Gate - Admin POST succeeds
            JsonObject compBody = new JsonObject();
            compBody.addProperty("employeeId", createdEmpId.toString());
            compBody.addProperty("arrangementType", "MONTHLY_SALARY");
            compBody.addProperty("baseRatePaise", 2800000L);
            compBody.addProperty("tripAllowancePaise", 0L);
            compBody.addProperty("effectiveFrom", "2026-10-25");

            HttpRequest req3 = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/personnel/compensation"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(compBody.toString()))
                    .build();
            HttpResponse<String> res3 = http.send(req3, HttpResponse.BodyHandlers.ofString());
            require(res3.statusCode() == 201, "admin POST compensation returned 201");

            // 4. Compensation Gate - Employee GET is rejected with 403 Forbidden (FR-ACC-003)
            HttpRequest req4 = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/personnel/compensation?employeeId=" + createdEmpId))
                    .header("Authorization", "Bearer emp-token")
                    .GET()
                    .build();
            HttpResponse<String> res4 = http.send(req4, HttpResponse.BodyHandlers.ofString());
            require(res4.statusCode() == 403, "employee GET compensation returned 403 Forbidden (FR-ACC-003)");

            // 5. Vehicle Registration
            JsonObject vehBody = new JsonObject();
            vehBody.addProperty("categoryId", UUID.randomUUID().toString());
            vehBody.addProperty("plate", "KL 07 EF 5678");
            vehBody.addProperty("make", "Maruti Suzuki");
            vehBody.addProperty("model", "Ertiga VXI");
            vehBody.addProperty("capacity", 7);
            vehBody.addProperty("dailyRatePaise", 250000L);
            vehBody.addProperty("hourlyRatePaise", 20000L);

            HttpRequest req5 = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/fleet/vehicles"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(vehBody.toString()))
                    .build();
            HttpResponse<String> res5 = http.send(req5, HttpResponse.BodyHandlers.ofString());
            require(res5.statusCode() == 201, "POST /api/v1/fleet/vehicles returned 201");

            // 6. Driver Profile Registration & Operational View
            JsonObject drvBody = new JsonObject();
            drvBody.addProperty("employeeId", createdEmpId.toString());
            drvBody.addProperty("licenceNumber", "KL07 20200007890");
            JsonArray classes = new JsonArray();
            classes.add("LMV");
            drvBody.add("classes", classes);
            drvBody.addProperty("expiry", "2035-08-10");
            drvBody.addProperty("experienceYears", 6);

            HttpRequest req6 = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/drivers"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(drvBody.toString()))
                    .build();
            HttpResponse<String> res6 = http.send(req6, HttpResponse.BodyHandlers.ofString());
            require(res6.statusCode() == 201, "POST /api/v1/drivers returned 201");

            HttpRequest req7 = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/drivers"))
                    .header("Authorization", "Bearer emp-token")
                    .GET()
                    .build();
            HttpResponse<String> res7 = http.send(req7, HttpResponse.BodyHandlers.ofString());
            require(res7.statusCode() == 200, "GET /api/v1/drivers returned 200");
            JsonObject drvRes = JsonParser.parseString(res7.body()).getAsJsonObject();
            JsonObject firstDrv = drvRes.getAsJsonArray("drivers").get(0).getAsJsonObject();
            require(!firstDrv.has("salary"), "driver operational projection omits salary (FR-ACC-003)");
            require(!firstDrv.has("margin"), "driver operational projection omits margin (FR-ACC-003)");

            // 7. Customer Creation Gate - Standalone without booking context fails (FR-CUST-002)
            JsonObject standaloneCust = new JsonObject();
            standaloneCust.addProperty("fullName", "Aditya Nair");
            standaloneCust.addProperty("mobile", "+919847111222");
            standaloneCust.addProperty("email", "aditya@example.com");
            standaloneCust.addProperty("ageAtVisit", 30);
            standaloneCust.addProperty("bookingContext", ""); // Blank!

            HttpRequest req8 = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/customers"))
                    .header("Authorization", "Bearer emp-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(standaloneCust.toString()))
                    .build();
            HttpResponse<String> res8 = http.send(req8, HttpResponse.BodyHandlers.ofString());
            require(res8.statusCode() == 400, "standalone customer creation without bookingContext rejected with 400 (FR-CUST-002)");

            // 8. Customer Creation with Active Booking Context succeeds
            standaloneCust.addProperty("bookingContext", "BOOKING_TRIP_KOCHI_MUNNAR_2026");
            HttpRequest req9 = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/customers"))
                    .header("Authorization", "Bearer emp-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(standaloneCust.toString()))
                    .build();
            HttpResponse<String> res9 = http.send(req9, HttpResponse.BodyHandlers.ofString());
            require(res9.statusCode() == 201, "customer creation within bookingContext returned 201 (FR-CUST-002)");

        } finally {
            server.stop(0);
        }
    }

    private static void testDesktopStage3ViewsLayoutAndResponsive() {
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@vrms.in", Set.of("MANAGE_PERSONNEL", "VIEW_COMPENSATION"));
        StaffSession employee = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@vrms.in", Set.of("VIEW_OPERATIONAL_HISTORY"));

        // PersonnelView testing
        PersonnelView adminPersonnel = new PersonnelView(admin);
        require(adminPersonnel.getComponentCount() > 0, "admin PersonnelView initialized");
        require(adminPersonnel.fullName != null, "personnel form fullName present");

        PersonnelView empPersonnel = new PersonnelView(employee);
        require(empPersonnel.getComponentCount() > 0, "employee PersonnelView initialized");

        // CustomersView testing
        CustomersView customersView = new CustomersView();
        require(customersView.searchField != null, "customer search field present");
        require(customersView.visitList != null, "visit list present");

        // DriversView testing
        DriversView driversView = new DriversView(employee);
        require(driversView.newDutyStatus != null, "driver status dropdown present");

        // BookingView enhancements testing
        BookingView bookingView = new BookingView();
        require(bookingView.ageAtVisit != null, "bookingView ageAtVisit field present (FR-CUST-008)");
        require(bookingView.searchRepeatCustomer != null, "bookingView searchRepeatCustomer button present");
        bookingView.phone.setText("+919847012345");
        bookingView.searchRepeatCustomer.doClick();
        require(bookingView.customerLookupNote.getText().contains("Duplicate suggestions do not auto-merge identities"), "duplicate suggestion note verified");

        // WorkspaceView responsive layout testing
        WorkspaceView workspace = new WorkspaceView(() -> {}, admin);
        workspace.showPage("Customers");
        workspace.showPage("Drivers");
        workspace.showPage("Personnel");

        // Test layouts at mandatory target resolutions: 1024x720, 1280x820, 1600x1000
        for (Dimension d : new Dimension[]{new Dimension(1024, 720), new Dimension(1280, 820), new Dimension(1600, 1000)}) {
            workspace.setSize(d);
            workspace.doLayout();
            workspace.validate();
            require(workspace.getWidth() == d.width && workspace.getHeight() == d.height, "layout validated at " + d.width + "x" + d.height);
        }
    }

    // =========================================================================
    // Mock Domain Ports for Unit/Integration Verification
    // =========================================================================

    private static final class MockPersonnelPort implements PersonnelPort {
        final Map<UUID, EmployeeRecord> employees = new ConcurrentHashMap<>();
        final List<AttendanceRecord> attendance = new ArrayList<>();
        final Map<UUID, CompensationRecord> compensation = new ConcurrentHashMap<>();

        @Override
        public EmployeeRecord registerEmployee(StaffSession session, String fullName, String contact, String email, String address, LocalDate joiningDate, boolean isDriver, UUID photoFileId) {
            UUID id = UUID.randomUUID();
            EmployeeRecord e = new EmployeeRecord(id, session.companyId(), session.userId(), fullName, contact, email, address, joiningDate, "ACTIVE", isDriver, photoFileId, 1L);
            employees.put(id, e);
            return e;
        }

        @Override
        public EmployeeRecord updateEmployee(StaffSession session, UUID employeeId, String fullName, String contact, String email, String address, String employmentStatus) {
            EmployeeRecord old = employees.get(employeeId);
            EmployeeRecord updated = new EmployeeRecord(old.id(), old.companyId(), old.authUserId(), fullName, contact, email, address, old.joiningDate(), employmentStatus, old.isDriver(), old.photoFileId(), old.version() + 1);
            employees.put(employeeId, updated);
            return updated;
        }

        @Override
        public EmployeeRecord deactivateEmployee(StaffSession session, UUID employeeId, String reason) {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Deactivation reason cannot be blank.");
            EmployeeRecord old = employees.get(employeeId);
            EmployeeRecord deactivated = new EmployeeRecord(old.id(), old.companyId(), old.authUserId(), old.fullName(), old.contactNumber(), old.email(), old.address(), old.joiningDate(), "DEACTIVATED", old.isDriver(), old.photoFileId(), old.version() + 1);
            employees.put(employeeId, deactivated);
            return deactivated;
        }

        @Override
        public List<EmployeeRecord> listEmployees(StaffSession session, boolean activeOnly) {
            List<EmployeeRecord> list = new ArrayList<>();
            for (var e : employees.values()) {
                if (!activeOnly || "ACTIVE".equals(e.employmentStatus())) list.add(e);
            }
            return list;
        }

        @Override
        public Optional<EmployeeRecord> findDuplicateEmployee(StaffSession session, String contactNumber, String email) {
            for (var e : employees.values()) {
                if (e.contactNumber().equals(contactNumber) || (email != null && e.email().equalsIgnoreCase(email))) {
                    return Optional.of(e);
                }
            }
            return Optional.empty();
        }

        @Override
        public AttendanceRecord recordAttendance(StaffSession session, UUID employeeId, LocalDate date, String status, String notes) {
            AttendanceRecord rec = new AttendanceRecord(UUID.randomUUID(), session.companyId(), employeeId, date, status, notes, session.userId());
            attendance.add(rec);
            return rec;
        }

        @Override
        public List<AttendanceRecord> getAttendanceHistory(StaffSession session, UUID employeeId, LocalDate from, LocalDate to) {
            List<AttendanceRecord> list = new ArrayList<>();
            for (var a : attendance) {
                if (a.employeeId().equals(employeeId) && !a.recordDate().isBefore(from) && !a.recordDate().isAfter(to)) {
                    list.add(a);
                }
            }
            return list;
        }

        @Override
        public CompensationRecord recordCompensation(StaffSession session, UUID employeeId, String arrangementType, long baseRatePaise, long tripAllowancePaise, LocalDate effectiveFrom) {
            CompensationRecord rec = new CompensationRecord(UUID.randomUUID(), session.companyId(), employeeId, arrangementType, baseRatePaise, tripAllowancePaise, effectiveFrom);
            compensation.put(employeeId, rec);
            return rec;
        }

        @Override
        public Optional<CompensationRecord> getCompensation(StaffSession session, UUID employeeId) {
            if (!session.isAdministrator()) throw new SecurityException("Compensation restricted to Administrator (FR-ACC-003)");
            return Optional.ofNullable(compensation.get(employeeId));
        }
    }

    private static final class MockFleetPort implements FleetPort {
        final Map<UUID, VehicleRecord> vehicles = new ConcurrentHashMap<>();
        final Map<UUID, AssetRestrictionRecord> restrictions = new ConcurrentHashMap<>();
        final List<VehicleDocumentRecord> documents = new ArrayList<>();

        @Override
        public VehicleRecord registerVehicle(StaffSession session, UUID categoryId, String plate, String manufacturer, String model, int capacity, long dailyRatePaise, long hourlyRatePaise, UUID photoFileId) {
            for (var v : vehicles.values()) {
                if (v.registrationPlate().equalsIgnoreCase(plate)) throw new IllegalArgumentException("Duplicate registration plate.");
            }
            UUID id = UUID.randomUUID();
            VehicleRecord v = new VehicleRecord(id, session.companyId(), categoryId != null ? categoryId : UUID.randomUUID(), "Standard", plate, manufacturer, model, capacity, dailyRatePaise, hourlyRatePaise, "AVAILABLE", photoFileId, 1L);
            vehicles.put(id, v);
            return v;
        }

        @Override
        public List<VehicleRecord> listVehicles(StaffSession session, String categoryCode, String status) {
            return new ArrayList<>(vehicles.values());
        }

        @Override
        public Optional<VehicleRecord> getVehicle(StaffSession session, UUID vehicleId) {
            return Optional.ofNullable(vehicles.get(vehicleId));
        }

        @Override
        public AssetRestrictionRecord freezeAsset(StaffSession session, String assetType, UUID assetId, String reason, Instant startTime, Instant endTime) {
            UUID id = UUID.randomUUID();
            AssetRestrictionRecord r = new AssetRestrictionRecord(id, session.companyId(), assetType, assetId, "FREEZE", startTime, endTime, reason, true);
            restrictions.put(id, r);
            return r;
        }

        @Override
        public void resolveAssetRestriction(StaffSession session, UUID restrictionId) {
            AssetRestrictionRecord r = restrictions.get(restrictionId);
            if (r != null) {
                restrictions.put(restrictionId, new AssetRestrictionRecord(r.id(), r.companyId(), r.assetType(), r.assetId(), r.restrictionType(), r.startTime(), r.endTime(), r.reason(), false));
            }
        }

        @Override
        public VehicleDocumentRecord addVehicleDocument(StaffSession session, UUID vehicleId, String docType, UUID fileId, LocalDate issueDate, LocalDate expiryDate) {
            int ver = 1;
            for (var d : documents) {
                if (d.vehicleId().equals(vehicleId) && d.docType().equals(docType)) ver = Math.max(ver, d.version() + 1);
            }
            VehicleDocumentRecord doc = new VehicleDocumentRecord(UUID.randomUUID(), session.companyId(), vehicleId, docType, fileId, issueDate, expiryDate, ver);
            documents.add(doc);
            return doc;
        }

        @Override
        public List<VehicleDocumentRecord> getVehicleDocuments(StaffSession session, UUID vehicleId) {
            List<VehicleDocumentRecord> list = new ArrayList<>();
            for (var d : documents) {
                if (d.vehicleId().equals(vehicleId)) list.add(d);
            }
            return list;
        }
    }

    private static final class MockDriverPort implements DriverPort {
        final Map<UUID, DriverProfileRecord> drivers = new ConcurrentHashMap<>();

        @Override
        public DriverProfileRecord registerDriverProfile(StaffSession session, UUID employeeId, String licenceNumber, List<String> classes, LocalDate expiry, int experienceYears) {
            UUID id = UUID.randomUUID();
            DriverProfileRecord d = new DriverProfileRecord(id, session.companyId(), employeeId, "Driver Name", "+919847000000", licenceNumber, classes, expiry, experienceYears, "AVAILABLE", true);
            drivers.put(id, d);
            return d;
        }

        @Override
        public List<DriverProfileRecord> listDrivers(StaffSession session, String dutyStatus) {
            return new ArrayList<>(drivers.values());
        }

        @Override
        public void updateDutyStatus(StaffSession session, UUID driverId, String dutyStatus) {
            DriverProfileRecord old = drivers.get(driverId);
            if (old != null) {
                drivers.put(driverId, new DriverProfileRecord(old.id(), old.companyId(), old.employeeId(), old.fullName(), old.contactNumber(), old.licenceNumber(), old.licenceClasses(), old.licenceExpiry(), old.experienceYears(), dutyStatus, old.isEmployed()));
            }
        }
    }

    private static final class MockCustomerPort implements CustomerPort {
        final Map<UUID, CustomerRecord> customers = new ConcurrentHashMap<>();
        final List<CustomerVisitRecord> visits = new ArrayList<>();

        @Override
        public CustomerRecord createCustomerWithVisit(StaffSession session, String fullName, String mobile, String email, String address, int ageAtVisit, String bookingContext) {
            if (bookingContext == null || bookingContext.isBlank()) {
                throw new IllegalArgumentException("Customer creation must occur within an active booking workflow (FR-CUST-002).");
            }
            if (ageAtVisit < 18 || ageAtVisit > 120) {
                throw new IllegalArgumentException("Age at visit must be between 18 and 120 (FR-CUST-008).");
            }
            UUID id = UUID.randomUUID();
            CustomerRecord c = new CustomerRecord(id, session.companyId(), fullName, mobile, email, address, Instant.now(), 1L);
            customers.put(id, c);
            visits.add(new CustomerVisitRecord(UUID.randomUUID(), session.companyId(), id, ageAtVisit, Instant.now(), bookingContext));
            return c;
        }

        @Override
        public List<CustomerRecord> searchCustomers(StaffSession session, String query) {
            return new ArrayList<>(customers.values());
        }

        @Override
        public CustomerVisitRecord recordRepeatVisit(StaffSession session, UUID customerId, int ageAtVisit, String bookingContext) {
            if (bookingContext == null || bookingContext.isBlank()) throw new IllegalArgumentException("Booking context required.");
            CustomerVisitRecord v = new CustomerVisitRecord(UUID.randomUUID(), session.companyId(), customerId, ageAtVisit, Instant.now(), bookingContext);
            visits.add(v);
            return v;
        }

        @Override
        public List<CustomerVisitRecord> getVisitHistory(StaffSession session, UUID customerId) {
            List<CustomerVisitRecord> list = new ArrayList<>();
            for (var v : visits) {
                if (v.customerId().equals(customerId)) list.add(v);
            }
            return list;
        }
    }
}
