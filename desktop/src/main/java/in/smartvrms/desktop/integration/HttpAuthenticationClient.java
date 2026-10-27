package in.smartvrms.desktop.integration;

import com.google.gson.*;
import in.smartvrms.application.port.AuthenticationPort;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Strict JSON, bounded HTTP and memory-only tokens. Server messages are never echoed. */
public final class HttpAuthenticationClient implements AuthenticationPort {
    private final URI endpoint;
    private final DesktopHttp client = new DesktopHttp();
    public HttpAuthenticationClient(DesktopConfig config) { endpoint = config.apiEndpoint().orElse(null); }
    public boolean isConfigured() { return endpoint != null; }
    @Override public void prepareConnection() {
        long deadline=System.nanoTime()+Duration.ofSeconds(95).toNanos();
        while(System.nanoTime()<deadline) {
            try {
                var health=HttpRequest.newBuilder(endpoint.resolve("/health")).timeout(Duration.ofSeconds(70)).GET().build();
                int status=client.send(health).status();
                if(status==200)return;
                if(status!=502 && status!=503 && status!=504 && status!=425)break;
            } catch(RuntimeException ex) { if(Thread.currentThread().isInterrupted())throw ex; }
            try { Thread.sleep(2000); } catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new IllegalStateException("Connection cancelled."); }
        }
        throw new IllegalStateException("The service is still starting or unavailable. Try again shortly.");
    }
    public SignInResult signIn(String email, char[] password) {
        if (email == null || email.isBlank() || password == null || password.length == 0)
            throw new IllegalArgumentException("Email and password are required.");
        JsonObject body = new JsonObject();
        body.addProperty("email", email.strip()); body.addProperty("password", new String(password));
        return session(post("/api/v1/auth/login", body));
    }
    public synchronized SignInResult refresh(SessionTokens tokens) {
        if (tokens == null || tokens.refreshToken().isBlank()) throw new SecurityException("Valid session required.");
        JsonObject body = new JsonObject(); body.addProperty("refreshToken", tokens.refreshToken());
        return session(post("/api/v1/auth/refresh", body));
    }
    public void logout(SessionTokens tokens) {
        if (!isConfigured() || tokens == null) return;
        try { client.send(request("/api/v1/auth/logout").header("Authorization", "Bearer " + tokens.accessToken())
                .POST(HttpRequest.BodyPublishers.noBody()).build()); }
        catch (RuntimeException ignored) { /* The owner clears its memory session regardless. */ }
    }
    public StaffSession verifySession(String token) {
        if (token == null || token.isBlank()) throw new SecurityException("Valid session required.");
        JsonObject root = read(client.send(request("/api/v1/auth/me").header("Authorization", "Bearer " + token).GET().build()));
        return staff(root.has("user") ? DesktopJson.object(root, "user") : root);
    }
    private HttpRequest.Builder request(String path) {
        if (!isConfigured()) throw new IllegalStateException("Authentication service is not configured.");
        return HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
    }
    private JsonObject post(String path, JsonObject body) {
        return read(client.send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build()));
    }
    private JsonObject read(DesktopHttp.Response response) {
        int status = response.status();
        if (status == 401) throw new SecurityException("Invalid or expired credentials. Sign in again.");
        if (status == 403) throw new SecurityException("Access denied.");
        if (status == 429) throw new IllegalStateException("Too many requests. Try again later.");
        if (status != 200) throw new IllegalStateException("Authentication service is unavailable. Try again later.");
        if (!response.contentType().split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
            throw new IllegalStateException("Invalid authentication response.");
        return DesktopJson.object(response.body());
    }
    private SignInResult session(JsonObject root) {
        try {
            String access = DesktopJson.string(root, "accessToken"), refresh = DesktopJson.string(root, "refreshToken");
            long expiry = DesktopJson.longVal(root, "expiresIn");
            if (access.isBlank() || refresh.isBlank() || expiry <= 0) throw new IllegalArgumentException();
            return new SignInResult(staff(DesktopJson.object(root, "user")), new SessionTokens(access, refresh, expiry));
        } catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid authentication response."); }
    }
    private StaffSession staff(JsonObject user) {
        try {
            String role = DesktopJson.string(user, "role");
            if (!Set.of("ADMINISTRATOR", "EMPLOYEE").contains(role)) throw new IllegalArgumentException();
            Set<String> capabilities = new HashSet<>();
            JsonElement raw = user.get("capabilities");
            if (raw == null || !raw.isJsonArray()) throw new IllegalArgumentException();
            for (JsonElement item : raw.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                capabilities.add(item.getAsString());
            }
            String email = user.has("email") ? DesktopJson.string(user, "email") : "";
            return new StaffSession(UUID.fromString(DesktopJson.string(user, "id")),
                    UUID.fromString(DesktopJson.string(user, "companyId")), role, email, capabilities);
        } catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid authentication response."); }
    }
}
