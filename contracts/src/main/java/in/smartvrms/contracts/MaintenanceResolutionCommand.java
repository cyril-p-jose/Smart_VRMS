package in.smartvrms.contracts;

import java.util.UUID;

/** Administrator command to resolve a maintenance or damage case (FR-DMG-002, FR-DMG-003). */
public record MaintenanceResolutionCommand(
        UUID caseId,
        long actualCostPaise,
        long approvedCustomerRecoveryPaise,
        String resolutionNotes
) {}
