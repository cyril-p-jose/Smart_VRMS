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

/** Authenticated booking operational API client (FR-BOOK-001..011). */
public final class BookingHttpClient {
    private final URI endpoint;
    private final DesktopSession session;
    private final DesktopHttp http = new DesktopHttp();

    public BookingHttpClient(DesktopConfig config, DesktopSession session) {
        this.endpoint = config.apiEndpoint().orElseThrow();
        this.session = Objects.requireNonNull(session, "session");
    }

    public BookingQuote calculateQuote(Instant startTime, Instant endTime, List<BookingConfirmationCommand.PackageItem> items) {
        JsonObject req = new JsonObject();
        req.addProperty("startTime", startTime.toString());
        req.addProperty("endTime", endTime.toString());
        JsonArray arr = new JsonArray();
        for (var it : items) {
            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("vehicleId", it.vehicleId().toString());
            itemObj.addProperty("driverRequired", it.driverRequired());
            if (it.driverId() != null) itemObj.addProperty("driverId", it.driverId().toString());
            arr.add(itemObj);
        }
        req.add("items", arr);
        JsonObject res = sendPost("/api/v1/bookings/quote", req);
        return parseQuote(res);
    }

    public boolean isVehicleAvailable(UUID vehicleId, Instant startTime, Instant endTime) {
        String path = "/api/v1/bookings/availability?vehicleId=" + vehicleId
                + "&startTime=" + URLEncoder.encode(startTime.toString(), StandardCharsets.UTF_8)
                + "&endTime=" + URLEncoder.encode(endTime.toString(), StandardCharsets.UTF_8);
        JsonObject res = sendGet(path);
        return res.has("available") && res.get("available").getAsBoolean();
    }

    public boolean isDriverAvailable(UUID driverId, Instant startTime, Instant endTime) {
        String path = "/api/v1/bookings/driver-availability?driverId=" + driverId
                + "&startTime=" + URLEncoder.encode(startTime.toString(), StandardCharsets.UTF_8)
                + "&endTime=" + URLEncoder.encode(endTime.toString(), StandardCharsets.UTF_8);
        JsonObject res = sendGet(path);
        return res.has("available") && res.get("available").getAsBoolean();
    }

    public BookingRecord confirmBooking(BookingConfirmationCommand cmd) {
        JsonObject req = new JsonObject();
        req.addProperty("idempotencyKey", cmd.idempotencyKey());
        if (cmd.existingCustomerId() != null) req.addProperty("existingCustomerId", cmd.existingCustomerId().toString());
        if (cmd.customerFullName() != null) req.addProperty("customerFullName", cmd.customerFullName());
        if (cmd.customerContact() != null) req.addProperty("customerContact", cmd.customerContact());
        if (cmd.customerEmail() != null) req.addProperty("customerEmail", cmd.customerEmail());
        if (cmd.customerAddress() != null) req.addProperty("customerAddress", cmd.customerAddress());
        req.addProperty("ageAtVisit", cmd.ageAtVisit());
        req.addProperty("visitReason", cmd.visitReason());
        req.addProperty("startTime", cmd.startTime().toString());
        req.addProperty("endTime", cmd.endTime().toString());
        req.addProperty("agreedAdvancePaise", cmd.agreedAdvancePaise());
        req.addProperty("depositPaise", cmd.depositPaise());

        JsonArray arr = new JsonArray();
        for (var it : cmd.items()) {
            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("vehicleId", it.vehicleId().toString());
            itemObj.addProperty("driverRequired", it.driverRequired());
            if (it.driverId() != null) itemObj.addProperty("driverId", it.driverId().toString());
            arr.add(itemObj);
        }
        req.add("items", arr);

        JsonObject res = sendPost("/api/v1/bookings/confirm", req);
        return parseBooking(res);
    }

    public List<BookingRecord> listBookings(String status) {
        String q = (status != null && !status.isBlank()) ? "?status=" + URLEncoder.encode(status, StandardCharsets.UTF_8) : "";
        JsonObject root = sendGet("/api/v1/bookings" + q);
        List<BookingRecord> list = new ArrayList<>();
        if (root.has("bookings") && root.get("bookings").isJsonArray()) {
            for (var elem : root.getAsJsonArray("bookings")) {
                list.add(parseBooking(elem.getAsJsonObject()));
            }
        }
        return Collections.unmodifiableList(list);
    }

    private BookingQuote parseQuote(JsonObject o) {
        List<AssignmentQuote> lines = new ArrayList<>();
        if (o.has("assignments") && o.get("assignments").isJsonArray()) {
            for (var elem : o.getAsJsonArray("assignments")) {
                JsonObject a = elem.getAsJsonObject();
                lines.add(new AssignmentQuote(
                        uuid(a, "vehicleId"),
                        text(a, "vehicleName"),
                        text(a, "category"),
                        text(a, "registrationPlate"),
                        uuid(a, "driverId"),
                        text(a, "driverName"),
                        a.has("driverRequired") && a.get("driverRequired").getAsBoolean(),
                        a.get("dailyRatePaise").getAsLong(),
                        a.get("hourlyRatePaise").getAsLong(),
                        a.get("vehicleTotalPaise").getAsLong(),
                        a.get("driverTotalPaise").getAsLong(),
                        a.get("lineTotalPaise").getAsLong()
                ));
            }
        }
        return new BookingQuote(
                Instant.parse(text(o, "startTime")),
                Instant.parse(text(o, "endTime")),
                o.get("durationHours").getAsLong(),
                o.get("billableDays").getAsLong(),
                o.get("billableRemainderHours").getAsLong(),
                lines,
                o.get("baseTotalPaise").getAsLong(),
                o.get("driverTotalPaise").getAsLong(),
                o.get("taxPaise").getAsLong(),
                o.get("grandTotalPaise").getAsLong(),
                o.get("suggestedAdvancePaise").getAsLong(),
                o.get("depositPaise").getAsLong()
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
        if (response.status() == 409) throw new IllegalStateException("Vehicle or driver is no longer available for the requested period (409 Conflict).");
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
}
