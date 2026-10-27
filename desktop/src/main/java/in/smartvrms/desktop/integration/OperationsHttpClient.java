package in.smartvrms.desktop.integration;

import com.google.gson.*;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.infrastructure.DesktopConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Authenticated Operations and Running Services API Client (FR-OPS-001..004, FR-EXT-001..003, FR-DMG-001..004).
 * Manages running rentals, custody checkout/return, saved-rate extensions, and maintenance restrictions.
 */
public final class OperationsHttpClient {
    private final URI endpoint;
    private final DesktopSession session;
    private final DesktopHttp http = new DesktopHttp();

    public OperationsHttpClient(DesktopConfig config, DesktopSession session) {
        this.endpoint = config.apiEndpoint().orElseThrow();
        this.session = Objects.requireNonNull(session, "session");
    }

    public CustodyRecord recordCheckout(UUID assignmentId, Instant actualTime, int odometer, int fuelPercent, String notes) {
        JsonObject req = new JsonObject();
        req.addProperty("assignmentId", assignmentId.toString());
        req.addProperty("actualTime", (actualTime != null ? actualTime : Instant.now()).toString());
        req.addProperty("odometer", odometer);
        req.addProperty("fuelPercent", fuelPercent);
        if (notes != null && !notes.isBlank()) req.addProperty("notes", notes.strip());

        JsonObject res = sendPost("/api/v1/operations/checkout", req);
        return parseCustody(res);
    }

    public CustodyRecord recordReturnInspection(
            UUID assignmentId,
            Instant actualTime,
            int odometer,
            int fuelPercent,
            String condition,
            String damageNotes,
            UUID photoFileId,
            String issueType,
            long estimatedCostPaise,
            long allegedRecoveryPaise) {
        JsonObject req = new JsonObject();
        req.addProperty("assignmentId", assignmentId.toString());
        req.addProperty("actualTime", (actualTime != null ? actualTime : Instant.now()).toString());
        req.addProperty("odometer", odometer);
        req.addProperty("fuelPercent", fuelPercent);
        req.addProperty("condition", condition != null ? condition : "ACCEPTABLE");
        if (damageNotes != null && !damageNotes.isBlank()) req.addProperty("damageNotes", damageNotes.strip());
        if (photoFileId != null) req.addProperty("damagePhotoFileId", photoFileId.toString());
        if (issueType != null && !issueType.isBlank()) req.addProperty("issueType", issueType.strip());
        req.addProperty("estimatedRepairCostPaise", estimatedCostPaise);
        req.addProperty("allegedCustomerRecoveryPaise", allegedRecoveryPaise);

        JsonObject res = sendPost("/api/v1/operations/return", req);
        return parseCustody(res);
    }

    public ExtensionQuote quoteExtension(UUID bookingId, List<UUID> assignmentIds, Instant revisedEndTime, String reason) {
        JsonObject req = new JsonObject();
        req.addProperty("bookingId", bookingId.toString());
        req.addProperty("revisedEndTime", revisedEndTime.toString());
        if (reason != null && !reason.isBlank()) req.addProperty("reason", reason.strip());
        if (assignmentIds != null && !assignmentIds.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (UUID id : assignmentIds) arr.add(id.toString());
            req.add("assignmentIds", arr);
        }

        JsonObject res = sendPost("/api/v1/operations/extensions/quote", req);
        return parseExtensionQuote(res);
    }

    public BookingRecord extendAssignments(UUID bookingId, List<UUID> assignmentIds, Instant revisedEndTime, String reason) {
        JsonObject req = new JsonObject();
        req.addProperty("bookingId", bookingId.toString());
        req.addProperty("revisedEndTime", revisedEndTime.toString());
        req.addProperty("reason", reason != null && !reason.isBlank() ? reason.strip() : "Customer rental extension");
        if (assignmentIds != null && !assignmentIds.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (UUID id : assignmentIds) arr.add(id.toString());
            req.add("assignmentIds", arr);
        }

        JsonObject res = sendPost("/api/v1/operations/extensions/confirm", req);
        return parseBooking(res);
    }

    public List<RunningServiceSummary> listRunningServices(boolean overdueOnly) {
        String path = "/api/v1/operations/running-services" + (overdueOnly ? "?overdueOnly=true" : "");
        JsonObject root = sendGet(path);
        List<RunningServiceSummary> list = new ArrayList<>();
        if (root.has("runningServices") && root.get("runningServices").isJsonArray()) {
            for (var elem : root.getAsJsonArray("runningServices")) {
                list.add(parseRunningService(elem.getAsJsonObject()));
            }
        }
        return Collections.unmodifiableList(list);
    }

    public RunningServiceSummary getRunningServiceDetail(UUID bookingId) {
        String path = "/api/v1/operations/running-services/detail?bookingId=" + bookingId.toString();
        JsonObject root = sendGet(path);
        return parseRunningService(root);
    }

