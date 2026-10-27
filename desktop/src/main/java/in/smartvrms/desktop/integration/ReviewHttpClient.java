package in.smartvrms.desktop.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import in.smartvrms.contracts.ServiceReviewCommand;
import in.smartvrms.contracts.ServiceReviewCorrectionCommand;
import in.smartvrms.contracts.ServiceReviewRecord;
import in.smartvrms.desktop.infrastructure.DesktopConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated Desktop Client for Service Reviews and Moderation Corrections
 * (FR-REV-001, FR-REV-002).
 */
public final class ReviewHttpClient {
    private final URI endpoint;
    private final DesktopSession session;
    private final DesktopHttp http = new DesktopHttp();

    public ReviewHttpClient(DesktopConfig config, DesktopSession session) {
        this.endpoint = config.apiEndpoint().orElseThrow();
        this.session = Objects.requireNonNull(session, "session");
    }

    public ServiceReviewRecord recordReview(ServiceReviewCommand cmd) {
        JsonObject req = new JsonObject();
        req.addProperty("bookingId", cmd.bookingId().toString());
        req.addProperty("rating", cmd.rating());
        req.addProperty("feedbackText", cmd.feedbackText());
        req.addProperty("communicationSource", cmd.communicationSource());
        req.addProperty("consentGiven", cmd.consentGiven());

        JsonObject res = sendPost("/api/v1/reviews", req);
        return parseReview(res);
    }

    public ServiceReviewRecord correctReview(ServiceReviewCorrectionCommand cmd) {
        JsonObject req = new JsonObject();
        req.addProperty("reviewId", cmd.reviewId().toString());
        req.addProperty("revisedRating", cmd.revisedRating());
        req.addProperty("revisedFeedback", cmd.revisedFeedback());
        req.addProperty("correctionReason", cmd.correctionReason());

        JsonObject res = sendPost("/api/v1/reviews/correct", req);
        return parseReview(res);
    }

    public List<ServiceReviewRecord> listReviews(UUID bookingId, UUID customerId) {
        StringBuilder path = new StringBuilder("/api/v1/reviews?");
        if (bookingId != null) path.append("bookingId=").append(URLEncoder.encode(bookingId.toString(), StandardCharsets.UTF_8)).append("&");
        if (customerId != null) path.append("customerId=").append(URLEncoder.encode(customerId.toString(), StandardCharsets.UTF_8));
        JsonObject res = sendGet(path.toString());
        List<ServiceReviewRecord> list = new ArrayList<>();
        if (res.has("reviews") && res.get("reviews").isJsonArray()) {
            for (JsonElement el : res.getAsJsonArray("reviews")) {
                list.add(parseReview(el.getAsJsonObject()));
            }
        }
        return list;
    }

    private static ServiceReviewRecord parseReview(JsonObject o) {
        Instant recordedAt = o.has("recordedAt") && !o.get("recordedAt").isJsonNull()
                ? Instant.parse(o.get("recordedAt").getAsString()) : Instant.now();

        return new ServiceReviewRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "bookingId"),
                uuid(o, "customerId"),
                uuid(o, "visitId"),
                o.has("rating") ? o.get("rating").getAsInt() : 5,
                text(o, "feedbackText"),
                text(o, "communicationSource"),
                !o.has("consentGiven") || o.get("consentGiven").getAsBoolean(),
                text(o, "status"),
                uuid(o, "recordedBy"),
                recordedAt
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
        if (response.status() == 409) throw new IllegalStateException("Conflict: Duplicate review or concurrent modification (409 Conflict).");
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
        return o.has(key) && !o.get(key).isJsonNull() && !o.get(key).getAsString().isBlank()
                ? UUID.fromString(o.get(key).getAsString()) : null;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
