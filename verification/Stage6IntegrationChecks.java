package in.smartvrms.verification;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.application.port.AuditPort;
import in.smartvrms.application.port.FinancePort;
import in.smartvrms.contracts.BillingDocumentRecord;
import in.smartvrms.contracts.BillingItemRecord;
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
import in.smartvrms.desktop.ui.FinanceView;
import in.smartvrms.desktop.ui.WorkspaceView;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.finance.FinancialLedgerEngine;
import in.smartvrms.server.finance.PdfDocumentGenerator;
import in.smartvrms.server.finance.QrCodeGenerator;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.StaffAuthorizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.Dimension;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage 6 Integration Checks:
 * Immutable finance ledger, verified receipts, payment allocations, refundable deposits,
 * financial corrections/refunds, PDFBox 3.0.3 documents, ZXing UPI QR intents, and privacy guards
 * (FR-PAY-001..004, FR-FIN-001..005, FR-PDF-001..002).
 */
public final class Stage6IntegrationChecks {
    private static int checks;
    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EMPLOYEE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMPANY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 6 integration checks...");
        testMigration007SchemaIntegrity();
        testFinancialLedgerEngineCalculations();
        testQrCodeGenerator();
        testPdfDocumentGenerator();
        testMockFinancePortBusinessInvariants();
        testHttpServerFinanceEndpoints();
        testFinanceDesktopView();

