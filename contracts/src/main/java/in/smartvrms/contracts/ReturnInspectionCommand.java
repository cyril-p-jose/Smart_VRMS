package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

/** Command to record physical return and inspection condition (FR-OPS-002, FR-OPS-003, FR-DMG-001, FR-DMG-002). */
public record ReturnInspectionCommand(
        UUID assignmentId,
        Instant actualTime,
        int odometer,
        int fuelPercent,
        String condition, // ACCEPTABLE or DAMAGED
        String damageNotes,
        UUID damagePhotoFileId,
        String issueType, // CUSTOMER_DAMAGE, ORDINARY_WEAR, ROUTINE_SERVICE, BREAKDOWN
        long estimatedRepairCostPaise,
        long allegedCustomerRecoveryPaise
) {}
