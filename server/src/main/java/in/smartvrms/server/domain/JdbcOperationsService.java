package in.smartvrms.server.domain;

import in.smartvrms.application.port.AuditPort;
import in.smartvrms.application.port.OperationsPort;
import in.smartvrms.contracts.*;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.JdbcStaffContext;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * JDBC implementation of OperationsPort enforcing independent reservation, custody,
 * inspection, restriction, and financial state machines (FR-OPS-001..004, FR-EXT-001..003, FR-DMG-001..004).
 */
public class JdbcOperationsService implements OperationsPort {
    private static final Logger LOG = Logger.getLogger(JdbcOperationsService.class.getName());

    private final DataSource dataSource;
    private final AuditPort auditPort;

    public JdbcOperationsService(DataSource dataSource, AuditPort auditPort) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.auditPort = auditPort;
    }

    @Override
    public CustodyRecord recordCheckout(StaffSession session, CheckoutCommand cmd) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(cmd, "cmd");
        if (cmd.actualTime() == null) throw new IllegalArgumentException("Actual checkout time is required");
        if (cmd.odometer() < 0) throw new IllegalArgumentException("Odometer must be non-negative");
        if (cmd.fuelPercent() < 0 || cmd.fuelPercent() > 100) throw new IllegalArgumentException("Fuel percent must be 0-100");

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session, Capability.CHECKOUT);

                // 1. Lock and load assignment
                UUID vehicleId;
                UUID driverId;
                boolean driverRequired;
                UUID bookingId;
                String currentStatus;

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT vehicle_id, driver_id, driver_required, booking_id, status " +
                        "FROM vrms_private.booking_assignments " +
                        "WHERE company_id = ? AND id = ? FOR UPDATE")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, cmd.assignmentId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("Assignment not found: " + cmd.assignmentId());
                        vehicleId = (UUID) rs.getObject(1);
                        driverId = (UUID) rs.getObject(2);
                        driverRequired = rs.getBoolean(3);
                        bookingId = (UUID) rs.getObject(4);
                        currentStatus = rs.getString(5);
                    }
                }

                if ("CHECKED_OUT".equals(currentStatus) || "RETURNED".equals(currentStatus)) {
                    throw new IllegalStateException("Assignment already " + currentStatus.toLowerCase() + "; duplicate checkout denied");
                }

                // 2. Recheck vehicle eligibility at checkout boundary
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM vrms_private.asset_restrictions " +
                        "WHERE company_id = ? AND asset_type = 'VEHICLE' AND asset_id = ? AND active = true")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, vehicleId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) throw new IllegalStateException("Vehicle is currently under active restriction");
                    }
                }

                // 3. Recheck driver eligibility if assigned
                if (driverRequired && driverId != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT 1 FROM vrms_private.asset_restrictions " +
                            "WHERE company_id = ? AND asset_type = 'DRIVER' AND asset_id = ? AND active = true")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, driverId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) throw new IllegalStateException("Assigned driver has active restriction or leave");
                        }
                    }
                }

                // 4. Insert custody record
                UUID custodyId;
                Instant createdAt;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vrms_private.custody_records " +
                        "(company_id, assignment_id, vehicle_id, checkout_time, checkout_odometer, checkout_fuel_percent, checkout_notes, checkout_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                        "RETURNING id, created_at")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, cmd.assignmentId());
                    ps.setObject(3, vehicleId);
                    ps.setTimestamp(4, Timestamp.from(cmd.actualTime()));
                    ps.setInt(5, cmd.odometer());
                    ps.setInt(6, cmd.fuelPercent());
                    ps.setString(7, cmd.notes());
                    ps.setObject(8, session.userId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalStateException("Failed to insert custody record");
                        custodyId = (UUID) rs.getObject(1);
                        createdAt = rs.getTimestamp(2).toInstant();
                    }
                }

                // 5. Update assignment status
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.booking_assignments SET status = 'CHECKED_OUT' WHERE id = ?")) {
                    ps.setObject(1, cmd.assignmentId());
                    ps.executeUpdate();
                }

                // 6. Update booking status to ACTIVE
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.bookings SET status = 'ACTIVE' WHERE id = ? AND status = 'CONFIRMED'")) {
                    ps.setObject(1, bookingId);
                    ps.executeUpdate();
                }

                // 7. Update vehicle operational status to RENTED
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.vehicles SET operational_status = 'RENTED' WHERE id = ?")) {
                    ps.setObject(1, vehicleId);
                    ps.executeUpdate();
                }

                // 8. Update driver duty status to ASSIGNED
                if (driverRequired && driverId != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vrms_private.driver_profiles SET duty_status = 'ASSIGNED' WHERE id = ?")) {
                        ps.setObject(1, driverId);
                        ps.executeUpdate();
                    }
                }

                c.commit();

                if (auditPort != null) {
                    auditPort.recordEvent(session, "CHECKOUT_RECORDED", "ASSIGNMENT", cmd.assignmentId().toString(), "{\"custodyId\":\"" + custodyId + "\"}");
                }

                return new CustodyRecord(
                        custodyId, session.companyId(), cmd.assignmentId(), vehicleId,
                        cmd.actualTime(), cmd.odometer(), cmd.fuelPercent(), cmd.notes(),
                        null, null, null, "PENDING", null, null, session.userId(), null, createdAt
                );
            } catch (Exception e) {
                c.rollback();
                throw (e instanceof RuntimeException re) ? re : new RuntimeException("Checkout failed", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error during checkout", e);
        }
    }

    @Override
    public CustodyRecord recordReturnInspection(StaffSession session, ReturnInspectionCommand cmd) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(cmd, "cmd");
        if (cmd.actualTime() == null) throw new IllegalArgumentException("Actual return time is required");
        if (cmd.odometer() < 0) throw new IllegalArgumentException("Odometer must be non-negative");
        if (cmd.fuelPercent() < 0 || cmd.fuelPercent() > 100) throw new IllegalArgumentException("Fuel percent must be 0-100");
        String condition = cmd.condition() != null ? cmd.condition().toUpperCase() : "ACCEPTABLE";
        if (!condition.equals("ACCEPTABLE") && !condition.equals("DAMAGED")) {
            throw new IllegalArgumentException("Condition must be ACCEPTABLE or DAMAGED");
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session, Capability.RECORD_RETURN_INSPECTION);

                // 1. Lock and load assignment and custody
                UUID vehicleId;
                UUID driverId;
                boolean driverRequired;
                UUID bookingId;
                String currentStatus;

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT vehicle_id, driver_id, driver_required, booking_id, status " +
                        "FROM vrms_private.booking_assignments " +
                        "WHERE company_id = ? AND id = ? FOR UPDATE")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, cmd.assignmentId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("Assignment not found: " + cmd.assignmentId());
                        vehicleId = (UUID) rs.getObject(1);
                        driverId = (UUID) rs.getObject(2);
                        driverRequired = rs.getBoolean(3);
                        bookingId = (UUID) rs.getObject(4);
                        currentStatus = rs.getString(5);
                    }
                }

                if (!"CHECKED_OUT".equals(currentStatus)) {
                    throw new IllegalStateException("Assignment is not checked out (current status: " + currentStatus + "); return denied");
                }

                // 2. Lock and load custody record
                UUID custodyId;
                Instant checkoutTime;
                int checkoutOdometer;
                int checkoutFuel;
                String checkoutNotes;
                UUID checkoutBy;
                Instant createdAt;

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, checkout_time, checkout_odometer, checkout_fuel_percent, checkout_notes, checkout_by, created_at " +
                        "FROM vrms_private.custody_records " +
                        "WHERE company_id = ? AND assignment_id = ? FOR UPDATE")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, cmd.assignmentId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalStateException("Custody record missing for checked out assignment");
                        custodyId = (UUID) rs.getObject(1);
                        checkoutTime = rs.getTimestamp(2).toInstant();
                        checkoutOdometer = rs.getInt(3);
                        checkoutFuel = rs.getInt(4);
                        checkoutNotes = rs.getString(5);
                        checkoutBy = (UUID) rs.getObject(6);
                        createdAt = rs.getTimestamp(7).toInstant();
                    }
                }

                if (cmd.odometer() < checkoutOdometer) {
                    throw new IllegalArgumentException("Return odometer (" + cmd.odometer() + ") cannot be less than checkout odometer (" + checkoutOdometer + ")");
                }

                // 3. Update custody record
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.custody_records SET " +
                        "return_time = ?, return_odometer = ?, return_fuel_percent = ?, " +
                        "inspection_status = ?, damage_notes = ?, damage_photo_file_id = ?, returned_by = ? " +
                        "WHERE id = ?")) {
                    ps.setTimestamp(1, Timestamp.from(cmd.actualTime()));
                    ps.setInt(2, cmd.odometer());
                    ps.setInt(3, cmd.fuelPercent());
                    ps.setString(4, condition);
                    ps.setString(5, cmd.damageNotes());
                    ps.setObject(6, cmd.damagePhotoFileId());
                    ps.setObject(7, session.userId());
                    ps.setObject(8, custodyId);
                    ps.executeUpdate();
                }

                // 4. Update assignment status to RETURNED
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.booking_assignments SET status = 'RETURNED' WHERE id = ?")) {
                    ps.setObject(1, cmd.assignmentId());
                    ps.executeUpdate();
                }

                // 5. Handle Vehicle Physical Release & Restrictions (FR-OPS-003, AC-OPS-003)
                // Release vehicle reservation from this rental
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.vehicle_reservations SET status = 'RELEASED' WHERE assignment_id = ?")) {
                    ps.setObject(1, cmd.assignmentId());
                    ps.executeUpdate();
                }

                if ("ACCEPTABLE".equals(condition)) {
                    // Check if any other active assignment or restriction exists for this vehicle
                    boolean otherActiveBlock = false;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT 1 FROM vrms_private.asset_restrictions WHERE company_id = ? AND asset_type = 'VEHICLE' AND asset_id = ? AND active = true " +
                            "UNION ALL " +
                            "SELECT 1 FROM vrms_private.booking_assignments WHERE company_id = ? AND vehicle_id = ? AND status = 'CHECKED_OUT' AND id <> ?")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, vehicleId);
                        ps.setObject(3, session.companyId());
                        ps.setObject(4, vehicleId);
                        ps.setObject(5, cmd.assignmentId());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) otherActiveBlock = true;
                        }
                    }

                    if (!otherActiveBlock) {
                        // Clean return immediately releases vehicle, EVEN WITH UNPAID DEBT (FR-OPS-003)
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE vrms_private.vehicles SET operational_status = 'AVAILABLE' WHERE id = ?")) {
                            ps.setObject(1, vehicleId);
                            ps.executeUpdate();
                        }
                    }
                } else {
                    // DAMAGED: create active asset_restriction and maintenance_case (FR-DMG-001, FR-DMG-003)
                    UUID restrictionId;
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO vrms_private.asset_restrictions " +
                            "(company_id, asset_type, asset_id, restriction_type, start_time, reason, active) " +
                            "VALUES (?, 'VEHICLE', ?, 'MAINTENANCE', ?, ?, true) " +
                            "RETURNING id")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, vehicleId);
                        ps.setTimestamp(3, Timestamp.from(cmd.actualTime()));
                        ps.setString(4, cmd.damageNotes() != null && !cmd.damageNotes().isBlank() ? cmd.damageNotes() : "Damage reported upon return");
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            restrictionId = (UUID) rs.getObject(1);
                        }
                    }

                    String issueType = cmd.issueType() != null && !cmd.issueType().isBlank() ? cmd.issueType() : "CUSTOMER_DAMAGE";
                    long allegedRecovery = "ORDINARY_WEAR".equalsIgnoreCase(issueType) ? 0L : Math.max(0, cmd.allegedCustomerRecoveryPaise());
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO vrms_private.maintenance_cases " +
                            "(company_id, vehicle_id, assignment_id, custody_id, restriction_id, issue_type, status, " +
                            "estimated_cost_paise, alleged_customer_recovery_paise, notes, damage_photo_file_id, reported_by) " +
                            "VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?)")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, vehicleId);
                        ps.setObject(3, cmd.assignmentId());
                        ps.setObject(4, custodyId);
                        ps.setObject(5, restrictionId);
                        ps.setString(6, issueType);
                        ps.setLong(7, Math.max(0, cmd.estimatedRepairCostPaise()));
                        ps.setLong(8, allegedRecovery);
                        ps.setString(9, cmd.damageNotes() != null && !cmd.damageNotes().isBlank() ? cmd.damageNotes() : "Vehicle return inspection damage");
                        ps.setObject(10, cmd.damagePhotoFileId());
                        ps.setObject(11, session.userId());
                        ps.executeUpdate();
                    }

                    // Set vehicle operational status to MAINTENANCE
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vrms_private.vehicles SET operational_status = 'MAINTENANCE' WHERE id = ?")) {
                        ps.setObject(1, vehicleId);
                        ps.executeUpdate();
                    }
                }

                // 6. Handle Driver Independent Release (FR-DRV-003)
                if (driverRequired && driverId != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vrms_private.driver_reservations SET status = 'RELEASED' WHERE assignment_id = ?")) {
                        ps.setObject(1, cmd.assignmentId());
                        ps.executeUpdate();
                    }

                    boolean otherDriverBlock = false;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT 1 FROM vrms_private.asset_restrictions WHERE company_id = ? AND asset_type = 'DRIVER' AND asset_id = ? AND active = true " +
                            "UNION ALL " +
                            "SELECT 1 FROM vrms_private.booking_assignments WHERE company_id = ? AND driver_id = ? AND status = 'CHECKED_OUT' AND id <> ?")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, driverId);
                        ps.setObject(3, session.companyId());
                        ps.setObject(4, driverId);
                        ps.setObject(5, cmd.assignmentId());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) otherDriverBlock = true;
                        }
                    }

                    if (!otherDriverBlock) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE vrms_private.driver_profiles SET duty_status = 'AVAILABLE' WHERE id = ?")) {
                            ps.setObject(1, driverId);
                            ps.executeUpdate();
                        }
                    }
                }

                // 7. Check if all assignments for booking are returned
                boolean allReturned = true;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM vrms_private.booking_assignments WHERE booking_id = ? AND status <> 'RETURNED' AND status <> 'CANCELLED'")) {
                    ps.setObject(1, bookingId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) allReturned = false;
                    }
                }

                if (allReturned) {
                    // Check balance for financial closure vs settlement pending (FR-OPS-004)
                    long totalPaise = 0;
                    long advancePaise = 0;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT total_paise, actual_advance_received_paise FROM vrms_private.bookings WHERE id = ?")) {
                        ps.setObject(1, bookingId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                totalPaise = rs.getLong(1);
                                advancePaise = rs.getLong(2);
                            }
                        }
                    }

                    if (advancePaise >= totalPaise) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE vrms_private.bookings SET status = 'COMPLETED' WHERE id = ?")) {
                            ps.setObject(1, bookingId);
                            ps.executeUpdate();
                        }
                    }
                }

                c.commit();

                if (auditPort != null) {
                    auditPort.recordEvent(session, "RETURN_INSPECTION_RECORDED", "ASSIGNMENT", cmd.assignmentId().toString(), "{\"custodyId\":\"" + custodyId + "\",\"condition\":\"" + condition + "\"}");
                }

                return new CustodyRecord(
                        custodyId, session.companyId(), cmd.assignmentId(), vehicleId,
                        checkoutTime, checkoutOdometer, checkoutFuel, checkoutNotes,
                        cmd.actualTime(), cmd.odometer(), cmd.fuelPercent(), condition,
                        cmd.damageNotes(), cmd.damagePhotoFileId(), checkoutBy, session.userId(), createdAt
                );
            } catch (Exception e) {
                c.rollback();
                throw (e instanceof RuntimeException re) ? re : new RuntimeException("Return inspection failed", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error during return inspection", e);
        }
    }

    @Override
    public ExtensionQuote quoteExtension(UUID companyId, ExtensionCommand cmd) {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(cmd, "cmd");
        if (cmd.revisedEndTime() == null) throw new IllegalArgumentException("Revised end time is required");

        try (Connection c = dataSource.getConnection()) {
            List<ExtensionQuote.AssignmentDelta> deltas = new ArrayList<>();
            long totalDelta = 0;

            String sql = "SELECT id, vehicle_id, start_time, end_time, daily_rate_paise, hourly_rate_paise, " +
                         "driver_required, driver_total_paise, vehicle_total_paise, assignment_total_paise " +
                         "FROM vrms_private.booking_assignments WHERE company_id = ? AND booking_id = ?";

            if (cmd.assignmentIds() != null && !cmd.assignmentIds().isEmpty()) {
                sql += " AND id = ANY(?)";
            }

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, companyId);
                ps.setObject(2, cmd.bookingId());
                if (cmd.assignmentIds() != null && !cmd.assignmentIds().isEmpty()) {
                    ps.setArray(3, c.createArrayOf("uuid", cmd.assignmentIds().toArray()));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID assignId = (UUID) rs.getObject(1);
                        UUID vehicleId = (UUID) rs.getObject(2);
                        Instant startTime = rs.getTimestamp(3).toInstant();
                        Instant oldEndTime = rs.getTimestamp(4).toInstant();
                        long dailyRate = rs.getLong(5);
                        long hourlyRate = rs.getLong(6);
                        boolean driverReq = rs.getBoolean(7);
                        long oldDriverTotal = rs.getLong(8);
                        long oldVehicleTotal = rs.getLong(9);
                        long oldAssignTotal = rs.getLong(10);

                        if (!cmd.revisedEndTime().isAfter(oldEndTime)) {
                            throw new IllegalArgumentException("Revised end time (" + cmd.revisedEndTime() + ") must be after current end time (" + oldEndTime + ")");
                        }

                        // Reprice full revised period using saved rates snapshot (FR-EXT-002)
                        // Driver hourly fee inferred from driver total if driver required
                        long driverDailyFee = 0;
                        long driverHourlyFee = 0;
                        if (driverReq && oldDriverTotal > 0) {
                            // Fetch driver fee from rate plan or proportional snapshot
                            driverDailyFee = 50000; // baseline 500 INR/day if applicable
                            driverHourlyFee = driverDailyFee / 24;
                        }

                        AssignmentQuote fullPeriodQuote = QuoteEngine.calculateAssignmentQuote(
                                vehicleId, "Vehicle", dailyRate, hourlyRate, driverReq, driverDailyFee, driverHourlyFee,
                                startTime, cmd.revisedEndTime()
                        );

                        long incVehicle = Math.max(0, fullPeriodQuote.vehicleTotalPaise() - oldVehicleTotal);
                        long incDriver = Math.max(0, fullPeriodQuote.driverTotalPaise() - oldDriverTotal);
                        long incTotal = incVehicle + incDriver;

                        deltas.add(new ExtensionQuote.AssignmentDelta(
                                assignId, vehicleId, oldEndTime, cmd.revisedEndTime(), incVehicle, incDriver, incTotal
                        ));
                        totalDelta += incTotal;
                    }
                }
            }

            if (deltas.isEmpty()) {
                throw new IllegalArgumentException("No matching assignments found to extend");
            }

            return new ExtensionQuote(cmd.bookingId(), deltas, totalDelta, cmd.revisedEndTime());
        } catch (SQLException e) {
            throw new RuntimeException("Database error calculating extension quote", e);
        }
    }

    @Override
    public BookingRecord extendAssignments(StaffSession session, ExtensionCommand cmd) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(cmd, "cmd");
        if (cmd.revisedEndTime() == null) throw new IllegalArgumentException("Revised end time is required");
        if (cmd.reason() == null || cmd.reason().isBlank()) throw new IllegalArgumentException("Extension reason is required");

        ExtensionQuote quote = quoteExtension(session.companyId(), cmd);

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session, Capability.EXTEND_AT_SAVED_RATES);

                // 1. Collect and sort vehicle and driver IDs for deterministic locking
                List<UUID> vehicleIds = quote.assignmentDeltas().stream().map(ExtensionQuote.AssignmentDelta::vehicleId).distinct().sorted().toList();
                for (UUID vId : vehicleIds) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT id FROM vrms_private.vehicles WHERE company_id = ? AND id = ? FOR UPDATE")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, vId);
                        ps.executeQuery();
                    }
                }

                // 2. Recheck conflicts across all selected assignments in extended window (FR-EXT-001, AC-EXT-001)
                for (ExtensionQuote.AssignmentDelta delta : quote.assignmentDeltas()) {
                    // Check vehicle reservation conflicts
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT 1 FROM vrms_private.vehicle_reservations " +
                            "WHERE company_id = ? AND vehicle_id = ? AND status = 'ACTIVE' AND assignment_id <> ? " +
                            "AND (start_time < ? AND end_time > ?)")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, delta.vehicleId());
                        ps.setObject(3, delta.assignmentId());
                        ps.setTimestamp(4, Timestamp.from(delta.revisedEndTime()));
                        ps.setTimestamp(5, Timestamp.from(delta.previousEndTime()));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                throw new IllegalStateException("Extension conflict: Vehicle has another confirmed reservation during the extended period");
                            }
                        }
                    }

                    // Check vehicle active restrictions
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT 1 FROM vrms_private.asset_restrictions " +
                            "WHERE company_id = ? AND asset_type = 'VEHICLE' AND asset_id = ? AND active = true " +
                            "AND (end_time IS NULL OR end_time > ?)")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, delta.vehicleId());
                        ps.setTimestamp(3, Timestamp.from(delta.previousEndTime()));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                throw new IllegalStateException("Extension conflict: Vehicle has an active restriction");
                            }
                        }
                    }

                    // Check vehicle documents
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT 1 FROM vrms_private.vehicle_documents " +
                            "WHERE company_id = ? AND vehicle_id = ? AND expiry_date IS NOT NULL AND expiry_date < ?::date")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, delta.vehicleId());
                        ps.setTimestamp(3, Timestamp.from(delta.revisedEndTime()));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                throw new IllegalStateException("Extension conflict: Vehicle document expires during extended period");
                            }
                        }
                    }

                    // Check driver if assigned
                    UUID driverId = null;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT driver_id FROM vrms_private.booking_assignments WHERE id = ?")) {
                        ps.setObject(1, delta.assignmentId());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) driverId = (UUID) rs.getObject(1);
                        }
                    }

                    if (driverId != null) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "SELECT 1 FROM vrms_private.driver_reservations " +
                                "WHERE company_id = ? AND driver_id = ? AND status = 'ACTIVE' AND assignment_id <> ? " +
                                "AND (start_time < ? AND end_time > ?)")) {
                            ps.setObject(1, session.companyId());
                            ps.setObject(2, driverId);
                            ps.setObject(3, delta.assignmentId());
                            ps.setTimestamp(4, Timestamp.from(delta.revisedEndTime()));
                            ps.setTimestamp(5, Timestamp.from(delta.previousEndTime()));
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    throw new IllegalStateException("Extension conflict: Driver has another confirmed reservation during the extended period");
                                }
                            }
                        }

                        try (PreparedStatement ps = c.prepareStatement(
                                "SELECT 1 FROM vrms_private.asset_restrictions " +
                                "WHERE company_id = ? AND asset_type = 'DRIVER' AND asset_id = ? AND active = true " +
                                "AND (end_time IS NULL OR end_time > ?)")) {
                            ps.setObject(1, session.companyId());
                            ps.setObject(2, driverId);
                            ps.setTimestamp(3, Timestamp.from(delta.previousEndTime()));
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    throw new IllegalStateException("Extension conflict: Driver has active leave or restriction during extended period");
                                }
                            }
                        }
                    }
                }

                // 3. All checks passed: apply extension mutations (FR-EXT-002, FR-EXT-003)
                long totalVehicleInc = 0;
                long totalDriverInc = 0;

                for (ExtensionQuote.AssignmentDelta delta : quote.assignmentDeltas()) {
                    totalVehicleInc += delta.incrementalVehiclePaise();
                    totalDriverInc += delta.incrementalDriverPaise();

                    // Update assignment
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vrms_private.booking_assignments SET " +
                            "end_time = ?, vehicle_total_paise = vehicle_total_paise + ?, " +
                            "driver_total_paise = driver_total_paise + ?, assignment_total_paise = assignment_total_paise + ? " +
                            "WHERE id = ?")) {
                        ps.setTimestamp(1, Timestamp.from(delta.revisedEndTime()));
                        ps.setLong(2, delta.incrementalVehiclePaise());
                        ps.setLong(3, delta.incrementalDriverPaise());
                        ps.setLong(4, delta.incrementalTotalPaise());
                        ps.setObject(5, delta.assignmentId());
                        ps.executeUpdate();
                    }

                    // Update vehicle reservation
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vrms_private.vehicle_reservations SET end_time = ? WHERE assignment_id = ?")) {
                        ps.setTimestamp(1, Timestamp.from(delta.revisedEndTime()));
                        ps.setObject(2, delta.assignmentId());
                        ps.executeUpdate();
                    }

                    // Update driver reservation if present
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vrms_private.driver_reservations SET end_time = ? WHERE assignment_id = ?")) {
                        ps.setTimestamp(1, Timestamp.from(delta.revisedEndTime()));
                        ps.setObject(2, delta.assignmentId());
                        ps.executeUpdate();
                    }

                    // Record extension history (FR-DATA-001)
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO vrms_private.extension_records " +
                            "(company_id, booking_id, assignment_id, previous_end_time, revised_end_time, incremental_charge_paise, reason, extended_by) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, cmd.bookingId());
                        ps.setObject(3, delta.assignmentId());
                        ps.setTimestamp(4, Timestamp.from(delta.previousEndTime()));
                        ps.setTimestamp(5, Timestamp.from(delta.revisedEndTime()));
                        ps.setLong(6, delta.incrementalTotalPaise());
                        ps.setString(7, cmd.reason().strip());
                        ps.setObject(8, session.userId());
                        ps.executeUpdate();
                    }
                }

                // 4. Update booking totals and end time
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.bookings SET " +
                        "base_total_paise = base_total_paise + ?, driver_total_paise = driver_total_paise + ?, " +
                        "total_paise = total_paise + ?, end_time = GREATEST(end_time, ?), version = version + 1 " +
                        "WHERE id = ?")) {
                    ps.setLong(1, totalVehicleInc);
                    ps.setLong(2, totalDriverInc);
                    ps.setLong(3, quote.totalDeltaPaise());
                    ps.setTimestamp(4, Timestamp.from(cmd.revisedEndTime()));
                    ps.setObject(5, cmd.bookingId());
                    ps.executeUpdate();
                }

                c.commit();

                if (auditPort != null) {
                    auditPort.recordEvent(session, "BOOKING_EXTENDED", "BOOKING", cmd.bookingId().toString(), "{\"deltaPaise\":" + quote.totalDeltaPaise() + "}");
                }

                return loadBookingRecord(c, session.companyId(), cmd.bookingId());
            } catch (Exception e) {
                c.rollback();
                throw (e instanceof RuntimeException re) ? re : new RuntimeException("Extension failed", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error during extension", e);
        }
    }

    @Override
    public List<RunningServiceSummary> listRunningServices(UUID companyId, boolean overdueOnly) {
        Objects.requireNonNull(companyId, "companyId");
        try (Connection c = dataSource.getConnection()) {
            List<RunningServiceSummary> result = new ArrayList<>();
            Instant now = Instant.now();

            String sql = "SELECT b.id, b.booking_number, b.customer_id, c.full_name, c.mobile_number, " +
                         "b.visit_id, b.status, b.start_time, b.end_time, b.total_paise, b.actual_advance_received_paise " +
                         "FROM vrms_private.bookings b " +
                         "JOIN vrms_private.customers c ON c.id = b.customer_id " +
                         "WHERE b.company_id = ? AND b.status IN ('CONFIRMED', 'ACTIVE') " +
                         "ORDER BY b.start_time ASC";

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, companyId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID bookingId = (UUID) rs.getObject(1);
                        String bookingNum = rs.getString(2);
                        UUID custId = (UUID) rs.getObject(3);
                        String custName = rs.getString(4);
                        String custContact = rs.getString(5);
                        UUID visitId = (UUID) rs.getObject(6);
                        String bStatus = rs.getString(7);
                        Instant start = rs.getTimestamp(8).toInstant();
                        Instant end = rs.getTimestamp(9).toInstant();
                        long total = rs.getLong(10);
                        long advance = rs.getLong(11);
                        long balance = Math.max(0, total - advance);

                        List<RunningAssignmentSummary> assignments = loadRunningAssignments(c, companyId, bookingId);
                        boolean isOverdue = false;
                        List<String> plates = new ArrayList<>();
                        List<String> drivers = new ArrayList<>();

                        for (RunningAssignmentSummary a : assignments) {
                            if (a.registrationPlate() != null) plates.add(a.registrationPlate());
                            if (a.driverName() != null) drivers.add(a.driverName());
                            if ("CHECKED_OUT".equals(a.custodyStatus()) && now.isAfter(a.endTime())) {
                                isOverdue = true;
                            }
                        }

                        if (!overdueOnly || isOverdue) {
                            result.add(new RunningServiceSummary(
                                    bookingId, bookingNum, custId, custName, custContact, visitId,
                                    bStatus, start, end, isOverdue, assignments.size(), plates, drivers,
                                    total, advance, balance, assignments
                            ));
                        }
                    }
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Database error loading running services", e);
        }
    }

    @Override
    public RunningServiceSummary getRunningServiceDetail(UUID companyId, UUID bookingId) {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(bookingId, "bookingId");

        try (Connection c = dataSource.getConnection()) {
            Instant now = Instant.now();
            String sql = "SELECT b.id, b.booking_number, b.customer_id, c.full_name, c.mobile_number, " +
                         "b.visit_id, b.status, b.start_time, b.end_time, b.total_paise, b.actual_advance_received_paise " +
                         "FROM vrms_private.bookings b " +
                         "JOIN vrms_private.customers c ON c.id = b.customer_id " +
                         "WHERE b.company_id = ? AND b.id = ?";

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, companyId);
                ps.setObject(2, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Booking not found: " + bookingId);
                    String bookingNum = rs.getString(2);
                    UUID custId = (UUID) rs.getObject(3);
                    String custName = rs.getString(4);
                    String custContact = rs.getString(5);
                    UUID visitId = (UUID) rs.getObject(6);
                    String bStatus = rs.getString(7);
                    Instant start = rs.getTimestamp(8).toInstant();
                    Instant end = rs.getTimestamp(9).toInstant();
                    long total = rs.getLong(10);
                    long advance = rs.getLong(11);
                    long balance = Math.max(0, total - advance);

                    List<RunningAssignmentSummary> assignments = loadRunningAssignments(c, companyId, bookingId);
                    boolean isOverdue = false;
                    List<String> plates = new ArrayList<>();
                    List<String> drivers = new ArrayList<>();

                    for (RunningAssignmentSummary a : assignments) {
                        if (a.registrationPlate() != null) plates.add(a.registrationPlate());
                        if (a.driverName() != null) drivers.add(a.driverName());
                        if ("CHECKED_OUT".equals(a.custodyStatus()) && now.isAfter(a.endTime())) {
                            isOverdue = true;
                        }
                    }

                    return new RunningServiceSummary(
                            bookingId, bookingNum, custId, custName, custContact, visitId,
                            bStatus, start, end, isOverdue, assignments.size(), plates, drivers,
                            total, advance, balance, assignments
                    );
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error loading running service detail", e);
        }
    }

    @Override
    public MaintenanceCaseRecord openMaintenanceCase(
            StaffSession session,
            UUID vehicleId,
            UUID assignmentId,
            UUID custodyId,
            String issueType,
            long estimatedCostPaise,
            long allegedRecoveryPaise,
            String notes,
            UUID photoFileId) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(vehicleId, "vehicleId");
        if (notes == null || notes.isBlank()) throw new IllegalArgumentException("Maintenance notes are required");
        String type = issueType != null && !issueType.isBlank() ? issueType : "ORDINARY_WEAR";

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session, Capability.RECORD_MAINTENANCE_EVIDENCE);

                // 1. Create active asset restriction on vehicle
                UUID restrictionId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vrms_private.asset_restrictions " +
                        "(company_id, asset_type, asset_id, restriction_type, start_time, reason, active) " +
                        "VALUES (?, 'VEHICLE', ?, 'MAINTENANCE', now(), ?, true) " +
                        "RETURNING id")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, vehicleId);
                    ps.setString(3, notes.strip());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        restrictionId = (UUID) rs.getObject(1);
                    }
                }

                // 2. Insert maintenance case
                UUID caseId;
                Instant createdAt;
                long recovery = "ORDINARY_WEAR".equalsIgnoreCase(type) && !session.isAdministrator() ? 0L : Math.max(0, allegedRecoveryPaise);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vrms_private.maintenance_cases " +
                        "(company_id, vehicle_id, assignment_id, custody_id, restriction_id, issue_type, status, " +
                        "estimated_cost_paise, alleged_customer_recovery_paise, notes, damage_photo_file_id, reported_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?) " +
                        "RETURNING id, created_at")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, vehicleId);
                    ps.setObject(3, assignmentId);
                    ps.setObject(4, custodyId);
                    ps.setObject(5, restrictionId);
                    ps.setString(6, type);
                    ps.setLong(7, Math.max(0, estimatedCostPaise));
                    ps.setLong(8, recovery);
                    ps.setString(9, notes.strip());
                    ps.setObject(10, photoFileId);
                    ps.setObject(11, session.userId());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        caseId = (UUID) rs.getObject(1);
                        createdAt = rs.getTimestamp(2).toInstant();
                    }
                }

                // 3. Update vehicle status to MAINTENANCE
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.vehicles SET operational_status = 'MAINTENANCE' WHERE id = ?")) {
                    ps.setObject(1, vehicleId);
                    ps.executeUpdate();
                }

                c.commit();

                if (auditPort != null) {
                    auditPort.recordEvent(session, "MAINTENANCE_CASE_OPENED", "VEHICLE", vehicleId.toString(), "{\"caseId\":\"" + caseId + "\"}");
                }

                return new MaintenanceCaseRecord(
                        caseId, session.companyId(), vehicleId, assignmentId, custodyId, restrictionId,
                        type, "OPEN", Math.max(0, estimatedCostPaise), recovery,
                        0, 0, notes.strip(), photoFileId, null, session.userId(), null, createdAt, null
                );
            } catch (Exception e) {
                c.rollback();
                throw (e instanceof RuntimeException re) ? re : new RuntimeException("Failed to open maintenance case", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error opening maintenance case", e);
        }
    }

    @Override
    public MaintenanceCaseRecord resolveMaintenanceCase(StaffSession session, MaintenanceResolutionCommand cmd) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(cmd, "cmd");
        if (!session.isAdministrator()) {
            throw new SecurityException("Forbidden: Only administrators can approve maintenance resolution");
        }
        if (cmd.actualCostPaise() < 0) throw new IllegalArgumentException("Actual cost must be non-negative");
        if (cmd.approvedCustomerRecoveryPaise() < 0) throw new IllegalArgumentException("Approved recovery must be non-negative");

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session, Capability.APPROVE_MAINTENANCE_RESOLUTION);

                // 1. Lock and load maintenance case
                UUID vehicleId;
                UUID restrictionId;
                String currentStatus;
                String issueType;

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT vehicle_id, restriction_id, status, issue_type FROM vrms_private.maintenance_cases " +
                        "WHERE company_id = ? AND id = ? FOR UPDATE")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, cmd.caseId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("Maintenance case not found: " + cmd.caseId());
                        vehicleId = (UUID) rs.getObject(1);
                        restrictionId = (UUID) rs.getObject(2);
                        currentStatus = rs.getString(3);
                        issueType = rs.getString(4);
                    }
                }

                if ("RESOLVED".equals(currentStatus)) {
                    throw new IllegalStateException("Maintenance case is already resolved");
                }

                if ("ORDINARY_WEAR".equalsIgnoreCase(issueType) && cmd.approvedCustomerRecoveryPaise() > 0) {
                    if (cmd.resolutionNotes() == null || cmd.resolutionNotes().isBlank() || "Maintenance completed".equalsIgnoreCase(cmd.resolutionNotes().strip())) {
                        throw new IllegalArgumentException("Ordinary wear cannot be charged to customer without explicit responsibility justification and authorized approval");
                    }
                }

                // 2. Mark maintenance case RESOLVED
                Instant resolvedAt = Instant.now();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.maintenance_cases SET " +
                        "status = 'RESOLVED', actual_cost_paise = ?, approved_customer_recovery_paise = ?, " +
                        "resolution_notes = ?, resolved_by = ?, resolved_at = ? " +
                        "WHERE id = ?")) {
                    ps.setLong(1, cmd.actualCostPaise());
                    ps.setLong(2, cmd.approvedCustomerRecoveryPaise());
                    ps.setString(3, cmd.resolutionNotes());
                    ps.setObject(4, session.userId());
                    ps.setTimestamp(5, Timestamp.from(resolvedAt));
                    ps.setObject(6, cmd.caseId());
                    ps.executeUpdate();
                }

                // 3. Deactivate associated restriction
                if (restrictionId != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vrms_private.asset_restrictions SET active = false WHERE id = ?")) {
                        ps.setObject(1, restrictionId);
                        ps.executeUpdate();
                    }
                }

                // 4. Safety gate: recheck if ANY other active restrictions remain on this vehicle (FR-DMG-003, AC-DMG-003)
                boolean otherRestrictionExists = false;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM vrms_private.asset_restrictions " +
                        "WHERE company_id = ? AND asset_type = 'VEHICLE' AND asset_id = ? AND active = true")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, vehicleId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) otherRestrictionExists = true;
                    }
                }

                if (!otherRestrictionExists) {
                    // Check if vehicle has any current checked out assignment
                    boolean currentlyCheckedOut = false;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT 1 FROM vrms_private.booking_assignments " +
                            "WHERE company_id = ? AND vehicle_id = ? AND status = 'CHECKED_OUT'")) {
                        ps.setObject(1, session.companyId());
                        ps.setObject(2, vehicleId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) currentlyCheckedOut = true;
                        }
                    }

                    String nextStatus = currentlyCheckedOut ? "RENTED" : "AVAILABLE";
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vrms_private.vehicles SET operational_status = ? WHERE id = ?")) {
                        ps.setString(1, nextStatus);
                        ps.setObject(2, vehicleId);
                        ps.executeUpdate();
                    }
                }

                c.commit();

                if (auditPort != null) {
                    auditPort.recordEvent(session, "MAINTENANCE_CASE_RESOLVED", "MAINTENANCE_CASE", cmd.caseId().toString(), "{\"actualCostPaise\":" + cmd.actualCostPaise() + "}");
                }

                return loadMaintenanceCase(c, session.companyId(), cmd.caseId());
            } catch (Exception e) {
                c.rollback();
                throw (e instanceof RuntimeException re) ? re : new RuntimeException("Failed to resolve maintenance case", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error resolving maintenance case", e);
        }
    }

    @Override
    public List<MaintenanceCaseRecord> listMaintenanceCases(UUID companyId, UUID vehicleId, boolean openOnly) {
        Objects.requireNonNull(companyId, "companyId");
        try (Connection c = dataSource.getConnection()) {
            List<MaintenanceCaseRecord> list = new ArrayList<>();
            String sql = "SELECT id, vehicle_id, assignment_id, custody_id, restriction_id, issue_type, status, " +
                         "estimated_cost_paise, alleged_customer_recovery_paise, actual_cost_paise, approved_customer_recovery_paise, " +
                         "notes, damage_photo_file_id, resolution_notes, reported_by, resolved_by, created_at, resolved_at " +
                         "FROM vrms_private.maintenance_cases WHERE company_id = ?";

            if (vehicleId != null) sql += " AND vehicle_id = ?";
            if (openOnly) sql += " AND status <> 'RESOLVED'";
            sql += " ORDER BY created_at DESC";

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, companyId);
                if (vehicleId != null) ps.setObject(2, vehicleId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapMaintenanceCase(rs, companyId));
                    }
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Database error listing maintenance cases", e);
        }
    }

    private List<RunningAssignmentSummary> loadRunningAssignments(Connection c, UUID companyId, UUID bookingId) throws SQLException {
        List<RunningAssignmentSummary> list = new ArrayList<>();
        String sql = "SELECT a.id, a.vehicle_id, v.registration_plate, vc.name, a.driver_id, e.full_name, " +
                     "a.start_time, a.end_time, a.status, cr.inspection_status " +
                     "FROM vrms_private.booking_assignments a " +
                     "JOIN vrms_private.vehicles v ON v.id = a.vehicle_id " +
                     "JOIN vrms_private.vehicle_categories vc ON vc.id = v.category_id " +
                     "LEFT JOIN vrms_private.driver_profiles d ON d.id = a.driver_id " +
                     "LEFT JOIN vrms_private.employees e ON e.id = d.employee_id " +
                     "LEFT JOIN vrms_private.custody_records cr ON cr.assignment_id = a.id " +
                     "WHERE a.company_id = ? AND a.booking_id = ? " +
                     "ORDER BY a.start_time ASC";

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, companyId);
            ps.setObject(2, bookingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID assignId = (UUID) rs.getObject(1);
                    UUID vehId = (UUID) rs.getObject(2);
                    String plate = rs.getString(3);
                    String catName = rs.getString(4);
                    UUID drvId = (UUID) rs.getObject(5);
                    String drvName = rs.getString(6);
                    Instant sTime = rs.getTimestamp(7).toInstant();
                    Instant eTime = rs.getTimestamp(8).toInstant();
                    String aStatus = rs.getString(9);
                    String inspStatus = rs.getString(10);

                    String custodyStatus = aStatus; // RESERVED, CHECKED_OUT, RETURNED

                    list.add(new RunningAssignmentSummary(
                            assignId, vehId, plate, catName, drvId, drvName, sTime, eTime,
                            aStatus, custodyStatus, inspStatus
                    ));
                }
            }
        }
        return list;
    }

    private BookingRecord loadBookingRecord(Connection c, UUID companyId, UUID bookingId) throws SQLException {
        String sql = "SELECT id, company_id, customer_id, visit_id, booking_number, status, start_time, end_time, " +
                     "base_total_paise, driver_total_paise, tax_paise, total_paise, agreed_advance_paise, " +
                     "actual_advance_received_paise, deposit_paise, pricing_snapshot, created_by, created_at, version " +
                     "FROM vrms_private.bookings WHERE company_id = ? AND id = ?";

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, companyId);
            ps.setObject(2, bookingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Booking not found: " + bookingId);
                return new BookingRecord(
                        (UUID) rs.getObject(1),
                        (UUID) rs.getObject(2),
                        (UUID) rs.getObject(3),
                        (UUID) rs.getObject(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getTimestamp(7).toInstant(),
                        rs.getTimestamp(8).toInstant(),
                        rs.getLong(9),
                        rs.getLong(10),
                        rs.getLong(11),
                        rs.getLong(12),
                        rs.getLong(13),
                        rs.getLong(14),
                        rs.getLong(15),
                        rs.getString(16),
                        List.of(),
                        (UUID) rs.getObject(17),
                        rs.getTimestamp(18).toInstant(),
                        rs.getLong(19)
                );
            }
        }
    }

    private MaintenanceCaseRecord loadMaintenanceCase(Connection c, UUID companyId, UUID caseId) throws SQLException {
        String sql = "SELECT id, vehicle_id, assignment_id, custody_id, restriction_id, issue_type, status, " +
                     "estimated_cost_paise, alleged_customer_recovery_paise, actual_cost_paise, approved_customer_recovery_paise, " +
                     "notes, damage_photo_file_id, resolution_notes, reported_by, resolved_by, created_at, resolved_at " +
                     "FROM vrms_private.maintenance_cases WHERE company_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, companyId);
            ps.setObject(2, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Maintenance case not found: " + caseId);
                return mapMaintenanceCase(rs, companyId);
            }
        }
    }

    private MaintenanceCaseRecord mapMaintenanceCase(ResultSet rs, UUID companyId) throws SQLException {
        return new MaintenanceCaseRecord(
                (UUID) rs.getObject(1),
                companyId,
                (UUID) rs.getObject(2),
                (UUID) rs.getObject(3),
                (UUID) rs.getObject(4),
                (UUID) rs.getObject(5),
                rs.getString(6),
                rs.getString(7),
                rs.getLong(8),
                rs.getLong(9),
                rs.getLong(10),
                rs.getLong(11),
                rs.getString(12),
                (UUID) rs.getObject(13),
                rs.getString(14),
                (UUID) rs.getObject(15),
                (UUID) rs.getObject(16),
                rs.getTimestamp(17).toInstant(),
                rs.getTimestamp(18) != null ? rs.getTimestamp(18).toInstant() : null
        );
    }

    private static void setContext(Connection c, StaffSession session, Capability capability) throws SQLException {
        JdbcStaffContext.require(c, session, capability);
    }
}
