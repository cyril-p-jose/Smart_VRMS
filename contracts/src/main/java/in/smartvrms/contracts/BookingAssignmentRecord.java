package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

/** Confirmed vehicle assignment within a single or multi-vehicle booking (FR-BOOK-002, FR-BOOK-003). */
public record BookingAssignmentRecord(
        UUID id,
        UUID companyId,
        UUID bookingId,
        UUID vehicleId,
        UUID driverId,
        boolean driverRequired,
        Instant startTime,
        Instant endTime,
        long dailyRatePaise,
        long hourlyRatePaise,
        long vehicleTotalPaise,
        long driverTotalPaise,
        long assignmentTotalPaise,
        String status
) {}
