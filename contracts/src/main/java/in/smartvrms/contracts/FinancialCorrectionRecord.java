package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

public record FinancialCorrectionRecord(
    UUID id,
    UUID companyId,
    UUID bookingId,
    UUID paymentId,
    String correctionType,
    long amountPaise,
    String reason,
    UUID approvedBy,
    String bankPayoutRef,
    String status,
    Instant createdAt
) {}
