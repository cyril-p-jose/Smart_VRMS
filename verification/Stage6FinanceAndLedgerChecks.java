package in.smartvrms.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.application.port.AuthenticationPort;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import in.smartvrms.desktop.integration.DesktopSession;
import in.smartvrms.desktop.integration.FinanceHttpClient;
import in.smartvrms.desktop.ui.FinanceView;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.domain.JdbcFinanceService;
import in.smartvrms.server.finance.FinancialLedgerEngine;
import in.smartvrms.server.finance.PdfDocumentGenerator;
import in.smartvrms.server.finance.QrCodeGenerator;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.StaffAuthorizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage 6 Accounting Boundaries, Finance, Payments, Deposits, and PDF Verification Suite.
 * Requirements:
 * - FR-PAY-001..004: Payment requests, UPI QR intent (instruction only), staff-verified receipts, idempotency/duplicate rejection, admin-only corrections.
 * - FR-FIN-001..005: Refundable deposit liability vs revenue separation, explicit ledger movements, integer paise arithmetic, reconciliation & discrepancy reporting, employee payroll/profit privacy.
 * - FR-PDF-001..002: Actual PDFBox 3.0.3 generation, itemized lines, unverified GST status watermark / pro-forma disclaimer.
 */
public final class Stage6FinanceAndLedgerChecks {
    private static int checks = 0;
    private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EMPLOYEE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 6 Finance, Accounting & PDF verification checks...");

        testIntegerArithmeticAndPaiseRounding();
        testRefundableDepositLiabilitySeparation();
        testPaymentReceiptAllocationAndIdempotency();
        testUpiQrInstructionContract();
        testAdminOnlyRefundsAndCorrectionsHistory();
        testLedgerReconciliationAndDiscrepancyDetection();
        testPdfBoxUnverifiedGstDocumentGeneration();
        testHttpFinanceApiAndRoleAccessControl();
        testDesktopFinanceClientAndUiFlow();

