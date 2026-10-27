package in.smartvrms.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Command to extend rental duration for all or a subset of assignments (FR-EXT-001, FR-EXT-003). */
public record ExtensionCommand(
        UUID bookingId,
        List<UUID> assignmentIds,
        Instant revisedEndTime,
        String reason
) {}
