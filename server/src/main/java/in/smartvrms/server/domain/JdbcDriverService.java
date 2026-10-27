package in.smartvrms.server.domain;

import in.smartvrms.application.port.AuditPort;
import in.smartvrms.application.port.DriverPort;
import in.smartvrms.contracts.DriverProfileRecord;
import in.smartvrms.contracts.StaffSession;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public final class JdbcDriverService implements DriverPort {
    private final DataSource dataSource;
    private final AuditPort auditPort;

    public JdbcDriverService(DataSource dataSource, AuditPort auditPort) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.auditPort = auditPort;
    }

    @Override
    public DriverProfileRecord registerDriverProfile(StaffSession session, UUID employeeId, String licenceNumber, List<String> classes, LocalDate expiry, int experienceYears) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(licenceNumber, "licenceNumber");
        Objects.requireNonNull(expiry, "expiry");
        if (classes == null || classes.isEmpty()) throw new IllegalArgumentException("Driver licence classes required");
        String cleanLicence = licenceNumber.strip();
        if (cleanLicence.length() < 3 || cleanLicence.length() > 50) {
            throw new IllegalArgumentException("Licence number must be between 3 and 50 characters");
        }
        if (experienceYears < 0) {
            throw new IllegalArgumentException("Experience years cannot be negative");
        }

        String lookupEmployeeSql = """
            SELECT full_name, contact_number, employment_status
            FROM vrms_private.employees
            WHERE id = ? AND company_id = ?
            """;

        String insertSql = """
            INSERT INTO vrms_private.driver_profiles
                (company_id, employee_id, licence_number, licence_classes, licence_expiry, experience_years, duty_status)
            VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE')
            RETURNING id
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                in.smartvrms.server.security.JdbcStaffContext.require(c, session, in.smartvrms.server.security.Capability.MANAGE_DRIVER_MASTER);

                String empName;
                String empContact;
                boolean isEmployed;
                try (PreparedStatement es = c.prepareStatement(lookupEmployeeSql)) {
                    es.setObject(1, employeeId);
                    es.setObject(2, session.companyId());
                    try (ResultSet er = es.executeQuery()) {
                        if (!er.next()) {
                            throw new IllegalArgumentException("Employee not found in company: " + employeeId);
                        }
                        empName = er.getString("full_name");
                        empContact = er.getString("contact_number");
                        isEmployed = "ACTIVE".equalsIgnoreCase(er.getString("employment_status"));
                    }
                }

                DriverProfileRecord record;
                try (PreparedStatement s = c.prepareStatement(insertSql)) {
                    s.setObject(1, session.companyId());
                    s.setObject(2, employeeId);
                    s.setString(3, cleanLicence);
                    s.setArray(4, c.createArrayOf("text", classes.toArray(new String[0])));
                    s.setDate(5, java.sql.Date.valueOf(expiry));
                    s.setInt(6, experienceYears);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new IllegalStateException("Failed to register driver");
                        UUID id = r.getObject(1, UUID.class);
                        record = new DriverProfileRecord(
                            id, session.companyId(), employeeId, empName, empContact,
                            cleanLicence, classes, expiry, experienceYears, "AVAILABLE", isEmployed
                        );
                    }
                }
                in.smartvrms.server.security.JdbcBusinessAudit.append(c, session, "DRIVER_REGISTERED", "DRIVER", record.id().toString(), in.smartvrms.server.security.JdbcBusinessAudit.detail("licence", cleanLicence));
                c.commit();
                return record;
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SecurityException denied) throw denied;
                if (e instanceof IllegalArgumentException invalid) throw invalid;
                throw new RuntimeException("Failed to register driver profile", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error registering driver", e);
        }
    }

    @Override
    public List<DriverProfileRecord> listDrivers(StaffSession session, String dutyStatus) {
        Objects.requireNonNull(session, "session");
        String sql = """
            SELECT d.id, d.company_id, d.employee_id, e.full_name, e.contact_number, d.licence_number,
                   d.licence_classes, d.licence_expiry, d.experience_years, d.duty_status,
                   (e.employment_status = 'ACTIVE') as is_employed
            FROM vrms_private.driver_profiles d
            JOIN vrms_private.employees e ON e.id = d.employee_id
            WHERE d.company_id = ? AND (? IS NULL OR d.duty_status = ?)
            ORDER BY e.full_name ASC
            """;

        List<DriverProfileRecord> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                in.smartvrms.server.security.JdbcStaffContext.require(c, session, in.smartvrms.server.security.Capability.VIEW_DRIVER_OPERATIONS);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setObject(1, session.companyId());
                    s.setString(2, dutyStatus);
                    s.setString(3, dutyStatus);
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) {
                            Array arr = r.getArray("licence_classes");
                            String[] classesArr = arr != null ? (String[]) arr.getArray() : new String[0];
                            list.add(new DriverProfileRecord(
                                r.getObject("id", UUID.class),
                                r.getObject("company_id", UUID.class),
                                r.getObject("employee_id", UUID.class),
                                r.getString("full_name"),
                                r.getString("contact_number"),
                                r.getString("licence_number"),
                                List.of(classesArr),
                                r.getDate("licence_expiry").toLocalDate(),
                                r.getInt("experience_years"),
                                r.getString("duty_status"),
                                r.getBoolean("is_employed")
                            ));
                        }
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SecurityException denied) throw denied;
                if (e instanceof IllegalArgumentException invalid) throw invalid;
                throw new RuntimeException("Failed to list drivers", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error listing drivers", e);
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public void updateDutyStatus(StaffSession session, UUID driverId, String dutyStatus) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(driverId, "driverId");
        Objects.requireNonNull(dutyStatus, "dutyStatus");
        if (!List.of("AVAILABLE", "ASSIGNED", "ON_LEAVE", "UNAVAILABLE").contains(dutyStatus)) {
            throw new IllegalArgumentException("Invalid duty status. Must be one of AVAILABLE, ASSIGNED, ON_LEAVE, UNAVAILABLE");
        }

        String sql = """
            UPDATE vrms_private.driver_profiles
            SET duty_status = ?, version = version + 1
            WHERE id = ? AND company_id = ?
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                in.smartvrms.server.security.JdbcStaffContext.require(c, session, in.smartvrms.server.security.Capability.MANAGE_DRIVER_MASTER);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setString(1, dutyStatus);
                    s.setObject(2, driverId);
                    s.setObject(3, session.companyId());
                    int updated = s.executeUpdate();
                    if (updated == 0) {
                        throw new IllegalArgumentException("Driver profile not found: " + driverId);
                    }
                }
                in.smartvrms.server.security.JdbcBusinessAudit.append(c, session, "DRIVER_STATUS_UPDATED", "DRIVER", driverId.toString(), in.smartvrms.server.security.JdbcBusinessAudit.detail("status", dutyStatus));
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SecurityException denied) throw denied;
                if (e instanceof IllegalArgumentException invalid) throw invalid;
                throw new RuntimeException("Failed to update driver duty status", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error updating driver duty status", e);
        }
    }

}
