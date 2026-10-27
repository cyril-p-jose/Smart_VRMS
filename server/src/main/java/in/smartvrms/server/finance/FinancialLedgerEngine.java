package in.smartvrms.server.finance;

import in.smartvrms.contracts.LedgerSummary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public final class FinancialLedgerEngine {

    private FinancialLedgerEngine() {}

    public static BigDecimal paiseToRupees(long paise) {
        return BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public static long rupeesToPaise(BigDecimal rupees) {
        return rupees.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static LedgerSummary calculateSummary(
        Instant from,
        Instant to,
        long totalRevenueEarnedPaise,
        long totalCashCollectedPaise,
        long totalReceivablesPaise,
        long totalDepositLiabilityPaise,
        long totalExpensesPaise
    ) {
        BigDecimal rev = paiseToRupees(totalRevenueEarnedPaise);
        BigDecimal exp = paiseToRupees(totalExpensesPaise);
        BigDecimal marginRupees = rev.subtract(exp);
        long marginPaise = rupeesToPaise(marginRupees);

        return new LedgerSummary(
            from,
            to,
            totalRevenueEarnedPaise,
            totalCashCollectedPaise,
            totalReceivablesPaise,
            totalDepositLiabilityPaise,
            totalExpensesPaise,
            marginPaise
        );
    }
}
