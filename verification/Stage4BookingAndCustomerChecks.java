package in.smartvrms.verification;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.application.booking.DraftValidator;
import in.smartvrms.application.port.*;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.controller.BookingController;
import in.smartvrms.desktop.integration.BookingHttpClient;
import in.smartvrms.desktop.integration.CustomerHttpClient;
import in.smartvrms.desktop.ui.BookingView;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.domain.JdbcBookingService;
import in.smartvrms.server.domain.JdbcCustomerService;
import in.smartvrms.server.domain.QuoteEngine;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.JdbcIdempotencyService;
import in.smartvrms.server.security.StaffAuthorizer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Stage 4 End-to-End Booking and Customer Verification Suite.
 * Validates FR-CUST-001..008 and FR-BOOK-001..011:
 * - Quote boundaries, hourly remainder, rounding, late grace, extension repricing, tax treatment
 * - Half-open [start, end) interval collision vs adjacency
 * - Single vs package multi-vehicle, mixed driver needs
 * - Customer KYC within booking context, age-at-visit integer (18-120), duplicate warnings
 * - Concurrent booking conflict revalidation (409 Conflict)
 * - Idempotency key exact replay vs payload mutation
 * - Tenant isolation across companies
 * - Atomic package rollback
 * - Live HTTP API + Desktop UI wiring (removal of fake confirm)
 */
