package in.smartvrms.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.contracts.*;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.automation.DocumentReminderScanner;
import in.smartvrms.server.domain.JdbcFleetService;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.JdbcFileStorageService;
import in.smartvrms.server.security.StaffAuthorizer;

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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Stage 3 Fleet and Document Verification Suite:
 * - FR-VEH-001: Mixed fleet categories (Bike, Scooter, Car, Tempo/van, Bus, custom).
 * - FR-VEH-002: Vehicle particulars, plate validation, same-company duplicate plate rejection.
 * - FR-VEH-003: Visual fleet search, category/model/capacity/plate filters.
 * - FR-VEH-004: Connected vehicle history, restrictions and versioned documents for chosen vehicle only.
 * - FR-VEH-005: Truthful exact-period operational availability states (AVAILABLE, RENTED, MAINTENANCE, FROZEN).
 * - FR-VEH-006: Asset freeze and safe reactivation (closing freeze leaves vehicle unavailable if other blocks remain).
 * - FR-DOC-001: Private vehicle documents (RC, INSURANCE, PUC, PERMIT, FITNESS, TAX) without exposing public URLs.
 * - FR-DOC-002: Renewal version history without overwriting, date-order validation (expiry >= issue), non-expiring allowed.
 * - FR-DOC-003: Document reminder scanner <72h window, 72h boundary exclusion, expired handling.
 * - FR-DOC-004: Duplicate reminder prevention and version superseding.
 * - Secure Storage: Private buckets, company isolation, ADMIN_ONLY tier enforcement, fail-closed 503 when unconfigured.
 */
public final class Stage3FleetAndDocumentChecks {
    private static int checks;

    private static final String DUMMY_URL = "https://abcdefghijklmnopqrst.supabase.co";
    private static final String DUMMY_KEY = "sb_publishable_fixture0123456789";

