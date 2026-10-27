package in.smartvrms.contracts;

import java.time.Instant;
import java.util.List;

/** Authoritative immutable pricing quote snapshot (FR-BOOK-007, FR-BOOK-008). */
public record BookingQuote(
        Instant startTime,
        Instant endTime,
        long durationHours,
        long billableDays,
        long billableRemainderHours,
        List<AssignmentQuote> assignments,
        long baseTotalPaise,
        long driverTotalPaise,
        long taxPaise,
        long grandTotalPaise,
        long suggestedAdvancePaise,
        long depositPaise
) {}