    public MaintenanceCaseRecord openMaintenanceCase(
            UUID vehicleId,
            UUID assignmentId,
            UUID custodyId,
            String issueType,
            long estimatedCostPaise,
            long allegedRecoveryPaise,
            String notes,
            UUID photoFileId) {
        JsonObject req = new JsonObject();
        req.addProperty("vehicleId", vehicleId.toString());
        if (assignmentId != null) req.addProperty("assignmentId", assignmentId.toString());
        if (custodyId != null) req.addProperty("custodyId", custodyId.toString());
        if (issueType != null) req.addProperty("issueType", issueType);
        req.addProperty("estimatedCostPaise", estimatedCostPaise);
        req.addProperty("allegedRecoveryPaise", allegedRecoveryPaise);
        req.addProperty("notes", notes != null ? notes.strip() : "");
        if (photoFileId != null) req.addProperty("photoFileId", photoFileId.toString());

        JsonObject res = sendPost("/api/v1/operations/maintenance/open", req);
        return parseMaintenanceCase(res);
    }

    public MaintenanceCaseRecord resolveMaintenanceCase(UUID caseId, long actualCostPaise, long approvedCustomerRecoveryPaise, String resolutionNotes) {
        JsonObject req = new JsonObject();
        req.addProperty("caseId", caseId.toString());
        req.addProperty("actualCostPaise", actualCostPaise);
        req.addProperty("approvedCustomerRecoveryPaise", approvedCustomerRecoveryPaise);
        if (resolutionNotes != null && !resolutionNotes.isBlank()) req.addProperty("resolutionNotes", resolutionNotes.strip());

        JsonObject res = sendPost("/api/v1/operations/maintenance/resolve", req);
        return parseMaintenanceCase(res);
    }

    public List<MaintenanceCaseRecord> listMaintenanceCases(UUID vehicleId, boolean openOnly) {
        StringBuilder q = new StringBuilder("/api/v1/operations/maintenance/cases?");
        if (vehicleId != null) q.append("vehicleId=").append(vehicleId).append("&");
        if (openOnly) q.append("openOnly=true&");
        JsonObject root = sendGet(q.toString());
        List<MaintenanceCaseRecord> list = new ArrayList<>();
        if (root.has("cases") && root.get("cases").isJsonArray()) {
            for (var elem : root.getAsJsonArray("cases")) {
                list.add(parseMaintenanceCase(elem.getAsJsonObject()));
            }
        }
        return Collections.unmodifiableList(list);
    }