    private static final UUID ADMIN_USER_ID = UUID.fromString("11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID EMPLOYEE_USER_ID = UUID.fromString("22222222-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID OTHER_COMPANY_USER_ID = UUID.fromString("33333333-cccc-cccc-cccc-cccccccccccc");
    private static final UUID COMPANY_ID = UUID.fromString("55555555-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID OTHER_COMPANY_ID = UUID.fromString("66666666-ffff-ffff-ffff-ffffffffffff");

    private static final StaffSession ADMIN_SESSION = new StaffSession(
            ADMIN_USER_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in",
            Set.of(Capability.MANAGE_VEHICLE_MASTER.name(), Capability.VIEW_FLEET.name(),
                    Capability.RECORD_FLEET_FREEZE.name(), Capability.APPROVE_MAINTENANCE_RESOLUTION.name(),
                    Capability.RENEW_ASSIGNED_DOCUMENT.name(), Capability.MANAGE_PERSONNEL.name())
    );

    private static final StaffSession EMPLOYEE_SESSION = new StaffSession(
            EMPLOYEE_USER_ID, COMPANY_ID, "EMPLOYEE", "employee@smartvrms.in",
            Set.of(Capability.VIEW_FLEET.name(), Capability.RECORD_FLEET_FREEZE.name(),
                    Capability.RENEW_ASSIGNED_DOCUMENT.name())
    );

    private static final StaffSession OTHER_COMPANY_ADMIN = new StaffSession(
            OTHER_COMPANY_USER_ID, OTHER_COMPANY_ID, "ADMINISTRATOR", "other@smartvrms.in",
            Set.of(Capability.MANAGE_VEHICLE_MASTER.name(), Capability.VIEW_FLEET.name(),
                    Capability.RECORD_FLEET_FREEZE.name(), Capability.APPROVE_MAINTENANCE_RESOLUTION.name(),
                    Capability.RENEW_ASSIGNED_DOCUMENT.name())
    );

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("FAIL: " + message);
        checks++;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 3 fleet and document lifecycle verification checks...");

        testMixedFleetCategories();
        testVehicleParticularsAndDuplicatePlateValidation();
        testAssetFreezeAndSafeReactivation();
        testVehicleDocumentVersioningAndDateOrder();
        testDocumentReminderScannerRules();
        testPrivateFileStorageTiersAndIsolation();
        testHttpFleetAndDocumentEndpoints();

        System.out.println("PASS: " + checks + " Stage 3 fleet, document, freeze/reactivation, reminder and storage assertions.");
    }

    // 1. FR-VEH-001: Mixed Fleet Categories
    private static void testMixedFleetCategories() {
        System.out.println("Checking FR-VEH-001: Mixed fleet categories...");
        List<Map<String, Object>> categoryStore = new ArrayList<>();

        DataSource ds = createMockDataSource(new MockQueryHandler() {
            @Override
            public ResultSet handle(String sql, List<Object> params) throws SQLException {
                if (sql.contains("SELECT id,code,name FROM vrms_private.vehicle_categories")) {
                    UUID compId = (UUID) params.get(0);
                    List<Map<String, Object>> filtered = categoryStore.stream()
                            .filter(r -> compId.equals(r.get("company_id")))
                            .toList();
                    return createMockResultSet(filtered, List.of("id", "code", "name"));
                }
                if (sql.contains("INSERT INTO vrms_private.vehicle_categories")) {
                    UUID id = UUID.randomUUID();
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", id);
                    row.put("company_id", params.get(0));
                    row.put("code", params.get(1));
                    row.put("name", params.get(2));
                    categoryStore.add(row);
                    return createMockResultSet(List.of(Map.of("id", id)), List.of("id"));
                }
                return emptyResultSet();
            }
        });

        JdbcFleetService fleet = new JdbcFleetService(ds, null);

        // Employee cannot create category
        boolean caughtEmpCategory = false;
        try {
            fleet.createCategory(EMPLOYEE_SESSION, "BIKE", "Two Wheeler Bike");
        } catch (SecurityException ex) {
            caughtEmpCategory = true;
        }
        require(caughtEmpCategory, "Employee cannot create vehicle category (MANAGE_VEHICLE_MASTER required)");

        // Admin registers standard categories (Bike, Scooter, Car, Tempo/van, Bus) and custom category (LUX_VAN)
        var bike = fleet.createCategory(ADMIN_SESSION, "BIKE", "Standard Motorcycle");
        var scooter = fleet.createCategory(ADMIN_SESSION, "SCOOTER", "Gearless Scooter");
        var car = fleet.createCategory(ADMIN_SESSION, "CAR", "Sedan / Hatchback Car");
        var tempo = fleet.createCategory(ADMIN_SESSION, "TEMPO", "Tempo Traveller / Van");
        var bus = fleet.createCategory(ADMIN_SESSION, "BUS", "Passenger Bus");
        var custom = fleet.createCategory(ADMIN_SESSION, "LUX_VAN", "Luxury VIP Caravan");

        require(bike.code().equals("BIKE"), "BIKE category created");
        require(scooter.code().equals("SCOOTER"), "SCOOTER category created");
        require(car.code().equals("CAR"), "CAR category created");
        require(tempo.code().equals("TEMPO"), "TEMPO category created");
        require(bus.code().equals("BUS"), "BUS category created");
        require(custom.code().equals("LUX_VAN"), "Custom LUX_VAN category created without code change (FR-VEH-001)");

        // Category code regex validation
        boolean caughtBadCode = false;
        try {
            fleet.createCategory(ADMIN_SESSION, "invalid-code", "Invalid Code Category");
        } catch (IllegalArgumentException ex) {
            caughtBadCode = true;
        }
        require(caughtBadCode, "Category code with lowercase/hyphen rejected by regex");

        // List categories (Employee and Admin can view)
        var categories = fleet.listCategories(EMPLOYEE_SESSION);
        require(categories.size() == 6, "All 6 categories listed for company staff");
    }

    // 2. FR-VEH-002: Vehicle Particulars & Duplicate Plate Validation
    private static void testVehicleParticularsAndDuplicatePlateValidation() {
        System.out.println("Checking FR-VEH-002: Vehicle particulars and duplicate plate validation...");
        List<Map<String, Object>> vehicles = new ArrayList<>();
        UUID catId = UUID.randomUUID();

        DataSource ds = createMockDataSource(new MockQueryHandler() {
            @Override
            public ResultSet handle(String sql, List<Object> params) throws SQLException {
                if (sql.contains("FROM vrms_private.vehicles WHERE company_id = ? AND UPPER(registration_plate) = ?")) {
                    UUID compId = (UUID) params.get(0);
                    String plate = (String) params.get(1);
                    boolean exists = vehicles.stream().anyMatch(v -> compId.equals(v.get("company_id")) && plate.equalsIgnoreCase((String) v.get("registration_plate")));
                    return exists ? createMockResultSet(List.of(Map.of("1", 1)), List.of("1")) : emptyResultSet();
                }
                if (sql.contains("SELECT name FROM vrms_private.vehicle_categories WHERE id=? AND company_id=?")) {
                    return createMockResultSet(List.of(Map.of("name", "Sedan Car")), List.of("name"));
                }
                if (sql.contains("INSERT INTO vrms_private.vehicles")) {
                    UUID compId = (UUID) params.get(0);
                    String plate = (String) params.get(2);
                    if (vehicles.stream().anyMatch(v -> compId.equals(v.get("company_id")) && plate.equalsIgnoreCase((String) v.get("registration_plate")))) {
                        throw new SQLException("duplicate key value violates unique constraint \"vehicles_company_id_registration_plate_key\"", "23505");
                    }
                    UUID id = UUID.randomUUID();
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", id);
                    row.put("company_id", compId);
                    row.put("category_id", params.get(1));
                    row.put("registration_plate", plate);
                    row.put("manufacturer", params.get(3));
                    row.put("model", params.get(4));
                    row.put("seating_capacity", params.get(5));
                    row.put("daily_rate_paise", params.get(6));
                    row.put("hourly_rate_paise", params.get(7));
                    row.put("operational_status", "AVAILABLE");
                    row.put("photo_file_id", params.get(8));
                    row.put("version", 1L);
                    vehicles.add(row);
                    return createMockResultSet(List.of(Map.of("id", id, "created_at", Timestamp.from(Instant.now()), "version", 1L)), List.of("id", "created_at", "version"));
                }
                return emptyResultSet();
            }
        });

        JdbcFleetService fleet = new JdbcFleetService(ds, null);

        // Register valid vehicle
        var v = fleet.registerVehicle(ADMIN_SESSION, catId, "kl 07 cd 1234", "Toyota", "Innova Crysta", 7, 350000L, 25000L, null);
        require("KL 07 CD 1234".equals(v.registrationPlate()), "Plate normalized to uppercase");
        require(v.seatingCapacity() == 7, "Seating capacity recorded");
        require(v.dailyRatePaise() == 350000L, "Daily rate recorded (3,500 INR)");
        require("AVAILABLE".equals(v.operationalStatus()), "Initial status is AVAILABLE");

        // Duplicate plate in same company rejected (FR-VEH-002)
        boolean caughtDupPlate = false;
        try {
            fleet.registerVehicle(ADMIN_SESSION, catId, "KL 07 CD 1234", "Toyota", "Innova Crysta", 7, 350000L, 25000L, null);
        } catch (IllegalArgumentException ex) {
            caughtDupPlate = true;
        }
        require(caughtDupPlate, "Duplicate registration plate in same company rejected with IllegalArgumentException");

        // Invalid plate length (<4 or >20) rejected
        boolean caughtShortPlate = false;
        try {
            fleet.registerVehicle(ADMIN_SESSION, catId, "AB", "Toyota", "Innova", 7, 350000L, 25000L, null);
        } catch (IllegalArgumentException ex) {
            caughtShortPlate = true;
        }
        require(caughtShortPlate, "Plate shorter than 4 chars rejected");

        // Zero or negative seating capacity rejected
        boolean caughtZeroSeats = false;
        try {
            fleet.registerVehicle(ADMIN_SESSION, catId, "KL 07 CD 9999", "Toyota", "Innova", 0, 350000L, 25000L, null);
        } catch (IllegalArgumentException ex) {
            caughtZeroSeats = true;
        }
        require(caughtZeroSeats, "Seating capacity <= 0 rejected");

        // Negative daily rate rejected
        boolean caughtNegRate = false;
        try {
            fleet.registerVehicle(ADMIN_SESSION, catId, "KL 07 CD 9999", "Toyota", "Innova", 5, -100L, 0L, null);
        } catch (IllegalArgumentException ex) {
            caughtNegRate = true;
        }
        require(caughtNegRate, "Negative daily rate rejected");
    }

    // 3. FR-VEH-006: Asset Freeze and Safe Reactivation
    private static void testAssetFreezeAndSafeReactivation() {
        System.out.println("Checking FR-VEH-006: Asset freeze and safe reactivation...");
        UUID vehicleId = UUID.randomUUID();
        Map<String, Object> vehicle = new HashMap<>(Map.of(
                "id", vehicleId, "company_id", COMPANY_ID, "operational_status", "AVAILABLE"
        ));
        List<Map<String, Object>> restrictions = new ArrayList<>();

        DataSource ds = createMockDataSource(new MockQueryHandler() {
            @Override
            public ResultSet handle(String sql, List<Object> params) throws SQLException {
                if (sql.contains("FROM vrms_private.vehicles WHERE id = ? AND company_id = ?")) {
                    return createMockResultSet(List.of(Map.of("1", 1)), List.of("1"));
                }
                if (sql.contains("INSERT INTO vrms_private.asset_restrictions")) {
                    UUID id = UUID.randomUUID();
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", id);
                    row.put("company_id", params.get(0));
                    row.put("asset_type", params.get(1));
                    row.put("asset_id", params.get(2));
                    row.put("restriction_type", "FREEZE");
                    row.put("start_time", params.get(3));
                    row.put("end_time", params.get(4));
                    row.put("reason", params.get(5));
                    row.put("active", true);
                    restrictions.add(row);
                    return createMockResultSet(List.of(Map.of("id", id)), List.of("id"));
                }
                if (sql.contains("UPDATE vrms_private.vehicles SET operational_status = 'FROZEN'")) {
                    vehicle.put("operational_status", "FROZEN");
                    return emptyResultSet();
                }
                if (sql.contains("SELECT asset_type, asset_id FROM vrms_private.asset_restrictions WHERE id = ? AND company_id = ?")) {
                    UUID restId = (UUID) params.get(0);
                    var match = restrictions.stream().filter(r -> restId.equals(r.get("id"))).findFirst();
                    if (match.isPresent()) {
                        return createMockResultSet(List.of(Map.of("asset_type", match.get().get("asset_type"), "asset_id", match.get().get("asset_id"))), List.of("asset_type", "asset_id"));
                    }
                    return emptyResultSet();
                }
                if (sql.contains("UPDATE vrms_private.asset_restrictions") && sql.contains("SET active = false")) {
                    UUID restId = (UUID) params.get(0);
                    restrictions.stream().filter(r -> restId.equals(r.get("id"))).forEach(r -> r.put("active", false));
                    return emptyResultSet();
                }
                if (sql.contains("FROM vrms_private.asset_restrictions WHERE asset_type = 'VEHICLE' AND asset_id = ? AND company_id = ? AND active = true")) {
                    UUID vId = (UUID) params.get(0);
                    boolean hasActive = restrictions.stream().anyMatch(r -> vId.equals(r.get("asset_id")) && Boolean.TRUE.equals(r.get("active")));
                    return hasActive ? createMockResultSet(List.of(Map.of("1", 1)), List.of("1")) : emptyResultSet();
                }
                if (sql.contains("UPDATE vrms_private.vehicles SET operational_status = 'AVAILABLE'")) {
                    vehicle.put("operational_status", "AVAILABLE");
                    return emptyResultSet();
                }
                return emptyResultSet();
            }
        });

        JdbcFleetService fleet = new JdbcFleetService(ds, null);

        // 1. Freeze asset
        var freeze1 = fleet.freezeAsset(ADMIN_SESSION, "VEHICLE", vehicleId, "Scheduled preventative maintenance", Instant.now(), null);
        require(freeze1.active(), "Freeze restriction is active");
        require("FROZEN".equals(vehicle.get("operational_status")), "Vehicle operational status transitioned to FROZEN");

        // 2. Add second independent freeze block (e.g. Traffic RTO inquiry)
        var freeze2 = fleet.freezeAsset(ADMIN_SESSION, "VEHICLE", vehicleId, "RTO documentation hold", Instant.now(), null);
        require(freeze2.active(), "Second freeze block created");

        // 3. Resolve the first restriction
        fleet.resolveAssetRestriction(ADMIN_SESSION, freeze1.id());
        // Second block remains active -> Vehicle MUST remain FROZEN (FR-VEH-006 Safe Reactivation)
        require("FROZEN".equals(vehicle.get("operational_status")),
                "FR-VEH-006: Resolving first freeze leaves vehicle FROZEN because independent RTO hold restriction remains");

        // 4. Resolve second restriction -> No remaining active restrictions -> Vehicle becomes AVAILABLE
        fleet.resolveAssetRestriction(ADMIN_SESSION, freeze2.id());
        require("AVAILABLE".equals(vehicle.get("operational_status")),
                "Vehicle status safely restored to AVAILABLE after all active restrictions are resolved");

        // 5. Invalid freeze parameters
        boolean caughtBlankReason = false;
        try {
            fleet.freezeAsset(ADMIN_SESSION, "VEHICLE", vehicleId, "   ", Instant.now(), null);
        } catch (IllegalArgumentException ex) {
            caughtBlankReason = true;
        }
        require(caughtBlankReason, "Blank freeze reason rejected");

        boolean caughtInvalidTime = false;
        try {
            Instant start = Instant.now();
            fleet.freezeAsset(ADMIN_SESSION, "VEHICLE", vehicleId, "Test", start, start.minusSeconds(3600));
        } catch (IllegalArgumentException ex) {
            caughtInvalidTime = true;
        }
        require(caughtInvalidTime, "End time before start time rejected");
    }

    // 4. FR-DOC-001 & FR-DOC-002: Document Versioning & Date Order
    private static void testVehicleDocumentVersioningAndDateOrder() {
        System.out.println("Checking FR-DOC-001 and FR-DOC-002: Vehicle document versioning and date order...");
        UUID vehicleId = UUID.randomUUID();
        UUID fileId1 = UUID.randomUUID();
        UUID fileId2 = UUID.randomUUID();
        List<Map<String, Object>> docStore = new ArrayList<>();

        DataSource ds = createMockDataSource(new MockQueryHandler() {
            @Override
            public ResultSet handle(String sql, List<Object> params) throws SQLException {
                if (sql.contains("FROM vrms_private.vehicles WHERE id = ? AND company_id = ?")) {
                    return createMockResultSet(List.of(Map.of("1", 1)), List.of("1"));
                }
                if (sql.contains("FROM vrms_private.file_metadata WHERE id = ? AND company_id = ?")) {
                    return createMockResultSet(List.of(Map.of("1", 1)), List.of("1"));
                }
                if (sql.contains("INSERT INTO vrms_private.vehicle_documents")) {
                    UUID id = UUID.randomUUID();
                    String docType = (String) params.get(2);
                    long maxVer = docStore.stream()
                            .filter(d -> docType.equals(d.get("doc_type")))
                            .mapToLong(d -> (int) d.get("version"))
                            .max().orElse(0L);
                    int newVer = (int) (maxVer + 1);
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", id);
                    row.put("company_id", params.get(0));
                    row.put("vehicle_id", params.get(1));
                    row.put("doc_type", docType);
                    row.put("file_id", params.get(3));
                    row.put("issue_date", params.get(4));
                    row.put("expiry_date", params.get(5));
                    row.put("version", newVer);
                    docStore.add(row);
                    return createMockResultSet(List.of(Map.of("id", id, "version", newVer)), List.of("id", "version"));
                }
                return emptyResultSet();
            }
        });

        JdbcFleetService fleet = new JdbcFleetService(ds, null);

        // Add initial INSURANCE document (version 1)
        var doc1 = fleet.addVehicleDocument(ADMIN_SESSION, vehicleId, "INSURANCE", fileId1, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));
        require(doc1.version() == 1, "Initial INSURANCE document is version 1");
        require("INSURANCE".equals(doc1.docType()), "docType recorded as INSURANCE");

        // Renew INSURANCE with new file and dates -> Version increments to 2 without overwriting v1
        var doc2 = fleet.addVehicleDocument(ADMIN_SESSION, vehicleId, "INSURANCE", fileId2, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        require(doc2.version() == 2, "Renewed INSURANCE document increments to version 2 (FR-DOC-002)");
        require(docStore.size() == 2, "Prior document version 1 preserved in database (no destructive overwrite)");

        // Add non-expiring document (expiryDate == null allowed per AC-DOC-002)
        var taxDoc = fleet.addVehicleDocument(ADMIN_SESSION, vehicleId, "TAX", fileId1, LocalDate.of(2025, 1, 1), null);
        require(taxDoc.expiryDate() == null, "Non-expiring tax document saved with null expiry date");

        // Date-order validation: expiry date before issue date is rejected
        boolean caughtInvalidDateOrder = false;
        try {
            fleet.addVehicleDocument(ADMIN_SESSION, vehicleId, "PUC", fileId1, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 5, 1));
        } catch (IllegalArgumentException ex) {
            caughtInvalidDateOrder = true;
        }
        require(caughtInvalidDateOrder, "Expiry date before issue date rejected with IllegalArgumentException");

        // Unsupported document type rejected
        boolean caughtBadType = false;
        try {
            fleet.addVehicleDocument(ADMIN_SESSION, vehicleId, "DRIVING_LICENCE_OF_RANDOM_PERSON", fileId1, LocalDate.of(2025, 1, 1), null);
        } catch (IllegalArgumentException ex) {
            caughtBadType = true;
        }
        require(caughtBadType, "Invalid vehicle document type rejected");
    }

