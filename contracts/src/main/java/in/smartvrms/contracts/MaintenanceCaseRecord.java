package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

/** Maintenance, damage, or wear case record with distinct estimate, actual, and recovery (FR-DMG-001, FR-DMG-002, FR-DMG-003). */
public record MaintenanceCaseRecord(
        UUID id,
        UUID companyId,
        UUID vehicleId,
        UUID assignmentId,
        UUID custodyId,
        UUID restrictionId,
        String issueType,
        String status,
        long estimatedCostPaise,
        long allegedCustomerRecoveryPaise,
        long actualCostPaise,
        long approvedCustomerRecoveryPaise,
        String notes,
        UUID damagePhotoFileId,
        String resolutionNotes,
        UUID reportedBy,
        UUID resolvedBy,
        Instant createdAt,
        Instant resolvedAt
) {}
