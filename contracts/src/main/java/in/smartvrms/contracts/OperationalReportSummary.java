package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;

/**
 * Operational performance summary (FR-OPS-001, FR-REV-001).
 * Strictly contains zero payroll, driver compensation, or company margin fields (FR-ACC-003, FR-FIN-005).
 */
public record OperationalReportSummary(
        Instant fromTime,
        Instant toTime,
        int totalBookingsCount,
        int totalAssignmentsCount,
        int activeRentalsCount,
        int completedRentalsCount,
        int overdueRentalsCount,
        int maintenanceCasesCount,
        int reviewsCount,
        double averageReviewRating
) {
    public OperationalReportSummary {
        Objects.requireNonNull(fromTime, "fromTime");
        Objects.requireNonNull(toTime, "toTime");
    }
}
