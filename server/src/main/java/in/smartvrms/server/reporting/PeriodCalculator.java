package in.smartvrms.server.reporting;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * Pure calculation utilities for reporting intervals, Indian Financial Years (1 Apr - 31 Mar),
 * and Monday-start calendar weeks in Asia/Kolkata (FR-FIN-003, FR-FIN-004).
 */
public final class PeriodCalculator {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZONE);

    private PeriodCalculator() { }

    /**
     * Resolves the Indian Financial Year label (e.g., "FY 2026-27" for dates between 1 Apr 2026 and 31 Mar 2027).
     */
    public static String getFinancialYearLabel(Instant time) {
        Objects.requireNonNull(time, "time");
        LocalDate date = time.atZone(ZONE).toLocalDate();
        int year = date.getYear();
        int startYear = (date.getMonthValue() >= 4) ? year : (year - 1);
        int endYear = startYear + 1;
        return "FY " + startYear + "-" + String.valueOf(endYear).substring(2);
    }

    /**
     * Resolves start boundary of the Indian Financial Year for the given instant.
     */
    public static Instant getFinancialYearStart(Instant time) {
        Objects.requireNonNull(time, "time");
        LocalDate date = time.atZone(ZONE).toLocalDate();
        int startYear = (date.getMonthValue() >= 4) ? date.getYear() : (date.getYear() - 1);
        return LocalDate.of(startYear, 4, 1).atStartOfDay(ZONE).toInstant();
    }

    /**
     * Resolves end boundary of the Indian Financial Year for the given instant (end of 31 March).
     */
    public static Instant getFinancialYearEnd(Instant time) {
        Objects.requireNonNull(time, "time");
        LocalDate date = time.atZone(ZONE).toLocalDate();
        int startYear = (date.getMonthValue() >= 4) ? date.getYear() : (date.getYear() - 1);
        return LocalDate.of(startYear + 1, 3, 31).atTime(23, 59, 59).atZone(ZONE).toInstant();
    }

    /**
     * Resolves the start of the Monday-based week in Asia/Kolkata.
     */
    public static Instant getMondayWeekStart(Instant time) {
        Objects.requireNonNull(time, "time");
        LocalDate date = time.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return date.atStartOfDay(ZONE).toInstant();
    }

    /**
     * Resolves the end of the Monday-based week (Sunday 23:59:59) in Asia/Kolkata.
     */
    public static Instant getMondayWeekEnd(Instant time) {
        Objects.requireNonNull(time, "time");
        LocalDate date = time.atZone(ZONE).toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        return date.atTime(23, 59, 59).atZone(ZONE).toInstant();
    }

    /**
     * Formats period date range for reports.
     */
    public static String formatPeriodRange(Instant from, Instant to) {
        return DATE_FMT.format(from) + " to " + DATE_FMT.format(to);
    }
}
