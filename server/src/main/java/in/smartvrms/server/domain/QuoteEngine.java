package in.smartvrms.server.domain;

import in.smartvrms.contracts.AssignmentQuote;
import in.smartvrms.contracts.BookingQuote;
import in.smartvrms.contracts.RatePlanRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative pricing calculator implementing the approved commercial rules:
 * 1. First 24-hour minimum (<= 24h = 1 full day).
 * 2. Subsequent days plus hourly remainder (daily_rate / 24) capped at 1 day.
 * 3. Decimal paise rounding HALF_UP.
 * 4. Distinct per-assignment driver customer charges.
 * 5. Symbolic unverified tax (0 paise until verified registration/GST classification).
 * Reference: planning/Smart-VRMS-Planning-Baseline.md Section 8 and FR-BOOK-007.
 */
public final class QuoteEngine {

    private QuoteEngine() {}
    private static long billableHours(Duration duration) {
        long seconds = duration.getSeconds();
        return Math.addExact(seconds / 3600, seconds % 3600 != 0 || duration.getNano() != 0 ? 1 : 0);
    }

    public record AssignmentInput(
            UUID vehicleId,
            String vehicleName,
            String category,
            String registrationPlate,
            UUID driverId,
            String driverName,
            boolean driverRequired,
            RatePlanRecord ratePlan
    ) {}

    public static BookingQuote calculate(Instant startTime, Instant endTime, List<AssignmentInput> items) {
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("Start time and end time are required.");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("End time must be strictly after start time.");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Booking must contain at least one vehicle assignment.");
        }

        Duration duration = Duration.between(startTime, endTime);
        long totalHours = billableHours(duration);

        long billableDays;
        long billableRemainderHours;

        if (totalHours <= 24) {
            billableDays = 1;
            billableRemainderHours = 0;
        } else {
            billableDays = totalHours / 24;
            billableRemainderHours = totalHours % 24;
        }

        List<AssignmentQuote> assignmentQuotes = new ArrayList<>();
        long baseTotalPaise = 0;
        long driverTotalPaise = 0;

        for (AssignmentInput item : items) {
            RatePlanRecord plan = item.ratePlan();
            if (plan == null) throw new IllegalArgumentException("A configured rate plan is required.");
            long dailyRate = plan.dailyRatePaise();
            long hourlyRate = plan != null ? plan.hourlyRatePaise() : dailyRate / 24;
            long driverDailyFee = (plan != null && item.driverRequired()) ? plan.driverDailyFeePaise() : (item.driverRequired() ? 80000L : 0L);
            long driverHourlyFee = (plan != null && item.driverRequired()) ? plan.driverHourlyFeePaise() : driverDailyFee / 24;

            // Vehicle charge: full days + remainder
            long vehicleDayTotal = Math.multiplyExact(billableDays, dailyRate);
            long vehicleRemainderTotal = 0;
            if (billableRemainderHours > 0) {
                BigDecimal remainder = BigDecimal.valueOf(dailyRate)
                        .multiply(BigDecimal.valueOf(billableRemainderHours))
                        .divide(BigDecimal.valueOf(24), 0, RoundingMode.HALF_UP);
                vehicleRemainderTotal = Math.min(dailyRate, remainder.longValue());
            }
            long vehicleTotal = Math.addExact(vehicleDayTotal, vehicleRemainderTotal);

            // Driver charge: full days + remainder if driver required
            long driverDayTotal = 0;
            long driverRemainderTotal = 0;
            if (item.driverRequired()) {
                driverDayTotal = Math.multiplyExact(billableDays, driverDailyFee);
                if (billableRemainderHours > 0) {
                    BigDecimal driverRem = BigDecimal.valueOf(driverDailyFee)
                            .multiply(BigDecimal.valueOf(billableRemainderHours))
                            .divide(BigDecimal.valueOf(24), 0, RoundingMode.HALF_UP);
                    driverRemainderTotal = Math.min(driverDailyFee, driverRem.longValue());
                }
            }
            long driverTotal = Math.addExact(driverDayTotal, driverRemainderTotal);
            long lineTotal = Math.addExact(vehicleTotal, driverTotal);

            assignmentQuotes.add(new AssignmentQuote(
                    item.vehicleId(),
                    item.vehicleName(),
                    item.category(),
                    item.registrationPlate(),
                    item.driverId(),
                    item.driverName(),
                    item.driverRequired(),
                    dailyRate,
                    hourlyRate,
                    vehicleTotal,
                    driverTotal,
                    lineTotal
            ));

            baseTotalPaise = Math.addExact(baseTotalPaise, vehicleTotal);
            driverTotalPaise = Math.addExact(driverTotalPaise, driverTotal);
        }

        long taxPaise = 0L; // Unverified tax is symbolic 0
        long grandTotalPaise = Math.addExact(Math.addExact(baseTotalPaise, driverTotalPaise), taxPaise);

