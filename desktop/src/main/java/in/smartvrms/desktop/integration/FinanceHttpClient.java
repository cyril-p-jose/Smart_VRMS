package in.smartvrms.desktop.integration;

import com.google.gson.*;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.infrastructure.DesktopConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Authenticated Finance, Payment, Ledger, and Billing Documents API Client
 * (FR-PAY-001..004, FR-FIN-001..005, FR-PDF-001..002).
 */
public final class FinanceHttpClient {
    private final URI endpoint;
    private final DesktopSession session;
    private final DesktopHttp http = new DesktopHttp();

    public FinanceHttpClient(DesktopConfig config, DesktopSession session) {
        this.endpoint = config.apiEndpoint().orElseThrow();
        this.session = Objects.requireNonNull(session, "session");
    }

    public PaymentRequestRecord createPaymentRequest(UUID bookingId, long amountPaise, String paymentType, Instant dueAt, String notes) {
        JsonObject req = new JsonObject();
        req.addProperty("bookingId", bookingId.toString());
        req.addProperty("amountPaise", amountPaise);
        if (paymentType != null) req.addProperty("paymentType", paymentType);
        if (dueAt != null) req.addProperty("dueAt", dueAt.toString());
        if (notes != null && !notes.isBlank()) req.addProperty("notes", notes.strip());

        JsonObject res = sendPost("/api/v1/finance/requests", req);
        return parsePaymentRequest(res);
    }

    public QrIntentRecord getPaymentQr(UUID requestId) {
        String path = "/api/v1/finance/requests/qr?requestId=" + URLEncoder.encode(requestId.toString(), StandardCharsets.UTF_8);
        JsonObject res = sendGet(path);
        return new QrIntentRecord(
                uuid(res, "requestId"),
                text(res, "upiUri"),
                text(res, "merchantVpa"),
                text(res, "merchantName"),
                res.get("amountPaise").getAsLong(),
                text(res, "base64PngQr")
        );
    }

    public PaymentRecord recordVerifiedPayment(RecordPaymentCommand cmd) {
        JsonObject req = new JsonObject();
        req.addProperty("bookingId", cmd.bookingId().toString());
        if (cmd.paymentRequestId() != null) req.addProperty("paymentRequestId", cmd.paymentRequestId().toString());
        req.addProperty("amountPaise", cmd.amountPaise());
        if (cmd.paymentMethod() != null) req.addProperty("paymentMethod", cmd.paymentMethod());
        if (cmd.externalReference() != null) req.addProperty("externalReference", cmd.externalReference().strip());
        req.addProperty("verificationEvidence", cmd.verificationEvidence() != null ? cmd.verificationEvidence().strip() : "Verified");
        if (cmd.notes() != null) req.addProperty("notes", cmd.notes().strip());
        req.addProperty("allocateToAdvancePaise", cmd.allocateToAdvancePaise());
        req.addProperty("allocateToDepositPaise", cmd.allocateToDepositPaise());

        JsonObject res = sendPost("/api/v1/finance/payments", req);
        return parsePayment(res);
    }

    public List<PaymentRecord> listPaymentsForBooking(UUID bookingId) {
        String path = "/api/v1/finance/payments?bookingId=" + URLEncoder.encode(bookingId.toString(), StandardCharsets.UTF_8);
        JsonObject res = sendGet(path);
        List<PaymentRecord> list = new ArrayList<>();
        if (res.has("payments") && res.get("payments").isJsonArray()) {
            for (var el : res.getAsJsonArray("payments")) {
                list.add(parsePayment(el.getAsJsonObject()));
            }
        }
        return list;
    }

    public RefundableDepositRecord getDepositForBooking(UUID bookingId) {
        String path = "/api/v1/finance/deposits?bookingId=" + URLEncoder.encode(bookingId.toString(), StandardCharsets.UTF_8);
        JsonObject res = sendGet(path);
        if (!res.has("id")) return null;
        return parseDeposit(res);
    }

    public RefundableDepositRecord manageDeposit(UUID bookingId, String action, long amountPaise, String reason) {
        JsonObject req = new JsonObject();
        req.addProperty("bookingId", bookingId.toString());
        req.addProperty("action", action);
        req.addProperty("amountPaise", amountPaise);
        req.addProperty("reason", reason != null ? reason.strip() : "Deposit management");

        JsonObject res = sendPost("/api/v1/finance/deposits", req);
        return parseDeposit(res);
    }

