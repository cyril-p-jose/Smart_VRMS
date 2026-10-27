package in.smartvrms.application.port;

import in.smartvrms.contracts.BillingDocumentRecord;
import in.smartvrms.contracts.CustomerStatement;
import in.smartvrms.contracts.FinancialCorrectionCommand;
import in.smartvrms.contracts.FinancialCorrectionRecord;
import in.smartvrms.contracts.LedgerSummary;
import in.smartvrms.contracts.PaymentRecord;
import in.smartvrms.contracts.PaymentRequestRecord;
import in.smartvrms.contracts.QrIntentRecord;
import in.smartvrms.contracts.RecordPaymentCommand;
import in.smartvrms.contracts.RefundableDepositRecord;
import in.smartvrms.contracts.StaffSession;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface FinancePort {
    PaymentRequestRecord createPaymentRequest(StaffSession session, UUID bookingId, long amountPaise, String paymentType, Instant dueAt, String notes);
    QrIntentRecord getPaymentQr(StaffSession session, UUID requestId);
    PaymentRecord recordVerifiedPayment(StaffSession session, RecordPaymentCommand cmd);
    List<PaymentRecord> listPaymentsForBooking(StaffSession session, UUID bookingId);
    RefundableDepositRecord getDepositForBooking(StaffSession session, UUID bookingId);
    RefundableDepositRecord manageDeposit(StaffSession session, UUID bookingId, String action, long amountPaise, String reason);
    FinancialCorrectionRecord approveCorrection(StaffSession session, FinancialCorrectionCommand cmd);
    BillingDocumentRecord issueBillingDocument(StaffSession session, UUID bookingId, String documentType, String notes);
    List<BillingDocumentRecord> listBillingDocuments(StaffSession session, UUID bookingId);
    byte[] renderDocumentPdf(StaffSession session, UUID documentId);
    CustomerStatement getCustomerStatement(StaffSession session, UUID customerId);
    LedgerSummary getLedgerSummary(StaffSession session, Instant from, Instant to);
}
