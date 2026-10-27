package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

/** Operational state for an individual vehicle assignment in running services (FR-OPS-001, FR-OPS-002). */
public record RunningAssignmentSummary(
        UUID assignmentId,
        UUID vehicleId,
        String registrationPlate,
        String categoryName,
        UUID driverId,
        String driverName,
        Instant startTime,
        Instant endTime,
        String assignmentStatus,
        String custodyStatus,
        String inspectionStatus
) {}
