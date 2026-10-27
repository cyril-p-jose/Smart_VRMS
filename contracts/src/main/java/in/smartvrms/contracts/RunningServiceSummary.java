package in.smartvrms.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Aggregated operational summary for running rentals queue (FR-OPS-001, FR-CUST-007). */
public record RunningServiceSummary(
        UUID bookingId,
        String bookingNumber,
        UUID customerId,
        String customerName,
        String customerContact,
        UUID visitId,
        String bookingStatus,
        Instant startTime,
        Instant endTime,
        boolean isOverdue,
        int vehicleCount,
        List<String> vehiclePlates,
        List<String> driverNames,
        long totalPaise,
        long advanceReceivedPaise,
        long balancePaise,
        List<RunningAssignmentSummary> assignments
) {}
