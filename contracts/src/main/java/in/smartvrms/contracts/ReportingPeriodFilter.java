package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Filter criteria for operational and financial period reporting (FR-FIN-003, FR-FIN-004).
 */
public record ReportingPeriodFilter(
        Instant fromTime,
        Instant toTime,
        String granularity, // "DAY", "MONDAY_WEEK", "MONTH", "FINANCIAL_YEAR"
        UUID vehicleId,
        UUID customerId
) {
    public ReportingPeriodFilter {
        Objects.requireNonNull(fromTime, "fromTime");
        Objects.requireNonNull(toTime, "toTime");
        if (toTime.isBefore(fromTime)) {
            throw new IllegalArgumentException("toTime cannot be before fromTime");
        }
        if (granularity == null || granularity.trim().isEmpty()) {
            granularity = "MONTH";
        }
    }

    public static ReportingPeriodFilter of(Instant from, Instant to) {
        return new ReportingPeriodFilter(from, to, "MONTH", null, null);
    }
}
