package in.smartvrms.contracts;

import java.util.UUID;

/** Itemized assignment quote line explaining vehicle and driver calculations (FR-BOOK-003, FR-BOOK-007). */
public record AssignmentQuote(
        UUID vehicleId,
        String vehicleName,
        String category,
        String registrationPlate,
        UUID driverId,
        String driverName,
        boolean driverRequired,
        long dailyRatePaise,
        long hourlyRatePaise,
        long vehicleTotalPaise,
        long driverTotalPaise,
        long lineTotalPaise
) {}