        return new BookingQuote(
                startTime,
                endTime,
                totalHours,
                billableDays,
                billableRemainderHours,
                List.copyOf(assignmentQuotes),
                baseTotalPaise,
                driverTotalPaise,
                taxPaise,
                grandTotalPaise,
                0L, // Suggested advance
                0L  // Deposit
        );
    }

    public static AssignmentQuote calculateAssignmentQuote(
            UUID vehicleId,
            String vehicleName,
            long dailyRate,
            long hourlyRate,
            boolean driverRequired,
            long driverDailyFee,
            long driverHourlyFee,
            Instant startTime,
            Instant endTime) {
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("Start time and end time are required.");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("End time must be strictly after start time.");
        }

        Duration duration = Duration.between(startTime, endTime);
        long totalHours = billableHours(duration);

        long billableDays;
        long billableRemainderHours;

        if (totalHours <= 24) {
            billableDays = 1;
            billableRemainderHours = 0;
        } else {
            billableDays = totalHours / 24;
            billableRemainderHours = totalHours % 24;
        }

        long vehicleDayTotal = Math.multiplyExact(billableDays, dailyRate);
        long vehicleRemainderTotal = 0;
        if (billableRemainderHours > 0) {
            BigDecimal remainder = BigDecimal.valueOf(dailyRate)
                    .multiply(BigDecimal.valueOf(billableRemainderHours))
                    .divide(BigDecimal.valueOf(24), 0, RoundingMode.HALF_UP);
            vehicleRemainderTotal = Math.min(dailyRate, remainder.longValue());
        }
        long vehicleTotal = Math.addExact(vehicleDayTotal, vehicleRemainderTotal);

        long driverDayTotal = 0;
        long driverRemainderTotal = 0;
        if (driverRequired) {
            driverDayTotal = Math.multiplyExact(billableDays, driverDailyFee);
            if (billableRemainderHours > 0) {
                BigDecimal driverRem = BigDecimal.valueOf(driverDailyFee)
                        .multiply(BigDecimal.valueOf(billableRemainderHours))
                        .divide(BigDecimal.valueOf(24), 0, RoundingMode.HALF_UP);
                driverRemainderTotal = Math.min(driverDailyFee, driverRem.longValue());
            }
        }
        long driverTotal = Math.addExact(driverDayTotal, driverRemainderTotal);
        long lineTotal = Math.addExact(vehicleTotal, driverTotal);

        return new AssignmentQuote(
                vehicleId,
                vehicleName != null ? vehicleName : "Vehicle",
                "STANDARD",
                "",
                null,
                null,
                driverRequired,
                dailyRate,
                hourlyRate,
                vehicleTotal,
                driverTotal,
                lineTotal
        );
    }

    /**
     * BR-PRICE-003: 30-minute return grace. Late fee charged beyond grace rounded up to whole hours at dailyRate / 24.
     */
    public static long calculateLateFee(long dailyRatePaise, Instant agreedEnd, Instant actualReturn) {
        if (agreedEnd == null || actualReturn == null || !actualReturn.isAfter(agreedEnd)) {
            return 0L;
        }
        Duration delay = Duration.between(agreedEnd, actualReturn);
        long delayMinutes = delay.toMinutes();
        if (delayMinutes <= 30) {
            return 0L; // Within 30-minute grace period
        }
        long chargeableMinutes = delayMinutes - 30;
        long chargeableHours = (chargeableMinutes + 59) / 60; // round up to whole hours
        long days = chargeableHours / 24;
        long remHours = chargeableHours % 24;

        long dayTotal = Math.multiplyExact(days, dailyRatePaise);
        BigDecimal hourlyCharge = BigDecimal.valueOf(dailyRatePaise)
                .multiply(BigDecimal.valueOf(remHours))
                .divide(BigDecimal.valueOf(24), 0, RoundingMode.HALF_UP);
        long remTotal = Math.min(dailyRatePaise, hourlyCharge.longValue());
        return Math.addExact(dayTotal, remTotal);
    }

    /**
     * BR-PRICE-002: Extension reprices the entire planned duration and charges only the delta.
     * No second first-day minimum is applied.
     */
    public static long calculateExtensionDelta(long dailyRatePaise, Instant originalStart, Instant originalEnd, Instant newEnd) {
        if (originalStart == null || originalEnd == null || newEnd == null) {
            throw new IllegalArgumentException("Original and new interval timestamps required.");
        }
        if (!newEnd.isAfter(originalEnd)) {
            throw new IllegalArgumentException("New end time must be after original end time.");
        }
        AssignmentQuote originalQuote = calculateAssignmentQuote(
                UUID.randomUUID(), "Vehicle", dailyRatePaise, dailyRatePaise / 24, false, 0, 0, originalStart, originalEnd
        );
        AssignmentQuote extendedQuote = calculateAssignmentQuote(
                UUID.randomUUID(), "Vehicle", dailyRatePaise, dailyRatePaise / 24, false, 0, 0, originalStart, newEnd
        );
        return Math.max(0L, extendedQuote.vehicleTotalPaise() - originalQuote.vehicleTotalPaise());
    }
}
