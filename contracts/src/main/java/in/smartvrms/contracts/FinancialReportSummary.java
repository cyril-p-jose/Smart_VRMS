package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;

/**
 * Authoritative financial period report summary (FR-FIN-001, FR-FIN-003, FR-FIN-004).
 * Strictly restricted to Administrator accounts (FR-ACC-003, FR-FIN-005).
 */
public record FinancialReportSummary(
        Instant fromTime,
        Instant toTime,
        String periodLabel,
        long totalRevenueBilledPaise,
        long totalCashCollectedPaise,
        long outstandingReceivablesPaise,
        long refundableDepositLiabilityPaise,
        long maintenanceExpensesPaise,
        long compensationExpensesPaise,
        long totalOperatingExpensesPaise,
        long netOperatingAccrualMarginPaise
) {
    public FinancialReportSummary {
        Objects.requireNonNull(fromTime, "fromTime");
        Objects.requireNonNull(toTime, "toTime");
        Objects.requireNonNull(periodLabel, "periodLabel");
    }
}
