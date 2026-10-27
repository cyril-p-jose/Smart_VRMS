package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

public record RefundableDepositRecord(
    UUID id,
    UUID companyId,
    UUID bookingId,
    UUID customerId,
    long depositReceivedPaise,
    long depositRefundedPaise,
    long depositAppliedPaise,
    String status,
    Instant heldAt,
    Instant refundedAt,
    String refundRef,
    UUID refundApprovedBy,
    String notes
) {
    public long remainingHeldPaise() {
        return Math.max(0, depositReceivedPaise - (depositRefundedPaise + depositAppliedPaise));
    }
}
