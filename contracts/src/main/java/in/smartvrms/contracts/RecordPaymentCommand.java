package in.smartvrms.contracts;

import java.util.UUID;

public record RecordPaymentCommand(
    UUID bookingId,
    UUID paymentRequestId,
    long amountPaise,
    String paymentMethod,
    String externalReference,
    String verificationEvidence,
    String notes,
    long allocateToAdvancePaise,
    long allocateToDepositPaise
) {}
