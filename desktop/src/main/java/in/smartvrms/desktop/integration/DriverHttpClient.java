package in.smartvrms.desktop.integration;

import com.google.gson.*;
import in.smartvrms.contracts.DriverProfileRecord;
import in.smartvrms.desktop.infrastructure.DesktopConfig;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** Authenticated driver operational master API client. */
public final class DriverHttpClient {
    private final URI endpoint;
    private final DesktopSession session;
    private final DesktopHttp http = new DesktopHttp();

    public DriverHttpClient(DesktopConfig config, DesktopSession session) {
        this.endpoint = config.apiEndpoint().orElseThrow();
        this.session = Objects.requireNonNull(session, "session");
    }

    public List<DriverProfileRecord> listDrivers(String dutyStatus) {
        String query = (dutyStatus != null && !dutyStatus.isBlank()) ? "?dutyStatus=" + dutyStatus : "";
        JsonObject root = send("/api/v1/drivers" + query, null);
        List<DriverProfileRecord> rows = new ArrayList<>();
        if (root.has("drivers") && root.get("drivers").isJsonArray()) {
            for (var elem : root.getAsJsonArray("drivers")) {
                rows.add(parseDriver(elem.getAsJsonObject()));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    public DriverProfileRecord registerDriver(UUID employeeId, String licenceNumber, List<String> classes, LocalDate expiry, int experienceYears) {
        JsonObject json = new JsonObject();
        json.addProperty("employeeId", employeeId.toString());
        json.addProperty("licenceNumber", licenceNumber);
        JsonArray cls = new JsonArray();
        for (String c : classes) cls.add(c);
        json.add("classes", cls);
        json.addProperty("expiry", expiry.toString());
        json.addProperty("experienceYears", experienceYears);
        JsonObject res = send("/api/v1/drivers", json);
        return parseDriver(res);
    }

    public void updateDutyStatus(UUID driverId, String dutyStatus) {
        JsonObject json = new JsonObject();
        json.addProperty("driverId", driverId.toString());
        json.addProperty("dutyStatus", dutyStatus);
        send("/api/v1/drivers/status", json);
    }

    private DriverProfileRecord parseDriver(JsonObject o) {
        List<String> classes = new ArrayList<>();
        if (o.has("licenceClasses") && o.get("licenceClasses").isJsonArray()) {
            for (var c : o.getAsJsonArray("licenceClasses")) classes.add(c.getAsString());
        }
        return new DriverProfileRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "employeeId"),
                text(o, "fullName"),
                text(o, "contactNumber"),
                text(o, "licenceNumber"),
                classes,
                LocalDate.parse(text(o, "licenceExpiry")),
                o.get("experienceYears").getAsInt(),
                text(o, "dutyStatus"),
                o.get("isEmployed").getAsBoolean()
        );
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
        if (response.status() != 200 && response.status() != 201) {
            String msg = "The request did not complete (" + response.status() + ").";
            try {
                JsonObject err = DesktopJson.object(response.body());
                if (err.has("error")) msg = err.get("error").getAsString();
            } catch (Exception ignored) {}
            throw new IllegalStateException(msg);
        }
        if (!response.contentType().split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw new IllegalStateException("Invalid API response format.");
        }
        return DesktopJson.object(response.body());
    }

    private static String text(JsonObject json, String name) {
        return DesktopJson.string(json, name);
    }

    private static UUID uuid(JsonObject json, String name) {
        return UUID.fromString(text(json, name));
    }
}
