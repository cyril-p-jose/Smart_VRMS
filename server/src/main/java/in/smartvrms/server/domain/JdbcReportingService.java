package in.smartvrms.server.domain;

import in.smartvrms.application.port.AuditPort;
import in.smartvrms.application.port.ReportingPort;
import in.smartvrms.contracts.FinancialReportSummary;
import in.smartvrms.contracts.OperationalReportSummary;
import in.smartvrms.contracts.ReportingPeriodFilter;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.server.reporting.PeriodCalculator;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.JdbcStaffContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public class JdbcReportingService implements ReportingPort {

    private final DataSource dataSource;
    private final AuditPort auditPort;

    public JdbcReportingService(DataSource dataSource, AuditPort auditPort) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.auditPort = auditPort;
    }

    private void checkStaff(StaffSession session) {
        Objects.requireNonNull(session, "session");
        if (!"ADMINISTRATOR".equals(session.role()) && !"EMPLOYEE".equals(session.role())) {
            throw new SecurityException("Forbidden: Staff session required");
        }
    }

    private void checkAdminOnly(StaffSession session, String actionDesc) {
        checkStaff(session);
        if (!"ADMINISTRATOR".equals(session.role())) {
            throw new SecurityException("Forbidden: Only administrators can " + actionDesc + " (FR-ACC-003, FR-FIN-005)");
        }
    }

    @Override
    public OperationalReportSummary getOperationalReport(StaffSession session, ReportingPeriodFilter filter) {
        checkStaff(session);
        Objects.requireNonNull(filter, "filter");

        try (Connection c = dataSource.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            JdbcStaffContext.require(c, session, Capability.VIEW_OPERATIONAL_HISTORY);

            int totalBookings = 0;
            int totalAssignments = 0;
            int activeRentals = 0;
            int completedRentals = 0;
            int overdueRentals = 0;
            int maintenanceCases = 0;
            int reviewsCount = 0;
            double avgRating = 0.0;

            // 1. Total bookings & assignments
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(DISTINCT b.id) as bookings, count(a.id) as assignments " +
                    "FROM vrms_private.bookings b " +
                    "LEFT JOIN vrms_private.booking_assignments a ON b.id = a.booking_id " +
                    "WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at <= ?"
            )) {
                ps.setObject(1, session.companyId());
                ps.setTimestamp(2, Timestamp.from(filter.fromTime()));
                ps.setTimestamp(3, Timestamp.from(filter.toTime()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalBookings = rs.getInt("bookings");
                        totalAssignments = rs.getInt("assignments");
                    }
                }
            }

            // 2. Custody stats
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " +
                    "   count(CASE WHEN actual_return_time IS NULL THEN 1 END) as active, " +
                    "   count(CASE WHEN actual_return_time IS NOT NULL THEN 1 END) as completed " +
                    "FROM vrms_private.custody_records " +
                    "WHERE company_id = ? AND actual_checkout_time >= ? AND actual_checkout_time <= ?"
            )) {
                ps.setObject(1, session.companyId());
                ps.setTimestamp(2, Timestamp.from(filter.fromTime()));
                ps.setTimestamp(3, Timestamp.from(filter.toTime()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        activeRentals = rs.getInt("active");
                        completedRentals = rs.getInt("completed");
                    }
                }
            }

            // 3. Overdue rentals
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM vrms_private.booking_assignments a " +
                    "JOIN vrms_private.custody_records c ON a.id = c.assignment_id " +
                    "WHERE a.company_id = ? AND c.actual_return_time IS NULL AND a.expected_return_time < now()"
            )) {
                ps.setObject(1, session.companyId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        overdueRentals = rs.getInt(1);
                    }
                }
            }

            // 4. Maintenance cases
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM vrms_private.maintenance_cases " +
                    "WHERE company_id = ? AND created_at >= ? AND created_at <= ?"
            )) {
                ps.setObject(1, session.companyId());
                ps.setTimestamp(2, Timestamp.from(filter.fromTime()));
                ps.setTimestamp(3, Timestamp.from(filter.toTime()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        maintenanceCases = rs.getInt(1);
                    }
                }
            }

            // 5. Reviews
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*), coalesce(avg(rating), 0.0) FROM vrms_private.service_reviews " +
                    "WHERE company_id = ? AND recorded_at >= ? AND recorded_at <= ?"
            )) {
                ps.setObject(1, session.companyId());
                ps.setTimestamp(2, Timestamp.from(filter.fromTime()));
                ps.setTimestamp(3, Timestamp.from(filter.toTime()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        reviewsCount = rs.getInt(1);
                        avgRating = rs.getDouble(2);
                    }
                }
            }

            OperationalReportSummary report = new OperationalReportSummary(
                    filter.fromTime(), filter.toTime(), totalBookings, totalAssignments,
                    activeRentals, completedRentals, overdueRentals, maintenanceCases,
                    reviewsCount, avgRating
            );
            c.commit();
            return report;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to generate operational report: " + e.getMessage(), e);
        }
    }

    @Override
    public FinancialReportSummary getFinancialReport(StaffSession session, ReportingPeriodFilter filter) {
        checkAdminOnly(session, "access financial report");
        Objects.requireNonNull(filter, "filter");

        try (Connection c = dataSource.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            JdbcStaffContext.require(c, session, Capability.VIEW_COMPANY_MARGIN);

            long revenuePaise = 0L;
            long cashPaise = 0L;
            long receivablesPaise = 0L;
            long depositLiabilityPaise = 0L;
            long maintenancePaise = 0L;
            long compensationPaise = 0L;

            // 1. Revenue & Cash from Ledger entries
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT entry_type, sum(amount_paise) as total FROM vrms_private.ledger_entries " +
                    "WHERE company_id = ? AND posted_at >= ? AND posted_at <= ? GROUP BY entry_type"
            )) {
                ps.setObject(1, session.companyId());
                ps.setTimestamp(2, Timestamp.from(filter.fromTime()));
                ps.setTimestamp(3, Timestamp.from(filter.toTime()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String type = rs.getString("entry_type");
                        long total = rs.getLong("total");
                        if ("REVENUE_EARNED".equals(type)) {
                            revenuePaise = total;
                        } else if ("CASH_RECEIVED".equals(type)) {
                            cashPaise = total;
                        } else if ("RECEIVABLE_DEBT".equals(type)) {
                            receivablesPaise = total;
                        }
                    }
                }
            }

            // 2. Deposit liability
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(deposit_held_paise - deposit_refunded_paise - deposit_applied_paise), 0) " +
                    "FROM vrms_private.refundable_deposits " +
                    "WHERE company_id = ? AND status IN ('HELD', 'PARTIALLY_REFUNDED')"
            )) {
                ps.setObject(1, session.companyId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        depositLiabilityPaise = rs.getLong(1);
                    }
                }
            }

            // 3. Maintenance expenses
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(actual_cost_paise), 0) FROM vrms_private.maintenance_cases " +
                    "WHERE company_id = ? AND created_at >= ? AND created_at <= ?"
            )) {
                ps.setObject(1, session.companyId());
                ps.setTimestamp(2, Timestamp.from(filter.fromTime()));
                ps.setTimestamp(3, Timestamp.from(filter.toTime()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        maintenancePaise = rs.getLong(1);
                    }
                }
            }

            // 4. Compensation expenses
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(fixed_amount_paise), 0) FROM vrms_private.compensation_records " +
                    "WHERE company_id = ? AND effective_from >= ? AND effective_from <= ?"
            )) {
                ps.setObject(1, session.companyId());
                ps.setTimestamp(2, Timestamp.from(filter.fromTime()));
                ps.setTimestamp(3, Timestamp.from(filter.toTime()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        compensationPaise = rs.getLong(1);
                    }
                }
            }

            long totalExpenses = maintenancePaise + compensationPaise;
            long netMargin = revenuePaise - totalExpenses;
            String periodLabel = PeriodCalculator.getFinancialYearLabel(filter.fromTime());

            FinancialReportSummary report = new FinancialReportSummary(
                    filter.fromTime(), filter.toTime(), periodLabel,
                    revenuePaise, cashPaise, receivablesPaise, depositLiabilityPaise,
                    maintenancePaise, compensationPaise, totalExpenses, netMargin
            );
            c.commit();
            return report;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to generate financial report: " + e.getMessage(), e);
        }
    }

    /**
     * Sanitizes CSV cell values to prevent spreadsheet formula injection attacks (CSV Injection)
     * and handles proper RFC 4180 quoting and escaping.
     */
    public static String escapeCsvCell(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.trim();
        // Prevent formula execution in spreadsheet software (=, +, -, @, tab, CR)
        if (value.startsWith("\t") || value.startsWith("\r")) {
            clean = "'" + value;
        } else if (!clean.isEmpty()) {
            char first = clean.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                clean = "'" + clean;
            }
        }
        if (clean.contains(",") || clean.contains("\"") || clean.contains("\n") || clean.contains("\r")) {
            clean = "\"" + clean.replace("\"", "\"\"") + "\"";
        }
        return clean;
    }

    @Override
    public String exportReportCsv(StaffSession session, ReportingPeriodFilter filter) {
        checkStaff(session);
        boolean isAdmin = "ADMINISTRATOR".equals(session.role());
        OperationalReportSummary ops = getOperationalReport(session, filter);

        StringBuilder sb = new StringBuilder();
        // Prepend UTF-8 Byte Order Mark (BOM) for correct UTF-8 and currency character rendering in Excel
        sb.append("\uFEFF");
        sb.append(escapeCsvCell("Smart VRMS Period Report")).append("\n");
        sb.append(escapeCsvCell("Period:")).append(",").append(escapeCsvCell(PeriodCalculator.formatPeriodRange(filter.fromTime(), filter.toTime()))).append("\n");
        sb.append(escapeCsvCell("Generated By:")).append(",").append(escapeCsvCell(session.role())).append("\n\n");

        sb.append("OPERATIONAL PERFORMANCE\n");
        sb.append("Metric,Value\n");
        sb.append(escapeCsvCell("Total Bookings")).append(",").append(ops.totalBookingsCount()).append("\n");
        sb.append(escapeCsvCell("Total Vehicle Assignments")).append(",").append(ops.totalAssignmentsCount()).append("\n");
        sb.append(escapeCsvCell("Active Rentals")).append(",").append(ops.activeRentalsCount()).append("\n");
        sb.append(escapeCsvCell("Completed Rentals")).append(",").append(ops.completedRentalsCount()).append("\n");
        sb.append(escapeCsvCell("Overdue Rentals")).append(",").append(ops.overdueRentalsCount()).append("\n");
        sb.append(escapeCsvCell("Maintenance Incidents")).append(",").append(ops.maintenanceCasesCount()).append("\n");
        sb.append(escapeCsvCell("Customer Reviews Count")).append(",").append(ops.reviewsCount()).append("\n");
        sb.append(escapeCsvCell("Average Review Rating")).append(",").append(String.format(Locale.US, "%.2f", ops.averageReviewRating())).append("\n\n");

        if (isAdmin) {
            FinancialReportSummary fin = getFinancialReport(session, filter);
            sb.append("FINANCIAL PERFORMANCE (ADMINISTRATOR CONFIDENTIAL)\n");
            sb.append("Metric,Amount (INR)\n");
            sb.append(escapeCsvCell("Total Revenue Billed")).append(",").append(String.format(Locale.US, "%.2f", fin.totalRevenueBilledPaise() / 100.0)).append("\n");
            sb.append(escapeCsvCell("Actual Cash Collected")).append(",").append(String.format(Locale.US, "%.2f", fin.totalCashCollectedPaise() / 100.0)).append("\n");
            sb.append(escapeCsvCell("Outstanding Receivables")).append(",").append(String.format(Locale.US, "%.2f", fin.outstandingReceivablesPaise() / 100.0)).append("\n");
            sb.append(escapeCsvCell("Refundable Deposit Liability")).append(",").append(String.format(Locale.US, "%.2f", fin.refundableDepositLiabilityPaise() / 100.0)).append("\n");
            sb.append(escapeCsvCell("Maintenance Expenses")).append(",").append(String.format(Locale.US, "%.2f", fin.maintenanceExpensesPaise() / 100.0)).append("\n");
            sb.append(escapeCsvCell("Staff Compensation Expenses")).append(",").append(String.format(Locale.US, "%.2f", fin.compensationExpensesPaise() / 100.0)).append("\n");
            sb.append(escapeCsvCell("Total Operating Expenses")).append(",").append(String.format(Locale.US, "%.2f", fin.totalOperatingExpensesPaise() / 100.0)).append("\n");
            sb.append(escapeCsvCell("Net Operating Accrual Margin")).append(",").append(String.format(Locale.US, "%.2f", fin.netOperatingAccrualMarginPaise() / 100.0)).append("\n");
        } else {
            sb.append("FINANCIAL PERFORMANCE\n");
            sb.append(escapeCsvCell("Notice: Financial margin and expense details restricted to Administrator accounts (FR-ACC-003, FR-FIN-005)")).append("\n");
        }

        return sb.toString();
    }
}
