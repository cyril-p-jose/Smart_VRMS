package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

public record PaymentRecord(
    UUID id,
    UUID companyId,
    UUID bookingId,
    UUID customerId,
    UUID paymentRequestId,
    long amountPaise,
    String currency,
    String paymentMethod,
    String externalReference,
    String status,
    String verificationEvidence,
    UUID verifiedBy,
    Instant verifiedAt,
    String notes,
    Instant createdAt
) {}