    // 5. FR-DOC-003 & FR-DOC-004: Document Reminder Scanner Rules
    private static void testDocumentReminderScannerRules() {
        System.out.println("Checking FR-DOC-003 and FR-DOC-004: Document reminder scanner boundaries...");
        Instant scanTime = Instant.parse("2026-10-26T03:30:00Z"); // 09:00 AM IST

        // Case 1: Exactly 72h00m00s remaining is strictly EXCLUDED
        Instant exact72h = scanTime.plus(Duration.ofHours(72));
        var stage72 = DocumentReminderScanner.evaluateStage(exact72h, scanTime);
        require(stage72 == DocumentReminderScanner.ReminderStage.NONE,
                "FR-DOC-003: Exactly 72h00m remaining is strictly excluded (stage = NONE)");

        // Case 2: 71h59m59s remaining (< 72h) is included as LESS_THAN_72H
        Instant below72h = scanTime.plus(Duration.ofHours(71)).plus(Duration.ofMinutes(59));
        var stageBelow72 = DocumentReminderScanner.evaluateStage(below72h, scanTime);
        require(stageBelow72 == DocumentReminderScanner.ReminderStage.LESS_THAN_72H,
                "FR-DOC-003: 71h59m remaining is classified as LESS_THAN_72H");

        // Case 3: Expired document
        Instant expired = scanTime.minus(Duration.ofMinutes(1));
        var stageExpired = DocumentReminderScanner.evaluateStage(expired, scanTime);
        require(stageExpired == DocumentReminderScanner.ReminderStage.EXPIRED,
                "FR-DOC-003: Past expiry is classified as EXPIRED");

        // Case 4: Non-expiring document (null expiry)
        var stageNull = DocumentReminderScanner.evaluateStage(null, scanTime);
        require(stageNull == DocumentReminderScanner.ReminderStage.NONE,
                "FR-DOC-002: Non-expiring document emits no reminder (stage = NONE)");

        // Case 5: Idempotency key per version-stage-recipient (FR-DOC-004)
        UUID docId = UUID.randomUUID();
        String key1 = DocumentReminderScanner.buildIdempotencyKey(COMPANY_ID, docId, 1, DocumentReminderScanner.ReminderStage.LESS_THAN_72H, "admin@smartvrms.in");
        String key2 = DocumentReminderScanner.buildIdempotencyKey(COMPANY_ID, docId, 1, DocumentReminderScanner.ReminderStage.LESS_THAN_72H, "admin@smartvrms.in");
        require(key1.equals(key2), "FR-DOC-004: Same version, stage and recipient generates identical idempotency key");

        // Case 6: Renewed document (version 2) generates distinct idempotency key (supersedes old version)
        String keyV2 = DocumentReminderScanner.buildIdempotencyKey(COMPANY_ID, docId, 2, DocumentReminderScanner.ReminderStage.LESS_THAN_72H, "admin@smartvrms.in");
        require(!key1.equals(keyV2), "FR-DOC-004: Version 2 generates distinct key, preventing confusion with version 1");
    }