    public FinancialCorrectionRecord approveCorrection(FinancialCorrectionCommand cmd) {
        JsonObject req = new JsonObject();
        req.addProperty("bookingId", cmd.bookingId().toString());
        if (cmd.paymentId() != null) req.addProperty("paymentId", cmd.paymentId().toString());
        req.addProperty("correctionType", cmd.correctionType());
        req.addProperty("amountPaise", cmd.amountPaise());
        req.addProperty("reason", cmd.reason());
        if (cmd.bankPayoutRef() != null) req.addProperty("bankPayoutRef", cmd.bankPayoutRef().strip());

        JsonObject res = sendPost("/api/v1/finance/corrections", req);
        return new FinancialCorrectionRecord(
                uuid(res, "id"),
                uuid(res, "companyId"),
                uuid(res, "bookingId"),
                uuid(res, "paymentId"),
                text(res, "correctionType"),
                res.get("amountPaise").getAsLong(),
                text(res, "reason"),
                uuid(res, "approvedBy"),
                textOrNull(res, "bankPayoutRef"),
                text(res, "status"),
                Instant.parse(text(res, "createdAt"))
        );
    }

    public BillingDocumentRecord issueBillingDocument(UUID bookingId, String documentType, String notes) {
        JsonObject req = new JsonObject();
        req.addProperty("bookingId", bookingId.toString());
        if (documentType != null) req.addProperty("documentType", documentType);
        if (notes != null && !notes.isBlank()) req.addProperty("notes", notes.strip());

        JsonObject res = sendPost("/api/v1/finance/documents", req);
        return parseBillingDocument(res);
    }

    public List<BillingDocumentRecord> listBillingDocuments(UUID bookingId) {
        String path = "/api/v1/finance/documents?bookingId=" + URLEncoder.encode(bookingId.toString(), StandardCharsets.UTF_8);
        JsonObject res = sendGet(path);
        List<BillingDocumentRecord> list = new ArrayList<>();
        if (res.has("documents") && res.get("documents").isJsonArray()) {
            for (var el : res.getAsJsonArray("documents")) {
                list.add(parseBillingDocument(el.getAsJsonObject()));
            }
        }
        return list;
    }

