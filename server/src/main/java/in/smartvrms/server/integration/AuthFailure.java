package in.smartvrms.server.integration;

/** Safe errors deliberately omit provider response bodies, URLs and credentials. */
public final class AuthFailure extends RuntimeException {
    public enum Kind { INVALID_CREDENTIALS, INVALID_SESSION, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE }
    private final Kind kind;
    public AuthFailure(Kind kind) { super(switch(kind) {
        case INVALID_CREDENTIALS -> "Invalid email or password.";
        case INVALID_SESSION -> "Sign in again with an active staff account.";
        case RATE_LIMITED -> "Authentication is rate limited. Try again later.";
        case UNAVAILABLE -> "Authentication service is unavailable. Try again later.";
        case INVALID_RESPONSE -> "Authentication response could not be verified.";
    }); this.kind=kind; }
    public Kind kind() { return kind; }
}