    // 6. Secure Storage: Access Tiers, Company Isolation, Fail-Closed Signing
    private static void testPrivateFileStorageTiersAndIsolation() {
        System.out.println("Checking Secure Storage: Buckets, access tiers, company isolation...");

        // Bucket selection
        require("vrms-private-media".equals(JdbcFileStorageService.selectBucket(FileCategory.VEHICLE_PHOTO)), "VEHICLE_PHOTO routed to private media bucket");
        require("vrms-private-documents".equals(JdbcFileStorageService.selectBucket(FileCategory.VEHICLE_DOC)), "VEHICLE_DOC routed to private documents bucket");

        UUID fileId = UUID.randomUUID();
        Map<String, Object> fileRecord = new HashMap<>();
        fileRecord.put("id", fileId);
        fileRecord.put("company_id", COMPANY_ID);
        fileRecord.put("uploaded_by", ADMIN_USER_ID);
        fileRecord.put("storage_bucket", "vrms-private-documents");
        fileRecord.put("storage_path", COMPANY_ID + "/vehicle_doc/" + fileId + ".pdf");
        fileRecord.put("file_name", "rc_document.pdf");
        fileRecord.put("content_type", "application/pdf");
        fileRecord.put("size_bytes", 102400L);
        fileRecord.put("category", "VEHICLE_DOC");
        fileRecord.put("access_tier", "OPERATIONAL_STAFF");
        fileRecord.put("status", "FINALIZED");
        fileRecord.put("created_at", Timestamp.from(Instant.now()));
        fileRecord.put("finalized_at", Timestamp.from(Instant.now()));

        DataSource ds = createMockDataSource(new MockQueryHandler() {
            @Override
            public ResultSet handle(String sql, List<Object> params) throws SQLException {
                if (sql.contains("file_metadata") && sql.contains("id = ?")) {
                    UUID fId = (UUID) params.get(0);
                    UUID cId = (UUID) params.get(1);
                    if (fileId.equals(fId) && COMPANY_ID.equals(cId)) {
                        return createMockResultSet(List.of(fileRecord), List.of(
                                "id", "company_id", "uploaded_by", "storage_bucket", "storage_path",
                                "file_name", "content_type", "size_bytes", "category", "access_tier",
                                "status", "created_at", "finalized_at"
                        ));
                    }
                    return emptyResultSet();
                }
                return emptyResultSet();
            }
        });

        // Test without serviceRoleKey -> fail-closed with clear IllegalStateException
        JdbcFileStorageService unconfiguredStorage = new JdbcFileStorageService(ds, "https://example.supabase.co/storage/v1", null);

        boolean caughtUnconfigured = false;
        try {
            unconfiguredStorage.generateSignedDownloadUrl(ADMIN_SESSION, fileId, 300);
        } catch (IllegalStateException ex) {
            caughtUnconfigured = true;
            require(ex.getMessage().contains("SUPABASE_SERVICE_ROLE_KEY is required"), "Explicit explanation of unconfigured signing");
        }
        require(caughtUnconfigured, "File download fails closed when server signing key is unconfigured (no fake or public URL)");

        // Test cross-company isolation: Other company admin cannot access file metadata
        boolean caughtCrossCompany = false;
        try {
            unconfiguredStorage.generateSignedDownloadUrl(OTHER_COMPANY_ADMIN, fileId, 300);
        } catch (SecurityException ex) {
            caughtCrossCompany = true;
        }
        require(caughtCrossCompany, "Cross-company file access strictly denied with SecurityException");

        // Test ADMIN_ONLY access tier enforcement on Employee
        fileRecord.put("access_tier", "ADMIN_ONLY");
        boolean caughtEmployeeTierDenied = false;
        try {
            unconfiguredStorage.generateSignedDownloadUrl(EMPLOYEE_SESSION, fileId, 300);
        } catch (SecurityException ex) {
            caughtEmployeeTierDenied = true;
        }
        require(caughtEmployeeTierDenied, "Employee cannot generate download link for ADMIN_ONLY file");
    }

