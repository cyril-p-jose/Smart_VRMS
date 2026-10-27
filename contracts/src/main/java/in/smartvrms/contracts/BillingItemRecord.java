package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

public record BillingItemRecord(
    UUID id,
    UUID companyId,
    UUID billingDocumentId,
    UUID assignmentId,
    String itemType,
    String description,
    int quantity,
    long unitRatePaise,
    long totalPaise,
    Instant createdAt
) {}
