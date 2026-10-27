package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

/** Historical record of an approved extension (FR-EXT-001, FR-EXT-002, FR-DATA-001). */
public record ExtensionRecord(
        UUID id,
        UUID companyId,
        UUID bookingId,
        UUID assignmentId,
        Instant previousEndTime,
        Instant revisedEndTime,
        long incrementalChargePaise,
        String reason,
        UUID extendedBy,
        Instant createdAt
) {}
