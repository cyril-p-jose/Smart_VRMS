package in.smartvrms.contracts;

import java.util.List;
import java.util.UUID;

public record CustomerStatement(
    UUID customerId,
    String customerName,
    long totalBilledPaise,
    long totalPaidPaise,
    long balanceDuePaise,
    long depositHeldPaise,
    List<PaymentRecord> payments,
    List<BillingDocumentRecord> documents
) {}