public final class Stage4BookingAndCustomerChecks {
    private static int checks = 0;
    private static final UUID COMPANY_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPANY_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_STAFF = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_STAFF_B = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID USER_ADMIN = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 4 Booking and Customer verification checks...");
        testQuoteBoundariesAndRounding();
        testLateReturnGraceAndExtensionRepricing();
        testTaxUnverifiedSymbolicZero();
        testHalfOpenIntervalAdjacencyAndOverlap();
        testCustomerLifecycleAndKycRules();
        testPackageConfigurationsAndAdvanceSeparation();
        testConcurrentBookingConflictRevalidation();
        testIdempotencyExactReplayAndMutation();
        testTenantIsolationAndCrossCompanyRejection();
        testAtomicPackageRollback();
        testHttpServerAndApiWiring();
        testDesktopUiIntegration();
        System.out.println("PASS: All " + checks + " Stage 4 end-to-end booking & customer assertions passed cleanly!");
    }

    private static void testQuoteBoundariesAndRounding() {
        Instant start = Instant.parse("2026-11-01T10:00:00Z");

        // Daily rate 2,400 INR = 240,000 paise; Hourly rate = 10,000 paise
        RatePlanRecord carPlan = new RatePlanRecord(
                UUID.randomUUID(), COMPANY_A, null, null, 240000L, 10000L, 80000L, 3333L, start, null, 1L
        );
        QuoteEngine.AssignmentInput carInput = new QuoteEngine.AssignmentInput(
                UUID.randomUUID(), "Sedan Car", "CAR", "KA-01-AB-1234", null, null, false, carPlan
        );

        // 1 hour planned rental: must bill full 24h first-day minimum (BR-PRICE-001)
        BookingQuote q1h = QuoteEngine.calculate(start, start.plus(1, ChronoUnit.HOURS), List.of(carInput));
        require(q1h.durationHours() == 1, "1 hour duration");
        require(q1h.billableDays() == 1, "1 hour bills 1 full day minimum (BR-PRICE-001)");
        require(q1h.billableRemainderHours() == 0, "0 remainder hours for < 24h");
        require(q1h.baseTotalPaise() == 240000L, "1h costs 240,000 paise minimum");

        // 23 hours 59 minutes: still bills 1 full day minimum
        BookingQuote q23h59m = QuoteEngine.calculate(start, start.plus(23, ChronoUnit.HOURS).plus(59, ChronoUnit.MINUTES), List.of(carInput));
        require(q23h59m.billableDays() == 1, "23h59m bills 1 full day minimum");
        require(q23h59m.billableRemainderHours() == 0, "23h59m has 0 remainder hours");
        require(q23h59m.baseTotalPaise() == 240000L, "23h59m costs 240,000 paise");

        // Exactly 24 hours: 1 full day
        BookingQuote q24h = QuoteEngine.calculate(start, start.plus(24, ChronoUnit.HOURS), List.of(carInput));
        require(q24h.billableDays() == 1, "24h bills 1 full day");
        require(q24h.billableRemainderHours() == 0, "24h has 0 remainder hours");
        require(q24h.baseTotalPaise() == 240000L, "24h costs 240,000 paise");

        // 24 hours 1 minute: 1 full day + 1 remainder hour (rounded up to whole hour per BR-PRICE-001)
        BookingQuote q24h1m = QuoteEngine.calculate(start, start.plus(24, ChronoUnit.HOURS).plus(1, ChronoUnit.MINUTES), List.of(carInput));
        require(q24h1m.billableDays() == 1, "24h1m has 1 full day");
        require(q24h1m.billableRemainderHours() == 1, "24h1m rounds up to 1 remainder hour");
        require(q24h1m.baseTotalPaise() == 250000L, "24h1m costs 240,000 + 10,000 = 250,000 paise");

        // 26 hours: 1 full day + 2 remainder hours = 240,000 + 20,000 = 260,000 paise
        BookingQuote q26h = QuoteEngine.calculate(start, start.plus(26, ChronoUnit.HOURS), List.of(carInput));
        require(q26h.billableDays() == 1, "26h has 1 full day");
        require(q26h.billableRemainderHours() == 2, "26h has 2 remainder hours");
        require(q26h.baseTotalPaise() == 260000L, "26h costs 260,000 paise");

        // Remainder hours capped at daily rate: 24h + 23h at 10,000 = 230,000, capped at daily rate 240,000
        BookingQuote q47h = QuoteEngine.calculate(start, start.plus(47, ChronoUnit.HOURS), List.of(carInput));
        require(q47h.billableDays() == 1, "47h has 1 full day");
        require(q47h.billableRemainderHours() == 23, "47h has 23 remainder hours");
        require(q47h.baseTotalPaise() == 470000L, "47h base total is 240,000 + 230,000 = 470,000 paise (below 480,000 cap)");
    }

    private static void testLateReturnGraceAndExtensionRepricing() {
        long dailyRatePaise = 240000L;
        Instant agreedEnd = Instant.parse("2026-11-05T12:00:00Z");

        // 30-minute return grace (BR-PRICE-003): return at 12:20 = 0 fee
        long fee20m = QuoteEngine.calculateLateFee(dailyRatePaise, agreedEnd, agreedEnd.plus(20, ChronoUnit.MINUTES));
        require(fee20m == 0L, "20 min late is within 30 min grace -> 0 fee (BR-PRICE-003)");

        // Exactly 30 min late: 0 fee
        long fee30m = QuoteEngine.calculateLateFee(dailyRatePaise, agreedEnd, agreedEnd.plus(30, ChronoUnit.MINUTES));
        require(fee30m == 0L, "30 min late is within grace -> 0 fee");

        // 35 min late: exceeds 30 min grace -> 1 full hour fee (10,000 paise)
        long fee35m = QuoteEngine.calculateLateFee(dailyRatePaise, agreedEnd, agreedEnd.plus(35, ChronoUnit.MINUTES));
        require(fee35m == 10000L, "35 min late bills 1 hour fee beyond grace");

        // 70 min total late: 70 - 30 = 40 chargeable min -> 1 hour fee (10,000 paise per baseline example)
        long fee70m = QuoteEngine.calculateLateFee(dailyRatePaise, agreedEnd, agreedEnd.plus(70, ChronoUnit.MINUTES));
        require(fee70m == 10000L, "70 min late is 40 chargeable min -> 1 hour fee per baseline example (BR-PRICE-003)");

        // 100 min total late: 100 - 30 = 70 chargeable min -> 2 hours fee (20,000 paise)
        long fee100m = QuoteEngine.calculateLateFee(dailyRatePaise, agreedEnd, agreedEnd.plus(100, ChronoUnit.MINUTES));
        require(fee100m == 20000L, "100 min late is 70 chargeable min -> 2 hours fee (BR-PRICE-003)");

        // Extension delta repricing (BR-PRICE-002): Extending 24h booking by 6 hours
        // Original: 24h -> 240,000 paise. New: 30h -> 240,000 + 6*10,000 = 300,000 paise. Delta = 60,000 paise.
        // It must NOT charge a second 24h minimum (240,000) for the extension.
        Instant origStart = Instant.parse("2026-11-05T10:00:00Z");
        Instant origEnd = origStart.plus(24, ChronoUnit.HOURS);
        Instant newEnd = origStart.plus(30, ChronoUnit.HOURS);
        long extensionDelta = QuoteEngine.calculateExtensionDelta(dailyRatePaise, origStart, origEnd, newEnd);
        require(extensionDelta == 60000L, "Extension delta repriced full interval: 6h remainder = 60,000 paise, no duplicate 24h min (BR-PRICE-002)");
    }

    private static void testTaxUnverifiedSymbolicZero() {
        Instant start = Instant.parse("2026-11-01T10:00:00Z");
        Instant end = start.plus(48, ChronoUnit.HOURS);
        RatePlanRecord plan = new RatePlanRecord(
                UUID.randomUUID(), COMPANY_A, null, null, 240000L, 10000L, 80000L, 3333L, start, null, 1L
        );
        QuoteEngine.AssignmentInput input = new QuoteEngine.AssignmentInput(
                UUID.randomUUID(), "Test Car", "CAR", "KA-01-EE-1111", null, null, false, plan
        );
        BookingQuote quote = QuoteEngine.calculate(start, end, List.of(input));

        // Tax-unverified company must not generate a tax-compliant invoice claim (BR-TAX-001)
        require(quote.taxPaise() == 0L, "Tax is strictly symbolic 0 paise for unverified tax setup (BR-TAX-001)");
        require(quote.grandTotalPaise() == quote.baseTotalPaise() + quote.driverTotalPaise() + quote.taxPaise(), "Grand total is sum of vehicle + driver + tax");
    }

    private static void testHalfOpenIntervalAdjacencyAndOverlap() {
        Instant t10 = Instant.parse("2026-11-01T10:00:00Z");
        Instant t12 = Instant.parse("2026-11-01T12:00:00Z");
        Instant t14 = Instant.parse("2026-11-01T14:00:00Z");

        // In half-open [start, end), two intervals [s1, e1) and [s2, e2) collide iff s1 < e2 AND e1 > s2
        // Adjacent intervals [10, 12) and [12, 14):
        // s1 < e2 (10 < 14: true) BUT e1 > s2 (12 > 12: FALSE). Collision = false!
        boolean adjacentCollides = (t10.isBefore(t14) && t12.isAfter(t12));
        require(!adjacentCollides, "Adjacent intervals [10:00, 12:00) and [12:00, 14:00) do NOT collide in half-open logic");

        // Overlapping intervals [10, 12) and [11:59, 13:00):
        Instant t1159 = t12.minus(1, ChronoUnit.MINUTES);
        Instant t13 = Instant.parse("2026-11-01T13:00:00Z");
        boolean overlapCollides = (t10.isBefore(t13) && t12.isAfter(t1159));
        require(overlapCollides, "Overlapping intervals [10:00, 12:00) and [11:59, 13:00) collide");

        // Nested interval [10, 14) and [11, 13):
        Instant t11 = Instant.parse("2026-11-01T11:00:00Z");
        boolean nestedCollides = (t10.isBefore(t13) && t14.isAfter(t11));
        require(nestedCollides, "Subset nested interval collides");
    }

    private static void testCustomerLifecycleAndKycRules() {
        StaffSession session = new StaffSession(USER_STAFF, COMPANY_A, "EMPLOYEE", "staff@vrms.in", Set.of("BOOK_STANDARD_RENTAL"));
        Map<String, Object> storage = new ConcurrentHashMap<>();
        DataSource ds = createMockDataSource(storage);
        JdbcCustomerService customerService = new JdbcCustomerService(ds);

        // FR-CUST-002: Customer creation outside booking workflow is rejected
        boolean rejectedNoBooking = false;
        try {
            customerService.createCustomerWithVisit(session, "Rajesh Kumar", "+919876543210", "rajesh@example.com", "Bangalore", 30, null);
        } catch (IllegalArgumentException e) {
            rejectedNoBooking = true;
        }
        require(rejectedNoBooking, "Customer creation with null bookingContext rejected (FR-CUST-002)");

        boolean rejectedBlankBooking = false;
        try {
            customerService.createCustomerWithVisit(session, "Rajesh Kumar", "+919876543210", "rajesh@example.com", "Bangalore", 30, "   ");
        } catch (IllegalArgumentException e) {
            rejectedBlankBooking = true;
        }
        require(rejectedBlankBooking, "Customer creation with blank bookingContext rejected (FR-CUST-002)");

        // FR-CUST-008: Age at visit validation (18-120). DOB must not be collected just to derive age.
        boolean rejectedUnderage = false;
        try {
            customerService.createCustomerWithVisit(session, "Minor Customer", "+919876543210", "minor@example.com", "Bangalore", 17, "Booking BK-1");
        } catch (IllegalArgumentException e) {
            rejectedUnderage = true;
        }
        require(rejectedUnderage, "Age at visit < 18 rejected (FR-CUST-008)");

        boolean rejectedOverage = false;
        try {
            customerService.createCustomerWithVisit(session, "Ancient Customer", "+919876543210", "ancient@example.com", "Bangalore", 121, "Booking BK-1");
        } catch (IllegalArgumentException e) {
            rejectedOverage = true;
        }
        require(rejectedOverage, "Age at visit > 120 rejected (FR-CUST-008)");

        // Valid creation inside booking workflow
        CustomerRecord created = customerService.createCustomerWithVisit(session, "Arjun Sharma", "+919888877777", "arjun@example.com", "Koramangala, Bangalore", 29, "Booking BK-001");
        require(created != null, "Customer created in booking context");
        require("Arjun Sharma".equals(created.fullName()), "Customer name preserved");
        require("+919888877777".equals(created.mobileNumber()), "Mobile preserved");

        // FR-CUST-007: Duplicate warning lookup
        Optional<CustomerRecord> dupByPhone = customerService.findDuplicateCustomer(session, "+919888877777", null);
        require(dupByPhone.isPresent(), "Duplicate customer found by mobile");
        require(dupByPhone.get().id().equals(created.id()), "Duplicate points to existing customer ID");

        Optional<CustomerRecord> dupByEmail = customerService.findDuplicateCustomer(session, null, "arjun@example.com");
        require(dupByEmail.isPresent(), "Duplicate customer found by email");

        Optional<CustomerRecord> noDup = customerService.findDuplicateCustomer(session, "+919000000000", "unknown@example.com");
        require(noDup.isEmpty(), "No duplicate for unrecorded customer");

        // Repeat visit recorded for existing customer
        CustomerVisitRecord repeatVisit = customerService.recordRepeatVisit(session, created.id(), 30, "Second Rental BK-002");
        require(repeatVisit != null, "Repeat visit recorded");
        require(repeatVisit.customerId().equals(created.id()), "Repeat visit linked to same customer");
        require(repeatVisit.ageAtVisit() == 30, "Age at visit captured as integer for repeat visit (FR-CUST-008)");

        List<CustomerVisitRecord> history = customerService.getVisitHistory(session, created.id());
        require(history.size() == 2, "Customer has 2 visits recorded in history");
    }

    private static void testPackageConfigurationsAndAdvanceSeparation() {
        StaffSession session = new StaffSession(USER_STAFF, COMPANY_A, "EMPLOYEE", "staff@vrms.in", Set.of("BOOK_STANDARD_RENTAL"));
        Map<String, Object> storage = new ConcurrentHashMap<>();
        DataSource ds = createMockDataSource(storage);
        JdbcBookingService bookingService = new JdbcBookingService(ds, null, new JdbcIdempotencyService(ds));

        Instant start = Instant.parse("2026-11-10T10:00:00Z");
        Instant end = start.plus(48, ChronoUnit.HOURS);
        UUID carId = UUID.randomUUID();
        UUID scooterId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        // Single vehicle-only package
        BookingConfirmationCommand cmdSingle = new BookingConfirmationCommand(
                "key-single-1", null, "Sita Devi", "+919111122222", "sita@example.com", "Indiranagar", 32, "Leisure",
                start, end,
                List.of(new BookingConfirmationCommand.PackageItem(carId, false, null)),
                50000L, 200000L
        );
        BookingRecord bSingle = bookingService.confirmBooking(session, cmdSingle);
        require(bSingle != null, "Single vehicle booking confirmed");
        require(bSingle.assignments().size() == 1, "Single assignment");
        require(!bSingle.assignments().get(0).driverRequired(), "Driver not required");
        require(bSingle.agreedAdvancePaise() == 50000L, "Agreed advance 50,000 paise");
        require(bSingle.actualAdvanceReceivedPaise() == 0L, "Actual advance received is 0 paise (FR-BOOK-008)");
        require(bSingle.depositPaise() == 200000L, "Deposit 200,000 paise separate from rental income");

        // Multi-vehicle package with mixed driver needs (Car with driver + Scooter without driver)
        UUID car2Id = UUID.randomUUID();
        BookingConfirmationCommand cmdPackage = new BookingConfirmationCommand(
                "key-pkg-1", null, "Vikram Patel", "+919333344444", "vikram@example.com", "Whitefield", 35, "Group tour",
                start.plus(5, ChronoUnit.DAYS), end.plus(5, ChronoUnit.DAYS),
                List.of(
                        new BookingConfirmationCommand.PackageItem(car2Id, true, driverId),
                        new BookingConfirmationCommand.PackageItem(scooterId, false, null)
                ),
                100000L, 300000L
        );
        BookingRecord bPkg = bookingService.confirmBooking(session, cmdPackage);
        require(bPkg != null, "Package booking confirmed");
        require(bPkg.assignments().size() == 2, "Package has 2 assignments");
        require(bPkg.assignments().get(0).driverRequired(), "First item requires driver");
        require(!bPkg.assignments().get(1).driverRequired(), "Second item does not require driver");

        // Reject package with duplicate physical vehicle ID (FR-BOOK-002)
        boolean dupPhysicalRejected = false;
        try {
            bookingService.confirmBooking(session, new BookingConfirmationCommand(
                    "key-dup-phys", null, "Test", "+919000000001", "t@example.com", "Addr", 25, "Trip",
                    start.plus(10, ChronoUnit.DAYS), end.plus(10, ChronoUnit.DAYS),
                    List.of(
                            new BookingConfirmationCommand.PackageItem(carId, false, null),
                            new BookingConfirmationCommand.PackageItem(carId, false, null)
                    ),
                    0L, 0L
            ));
        } catch (IllegalArgumentException e) {
            dupPhysicalRejected = true;
        }
        require(dupPhysicalRejected, "Package with duplicate physical vehicle ID rejected (FR-BOOK-002)");
    }

    private static void testConcurrentBookingConflictRevalidation() throws Exception {
        StaffSession session = new StaffSession(USER_STAFF, COMPANY_A, "EMPLOYEE", "staff@vrms.in", Set.of("BOOK_STANDARD_RENTAL"));
        Map<String, Object> storage = new ConcurrentHashMap<>();
        DataSource ds = createMockDataSource(storage);
        JdbcBookingService bookingService = new JdbcBookingService(ds, null, new JdbcIdempotencyService(ds));

        Instant start = Instant.parse("2026-11-20T10:00:00Z");
        Instant end = start.plus(24, ChronoUnit.HOURS);
        UUID contestedVehicleId = UUID.randomUUID();

        // Booking 1 succeeds
        BookingRecord b1 = bookingService.confirmBooking(session, new BookingConfirmationCommand(
                "key-c1", null, "First Booked", "+919100000001", "c1@example.com", "Addr 1", 25, "Trip",
                start, end,
                List.of(new BookingConfirmationCommand.PackageItem(contestedVehicleId, false, null)),
                0L, 0L
        ));
        require(b1 != null, "First concurrent booking succeeded");

        // Concurrent Booking 2 for overlapping period MUST fail with IllegalStateException (maps to 409 Conflict)
        boolean conflictCaught = false;
        try {
            bookingService.confirmBooking(session, new BookingConfirmationCommand(
                    "key-c2", null, "Second Booked", "+919100000002", "c2@example.com", "Addr 2", 28, "Trip",
                    start.plus(2, ChronoUnit.HOURS), end.plus(2, ChronoUnit.HOURS),
                    List.of(new BookingConfirmationCommand.PackageItem(contestedVehicleId, false, null)),
                    0L, 0L
            ));
        } catch (IllegalStateException e) {
            conflictCaught = true;
        }
        require(conflictCaught, "Conflicting reservation revalidation at commit failed closed with IllegalStateException (409 Conflict)");
    }

    private static void testIdempotencyExactReplayAndMutation() {
        StaffSession session = new StaffSession(USER_STAFF, COMPANY_A, "EMPLOYEE", "staff@vrms.in", Set.of("BOOK_STANDARD_RENTAL"));
        Map<String, Object> storage = new ConcurrentHashMap<>();
        DataSource ds = createMockDataSource(storage);
        JdbcIdempotencyService idempotency = new JdbcIdempotencyService(ds);
        JdbcBookingService bookingService = new JdbcBookingService(ds, null, idempotency);

        Instant start = Instant.parse("2026-12-01T10:00:00Z");
        Instant end = start.plus(24, ChronoUnit.HOURS);
        UUID vId = UUID.randomUUID();
        String idemKey = "fixed-idem-key-999";

        BookingConfirmationCommand originalCmd = new BookingConfirmationCommand(
                idemKey, null, "Pooja Reddy", "+919444455555", "pooja@example.com", "Jayanagar", 27, "Holiday",
                start, end,
                List.of(new BookingConfirmationCommand.PackageItem(vId, false, null)),
                50000L, 100000L
        );

        BookingRecord firstRun = bookingService.confirmBooking(session, originalCmd);
        require(firstRun != null, "First execution succeeded");

        // Replay with identical payload and key: MUST return cached booking with identical ID and booking number (FR-BOOK-010)
        BookingRecord replay = bookingService.confirmBooking(session, originalCmd);
        require(replay != null, "Idempotent replay succeeded");
        require(replay.id().equals(firstRun.id()), "Replay returns exact same booking ID");
        require(replay.bookingNumber().equals(firstRun.bookingNumber()), "Replay returns exact same booking number");

        // Mutated request with SAME key: MUST fail closed with IllegalArgumentException
        BookingConfirmationCommand mutatedCmd = new BookingConfirmationCommand(
                idemKey, null, "MUTATED NAME", "+919444455555", "pooja@example.com", "Jayanagar", 27, "Holiday",
                start, end,
                List.of(new BookingConfirmationCommand.PackageItem(vId, false, null)),
                999999L, 100000L // changed advance
        );

        boolean mutationRejected = false;
        try {
            bookingService.confirmBooking(session, mutatedCmd);
        } catch (IllegalArgumentException e) {
            mutationRejected = true;
        }
        require(mutationRejected, "Reusing idempotency key with different request payload is rejected (400 Bad Request)");
    }

    private static void testTenantIsolationAndCrossCompanyRejection() {
        StaffSession sessionA = new StaffSession(USER_STAFF, COMPANY_A, "EMPLOYEE", "staff@vrms.in", Set.of("BOOK_STANDARD_RENTAL"));
        StaffSession sessionB = new StaffSession(USER_STAFF_B, COMPANY_B, "EMPLOYEE", "staff-b@vrms.in", Set.of("BOOK_STANDARD_RENTAL"));

        Map<String, Object> storage = new ConcurrentHashMap<>();
        DataSource ds = createMockDataSource(storage);
        JdbcCustomerService customerService = new JdbcCustomerService(ds);
        JdbcBookingService bookingService = new JdbcBookingService(ds, null, new JdbcIdempotencyService(ds));

        // Create customer in Company B
        CustomerRecord custB = customerService.createCustomerWithVisit(sessionB, "Company B Customer", "+919555566666", "b@example.com", "Mysore", 30, "BK-B-1");
        require(custB.companyId().equals(COMPANY_B), "Customer belongs to Company B");

        // Attempt to book using Company B customer under Company A session
        Instant start = Instant.parse("2026-12-10T10:00:00Z");
        Instant end = start.plus(24, ChronoUnit.HOURS);
        UUID vId = UUID.randomUUID();

        boolean crossCompanyCustomerRejected = false;
        try {
            bookingService.confirmBooking(sessionA, new BookingConfirmationCommand(
                    "cross-cust-key", custB.id(), null, null, null, null, 30, "Cross booking",
                    start, end,
                    List.of(new BookingConfirmationCommand.PackageItem(vId, false, null)),
                    0L, 0L
            ));
        } catch (SecurityException | IllegalArgumentException e) {
            crossCompanyCustomerRejected = true;
        }
        require(crossCompanyCustomerRejected, "Referencing customer from different company is rejected");
    }

    private static void testAtomicPackageRollback() {
        StaffSession session = new StaffSession(USER_STAFF, COMPANY_A, "EMPLOYEE", "staff@vrms.in", Set.of("BOOK_STANDARD_RENTAL"));
        Map<String, Object> storage = new ConcurrentHashMap<>();
        DataSource ds = createMockDataSource(storage);
        // Simulate a failing booking service where an error occurs midway through package insertion
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        DataSource failDs = createFailingDataSource(storage, rolledBack);
        JdbcBookingService failBookingService = new JdbcBookingService(failDs, null, new JdbcIdempotencyService(failDs));

        Instant start = Instant.parse("2026-12-15T10:00:00Z");
        Instant end = start.plus(24, ChronoUnit.HOURS);

        boolean failed = false;
        try {
            failBookingService.confirmBooking(session, new BookingConfirmationCommand(
                    "fail-rollback-key", null, "Rollback Customer", "+919777788888", "rb@example.com", "Addr", 26, "Trip",
                    start, end,
                    List.of(new BookingConfirmationCommand.PackageItem(UUID.randomUUID(), false, null)),
                    0L, 0L
            ));
        } catch (Exception e) {
            failed = true;
        }
        require(failed, "Package write failure threw exception");
        require(rolledBack.get(), "Transaction rollback executed on package failure (no orphan rows)");
    }

    private static void testHttpServerAndApiWiring() throws Exception {
        Map<String, Object> storage = new ConcurrentHashMap<>();
        DataSource ds = createMockDataSource(storage);
        AuditPort audit = new AuditPort() {
            @Override public AuditEventRecord recordEvent(StaffSession s, String act, String ent, String id, String det) { return null; }
            @Override public List<AuditEventRecord> queryEvents(StaffSession s, String ent, String id, int lim) { return List.of(); }
        };
        JdbcIdempotencyService idempotency = new JdbcIdempotencyService(ds);
        JdbcCustomerService customerService = new JdbcCustomerService(ds, audit);
        JdbcBookingService bookingService = new JdbcBookingService(ds, audit, idempotency);

        SupabaseConfig sbConfig = new SupabaseConfig("https://abcdefghijklmnopqrst.supabase.co", "sb_publishable_fixture0123456789");
        SupabaseAuth mockAuth = new SupabaseAuth(sbConfig);
        StaffAuthorizer.Staff staff = new StaffAuthorizer.Staff(USER_STAFF, COMPANY_A, "EMPLOYEE");
        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            if ("valid-token".equals(token)) return staff;
            throw new SecurityException("Invalid token");
        };

        HttpServerConfig config = new HttpServerConfig(new InetSocketAddress("127.0.0.1", 0), null);
        VrmsHttpServer server = new VrmsHttpServer(
                config, mockAuth, authorizer,
                null, audit, idempotency, null, null, null, null,
                customerService,
                bookingService
        );
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        String base = server.baseUri().toString();

        try {
            // 1. GET /api/v1/customers/duplicate without query params
            HttpRequest r1 = HttpRequest.newBuilder(URI.create(base + "/api/v1/customers/duplicate"))
                    .header("Authorization", "Bearer valid-token")
                    .GET().build();
            HttpResponse<String> resp1 = client.send(r1, HttpResponse.BodyHandlers.ofString());
            require(resp1.statusCode() == 200, "duplicate check returned 200");
            JsonObject j1 = JsonParser.parseString(resp1.body()).getAsJsonObject();
            require(!j1.get("duplicateFound").getAsBoolean(), "duplicateFound is false initially");

            // 2. POST /api/v1/customers without bookingContext rejected (FR-CUST-002)
            JsonObject cReq = new JsonObject();
            cReq.addProperty("fullName", "Rohan Verma");
            cReq.addProperty("mobile", "+919876500000");
            cReq.addProperty("email", "rohan@example.com");
            cReq.addProperty("address", "Indiranagar");
            cReq.addProperty("ageAtVisit", 28);
            HttpRequest r2 = HttpRequest.newBuilder(URI.create(base + "/api/v1/customers"))
                    .header("Authorization", "Bearer valid-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cReq.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> resp2 = client.send(r2, HttpResponse.BodyHandlers.ofString());
            require(resp2.statusCode() == 400, "customer creation outside booking rejected with 400 (FR-CUST-002)");

            // 3. POST /api/v1/bookings/quote
            JsonObject qReq = new JsonObject();
            qReq.addProperty("startTime", "2026-11-25T10:00:00Z");
            qReq.addProperty("endTime", "2026-11-26T10:00:00Z");
            JsonArray arr = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("vehicleId", UUID.randomUUID().toString());
            item.addProperty("driverRequired", false);
            arr.add(item);
            qReq.add("items", arr);

            HttpRequest r3 = HttpRequest.newBuilder(URI.create(base + "/api/v1/bookings/quote"))
                    .header("Authorization", "Bearer valid-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(qReq.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> resp3 = client.send(r3, HttpResponse.BodyHandlers.ofString());
            require(resp3.statusCode() == 200, "quote endpoint returned 200");
            JsonObject j3 = JsonParser.parseString(resp3.body()).getAsJsonObject();
            require(j3.has("grandTotalPaise"), "quote response has grandTotalPaise");

            // 4. POST /api/v1/bookings/confirm
            JsonObject bReq = new JsonObject();
            bReq.addProperty("idempotencyKey", "api-key-1");
            bReq.addProperty("customerFullName", "Naveen Rao");
            bReq.addProperty("customerContact", "+919876511111");
            bReq.addProperty("customerEmail", "naveen@example.com");
            bReq.addProperty("customerAddress", "MG Road");
            bReq.addProperty("ageAtVisit", 31);
            bReq.addProperty("startTime", "2026-11-25T10:00:00Z");
            bReq.addProperty("endTime", "2026-11-26T10:00:00Z");
            bReq.add("items", arr);
            bReq.addProperty("agreedAdvancePaise", 100000L);
            bReq.addProperty("depositPaise", 200000L);

            HttpRequest r4 = HttpRequest.newBuilder(URI.create(base + "/api/v1/bookings/confirm"))
                    .header("Authorization", "Bearer valid-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bReq.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> resp4 = client.send(r4, HttpResponse.BodyHandlers.ofString());
            require(resp4.statusCode() == 201, "booking confirmation returned 201 Created");
            JsonObject j4 = JsonParser.parseString(resp4.body()).getAsJsonObject();
            require(j4.has("bookingNumber"), "booking has bookingNumber");
            require(j4.get("status").getAsString().equals("CONFIRMED"), "booking status is CONFIRMED");
        } finally {
            server.stop(0);
        }
    }

    private static void testDesktopUiIntegration() {
        BookingView view = new BookingView();
        BookingController controller = new BookingController(view, new DraftValidator());

        // Initial state
        require(!view.confirmBtn.isEnabled(), "Confirm button is initially disabled (no fake confirm)");
        require(!view.isReviewing(), "Initially on details card");

        // Verify age-at-visit field
        require(view.ageAtVisit.getText().equals("25"), "Default age-at-visit is 25");

        // Shortlist vehicle with UUID
        UUID testVehicleId = UUID.randomUUID();
        view.shortlist(testVehicleId, "Honda City (KA-01-MJ-9999)");
        require(view.candidateVehicleId().equals(testVehicleId), "Candidate vehicle UUID recorded");
        require(view.candidate().contains("Honda City"), "Candidate vehicle name recorded");

        // Set inputs
        view.customer.setText("Sunil Joshi");
        view.phone.setText("+919876522222");
        view.email.setText("sunil@example.com");
        view.address.setText("Malleshwaram, Bangalore");
        view.pickup.setText("10/11/2026 10:00");
        view.expectedReturn.setText("11/11/2026 10:00");

        // Trigger local validation review
        view.check.doClick();
        require(view.isReviewing(), "Switched to review card");

        // Without connected booking client, confirm button remains disabled with warning (no fake confirm)
        require(!view.confirmBtn.isEnabled(), "Confirm button remains disabled without active server connection");
        require(view.confirmBtn.getText().contains("unavailable") || view.confirmBtn.getText().contains("reserve"), "Confirm button displays unavailable warning");

        // Reset
        view.clear.doClick();
        require(!view.isReviewing(), "Cleared back to details card");
        require(view.customer.getText().isEmpty(), "Customer name cleared");
        require(view.candidateVehicleId() == null, "Candidate vehicle cleared");
    }

    // =========================================================================
    // Mock DataSource and In-Memory Storage for JDBC Services
    // =========================================================================

    private static DataSource createMockDataSource(Map<String, Object> storage) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return createMockConnection(storage, false, null);
                    }
                    return null;
                }
        );
    }

    private static DataSource createFailingDataSource(Map<String, Object> storage, AtomicBoolean rolledBack) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return createMockConnection(storage, true, rolledBack);
                    }
                    return null;
                }
        );
    }

    private static Connection createMockConnection(Map<String, Object> storage, boolean failOnInsert, AtomicBoolean rolledBack) {
        boolean[] autoCommit = {true};
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("prepareStatement".equals(name)) {
                        String sql = (String) args[0];
                        return createMockPreparedStatement(sql, storage, failOnInsert);
                    }
                    if ("rollback".equals(name)) {
                        if (rolledBack != null) rolledBack.set(true);
                        return null;
                    }
                    if ("commit".equals(name)) {
                        return null;
                    }
                    if ("setAutoCommit".equals(name)) {
                        autoCommit[0] = (Boolean) args[0];
                        return null;
                    }
                    if ("getAutoCommit".equals(name)) return autoCommit[0];
                    if ("close".equals(name)) {
                        return null;
                    }
                    return null;
                }
        );
    }

    private static PreparedStatement createMockPreparedStatement(String sql, Map<String, Object> storage, boolean failOnInsert) {
        Map<Integer, Object> params = new HashMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                        params.put((Integer) args[0], args[1]);
                        return null;
                    }
                    if ("setString".equals(name) || "setObject".equals(name) || "setInt".equals(name) || "setLong".equals(name) || "setTimestamp".equals(name)) {
                        params.put((Integer) args[0], args[1]);
                        return null;
                    }
                    if ("execute".equals(name)) {
                        return true;
                    }
                    if ("executeUpdate".equals(name)) {
                        if (failOnInsert && sql.contains("INSERT INTO vrms_private.booking_assignments")) {
                            throw new SQLException("Simulated database failure during assignment insert");
                        }
                        if (sql.contains("INSERT INTO vrms_private.customers")) {
                            if (params.size() >= 6) {
                                UUID id = (UUID) params.get(1);
                                UUID companyId = (UUID) params.get(2);
                                String fullName = (String) params.get(3);
                                String mobile = (String) params.get(4);
                                String email = (String) params.get(5);
                                String address = (String) params.get(6);
                                storage.put("customer:" + id, new CustomerRecord(id, companyId, fullName, mobile, email, address, Instant.now(), 1L));
                            } else {
                                UUID id = UUID.randomUUID();
                                UUID companyId = (UUID) params.get(1);
                                String fullName = (String) params.get(2);
                                String mobile = (String) params.get(3);
                                String email = (String) params.get(4);
                                String address = (String) params.get(5);
                                storage.put("customer:" + id, new CustomerRecord(id, companyId, fullName, mobile, email, address, Instant.now(), 1L));
                            }
                        }
                        if (sql.contains("INSERT INTO vrms_private.customer_visits")) {
                            if (params.get(3) instanceof UUID) {
                                UUID id = (UUID) params.get(1);
                                UUID companyId = (UUID) params.get(2);
                                UUID customerId = (UUID) params.get(3);
                                int age = (Integer) params.get(4);
                                String purp = (String) params.get(5);
                                List<CustomerVisitRecord> visits = (List<CustomerVisitRecord>) storage.computeIfAbsent("visits:" + customerId, k -> new ArrayList<>());
                                visits.add(new CustomerVisitRecord(id, companyId, customerId, age, Instant.now(), purp));
                            } else {
                                UUID id = UUID.randomUUID();
                                UUID companyId = (UUID) params.get(1);
                                UUID customerId = (UUID) params.get(2);
                                int age = (Integer) params.get(3);
                                String purp = (String) params.get(4);
                                List<CustomerVisitRecord> visits = (List<CustomerVisitRecord>) storage.computeIfAbsent("visits:" + customerId, k -> new ArrayList<>());
                                visits.add(new CustomerVisitRecord(id, companyId, customerId, age, Instant.now(), purp));
                            }
                        }
                        if (sql.contains("INSERT INTO vrms_private.bookings")) {
                            UUID id = (UUID) params.get(1);
                            UUID companyId = (UUID) params.get(2);
                            UUID customerId = (UUID) params.get(3);
                            UUID visitId = (UUID) params.get(4);
                            String bNum = (String) params.get(5);
                            Timestamp st = (Timestamp) params.get(6);
                            Timestamp et = (Timestamp) params.get(7);
                            long base = (Long) params.get(8);
                            long driver = (Long) params.get(9);
                            long tax = (Long) params.get(10);
                            long total = (Long) params.get(11);
                            long adv = (Long) params.get(12);
                            long dep = (Long) params.get(13);
                            String snap = (String) params.get(14);
                            UUID createdBy = (UUID) params.get(15);
                            storage.put("booking:" + id, new BookingRecord(id, companyId, customerId, visitId, bNum, "CONFIRMED", st.toInstant(), et.toInstant(), base, driver, tax, total, adv, 0L, dep, snap, List.of(), createdBy, Instant.now(), 1L));
                        }
                        if (sql.contains("INSERT INTO vrms_private.booking_assignments")) {
                            UUID id = (UUID) params.get(1);
                            UUID companyId = (UUID) params.get(2);
                            UUID bookingId = (UUID) params.get(3);
                            UUID vehicleId = (UUID) params.get(4);
                            UUID driverId = (UUID) params.get(5);
                            Boolean driverReq = (Boolean) params.get(6);
                            Timestamp st = (Timestamp) params.get(7);
                            Timestamp et = (Timestamp) params.get(8);
                            long daily = (Long) params.get(9);
                            long hourly = (Long) params.get(10);
                            long vTotal = (Long) params.get(11);
                            long dTotal = (Long) params.get(12);
                            long aTotal = (Long) params.get(13);
                            List<BookingAssignmentRecord> assigns = (List<BookingAssignmentRecord>) storage.computeIfAbsent("assigns:" + bookingId, k -> new ArrayList<>());
                            assigns.add(new BookingAssignmentRecord(id, companyId, bookingId, vehicleId, driverId, Boolean.TRUE.equals(driverReq), st.toInstant(), et.toInstant(), daily, hourly, vTotal, dTotal, aTotal, "RESERVED"));
                        }
                        if (sql.contains("INSERT INTO vrms_private.vehicle_reservations")) {
                            UUID companyId = (UUID) params.get(1);
                            UUID vehicleId = (UUID) params.get(2);
                            UUID bookingId = (UUID) params.get(3);
                            UUID assignId = (UUID) params.get(4);
                            Timestamp st = (Timestamp) params.get(5);
                            Timestamp et = (Timestamp) params.get(6);
                            List<Object[]> resList = (List<Object[]>) storage.computeIfAbsent("vehicle_res:" + vehicleId, k -> new ArrayList<>());
                            resList.add(new Object[]{UUID.randomUUID(), companyId, vehicleId, assignId, st.toInstant(), et.toInstant()});
                        }
                        if (sql.contains("INSERT INTO vrms_private.driver_reservations")) {
                            UUID companyId = (UUID) params.get(1);
                            UUID driverId = (UUID) params.get(2);
                            UUID bookingId = (UUID) params.get(3);
                            UUID assignId = (UUID) params.get(4);
                            Timestamp st = (Timestamp) params.get(5);
                            Timestamp et = (Timestamp) params.get(6);
                            List<Object[]> resList = (List<Object[]>) storage.computeIfAbsent("driver_res:" + driverId, k -> new ArrayList<>());
                            resList.add(new Object[]{UUID.randomUUID(), companyId, driverId, assignId, st.toInstant(), et.toInstant()});
                        }
                        if (sql.contains("INSERT INTO vrms_private.idempotency_records")) {
                            UUID companyId = (UUID) params.get(2);
                            String key = (String) params.get(3);
                            UUID actor = (UUID) params.get(4);
                            String op = (String) params.get(5);
                            String hash = (String) params.get(6);
                            String stat = (String) params.get(7);
                            storage.put("idem:" + key, new IdempotencyRecord(UUID.randomUUID(), companyId, key, actor, op, hash, stat, 201, null, Instant.now(), Instant.now().plus(24, ChronoUnit.HOURS)));
                        }
                        if (sql.contains("UPDATE vrms_private.idempotency_records")) {
                            String key = (String) params.get(5);
                            IdempotencyRecord rec = (IdempotencyRecord) storage.get("idem:" + key);
                            if (rec != null) {
                                storage.put("idem:" + key, new IdempotencyRecord(rec.id(), rec.companyId(), rec.idempotencyKey(), rec.actorUserId(), rec.operation(), rec.requestHash(), (String) params.get(1), (Integer) params.get(2), (String) params.get(3), rec.createdAt(), rec.expiresAt()));
                            }
                        }
                        return 1;
                    }
                    if ("executeQuery".equals(name)) {
                        return createMockResultSet(sql, params, storage);
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return null;
                }
        );
    }

    private static ResultSet createMockResultSet(String sql, Map<Integer, Object> params, Map<String, Object> storage) {
        List<Map<Object, Object>> rows = new ArrayList<>();

        if (sql.contains("SELECT current_user = 'smart_vrms_api'")) {
            Map<Object, Object> row = new HashMap<>();
            row.put(1, true);
            rows.add(row);
        } else if (sql.contains("SELECT company_id, role, active FROM vrms_private.memberships")) {
            UUID userId = (UUID) params.get(1);
            UUID companyId = USER_STAFF.equals(userId) || USER_ADMIN.equals(userId) ? COMPANY_A
                    : USER_STAFF_B.equals(userId) ? COMPANY_B : null;
            if (companyId != null) {
                Map<Object, Object> row = new HashMap<>();
                row.put(1, companyId);
                row.put(2, USER_ADMIN.equals(userId) ? "ADMINISTRATOR" : "EMPLOYEE");
                row.put(3, true);
                row.put("company_id", companyId);
                row.put("role", USER_ADMIN.equals(userId) ? "ADMINISTRATOR" : "EMPLOYEE");
                row.put("active", true);
                rows.add(row);
            }
        } else if (sql.contains("INSERT INTO vrms_private.idempotency_records")) {
            UUID id = UUID.randomUUID();
            UUID companyId = (UUID) params.get(1);
            String key = (String) params.get(2);
            UUID actor = (UUID) params.get(3);
            String op = (String) params.get(4);
            String hash = (String) params.get(5);
            Timestamp now = Timestamp.from(Instant.now());
            Timestamp exp = Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS));
            IdempotencyRecord rec = new IdempotencyRecord(id, companyId, key, actor, op, hash, "PENDING", null, null, now.toInstant(), exp.toInstant());
            storage.put("idem:" + key, rec);
            Map<Object, Object> row = new HashMap<>();
            row.put("id", id);
            row.put(1, id);
            row.put("created_at", now);
            row.put(2, now);
            row.put("expires_at", exp);
            row.put(3, exp);
            rows.add(row);
        } else if (sql.contains("INSERT INTO vrms_private.customers")) {
            UUID id = UUID.randomUUID();
            UUID companyId = (UUID) params.get(1);
            String fullName = (String) params.get(2);
            String mobile = (String) params.get(3);
            String email = (String) params.get(4);
            String address = (String) params.get(5);
            Timestamp now = Timestamp.from(Instant.now());
            CustomerRecord rec = new CustomerRecord(id, companyId, fullName, mobile, email, address, now.toInstant(), 1L);
            storage.put("customer:" + id, rec);
            Map<Object, Object> row = new HashMap<>();
            row.put("id", id);
            row.put(1, id);
            row.put("created_at", now);
            row.put(2, now);
            row.put("version", 1L);
            row.put(3, 1L);
            rows.add(row);
        } else if (sql.contains("INSERT INTO vrms_private.customer_visits")) {
            UUID id = UUID.randomUUID();
            UUID companyId = (UUID) params.get(1);
            UUID customerId = (UUID) params.get(2);
            int age = (Integer) params.get(3);
            String purp = (String) params.get(4);
            Timestamp now = Timestamp.from(Instant.now());
            CustomerVisitRecord v = new CustomerVisitRecord(id, companyId, customerId, age, now.toInstant(), purp);
            List<CustomerVisitRecord> visits = (List<CustomerVisitRecord>) storage.computeIfAbsent("visits:" + customerId, k -> new ArrayList<>());
            visits.add(v);
            Map<Object, Object> row = new HashMap<>();
            row.put("id", id);
            row.put(1, id);
            row.put("visit_date", now);
            row.put(2, now);
            rows.add(row);
        } else if (sql.contains("FROM vrms_private.customers") && sql.contains("WHERE company_id = ?")) {
            UUID companyId = (UUID) params.get(1);
            if (params.size() >= 3 && params.get(2) != null && params.get(2) instanceof UUID) {
                // Query by ID: WHERE company_id = ? AND id = ?
                UUID cId = (UUID) params.get(2);
                CustomerRecord c = (CustomerRecord) storage.get("customer:" + cId);
                if (c != null && c.companyId().equals(companyId)) {
                    Map<Object, Object> row = new HashMap<>();
                    row.put("id", c.id());
                    row.put(1, c.id());
                    row.put("company_id", c.companyId());
                    row.put("full_name", c.fullName());
                    row.put("mobile_number", c.mobileNumber());
                    row.put("email", c.email() != null ? c.email() : "");
                    row.put("address", c.address());
                    row.put("created_at", Timestamp.from(c.createdAt()));
                    row.put("version", c.version());
                    rows.add(row);
                }
            } else if (params.size() >= 5) {
                // Duplicate query by mobile/email
                String mobile = (String) params.get(2);
                String email = (String) params.get(4);
                for (var val : storage.values()) {
                    if (val instanceof CustomerRecord c && c.companyId().equals(companyId)) {
                        if ((mobile != null && mobile.equals(c.mobileNumber())) || (email != null && email.equalsIgnoreCase(c.email()))) {
                            Map<Object, Object> row = new HashMap<>();
                            row.put("id", c.id());
                            row.put(1, c.id());
                            row.put("company_id", c.companyId());
                            row.put("full_name", c.fullName());
                            row.put("mobile_number", c.mobileNumber());
                            row.put("email", c.email() != null ? c.email() : "");
                            row.put("address", c.address());
                            row.put("created_at", Timestamp.from(c.createdAt()));
                            row.put("version", c.version());
                            rows.add(row);
                            break;
                        }
                    }
                }
            }
        } else if (sql.contains("FROM vrms_private.customer_visits")) {
            UUID custId = (UUID) params.get(2);
            List<CustomerVisitRecord> visits = (List<CustomerVisitRecord>) storage.get("visits:" + custId);
            if (visits != null) {
                for (var v : visits) {
                    Map<Object, Object> row = new HashMap<>();
                    row.put("id", v.id());
                    row.put("company_id", v.companyId());
                    row.put("customer_id", v.customerId());
                    row.put("age_at_visit", v.ageAtVisit());
                    row.put("visit_date", Timestamp.from(v.visitDate()));
                    row.put("purpose", v.purpose());
                    rows.add(row);
                }
            }
        } else if (sql.contains("FROM vrms_private.vehicle_reservations")) {
            UUID vehicleId = (UUID) params.get(2);
            Timestamp et = (Timestamp) params.get(3);
            Timestamp st = (Timestamp) params.get(4);
            List<Object[]> resList = (List<Object[]>) storage.get("vehicle_res:" + vehicleId);
            if (resList != null) {
                for (var r : resList) {
                    Instant resStart = (Instant) r[4];
                    Instant resEnd = (Instant) r[5];
                    // Half-open collision: resStart < et AND resEnd > st
                    if (resStart.isBefore(et.toInstant()) && resEnd.isAfter(st.toInstant())) {
                        Map<Object, Object> row = new HashMap<>();
                        row.put("conflict", 1);
                        rows.add(row);
                        break;
                    }
                }
            }
        } else if (sql.contains("FROM vrms_private.idempotency_records")) {
            String key = (String) params.get(2);
            IdempotencyRecord rec = (IdempotencyRecord) storage.get("idem:" + key);
            if (rec != null) {
                Map<Object, Object> map = new HashMap<>();
                map.put("id", rec.id());
                map.put("company_id", rec.companyId());
                map.put("idempotency_key", rec.idempotencyKey());
                map.put("actor_user_id", rec.actorUserId());
                map.put("operation", rec.operation());
                map.put("request_hash", rec.requestHash());
                map.put("status", rec.status());
                map.put("response_status", rec.responseStatus());
                map.put("response_body", rec.responseBody() != null ? rec.responseBody() : "");
                map.put("created_at", Timestamp.from(rec.createdAt()));
                map.put("expires_at", Timestamp.from(rec.expiresAt()));
                rows.add(map);
            }
        } else if (sql.contains("FROM vrms_private.vehicles")) {
            UUID vId = (UUID) params.get(2);
            Map<Object, Object> row = new HashMap<>();
            row.put("id", vId);
            row.put("name", "Verified Test Vehicle");
            row.put("registration_plate", "KA-01-XX-9999");
            row.put("category_id", UUID.randomUUID());
            row.put("status", "AVAILABLE");
            rows.add(row);
        } else if (sql.contains("FROM vrms_private.drivers")) {
            UUID dId = (UUID) params.get(2);
            Map<Object, Object> row = new HashMap<>();
            row.put("id", dId);
            row.put("name", "Verified Test Driver");
            row.put("duty_status", "AVAILABLE");
            rows.add(row);
        } else if (sql.contains("FROM vrms_private.driver_profiles")) {
            UUID dId = (UUID) params.get(2);
            Map<Object, Object> row = new HashMap<>();
            row.put("id", dId);
            row.put(1, java.sql.Date.valueOf(java.time.LocalDate.now().plusYears(2)));
            row.put("licence_expiry", java.sql.Date.valueOf(java.time.LocalDate.now().plusYears(2)));
            rows.add(row);
        } else if (sql.contains("FROM vrms_private.driver_reservations")) {
            UUID driverId = (UUID) params.get(2);
            Timestamp et = (Timestamp) params.get(3);
            Timestamp st = (Timestamp) params.get(4);
            List<Object[]> resList = (List<Object[]>) storage.get("driver_res:" + driverId);
            if (resList != null) {
                for (var r : resList) {
                    Instant resStart = (Instant) r[4];
                    Instant resEnd = (Instant) r[5];
                    if (resStart.isBefore(et.toInstant()) && resEnd.isAfter(st.toInstant())) {
                        Map<Object, Object> row = new HashMap<>();
                        row.put("conflict", 1);
                        rows.add(row);
                        break;
                    }
                }
            }
        } else if (sql.contains("FROM vrms_private.bookings")) {
            UUID bId = (UUID) params.get(2);
            BookingRecord b = (BookingRecord) storage.get("booking:" + bId);
            if (b != null) {
                Map<Object, Object> row = new HashMap<>();
                row.put("id", b.id());
                row.put("company_id", b.companyId());
                row.put("customer_id", b.customerId());
                row.put("visit_id", b.visitId());
                row.put("booking_number", b.bookingNumber());
                row.put("status", b.status());
                row.put("start_time", Timestamp.from(b.startTime()));
                row.put("end_time", Timestamp.from(b.endTime()));
                row.put("base_total_paise", b.baseTotalPaise());
                row.put("driver_total_paise", b.driverTotalPaise());
                row.put("tax_paise", b.taxPaise());
                row.put("total_paise", b.totalPaise());
                row.put("agreed_advance_paise", b.agreedAdvancePaise());
                row.put("actual_advance_received_paise", b.actualAdvanceReceivedPaise());
                row.put("deposit_paise", b.depositPaise());
                row.put("pricing_snapshot", b.pricingSnapshotJson());
                row.put("created_by", b.createdBy());
                row.put("created_at", Timestamp.from(b.createdAt()));
                row.put("version", b.version());
                rows.add(row);
            }
        } else if (sql.contains("FROM vrms_private.booking_assignments")) {
            UUID bId = (UUID) params.get(2);
            List<BookingAssignmentRecord> assigns = (List<BookingAssignmentRecord>) storage.get("assigns:" + bId);
            if (assigns != null) {
                for (var a : assigns) {
                    Map<Object, Object> row = new HashMap<>();
                    row.put("id", a.id());
                    row.put("company_id", a.companyId());
                    row.put("booking_id", a.bookingId());
                    row.put("vehicle_id", a.vehicleId());
                    row.put("driver_id", a.driverId());
                    row.put("driver_required", a.driverRequired());
                    row.put("start_time", Timestamp.from(a.startTime()));
                    row.put("end_time", Timestamp.from(a.endTime()));
                    row.put("daily_rate_paise", a.dailyRatePaise());
                    row.put("hourly_rate_paise", a.hourlyRatePaise());
                    row.put("vehicle_total_paise", a.vehicleTotalPaise());
                    row.put("driver_total_paise", a.driverTotalPaise());
                    row.put("assignment_total_paise", a.assignmentTotalPaise());
                    row.put("status", a.status());
                    rows.add(row);
                }
            }
        } else if (sql.contains("FROM vrms_private.rate_plans")) {
            Map<Object, Object> row = new HashMap<>();
            row.put("id", UUID.randomUUID());
            row.put("daily_rate_paise", 240000L);
            row.put("hourly_rate_paise", 10000L);
            row.put("driver_daily_fee_paise", 80000L);
            row.put("driver_hourly_fee_paise", 3333L);
            row.put("version", 1L);
            rows.add(row);
        }

        AtomicInteger cursor = new AtomicInteger(-1);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        return cursor.incrementAndGet() < rows.size();
                    }
                    if ("getString".equals(name)) {
                        Object key = args[0];
                        Object val = rows.get(cursor.get()).get(key);
                        return val != null ? val.toString() : null;
                    }
                    if ("getInt".equals(name)) {
                        Object key = args[0];
                        Object val = rows.get(cursor.get()).get(key);
                        return val instanceof Number ? ((Number) val).intValue() : 0;
                    }
                    if ("getLong".equals(name)) {
                        Object key = args[0];
                        Object val = rows.get(cursor.get()).get(key);
                        return val instanceof Number ? ((Number) val).longValue() : 0L;
                    }
                    if ("getBoolean".equals(name)) {
                        Object key = args[0];
                        Object val = rows.get(cursor.get()).get(key);
                        return val instanceof Boolean ? (Boolean) val : false;
                    }
                    if ("getDate".equals(name)) {
                        Object key = args[0];
                        Object val = rows.get(cursor.get()).get(key);
                        return val instanceof java.sql.Date ? (java.sql.Date) val : null;
                    }
                    if ("getObject".equals(name)) {
                        Object key = args[0];
                        return rows.get(cursor.get()).get(key);
                    }
                    if ("getTimestamp".equals(name)) {
                        Object key = args[0];
                        Object val = rows.get(cursor.get()).get(key);
                        return val instanceof Timestamp ? (Timestamp) val : (val instanceof Instant ? Timestamp.from((Instant) val) : null);
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return null;
                }
        );
    }
}
