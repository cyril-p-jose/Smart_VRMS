package in.smartvrms.desktop.integration;

import com.google.gson.JsonObject;
import in.smartvrms.contracts.FinancialReportSummary;
import in.smartvrms.contracts.OperationalReportSummary;
import in.smartvrms.contracts.ReportingPeriodFilter;
import in.smartvrms.desktop.infrastructure.DesktopConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Authenticated Desktop Client for Periodic Operational and Financial Reports
 * (FR-FIN-003, FR-ACC-003, FR-FIN-005).
 */
public final class ReportingHttpClient {
    private final URI endpoint;
    private final DesktopSession session;
    private final DesktopHttp http = new DesktopHttp();

    public ReportingHttpClient(DesktopConfig config, DesktopSession session) {
        this.endpoint = config.apiEndpoint().orElseThrow();
        this.session = Objects.requireNonNull(session, "session");
    }

    public OperationalReportSummary getOperationalReport(ReportingPeriodFilter filter) {
        String path = "/api/v1/reports/operational?" + buildQuery(filter);
        JsonObject res = sendGet(path);
        return new OperationalReportSummary(
                Instant.parse(res.get("fromTime").getAsString()),
                Instant.parse(res.get("toTime").getAsString()),
                res.get("totalBookingsCount").getAsInt(),
                res.get("totalAssignmentsCount").getAsInt(),
                res.get("activeRentalsCount").getAsInt(),
                res.get("completedRentalsCount").getAsInt(),
                res.get("overdueRentalsCount").getAsInt(),
                res.get("maintenanceCasesCount").getAsInt(),
                res.get("reviewsCount").getAsInt(),
                res.get("averageReviewRating").getAsDouble()
        );
    }

    public FinancialReportSummary getFinancialReport(ReportingPeriodFilter filter) {
        String path = "/api/v1/reports/financial?" + buildQuery(filter);
        JsonObject res = sendGet(path);
        return new FinancialReportSummary(
                Instant.parse(res.get("fromTime").getAsString()),
                Instant.parse(res.get("toTime").getAsString()),
                res.get("periodLabel").getAsString(),
                res.get("totalRevenueBilledPaise").getAsLong(),
                res.get("totalCashCollectedPaise").getAsLong(),
                res.get("outstandingReceivablesPaise").getAsLong(),
                res.get("refundableDepositLiabilityPaise").getAsLong(),
                res.get("maintenanceExpensesPaise").getAsLong(),
                res.get("compensationExpensesPaise").getAsLong(),
                res.get("totalOperatingExpensesPaise").getAsLong(),
                res.get("netOperatingAccrualMarginPaise").getAsLong()
        );
    }

    public String exportCsv(ReportingPeriodFilter filter) {
        String path = "/api/v1/reports/export?" + buildQuery(filter);
        var builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/csv")
                .header("Authorization", "Bearer " + session.accessToken());
        var response = http.send(builder.GET().build());
        if (response.status() == 401) throw new SecurityException("Session expired. Sign in again.");
        if (response.status() == 403) throw new SecurityException("Your current account does not have permission for this report export.");
        if (response.status() != 200) throw new IllegalStateException("Failed to export report CSV (" + response.status() + ").");
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static String buildQuery(ReportingPeriodFilter filter) {
        StringBuilder q = new StringBuilder();
        if (filter.fromTime() != null) q.append("from=").append(URLEncoder.encode(filter.fromTime().toString(), StandardCharsets.UTF_8)).append("&");
        if (filter.toTime() != null) q.append("to=").append(URLEncoder.encode(filter.toTime().toString(), StandardCharsets.UTF_8)).append("&");
        if (filter.granularity() != null) q.append("granularity=").append(URLEncoder.encode(filter.granularity(), StandardCharsets.UTF_8)).append("&");
        if (filter.vehicleId() != null) q.append("vehicleId=").append(URLEncoder.encode(filter.vehicleId().toString(), StandardCharsets.UTF_8)).append("&");
        if (filter.customerId() != null) q.append("customerId=").append(URLEncoder.encode(filter.customerId().toString(), StandardCharsets.UTF_8));
        return q.toString();
    }

    private JsonObject sendGet(String path) {
        var builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + session.accessToken());
        var response = http.send(builder.GET().build());
        if (response.status() == 401) throw new SecurityException("Session expired. Sign in again.");
        if (response.status() == 403) throw new SecurityException("Your current account does not have permission for this report (FR-ACC-003, FR-FIN-005).");
        if (response.status() != 200) {
            String msg = "The report request failed (" + response.status() + ").";
            try {
                JsonObject err = DesktopJson.object(response.body());
                if (err.has("error")) msg = err.get("error").getAsString();
            } catch (Exception ignored) {}
            throw new IllegalStateException(msg);
        }
        return DesktopJson.object(response.body());
    }
}