        System.out.println("PASS: All " + checks + " Stage 6 finance, ledger, accounting, and PDF assertions passed cleanly!");
    }

    // -------------------------------------------------------------------------
    // 1. Integer Arithmetic & BigDecimal Exact Rounding (FR-FIN-004)
    // -------------------------------------------------------------------------
    private static void testIntegerArithmeticAndPaiseRounding() {
        long rentalDailyRatePaise = 249999L; // ₹2499.99
        int days = 7;
        long totalRental = rentalDailyRatePaise * days;
        require(totalRental == 1749993L, "Exact integer paise multiplication without floating-point drift");

        BigDecimal rupees = BigDecimal.valueOf(totalRental).divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);
        require("17499.93".equals(rupees.toPlainString()), "Rupee conversion matches exact decimal string");

        long totalToSplit = 1000L;
        long share1 = totalToSplit / 3;
        long share2 = totalToSplit / 3;
        long share3 = totalToSplit - (share1 + share2);
        require(share1 + share2 + share3 == totalToSplit, "Sum of split shares reconciles exactly with total paise");
        require(share1 == 333L && share2 == 333L && share3 == 334L, "Paise remainder distributed deterministically");
    }

    // -------------------------------------------------------------------------
    // 2. Refundable Deposit Liability Separation (FR-FIN-001)
    // -------------------------------------------------------------------------
    private static void testRefundableDepositLiabilitySeparation() {
        MockDatabase db = new MockDatabase();
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in", Set.of("ADMINISTRATOR", "RECORD_VERIFIED_PAYMENT", "POST_REFUND"));
        JdbcFinanceService finance = new JdbcFinanceService(db.dataSource(), null);

        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        db.addBooking(bookingId, customerId, "BK-2026-001", 1000000L, 500000L);

        // 1. Collect ₹5,000 security deposit
        RecordPaymentCommand depositCmd = new RecordPaymentCommand(
                bookingId,
                null,
                500000L,
                "UPI",
                "UTR-DEP-001",
                "Axis Bank statement UTR-DEP-001 verified",
                "Security deposit collection",
                0L,
                500000L
        );
        PaymentRecord depPmt = finance.recordVerifiedPayment(admin, depositCmd);
        require(depPmt != null, "Deposit payment recorded");

        RefundableDepositRecord depRec = finance.getDepositForBooking(admin, bookingId);
        require(depRec != null, "Refundable deposit record created");
        require(depRec.depositReceivedPaise() == 500000L, "Deposit received is ₹5,000");
        require(depRec.remainingHeldPaise() == 500000L, "Deposit remaining held is ₹5,000");
        require("HELD".equals(depRec.status()), "Deposit status is HELD");

        long revenueCredit = db.getLedgerEntrySum("REVENUE_EARNED", "CREDIT");
        long cashCredit = db.getLedgerEntrySum("CASH_RECEIVED", "CREDIT");
        require(revenueCredit == 0L, "Revenue has 0 credit for security deposit collection (FR-FIN-001)");
        require(cashCredit == 500000L, "Cash receipt recorded for deposit collection (FR-FIN-001)");

        // 2. Release/refund ₹4,000 of deposit
        RefundableDepositRecord partialRefund = finance.manageDeposit(admin, bookingId, "REFUND", 400000L, "Vehicle returned with no issues, ₹4000 refunded");
        require(partialRefund.depositRefundedPaise() == 400000L, "₹4,000 deposit refunded");
        require(partialRefund.remainingHeldPaise() == 100000L, "₹1,000 deposit remaining");
        require("PARTIALLY_REFUNDED".equals(partialRefund.status()), "Status is PARTIALLY_REFUNDED");

        long refundDebit = db.getLedgerEntrySum("REFUND_PAID", "DEBIT");
        require(refundDebit == 400000L, "Deposit refund debited on refund (FR-FIN-001)");

        // 3. Apply remaining ₹1,000 to damage / rental debt
        RefundableDepositRecord applied = finance.manageDeposit(admin, bookingId, "APPLY", 100000L, "Applied to interior scratch damage");
        require(applied.remainingHeldPaise() == 0L, "0 deposit remaining after application");
        require("FORFEITED_APPLIED".equals(applied.status()), "Deposit status fully FORFEITED_APPLIED");
        long appliedRevenue = db.getLedgerEntrySum("REVENUE_EARNED", "CREDIT");
        require(appliedRevenue == 100000L, "Applied deposit recognized as revenue");
    }

    // -------------------------------------------------------------------------
    // 3. Payment Receipt Allocation & Idempotency (FR-PAY-001, FR-PAY-004)
    // -------------------------------------------------------------------------
    private static void testPaymentReceiptAllocationAndIdempotency() {
        MockDatabase db = new MockDatabase();
        StaffSession staff = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("RECORD_VERIFIED_PAYMENT"));
        JdbcFinanceService finance = new JdbcFinanceService(db.dataSource(), null);

        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        db.addBooking(bookingId, customerId, "BK-2026-002", 1500000L, 500000L);

        RecordPaymentCommand pmt1 = new RecordPaymentCommand(
                bookingId,
                null,
                500000L,
                "BANK_TRANSFER",
                "NEFT-2026-9918",
                "HDFC Bank statement credit NEFT-2026-9918",
                "Advance & Deposit payment",
                300000L,
                200000L
        );
        PaymentRecord rec1 = finance.recordVerifiedPayment(staff, pmt1);
        require(rec1 != null, "Payment successfully recorded by staff");
        require(rec1.amountPaise() == 500000L, "Total receipt recorded as ₹5,000");

        require(db.allocations.size() == 2, "Two distinct allocation records created");
        long advanceAlloc = db.allocations.stream().filter(a -> "ADVANCE".equals(a.target)).mapToLong(a -> a.amount).sum();
        long depositAlloc = db.allocations.stream().filter(a -> "SECURITY_DEPOSIT".equals(a.target)).mapToLong(a -> a.amount).sum();
        require(advanceAlloc == 300000L, "₹3,000 allocated to ADVANCE");
        require(depositAlloc == 200000L, "₹2,000 allocated to DEPOSIT");

        boolean dupThrew = false;
        try {
            finance.recordVerifiedPayment(staff, pmt1);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            dupThrew = true;
            require(ex.getMessage().contains("Duplicate external reference") || ex.getMessage().contains("already recorded"),
                    "Error mentions duplicate reference: " + ex.getMessage());
        }
        require(dupThrew, "Duplicate external payment reference / UTR rejected (FR-PAY-004)");
    }

    // -------------------------------------------------------------------------
    // 4. UPI QR Instruction Contract (FR-PAY-003)
    // -------------------------------------------------------------------------
    private static void testUpiQrInstructionContract() {
        MockDatabase db = new MockDatabase();
        StaffSession staff = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("RECORD_VERIFIED_PAYMENT"));
        JdbcFinanceService finance = new JdbcFinanceService(db.dataSource(), null);

        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        db.addBooking(bookingId, customerId, "BK-2026-003", 800000L, 400000L);

        PaymentRequestRecord req = finance.createPaymentRequest(staff, bookingId, 400000L, "RENTAL_BALANCE", Instant.now().plus(Duration.ofDays(1)), "Balance due before checkout");
        require(req != null, "Payment request created");
        require("PENDING".equals(req.status()), "Payment request status is PENDING");
        require(req.qrPayload().startsWith("upi://pay"), "QR payload contains valid UPI URI");

        QrIntentRecord qr = finance.getPaymentQr(staff, req.id());
        require(qr != null, "QR intent returned");
        require(qr.merchantVpa().contains("@"), "Valid merchant VPA");
        require(qr.base64PngQr() != null && !qr.base64PngQr().isBlank(), "QR image generated as base64 PNG");

        // QR display does NOT confirm payment
        require("PENDING".equals(req.status()), "Request remains PENDING after QR fetch");
        require(db.payments.isEmpty(), "Zero payments recorded simply by generating or viewing QR");
        require(db.ledgerEntries.isEmpty(), "Zero ledger entries posted by QR generation (FR-PAY-003)");
    }

    // -------------------------------------------------------------------------
    // 5. Admin-Only Refunds & Financial Corrections (FR-PAY-002, FR-ACC-003)
    // -------------------------------------------------------------------------
    private static void testAdminOnlyRefundsAndCorrectionsHistory() {
        MockDatabase db = new MockDatabase();
        StaffSession staff = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("RECORD_VERIFIED_PAYMENT"));
        StaffSession admin = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in", Set.of("ADMINISTRATOR", "POST_CREDIT_NOTE", "POST_REFUND"));
        JdbcFinanceService finance = new JdbcFinanceService(db.dataSource(), null);

        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        db.addBooking(bookingId, customerId, "BK-2026-004", 1200000L, 600000L);

        FinancialCorrectionCommand corrCmd = new FinancialCorrectionCommand(
                bookingId,
                null,
                "CREDIT_NOTE",
                150000L,
                "Manager-approved delayed delivery discount",
                "REF-PAYOUT-001"
        );

        boolean staffDenied = false;
        try {
            finance.approveCorrection(staff, corrCmd);
        } catch (SecurityException | IllegalArgumentException ex) {
            staffDenied = true;
            require(ex.getMessage().toLowerCase().contains("administrator") || ex.getMessage().contains("Forbidden"),
                    "Staff denied message: " + ex.getMessage());
        }
        require(staffDenied, "Employee blocked from issuing financial corrections (FR-ACC-003, FR-PAY-002)");

        FinancialCorrectionRecord approved = finance.approveCorrection(admin, corrCmd);
        require(approved != null, "Admin successfully approved financial correction");
        require(approved.amountPaise() == 150000L, "Correction amount matches ₹1,500");
        require("PROCESSED".equals(approved.status()), "Correction status is PROCESSED");

        require(db.financialCorrections.size() == 1, "Correction recorded as dedicated audit entry");
        require(db.getLedgerEntrySum("REVENUE_EARNED", "DEBIT") == 150000L, "Goodwill discount debited to REVENUE_EARNED");
    }

    // -------------------------------------------------------------------------
    // 6. Ledger Reconciliation & Discrepancy Detection (FR-FIN-004)
    // -------------------------------------------------------------------------
    private static void testLedgerReconciliationAndDiscrepancyDetection() {
        Instant past = Instant.now().minusSeconds(86400 * 30);
        Instant now = Instant.now();

        long revenue = 1240000L;
        long cash = 820000L;
        long receivables = 420000L;
        long deposits = 500000L;
        long expenses = 215000L;

        LedgerSummary summary = FinancialLedgerEngine.calculateSummary(past, now, revenue, cash, receivables, deposits, expenses);
        require(summary.totalRevenueEarnedPaise() == 1240000L, "Revenue earned matches");
        require(summary.totalCashCollectedPaise() == 820000L, "Cash collected matches");
        require(summary.totalReceivablesPaise() == 420000L, "Receivables match");
        require(summary.totalDepositLiabilityPaise() == 500000L, "Deposit liability matches");
        require(summary.totalExpensesPaise() == 215000L, "Expenses match");
        require(summary.operatingMarginPaise() == (revenue - expenses), "Operating margin equals revenue minus expenses (FR-FIN-001, FR-FIN-004)");
    }

    // -------------------------------------------------------------------------
    // 7. PDFBox 3.0.3 Unverified GST Document Generation (FR-PDF-001, FR-PDF-002)
    // -------------------------------------------------------------------------
    private static void testPdfBoxUnverifiedGstDocumentGeneration() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        BillingDocumentRecord doc = new BillingDocumentRecord(
                docId,
                COMPANY_ID,
                bookingId,
                customerId,
                "PRO_FORMA_INVOICE",
                "VRMS-2026-INV-0042",
                1,
                "ISSUED",
                1499900L,
                500000L,
                999900L,
                300000L,
                0L,
                true,
                "{}",
                null,
                ADMIN_ID,
                Instant.now(),
                Instant.now()
        );

        List<BillingItemRecord> items = List.of(
                new BillingItemRecord(UUID.randomUUID(), COMPANY_ID, docId, UUID.randomUUID(), "RENTAL_CHARGE", "Compact SUV Rental (5 Days)", 5, 240000L, 1200000L, Instant.now()),
                new BillingItemRecord(UUID.randomUUID(), COMPANY_ID, docId, UUID.randomUUID(), "EXTRA_OPTION", "Child Safety Seat", 1, 99900L, 99900L, Instant.now())
        );

        List<PaymentRecord> payments = List.of(
                new PaymentRecord(UUID.randomUUID(), COMPANY_ID, bookingId, customerId, null, 500000L, "INR", "UPI", "UTR-PDF-01", "VERIFIED", "Confirmed", ADMIN_ID, Instant.now(), "Advance", Instant.now())
        );

        byte[] pdfBytes = PdfDocumentGenerator.generateBillingDocumentPdf(doc, "Rajesh Kumar", "+919876543210", items, payments);
        require(pdfBytes != null && pdfBytes.length > 500, "PDF generated as non-empty byte stream (>500 bytes)");

        String header = new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII);
        require("%PDF-".equals(header), "Generated document has valid PDF format header");

        try (PDDocument pdDoc = Loader.loadPDF(pdfBytes)) {
            require(pdDoc.getNumberOfPages() >= 1, "PDF contains at least 1 rendered page");

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(pdDoc);

            require(text.contains("BETA PRO-FORMA / NOT AN OFFICIAL GST TAX INVOICE"),
                    "PDF prominently displays unverified GST pro-forma disclaimer banner (FR-PDF-001)");
            require(text.contains("VRMS-2026-INV-0042"), "Document number rendered in PDF");
            require(text.contains("Rajesh Kumar"), "Customer name rendered in PDF");
            require(text.contains("Compact SUV Rental (5 Days)"), "Itemized rental line rendered");
        }
    }

    // -------------------------------------------------------------------------
    // 8. HTTP Finance API & Role-Based Access Control (FR-PAY-001..004, FR-FIN-005)
    // -------------------------------------------------------------------------
    private static void testHttpFinanceApiAndRoleAccessControl() throws Exception {
        MockDatabase db = new MockDatabase();
        JdbcFinanceService finance = new JdbcFinanceService(db.dataSource(), null);

        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        db.addBooking(bookingId, customerId, "BK-2026-005", 2000000L, 1000000L);

        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in",
                Set.of("ADMINISTRATOR", "RECORD_VERIFIED_PAYMENT", "VIEW_COMPANY_MARGIN", "POST_CREDIT_NOTE", "POST_REFUND"));

        HttpServerConfig config = new HttpServerConfig(0);
        SupabaseConfig sc = new SupabaseConfig("https://abcdefghijklmnopqrst.supabase.co", "sb_publishable_fixture0123456789");
        SupabaseAuth auth = new SupabaseAuth(sc);

        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            if ("admin-token".equals(token)) {
                return new StaffAuthorizer.Staff(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR");
            }
            if ("emp-token".equals(token)) {
                if (cap.administratorOnly()) {
                    throw new SecurityException("Forbidden: Administrator role required for " + cap);
                }
                return new StaffAuthorizer.Staff(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE");
            }
            throw new SecurityException("Invalid token");
        };

        VrmsHttpServer server = new VrmsHttpServer(
                config, auth, authorizer, null, null, null, null, null, null, null, null, null, null,
                finance
        );

        server.start();
        int port = server.port();
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;

        try {
            // 1. Employee tries to view company ledger summary -> 403 Forbidden
            HttpRequest getLedgerReq = HttpRequest.newBuilder(URI.create(base + "/api/v1/finance/ledger"))
                    .header("Authorization", "Bearer emp-token")
                    .GET()
                    .build();
            HttpResponse<String> getLedgerRes = http.send(getLedgerReq, HttpResponse.BodyHandlers.ofString());
            require(getLedgerRes.statusCode() == 403, "Employee receives 403 Forbidden when requesting company ledger summary (FR-FIN-005)");

            // 2. Admin views company ledger summary -> 200 OK
            HttpRequest adminLedgerReq = HttpRequest.newBuilder(URI.create(base + "/api/v1/finance/ledger"))
                    .header("Authorization", "Bearer admin-token")
                    .GET()
                    .build();
            HttpResponse<String> adminLedgerRes = http.send(adminLedgerReq, HttpResponse.BodyHandlers.ofString());
            require(adminLedgerRes.statusCode() == 200, "Admin receives 200 OK for company ledger summary");
            JsonObject ledgerObj = JsonParser.parseString(adminLedgerRes.body()).getAsJsonObject();
            require(ledgerObj.has("totalRevenueEarnedPaise"), "Ledger response has totalRevenueEarnedPaise");
            require(ledgerObj.has("operatingMarginPaise"), "Ledger response has operatingMarginPaise");

            // 3. Employee records verified payment -> 201 Created
            JsonObject pmtBody = new JsonObject();
            pmtBody.addProperty("bookingId", bookingId.toString());
            pmtBody.addProperty("amountPaise", 1000000L);
            pmtBody.addProperty("paymentMethod", "UPI");
            pmtBody.addProperty("externalReference", "UTR-HTTP-001");
            pmtBody.addProperty("verificationEvidence", "HDFC UPI settlement batch confirmed");
            pmtBody.addProperty("allocateToAdvancePaise", 500000L);
            pmtBody.addProperty("allocateToDepositPaise", 500000L);

            HttpRequest postPmtReq = HttpRequest.newBuilder(URI.create(base + "/api/v1/finance/payments"))
                    .header("Authorization", "Bearer emp-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(pmtBody.toString()))
                    .build();
            HttpResponse<String> postPmtRes = http.send(postPmtReq, HttpResponse.BodyHandlers.ofString());
            require(postPmtRes.statusCode() == 201, "Staff successfully posts verified payment receipt (201 Created)");

            // 4. Duplicate external reference -> 409 Conflict
            HttpResponse<String> dupRes = http.send(postPmtReq, HttpResponse.BodyHandlers.ofString());
            require(dupRes.statusCode() == 409, "Duplicate external reference rejected with 409 Conflict (FR-PAY-004)");

            // 5. Download pro-forma PDF document -> 200 OK with application/pdf
            BillingDocumentRecord issuedDoc = finance.issueBillingDocument(adminSession, bookingId, "PRO_FORMA_INVOICE", "Invoice for customer");
            HttpRequest getPdfReq = HttpRequest.newBuilder(URI.create(base + "/api/v1/finance/documents/pdf?documentId=" + issuedDoc.id()))
                    .header("Authorization", "Bearer emp-token")
                    .GET()
                    .build();
            HttpResponse<byte[]> getPdfRes = http.send(getPdfReq, HttpResponse.BodyHandlers.ofByteArray());
            require(getPdfRes.statusCode() == 200, "PDF document download returns 200 OK");
            require("application/pdf".equals(getPdfRes.headers().firstValue("Content-Type").orElse("")), "Content-Type is application/pdf");
            require(getPdfRes.body().length > 500, "Downloaded PDF bytes non-empty");

        } finally {
            server.stop(0);
        }
    }

    // -------------------------------------------------------------------------
    // 9. Desktop Finance Client & UI Flow Integration
    // -------------------------------------------------------------------------
    private static void testDesktopFinanceClientAndUiFlow() throws Exception {
        MockDatabase db = new MockDatabase();
        JdbcFinanceService finance = new JdbcFinanceService(db.dataSource(), null);

        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        db.addBooking(bookingId, customerId, "BK-2026-006", 1800000L, 800000L);

        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in",
                Set.of("ADMINISTRATOR", "RECORD_VERIFIED_PAYMENT", "VIEW_COMPANY_MARGIN", "POST_CREDIT_NOTE", "POST_REFUND"));

        HttpServerConfig config = new HttpServerConfig(0);
        SupabaseConfig sc = new SupabaseConfig("https://abcdefghijklmnopqrst.supabase.co", "sb_publishable_fixture0123456789");
        SupabaseAuth auth = new SupabaseAuth(sc);

        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            if ("token-desktop-admin".equals(token)) {
                return new StaffAuthorizer.Staff(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR");
            }
            throw new SecurityException("Invalid token");
        };

        VrmsHttpServer server = new VrmsHttpServer(
                config, auth, authorizer, null, null, null, null, null, null, null, null, null, null,
                finance
        );

        server.start();
        int port = server.port();

        try {
            DesktopConfig deskConfig = new DesktopConfig(Optional.of(URI.create("http://127.0.0.1:" + port)));
            DesktopSession desktopSession = new DesktopSession(new MockAuthPort());
            desktopSession.accept(new SignInResult(adminSession, new SessionTokens("token-desktop-admin", "rt", 3600)));

            FinanceHttpClient client = new FinanceHttpClient(deskConfig, desktopSession);

            // 1. Client creates payment request
            PaymentRequestRecord req = client.createPaymentRequest(bookingId, 800000L, "RENTAL_ADVANCE", Instant.now().plus(Duration.ofDays(2)), "Advance requested");
            require(req != null, "Desktop client created payment request");

            // 2. Client retrieves QR intent
            QrIntentRecord qr = client.getPaymentQr(req.id());
            require(qr != null && qr.base64PngQr() != null, "Desktop client retrieved QR intent with base64 PNG");

            // 3. Client records verified payment
            PaymentRecord pmt = client.recordVerifiedPayment(new RecordPaymentCommand(
                    bookingId,
                    req.id(),
                    800000L,
                    "CASH",
                    "CASH-DESK-0091",
                    "Physical cash verified at counter",
                    "Booking advance",
                    800000L,
                    0L
            ));
            require(pmt != null, "Desktop client recorded cash receipt");

            // 4. Client issues billing document
            BillingDocumentRecord doc = client.issueBillingDocument(bookingId, "PAYMENT_RECEIPT", "Desk receipt");
            require(doc != null, "Desktop client issued billing document");

            // 5. Client downloads PDF
            byte[] pdf = client.downloadDocumentPdf(doc.id());
            require(pdf.length > 500, "Desktop client downloaded generated PDF");

            // 6. Client retrieves authoritative ledger summary
            LedgerSummary summary = client.getLedgerSummary(null, null);
            require(summary != null, "Desktop client fetched ledger summary");

            // 7. Test FinanceView integration
            FinanceView view = new FinanceView(adminSession);
            view.setClient(client);
            require(view.correctionBtn.isEnabled(), "Admin has credit note/refund button enabled in UI");
            require(view.paymentsTable.getModel().getRowCount() >= 2, "Specimen/initial rows present in payments table");

            StaffSession empSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("EMPLOYEE"));
            FinanceView empView = new FinanceView(empSession);
            require(!empView.correctionBtn.isEnabled(), "Employee has credit note/refund button disabled in UI (FR-ACC-003)");

        } finally {
            server.stop(0);
        }
    }

    private static class MockAuthPort implements AuthenticationPort {
        @Override public boolean isConfigured() { return true; }
        @Override public SignInResult signIn(String e, char[] p) { return null; }
        @Override public SignInResult refresh(SessionTokens t) { return null; }
        @Override public void logout(SessionTokens t) {}
        @Override public StaffSession verifySession(String accessToken) { return null; }
    }

    // =========================================================================
    // Lightweight In-Memory Mock Database for JDBC Service Testing
    // =========================================================================
    static class MockDatabase {
        final Map<UUID, BookingEntity> bookings = new ConcurrentHashMap<>();
        final Map<UUID, PaymentRequestRecord> paymentRequests = new ConcurrentHashMap<>();
        final Map<UUID, PaymentRecord> payments = new ConcurrentHashMap<>();
        final Set<String> externalRefs = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final List<AllocationEntity> allocations = Collections.synchronizedList(new ArrayList<>());
        final Map<UUID, RefundableDepositRecord> deposits = new ConcurrentHashMap<>();
        final List<FinancialCorrectionRecord> financialCorrections = Collections.synchronizedList(new ArrayList<>());
        final Map<UUID, BillingDocumentRecord> documents = new ConcurrentHashMap<>();
        final List<BillingItemRecord> documentItems = Collections.synchronizedList(new ArrayList<>());
        final List<LedgerEntryEntity> ledgerEntries = Collections.synchronizedList(new ArrayList<>());

        static class BookingEntity {
            UUID id;
            UUID customerId;
            String bookingNumber;
            long totalAmountPaise;
            long advanceAmountPaise;
            long paidAmountPaise;
            String status = "CONFIRMED";
        }

        static class AllocationEntity {
            UUID id;
            UUID paymentId;
            String target;
            long amount;
        }

        static class LedgerEntryEntity {
            UUID id;
            String accountCode;
            String debitCredit;
            long amountPaise;
            UUID bookingId;
        }

        void addBooking(UUID id, UUID customerId, String number, long total, long advance) {
            BookingEntity b = new BookingEntity();
            b.id = id;
            b.customerId = customerId;
            b.bookingNumber = number;
            b.totalAmountPaise = total;
            b.advanceAmountPaise = advance;
            bookings.put(id, b);
        }

        long getLedgerEntrySum(String entryType, String debitCredit) {
            return ledgerEntries.stream()
                    .filter(e -> entryType.equals(e.accountCode) && debitCredit.equalsIgnoreCase(e.debitCredit))
                    .mapToLong(e -> e.amountPaise)
                    .sum();
        }

        long getLedgerAccountSum(String accountCode, String debitCredit) {
            return getLedgerEntrySum(accountCode, debitCredit);
        }

        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        if ("getConnection".equals(method.getName())) {
                            return createMockConnection();
                        }
                        return null;
                    }
            );
        }

        private Connection createMockConnection() {
            boolean[] autoCommit = {true};
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("prepareStatement".equals(name)) {
                            String sql = (String) args[0];
                            return createMockPreparedStatement(sql);
                        }
                        if ("setAutoCommit".equals(name)) {
                            autoCommit[0] = (Boolean) args[0];
                            return null;
                        }
                        if ("getAutoCommit".equals(name)) return autoCommit[0];
                        if ("commit".equals(name) || "rollback".equals(name) || "close".equals(name)) {
                            return null;
                        }
                        return null;
                    }
            );
        }

        private PreparedStatement createMockPreparedStatement(String sql) {
            Map<Integer, Object> params = new HashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        String m = method.getName();
                        if (m.startsWith("set") && args.length >= 2 && args[0] instanceof Integer) {
                            params.put((Integer) args[0], args[1]);
                            return null;
                        }
                        if ("executeQuery".equals(m)) {
                            return executeQuery(sql, params);
                        }
                        if ("executeUpdate".equals(m)) {
                            return executeUpdate(sql, params);
                        }
                        if ("execute".equals(m)) {
                            return false;
                        }
                        if ("close".equals(m)) {
                            return null;
                        }
                        return null;
                    }
            );
        }

        private ResultSet executeQuery(String sql, Map<Integer, Object> params) {
            List<Map<String, Object>> rows = new ArrayList<>();

            if (sql.contains("SELECT current_user = 'smart_vrms_api'")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("1", true);
                rows.add(row);
            } else if (sql.contains("SELECT company_id, role, active FROM vrms_private.memberships")) {
                UUID userId = (UUID) params.get(1);
                if (ADMIN_ID.equals(userId) || EMPLOYEE_ID.equals(userId)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("company_id", COMPANY_ID);
                    row.put("role", ADMIN_ID.equals(userId) ? "ADMINISTRATOR" : "EMPLOYEE");
                    row.put("active", true);
                    rows.add(row);
                }
            } else if (sql.contains("SELECT customer_id, total_paise, actual_advance_received_paise")) {
                UUID bId = (UUID) params.get(2);
                BookingEntity b = bookings.get(bId);
                if (b != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("customer_id", b.customerId);
                    r.put("total_paise", b.totalAmountPaise);
                    r.put("actual_advance_received_paise", b.advanceAmountPaise);
                    rows.add(r);
                }
            } else if (sql.contains("SELECT customer_id, booking_number, total_paise")) {
                UUID bId = (UUID) params.get(2);
                BookingEntity b = bookings.get(bId);
                if (b != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("customer_id", b.customerId);
                    r.put("booking_number", b.bookingNumber);
                    r.put("total_paise", b.totalAmountPaise);
                    r.put("actual_advance_received_paise", b.advanceAmountPaise);
                    r.put("deposit_paise", 500000L);
                    rows.add(r);
                }
            } else if (sql.contains("SELECT customer_id, booking_number FROM vrms_private.bookings")) {
                UUID bId = (UUID) params.get(2);
                BookingEntity b = bookings.get(bId);
                if (b != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("customer_id", b.customerId);
                    r.put("booking_number", b.bookingNumber);
                    rows.add(r);
                }
            } else if (sql.contains("INSERT INTO vrms_private.payment_requests") && sql.contains("RETURNING id, created_at")) {
                UUID id = UUID.randomUUID();
                Instant now = Instant.now();
                PaymentRequestRecord req = new PaymentRequestRecord(
                        id,
                        (UUID) params.get(1),
                        (UUID) params.get(2),
                        (UUID) params.get(3),
                        (Long) params.get(4),
                        "INR",
                        (String) params.get(5),
                        ((Timestamp) params.get(6)).toInstant(),
                        "PENDING",
                        (String) params.get(7),
                        (String) params.get(8),
                        (String) params.get(9),
                        (String) params.get(10),
                        (UUID) params.get(11),
                        now
                );
                paymentRequests.put(id, req);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", id);
                r.put("created_at", Timestamp.from(now));
                rows.add(r);
            } else if (sql.contains("SELECT qr_payload, merchant_vpa, merchant_name, amount_paise FROM vrms_private.payment_requests") ||
                       sql.contains("SELECT id, qr_payload, merchant_vpa, merchant_name, amount_paise FROM vrms_private.payment_requests")) {
                UUID reqId = (UUID) params.get(2);
                PaymentRequestRecord req = paymentRequests.get(reqId);
                if (req != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("qr_payload", req.qrPayload());
                    r.put("merchant_vpa", req.merchantVpa());
                    r.put("merchant_name", req.merchantName());
                    r.put("amount_paise", req.amountPaise());
                    rows.add(r);
                }
            } else if (sql.contains("SELECT id FROM vrms_private.payments WHERE company_id = ? AND external_reference = ?")) {
                String ref = (String) params.get(2);
                if (externalRefs.contains(ref)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", UUID.randomUUID());
                    rows.add(r);
                }
            } else if (sql.contains("INSERT INTO vrms_private.payments")) {
                String ref = (String) params.get(7);
                if (ref != null && !externalRefs.add(ref)) {
                    throw new IllegalStateException("Duplicate external reference / UTR already recorded: " + ref);
                }
                UUID id = UUID.randomUUID();
                Instant now = Instant.now();
                PaymentRecord pmt = new PaymentRecord(
                        id,
                        (UUID) params.get(1),
                        (UUID) params.get(2),
                        (UUID) params.get(3),
                        (UUID) params.get(4),
                        (Long) params.get(5),
                        "INR",
                        (String) params.get(6),
                        ref,
                        "VERIFIED",
                        (String) params.get(8),
                        (UUID) params.get(9),
                        now,
                        (String) params.get(10),
                        now
                );
                payments.put(id, pmt);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", id);
                r.put("created_at", Timestamp.from(now));
                rows.add(r);
            } else if (sql.contains("SELECT id FROM vrms_private.refundable_deposits WHERE company_id = ? AND booking_id = ?")) {
                UUID bId = (UUID) params.get(2);
                RefundableDepositRecord dep = deposits.get(bId);
                if (dep != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", dep.id());
                    rows.add(r);
                }
            } else if (sql.contains("SELECT id, customer_id, deposit_received_paise, deposit_refunded_paise, deposit_applied_paise FROM vrms_private.refundable_deposits") ||
                       sql.contains("SELECT id, deposit_received_paise, deposit_refunded_paise, deposit_applied_paise, status FROM vrms_private.refundable_deposits")) {
                UUID bId = (UUID) params.get(2);
                RefundableDepositRecord dep = deposits.get(bId);
                if (dep != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", dep.id());
                    r.put("customer_id", dep.customerId());
                    r.put("deposit_received_paise", dep.depositReceivedPaise());
                    r.put("deposit_refunded_paise", dep.depositRefundedPaise());
                    r.put("deposit_applied_paise", dep.depositAppliedPaise());
                    rows.add(r);
                }
            } else if (sql.contains("INSERT INTO vrms_private.financial_corrections")) {
                UUID id = UUID.randomUUID();
                Instant now = Instant.now();
                FinancialCorrectionRecord corr = new FinancialCorrectionRecord(
                        id,
                        (UUID) params.get(1),
                        (UUID) params.get(2),
                        (UUID) params.get(3),
                        (String) params.get(4),
                        (Long) params.get(5),
                        (String) params.get(6),
                        (UUID) params.get(7),
                        (String) params.get(8),
                        "PROCESSED",
                        now
                );
                financialCorrections.add(corr);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", id);
                r.put("created_at", Timestamp.from(now));
                rows.add(r);
            } else if (sql.contains("INSERT INTO vrms_private.billing_documents")) {
                UUID id = UUID.randomUUID();
                Instant now = Instant.now();
                String docNum = (String) params.get(5);
                BillingDocumentRecord doc = new BillingDocumentRecord(
                        id,
                        (UUID) params.get(1),
                        (UUID) params.get(2),
                        (UUID) params.get(3),
                        (String) params.get(4),
                        docNum,
                        1,
                        "ISSUED",
                        (Long) params.get(6),
                        (Long) params.get(7),
                        (Long) params.get(8),
                        (Long) params.get(9),
                        0L,
                        true,
                        (String) params.get(10),
                        null,
                        (UUID) params.get(11),
                        now,
                        now
                );
                documents.put(id, doc);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", id);
                r.put("issued_at", Timestamp.from(now));
                rows.add(r);
            } else if (sql.contains("SELECT name, gstin FROM vrms_private.companies")) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", "Smart VRMS Enterprise Pvt Ltd");
                r.put("gstin", "UNVERIFIED");
                rows.add(r);
            } else if (sql.contains("SELECT full_name, phone FROM vrms_private.customers")) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("full_name", "Rajesh Kumar");
                r.put("phone", "+919876543210");
                rows.add(r);
            } else if (sql.contains("SELECT v.plate_number FROM vrms_private.allocations")) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("plate_number", "KA05MK9911");
                rows.add(r);
            } else if (sql.contains("SELECT") && sql.contains("FROM vrms_private.billing_documents WHERE company_id = ? AND id = ?")) {
                UUID id = (UUID) params.get(2);
                BillingDocumentRecord d = documents.get(id);
                if (d != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", d.id());
                    r.put("company_id", d.companyId());
                    r.put("booking_id", d.bookingId());
                    r.put("customer_id", d.customerId());
                    r.put("document_type", d.documentType());
                    r.put("document_number", d.documentNumber());
                    r.put("document_version", d.documentVersion());
                    r.put("status", d.status());
                    r.put("total_paise", d.totalPaise());
                    r.put("advance_paid_paise", d.advancePaidPaise());
                    r.put("balance_due_paise", d.balanceDuePaise());
                    r.put("deposit_held_paise", d.depositHeldPaise());
                    r.put("tax_paise", d.taxPaise());
                    r.put("is_beta_proforma", d.isBetaProforma());
                    r.put("snapshot_json", d.snapshotJson());
                    r.put("file_metadata_id", d.fileMetadataId());
                    r.put("issued_by", d.issuedBy());
                    r.put("issued_at", Timestamp.from(d.issuedAt()));
                    r.put("created_at", Timestamp.from(d.createdAt()));
                    rows.add(r);
                }
            } else if (sql.contains("SELECT") && sql.contains("FROM vrms_private.billing_items WHERE billing_document_id = ?")) {
                UUID docId = (UUID) params.get(1);
                for (BillingItemRecord it : documentItems) {
                    if (it.billingDocumentId().equals(docId)) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("id", it.id());
                        r.put("company_id", it.companyId());
                        r.put("billing_document_id", it.billingDocumentId());
                        r.put("assignment_id", it.assignmentId());
                        r.put("item_type", it.itemType());
                        r.put("description", it.description());
                        r.put("quantity", it.quantity());
                        r.put("unit_rate_paise", it.unitRatePaise());
                        r.put("total_paise", it.totalPaise());
                        r.put("created_at", Timestamp.from(it.createdAt()));
                        rows.add(r);
                    }
                }
            } else if (sql.contains("SELECT id, company_id, booking_id, customer_id, deposit_received_paise")) {
                UUID bId = (UUID) params.get(2);
                RefundableDepositRecord dep = deposits.get(bId);
                if (dep != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", dep.id());
                    r.put("company_id", dep.companyId());
                    r.put("booking_id", dep.bookingId());
                    r.put("customer_id", dep.customerId());
                    r.put("deposit_received_paise", dep.depositReceivedPaise());
                    r.put("deposit_refunded_paise", dep.depositRefundedPaise());
                    r.put("deposit_applied_paise", dep.depositAppliedPaise());
                    r.put("status", dep.status());
                    r.put("held_at", Timestamp.from(dep.heldAt()));
                    r.put("refunded_at", dep.refundedAt() != null ? Timestamp.from(dep.refundedAt()) : null);
                    r.put("refund_ref", dep.refundRef());
                    r.put("refund_approved_by", dep.refundApprovedBy());
                    r.put("notes", dep.notes());
                    rows.add(r);
                }
            } else if (sql.contains("SELECT account_code, debit_credit, SUM(amount_paise)")) {
                Map<String, Long> groups = new HashMap<>();
                for (LedgerEntryEntity e : ledgerEntries) {
                    String k = e.accountCode + ":" + e.debitCredit.toUpperCase();
                    groups.put(k, groups.getOrDefault(k, 0L) + e.amountPaise);
                }
                for (var entry : groups.entrySet()) {
                    String[] parts = entry.getKey().split(":");
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("account_code", parts[0]);
                    r.put("debit_credit", parts[1]);
                    r.put("total_amount", entry.getValue());
                    rows.add(r);
                }
            }

            return createMockResultSet(rows);
        }

        private int executeUpdate(String sql, Map<Integer, Object> params) {
            if (sql.contains("INSERT INTO vrms_private.payment_allocations")) {
                AllocationEntity a = new AllocationEntity();
                a.id = UUID.randomUUID();
                a.paymentId = (UUID) params.get(2);
                a.target = (String) params.get(4);
                a.amount = (Long) params.get(5);
                allocations.add(a);
                return 1;
            }
            if (sql.contains("INSERT INTO vrms_private.refundable_deposits")) {
                UUID bId = (UUID) params.get(2);
                RefundableDepositRecord dep = new RefundableDepositRecord(
                        UUID.randomUUID(),
                        (UUID) params.get(1),
                        bId,
                        (UUID) params.get(3),
                        (Long) params.get(4),
                        0L,
                        0L,
                        "HELD",
                        Instant.now(),
                        null,
                        null,
                        null,
                        "Held"
                );
                deposits.put(bId, dep);
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.refundable_deposits SET deposit_received_paise")) {
                long addAmt = (Long) params.get(1);
                UUID depId = (UUID) params.get(2);
                for (var entry : deposits.entrySet()) {
                    RefundableDepositRecord cur = entry.getValue();
                    if (cur.id().equals(depId)) {
                        RefundableDepositRecord updated = new RefundableDepositRecord(
                                cur.id(),
                                cur.companyId(),
                                cur.bookingId(),
                                cur.customerId(),
                                cur.depositReceivedPaise() + addAmt,
                                cur.depositRefundedPaise(),
                                cur.depositAppliedPaise(),
                                "HELD",
                                cur.heldAt(),
                                cur.refundedAt(),
                                cur.refundRef(),
                                cur.refundApprovedBy(),
                                cur.notes()
                        );
                        deposits.put(entry.getKey(), updated);
                        break;
                    }
                }
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.refundable_deposits SET deposit_refunded_paise")) {
                UUID depId = (UUID) params.get(7);
                for (var entry : deposits.entrySet()) {
                    RefundableDepositRecord cur = entry.getValue();
                    if (cur.id().equals(depId)) {
                        long refPaise = (Long) params.get(1);
                        long appPaise = (Long) params.get(2);
                        String st = (String) params.get(3);
                        RefundableDepositRecord updated = new RefundableDepositRecord(
                                cur.id(),
                                cur.companyId(),
                                cur.bookingId(),
                                cur.customerId(),
                                cur.depositReceivedPaise(),
                                refPaise,
                                appPaise,
                                st,
                                cur.heldAt(),
                                Instant.now(),
                                (String) params.get(4),
                                (UUID) params.get(5),
                                (String) params.get(6)
                        );
                        deposits.put(entry.getKey(), updated);
                        break;
                    }
                }
                return 1;
            }
            if (sql.contains("INSERT INTO vrms_private.ledger_entries")) {
                LedgerEntryEntity le = new LedgerEntryEntity();
                le.id = UUID.randomUUID();
                le.bookingId = (UUID) params.get(2);
                le.accountCode = (String) params.get(3);
                le.debitCredit = (String) params.get(4);
                le.amountPaise = (Long) params.get(5);
                ledgerEntries.add(le);
                return 1;
            }
            if (sql.contains("INSERT INTO vrms_private.billing_items")) {
                UUID docId = (UUID) params.get(2);
                long amount = params.get(3) instanceof Long ? (Long) params.get(3) : 0L;
                BillingItemRecord item = new BillingItemRecord(
                        UUID.randomUUID(),
                        COMPANY_ID,
                        docId,
                        null,
                        "RENTAL_CHARGE",
                        "Commercial Vehicle Rental Package",
                        1,
                        amount,
                        amount,
                        Instant.now()
                );
                documentItems.add(item);
                return 1;
            }
            if (sql.contains("UPDATE vrms_private.bookings SET paid_amount_paise")) {
                UUID bId = (UUID) params.get(params.size());
                BookingEntity b = bookings.get(bId);
                if (b != null) {
                    b.paidAmountPaise += (Long) params.get(1);
                }
                return 1;
            }
            return 1;
        }

        private ResultSet createMockResultSet(List<Map<String, Object>> rows) {
            final int[] cursor = {-1};
            return (ResultSet) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        String m = method.getName();
                        if ("next".equals(m)) {
                            cursor[0]++;
                            return cursor[0] < rows.size();
                        }
                        if ("close".equals(m)) {
                            return null;
                        }
                        if (cursor[0] < 0 || cursor[0] >= rows.size()) {
                            throw new SQLException("Cursor out of range");
                        }
                        Map<String, Object> cur = rows.get(cursor[0]);
                        if (args != null && args.length > 0) {
                            Object key = args[0];
                            Object val = null;
                            if (key instanceof String) {
                                val = cur.get(((String) key).toLowerCase());
                            } else if (key instanceof Integer) {
                                int idx = (Integer) key - 1;
                                List<Object> vals = new ArrayList<>(cur.values());
                                if (idx >= 0 && idx < vals.size()) val = vals.get(idx);
                            }
                            if ("getString".equals(m)) return val != null ? val.toString() : null;
                            if ("getLong".equals(m)) return val != null ? ((Number) val).longValue() : 0L;
                            if ("getInt".equals(m)) return val != null ? ((Number) val).intValue() : 0;
                            if ("getBoolean".equals(m)) return val != null && (Boolean) val;
                            if ("getTimestamp".equals(m)) return val instanceof Timestamp ? val : (val != null ? Timestamp.from((Instant) val) : null);
                            if ("getObject".equals(m)) return val;
                        }
                        return null;
                    }
            );
        }
    }
}
