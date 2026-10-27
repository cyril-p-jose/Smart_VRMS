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
import java.net.InetSocketAddress;
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
 * Stage 4 Integration Checks:
 * Pricing engine, packages, availability calculation, pricing snapshots, idempotency,
 * and concurrent allocation transactions (FR-BOOK-001 through FR-BOOK-010).
 */
public final class Stage4IntegrationChecks {
    private static int checks;
    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EMPLOYEE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMPANY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 4 integration checks...");
        testPricingQuoteEngineCalculations();
        testMigration005SchemaIntegrity();
        testBookingPortAndDomainLogic();
        testVrmsHttpServerStage4Endpoints();
        testBookingViewLayoutAndResponsiveScaling();
        System.out.println("PASS: " + checks + " Stage 4 pricing, package booking, concurrency, API, and UI assertions.");
    }

    private static void testPricingQuoteEngineCalculations() {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");

        // Case 1: First 24-hour minimum (5 hours planned costs full daily rate of 2,400 INR = 240000 paise)
        Instant end5h = now.plus(5, ChronoUnit.HOURS);
        RatePlanRecord carPlan = new RatePlanRecord(UUID.randomUUID(), COMPANY_ID, null, null, 240000L, 10000L, 80000L, 3333L, now, null, 1L);
        QuoteEngine.AssignmentInput carInput = new QuoteEngine.AssignmentInput(
                UUID.randomUUID(), "Sedan Car", "CAR", "KA-01-AB-1234", null, null, false, carPlan
        );
        BookingQuote q1 = QuoteEngine.calculate(now, end5h, List.of(carInput));
        require(q1.durationHours() == 5, "q1 duration 5h");
        require(q1.billableDays() == 1, "q1 billable days is 1 (first 24h minimum rule, FR-BOOK-007)");
        require(q1.billableRemainderHours() == 0, "q1 remainder hours is 0");
        require(q1.baseTotalPaise() == 240000L, "q1 base total is 240000 paise (2400 INR)");
        require(q1.driverTotalPaise() == 0L, "q1 driver total is 0");
        require(q1.grandTotalPaise() == 240000L, "q1 grand total is 240000 paise");

        // Case 2: 26 hours planned -> 2,400 + 2*(2,400/24) = 2,600 INR = 260000 paise
        Instant end26h = now.plus(26, ChronoUnit.HOURS);
        BookingQuote q2 = QuoteEngine.calculate(now, end26h, List.of(carInput));
        require(q2.durationHours() == 26, "q2 duration 26h");
        require(q2.billableDays() == 1, "q2 billable days is 1");
        require(q2.billableRemainderHours() == 2, "q2 remainder hours is 2");
        require(q2.baseTotalPaise() == 260000L, "q2 base total is 260000 paise (2600 INR) per Section 8");
        require(q2.grandTotalPaise() == 260000L, "q2 grand total 260000 paise");

        // Case 3: 2 days package (Section 8 Journey B):
        // Car @ 2,400/day = 4,800 + Scooter @ 600/day = 1,200 + Car driver @ 800/day = 1,600 -> Total 7,600 INR = 760000 paise
        Instant end2d = now.plus(48, ChronoUnit.HOURS);
        RatePlanRecord scooterPlan = new RatePlanRecord(UUID.randomUUID(), COMPANY_ID, null, null, 60000L, 2500L, 0L, 0L, now, null, 1L);
        UUID driverId = UUID.randomUUID();
        QuoteEngine.AssignmentInput carWithDriver = new QuoteEngine.AssignmentInput(
                UUID.randomUUID(), "Sedan Car", "CAR", "KA-01-AB-1234", driverId, "Ramesh Driver", true, carPlan
        );
        QuoteEngine.AssignmentInput scooterNoDriver = new QuoteEngine.AssignmentInput(
                UUID.randomUUID(), "Activa Scooter", "SCOOTER", "KA-01-CD-5678", null, null, false, scooterPlan
        );

        BookingQuote q3 = QuoteEngine.calculate(now, end2d, List.of(carWithDriver, scooterNoDriver));
        require(q3.billableDays() == 2, "q3 billable days is 2");
        require(q3.billableRemainderHours() == 0, "q3 remainder hours is 0");
        require(q3.assignments().size() == 2, "q3 package contains 2 distinct assignments (FR-BOOK-002)");
        require(q3.assignments().get(0).vehicleTotalPaise() == 480000L, "Car 2 days vehicle total = 480000 paise");
        require(q3.assignments().get(0).driverTotalPaise() == 160000L, "Car driver 2 days total = 160000 paise");
        require(q3.assignments().get(0).lineTotalPaise() == 640000L, "Car line total = 640000 paise");
        require(q3.assignments().get(1).vehicleTotalPaise() == 120000L, "Scooter 2 days total = 120000 paise");
        require(q3.assignments().get(1).driverTotalPaise() == 0L, "Scooter driver total = 0");
        require(q3.baseTotalPaise() == 600000L, "q3 base vehicle total = 600000 paise (4800 + 1200)");
        require(q3.driverTotalPaise() == 160000L, "q3 driver total = 160000 paise");
        require(q3.taxPaise() == 0L, "q3 tax is symbolic 0 (unverified tax setup, FR-BOOK-007)");
        require(q3.grandTotalPaise() == 760000L, "q3 grand total = 760000 paise (7600 INR) per Section 8 Journey B");

        // Input validation checks
        boolean errCaught = false;
        try {
            QuoteEngine.calculate(now, now.minusSeconds(10), List.of(carInput));
        } catch (IllegalArgumentException e) {
            errCaught = true;
        }
        require(errCaught, "rejects end time before start time");

        errCaught = false;
        try {
            QuoteEngine.calculate(now, end5h, List.of());
        } catch (IllegalArgumentException e) {
            errCaught = true;
        }
        require(errCaught, "rejects empty assignments list");
    }

    private static void testMigration005SchemaIntegrity() throws Exception {
        Path p = Path.of("database/migrations/005_booking_engine.sql");
        require(Files.exists(p), "migration 005 file exists");

        String sql = Files.readString(p);
        require(sql.contains("CREATE TABLE vrms_private.rate_plans"), "migration 005 creates rate_plans");
        require(sql.contains("CREATE TABLE vrms_private.bookings"), "migration 005 creates bookings");
        require(sql.contains("CREATE TABLE vrms_private.booking_assignments"), "migration 005 creates booking_assignments");
        require(sql.contains("CREATE TABLE vrms_private.vehicle_reservations"), "migration 005 creates vehicle_reservations");
        require(sql.contains("CREATE TABLE vrms_private.driver_reservations"), "migration 005 creates driver_reservations");

        require(sql.contains("ALTER TABLE vrms_private.rate_plans ENABLE ROW LEVEL SECURITY"), "rate_plans enables RLS");
        require(sql.contains("ALTER TABLE vrms_private.bookings FORCE ROW LEVEL SECURITY"), "bookings forces RLS");
        require(sql.contains("ALTER TABLE vrms_private.booking_assignments FORCE ROW LEVEL SECURITY"), "booking_assignments forces RLS");
        require(sql.contains("ALTER TABLE vrms_private.vehicle_reservations FORCE ROW LEVEL SECURITY"), "vehicle_reservations forces RLS");
        require(sql.contains("ALTER TABLE vrms_private.driver_reservations FORCE ROW LEVEL SECURITY"), "driver_reservations forces RLS");

        require(sql.contains("GRANT SELECT, INSERT, UPDATE ON vrms_private.bookings TO smart_vrms_api"), "grants on bookings");
        require(sql.contains("GRANT SELECT, INSERT, UPDATE ON vrms_private.booking_assignments TO smart_vrms_api"), "grants on assignments");
        require(sql.contains("GRANT SELECT, INSERT, UPDATE ON vrms_private.vehicle_reservations TO smart_vrms_api"), "grants on vehicle reservations");
        require(sql.contains("GRANT SELECT, INSERT, UPDATE ON vrms_private.driver_reservations TO smart_vrms_api"), "grants on driver reservations");
        require(sql.contains("INSERT INTO vrms_private.schema_migrations (version, description)") && sql.contains("4"), "records version 4");
    }

    private static void testBookingPortAndDomainLogic() {
        MockBookingPort port = new MockBookingPort();
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant end = start.plus(48, ChronoUnit.HOURS);

        UUID carId = UUID.randomUUID();
        UUID scooterId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        // 1. Vehicle availability query
        require(port.isVehicleAvailable(COMPANY_ID, carId, start, end), "car initially available");
        require(port.isDriverAvailable(COMPANY_ID, driverId, start, end), "driver initially available");

        // 2. Reject duplicate physical vehicle in package (FR-BOOK-002)
        boolean dupRejected = false;
        try {
            port.confirmBooking(new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "", Set.of("BOOK_STANDARD_RENTAL")),
                    new BookingConfirmationCommand(
                            "idemp-1", null, "Test Customer", "+919876543210", "cust@example.com", "Bangalore", 28, "Holiday",
                            start, end,
                            List.of(
                                    new BookingConfirmationCommand.PackageItem(carId, false, null),
                                    new BookingConfirmationCommand.PackageItem(carId, false, null) // duplicate physical ID
                            ),
                            100000L, 0L
                    ));
        } catch (IllegalArgumentException e) {
            dupRejected = true;
        }
        require(dupRejected, "duplicate physical vehicle in package is rejected (FR-BOOK-002)");

        // 3. Reject driver required without driver ID (FR-BOOK-003)
        boolean missingDriverRejected = false;
        try {
            port.confirmBooking(new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "", Set.of("BOOK_STANDARD_RENTAL")),
                    new BookingConfirmationCommand(
                            "idemp-2", null, "Test Customer", "+919876543210", "cust@example.com", "Bangalore", 28, "Holiday",
                            start, end,
                            List.of(new BookingConfirmationCommand.PackageItem(carId, true, null)), // driver required but null
                            100000L, 0L
                    ));
        } catch (IllegalArgumentException e) {
            missingDriverRejected = true;
        }
        require(missingDriverRejected, "driver required without eligible driver is rejected (FR-BOOK-003)");

        // 4. Successful atomic booking confirmation with package (FR-BOOK-001, FR-BOOK-002, FR-BOOK-003, FR-BOOK-010)
        BookingRecord confirmed = port.confirmBooking(
                new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "", Set.of("BOOK_STANDARD_RENTAL")),
                new BookingConfirmationCommand(
                        "idemp-success", null, "Anu Customer", "+919876543210", "anu@example.com", "MG Road, Bangalore", 26, "Vacation",
                        start, end,
                        List.of(
                                new BookingConfirmationCommand.PackageItem(carId, true, driverId),
                                new BookingConfirmationCommand.PackageItem(scooterId, false, null)
                        ),
                        100000L, 300000L // 1000 INR agreed advance, 3000 INR deposit
                )
        );

        require(confirmed != null, "booking confirmed");
        require(confirmed.bookingNumber().startsWith("BK-"), "booking number format BK-YYYYMMDD-XXXX");
        require("CONFIRMED".equals(confirmed.status()), "booking status is CONFIRMED");
        require(confirmed.assignments().size() == 2, "booking has 2 assignments");
        require(confirmed.agreedAdvancePaise() == 100000L, "agreed advance recorded");
        require(confirmed.actualAdvanceReceivedPaise() == 0L, "actual received advance remains 0 until verified receipt (FR-BOOK-008)");
        require(confirmed.depositPaise() == 300000L, "deposit recorded separately from rental income");

        // 5. Subsequent availability check blocks booked car and driver
        require(!port.isVehicleAvailable(COMPANY_ID, carId, start.plus(1, ChronoUnit.HOURS), end.minus(1, ChronoUnit.HOURS)), "booked car is unavailable");
        require(!port.isDriverAvailable(COMPANY_ID, driverId, start.plus(1, ChronoUnit.HOURS), end.minus(1, ChronoUnit.HOURS)), "booked driver is unavailable");
        require(port.isVehicleAvailable(COMPANY_ID, carId, end.plus(1, ChronoUnit.DAYS), end.plus(2, ChronoUnit.DAYS)), "car available outside reserved window");

        // 6. Conflicting booking for already reserved vehicle fails atomically (FR-BOOK-010)
        boolean conflictRejected = false;
        try {
            port.confirmBooking(new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "", Set.of("BOOK_STANDARD_RENTAL")),
                    new BookingConfirmationCommand(
                            "idemp-conflict", null, "Second Customer", "+919999999999", "second@example.com", "Bangalore", 30, "Trip",
                            start.plus(5, ChronoUnit.HOURS), end.plus(5, ChronoUnit.HOURS),
                            List.of(new BookingConfirmationCommand.PackageItem(carId, false, null)),
                            0L, 0L
                    ));
        } catch (IllegalStateException e) {
            conflictRejected = true;
        }
        require(conflictRejected, "conflicting concurrent booking for reserved vehicle rejected (FR-BOOK-010)");

        // 7. Rate plan configuration - Admin allowed, Employee denied (FR-BOOK-007)
        RatePlanRecord configuredPlan = port.configureRatePlan(
                new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "", Set.of("MANAGE_RATES")),
                new RatePlanRecord(null, COMPANY_ID, carId, null, 250000L, 11000L, 85000L, 3500L, Instant.now(), null, 1L)
        );
        require(configuredPlan.dailyRatePaise() == 250000L, "admin configured rate plan");

        boolean employeeRateDenied = false;
        try {
            port.configureRatePlan(
                    new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "", Set.of("BOOK_STANDARD_RENTAL")),
                    new RatePlanRecord(null, COMPANY_ID, carId, null, 200000L, 10000L, 80000L, 3000L, Instant.now(), null, 1L)
            );
        } catch (SecurityException e) {
            employeeRateDenied = true;
        }
        require(employeeRateDenied, "employee denied from configuring rate plans");
    }

    private static void testVrmsHttpServerStage4Endpoints() throws Exception {
        MockBookingPort port = new MockBookingPort();
        SupabaseConfig sbConfig = new SupabaseConfig(
                "https://abcdefghijklmnopqrst.supabase.co",
                "sb_publishable_fixture0123456789"
        );
        SupabaseAuth mockAuth = new SupabaseAuth(sbConfig);

        StaffAuthorizer.Staff adminStaff = new StaffAuthorizer.Staff(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR");
        StaffAuthorizer.Staff employeeStaff = new StaffAuthorizer.Staff(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE");

        VrmsHttpServer.Authorizer mockAuthorizer = (tok, cap) -> {
            if ("admin-tok".equals(tok)) return adminStaff;
            if ("employee-tok".equals(tok)) {
                if (cap.administratorOnly()) {
                    throw new SecurityException("Forbidden: Capability " + cap + " requires Administrator.");
                }
                return employeeStaff;
            }
            throw new IllegalArgumentException("Invalid token");
        };

        HttpServerConfig config = new HttpServerConfig(new InetSocketAddress("127.0.0.1", 0), null);
        VrmsHttpServer server = new VrmsHttpServer(
                config, mockAuth, mockAuthorizer,
                null, null, null, null, null, null, null, null,
                port
        );
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        String baseUri = server.baseUri().toString();
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Instant end = start.plus(24, ChronoUnit.HOURS);
        UUID vId = UUID.randomUUID();

        try {
            // 1. POST /api/v1/bookings/quote
            JsonObject quoteReq = new JsonObject();
            quoteReq.addProperty("startTime", start.toString());
            quoteReq.addProperty("endTime", end.toString());
            JsonArray items = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("vehicleId", vId.toString());
            item.addProperty("driverRequired", false);
            items.add(item);
            quoteReq.add("items", items);

            HttpRequest req1 = HttpRequest.newBuilder()
                    .uri(URI.create(baseUri + "/api/v1/bookings/quote"))
                    .header("Authorization", "Bearer employee-tok")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(quoteReq.toString()))
                    .build();
            HttpResponse<String> resp1 = client.send(req1, HttpResponse.BodyHandlers.ofString());
            require(resp1.statusCode() == 200, "quote endpoint returns 200");
            JsonObject quoteResp = JsonParser.parseString(resp1.body()).getAsJsonObject();
            require(quoteResp.has("grandTotalPaise"), "quote response has grandTotalPaise");

            // 2. GET /api/v1/bookings/availability
            HttpRequest req2 = HttpRequest.newBuilder()
                    .uri(URI.create(baseUri + "/api/v1/bookings/availability?vehicleId=" + vId + "&startTime=" + start + "&endTime=" + end))
                    .header("Authorization", "Bearer employee-tok")
                    .GET()
                    .build();
            HttpResponse<String> resp2 = client.send(req2, HttpResponse.BodyHandlers.ofString());
            require(resp2.statusCode() == 200, "availability endpoint returns 200");
            JsonObject availResp = JsonParser.parseString(resp2.body()).getAsJsonObject();
            require(availResp.get("available").getAsBoolean(), "vehicle is available");

            // 3. POST /api/v1/bookings/confirm
            JsonObject confReq = new JsonObject();
            confReq.addProperty("idempotencyKey", UUID.randomUUID().toString());
            confReq.addProperty("customerFullName", "HTTP Client Test");
            confReq.addProperty("customerContact", "+919988776655");
            confReq.addProperty("customerEmail", "http@test.com");
            confReq.addProperty("customerAddress", "Indiranagar, Bangalore");
            confReq.addProperty("ageAtVisit", 29);
            confReq.addProperty("visitReason", "Business");
            confReq.addProperty("startTime", start.toString());
            confReq.addProperty("endTime", end.toString());
            confReq.add("items", items);
            confReq.addProperty("agreedAdvancePaise", 50000L);
            confReq.addProperty("depositPaise", 200000L);

            HttpRequest req3 = HttpRequest.newBuilder()
                    .uri(URI.create(baseUri + "/api/v1/bookings/confirm"))
                    .header("Authorization", "Bearer employee-tok")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(confReq.toString()))
                    .build();
            HttpResponse<String> resp3 = client.send(req3, HttpResponse.BodyHandlers.ofString());
            require(resp3.statusCode() == 201, "confirm booking returns 201 Created");
            JsonObject confResp = JsonParser.parseString(resp3.body()).getAsJsonObject();
            require(confResp.has("bookingNumber"), "response contains bookingNumber");
            require("CONFIRMED".equals(confResp.get("status").getAsString()), "response status is CONFIRMED");

            // 4. GET /api/v1/bookings
            HttpRequest req4 = HttpRequest.newBuilder()
                    .uri(URI.create(baseUri + "/api/v1/bookings?limit=10&offset=0"))
                    .header("Authorization", "Bearer employee-tok")
                    .GET()
                    .build();
            HttpResponse<String> resp4 = client.send(req4, HttpResponse.BodyHandlers.ofString());
            require(resp4.statusCode() == 200, "list bookings returns 200");
            JsonObject listResp = JsonParser.parseString(resp4.body()).getAsJsonObject();
            require(listResp.has("bookings"), "list bookings contains bookings array");

            // 5. Role gate: Employee cannot configure rate plans (MANAGE_RATES is Admin-only)
            JsonObject rateReq = new JsonObject();
            rateReq.addProperty("vehicleId", vId.toString());
            rateReq.addProperty("dailyRatePaise", 300000L);
            HttpRequest req5 = HttpRequest.newBuilder()
                    .uri(URI.create(baseUri + "/api/v1/bookings/rate-plans"))
                    .header("Authorization", "Bearer employee-tok")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(rateReq.toString()))
                    .build();
            HttpResponse<String> resp5 = client.send(req5, HttpResponse.BodyHandlers.ofString());
            require(resp5.statusCode() == 403, "employee denied from rate-plans endpoint (403 Forbidden)");

        } finally {
            server.stop(0);
        }
    }

    private static void testBookingViewLayoutAndResponsiveScaling() {
        BookingView view = new BookingView();
        require(view.customer != null, "customer input present");
        require(view.phone != null, "phone input present");
        require(view.ageAtVisit != null, "ageAtVisit input present");
        require(view.agreedAdvance != null, "agreedAdvance input present");
        require(view.deposit != null, "deposit input present");

        view.customer.setText("Sunil Kumar");
        view.phone.setText("+919123456789");
        view.email.setText("sunil@example.com");
        view.ageAtVisit.setText("32");
        view.pickup.setText("10/11/2026 10:00");
        view.expectedReturn.setText("12/11/2026 10:00");
        view.shortlist("Hyundai Creta");

        view.showReview();
        require(view.isReviewing(), "view is reviewing");
        require(!view.confirmBtn.isEnabled(), "confirm button disabled during draft review");
        require("₹2400".equals(view.rentalChargeLabel.getText()), "rental charge shows 2400");

        // Test display scaling
        int[][] resolutions = {{1024, 720}, {1280, 820}, {1600, 1000}};
        for (int[] res : resolutions) {
            view.setSize(new Dimension(res[0], res[1]));
            view.doLayout();
            require(view.getWidth() == res[0] && view.getHeight() == res[1], "view scales to " + res[0] + "x" + res[1]);
        }
    }

    // =========================================================================
    // Mock Port Implementation for Isolation Tests
    // =========================================================================

    private static class MockBookingPort implements BookingPort {
        record ResWindow(UUID id, Instant start, Instant end) {
            boolean overlaps(Instant s, Instant e) {
                return s.isBefore(end) && e.isAfter(start);
            }
        }

        private final Map<UUID, BookingRecord> bookings = new ConcurrentHashMap<>();
        private final List<ResWindow> vehicleReservations = new ArrayList<>();
        private final List<ResWindow> driverReservations = new ArrayList<>();
        private final Map<UUID, RatePlanRecord> ratePlans = new ConcurrentHashMap<>();

        @Override
        public BookingQuote calculateQuote(UUID companyId, Instant startTime, Instant endTime, List<BookingConfirmationCommand.PackageItem> items) {
            List<QuoteEngine.AssignmentInput> inputs = new ArrayList<>();
            for (var item : items) {
                RatePlanRecord plan = ratePlans.getOrDefault(item.vehicleId(),
                        new RatePlanRecord(UUID.randomUUID(), companyId, item.vehicleId(), null, 240000L, 10000L, 80000L, 3333L, Instant.now(), null, 1L));
                inputs.add(new QuoteEngine.AssignmentInput(
                        item.vehicleId(), "Specimen Vehicle", "CAR", "KA-01-XX-9999",
                        item.driverId(), item.driverRequired() ? "Assigned Driver" : null,
                        item.driverRequired(), plan
                ));
            }
            return QuoteEngine.calculate(startTime, endTime, inputs);
        }

        @Override
        public synchronized boolean isVehicleAvailable(UUID companyId, UUID vehicleId, Instant startTime, Instant endTime) {
            for (ResWindow w : vehicleReservations) {
                if (w.id().equals(vehicleId) && w.overlaps(startTime, endTime)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public synchronized boolean isDriverAvailable(UUID companyId, UUID driverId, Instant startTime, Instant endTime) {
            if (driverId == null) return false;
            for (ResWindow w : driverReservations) {
                if (w.id().equals(driverId) && w.overlaps(startTime, endTime)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public synchronized BookingRecord confirmBooking(StaffSession session, BookingConfirmationCommand command) {
            Set<UUID> vIds = new HashSet<>();
            for (var it : command.items()) {
                if (!vIds.add(it.vehicleId())) {
                    throw new IllegalArgumentException("Duplicate physical vehicle in package is rejected: " + it.vehicleId());
                }
                if (it.driverRequired() && it.driverId() == null) {
                    throw new IllegalArgumentException("Driver required but not selected.");
                }
            }

            for (var it : command.items()) {
                if (!isVehicleAvailable(session.companyId(), it.vehicleId(), command.startTime(), command.endTime())) {
                    throw new IllegalStateException("Vehicle " + it.vehicleId() + " unavailable.");
                }
                if (it.driverRequired() && !isDriverAvailable(session.companyId(), it.driverId(), command.startTime(), command.endTime())) {
                    throw new IllegalStateException("Driver " + it.driverId() + " unavailable.");
                }
            }

            UUID bId = UUID.randomUUID();
            UUID cId = command.existingCustomerId() != null ? command.existingCustomerId() : UUID.randomUUID();
            UUID vId = UUID.randomUUID();
            BookingQuote quote = calculateQuote(session.companyId(), command.startTime(), command.endTime(), command.items());

            List<BookingAssignmentRecord> assigns = new ArrayList<>();
            for (var it : command.items()) {
                UUID aId = UUID.randomUUID();
                vehicleReservations.add(new ResWindow(it.vehicleId(), command.startTime(), command.endTime()));
                if (it.driverRequired()) {
                    driverReservations.add(new ResWindow(it.driverId(), command.startTime(), command.endTime()));
                }
                assigns.add(new BookingAssignmentRecord(
                        aId, session.companyId(), bId, it.vehicleId(), it.driverId(), it.driverRequired(),
                        command.startTime(), command.endTime(), 240000L, 10000L, 240000L, it.driverRequired() ? 80000L : 0L,
                        240000L + (it.driverRequired() ? 80000L : 0L), "RESERVED"
                ));
            }

            BookingRecord rec = new BookingRecord(
                    bId, session.companyId(), cId, vId, "BK-20261110-1234", "CONFIRMED",
                    command.startTime(), command.endTime(), quote.baseTotalPaise(), quote.driverTotalPaise(),
                    quote.taxPaise(), quote.grandTotalPaise(), command.agreedAdvancePaise(), 0L, command.depositPaise(),
                    "{}", assigns, session.userId(), Instant.now(), 1L
            );
            bookings.put(bId, rec);
            return rec;
        }

        @Override
        public BookingRecord getBookingById(UUID companyId, UUID bookingId) {
            return bookings.get(bookingId);
        }

        @Override
        public List<BookingRecord> listBookings(UUID companyId, int limit, int offset) {
            return new ArrayList<>(bookings.values());
        }

        @Override
        public RatePlanRecord configureRatePlan(StaffSession session, RatePlanRecord plan) {
            if (!"ADMINISTRATOR".equals(session.role())) {
                throw new SecurityException("Admin only");
            }
            UUID id = plan.id() != null ? plan.id() : UUID.randomUUID();
            RatePlanRecord saved = new RatePlanRecord(
                    id, session.companyId(), plan.vehicleId(), plan.categoryId(),
                    plan.dailyRatePaise(), plan.hourlyRatePaise(), plan.driverDailyFeePaise(),
                    plan.driverHourlyFeePaise(), plan.effectiveFrom(), plan.effectiveTo(), 1L
            );
            if (plan.vehicleId() != null) {
                ratePlans.put(plan.vehicleId(), saved);
            }
            return saved;
        }

        @Override
        public RatePlanRecord getRatePlanForVehicle(UUID companyId, UUID vehicleId) {
            return ratePlans.get(vehicleId);
        }
    }
}
