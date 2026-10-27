package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

/** Custody and physical inspection record for a vehicle assignment (FR-OPS-001, FR-OPS-002, FR-OPS-003). */
public record CustodyRecord(
        UUID id,
        UUID companyId,
        UUID assignmentId,
        UUID vehicleId,
        Instant checkoutTime,
        int checkoutOdometer,
        int checkoutFuelPercent,
        String checkoutNotes,
        Instant returnTime,
        Integer returnOdometer,
        Integer returnFuelPercent,
        String inspectionStatus,
        String damageNotes,
        UUID damagePhotoFileId,
        UUID checkoutBy,
        UUID returnedBy,
        Instant createdAt
) {}
