package in.smartvrms.desktop.integration;

import com.google.gson.*;
import in.smartvrms.contracts.CustomerRecord;
import in.smartvrms.contracts.CustomerVisitRecord;
import in.smartvrms.desktop.infrastructure.DesktopConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Authenticated customer and visit operational API client (FR-CUST-001..008). */
public final class CustomerHttpClient {
    private final URI endpoint;
    private final DesktopSession session;
    private final DesktopHttp http = new DesktopHttp();

    public CustomerHttpClient(DesktopConfig config, DesktopSession session) {
        this.endpoint = config.apiEndpoint().orElseThrow();
        this.session = Objects.requireNonNull(session, "session");
    }

    public List<CustomerRecord> searchCustomers(String query) {
        String encoded = URLEncoder.encode(query != null ? query : "", StandardCharsets.UTF_8);
        JsonObject root = sendGet("/api/v1/customers/search?query=" + encoded);
        List<CustomerRecord> rows = new ArrayList<>();
        if (root.has("customers") && root.get("customers").isJsonArray()) {
            for (var elem : root.getAsJsonArray("customers")) {
                rows.add(parseCustomer(elem.getAsJsonObject()));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    public Optional<CustomerRecord> findDuplicateCustomer(String mobile, String email) {
        StringBuilder q = new StringBuilder("/api/v1/customers/duplicate?");
        if (mobile != null && !mobile.isBlank()) {
            q.append("mobile=").append(URLEncoder.encode(mobile.strip(), StandardCharsets.UTF_8)).append("&");
        }
        if (email != null && !email.isBlank()) {
            q.append("email=").append(URLEncoder.encode(email.strip(), StandardCharsets.UTF_8));
        }
        JsonObject root = sendGet(q.toString());
        if (root.has("duplicateFound") && root.get("duplicateFound").getAsBoolean() && root.has("customer")) {
            return Optional.of(parseCustomer(root.getAsJsonObject("customer")));
        }
        return Optional.empty();
    }

    public CustomerRecord createCustomerWithVisit(String fullName, String mobile, String email, String address, int ageAtVisit, String bookingContext) {
        JsonObject json = new JsonObject();
        json.addProperty("fullName", fullName);
        json.addProperty("mobile", mobile);
        json.addProperty("email", email);
        json.addProperty("address", address);
        json.addProperty("ageAtVisit", ageAtVisit);
        json.addProperty("bookingContext", bookingContext);
        JsonObject res = sendPost("/api/v1/customers", json);
        return parseCustomer(res);
    }

    public CustomerVisitRecord recordRepeatVisit(UUID customerId, int ageAtVisit, String bookingContext) {
        JsonObject json = new JsonObject();
        json.addProperty("customerId", customerId.toString());
        json.addProperty("ageAtVisit", ageAtVisit);
        json.addProperty("bookingContext", bookingContext);
        JsonObject res = sendPost("/api/v1/customers/visits", json);
        return parseVisit(res);
    }

    public List<CustomerVisitRecord> getVisitHistory(UUID customerId) {
        JsonObject root = sendGet("/api/v1/customers/visits?customerId=" + customerId.toString());
        List<CustomerVisitRecord> rows = new ArrayList<>();
        if (root.has("visits") && root.get("visits").isJsonArray()) {
            for (var elem : root.getAsJsonArray("visits")) {
                rows.add(parseVisit(elem.getAsJsonObject()));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    private CustomerRecord parseCustomer(JsonObject o) {
        return new CustomerRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                text(o, "fullName"),
                text(o, "mobileNumber"),
                text(o, "email"),
                text(o, "address"),
                Instant.parse(text(o, "createdAt")),
                o.get("version").getAsLong()
        );
    }

    private CustomerVisitRecord parseVisit(JsonObject o) {
        return new CustomerVisitRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "customerId"),
                o.get("ageAtVisit").getAsInt(),
                Instant.parse(text(o, "visitDate")),
                text(o, "purpose")
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