    private CustodyRecord parseCustody(JsonObject o) {
        return new CustodyRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "assignmentId"),
                uuid(o, "vehicleId"),
                Instant.parse(text(o, "checkoutTime")),
                o.get("checkoutOdometer").getAsInt(),
                o.get("checkoutFuelPercent").getAsInt(),
                textOrNull(o, "checkoutNotes"),
                o.has("returnTime") && !o.get("returnTime").isJsonNull() ? Instant.parse(text(o, "returnTime")) : null,
                o.has("returnOdometer") && !o.get("returnOdometer").isJsonNull() ? o.get("returnOdometer").getAsInt() : null,
                o.has("returnFuelPercent") && !o.get("returnFuelPercent").isJsonNull() ? o.get("returnFuelPercent").getAsInt() : null,
                text(o, "inspectionStatus"),
                textOrNull(o, "damageNotes"),
                uuid(o, "damagePhotoFileId"),
                uuid(o, "checkoutBy"),
                uuid(o, "returnedBy"),
                o.has("createdAt") && !o.get("createdAt").isJsonNull() ? Instant.parse(text(o, "createdAt")) : null
        );
    }

    private ExtensionQuote parseExtensionQuote(JsonObject o) {
        List<ExtensionQuote.AssignmentDelta> deltas = new ArrayList<>();
        if (o.has("assignmentDeltas") && o.get("assignmentDeltas").isJsonArray()) {
            for (var elem : o.getAsJsonArray("assignmentDeltas")) {
                JsonObject d = elem.getAsJsonObject();
                deltas.add(new ExtensionQuote.AssignmentDelta(
                        uuid(d, "assignmentId"),
                        uuid(d, "vehicleId"),
                        Instant.parse(text(d, "previousEndTime")),
                        Instant.parse(text(d, "revisedEndTime")),
                        d.get("incrementalVehiclePaise").getAsLong(),
                        d.get("incrementalDriverPaise").getAsLong(),
                        d.get("incrementalTotalPaise").getAsLong()
                ));
            }
        }
        return new ExtensionQuote(
                uuid(o, "bookingId"),
                deltas,
                o.get("totalDeltaPaise").getAsLong(),
                Instant.parse(text(o, "revisedEndTime"))
        );
    }

    private RunningServiceSummary parseRunningService(JsonObject o) {
        List<String> plates = new ArrayList<>();
        if (o.has("vehiclePlates") && o.get("vehiclePlates").isJsonArray()) {
            for (var elem : o.getAsJsonArray("vehiclePlates")) plates.add(elem.getAsString());
        }

        List<String> drivers = new ArrayList<>();
        if (o.has("driverNames") && o.get("driverNames").isJsonArray()) {
            for (var elem : o.getAsJsonArray("driverNames")) drivers.add(elem.getAsString());
        }

        List<RunningAssignmentSummary> assigns = new ArrayList<>();
        if (o.has("assignments") && o.get("assignments").isJsonArray()) {
            for (var elem : o.getAsJsonArray("assignments")) {
                JsonObject a = elem.getAsJsonObject();
                assigns.add(new RunningAssignmentSummary(
                        uuid(a, "assignmentId"),
                        uuid(a, "vehicleId"),
                        text(a, "registrationPlate"),
                        text(a, "categoryName"),
                        uuid(a, "driverId"),
                        textOrNull(a, "driverName"),
                        Instant.parse(text(a, "startTime")),
                        Instant.parse(text(a, "endTime")),
                        text(a, "assignmentStatus"),
                        text(a, "custodyStatus"),
                        textOrNull(a, "inspectionStatus")
                ));
            }
        }

        return new RunningServiceSummary(
                uuid(o, "bookingId"),
                text(o, "bookingNumber"),
                uuid(o, "customerId"),
                text(o, "customerName"),
                text(o, "customerContact"),
                uuid(o, "visitId"),
                text(o, "bookingStatus"),
                Instant.parse(text(o, "startTime")),
                Instant.parse(text(o, "endTime")),
                o.has("isOverdue") && o.get("isOverdue").getAsBoolean(),
                o.get("vehicleCount").getAsInt(),
                plates,
                drivers,
                o.get("totalPaise").getAsLong(),
                o.get("advanceReceivedPaise").getAsLong(),
                o.get("balancePaise").getAsLong(),
                assigns
        );
    }

    private MaintenanceCaseRecord parseMaintenanceCase(JsonObject o) {
        return new MaintenanceCaseRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "vehicleId"),
                uuid(o, "assignmentId"),
                uuid(o, "custodyId"),
                uuid(o, "restrictionId"),
                text(o, "issueType"),
                text(o, "status"),
                o.get("estimatedCostPaise").getAsLong(),
                o.get("allegedCustomerRecoveryPaise").getAsLong(),
                o.has("actualCostPaise") ? o.get("actualCostPaise").getAsLong() : 0L,
                o.has("approvedCustomerRecoveryPaise") ? o.get("approvedCustomerRecoveryPaise").getAsLong() : 0L,
                text(o, "notes"),
                uuid(o, "damagePhotoFileId"),
                textOrNull(o, "resolutionNotes"),
                uuid(o, "reportedBy"),
                uuid(o, "resolvedBy"),
                Instant.parse(text(o, "createdAt")),
                o.has("resolvedAt") && !o.get("resolvedAt").isJsonNull() ? Instant.parse(text(o, "resolvedAt")) : null
        );
    }

    private BookingRecord parseBooking(JsonObject o) {
        List<BookingAssignmentRecord> assigns = new ArrayList<>();
        if (o.has("assignments") && o.get("assignments").isJsonArray()) {
            for (var elem : o.getAsJsonArray("assignments")) {
                JsonObject a = elem.getAsJsonObject();
                assigns.add(new BookingAssignmentRecord(
                        uuid(a, "id"),
                        uuid(a, "companyId") != null ? uuid(a, "companyId") : uuid(o, "companyId"),
                        uuid(a, "bookingId") != null ? uuid(a, "bookingId") : uuid(o, "id"),
                        uuid(a, "vehicleId"),
                        uuid(a, "driverId"),
                        a.has("driverRequired") && a.get("driverRequired").getAsBoolean(),
                        a.has("startTime") ? Instant.parse(text(a, "startTime")) : Instant.parse(text(o, "startTime")),
                        a.has("endTime") ? Instant.parse(text(a, "endTime")) : Instant.parse(text(o, "endTime")),
                        a.get("dailyRatePaise").getAsLong(),
                        a.get("hourlyRatePaise").getAsLong(),
                        a.get("vehicleTotalPaise").getAsLong(),
                        a.get("driverTotalPaise").getAsLong(),
                        a.get("assignmentTotalPaise").getAsLong(),
                        text(a, "status")
                ));
            }
        }
        return new BookingRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "customerId"),
                uuid(o, "visitId"),
                text(o, "bookingNumber"),
                text(o, "status"),
                Instant.parse(text(o, "startTime")),
                Instant.parse(text(o, "endTime")),
                o.get("baseTotalPaise").getAsLong(),
                o.get("driverTotalPaise").getAsLong(),
                o.get("taxPaise").getAsLong(),
                o.get("totalPaise").getAsLong(),
                o.get("agreedAdvancePaise").getAsLong(),
                o.has("actualAdvanceReceivedPaise") ? o.get("actualAdvanceReceivedPaise").getAsLong() : 0L,
                o.get("depositPaise").getAsLong(),
                o.has("pricingSnapshot") ? o.get("pricingSnapshot").toString() : "{}",
                assigns,
                uuid(o, "createdBy"),
                Instant.parse(text(o, "createdAt")),
                o.get("version").getAsLong()
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
        if (response.status() == 409) throw new IllegalStateException("Conflict: The vehicle, driver, or custody record has a conflicting update (409 Conflict).");
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
