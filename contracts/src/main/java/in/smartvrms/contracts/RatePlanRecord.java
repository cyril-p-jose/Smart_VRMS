package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

/** Master rate plan defining vehicle daily/hourly rates and driver customer charges (FR-BOOK-007). */
public record RatePlanRecord(
        UUID id,
        UUID companyId,
        UUID vehicleId,
        UUID categoryId,
        long dailyRatePaise,
        long hourlyRatePaise,
        long driverDailyFeePaise,
        long driverHourlyFeePaise,
        Instant effectiveFrom,
        Instant effectiveTo,
        long version
) {}