        System.out.println("PASS: " + checks + " Stage 6 finance, payments, ledger, PDF, UPI QR, and UI assertions.");
    }

    // =========================================================================
    // 1. Migration 007 Schema Integrity Checks
    // =========================================================================
    private static void testMigration007SchemaIntegrity() throws Exception {
        Path migPath = Path.of("database", "migrations", "007_finance_ledger_and_documents.sql");
        require(Files.exists(migPath), "Migration 007 exists at database/migrations/007_finance_ledger_and_documents.sql");
        String sql = Files.readString(migPath);

        require(sql.contains("CREATE TABLE vrms_private.payment_requests"), "Table payment_requests defined");
        require(sql.contains("CREATE TABLE vrms_private.payments"), "Table payments defined");
        require(sql.contains("CREATE TABLE vrms_private.payment_allocations"), "Table payment_allocations defined");
        require(sql.contains("CREATE TABLE vrms_private.refundable_deposits"), "Table refundable_deposits defined");
        require(sql.contains("CREATE TABLE vrms_private.financial_corrections"), "Table financial_corrections defined");
        require(sql.contains("CREATE TABLE vrms_private.billing_documents"), "Table billing_documents defined");
        require(sql.contains("CREATE TABLE vrms_private.billing_items"), "Table billing_items defined");
        require(sql.contains("CREATE TABLE vrms_private.ledger_entries"), "Table ledger_entries defined");

        require(sql.contains("uq_payments_external_ref"), "Unique constraint on payment external reference prevents duplicate UTR posting");
        require(sql.contains("ENABLE ROW LEVEL SECURITY"), "Row Level Security enabled on all finance tables");
        require(sql.contains("smart_vrms_api"), "Permissions granted to smart_vrms_api role");
        require(sql.contains("schema_migrations (version, description)"), "Migration registers schema version 6");
    }

    // =========================================================================
    // 2. Financial Ledger Engine Exact Calculations
    // =========================================================================
    private static void testFinancialLedgerEngineCalculations() {
        BigDecimal rupees = FinancialLedgerEngine.paiseToRupees(245050L);
        require(rupees.compareTo(new BigDecimal("2450.50")) == 0, "paiseToRupees converts exactly");

        long paise = FinancialLedgerEngine.rupeesToPaise(new BigDecimal("1249.99"));
        require(paise == 124999L, "rupeesToPaise converts exactly");

        Instant now = Instant.now();
        Instant past = now.minusSeconds(86400 * 30);

        long revenue = 1240000L; // 12,400.00
        long cash = 820000L;     // 8,200.00
        long receivables = 420000L; // 4,200.00
        long deposits = 500000L; // 5,000.00
        long expenses = 215000L; // 2,150.00

        LedgerSummary summary = FinancialLedgerEngine.calculateSummary(past, now, revenue, cash, receivables, deposits, expenses);
        require(summary.totalRevenueEarnedPaise() == 1240000L, "Revenue earned matches");
        require(summary.totalCashCollectedPaise() == 820000L, "Cash collected matches");
        require(summary.totalReceivablesPaise() == 420000L, "Receivables match");
        require(summary.totalDepositLiabilityPaise() == 500000L, "Deposit liability matches");
        require(summary.totalExpensesPaise() == 215000L, "Expenses match");
        require(summary.operatingMarginPaise() == (revenue - expenses), "Operating margin exactly equals revenue minus expenses (FR-FIN-001, FR-FIN-004)");
    }

    // =========================================================================
    // 3. ZXing UPI QR Code Generator
    // =========================================================================
    private static void testQrCodeGenerator() {
        String uri = QrCodeGenerator.buildUpiUri("vrms@upi", "Smart VRMS", 250000L, "Rental Booking", "REF1234");
        require(uri.startsWith("upi://pay?"), "UPI URI starts with upi://pay?");
        require(uri.contains("pa=vrms%40upi") || uri.contains("pa=vrms@upi"), "VPA encoded");
        require(uri.contains("am=2500.00"), "Amount formatted in standard decimal rupees");
        require(uri.contains("cu=INR"), "Currency is INR");

        byte[] png = QrCodeGenerator.generateQrPng(uri, 200, 200);
        require(png != null && png.length > 50, "QR PNG bytes generated");
        // PNG magic bytes: -119, 80, 78, 71 (0x89, 'P', 'N', 'G')
        require(png[0] == (byte) 0x89 && png[1] == (byte) 0x50 && png[2] == (byte) 0x4E && png[3] == (byte) 0x47, "Valid PNG magic header bytes");

        String b64 = QrCodeGenerator.generateQrBase64Png(uri, 150, 150);
        require(b64 != null && !b64.isBlank(), "Base64 PNG string generated");
    }

    // =========================================================================
    // 4. Apache PDFBox 3.0.3 Document Generator
    // =========================================================================
    private static void testPdfDocumentGenerator() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        BillingDocumentRecord doc = new BillingDocumentRecord(
                docId,
                COMPANY_ID,
                bookingId,
                customerId,
                "PRO_FORMA_INVOICE",
                "VRMS-2026-DOC-0042",
                1,
                "ISSUED",
                480000L, // ₹4800.00
                240000L, // ₹2400.00 advance
                240000L, // ₹2400.00 balance
                100000L, // ₹1000.00 deposit
                0L,      // ₹0.00 unverified tax
                true,
                "{}",
                null,
                ADMIN_ID,
                Instant.now(),
                Instant.now()
        );

        List<BillingItemRecord> items = List.of(
                new BillingItemRecord(UUID.randomUUID(), COMPANY_ID, docId, UUID.randomUUID(), "RENTAL_CHARGE", "Hyundai Creta (2 Days)", 1, 400000L, 400000L, Instant.now()),
                new BillingItemRecord(UUID.randomUUID(), COMPANY_ID, docId, UUID.randomUUID(), "DRIVER_FEE", "Professional Driver (2 Days)", 1, 80000L, 80000L, Instant.now())
        );

        List<PaymentRecord> payments = List.of(
                new PaymentRecord(UUID.randomUUID(), COMPANY_ID, bookingId, customerId, null, 240000L, "INR", "UPI", "UTR9928310", "VERIFIED", "Bank Statement Verified", ADMIN_ID, Instant.now(), "Advance payment", Instant.now())
        );

        byte[] pdfBytes = PdfDocumentGenerator.generateBillingDocumentPdf(doc, "Sunil Kumar", "+91 9876543210", items, payments);
        require(pdfBytes != null && pdfBytes.length > 500, "PDF bytes generated");

        // Validate PDF magic header: %PDF-
        String header = new String(pdfBytes, 0, 5);
        require("%PDF-".equals(header), "Valid PDF standard magic header %PDF-");

        // Parse with PDFBox 3.0.3 Loader and verify rendered text content
        try (PDDocument parsed = Loader.loadPDF(pdfBytes)) {
            require(parsed.getNumberOfPages() >= 1, "PDF has at least 1 page");
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(parsed);

            require(text.contains("SMART VRMS"), "PDF contains SMART VRMS branding");
            require(text.contains("BETA PRO-FORMA / NOT AN OFFICIAL GST TAX INVOICE"), "PDF contains mandatory beta disclaimer (FR-BOOK-009, FR-PDF-001)");
            require(text.contains("VRMS-2026-DOC-0042"), "PDF contains document number");
            require(text.contains("Sunil Kumar"), "PDF contains customer name");
            require(text.contains("Hyundai Creta"), "PDF contains rental line item");
            require(text.contains("4800.00"), "PDF contains total amount 4800.00");
            require(text.contains("UTR9928310"), "PDF contains payment reference UTR9928310");
        }
    }

    // =========================================================================
    // 5. Mock Finance Port Business Invariants
    // =========================================================================
    private static void testMockFinancePortBusinessInvariants() {
        MockFinancePort mock = new MockFinancePort();
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in", Set.of("ADMINISTRATOR", "VIEW_FLEET", "RECORD_VERIFIED_PAYMENT", "POST_REFUND", "POST_CREDIT_NOTE", "VIEW_COMPANY_MARGIN"));
        StaffSession empSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("VIEW_FLEET", "RECORD_VERIFIED_PAYMENT", "VIEW_OPERATIONAL_BALANCE"));

        UUID bookingId = UUID.randomUUID();

        // 1. Negative payment rejected
        boolean negFail = false;
        try {
            mock.recordVerifiedPayment(adminSession, new RecordPaymentCommand(bookingId, null, -100L, "CASH", "REF1", "Cash", "", 0, 0));
        } catch (IllegalArgumentException e) {
            negFail = true;
        }
        require(negFail, "Negative payment amount rejected with IllegalArgumentException (FR-PAY-001)");

        // 2. Duplicate UTR rejected
        RecordPaymentCommand cmd1 = new RecordPaymentCommand(bookingId, null, 250000L, "UPI", "UTR1001", "Bank Verified", "", 0, 0);
        mock.recordVerifiedPayment(adminSession, cmd1);

        boolean dupFail = false;
        try {
            mock.recordVerifiedPayment(adminSession, cmd1);
        } catch (IllegalStateException e) {
            dupFail = true;
        }
        require(dupFail, "Duplicate payment reference / UTR rejected with IllegalStateException (FR-PAY-001, FR-PAY-004)");

        // 3. Refund deposit exceeding held deposit rejected
        mock.deposits.put(bookingId, new RefundableDepositRecord(UUID.randomUUID(), COMPANY_ID, bookingId, UUID.randomUUID(), 100000L, 0L, 0L, "HELD", Instant.now(), null, null, null, null));
        boolean depOverFail = false;
        try {
            mock.manageDeposit(adminSession, bookingId, "REFUND", 150000L, "Excess refund test");
        } catch (IllegalArgumentException e) {
            depOverFail = true;
        }
        require(depOverFail, "Refunding more than held deposit rejected with IllegalArgumentException (FR-FIN-001, FR-PAY-002)");

        // 4. Employee cannot approve credit notes or refunds (FR-ACC-003, FR-PAY-002)
        boolean empCorrFail = false;
        try {
            mock.approveCorrection(empSession, new FinancialCorrectionCommand(bookingId, null, "CREDIT_NOTE", 50000L, "Goodwill", null));
        } catch (SecurityException e) {
            empCorrFail = true;
        }
        require(empCorrFail, "Employee cannot approve credit note/refund (SecurityException thrown)");

        // 5. Employee cannot view company operating margin or ledger (FR-ACC-003, FR-FIN-005)
        boolean empLedgerFail = false;
        try {
            mock.getLedgerSummary(empSession, Instant.now().minusSeconds(86400), Instant.now());
        } catch (SecurityException e) {
            empLedgerFail = true;
        }
        require(empLedgerFail, "Employee cannot view company financial ledger or operating margin (SecurityException thrown)");

        // 6. Administrator succeeds
        LedgerSummary adminSum = mock.getLedgerSummary(adminSession, Instant.now().minusSeconds(86400), Instant.now());
        require(adminSum != null && adminSum.operatingMarginPaise() > 0, "Administrator retrieves company operating margin");
    }

    // =========================================================================
    // 6. HTTP Server Finance Endpoints Loopback Tests
    // =========================================================================
    private static void testHttpServerFinanceEndpoints() throws Exception {
        MockFinancePort mock = new MockFinancePort();

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
                config, auth, authorizer, null, null, null, null, null, null, null, null, null, null, mock
        );
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + server.port();

        try {
            UUID bookingId = UUID.randomUUID();

            // 1. POST /api/v1/finance/requests
            JsonObject reqBody = new JsonObject();
            reqBody.addProperty("bookingId", bookingId.toString());
            reqBody.addProperty("amountPaise", 250000L);
            reqBody.addProperty("paymentType", "RENTAL_BALANCE");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/finance/requests"))
                    .header("Authorization", "Bearer emp-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody.toString()))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            require(resp.statusCode() == 201, "Create payment request returns 201 Created");
            JsonObject reqResp = JsonParser.parseString(resp.body()).getAsJsonObject();
            String requestId = reqResp.get("id").getAsString();

            // 2. GET /api/v1/finance/requests/qr
            HttpRequest qrReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/finance/requests/qr?requestId=" + requestId))
                    .header("Authorization", "Bearer emp-token")
                    .GET()
                    .build();
            HttpResponse<String> qrResp = client.send(qrReq, HttpResponse.BodyHandlers.ofString());
            require(qrResp.statusCode() == 200, "Get payment QR returns 200 OK");
            JsonObject qrJson = JsonParser.parseString(qrResp.body()).getAsJsonObject();
            require(qrJson.has("base64PngQr") && !qrJson.get("base64PngQr").getAsString().isBlank(), "QR payload contains Base64 PNG");

            // 3. POST /api/v1/finance/payments
            JsonObject payBody = new JsonObject();
            payBody.addProperty("bookingId", bookingId.toString());
            payBody.addProperty("paymentRequestId", requestId);
            payBody.addProperty("amountPaise", 250000L);
            payBody.addProperty("paymentMethod", "UPI");
            payBody.addProperty("externalReference", "UTR778899");
            payBody.addProperty("verificationEvidence", "Bank statement credit verified");

            HttpRequest payReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/finance/payments"))
                    .header("Authorization", "Bearer emp-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payBody.toString()))
                    .build();
            HttpResponse<String> payResp = client.send(payReq, HttpResponse.BodyHandlers.ofString());
            require(payResp.statusCode() == 201, "Record payment returns 201 Created");

            // 4. GET /api/v1/finance/payments?bookingId=...
            HttpRequest listPayReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/finance/payments?bookingId=" + bookingId))
                    .header("Authorization", "Bearer emp-token")
                    .GET()
                    .build();
            HttpResponse<String> listPayResp = client.send(listPayReq, HttpResponse.BodyHandlers.ofString());
            require(listPayResp.statusCode() == 200, "List payments returns 200 OK");

            // 5. POST /api/v1/finance/corrections (Employee denied -> 403 Forbidden)
            JsonObject corrBody = new JsonObject();
            corrBody.addProperty("bookingId", bookingId.toString());
            corrBody.addProperty("correctionType", "CREDIT_NOTE");
            corrBody.addProperty("amountPaise", 50000L);
            corrBody.addProperty("reason", "Goodwill waiver");

            HttpRequest corrEmpReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/finance/corrections"))
                    .header("Authorization", "Bearer emp-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(corrBody.toString()))
                    .build();
            HttpResponse<String> corrEmpResp = client.send(corrEmpReq, HttpResponse.BodyHandlers.ofString());
            require(corrEmpResp.statusCode() == 403, "Employee denied financial correction authorization (403 Forbidden)");

            // 6. POST /api/v1/finance/corrections (Administrator authorized -> 201 Created)
            HttpRequest corrAdminReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/finance/corrections"))
                    .header("Authorization", "Bearer admin-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(corrBody.toString()))
                    .build();
            HttpResponse<String> corrAdminResp = client.send(corrAdminReq, HttpResponse.BodyHandlers.ofString());
            require(corrAdminResp.statusCode() == 201, "Administrator authorizes financial correction (201 Created)");

            // 7. POST /api/v1/finance/documents
            JsonObject docBody = new JsonObject();
            docBody.addProperty("bookingId", bookingId.toString());
            docBody.addProperty("documentType", "PRO_FORMA_INVOICE");

            HttpRequest docReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/finance/documents"))
                    .header("Authorization", "Bearer emp-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(docBody.toString()))
                    .build();
            HttpResponse<String> docResp = client.send(docReq, HttpResponse.BodyHandlers.ofString());
            require(docResp.statusCode() == 201, "Issue billing document returns 201 Created");
            JsonObject docJson = JsonParser.parseString(docResp.body()).getAsJsonObject();
            String docId = docJson.get("id").getAsString();

            // 8. GET /api/v1/finance/documents/pdf?documentId=...
            HttpRequest pdfReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/finance/documents/pdf?documentId=" + docId))
                    .header("Authorization", "Bearer emp-token")
                    .GET()
                    .build();
            HttpResponse<byte[]> pdfResp = client.send(pdfReq, HttpResponse.BodyHandlers.ofByteArray());
            require(pdfResp.statusCode() == 200, "Download PDF returns 200 OK");
            require("application/pdf".equals(pdfResp.headers().firstValue("Content-Type").orElse("")), "Content-Type is application/pdf");
            require(pdfResp.body().length > 500, "Returned non-empty PDF bytes");

            // 9. GET /api/v1/finance/ledger (Employee denied -> 403 Forbidden)
            HttpRequest ledgerEmpReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/finance/ledger"))
                    .header("Authorization", "Bearer emp-token")
                    .GET()
                    .build();
            HttpResponse<String> ledgerEmpResp = client.send(ledgerEmpReq, HttpResponse.BodyHandlers.ofString());
            require(ledgerEmpResp.statusCode() == 403, "Employee cannot access company ledger or profit margins (403 Forbidden, FR-ACC-003, FR-FIN-005)");

            // 10. GET /api/v1/finance/ledger (Administrator authorized -> 200 OK)
            HttpRequest ledgerAdminReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/finance/ledger"))
                    .header("Authorization", "Bearer admin-token")
                    .GET()
                    .build();
            HttpResponse<String> ledgerAdminResp = client.send(ledgerAdminReq, HttpResponse.BodyHandlers.ofString());
            require(ledgerAdminResp.statusCode() == 200, "Administrator retrieves company financial ledger (200 OK)");

        } finally {
            server.stop(0);
        }
    }

    // =========================================================================
    // 7. Desktop Swing Finance View Layout & Display Scaling
    // =========================================================================
    private static void testFinanceDesktopView() {
        StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.in", Set.of("ADMINISTRATOR", "VIEW_FLEET", "RECORD_VERIFIED_PAYMENT", "POST_REFUND", "POST_CREDIT_NOTE", "VIEW_COMPANY_MARGIN"));
        StaffSession empSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "emp@smartvrms.in", Set.of("VIEW_FLEET", "RECORD_VERIFIED_PAYMENT", "VIEW_OPERATIONAL_BALANCE"));

        FinanceView view = new FinanceView(empSession);
        require(view.paymentsTableModel.getRowCount() > 0, "Specimen payments populated");
        require(view.docsTableModel.getRowCount() > 0, "Specimen billing documents populated");
        require(!view.correctionBtn.isEnabled(), "Credit note/refund button disabled for Employee session (FR-ACC-003, FR-PAY-002)");

        // Switch to Admin session
        view.setSession(adminSession);
        require(view.correctionBtn.isEnabled(), "Credit note/refund button enabled for Administrator session");

        WorkspaceView workspace = new WorkspaceView(() -> {}, adminSession);
        require(workspace.financeView != null, "WorkspaceView contains FinanceView");

        // Responsive resolutions: 1024x720, 1280x820, 1600x1000
        int[][] resolutions = {{1024, 720}, {1280, 820}, {1600, 1000}};
        for (int[] res : resolutions) {
            view.setSize(new Dimension(res[0], res[1]));
            view.doLayout();
            require(view.getWidth() == res[0] && view.getHeight() == res[1], "FinanceView scales to " + res[0] + "x" + res[1]);

            workspace.setSize(new Dimension(res[0], res[1]));
            workspace.doLayout();
            workspace.showPage("Finance");
            require(workspace.getWidth() == res[0] && workspace.getHeight() == res[1], "WorkspaceView scales to " + res[0] + "x" + res[1]);
        }
    }

    // =========================================================================
    // Mock Finance Port for offline isolation testing
    // =========================================================================
    private static class MockFinancePort implements FinancePort {
        final Map<UUID, PaymentRequestRecord> requests = new ConcurrentHashMap<>();
        final Map<UUID, List<PaymentRecord>> payments = new ConcurrentHashMap<>();
        final Set<String> recordedReferences = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final Map<UUID, RefundableDepositRecord> deposits = new ConcurrentHashMap<>();
        final Map<UUID, List<BillingDocumentRecord>> documents = new ConcurrentHashMap<>();
        final Map<UUID, BillingDocumentRecord> docsById = new ConcurrentHashMap<>();

        @Override
        public PaymentRequestRecord createPaymentRequest(StaffSession session, UUID bookingId, long amountPaise, String paymentType, Instant dueAt, String notes) {
            UUID id = UUID.randomUUID();
            String uri = QrCodeGenerator.buildUpiUri("merchant@vrms", "Smart VRMS", amountPaise, "Rental Booking", bookingId.toString().substring(0, 8));
            PaymentRequestRecord r = new PaymentRequestRecord(id, COMPANY_ID, bookingId, UUID.randomUUID(), amountPaise, "INR", paymentType, dueAt != null ? dueAt : Instant.now().plusSeconds(86400), "PENDING", "merchant@vrms", "Smart VRMS", uri, notes, session.userId(), Instant.now());
            requests.put(id, r);
            return r;
        }

        @Override
        public QrIntentRecord getPaymentQr(StaffSession session, UUID requestId) {
            PaymentRequestRecord r = requests.get(requestId);
            if (r == null) throw new IllegalArgumentException("Request not found: " + requestId);
            String b64 = QrCodeGenerator.generateQrBase64Png(r.qrPayload(), 200, 200);
            return new QrIntentRecord(requestId, r.qrPayload(), r.merchantVpa(), r.merchantName(), r.amountPaise(), b64);
        }

        @Override
        public PaymentRecord recordVerifiedPayment(StaffSession session, RecordPaymentCommand cmd) {
            if (cmd.amountPaise() <= 0) throw new IllegalArgumentException("Amount must be positive");
            if (cmd.externalReference() != null && !cmd.externalReference().isBlank()) {
                if (!recordedReferences.add(cmd.externalReference().trim())) {
                    throw new IllegalStateException("Duplicate payment reference / UTR already recorded: " + cmd.externalReference());
                }
            }
            UUID id = UUID.randomUUID();
            PaymentRecord p = new PaymentRecord(id, COMPANY_ID, cmd.bookingId(), UUID.randomUUID(), cmd.paymentRequestId(), cmd.amountPaise(), "INR", cmd.paymentMethod(), cmd.externalReference(), "VERIFIED", cmd.verificationEvidence(), session.userId(), Instant.now(), cmd.notes(), Instant.now());
            payments.computeIfAbsent(cmd.bookingId(), k -> new ArrayList<>()).add(p);
            return p;
        }

        @Override
        public List<PaymentRecord> listPaymentsForBooking(StaffSession session, UUID bookingId) {
            return payments.getOrDefault(bookingId, List.of());
        }

        @Override
        public RefundableDepositRecord getDepositForBooking(StaffSession session, UUID bookingId) {
            return deposits.get(bookingId);
        }

        @Override
        public RefundableDepositRecord manageDeposit(StaffSession session, UUID bookingId, String action, long amountPaise, String reason) {
            if (!"ADMINISTRATOR".equals(session.role())) throw new SecurityException("Admin only");
            RefundableDepositRecord cur = deposits.get(bookingId);
            if (cur == null) throw new IllegalStateException("No deposit found");
            long rem = cur.remainingHeldPaise();
            if (amountPaise > rem) throw new IllegalArgumentException("Amount exceeds deposit held");
            RefundableDepositRecord upd = new RefundableDepositRecord(cur.id(), COMPANY_ID, bookingId, cur.customerId(), cur.depositReceivedPaise(), cur.depositRefundedPaise() + amountPaise, cur.depositAppliedPaise(), "PARTIALLY_REFUNDED", cur.heldAt(), Instant.now(), "REF123", session.userId(), reason);
            deposits.put(bookingId, upd);
            return upd;
        }

        @Override
        public FinancialCorrectionRecord approveCorrection(StaffSession session, FinancialCorrectionCommand cmd) {
            if (!"ADMINISTRATOR".equals(session.role())) throw new SecurityException("Admin only");
            return new FinancialCorrectionRecord(UUID.randomUUID(), COMPANY_ID, cmd.bookingId(), cmd.paymentId(), cmd.correctionType(), cmd.amountPaise(), cmd.reason(), session.userId(), cmd.bankPayoutRef(), "PROCESSED", Instant.now());
        }

        @Override
        public BillingDocumentRecord issueBillingDocument(StaffSession session, UUID bookingId, String documentType, String notes) {
            UUID id = UUID.randomUUID();
            BillingDocumentRecord d = new BillingDocumentRecord(id, COMPANY_ID, bookingId, UUID.randomUUID(), documentType, "VRMS-2026-DOC-1234", 1, "ISSUED", 240000L, 120000L, 120000L, 100000L, 0L, true, "{}", null, session.userId(), Instant.now(), Instant.now());
            documents.computeIfAbsent(bookingId, k -> new ArrayList<>()).add(d);
            docsById.put(id, d);
            return d;
        }

        @Override
        public List<BillingDocumentRecord> listBillingDocuments(StaffSession session, UUID bookingId) {
            return documents.getOrDefault(bookingId, List.of());
        }

        @Override
        public byte[] renderDocumentPdf(StaffSession session, UUID documentId) {
            BillingDocumentRecord doc = docsById.get(documentId);
            if (doc == null) {
                doc = new BillingDocumentRecord(documentId, COMPANY_ID, UUID.randomUUID(), UUID.randomUUID(), "PRO_FORMA_INVOICE", "VRMS-2026-DOC-1234", 1, "ISSUED", 240000L, 120000L, 120000L, 100000L, 0L, true, "{}", null, session.userId(), Instant.now(), Instant.now());
            }
            return PdfDocumentGenerator.generateBillingDocumentPdf(doc, "Test Customer", "+91 9999999999", List.of(), List.of());
        }

        @Override
        public CustomerStatement getCustomerStatement(StaffSession session, UUID customerId) {
            return new CustomerStatement(customerId, "Test Customer", 500000L, 300000L, 200000L, 100000L, List.of(), List.of());
        }

        @Override
        public LedgerSummary getLedgerSummary(StaffSession session, Instant from, Instant to) {
            if (!"ADMINISTRATOR".equals(session.role())) throw new SecurityException("Employee cannot view company financial ledger or operating margin (FR-ACC-003, FR-FIN-005)");
            return FinancialLedgerEngine.calculateSummary(from, to, 1240000L, 820000L, 420000L, 500000L, 215000L);
        }
    }
}
