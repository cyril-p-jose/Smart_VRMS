package in.smartvrms.application.port;

import in.smartvrms.contracts.FinancialReportSummary;
import in.smartvrms.contracts.OperationalReportSummary;
import in.smartvrms.contracts.ReportingPeriodFilter;
import in.smartvrms.contracts.StaffSession;

/**
 * Port for operational and financial period reporting (FR-FIN-003, FR-FIN-004, FR-ACC-003, FR-FIN-005).
 */
public interface ReportingPort {
    /**
     * Generates operational performance metrics (trips, custody, incidents, ratings).
     * Accessible by Employee and Administrator roles. Contains zero salary or margin fields.
     */
    OperationalReportSummary getOperationalReport(StaffSession session, ReportingPeriodFilter filter);

    /**
     * Generates authoritative financial summary (revenue, cash, receivables, deposits, expenses, margin).
     * Strictly restricted to Administrator role. Throws SecurityException for Employee.
     */
    FinancialReportSummary getFinancialReport(StaffSession session, ReportingPeriodFilter filter);

    /**
     * Exports a reconciled period report in CSV format.
     */
    String exportReportCsv(StaffSession session, ReportingPeriodFilter filter);
}
