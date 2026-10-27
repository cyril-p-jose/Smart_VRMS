package in.smartvrms.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Itemized calculation of incremental charges for an extension (FR-EXT-002, FR-EXT-003). */
public record ExtensionQuote(
        UUID bookingId,
        List<AssignmentDelta> assignmentDeltas,
        long totalDeltaPaise,
        Instant revisedEndTime
) {
    public record AssignmentDelta(
            UUID assignmentId,
            UUID vehicleId,
            Instant previousEndTime,
            Instant revisedEndTime,
            long incrementalVehiclePaise,
            long incrementalDriverPaise,
            long incrementalTotalPaise
    ) {}
}
