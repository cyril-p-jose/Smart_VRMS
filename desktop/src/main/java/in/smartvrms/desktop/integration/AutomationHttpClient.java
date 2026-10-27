package in.smartvrms.desktop.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import in.smartvrms.contracts.DeliveryAttemptRecord;
import in.smartvrms.contracts.OutboxMessageRecord;
import in.smartvrms.contracts.WorkerHealthStatus;
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
 * Authenticated Desktop Client for Automation Outbox, Worker Health and Document Reminders
 * (FR-MSG-001, FR-MSG-002, FR-DOC-003, FR-DOC-004, NFR-HOST-001).
 */
public final class AutomationHttpClient {
    private final URI endpoint;
    private final DesktopSession session;
    private final DesktopHttp http = new DesktopHttp();

    public AutomationHttpClient(DesktopConfig config, DesktopSession session) {
        this.endpoint = config.apiEndpoint().orElseThrow();
        this.session = Objects.requireNonNull(session, "session");
    }

    public WorkerHealthStatus getWorkerHealth() {
        JsonObject res = sendGet("/api/v1/automation/health");
        Instant lastScan = null;
        if (res.has("lastScanTime") && !res.get("lastScanTime").getAsString().isBlank()) {
            lastScan = Instant.parse(res.get("lastScanTime").getAsString());
        }
        return new WorkerHealthStatus(
                lastScan,
                res.get("pendingMessagesCount").getAsInt(),
                res.get("claimedMessagesCount").getAsInt(),
                res.get("failedMessagesCount").getAsInt(),
                res.get("acceptedMessagesCount").getAsInt(),
                res.get("oldestPendingAgeSeconds").getAsLong(),
                res.get("isLagging").getAsBoolean()
        );
    }

    public int scanReminders() {
        JsonObject req = new JsonObject();
        JsonObject res = sendPost("/api/v1/automation/reminders/scan", req);
        return res.has("scannedCount") ? res.get("scannedCount").getAsInt() : 0;
    }

    public List<OutboxMessageRecord> listRecentMessages(int limit) {
        String path = "/api/v1/automation/outbox?limit=" + Math.max(1, Math.min(100, limit));
        JsonObject res = sendGet(path);
        List<OutboxMessageRecord> list = new ArrayList<>();
        if (res.has("messages") && res.get("messages").isJsonArray()) {
            for (JsonElement el : res.getAsJsonArray("messages")) {
                list.add(parseOutboxMessage(el.getAsJsonObject()));
            }
        }
        return list;
    }

    public List<OutboxMessageRecord> claimBatch(int batchSize, int leaseDurationSeconds) {
        JsonObject req = new JsonObject();
        req.addProperty("batchSize", batchSize);
        req.addProperty("leaseDurationSeconds", leaseDurationSeconds);
        JsonObject res = sendPost("/api/v1/automation/outbox/claim", req);
        List<OutboxMessageRecord> list = new ArrayList<>();
        if (res.has("messages") && res.get("messages").isJsonArray()) {
            for (JsonElement el : res.getAsJsonArray("messages")) {
                list.add(parseOutboxMessage(el.getAsJsonObject()));
            }
        }
        return list;
    }

    public void markAccepted(UUID outboxId, String providerName, int responseCode, String messageIdRef) {
        JsonObject req = new JsonObject();
        req.addProperty("outboxId", outboxId.toString());
        req.addProperty("providerName", providerName);
        req.addProperty("responseCode", responseCode);
        if (messageIdRef != null) req.addProperty("providerMessageId", messageIdRef);
        sendPost("/api/v1/automation/outbox/complete", req);
    }

    public void markFailed(UUID outboxId, String providerName, String errorMessage, boolean permanent) {
        JsonObject req = new JsonObject();
        req.addProperty("outboxId", outboxId.toString());
        req.addProperty("providerName", providerName);
        req.addProperty("errorMessage", errorMessage);
        req.addProperty("permanent", permanent);
        sendPost("/api/v1/automation/outbox/fail", req);
    }

    public List<DeliveryAttemptRecord> listAttempts(UUID outboxId) {
        String path = "/api/v1/automation/attempts?outboxId=" + URLEncoder.encode(outboxId.toString(), StandardCharsets.UTF_8);
        JsonObject res = sendGet(path);
        List<DeliveryAttemptRecord> list = new ArrayList<>();
        if (res.has("attempts") && res.get("attempts").isJsonArray()) {
            for (JsonElement el : res.getAsJsonArray("attempts")) {
                list.add(parseDeliveryAttempt(el.getAsJsonObject()));
            }
        }
        return list;
    }

    private static OutboxMessageRecord parseOutboxMessage(JsonObject o) {
        Instant leaseUntil = o.has("leaseUntil") && !o.get("leaseUntil").isJsonNull() && !o.get("leaseUntil").getAsString().isBlank()
                ? Instant.parse(o.get("leaseUntil").getAsString()) : null;
        Instant nextAttempt = o.has("nextAttemptAt") && !o.get("nextAttemptAt").isJsonNull() && !o.get("nextAttemptAt").getAsString().isBlank()
                ? Instant.parse(o.get("nextAttemptAt").getAsString()) : null;
        Instant lastAttempt = o.has("lastAttemptAt") && !o.get("lastAttemptAt").isJsonNull() && !o.get("lastAttemptAt").getAsString().isBlank()
                ? Instant.parse(o.get("lastAttemptAt").getAsString()) : null;
        Instant createdAt = o.has("createdAt") && !o.get("createdAt").isJsonNull()
                ? Instant.parse(o.get("createdAt").getAsString()) : Instant.now();

        return new OutboxMessageRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                text(o, "eventType"),
                text(o, "idempotencyKey"),
                text(o, "recipientEmail"),
                text(o, "recipientRole"),
                text(o, "subject"),
                text(o, "templateName"),
                text(o, "payloadJson"),
                uuid(o, "attachmentDocId"),
                text(o, "status"),
                leaseUntil,
                textOrNull(o, "claimedBy"),
                o.has("attemptsCount") ? o.get("attemptsCount").getAsInt() : 0,
                o.has("maxAttempts") ? o.get("maxAttempts").getAsInt() : 5,
                nextAttempt,
                lastAttempt,
                textOrNull(o, "lastError"),
                createdAt
        );
    }

    private static DeliveryAttemptRecord parseDeliveryAttempt(JsonObject o) {
        Instant started = o.has("startedAt") && !o.get("startedAt").isJsonNull()
                ? Instant.parse(o.get("startedAt").getAsString()) : Instant.now();
        Instant finished = o.has("finishedAt") && !o.get("finishedAt").isJsonNull() && !o.get("finishedAt").getAsString().isBlank()
                ? Instant.parse(o.get("finishedAt").getAsString()) : null;
        Integer code = o.has("providerResponseCode") && !o.get("providerResponseCode").isJsonNull()
                ? o.get("providerResponseCode").getAsInt() : null;

        return new DeliveryAttemptRecord(
                uuid(o, "id"),
                uuid(o, "companyId"),
                uuid(o, "outboxId"),
                o.has("attemptNumber") ? o.get("attemptNumber").getAsInt() : 1,
                started,
                finished,
                text(o, "providerName"),
                code,
                textOrNull(o, "providerMessageId"),
                textOrNull(o, "errorMessage"),
                text(o, "outcome")
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
        return o.has(key) && !o.get(key).isJsonNull() && !o.get(key).getAsString().isBlank()
                ? UUID.fromString(o.get(key).getAsString()) : null;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String textOrNull(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