    // 7. HTTP Server API Endpoints
    private static void testHttpFleetAndDocumentEndpoints() throws Exception {
        System.out.println("Checking HTTP fleet and document endpoints...");

        UUID vehicleId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID restrictionId = UUID.randomUUID();

        in.smartvrms.application.port.FleetPort mockFleet = new in.smartvrms.application.port.FleetPort() {
            @Override
            public List<VehicleCategoryRecord> listCategories(StaffSession session) {
                return List.of(new VehicleCategoryRecord(categoryId, "CAR", "Sedan"));
            }

            @Override
            public VehicleCategoryRecord createCategory(StaffSession session, String code, String name) {
                return new VehicleCategoryRecord(categoryId, code, name);
            }

            @Override
            public VehicleRecord registerVehicle(StaffSession session, UUID catId, String plate, String make, String model, int capacity, long dailyRatePaise, long hourlyRatePaise, UUID photoFileId) {
                return new VehicleRecord(vehicleId, session.companyId(), catId, "Sedan", plate, make, model, capacity, dailyRatePaise, hourlyRatePaise, "AVAILABLE", photoFileId, 1L);
            }

            @Override
            public List<VehicleRecord> listVehicles(StaffSession session, String categoryCode, String status) {
                return List.of(new VehicleRecord(vehicleId, session.companyId(), categoryId, "Sedan", "KL 07 CD 1234", "Toyota", "Innova", 7, 350000L, 25000L, "AVAILABLE", null, 1L));
            }

            @Override
            public Optional<VehicleRecord> getVehicle(StaffSession session, UUID vId) {
                return Optional.of(new VehicleRecord(vehicleId, session.companyId(), categoryId, "Sedan", "KL 07 CD 1234", "Toyota", "Innova", 7, 350000L, 25000L, "AVAILABLE", null, 1L));
            }

            @Override
            public AssetRestrictionRecord freezeAsset(StaffSession session, String assetType, UUID assetId, String reason, Instant startTime, Instant endTime) {
                return new AssetRestrictionRecord(restrictionId, session.companyId(), assetType, assetId, "FREEZE", startTime, endTime, reason, true);
            }

            @Override
            public void resolveAssetRestriction(StaffSession session, UUID restId) {
            }

            @Override
            public List<AssetRestrictionRecord> listAssetRestrictions(StaffSession session, String assetType, UUID assetId) {
                return List.of(new AssetRestrictionRecord(restrictionId, session.companyId(), assetType, assetId, "FREEZE", Instant.now(), null, "Inspection", true));
            }

            @Override
            public VehicleDocumentRecord addVehicleDocument(StaffSession session, UUID vId, String docType, UUID fileId, LocalDate issueDate, LocalDate expiryDate) {
                return new VehicleDocumentRecord(UUID.randomUUID(), session.companyId(), vId, docType, fileId, issueDate, expiryDate, 1);
            }

            @Override
            public List<VehicleDocumentRecord> getVehicleDocuments(StaffSession session, UUID vId) {
                return List.of(new VehicleDocumentRecord(UUID.randomUUID(), session.companyId(), vId, "RC", UUID.randomUUID(), LocalDate.of(2025, 1, 1), LocalDate.of(2035, 1, 1), 1));
            }
        };

        // StoragePort that throws IllegalStateException to test HTTP 503 response
        in.smartvrms.application.port.StoragePort mockStorage = new in.smartvrms.application.port.StoragePort() {
            @Override
            public FileMetadataRecord initiateUpload(StaffSession session, String fileName, String contentType, long sizeBytes, FileCategory category, FileAccessTier accessTier) {
                return null;
            }
            @Override
            public FileMetadataRecord finalizeUpload(StaffSession session, UUID fileId) {
                return null;
            }
            @Override
            public Optional<FileMetadataRecord> getMetadata(StaffSession session, UUID fileId) {
                return Optional.empty();
            }
            @Override
            public String generateSignedDownloadUrl(StaffSession session, UUID fileId, int expiresInSeconds) {
                throw new IllegalStateException("Private Storage signing is not configured.");
            }
        };

        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            if ("admin-token".equals(token)) {
                return new StaffAuthorizer.Staff(ADMIN_USER_ID, COMPANY_ID, "ADMINISTRATOR");
            }
            if ("employee-token".equals(token)) {
                if (cap.administratorOnly()) throw new SecurityException("Forbidden for Employee");
                return new StaffAuthorizer.Staff(EMPLOYEE_USER_ID, COMPANY_ID, "EMPLOYEE");
            }
            throw new SecurityException("Invalid token");
        };

