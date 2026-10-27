package in.smartvrms.contracts;

import java.time.Instant;

public record LedgerSummary(
    Instant fromTime,
    Instant toTime,
    long totalRevenueEarnedPaise,
    long totalCashCollectedPaise,
    long totalReceivablesPaise,
    long totalDepositLiabilityPaise,
    long totalExpensesPaise,
    long operatingMarginPaise
) {}
