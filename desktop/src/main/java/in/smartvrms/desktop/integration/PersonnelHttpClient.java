package in.smartvrms.desktop.integration;

import com.google.gson.*;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.infrastructure.DesktopConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** Authenticated personnel and member management API client. */
public final class PersonnelHttpClient {
    private final URI endpoint;
    private final DesktopSession session;
    private final DesktopHttp http = new DesktopHttp();

    public PersonnelHttpClient(DesktopConfig config, DesktopSession session) {
        this.endpoint = config.apiEndpoint().orElseThrow();
        this.session = Objects.requireNonNull(session, "session");
    }

    public List<EmployeeRecord> listEmployees(boolean includeDeactivated) {
        String query = includeDeactivated ? "?includeDeactivated=true" : "";
        JsonObject root = send("/api/v1/personnel/employees" + query, null);
        List<EmployeeRecord> rows = new ArrayList<>();
        if (root.has("employees") && root.get("employees").isJsonArray()) {
            for (var elem : root.getAsJsonArray("employees")) {
                rows.add(parseEmployee(elem.getAsJsonObject()));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    public EmployeeRecord registerEmployee(String fullName, String contact, String email, String address, LocalDate joiningDate, boolean isDriver, UUID photoFileId) {
        JsonObject json = new JsonObject();
        json.addProperty("fullName", fullName);
        json.addProperty("contact", contact);
        if (email != null && !email.isBlank()) json.addProperty("email", email.strip());
        json.addProperty("address", address);
        if (joiningDate != null) json.addProperty("joiningDate", joiningDate.toString());
        json.addProperty("isDriver", isDriver);
        if (photoFileId != null) json.addProperty("photoFileId", photoFileId.toString());
        JsonObject res = send("/api/v1/personnel/employees", json);
        return parseEmployee(res);
    }

    public EmployeeRecord deactivateEmployee(UUID employeeId, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("employeeId", employeeId.toString());
        json.addProperty("reason", reason);
        JsonObject res = send("/api/v1/personnel/employees/deactivate", json);
        return parseEmployee(res);
    }

    public Optional<EmployeeRecord> checkDuplicate(String contact, String email) {
        StringBuilder q = new StringBuilder("?");
        if (contact != null && !contact.isBlank()) {
            q.append("contact=").append(URLEncoder.encode(contact.strip(), StandardCharsets.UTF_8)).append("&");
        }
        if (email != null && !email.isBlank()) {
            q.append("email=").append(URLEncoder.encode(email.strip(), StandardCharsets.UTF_8));
        }
        JsonObject res = send("/api/v1/personnel/duplicates" + q, null);
        if (res.has("duplicateFound") && res.get("duplicateFound").getAsBoolean() && res.has("employee")) {
            return Optional.of(parseEmployee(res.getAsJsonObject("employee")));
        }
        return Optional.empty();
    }

    public AttendanceRecord recordAttendance(UUID employeeId, LocalDate date, String status, String notes) {
        JsonObject json = new JsonObject();
        json.addProperty("employeeId", employeeId.toString());
        json.addProperty("date", date.toString());
        json.addProperty("status", status);
        if (notes != null) json.addProperty("notes", notes);
        JsonObject res = send("/api/v1/personnel/attendance", json);
        return new AttendanceRecord(
                uuid(res, "id"),
                uuid(res, "companyId"),
                uuid(res, "employeeId"),
                LocalDate.parse(text(res, "recordDate")),
                text(res, "status"),
                res.has("notes") && !res.get("notes").isJsonNull() ? res.get("notes").getAsString() : null,
                res.has("recordedBy") && !res.get("recordedBy").isJsonNull() ? uuid(res, "recordedBy") : null
        );
    }

    public List<AttendanceRecord> getAttendanceHistory(UUID employeeId, LocalDate from, LocalDate to) {
        String q = "?employeeId=" + employeeId + "&from=" + from + "&to=" + to;
        JsonObject root = send("/api/v1/personnel/attendance" + q, null);
        List<AttendanceRecord> rows = new ArrayList<>();
        if (root.has("attendance") && root.get("attendance").isJsonArray()) {
            for (var elem : root.getAsJsonArray("attendance")) {
                JsonObject res = elem.getAsJsonObject();
                rows.add(new AttendanceRecord(
                        uuid(res, "id"),
                        uuid(res, "companyId"),
                        uuid(res, "employeeId"),
                        LocalDate.parse(text(res, "recordDate")),
                        text(res, "status"),
                        res.has("notes") && !res.get("notes").isJsonNull() ? res.get("notes").getAsString() : null,
                        res.has("recordedBy") && !res.get("recordedBy").isJsonNull() ? uuid(res, "recordedBy") : null
                ));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    public CompensationRecord recordCompensation(UUID employeeId, String arrangementType, long baseRatePaise, long tripAllowancePaise, LocalDate effectiveFrom) {
        JsonObject json = new JsonObject();
        json.addProperty("employeeId", employeeId.toString());
        json.addProperty("arrangementType", arrangementType);
        json.addProperty("baseRatePaise", baseRatePaise);
        json.addProperty("tripAllowancePaise", tripAllowancePaise);
        json.addProperty("effectiveFrom", effectiveFrom.toString());
        JsonObject res = send("/api/v1/personnel/compensation", json);
        return new CompensationRecord(
                uuid(res, "id"),
                uuid(res, "companyId"),
                uuid(res, "employeeId"),
                text(res, "arrangementType"),
                DesktopJson.longVal(res, "baseRatePaise"),
                DesktopJson.longVal(res, "tripAllowancePaise"),
                LocalDate.parse(text(res, "effectiveFrom"))
        );
    }

    public Optional<CompensationRecord> getCompensation(UUID employeeId) {
        JsonObject res = send("/api/v1/personnel/compensation?employeeId=" + employeeId, null);
        if (res.has("found") && res.get("found").getAsBoolean() && res.has("compensation")) {
            JsonObject c = res.getAsJsonObject("compensation");
            return Optional.of(new CompensationRecord(
                    uuid(c, "id"),
                    uuid(c, "companyId"),
                    uuid(c, "employeeId"),
                    text(c, "arrangementType"),
                    DesktopJson.longVal(c, "baseRatePaise"),
                    DesktopJson.longVal(c, "tripAllowancePaise"),
                    LocalDate.parse(text(c, "effectiveFrom"))
            ));
        }
        return Optional.empty();
    }

    public MemberManagementRecord inviteStaff(String email, String fullName) {
        JsonObject json = new JsonObject();
        json.addProperty("email", email);
        if (fullName != null && !fullName.isBlank()) json.addProperty("fullName", fullName);
        JsonObject res = send("/api/v1/admin/members/invite", json);
        return new MemberManagementRecord(
                uuid(res, "authUserId"),
                uuid(res, "companyId"),
                text(res, "role"),
                res.get("active").getAsBoolean(),
                null,
                java.time.Instant.parse(text(res, "createdAt")),
                1L
        );
    }

    public List<MemberManagementRecord> listMembers() {
        JsonObject root = send("/api/v1/admin/members", null);
        List<MemberManagementRecord> rows = new ArrayList<>();
        if (root.has("members") && root.get("members").isJsonArray()) {
            for (var elem : root.getAsJsonArray("members")) {
                JsonObject m = elem.getAsJsonObject();
                rows.add(new MemberManagementRecord(
                        uuid(m, "authUserId"),
                        uuid(m, "companyId"),
                        text(m, "role"),
                        m.get("active").getAsBoolean(),
                        null,
                        java.time.Instant.parse(text(m, "createdAt")),
                        1L
                ));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    private EmployeeRecord parseEmployee(JsonObject o) {
        return new EmployeeRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                o.has("authUserId") && !o.get("authUserId").isJsonNull() ? uuid(o, "authUserId") : null,
                text(o, "fullName"),
                text(o, "contactNumber"),
                o.has("email") && !o.get("email").isJsonNull() ? o.get("email").getAsString() : null,
                text(o, "address"),
                LocalDate.parse(text(o, "joiningDate")),
                text(o, "employmentStatus"),
                o.get("isDriver").getAsBoolean(),
                o.has("photoFileId") && !o.get("photoFileId").isJsonNull() ? uuid(o, "photoFileId") : null,
                DesktopJson.longVal(o, "version")
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
        if (response.status() == 503) {
            String msg = "Service unavailable.";
            try {
                JsonObject err = DesktopJson.object(response.body());
                if (err.has("error")) msg = err.get("error").getAsString();
            } catch (Exception ignored) {}
            throw new IllegalStateException(msg);
        }
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
