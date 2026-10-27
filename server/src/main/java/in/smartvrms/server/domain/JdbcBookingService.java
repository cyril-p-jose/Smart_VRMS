package in.smartvrms.server.domain;

import in.smartvrms.application.port.AuditPort;
import in.smartvrms.application.port.BookingPort;
import in.smartvrms.application.port.IdempotencyPort;
import in.smartvrms.contracts.AssignmentQuote;
import in.smartvrms.contracts.BookingAssignmentRecord;
import in.smartvrms.contracts.BookingConfirmationCommand;
import in.smartvrms.contracts.BookingQuote;
import in.smartvrms.contracts.BookingRecord;
import in.smartvrms.contracts.RatePlanRecord;
import in.smartvrms.contracts.StaffSession;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * JDBC implementation of BookingPort enforcing atomic transactions, sorted resource locks,
 * constraint validation, and idempotency (FR-BOOK-001 through FR-BOOK-010).
 */
public class JdbcBookingService implements BookingPort {

    private final DataSource dataSource;
    private final AuditPort auditPort;
    private final IdempotencyPort idempotencyPort;

    public JdbcBookingService(DataSource dataSource, AuditPort auditPort, IdempotencyPort idempotencyPort) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.auditPort = auditPort;
        this.idempotencyPort = idempotencyPort;
    }

    @Override
    public BookingQuote calculateQuote(UUID companyId, Instant startTime, Instant endTime, List<BookingConfirmationCommand.PackageItem> items) {
        Objects.requireNonNull(companyId, "companyId");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("At least one vehicle assignment is required for quote.");
        }

        List<QuoteEngine.AssignmentInput> inputs = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            for (BookingConfirmationCommand.PackageItem item : items) {
                // Fetch vehicle particulars
                String vehicleName = "Vehicle";
                String category = "CAR";
                String plate = "TEST";
                UUID categoryId = null;

                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT (v.manufacturer || ' ' || v.model) AS name, c.code, v.registration_plate, v.category_id " +
                        "FROM vrms_private.vehicles v " +
                        "JOIN vrms_private.vehicle_categories c ON c.id = v.category_id " +
                        "WHERE v.company_id = ? AND v.id = ?")) {
                    ps.setObject(1, companyId);
                    ps.setObject(2, item.vehicleId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            vehicleName = rs.getString(1);
                            category = rs.getString(2);
                            plate = rs.getString(3);
                            categoryId = (UUID) rs.getObject(4);
                        }
                    }
                }

                // Fetch rate plan
                RatePlanRecord ratePlan = fetchRatePlan(conn, companyId, item.vehicleId(), categoryId);

                // Fetch driver details if required
                String driverName = null;
                if (item.driverRequired() && item.driverId() != null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT e.full_name FROM vrms_private.driver_profiles d " +
                            "JOIN vrms_private.employees e ON e.id = d.employee_id " +
                            "WHERE d.company_id = ? AND d.id = ?")) {
                        ps.setObject(1, companyId);
                        ps.setObject(2, item.driverId());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                driverName = rs.getString(1);
                            }
                        }
                    }
                }

                inputs.add(new QuoteEngine.AssignmentInput(
                        item.vehicleId(),
                        vehicleName,
                        category,
                        plate,
                        item.driverId(),
                        driverName,
                        item.driverRequired(),
                        ratePlan
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to calculate quote from database", e);
        }

        return QuoteEngine.calculate(startTime, endTime, inputs);
    }

    private RatePlanRecord fetchRatePlan(Connection conn, UUID companyId, UUID vehicleId, UUID categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, vehicle_id, category_id, daily_rate_paise, hourly_rate_paise, " +
                "driver_daily_fee_paise, driver_hourly_fee_paise, effective_from, effective_to, version " +
                "FROM vrms_private.rate_plans " +
                "WHERE company_id = ? AND (vehicle_id = ? OR category_id = ?) " +
                "ORDER BY vehicle_id NULLS LAST, created_at DESC LIMIT 1")) {
            ps.setObject(1, companyId);
            ps.setObject(2, vehicleId);
            ps.setObject(3, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new RatePlanRecord(
                            (UUID) rs.getObject("id"),
                            companyId,
                            (UUID) rs.getObject("vehicle_id"),
                            (UUID) rs.getObject("category_id"),
                            rs.getLong("daily_rate_paise"),
                            rs.getLong("hourly_rate_paise"),
                            rs.getLong("driver_daily_fee_paise"),
                            rs.getLong("driver_hourly_fee_paise"),
                            rs.getTimestamp("effective_from") != null ? rs.getTimestamp("effective_from").toInstant() : null,
                            rs.getTimestamp("effective_to") != null ? rs.getTimestamp("effective_to").toInstant() : null,
                            rs.getLong("version")
                    );
                }
            }
        }
        // Fallback default rates (2400 INR daily / 100 INR hourly, 800 INR driver fee)
        return new RatePlanRecord(null, companyId, vehicleId, categoryId, 240000L, 10000L, 80000L, 3333L, Instant.now(), null, 1L);
    }

    @Override
    public boolean isVehicleAvailable(UUID companyId, UUID vehicleId, Instant startTime, Instant endTime) {
        try (Connection conn = dataSource.getConnection()) {
            return checkVehicleAvailability(conn, companyId, vehicleId, startTime, endTime);
        } catch (SQLException e) {
            throw new IllegalStateException("Error checking vehicle availability", e);
        }
    }

    private boolean checkVehicleAvailability(Connection conn, UUID companyId, UUID vehicleId, Instant startTime, Instant endTime) throws SQLException {
        // 1. Check active reservations overlap [start_time, end_time) with [startTime, endTime)
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM vrms_private.vehicle_reservations " +
                "WHERE company_id = ? AND vehicle_id = ? AND status = 'ACTIVE' " +
                "AND start_time < ? AND end_time > ?")) {
            ps.setObject(1, companyId);
            ps.setObject(2, vehicleId);
            ps.setTimestamp(3, Timestamp.from(endTime));
            ps.setTimestamp(4, Timestamp.from(startTime));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return false; // Reserved
                }
            }
        }

        // 2. Check active freezes/restrictions
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM vrms_private.asset_restrictions " +
                "WHERE company_id = ? AND asset_id = ? AND asset_type = 'VEHICLE' AND active = true")) {
            ps.setObject(1, companyId);
            ps.setObject(2, vehicleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return false; // Frozen/Restricted
                }
            }
        }

        // 3. Check document validity (expiry before end time)
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM vrms_private.vehicle_documents " +
                "WHERE company_id = ? AND vehicle_id = ? " +
                "AND expiry_date IS NOT NULL AND expiry_date < ?::date")) {
            ps.setObject(1, companyId);
            ps.setObject(2, vehicleId);
            ps.setDate(3, Date.valueOf(LocalDate.ofInstant(endTime, java.time.ZoneId.of("Asia/Kolkata"))));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return false; // Expired document
                }
            }
        }

        return true;
    }

    @Override
    public boolean isDriverAvailable(UUID companyId, UUID driverId, Instant startTime, Instant endTime) {
        try (Connection conn = dataSource.getConnection()) {
            return checkDriverAvailability(conn, companyId, driverId, startTime, endTime);
        } catch (SQLException e) {
            throw new IllegalStateException("Error checking driver availability", e);
        }
    }

    private boolean checkDriverAvailability(Connection conn, UUID companyId, UUID driverId, Instant startTime, Instant endTime) throws SQLException {
        if (driverId == null) {
            return false;
        }

        // 1. Check active driver reservations overlap [start_time, end_time) with [startTime, endTime)
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM vrms_private.driver_reservations " +
                "WHERE company_id = ? AND driver_id = ? AND status = 'ACTIVE' " +
                "AND start_time < ? AND end_time > ?")) {
            ps.setObject(1, companyId);
            ps.setObject(2, driverId);
            ps.setTimestamp(3, Timestamp.from(endTime));
            ps.setTimestamp(4, Timestamp.from(startTime));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return false; // Driver already reserved
                }
            }
        }

        // 2. Check driver licence expiry
        LocalDate endDate = LocalDate.ofInstant(endTime, java.time.ZoneId.of("Asia/Kolkata"));
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT licence_expiry FROM vrms_private.driver_profiles " +
                "WHERE company_id = ? AND id = ?")) {
            ps.setObject(1, companyId);
            ps.setObject(2, driverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Date expiry = rs.getDate(1);
                    if (expiry != null && expiry.toLocalDate().isBefore(endDate)) {
                        return false; // Licence expires before trip ends
                    }
                } else {
                    return false; // Driver not found
                }
            }
        }

        // 3. Check driver leave in attendance records
        LocalDate startDate = LocalDate.ofInstant(startTime, java.time.ZoneId.of("Asia/Kolkata"));
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM vrms_private.attendance_records a " +
                "JOIN vrms_private.driver_profiles d ON d.employee_id = a.employee_id " +
                "WHERE a.company_id = ? AND d.id = ? AND a.status = 'ON_LEAVE' " +
                "AND a.record_date >= ? AND a.record_date <= ?")) {
            ps.setObject(1, companyId);
            ps.setObject(2, driverId);
            ps.setDate(3, Date.valueOf(startDate));
            ps.setDate(4, Date.valueOf(endDate));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return false; // Driver on approved leave
                }
            }
        }

        return true;
    }

    private String computeCommandHash(BookingConfirmationCommand cmd) {
        StringBuilder sb = new StringBuilder();
        if (cmd.existingCustomerId() != null) sb.append(cmd.existingCustomerId()).append(";");
        else sb.append(cmd.customerFullName()).append(";").append(cmd.customerContact()).append(";");
        sb.append(cmd.startTime()).append(";").append(cmd.endTime()).append(";");
        sb.append(cmd.agreedAdvancePaise()).append(";").append(cmd.depositPaise()).append(";");
        if (cmd.items() != null) {
            for (var item : cmd.items()) {
                sb.append(item.vehicleId()).append(":").append(item.driverRequired()).append(":").append(item.driverId()).append(";");
            }
        }
        return in.smartvrms.server.security.JdbcIdempotencyService.computeRequestHash(sb.toString());
    }

    @Override
    public BookingRecord confirmBooking(StaffSession session, BookingConfirmationCommand command) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(command, "command");

        if (command.startTime() == null || command.endTime() == null) {
            throw new IllegalArgumentException("Booking start time and end time are required.");
        }
        if (!command.endTime().isAfter(command.startTime())) {
            throw new IllegalArgumentException("Booking end time must be after start time.");
        }
        if (command.items() == null || command.items().isEmpty()) {
            throw new IllegalArgumentException("Booking must contain at least one vehicle.");
        }

        // Reject duplicate physical vehicle in same package (FR-BOOK-002)
        Set<UUID> vehicleIds = new HashSet<>();
        for (BookingConfirmationCommand.PackageItem item : command.items()) {
            if (!vehicleIds.add(item.vehicleId())) {
                throw new IllegalArgumentException("Duplicate physical vehicle in package is rejected: " + item.vehicleId());
            }
        }

        UUID companyId = session.companyId();

        // Idempotency check (FR-BOOK-010)
        String idempotencyKey = command.idempotencyKey();
        String requestHash = computeCommandHash(command);
        boolean recordPendingNeeded = false;
        if (idempotencyPort != null && idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existingRecord = idempotencyPort.check(session, idempotencyKey, "CONFIRM_BOOKING", requestHash);
            if (existingRecord.isPresent()) {
                var rec = existingRecord.get();
                if ("COMPLETED".equals(rec.status()) && rec.responseBody() != null && !rec.responseBody().isBlank()) {
                    try {
                        UUID cachedBookingId = UUID.fromString(rec.responseBody().trim());
                        BookingRecord cached = getBookingById(companyId, cachedBookingId);
                        if (cached != null) {
                            return cached;
                        }
                    } catch (Exception ignored) {}
                }
            } else {
                idempotencyPort.recordPending(session, idempotencyKey, "CONFIRM_BOOKING", requestHash, 86400);
                recordPendingNeeded = true;
            }
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Set transaction context
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT set_config('vrms.company_id', ?, true), " +
                        "       set_config('vrms.user_id', ?, true), " +
                        "       set_config('vrms.role', ?, true)")) {
                    ps.setString(1, companyId.toString());
                    ps.setString(2, session.userId().toString());
                    ps.setString(3, session.role());
                    ps.execute();
                }

                // Stable Resource Locking to prevent concurrent allocation deadlocks (FR-BOOK-010)
                List<UUID> sortedVehicleIds = new ArrayList<>(vehicleIds);
                sortedVehicleIds.sort(Comparator.naturalOrder());

                List<UUID> sortedDriverIds = command.items().stream()
                        .filter(BookingConfirmationCommand.PackageItem::driverRequired)
                        .map(BookingConfirmationCommand.PackageItem::driverId)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.naturalOrder())
                        .toList();

                for (UUID vId : sortedVehicleIds) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id FROM vrms_private.vehicles WHERE company_id = ? AND id = ? FOR UPDATE")) {
                        ps.setObject(1, companyId);
                        ps.setObject(2, vId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalArgumentException("Vehicle not found: " + vId);
                            }
                        }
                    }
                }

                for (UUID dId : sortedDriverIds) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id FROM vrms_private.driver_profiles WHERE company_id = ? AND id = ? FOR UPDATE")) {
                        ps.setObject(1, companyId);
                        ps.setObject(2, dId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalArgumentException("Driver not found: " + dId);
                            }
                        }
                    }
                }

                // Recheck availability under row locks
                for (BookingConfirmationCommand.PackageItem item : command.items()) {
                    if (!checkVehicleAvailability(conn, companyId, item.vehicleId(), command.startTime(), command.endTime())) {
                        throw new IllegalStateException("Vehicle " + item.vehicleId() + " is not available for the requested period.");
                    }
                    if (item.driverRequired()) {
                        if (item.driverId() == null) {
                            throw new IllegalArgumentException("Driver required for vehicle " + item.vehicleId() + " but no eligible driver selected.");
                        }
                        if (!checkDriverAvailability(conn, companyId, item.driverId(), command.startTime(), command.endTime())) {
                            throw new IllegalStateException("Driver " + item.driverId() + " is not available for the requested period.");
                        }
                    }
                }

                // Customer and visit resolution (FR-CUST-001, FR-CUST-002, FR-CUST-008)
                UUID customerId = command.existingCustomerId();
                UUID visitId;

                if (customerId != null) {
                    // Verify existing customer belongs to company
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id FROM vrms_private.customers WHERE company_id = ? AND id = ?")) {
                        ps.setObject(1, companyId);
                        ps.setObject(2, customerId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalArgumentException("Customer not found: " + customerId);
                            }
                        }
                    }
                    if (command.ageAtVisit() < 18 || command.ageAtVisit() > 120) {
                        throw new IllegalArgumentException("Age at visit must be between 18 and 120 years.");
                    }
                } else {
                    if (command.customerFullName() == null || command.customerFullName().isBlank() ||
                        command.customerContact() == null || command.customerContact().isBlank()) {
                        throw new IllegalArgumentException("Customer details required for new customer booking.");
                    }
                    if (command.ageAtVisit() < 18 || command.ageAtVisit() > 120) {
                        throw new IllegalArgumentException("Age at visit must be between 18 and 120 years.");
                    }

                    // Create new customer inside booking transaction
                    customerId = UUID.randomUUID();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO vrms_private.customers (id, company_id, full_name, mobile_number, email, address) " +
                            "VALUES (?, ?, ?, ?, ?, ?)")) {
                        ps.setObject(1, customerId);
                        ps.setObject(2, companyId);
                        ps.setString(3, command.customerFullName().trim());
                        ps.setString(4, command.customerContact().trim());
                        ps.setString(5, command.customerEmail());
                        ps.setString(6, command.customerAddress() != null ? command.customerAddress().trim() : "Not Provided");
                        ps.executeUpdate();
                    }
                }

                // Create visit linked to booking
                visitId = UUID.randomUUID();
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO vrms_private.customer_visits (id, company_id, customer_id, visit_date, age_at_visit, purpose) " +
                        "VALUES (?, ?, ?, now(), ?, ?)")) {
                    ps.setObject(1, visitId);
                    ps.setObject(2, companyId);
                    ps.setObject(3, customerId);
                    ps.setInt(4, command.ageAtVisit());
                    ps.setString(5, command.visitReason() != null ? command.visitReason().trim() : "Vehicle Rental");
                    ps.executeUpdate();
                }

                // Compute pricing quote
                BookingQuote quote = calculateQuote(companyId, command.startTime(), command.endTime(), command.items());

                // Generate booking number: BK-YYYYMMDD-XXXX
                String dateStr = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
                int randomSeq = ThreadLocalRandom.current().nextInt(1000, 9999);
                String bookingNumber = "BK-" + dateStr + "-" + randomSeq;

                UUID bookingId = UUID.randomUUID();
                String snapshotJson = buildPricingSnapshotJson(quote);

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO vrms_private.bookings (id, company_id, customer_id, visit_id, booking_number, " +
                        "status, start_time, end_time, base_total_paise, driver_total_paise, tax_paise, total_paise, " +
                        "agreed_advance_paise, actual_advance_received_paise, deposit_paise, pricing_snapshot, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?, ?, 0, ?, ?::jsonb, ?)")) {
                    ps.setObject(1, bookingId);
                    ps.setObject(2, companyId);
                    ps.setObject(3, customerId);
                    ps.setObject(4, visitId);
                    ps.setString(5, bookingNumber);
                    ps.setTimestamp(6, Timestamp.from(command.startTime()));
                    ps.setTimestamp(7, Timestamp.from(command.endTime()));
                    ps.setLong(8, quote.baseTotalPaise());
                    ps.setLong(9, quote.driverTotalPaise());
                    ps.setLong(10, quote.taxPaise());
                    ps.setLong(11, quote.grandTotalPaise());
                    ps.setLong(12, command.agreedAdvancePaise());
                    ps.setLong(13, command.depositPaise());
                    ps.setString(14, snapshotJson);
                    ps.setObject(15, session.userId());
                    ps.executeUpdate();
                }

                List<BookingAssignmentRecord> assignmentRecords = new ArrayList<>();
                for (AssignmentQuote aq : quote.assignments()) {
                    UUID assignmentId = UUID.randomUUID();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO vrms_private.booking_assignments (id, company_id, booking_id, vehicle_id, driver_id, " +
                            "driver_required, start_time, end_time, daily_rate_paise, hourly_rate_paise, vehicle_total_paise, " +
                            "driver_total_paise, assignment_total_paise, status) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED')")) {
                        ps.setObject(1, assignmentId);
                        ps.setObject(2, companyId);
                        ps.setObject(3, bookingId);
                        ps.setObject(4, aq.vehicleId());
                        ps.setObject(5, aq.driverId());
                        ps.setBoolean(6, aq.driverRequired());
                        ps.setTimestamp(7, Timestamp.from(command.startTime()));
                        ps.setTimestamp(8, Timestamp.from(command.endTime()));
                        ps.setLong(9, aq.dailyRatePaise());
                        ps.setLong(10, aq.hourlyRatePaise());
                        ps.setLong(11, aq.vehicleTotalPaise());
                        ps.setLong(12, aq.driverTotalPaise());
                        ps.setLong(13, aq.lineTotalPaise());
                        ps.executeUpdate();
                    }

                    // Insert vehicle reservation window
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO vrms_private.vehicle_reservations (company_id, vehicle_id, booking_id, assignment_id, " +
                            "start_time, end_time, status) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')")) {
                        ps.setObject(1, companyId);
                        ps.setObject(2, aq.vehicleId());
                        ps.setObject(3, bookingId);
                        ps.setObject(4, assignmentId);
                        ps.setTimestamp(5, Timestamp.from(command.startTime()));
                        ps.setTimestamp(6, Timestamp.from(command.endTime()));
                        ps.executeUpdate();
                    }

                    // Insert driver reservation window if driver assigned
                    if (aq.driverRequired() && aq.driverId() != null) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO vrms_private.driver_reservations (company_id, driver_id, booking_id, assignment_id, " +
                                "start_time, end_time, status) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')")) {
                            ps.setObject(1, companyId);
                            ps.setObject(2, aq.driverId());
                            ps.setObject(3, bookingId);
                            ps.setObject(4, assignmentId);
                            ps.setTimestamp(5, Timestamp.from(command.startTime()));
                            ps.setTimestamp(6, Timestamp.from(command.endTime()));
                            ps.executeUpdate();
                        }
                    }

                    assignmentRecords.add(new BookingAssignmentRecord(
                            assignmentId,
                            companyId,
                            bookingId,
                            aq.vehicleId(),
                            aq.driverId(),
                            aq.driverRequired(),
                            command.startTime(),
                            command.endTime(),
                            aq.dailyRatePaise(),
                            aq.hourlyRatePaise(),
                            aq.vehicleTotalPaise(),
                            aq.driverTotalPaise(),
                            aq.lineTotalPaise(),
                            "RESERVED"
                    ));
                }

                // Record audit event
                if (auditPort != null) {
                    auditPort.recordEvent(
                            session,
                            "CONFIRM_BOOKING",
                            "Booking",
                            bookingId.toString(),
                            "{\"bookingNumber\":\"" + bookingNumber + "\",\"totalPaise\":" + quote.grandTotalPaise() + "}"
                    );
                }

                conn.commit();

                if (idempotencyPort != null && idempotencyKey != null && !idempotencyKey.isBlank()) {
                    try {
                        idempotencyPort.recordCompletion(session, idempotencyKey, 201, bookingId.toString());
                    } catch (Exception ignored) {}
                }

                return new BookingRecord(
                        bookingId,
                        companyId,
                        customerId,
                        visitId,
                        bookingNumber,
                        "CONFIRMED",
                        command.startTime(),
                        command.endTime(),
                        quote.baseTotalPaise(),
                        quote.driverTotalPaise(),
                        quote.taxPaise(),
                        quote.grandTotalPaise(),
                        command.agreedAdvancePaise(),
                        0L,
                        command.depositPaise(),
                        snapshotJson,
                        assignmentRecords,
                        session.userId(),
                        Instant.now(),
                        1L
                );
            } catch (Exception e) {
                conn.rollback();
                if (idempotencyPort != null && idempotencyKey != null && !idempotencyKey.isBlank() && recordPendingNeeded) {
                    try {
                        idempotencyPort.recordFailure(session, idempotencyKey, 400, e.getMessage());
                    } catch (Exception ignored) {}
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to commit atomic booking transaction", e);
        }
    }

    private String buildPricingSnapshotJson(BookingQuote quote) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"baseTotalPaise\":").append(quote.baseTotalPaise()).append(",");
        sb.append("\"driverTotalPaise\":").append(quote.driverTotalPaise()).append(",");
        sb.append("\"taxPaise\":").append(quote.taxPaise()).append(",");
        sb.append("\"grandTotalPaise\":").append(quote.grandTotalPaise()).append(",");
        sb.append("\"durationHours\":").append(quote.durationHours()).append(",");
        sb.append("\"billableDays\":").append(quote.billableDays()).append(",");
        sb.append("\"billableRemainderHours\":").append(quote.billableRemainderHours()).append(",");
        sb.append("\"items\":[");
        for (int i = 0; i < quote.assignments().size(); i++) {
            AssignmentQuote aq = quote.assignments().get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
              .append("\"vehicleId\":\"").append(aq.vehicleId()).append("\",")
              .append("\"vehicleName\":\"").append(aq.vehicleName()).append("\",")
              .append("\"plate\":\"").append(aq.registrationPlate()).append("\",")
              .append("\"driverRequired\":").append(aq.driverRequired()).append(",");
            if (aq.driverId() != null) {
                sb.append("\"driverId\":\"").append(aq.driverId()).append("\",");
            }
            sb.append("\"lineTotalPaise\":").append(aq.lineTotalPaise())
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public BookingRecord getBookingById(UUID companyId, UUID bookingId) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, company_id, customer_id, visit_id, booking_number, status, " +
                    "start_time, end_time, base_total_paise, driver_total_paise, tax_paise, total_paise, " +
                    "agreed_advance_paise, actual_advance_received_paise, deposit_paise, pricing_snapshot, " +
                    "created_by, created_at, version " +
                    "FROM vrms_private.bookings WHERE company_id = ? AND id = ?")) {
                ps.setObject(1, companyId);
                ps.setObject(2, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        List<BookingAssignmentRecord> assignments = fetchAssignments(conn, companyId, bookingId);
                        return new BookingRecord(
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("company_id"),
                                (UUID) rs.getObject("customer_id"),
                                (UUID) rs.getObject("visit_id"),
                                rs.getString("booking_number"),
                                rs.getString("status"),
                                rs.getTimestamp("start_time").toInstant(),
                                rs.getTimestamp("end_time").toInstant(),
                                rs.getLong("base_total_paise"),
                                rs.getLong("driver_total_paise"),
                                rs.getLong("tax_paise"),
                                rs.getLong("total_paise"),
                                rs.getLong("agreed_advance_paise"),
                                rs.getLong("actual_advance_received_paise"),
                                rs.getLong("deposit_paise"),
                                rs.getString("pricing_snapshot"),
                                assignments,
                                (UUID) rs.getObject("created_by"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getLong("version")
                        );
                    }
                }
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query booking by id", e);
        }
    }

    private List<BookingAssignmentRecord> fetchAssignments(Connection conn, UUID companyId, UUID bookingId) throws SQLException {
        List<BookingAssignmentRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, company_id, booking_id, vehicle_id, driver_id, driver_required, " +
                "start_time, end_time, daily_rate_paise, hourly_rate_paise, vehicle_total_paise, " +
                "driver_total_paise, assignment_total_paise, status " +
                "FROM vrms_private.booking_assignments WHERE company_id = ? AND booking_id = ?")) {
            ps.setObject(1, companyId);
            ps.setObject(2, bookingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new BookingAssignmentRecord(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("company_id"),
                            (UUID) rs.getObject("booking_id"),
                            (UUID) rs.getObject("vehicle_id"),
                            (UUID) rs.getObject("driver_id"),
                            rs.getBoolean("driver_required"),
                            rs.getTimestamp("start_time").toInstant(),
                            rs.getTimestamp("end_time").toInstant(),
                            rs.getLong("daily_rate_paise"),
                            rs.getLong("hourly_rate_paise"),
                            rs.getLong("vehicle_total_paise"),
                            rs.getLong("driver_total_paise"),
                            rs.getLong("assignment_total_paise"),
                            rs.getString("status")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public List<BookingRecord> listBookings(UUID companyId, int limit, int offset) {
        List<BookingRecord> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, company_id, customer_id, visit_id, booking_number, status, " +
                    "start_time, end_time, base_total_paise, driver_total_paise, tax_paise, total_paise, " +
                    "agreed_advance_paise, actual_advance_received_paise, deposit_paise, pricing_snapshot, " +
                    "created_by, created_at, version " +
                    "FROM vrms_private.bookings WHERE company_id = ? " +
                    "ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
                ps.setObject(1, companyId);
                ps.setInt(2, Math.max(1, Math.min(limit, 100)));
                ps.setInt(3, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID bId = (UUID) rs.getObject("id");
                        List<BookingAssignmentRecord> assignments = fetchAssignments(conn, companyId, bId);
                        list.add(new BookingRecord(
                                bId,
                                (UUID) rs.getObject("company_id"),
                                (UUID) rs.getObject("customer_id"),
                                (UUID) rs.getObject("visit_id"),
                                rs.getString("booking_number"),
                                rs.getString("status"),
                                rs.getTimestamp("start_time").toInstant(),
                                rs.getTimestamp("end_time").toInstant(),
                                rs.getLong("base_total_paise"),
                                rs.getLong("driver_total_paise"),
                                rs.getLong("tax_paise"),
                                rs.getLong("total_paise"),
                                rs.getLong("agreed_advance_paise"),
                                rs.getLong("actual_advance_received_paise"),
                                rs.getLong("deposit_paise"),
                                rs.getString("pricing_snapshot"),
                                assignments,
                                (UUID) rs.getObject("created_by"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getLong("version")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list bookings", e);
        }
        return list;
    }

    @Override
    public RatePlanRecord configureRatePlan(StaffSession session, RatePlanRecord plan) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(plan, "plan");
        if (!"ADMINISTRATOR".equals(session.role())) {
            throw new SecurityException("Only Administrator can configure rate plans.");
        }

        UUID id = plan.id() != null ? plan.id() : UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO vrms_private.rate_plans (id, company_id, vehicle_id, category_id, " +
                    "daily_rate_paise, hourly_rate_paise, driver_daily_fee_paise, driver_hourly_fee_paise, effective_from, effective_to) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (id) DO UPDATE SET daily_rate_paise = EXCLUDED.daily_rate_paise, " +
                    "hourly_rate_paise = EXCLUDED.hourly_rate_paise, driver_daily_fee_paise = EXCLUDED.driver_daily_fee_paise, " +
                    "driver_hourly_fee_paise = EXCLUDED.driver_hourly_fee_paise, version = vrms_private.rate_plans.version + 1")) {
                ps.setObject(1, id);
                ps.setObject(2, session.companyId());
                ps.setObject(3, plan.vehicleId());
                ps.setObject(4, plan.categoryId());
                ps.setLong(5, plan.dailyRatePaise());
                ps.setLong(6, plan.hourlyRatePaise());
                ps.setLong(7, plan.driverDailyFeePaise());
                ps.setLong(8, plan.driverHourlyFeePaise());
                ps.setTimestamp(9, plan.effectiveFrom() != null ? Timestamp.from(plan.effectiveFrom()) : Timestamp.from(Instant.now()));
                ps.setTimestamp(10, plan.effectiveTo() != null ? Timestamp.from(plan.effectiveTo()) : null);
                ps.executeUpdate();
            }
            return new RatePlanRecord(
                    id,
                    session.companyId(),
                    plan.vehicleId(),
                    plan.categoryId(),
                    plan.dailyRatePaise(),
                    plan.hourlyRatePaise(),
                    plan.driverDailyFeePaise(),
                    plan.driverHourlyFeePaise(),
                    plan.effectiveFrom(),
                    plan.effectiveTo(),
                    1L
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to configure rate plan", e);
        }
    }

    @Override
    public RatePlanRecord getRatePlanForVehicle(UUID companyId, UUID vehicleId) {
        try (Connection conn = dataSource.getConnection()) {
            return fetchRatePlan(conn, companyId, vehicleId, null);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fetch rate plan", e);
        }
    }
}
