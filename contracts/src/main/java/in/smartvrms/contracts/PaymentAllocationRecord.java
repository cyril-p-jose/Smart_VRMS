package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

public record PaymentAllocationRecord(
    UUID id,
    UUID companyId,
    UUID paymentId,
    UUID bookingId,
    String allocationType,
    long allocatedPaise,
    Instant createdAt
) {}
