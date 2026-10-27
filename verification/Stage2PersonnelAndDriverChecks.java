package in.smartvrms.server.security;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.contracts.AttendanceRecord;
import in.smartvrms.contracts.CompensationRecord;
import in.smartvrms.contracts.DriverProfileRecord;
import in.smartvrms.contracts.EmployeeRecord;
import in.smartvrms.contracts.MemberManagementRecord;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.domain.JdbcDriverService;
import in.smartvrms.server.domain.JdbcPersonnelService;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Dedicated Stage 2 verification suite covering:
 * - FR-ACC-001, FR-ACC-002, FR-ACC-003: Role workspaces, trusted auth, separation of salary/margin from operational driver views.
 * - FR-EMP-001..008: Employee identity, lifecycle, attendance/leave calendar, access distinction, reassignment/soft-deactivation, compensation tracking without invented statutory policy, duplicate suggestions.
 * - FR-DRV-001..004: Driver operational profiles (no salary), eligibility, shift duty status distinct from vehicle availability, assignment periods.
 * - Staff provisioning: Supabase Auth Admin REST API invitation fail-closed gate when unconfigured (HTTP 503), admin revocation and handover boundaries.
 */
public final class Stage2PersonnelAndDriverChecks {
    private static int checks;

    private static final String DUMMY_URL = "https://abcdefghijklmnopqrst.supabase.co";
    private static final String DUMMY_KEY = "sb_publishable_fixture0123456789";

    private static final UUID ADMIN_USER_ID = UUID.fromString("11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID EMPLOYEE_USER_ID = UUID.fromString("22222222-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TARGET_USER_ID = UUID.fromString("33333333-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OTHER_ADMIN_ID = UUID.fromString("44444444-dddd-dddd-dddd-dddddddddddd");
    private static final UUID COMPANY_ID = UUID.fromString("55555555-eeee-eeee-eeee-eeeeeeeeeeee");

    private static final StaffSession ADMIN_SESSION = new StaffSession(
            ADMIN_USER_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in",
            Set.of(Capability.MANAGE_PERSONNEL.name(), Capability.VIEW_COMPENSATION.name(),
                    Capability.MANAGE_ATTENDANCE.name(), Capability.MANAGE_DRIVER_MASTER.name(),
                    Capability.VIEW_DRIVER_OPERATIONS.name(), Capability.REQUEST_DRIVER_UNAVAILABILITY.name(),
                    Capability.INVITE_EMPLOYEE.name(), Capability.REVOKE_MEMBERSHIP.name())
    );

    private static final StaffSession EMPLOYEE_SESSION = new StaffSession(
            EMPLOYEE_USER_ID, COMPANY_ID, "EMPLOYEE", "employee@smartvrms.in",
            Set.of(Capability.VIEW_DRIVER_OPERATIONS.name(), Capability.REQUEST_DRIVER_UNAVAILABILITY.name(),
                    Capability.VIEW_OPERATIONAL_HISTORY.name())
    );

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("FAIL: " + message);
        checks++;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 2 personnel, driver, and provisioning verification checks...");

        testEmployeeRegistrationValidation();
        testDuplicateEmployeeDetection();
        testSoftDeactivationPreservesHistory();
        testAttendanceAndLeaveRecording();
        testCompensationTrackingAndRoleIsolation();
        testDriverOperationalProfileAndSalaryExclusion();
        testDriverDutyStatusTransitions();
        testStaffProvisioningFailClosedAndAdminBoundaries();
        testHttpEndpointRoleBoundaries();

        System.out.println("PASS: " + checks + " Stage 2 personnel, driver, and provisioning assertions.");
    }

    // 1. Employee Registration Validation
    private static void testEmployeeRegistrationValidation() {
        DataSource ds = createMockDataSource(new MockDbState());
        JdbcPersonnelService service = new JdbcPersonnelService(ds, null);

        // A. Non-admin is denied
        try {
            service.registerEmployee(EMPLOYEE_SESSION, "Ramesh", "9876543210", "r@vrms.in", "Kochi", LocalDate.now(), false, null);
            throw new AssertionError("Employee role must not register staff");
        } catch (SecurityException ex) {
            require(ex.getMessage().contains("Only administrators"), "Non-admin rejected from employee registration");
        }

        // B. Blank full name is rejected
        try {
            service.registerEmployee(ADMIN_SESSION, "   ", "9876543210", "r@vrms.in", "Kochi", LocalDate.now(), false, null);
            throw new AssertionError("Blank name must be rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Full name is required"), "Blank name rejected");
        }

        // C. Contact number length bounds
        try {
            service.registerEmployee(ADMIN_SESSION, "Ramesh", "123", "r@vrms.in", "Kochi", LocalDate.now(), false, null);
            throw new AssertionError("Short contact must be rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Valid contact number"), "Short contact rejected");
        }