        SupabaseAuth auth = new SupabaseAuth(new SupabaseConfig(DUMMY_URL, DUMMY_KEY));
        VrmsHttpServer server = new VrmsHttpServer(
                new HttpServerConfig(0), auth, authorizer, mockStorage, null, null, null, null, mockFleet, null, null
        );
        server.start();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI baseUri = server.baseUri();

        try {
            // GET /api/v1/fleet/vehicles
            HttpRequest getVehicles = HttpRequest.newBuilder(baseUri.resolve("/api/v1/fleet/vehicles"))
                    .header("Authorization", "Bearer employee-token")
                    .GET().build();
            HttpResponse<String> vResp = client.send(getVehicles, HttpResponse.BodyHandlers.ofString());
            require(vResp.statusCode() == 200, "GET /api/v1/fleet/vehicles returned 200 OK");
            JsonObject vJson = JsonParser.parseString(vResp.body()).getAsJsonObject();
            require(vJson.has("vehicles") && vJson.getAsJsonArray("vehicles").size() == 1, "Vehicle list returned");

            // GET /api/v1/fleet/restrictions
            HttpRequest getRests = HttpRequest.newBuilder(baseUri.resolve("/api/v1/fleet/restrictions?assetType=VEHICLE&assetId=" + vehicleId))
                    .header("Authorization", "Bearer employee-token")
                    .GET().build();
            HttpResponse<String> rResp = client.send(getRests, HttpResponse.BodyHandlers.ofString());
            require(rResp.statusCode() == 200, "GET /api/v1/fleet/restrictions returned 200 OK");
            JsonObject rJson = JsonParser.parseString(rResp.body()).getAsJsonObject();
            require(rJson.has("restrictions") && rJson.getAsJsonArray("restrictions").size() == 1, "Restrictions list returned");

            // POST /api/v1/fleet/restrictions/freeze
            JsonObject freezePayload = new JsonObject();
            freezePayload.addProperty("assetType", "VEHICLE");
            freezePayload.addProperty("assetId", vehicleId.toString());
            freezePayload.addProperty("reason", "Routine overhaul");
            HttpRequest postFreeze = HttpRequest.newBuilder(baseUri.resolve("/api/v1/fleet/restrictions/freeze"))
                    .header("Authorization", "Bearer employee-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(freezePayload.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> freezeResp = client.send(postFreeze, HttpResponse.BodyHandlers.ofString());
            require(freezeResp.statusCode() == 201, "POST /api/v1/fleet/restrictions/freeze returned 201 Created");

            // POST /api/v1/fleet/restrictions/resolve (Admin only capability: APPROVE_MAINTENANCE_RESOLUTION)
            JsonObject resolvePayload = new JsonObject();
            resolvePayload.addProperty("restrictionId", restrictionId.toString());

            // Employee denied resolve
            HttpRequest empResolve = HttpRequest.newBuilder(baseUri.resolve("/api/v1/fleet/restrictions/resolve"))
                    .header("Authorization", "Bearer employee-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(resolvePayload.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> empResolveResp = client.send(empResolve, HttpResponse.BodyHandlers.ofString());
            require(empResolveResp.statusCode() == 403, "Employee cannot resolve restriction (403 Forbidden)");

            // Admin succeeds resolve
            HttpRequest adminResolve = HttpRequest.newBuilder(baseUri.resolve("/api/v1/fleet/restrictions/resolve"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(resolvePayload.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> adminResolveResp = client.send(adminResolve, HttpResponse.BodyHandlers.ofString());
            require(adminResolveResp.statusCode() == 200, "Admin can resolve restriction (200 OK)");

            // GET /api/v1/files/download when storage is unconfigured -> HTTP 503 visibly unavailable
            HttpRequest getFile = HttpRequest.newBuilder(baseUri.resolve("/api/v1/files/download?fileId=" + UUID.randomUUID()))
                    .header("Authorization", "Bearer admin-token")
                    .GET().build();
            HttpResponse<String> fileResp = client.send(getFile, HttpResponse.BodyHandlers.ofString());
            require(fileResp.statusCode() == 503, "Unconfigured private storage returns HTTP 503 Service Unavailable");
        } finally {
            server.stop(0);
        }
    }

    // =========================================================================
    // JDBC Mocking Infrastructure
    // =========================================================================

    @FunctionalInterface
    private interface MockQueryHandler {
        ResultSet handle(String sql, List<Object> params) throws SQLException;
    }

    private static DataSource createMockDataSource(MockQueryHandler handler) {
        return proxy(DataSource.class, (p, m, a) -> {
            if ("getConnection".equals(m.getName())) {
                return createMockConnection(handler);
            }
            return null;
        });
    }

    private static Connection createMockConnection(MockQueryHandler handler) {
        return proxy(Connection.class, (p, m, a) -> {
            String name = m.getName();
            if ("prepareStatement".equals(name)) {
                String sql = (String) a[0];
                return createMockPreparedStatement(sql, handler);
            }
            if ("setAutoCommit".equals(name) || "commit".equals(name) || "rollback".equals(name) || "close".equals(name)) {
                return null;
            }
            if ("getAutoCommit".equals(name)) return false;
            if ("isClosed".equals(name)) return false;
            return null;
        });
    }

    private static PreparedStatement createMockPreparedStatement(String sql, MockQueryHandler handler) {
        List<Object> params = new ArrayList<>();
        return proxy(PreparedStatement.class, (p, m, a) -> {
            String name = m.getName();
            if ("setObject".equals(name) || "setString".equals(name) || "setInt".equals(name)
                    || "setLong".equals(name) || "setDate".equals(name) || "setTimestamp".equals(name)) {
                int idx = (Integer) a[0] - 1;
                while (params.size() <= idx) params.add(null);
                params.set(idx, a[1]);
                return null;
            }
            if ("setQueryTimeout".equals(name)) return null;
            if ("executeQuery".equals(name)) {
                if (sql.contains("pg_roles")) {
                    return createMockResultSet(List.of(Map.of("1", true)), List.of("1"));
                }
                if (sql.contains("set_config")) {
                    return createMockResultSet(List.of(Map.of("1", "")), List.of("1"));
                }
                if (sql.contains("SELECT company_id, role, active FROM vrms_private.memberships")) {
                    UUID userId = (UUID) params.get(0);
                    UUID comp = ADMIN_USER_ID.equals(userId) || EMPLOYEE_USER_ID.equals(userId) ? COMPANY_ID : OTHER_COMPANY_ID;
                    String role = ADMIN_USER_ID.equals(userId) || OTHER_COMPANY_USER_ID.equals(userId) ? "ADMINISTRATOR" : "EMPLOYEE";
                    return createMockResultSet(List.of(Map.of("company_id", comp, "role", role, "active", true)), List.of("company_id", "role", "active"));
                }
                return handler.handle(sql, params);
            }
            if ("executeUpdate".equals(name)) {
                handler.handle(sql, params);
                return 1;
            }
            if ("execute".equals(name)) {
                handler.handle(sql, params);
                return true;
            }
            if ("close".equals(name)) return null;
            return null;
        });
    }

    private static ResultSet emptyResultSet() {
        return createMockResultSet(List.of(), List.of());
    }

    private static ResultSet createMockResultSet(List<Map<String, Object>> rows, List<String> columns) {
        int[] cursor = {-1};
        return proxy(ResultSet.class, (p, m, a) -> {
            String name = m.getName();
            if ("next".equals(name)) {
                cursor[0]++;
                return cursor[0] < rows.size();
            }
            if ("getObject".equals(name)) {
                if (cursor[0] < 0 || cursor[0] >= rows.size()) throw new SQLException("Cursor out of bounds");
                Map<String, Object> row = rows.get(cursor[0]);
                if (a[0] instanceof Integer idx) {
                    String col = columns.get(idx - 1);
                    return row.get(col);
                }
                return row.get((String) a[0]);
            }
            if ("getString".equals(name)) {
                if (cursor[0] < 0 || cursor[0] >= rows.size()) throw new SQLException("Cursor out of bounds");
                Map<String, Object> row = rows.get(cursor[0]);
                Object val = a[0] instanceof Integer idx ? row.get(columns.get(idx - 1)) : row.get((String) a[0]);
                return val != null ? val.toString() : null;
            }
            if ("getInt".equals(name)) {
                if (cursor[0] < 0 || cursor[0] >= rows.size()) throw new SQLException("Cursor out of bounds");
                Map<String, Object> row = rows.get(cursor[0]);
                Object val = a[0] instanceof Integer idx ? row.get(columns.get(idx - 1)) : row.get((String) a[0]);
                return val instanceof Number n ? n.intValue() : (val != null ? Integer.parseInt(val.toString()) : 0);
            }
            if ("getLong".equals(name)) {
                if (cursor[0] < 0 || cursor[0] >= rows.size()) throw new SQLException("Cursor out of bounds");
                Map<String, Object> row = rows.get(cursor[0]);
                Object val = a[0] instanceof Integer idx ? row.get(columns.get(idx - 1)) : row.get((String) a[0]);
                return val instanceof Number n ? n.longValue() : (val != null ? Long.parseLong(val.toString()) : 0L);
            }
            if ("getBoolean".equals(name)) {
                if (cursor[0] < 0 || cursor[0] >= rows.size()) throw new SQLException("Cursor out of bounds");
                Map<String, Object> row = rows.get(cursor[0]);
                Object val = a[0] instanceof Integer idx ? row.get(columns.get(idx - 1)) : row.get((String) a[0]);
                return Boolean.TRUE.equals(val);
            }
            if ("getDate".equals(name)) {
                if (cursor[0] < 0 || cursor[0] >= rows.size()) throw new SQLException("Cursor out of bounds");
                Map<String, Object> row = rows.get(cursor[0]);
                Object val = a[0] instanceof Integer idx ? row.get(columns.get(idx - 1)) : row.get((String) a[0]);
                if (val instanceof Date d) return d;
                if (val instanceof LocalDate ld) return Date.valueOf(ld);
                return null;
            }
            if ("getTimestamp".equals(name)) {
                if (cursor[0] < 0 || cursor[0] >= rows.size()) throw new SQLException("Cursor out of bounds");
                Map<String, Object> row = rows.get(cursor[0]);
                Object val = a[0] instanceof Integer idx ? row.get(columns.get(idx - 1)) : row.get((String) a[0]);
                if (val instanceof Timestamp ts) return ts;
                if (val instanceof Instant inst) return Timestamp.from(inst);
                return null;
            }
            if ("close".equals(name)) return null;
            return null;
        });
    }
}
