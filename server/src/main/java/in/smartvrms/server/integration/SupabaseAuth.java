package in.smartvrms.server.integration;

import com.google.gson.JsonObject;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Fresh provider verification for each protected call. User metadata is never an authority. */
public final class SupabaseAuth {
    public record Response(int status, String contentType, byte[] body) {
        @Override public String toString() { return "AuthResponse[status=" + status + ", body omitted]"; }
    }
    @FunctionalInterface
    public interface Transport {
        Response send(HttpRequest request);
        default Response get(HttpRequest request) { return send(request); }
    }
    public record PublicSettings(boolean signupDisabled, boolean emailConfirmationRequired) { }
    public record AuthTokens(String accessToken, String refreshToken, long expiresInSeconds, UUID userId, String email) {
        public AuthTokens {
            Objects.requireNonNull(accessToken, "accessToken");
            Objects.requireNonNull(refreshToken, "refreshToken");
            Objects.requireNonNull(userId, "userId");
        }
        @Override public String toString() { return "AuthTokens[userId=" + userId + ", tokens omitted]"; }
    }

    private final SupabaseConfig config;
    private final Transport transport;
    private final Clock clock;
    private final Object refreshLock = new Object();

    public SupabaseAuth(SupabaseConfig config) { this(config, new BoundedHttp()::send, Clock.systemUTC()); }
    public SupabaseAuth(SupabaseConfig config, Transport transport, Clock clock) {
        this.config = Objects.requireNonNull(config);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
    }

    public PublicSettings settings() {
        JsonObject json = read("/auth/v1/settings", null);
        return new PublicSettings(StrictJson.bool(json, "disable_signup"), !StrictJson.bool(json, "mailer_autoconfirm"));
    }

    /** Returns only the provider-verified ID. A membership lookup is still mandatory. */
    public UUID verifyStaffIdentity(String accessToken) {
        validateTokenFormat(accessToken);
        JsonObject json = read("/auth/v1/user", accessToken);
        return validateUser(json);
    }

    /** Authenticate staff member using password grant. Memory-only tokens returned. */
    public AuthTokens signInWithPassword(String email, String password) {
        if (email == null || email.length() > 255 || !email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new AuthFailure(AuthFailure.Kind.INVALID_CREDENTIALS);
        }
        if (password == null || password.isEmpty() || password.length() > 1024) {
            throw new AuthFailure(AuthFailure.Kind.INVALID_CREDENTIALS);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("email", email.strip());
        payload.addProperty("password", password);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(config.endpoint().resolve("/auth/v1/token?grant_type=password"))
                .timeout(Duration.ofSeconds(15))
                .header("apikey", config.publishableKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        Response response = transport.send(request);
        if (response.status() == 400 || response.status() == 401) {
            throw new AuthFailure(AuthFailure.Kind.INVALID_CREDENTIALS);
        }
        if (response.status() == 429) {
            throw new AuthFailure(AuthFailure.Kind.RATE_LIMITED);
        }
        if (response.status() != 200) {
            throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE);
        }
        requireJsonContentType(response);
        JsonObject json = StrictJson.object(response.body());
        return extractTokens(json);
    }

    /** Refresh token rotation with serialized execution to prevent concurrency races. */
    public AuthTokens refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.length() > 8192 || !refreshToken.matches("^[A-Za-z0-9._-]+$")) {
            throw new AuthFailure(AuthFailure.Kind.INVALID_SESSION);
        }
        synchronized (refreshLock) {
            JsonObject payload = new JsonObject();
            payload.addProperty("refresh_token", refreshToken);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(config.endpoint().resolve("/auth/v1/token?grant_type=refresh_token"))
                    .timeout(Duration.ofSeconds(15))
                    .header("apikey", config.publishableKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            Response response = transport.send(request);
            if (response.status() == 400 || response.status() == 401 || response.status() == 403) {
                throw new AuthFailure(AuthFailure.Kind.INVALID_SESSION);
            }
            if (response.status() == 429) {
                throw new AuthFailure(AuthFailure.Kind.RATE_LIMITED);
            }
            if (response.status() != 200) {
                throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE);
            }
            requireJsonContentType(response);
            JsonObject json = StrictJson.object(response.body());
            return extractTokens(json);
        }
    }

    /** Revoke this session only; do not sign out the user's other devices. */
    public void logout(String accessToken) {
        validateTokenFormat(accessToken);
        HttpRequest request = HttpRequest.newBuilder(config.endpoint().resolve("/auth/v1/logout?scope=local"))
                .timeout(Duration.ofSeconds(15))
                .header("apikey", config.publishableKey())
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        Response response = transport.send(request);
        if (response.status() == 429) {
            throw new AuthFailure(AuthFailure.Kind.RATE_LIMITED);
        }
        if (response.status() >= 500) {
            throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE);
        }
        // 200, 204, 401, 403 treated as successfully signed out / inactive session
    }

    private void validateTokenFormat(String token) {
        if (token == null || token.length() > 8192 || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            throw new AuthFailure(AuthFailure.Kind.INVALID_SESSION);
        }
    }

    private AuthTokens extractTokens(JsonObject json) {
        String accessToken = StrictJson.string(json, "access_token");
        validateTokenFormat(accessToken);
        String refreshToken = StrictJson.string(json, "refresh_token");
        long expiresIn = StrictJson.longVal(json, "expires_in");
        JsonObject userJson = StrictJson.object(json, "user");
        UUID userId = validateUser(userJson);
        String email = userJson.has("email") && !userJson.get("email").isJsonNull() ? StrictJson.string(userJson, "email") : "";
        return new AuthTokens(accessToken, refreshToken, expiresIn, userId, email);
    }

    private UUID validateUser(JsonObject json) {
        if (StrictJson.bool(json, "is_anonymous")) {
            throw new AuthFailure(AuthFailure.Kind.INVALID_SESSION);
        }
        try {
            String id = StrictJson.string(json, "id");
            if (!id.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) throw new IllegalArgumentException();
            Instant.parse(StrictJson.string(json, "email_confirmed_at"));
            if (json.has("deleted_at") && !json.get("deleted_at").isJsonNull()) {
                throw new AuthFailure(AuthFailure.Kind.INVALID_SESSION);
            }
            if (json.has("banned_until") && !json.get("banned_until").isJsonNull()
                    && Instant.parse(StrictJson.string(json, "banned_until")).isAfter(clock.instant())) {
                throw new AuthFailure(AuthFailure.Kind.INVALID_SESSION);
            }
            return UUID.fromString(id);
        } catch (java.time.format.DateTimeParseException | IllegalArgumentException ex) {
            throw new AuthFailure(AuthFailure.Kind.INVALID_RESPONSE);
        }
    }

    private void requireJsonContentType(Response response) {
        if (response.contentType() == null || !response.contentType().split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw new AuthFailure(AuthFailure.Kind.INVALID_RESPONSE);
        }
    }

    private JsonObject read(String path, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(config.endpoint().resolve(path)).timeout(Duration.ofSeconds(15))
                .header("apikey", config.publishableKey()).header("Accept", "application/json").GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        Response response = transport.send(request.build());
        if (response.status() == 429) throw new AuthFailure(AuthFailure.Kind.RATE_LIMITED);
        if (response.status() == 401 || response.status() == 403) throw new AuthFailure(AuthFailure.Kind.INVALID_SESSION);
        if (response.status() != 200) throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE);
        requireJsonContentType(response);
        return StrictJson.object(response.body());
    }
}