    public byte[] downloadDocumentPdf(UUID documentId) {
        String path = "/api/v1/finance/documents/pdf?documentId=" + URLEncoder.encode(documentId.toString(), StandardCharsets.UTF_8);
        var builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/pdf")
                .header("Authorization", "Bearer " + session.accessToken())
                .GET();
        var request = builder.build();
        try {
            var response = java.net.http.HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 401) throw new SecurityException("Session expired. Sign in again.");
            if (response.statusCode() == 403) throw new SecurityException("Your current account does not have permission for this action.");
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Failed to download PDF (" + response.statusCode() + ").");
            }
            return response.body();
        } catch (Exception e) {
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new IllegalStateException(e.getMessage(), e);
        }
    }

    public CustomerStatement getCustomerStatement(UUID customerId) {
        String path = "/api/v1/finance/statements?customerId=" + URLEncoder.encode(customerId.toString(), StandardCharsets.UTF_8);
        JsonObject res = sendGet(path);

        List<PaymentRecord> payments = new ArrayList<>();
        if (res.has("payments") && res.get("payments").isJsonArray()) {
            for (var el : res.getAsJsonArray("payments")) payments.add(parsePayment(el.getAsJsonObject()));
        }

        List<BillingDocumentRecord> documents = new ArrayList<>();
        if (res.has("documents") && res.get("documents").isJsonArray()) {
            for (var el : res.getAsJsonArray("documents")) documents.add(parseBillingDocument(el.getAsJsonObject()));
        }

        return new CustomerStatement(
                uuid(res, "customerId"),
                text(res, "customerName"),
                res.get("totalBilledPaise").getAsLong(),
                res.get("totalPaidPaise").getAsLong(),
                res.get("balanceDuePaise").getAsLong(),
                res.get("depositHeldPaise").getAsLong(),
                payments,
                documents
        );
    }

    public LedgerSummary getLedgerSummary(Instant from, Instant to) {
        String path = "/api/v1/finance/ledger";
        if (from != null && to != null) {
            path += "?from=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
                    + "&to=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8);
        }
        JsonObject res = sendGet(path);
        String fromStr = res.has("fromTime") && !res.get("fromTime").getAsString().isBlank() ? res.get("fromTime").getAsString() : text(res, "fromPeriod");
        String toStr = res.has("toTime") && !res.get("toTime").getAsString().isBlank() ? res.get("toTime").getAsString() : text(res, "toPeriod");
        Instant fromInst = !fromStr.isBlank() ? Instant.parse(fromStr) : Instant.now().minusSeconds(86400 * 30);
        Instant toInst = !toStr.isBlank() ? Instant.parse(toStr) : Instant.now();
        return new LedgerSummary(
                fromInst,
                toInst,
                res.get("totalRevenueEarnedPaise").getAsLong(),
                res.get("totalCashCollectedPaise").getAsLong(),
                res.get("totalReceivablesPaise").getAsLong(),
                res.get("totalDepositLiabilityPaise").getAsLong(),
                res.get("totalExpensesPaise").getAsLong(),
                res.get("operatingMarginPaise").getAsLong()
        );
    }

    private static PaymentRequestRecord parsePaymentRequest(JsonObject o) {
        return new PaymentRequestRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "bookingId"),
                uuid(o, "customerId"),
                o.get("amountPaise").getAsLong(),
                text(o, "currency"),
                text(o, "paymentType"),
                Instant.parse(text(o, "dueAt")),
                text(o, "status"),
                text(o, "merchantVpa"),
                text(o, "merchantName"),
                text(o, "qrPayload"),
                textOrNull(o, "notes"),
                uuid(o, "createdBy"),
                Instant.parse(text(o, "createdAt"))
        );
    }

    private static PaymentRecord parsePayment(JsonObject o) {
        return new PaymentRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "bookingId"),
                uuid(o, "customerId"),
                uuid(o, "paymentRequestId"),
                o.get("amountPaise").getAsLong(),
                text(o, "currency"),
                text(o, "paymentMethod"),
                textOrNull(o, "externalReference"),
                text(o, "status"),
                text(o, "verificationEvidence"),
                uuid(o, "verifiedBy"),
                Instant.parse(text(o, "verifiedAt")),
                textOrNull(o, "notes"),
                Instant.parse(text(o, "createdAt"))
        );
    }

    private static RefundableDepositRecord parseDeposit(JsonObject o) {
        return new RefundableDepositRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "bookingId"),
                uuid(o, "customerId"),
                o.get("depositReceivedPaise").getAsLong(),
                o.get("depositRefundedPaise").getAsLong(),
                o.get("depositAppliedPaise").getAsLong(),
                text(o, "status"),
                Instant.parse(text(o, "heldAt")),
                o.has("refundedAt") && !o.get("refundedAt").isJsonNull() ? Instant.parse(o.get("refundedAt").getAsString()) : null,
                textOrNull(o, "refundRef"),
                uuid(o, "refundApprovedBy"),
                textOrNull(o, "notes")
        );
    }

    private static BillingDocumentRecord parseBillingDocument(JsonObject o) {
        return new BillingDocumentRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "bookingId"),
                uuid(o, "customerId"),
                text(o, "documentType"),
                text(o, "documentNumber"),
                o.get("documentVersion").getAsInt(),
                text(o, "status"),
                o.get("totalPaise").getAsLong(),
                o.get("advancePaidPaise").getAsLong(),
                o.get("balanceDuePaise").getAsLong(),
                o.get("depositHeldPaise").getAsLong(),
                o.get("taxPaise").getAsLong(),
                o.get("isBetaProforma").getAsBoolean(),
                text(o, "snapshotJson"),
                uuid(o, "fileMetadataId"),
                uuid(o, "issuedBy"),
                Instant.parse(text(o, "issuedAt")),
                Instant.parse(text(o, "createdAt"))
        );
    }

    private JsonObject sendGet(String path) {
        return send(path, null);
    }

    private JsonObject sendPost(String path, JsonObject payload) {
        return send(path, payload);
    }

    private JsonObject send(String path, JsonObject payload) {
        var builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + session.accessToken());
        var request = payload == null ? builder.GET().build() : builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)).build();
        var response = http.send(request);
        if (response.status() == 401) throw new SecurityException("Session expired. Sign in again.");
        if (response.status() == 403) throw new SecurityException("Your current account does not have permission for this action.");
        if (response.status() == 409) throw new IllegalStateException("Conflict: Duplicate entry or concurrent modification (409 Conflict).");
        if (response.status() != 200 && response.status() != 201) {
            String msg = "The request did not complete (" + response.status() + ").";
            try {
                JsonObject err = DesktopJson.object(response.body());
                if (err.has("error")) msg = err.get("error").getAsString();
            } catch (Exception ignored) {}
            throw new IllegalStateException(msg);
        }
        return DesktopJson.object(response.body());
    }

    private static UUID uuid(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? UUID.fromString(o.get(key).getAsString()) : null;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String textOrNull(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
