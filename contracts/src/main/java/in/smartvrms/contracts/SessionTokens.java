package in.smartvrms.contracts;

import java.util.Objects;

/** In-memory tokens. Sensitive values are redacted from toString and logs. */
public record SessionTokens(String accessToken, String refreshToken, long expiresInSeconds) {
    public SessionTokens {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshToken, "refreshToken");
    }
    @Override
    public String toString() {
        return "SessionTokens[tokens omitted]";
    }
}
