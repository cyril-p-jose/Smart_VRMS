package in.smartvrms.contracts;

import java.util.UUID;

public record FinancialCorrectionCommand(
    UUID bookingId,
    UUID paymentId,
    String correctionType,
    long amountPaise,
    String reason,
    String bankPayoutRef
) {}
