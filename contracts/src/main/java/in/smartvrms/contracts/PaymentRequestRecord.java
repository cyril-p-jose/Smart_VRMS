package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

public record PaymentRequestRecord(
    UUID id,
    UUID companyId,
    UUID bookingId,
    UUID customerId,
    long amountPaise,
    String currency,
    String paymentType,
    Instant dueAt,
    String status,
    String merchantVpa,
    String merchantName,
    String qrPayload,
    String notes,
    UUID createdBy,
    Instant createdAt
) {}
