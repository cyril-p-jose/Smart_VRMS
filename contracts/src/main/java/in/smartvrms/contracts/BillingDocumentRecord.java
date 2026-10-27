package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

public record BillingDocumentRecord(
    UUID id,
    UUID companyId,
    UUID bookingId,
    UUID customerId,
    String documentType,
    String documentNumber,
    int documentVersion,
    String status,
    long totalPaise,
    long advancePaidPaise,
    long balanceDuePaise,
    long depositHeldPaise,
    long taxPaise,
    boolean isBetaProforma,
    String snapshotJson,
    UUID fileMetadataId,
    UUID issuedBy,
    Instant issuedAt,
    Instant createdAt
) {}