        // D. Invalid email format
        try {
            service.registerEmployee(ADMIN_SESSION, "Ramesh", "9876543210", "not-an-email", "Kochi", LocalDate.now(), false, null);
            throw new AssertionError("Invalid email must be rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Invalid email format"), "Malformed email rejected");
        }

        // E. Missing address
        try {
            service.registerEmployee(ADMIN_SESSION, "Ramesh", "9876543210", "r@vrms.in", "", LocalDate.now(), false, null);
            throw new AssertionError("Blank address must be rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Address is required"), "Blank address rejected");
        }

        // F. Missing joining date
        try {
            service.registerEmployee(ADMIN_SESSION, "Ramesh", "9876543210", "r@vrms.in", "Kochi", null, false, null);
            throw new AssertionError("Null joining date must be rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Joining date is required"), "Null joining date rejected");
        }

        // G. Valid registration succeeds
        EmployeeRecord rec = service.registerEmployee(ADMIN_SESSION, "Ramesh Kumar", "9876543210", "ramesh@vrms.in", "Kochi, Kerala", LocalDate.of(2026, 1, 15), true, null);
        require(rec != null, "Employee registered");
        require("Ramesh Kumar".equals(rec.fullName()), "Full name preserved");
        require("ACTIVE".equals(rec.employmentStatus()), "Default status is ACTIVE");
        require(rec.isDriver(), "Driver flag preserved");
    }

    // 2. Duplicate Employee Suggestions (FR-EMP-008)
    private static void testDuplicateEmployeeDetection() {
        MockDbState state = new MockDbState();
        UUID existingId = UUID.randomUUID();
        state.employees.put(existingId, new MockEmployee(existingId, COMPANY_ID, "Deepak Varma", "9847012345", "deepak@vrms.in", "Kochi", LocalDate.of(2025, 5, 1), "ACTIVE", true));

        DataSource ds = createMockDataSource(state);
        JdbcPersonnelService service = new JdbcPersonnelService(ds, null);

        // A. Match by phone
        Optional<EmployeeRecord> dupPhone = service.findDuplicateEmployee(ADMIN_SESSION, "9847012345", "other@vrms.in");
        require(dupPhone.isPresent(), "Duplicate found by contact number");
        require("Deepak Varma".equals(dupPhone.get().fullName()), "Correct duplicate record returned");

        // B. Match by email
        Optional<EmployeeRecord> dupEmail = service.findDuplicateEmployee(ADMIN_SESSION, "9999999999", "deepak@vrms.in");
        require(dupEmail.isPresent(), "Duplicate found by email");

        // C. Clean empty inputs return empty (no false positive matches)
        Optional<EmployeeRecord> emptySearch = service.findDuplicateEmployee(ADMIN_SESSION, "", "");
        require(emptySearch.isEmpty(), "Blank inputs return empty duplicate match");

        // D. Non-matching contact & email returns empty
        Optional<EmployeeRecord> noMatch = service.findDuplicateEmployee(ADMIN_SESSION, "9111111111", "fresh@vrms.in");
        require(noMatch.isEmpty(), "Non-matching credentials return empty");
    }

    // 3. Soft-Deactivation with Mandatory Reason (FR-EMP-002, FR-EMP-005)
    private static void testSoftDeactivationPreservesHistory() {
        MockDbState state = new MockDbState();
        UUID empId = UUID.randomUUID();
        state.employees.put(empId, new MockEmployee(empId, COMPANY_ID, "Suresh G", "9447012345", "suresh@vrms.in", "Thrissur", LocalDate.of(2024, 3, 1), "ACTIVE", false));

        DataSource ds = createMockDataSource(state);
        JdbcPersonnelService service = new JdbcPersonnelService(ds, null);

        // A. Missing reason rejected
        try {
            service.deactivateEmployee(ADMIN_SESSION, empId, "   ");
            throw new AssertionError("Blank reason must be rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Mandatory reason"), "Deactivation without reason rejected");
        }

        // B. Non-admin cannot deactivate
        try {
            service.deactivateEmployee(EMPLOYEE_SESSION, empId, "Staff resigned");
            throw new AssertionError("Employee cannot deactivate staff");
        } catch (SecurityException ex) {
            require(ex.getMessage().contains("Only administrators"), "Non-admin deactivation rejected");
        }

        // C. Valid deactivation succeeds
        EmployeeRecord deactivated = service.deactivateEmployee(ADMIN_SESSION, empId, "Staff resigned to pursue higher studies");
        require("DEACTIVATED".equals(deactivated.employmentStatus()), "Status updated to DEACTIVATED");
        require(deactivated.version() > 1, "Record version incremented");

        // Historical references remain intact in DB
        MockEmployee stored = state.employees.get(empId);
        require(stored != null, "Historical employee record is never deleted");
        require("DEACTIVATED".equals(stored.status), "Stored employee marked DEACTIVATED");
    }

    // 4. Attendance and Leave Calendar (FR-EMP-003)
    private static void testAttendanceAndLeaveRecording() {
        MockDbState state = new MockDbState();
        UUID empId = UUID.randomUUID();
        DataSource ds = createMockDataSource(state);
        JdbcPersonnelService service = new JdbcPersonnelService(ds, null);

        // A. Invalid attendance status rejected
        try {
            service.recordAttendance(ADMIN_SESSION, empId, LocalDate.now(), "VACATION", "On trip");
            throw new AssertionError("Invalid status must be rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Invalid attendance status"), "Invalid status rejected");
        }

        // B. Valid statuses succeed: PRESENT, ABSENT, PARTIAL_DAY, ON_LEAVE
        for (String status : List.of("PRESENT", "ABSENT", "PARTIAL_DAY", "ON_LEAVE")) {
            AttendanceRecord rec = service.recordAttendance(ADMIN_SESSION, empId, LocalDate.of(2026, 10, 20), status, "Status: " + status);
            require(rec != null, "Attendance recorded for status " + status);
            require(status.equals(rec.status()), "Recorded status matches " + status);
        }

        // C. Retrieve history within date range
        List<AttendanceRecord> history = service.getAttendanceHistory(ADMIN_SESSION, empId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        require(history != null, "Attendance history retrieved");
    }

    // 5. Compensation Tracking & Role Isolation (FR-ACC-003, FR-EMP-006, FR-EMP-007)
    private static void testCompensationTrackingAndRoleIsolation() {
        MockDbState state = new MockDbState();
        UUID empId = UUID.randomUUID();
        DataSource ds = createMockDataSource(state);
        JdbcPersonnelService service = new JdbcPersonnelService(ds, null);

        // A. Non-admin Employee cannot record compensation
        try {
            service.recordCompensation(EMPLOYEE_SESSION, empId, "FIXED_MONTHLY", 3000000L, 0L, LocalDate.now());
            throw new AssertionError("Employee cannot record compensation");
        } catch (SecurityException ex) {
            require(ex.getMessage().contains("Only administrators"), "Employee blocked from recording compensation");
        }

        // B. Non-admin Employee cannot view compensation (returns empty, zero leak)
        Optional<CompensationRecord> hidden = service.getCompensation(EMPLOYEE_SESSION, empId);
        require(hidden.isEmpty(), "Employee role receives Optional.empty() for compensation");

        // C. Invalid arrangement type rejected
        try {
            service.recordCompensation(ADMIN_SESSION, empId, "ARBITRARY_PAY", 3000000L, 0L, LocalDate.now());
            throw new AssertionError("Invalid arrangement type rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Invalid arrangement type"), "Invalid arrangement rejected");
        }

        // D. Negative amounts rejected
        try {
            service.recordCompensation(ADMIN_SESSION, empId, "FIXED_MONTHLY", -5000L, 0L, LocalDate.now());
            throw new AssertionError("Negative rate must be rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("cannot be negative"), "Negative rate rejected");
        }

        // E. Valid compensation succeeds for all 4 approved arrangement types
        for (String type : List.of("FIXED_MONTHLY", "DAILY_WAGE", "PER_TRIP", "MIXED")) {
            CompensationRecord comp = service.recordCompensation(ADMIN_SESSION, empId, type, 2500000L, 50000L, LocalDate.of(2026, 1, 1));
            require(comp != null, "Compensation recorded for " + type);
            require(type.equals(comp.arrangementType()), "Arrangement type preserved: " + type);
        }

        // F. Admin can retrieve active terms
        Optional<CompensationRecord> retrieved = service.getCompensation(ADMIN_SESSION, empId);
        require(retrieved.isPresent(), "Administrator successfully views compensation terms");
        require(retrieved.get().baseRatePaise() == 2500000L, "Base rate paise accurate");
    }

    // 6. Driver Operational Profile & Privacy (FR-ACC-003, FR-DRV-001, FR-DRV-002)
    private static void testDriverOperationalProfileAndSalaryExclusion() {
        MockDbState state = new MockDbState();
        UUID empId = UUID.randomUUID();
        state.employees.put(empId, new MockEmployee(empId, COMPANY_ID, "Manoj George", "9745012345", "manoj@vrms.in", "Kollam", LocalDate.of(2025, 2, 1), "ACTIVE", true));

        DataSource ds = createMockDataSource(state);
        JdbcDriverService driverService = new JdbcDriverService(ds, null);

        // A. Licence length bounds
        try {
            driverService.registerDriverProfile(ADMIN_SESSION, empId, "KL", List.of("LMV"), LocalDate.of(2028, 1, 1), 5);
            throw new AssertionError("Short licence rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Licence number must be between"), "Short licence rejected");
        }

        // B. Negative experience rejected
        try {
            driverService.registerDriverProfile(ADMIN_SESSION, empId, "KL-07-20200001234", List.of("LMV"), LocalDate.of(2028, 1, 1), -1);
            throw new AssertionError("Negative experience rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("cannot be negative"), "Negative experience rejected");
        }

        // C. Non-existent employee rejected
        try {
            driverService.registerDriverProfile(ADMIN_SESSION, UUID.randomUUID(), "KL-07-20200001234", List.of("LMV"), LocalDate.of(2028, 1, 1), 5);
            throw new AssertionError("Non-existent employee rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Employee not found in company"), "Unknown employee rejected");
        }

        // D. Valid driver registration succeeds with real name and contact
        DriverProfileRecord profile = driverService.registerDriverProfile(ADMIN_SESSION, empId, "KL-07-20200001234", List.of("LMV", "HMV"), LocalDate.of(2029, 6, 30), 8);
        require(profile != null, "Driver registered");
        require("Manoj George".equals(profile.fullName()), "Real employee full name linked");
        require("9745012345".equals(profile.contactNumber()), "Real contact linked");
        require("AVAILABLE".equals(profile.dutyStatus()), "Default duty status is AVAILABLE");
        require(profile.isEmployed(), "Employee active status reflected in isEmployed");
    }

    // 7. Driver Duty Status Transitions (FR-DRV-003, FR-DRV-004)
    private static void testDriverDutyStatusTransitions() {
        MockDbState state = new MockDbState();
        UUID driverId = UUID.randomUUID();
        state.drivers.put(driverId, new MockDriver(driverId, COMPANY_ID, "KL-01-20220009999", "AVAILABLE"));

        DataSource ds = createMockDataSource(state);
        JdbcDriverService driverService = new JdbcDriverService(ds, null);

        // A. Invalid duty status rejected
        try {
            driverService.updateDutyStatus(ADMIN_SESSION, driverId, "HOLIDAY");
            throw new AssertionError("Invalid duty status rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Invalid duty status"), "Invalid duty status rejected");
        }

        // B. Non-existent driver rejected
        try {
            driverService.updateDutyStatus(ADMIN_SESSION, UUID.randomUUID(), "ASSIGNED");
            throw new AssertionError("Non-existent driver rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("Driver profile not found"), "Unknown driver rejected");
        }

        // C. Valid transitions succeed: AVAILABLE, ASSIGNED, ON_LEAVE, UNAVAILABLE
        for (String status : List.of("ASSIGNED", "ON_LEAVE", "UNAVAILABLE", "AVAILABLE")) {
            driverService.updateDutyStatus(ADMIN_SESSION, driverId, status);
            require(status.equals(state.drivers.get(driverId).dutyStatus), "Duty status transitioned to " + status);
        }
    }

    // 8. Staff Provisioning Boundaries and Fail-Closed Setup Gate (FR-AUTH-002, FR-EMP-004)
    private static void testStaffProvisioningFailClosedAndAdminBoundaries() {
        MockDbState state = new MockDbState();
        state.companies.put(COMPANY_ID, ADMIN_USER_ID); // Primary admin is ADMIN_USER_ID

        // Case A: Unconfigured secret -> fails closed with honest unavailable state
        DataSource ds = createMockDataSource(state);
        JdbcStaffProvisioningService unconfigured = new JdbcStaffProvisioningService(ds);
        try {
            unconfigured.inviteEmployee(ADMIN_SESSION, "newstaff@vrms.in", "New Staff");
            throw new AssertionError("Unconfigured invite provider must fail closed");
        } catch (IllegalStateException ex) {
            require(ex.getMessage().contains("Employee invitation service unavailable"), "Unconfigured invite fails closed with 503-compatible message");
        }

        // Case B: Configured secret with invite provider -> succeeds
        UUID invitedAuthId = UUID.randomUUID();
        JdbcStaffProvisioningService configured = new JdbcStaffProvisioningService(ds, (email, name) -> invitedAuthId);

        MemberManagementRecord invited = configured.inviteEmployee(ADMIN_SESSION, "newstaff@vrms.in", "New Staff");
        require(invited != null, "Staff invitation succeeded");
        require(invitedAuthId.equals(invited.authUserId()), "Invited user ID matches");
        require("EMPLOYEE".equals(invited.role()), "Assigned role is EMPLOYEE");
        require(invited.active(), "Invited membership is active");

        // Case C: Non-admin cannot invite
        try {
            configured.inviteEmployee(EMPLOYEE_SESSION, "other@vrms.in", "Other");
            throw new AssertionError("Employee cannot invite staff");
        } catch (SecurityException ex) {
            require(ex.getMessage().contains("Only administrators"), "Employee blocked from inviting staff");
        }

        // Case D: Cannot revoke primary administrator
        try {
            configured.revokeMembership(ADMIN_SESSION, ADMIN_USER_ID, "Attempt revoke primary admin");
            throw new AssertionError("Cannot revoke primary admin");
        } catch (IllegalStateException ex) {
            require(ex.getMessage().contains("Cannot revoke the primary administrator"), "Primary admin revocation prevented");
        }

        // Case E: Handover primary administrator requires active administrator target
        try {
            configured.handoverPrimaryAdministrator(ADMIN_SESSION, EMPLOYEE_USER_ID);
            throw new AssertionError("Cannot handover to non-admin");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("not an active administrator"), "Handover to non-admin rejected");
        }

        // Self-handover rejected
        try {
            configured.handoverPrimaryAdministrator(ADMIN_SESSION, ADMIN_USER_ID);
            throw new AssertionError("Self-handover rejected");
        } catch (IllegalArgumentException ex) {
            require(ex.getMessage().contains("oneself"), "Self-handover rejected");
        }

        // Valid handover to another active administrator succeeds
        state.memberships.put(OTHER_ADMIN_ID, new MockMembership(OTHER_ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", true));
        configured.handoverPrimaryAdministrator(ADMIN_SESSION, OTHER_ADMIN_ID);
        require(OTHER_ADMIN_ID.equals(state.companies.get(COMPANY_ID)), "Company primary admin updated to new admin");
    }

    // 9. HTTP Endpoint Role Boundaries and Schema Privacy (FR-ACC-001, FR-ACC-003)
    private static void testHttpEndpointRoleBoundaries() throws Exception {
        MockDbState state = new MockDbState();
        UUID driverId = UUID.randomUUID();
        UUID empId = UUID.randomUUID();
        state.employees.put(empId, new MockEmployee(empId, COMPANY_ID, "Kiran Kumar", "9446012345", "kiran@vrms.in", "Ernakulam", LocalDate.of(2025, 1, 1), "ACTIVE", true));
        state.drivers.put(driverId, new MockDriver(driverId, COMPANY_ID, "KL-07-20230005555", "AVAILABLE"));

        DataSource ds = createMockDataSource(state);
        JdbcPersonnelService personnel = new JdbcPersonnelService(ds, null);
        JdbcDriverService driver = new JdbcDriverService(ds, null);
        JdbcStaffProvisioningService provisioning = new JdbcStaffProvisioningService(ds);

        SupabaseConfig config = new SupabaseConfig(DUMMY_URL, DUMMY_KEY);
        SupabaseAuth auth = new SupabaseAuth(config, r -> new SupabaseAuth.Response(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8)), FIXED_CLOCK);

        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            if ("admin-token".equals(token)) {
                return new StaffAuthorizer.Staff(ADMIN_USER_ID, COMPANY_ID, "ADMINISTRATOR");
            } else if ("employee-token".equals(token)) {
                if (cap.administratorOnly()) throw new SecurityException("Forbidden");
                return new StaffAuthorizer.Staff(EMPLOYEE_USER_ID, COMPANY_ID, "EMPLOYEE");
            }
            throw new SecurityException("Unauthorized");
        };

        HttpServerConfig serverConfig = new HttpServerConfig(0);
        VrmsHttpServer server = new VrmsHttpServer(serverConfig, auth, authorizer,
                null, null, null, provisioning, personnel, null, driver, null);
        server.start();

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            URI base = server.baseUri();

            // A. Employee accessing compensation route -> 403 Forbidden
            HttpRequest empCompReq = HttpRequest.newBuilder(base.resolve("/api/v1/personnel/compensation?employeeId=" + empId))
                    .header("Authorization", "Bearer employee-token")
                    .GET().build();
            HttpResponse<String> empCompRes = client.send(empCompReq, HttpResponse.BodyHandlers.ofString());
            require(empCompRes.statusCode() == 403, "Employee blocked from compensation route (HTTP 403)");

            // B. Employee attempting staff invitation -> 403 Forbidden
            HttpRequest empInviteReq = HttpRequest.newBuilder(base.resolve("/api/v1/admin/members/invite"))
                    .header("Authorization", "Bearer employee-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"bad@vrms.in\"}"))
                    .build();
            HttpResponse<String> empInviteRes = client.send(empInviteReq, HttpResponse.BodyHandlers.ofString());
            require(empInviteRes.statusCode() == 403, "Employee blocked from admin invite route (HTTP 403)");

            // C. Admin attempting staff invite when service-role secret is unconfigured -> 503 Service Unavailable
            HttpRequest adminInviteReq = HttpRequest.newBuilder(base.resolve("/api/v1/admin/members/invite"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"newstaff@vrms.in\",\"fullName\":\"Staff Name\"}"))
                    .build();
            HttpResponse<String> adminInviteRes = client.send(adminInviteReq, HttpResponse.BodyHandlers.ofString());
            require(adminInviteRes.statusCode() == 503, "Unconfigured staff invitation fails closed with HTTP 503");
            require(adminInviteRes.body().contains("unavailable"), "Honest unavailable diagnostic returned");

            // D. Operational driver query returns fields without salary or margin (FR-ACC-003)
            HttpRequest driverReq = HttpRequest.newBuilder(base.resolve("/api/v1/drivers"))
                    .header("Authorization", "Bearer employee-token")
                    .GET().build();
            HttpResponse<String> driverRes = client.send(driverReq, HttpResponse.BodyHandlers.ofString());
            require(driverRes.statusCode() == 200, "Employee permitted to read operational drivers");
            JsonObject driverJson = JsonParser.parseString(driverRes.body()).getAsJsonObject();
            require(driverJson.has("drivers"), "Drivers array present in response");
            String responseStr = driverRes.body();
            require(!responseStr.contains("baseRatePaise"), "Operational driver schema contains zero salary fields");
            require(!responseStr.contains("tripAllowancePaise"), "Operational driver schema contains zero allowance fields");
            require(!responseStr.contains("margin"), "Operational driver schema contains zero company margin fields");
        } finally {
            server.stop(0);
        }
    }

    // --- Mock State & JDBC Proxy Harness ---

    private static final class MockEmployee {
        final UUID id;
        final UUID companyId;
        final String fullName;
        final String contact;
        final String email;
        final String address;
        final LocalDate joiningDate;
        String status;
        final boolean isDriver;
        long version = 1;

        MockEmployee(UUID id, UUID companyId, String fullName, String contact, String email, String address, LocalDate joiningDate, String status, boolean isDriver) {
            this.id = id;
            this.companyId = companyId;
            this.fullName = fullName;
            this.contact = contact;
            this.email = email;
            this.address = address;
            this.joiningDate = joiningDate;
            this.status = status;
            this.isDriver = isDriver;
        }
    }

    private static final class MockDriver {
        final UUID id;
        final UUID companyId;
        final String licenceNumber;
        String dutyStatus;
        long version = 1;

        MockDriver(UUID id, UUID companyId, String licenceNumber, String dutyStatus) {
            this.id = id;
            this.companyId = companyId;
            this.licenceNumber = licenceNumber;
            this.dutyStatus = dutyStatus;
        }
    }

    private static final class MockMembership {
        final UUID userId;
        final UUID companyId;
        final String role;
        boolean active;
        long version = 1L;

        MockMembership(UUID userId, UUID companyId, String role, boolean active) {
            this.userId = userId;
            this.companyId = companyId;
            this.role = role;
            this.active = active;
        }
    }

    private static final class MockDbState {
        final Map<UUID, MockEmployee> employees = new LinkedHashMap<>();
        final Map<UUID, MockDriver> drivers = new LinkedHashMap<>();
        final Map<UUID, MockMembership> memberships = new LinkedHashMap<>();
        final Map<UUID, UUID> companies = new LinkedHashMap<>(); // companyId -> primaryAdminId

        MockDbState() {
            memberships.put(ADMIN_USER_ID, new MockMembership(ADMIN_USER_ID, COMPANY_ID, "ADMINISTRATOR", true));
            memberships.put(EMPLOYEE_USER_ID, new MockMembership(EMPLOYEE_USER_ID, COMPANY_ID, "EMPLOYEE", true));
            companies.put(COMPANY_ID, ADMIN_USER_ID);
        }
    }

    private static DataSource createMockDataSource(MockDbState state) {
        return proxy(DataSource.class, (dsProxy, dsMethod, dsArgs) -> {
            if ("getConnection".equals(dsMethod.getName())) {
                return createMockConnection(state);
            }
            return null;
        });
    }

    private static Connection createMockConnection(MockDbState state) {
        return proxy(Connection.class, (cProxy, cMethod, cArgs) -> {
            String mName = cMethod.getName();
            if ("getAutoCommit".equals(mName)) return false;
            if ("isClosed".equals(mName)) return false;
            if ("setAutoCommit".equals(mName) || "commit".equals(mName) || "rollback".equals(mName) || "close".equals(mName) || "setReadOnly".equals(mName)) {
                return null;
            }
            if ("createArrayOf".equals(mName)) {
                return proxy(java.sql.Array.class, (aProxy, aMethod, aArgs) -> {
                    if ("getArray".equals(aMethod.getName())) return cArgs[1];
                    return null;
                });
            }
            if ("prepareStatement".equals(mName)) {
                String sql = (String) cArgs[0];
                return createMockPreparedStatement(state, sql);
            }
            return null;
        });
    }

    private static PreparedStatement createMockPreparedStatement(MockDbState state, String sql) {
        Map<Integer, Object> params = new HashMap<>();

        return proxy(PreparedStatement.class, (sProxy, sMethod, sArgs) -> {
            String smName = sMethod.getName();
            if ("setQueryTimeout".equals(smName)) return null;
            if (smName.startsWith("set") && sArgs != null && sArgs.length >= 2 && sArgs[0] instanceof Integer idx) {
                params.put(idx, sArgs[1]);
                return null;
            }
            if ("executeUpdate".equals(smName)) {
                if (sql.contains("driver_profiles") && sql.contains("duty_status")) {
                    String status = (String) params.get(1);
                    UUID driverId = (UUID) params.get(2);
                    MockDriver d = state.drivers.get(driverId);
                    if (d != null) {
                        d.dutyStatus = status;
                        d.version++;
                        return 1;
                    }
                    return 0;
                }
                if (sql.contains("companies") && sql.contains("primary_admin_user_id")) {
                    UUID newAdmin = (UUID) params.get(1);
                    UUID compId = (UUID) params.get(2);
                    state.companies.put(compId, newAdmin);
                    return 1;
                }
                return 1; // audit and generic updates
            }
            if ("executeQuery".equals(smName)) {
                return executeMockQuery(state, sql, params);
            }
            return null;
        });
    }

    private static ResultSet executeMockQuery(MockDbState state, String sql, Map<Integer, Object> params) {
        if (sql.contains("pg_roles")) {
            return singleRowResultSet(Map.of(1, true));
        }
        if (sql.contains("set_config")) {
            return singleRowResultSet(Map.of(1, ""));
        }
        // 1. JdbcStaffContext check: SELECT company_id, role, active FROM vrms_private.memberships WHERE auth_user_id = ?
        if (sql.contains("SELECT company_id, role, active FROM vrms_private.memberships")) {
            UUID userId = (UUID) params.get(1);
            MockMembership m = state.memberships.get(userId);
            if (m == null) return emptyResultSet();
            return singleRowResultSet(Map.of(
                    1, m.companyId,
                    2, m.role,
                    3, m.active,
                    "company_id", m.companyId,
                    "role", m.role,
                    "active", m.active
            ));
        }

        // 2. StaffAuthorizer check: SELECT auth_user_id, company_id, role, active FROM vrms_private.memberships
        if (sql.contains("SELECT auth_user_id, company_id, role, active FROM vrms_private.memberships")) {
            UUID userId = (UUID) params.get(1);
            MockMembership m = state.memberships.get(userId);
            if (m == null) return emptyResultSet();
            return singleRowResultSet(Map.of(
                    1, m.userId,
                    2, m.companyId,
                    3, m.role,
                    4, m.active,
                    "auth_user_id", m.userId,
                    "company_id", m.companyId,
                    "role", m.role,
                    "active", m.active
            ));
        }

        // 3. Primary admin check: SELECT primary_admin_user_id FROM vrms_private.companies WHERE id = ?
        if (sql.contains("SELECT primary_admin_user_id FROM vrms_private.companies")) {
            UUID compId = (UUID) params.get(1);
            UUID adminId = state.companies.get(compId);
            if (adminId == null) return emptyResultSet();
            return singleRowResultSet(Map.of(1, adminId, "primary_admin_user_id", adminId));
        }

        // 4. Role check for revoke: SELECT role, active FROM vrms_private.memberships WHERE company_id = ? AND auth_user_id = ?
        if (sql.contains("SELECT role, active FROM vrms_private.memberships WHERE company_id = ? AND auth_user_id = ?")) {
            UUID target = (UUID) params.get(2);
            MockMembership m = state.memberships.get(target);
            if (m == null) return emptyResultSet();
            return singleRowResultSet(Map.of(1, m.role, 2, m.active, "role", m.role, "active", m.active));
        }

        // 5. Active admin count
        if (sql.contains("SELECT count(*) FROM vrms_private.memberships WHERE company_id = ? AND role = 'ADMINISTRATOR'")) {
            return singleRowResultSet(Map.of(1, 1));
        }

        // 6. Target admin check for handover
        if (sql.contains("SELECT 1 FROM vrms_private.memberships WHERE company_id = ? AND auth_user_id = ? AND role = 'ADMINISTRATOR'")) {
            UUID target = (UUID) params.get(2);
            MockMembership m = state.memberships.get(target);
            if (m != null && "ADMINISTRATOR".equals(m.role) && m.active) {
                return singleRowResultSet(Map.of(1, 1));
            }
            return emptyResultSet();
        }

        // 7. Register employee: INSERT INTO vrms_private.employees ... RETURNING id, created_at, version
        if (sql.contains("INSERT INTO vrms_private.employees")) {
            UUID id = UUID.randomUUID();
            UUID compId = (UUID) params.get(1);
            String name = (String) params.get(2);
            String contact = (String) params.get(3);
            String email = (String) params.get(4);
            String addr = (String) params.get(5);
            Date joinDate = (Date) params.get(6);
            boolean isDriver = (Boolean) params.get(7);
            state.employees.put(id, new MockEmployee(id, compId, name, contact, email, addr, joinDate.toLocalDate(), "ACTIVE", isDriver));
            return singleRowResultSet(Map.of(1, id, 2, Timestamp.from(Instant.now()), 3, 1L));
        }

        // 8. Find duplicate employee
        if (sql.contains("SELECT id, company_id, auth_user_id, full_name, contact_number, email")) {
            String contact = (String) params.get(2);
            String email = (String) params.get(4);
            for (MockEmployee e : state.employees.values()) {
                if ((contact != null && contact.equals(e.contact)) || (email != null && email.equals(e.email))) {
                    return singleRowResultSet(Map.of(
                            "id", e.id,
                            "company_id", e.companyId,
                            "full_name", e.fullName,
                            "contact_number", e.contact,
                            "email", e.email != null ? e.email : "",
                            "address", e.address,
                            "joining_date", Date.valueOf(e.joiningDate),
                            "employment_status", e.status,
                            "is_driver", e.isDriver,
                            "version", e.version
                    ));
                }
            }
            return emptyResultSet();
        }

        // 9. Deactivate employee: UPDATE vrms_private.employees SET employment_status = 'DEACTIVATED'
        if (sql.contains("SET employment_status = 'DEACTIVATED'")) {
            UUID empId = (UUID) params.get(1);
            MockEmployee e = state.employees.get(empId);
            if (e != null) {
                e.status = "DEACTIVATED";
                e.version++;
                return singleRowResultSet(Map.of(
                        "id", e.id,
                        "company_id", e.companyId,
                        "full_name", e.fullName,
                        "contact_number", e.contact,
                        "email", e.email != null ? e.email : "",
                        "address", e.address,
                        "joining_date", Date.valueOf(e.joiningDate),
                        "employment_status", e.status,
                        "is_driver", e.isDriver,
                        "version", e.version
                ));
            }
            return emptyResultSet();
        }

        // 10. Record attendance: INSERT INTO vrms_private.attendance_records ... RETURNING id
        if (sql.contains("INSERT INTO vrms_private.attendance_records")) {
            return singleRowResultSet(Map.of(1, UUID.randomUUID()));
        }

        // 11. Record compensation: INSERT INTO vrms_private.compensation_records ... RETURNING id
        if (sql.contains("INSERT INTO vrms_private.compensation_records")) {
            return singleRowResultSet(Map.of(1, UUID.randomUUID()));
        }

        // 12. Get compensation
        if (sql.contains("SELECT id, company_id, employee_id, arrangement_type, base_rate_paise")) {
            UUID empId = (UUID) params.get(2);
            return singleRowResultSet(Map.of(
                    "id", UUID.randomUUID(),
                    "company_id", COMPANY_ID,
                    "employee_id", empId,
                    "arrangement_type", "FIXED_MONTHLY",
                    "base_rate_paise", 2500000L,
                    "trip_allowance_paise", 50000L,
                    "effective_from", Date.valueOf(LocalDate.of(2026, 1, 1))
            ));
        }

        // 13. Driver lookup employee: SELECT full_name, contact_number, employment_status FROM vrms_private.employees
        if (sql.contains("employment_status") && sql.contains("vrms_private.employees") && sql.contains("WHERE id = ?")) {
            UUID empId = (UUID) params.get(1);
            MockEmployee e = state.employees.get(empId);
            if (e == null) return emptyResultSet();
            return singleRowResultSet(Map.of(
                    "full_name", e.fullName,
                    "contact_number", e.contact,
                    "employment_status", e.status
            ));
        }

        // 14. Register driver: INSERT INTO vrms_private.driver_profiles ... RETURNING id
        if (sql.contains("INSERT INTO vrms_private.driver_profiles")) {
            UUID id = UUID.randomUUID();
            UUID compId = (UUID) params.get(1);
            String licence = (String) params.get(3);
            state.drivers.put(id, new MockDriver(id, compId, licence, "AVAILABLE"));
            return singleRowResultSet(Map.of(1, id));
        }

        // 15. List drivers
        if (sql.contains("FROM vrms_private.driver_profiles d")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (MockDriver d : state.drivers.values()) {
                rows.add(Map.of(
                        "id", d.id,
                        "company_id", d.companyId,
                        "employee_id", UUID.randomUUID(),
                        "full_name", "Driver Name",
                        "contact_number", "9999999999",
                        "licence_number", d.licenceNumber,
                        "licence_expiry", Date.valueOf(LocalDate.of(2028, 1, 1)),
                        "experience_years", 5,
                        "duty_status", d.dutyStatus,
                        "is_employed", true
                ));
            }
            return multiRowResultSet(rows);
        }

        // 16. Insert membership: INSERT INTO vrms_private.memberships ... RETURNING
        if (sql.contains("INSERT INTO vrms_private.memberships")) {
            UUID userId = (UUID) params.get(1);
            UUID compId = (UUID) params.get(2);
            UUID createdBy = (UUID) params.get(3);
            state.memberships.put(userId, new MockMembership(userId, compId, "EMPLOYEE", true));
            return singleRowResultSet(Map.of(
                    1, userId,
                    2, compId,
                    3, "EMPLOYEE",
                    4, true,
                    5, createdBy,
                    6, Timestamp.from(Instant.now()),
                    7, 1L
            ));
        }

        // 17. Revoke membership update: UPDATE vrms_private.memberships ... RETURNING
        if (sql.contains("UPDATE vrms_private.memberships") && sql.contains("active = false")) {
            UUID compId = (UUID) params.get(1);
            UUID target = (UUID) params.get(2);
            MockMembership m = state.memberships.get(target);
            if (m != null) {
                m.active = false;
                m.version++;
                return singleRowResultSet(Map.of(
                        1, m.userId,
                        2, m.companyId,
                        3, m.role,
                        4, false,
                        5, ADMIN_USER_ID,
                        6, Timestamp.from(Instant.now()),
                        7, m.version
                ));
            }
            return emptyResultSet();
        }

        return emptyResultSet();
    }

    private static ResultSet emptyResultSet() {
        return proxy(ResultSet.class, (p, m, a) -> {
            if ("next".equals(m.getName())) return false;
            if ("close".equals(m.getName())) return null;
            return null;
        });
    }

    private static ResultSet singleRowResultSet(Map<?, ?> data) {
        final boolean[] read = {false};
        return proxy(ResultSet.class, (p, m, a) -> {
            String mName = m.getName();
            if ("next".equals(mName)) {
                if (!read[0]) {
                    read[0] = true;
                    return true;
                }
                return false;
            }
            if ("close".equals(mName)) return null;
            if (("getObject".equals(mName) || "getString".equals(mName) || "getBoolean".equals(mName)
                    || "getLong".equals(mName) || "getInt".equals(mName) || "getDate".equals(mName)
                    || "getTimestamp".equals(mName)) && a != null && a.length >= 1) {
                Object val = data.get(a[0]);
                if (val != null) {
                    if (a.length >= 2 && a[1] instanceof Class<?> targetType) {
                        if (targetType.isInstance(val)) return val;
                    }
                    return val;
                }
            }
            if ("getArray".equals(mName)) {
                return proxy(java.sql.Array.class, (ap, am, aa) -> {
                    if ("getArray".equals(am.getName())) return new String[]{"LMV", "HMV"};
                    return null;
                });
            }
            return null;
        });
    }

    private static ResultSet multiRowResultSet(List<Map<String, Object>> rows) {
        final int[] index = {-1};
        return proxy(ResultSet.class, (p, m, a) -> {
            String mName = m.getName();
            if ("next".equals(mName)) {
                index[0]++;
                return index[0] < rows.size();
            }
            if ("close".equals(mName)) return null;
            if (index[0] >= 0 && index[0] < rows.size()) {
                Map<String, Object> cur = rows.get(index[0]);
                if (("getObject".equals(mName) || "getString".equals(mName) || "getBoolean".equals(mName)
                        || "getLong".equals(mName) || "getInt".equals(mName) || "getDate".equals(mName)
                        || "getTimestamp".equals(mName)) && a != null && a.length >= 1) {
                    Object val = cur.get(a[0]);
                    if (val != null) {
                        if (a.length >= 2 && a[1] instanceof Class<?> targetType) {
                            if (targetType.isInstance(val)) return val;
                        }
                        return val;
                    }
                }
                if ("getArray".equals(mName)) {
                    return proxy(java.sql.Array.class, (ap, am, aa) -> {
                        if ("getArray".equals(am.getName())) return new String[]{"LMV", "HMV"};
                        return null;
                    });
                }
            }
            return null;
        });
    }
}
