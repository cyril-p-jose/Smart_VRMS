package in.smartvrms.contracts;

import java.util.Objects;

/** Result of staff authentication containing active session and memory-only tokens. */
public record SignInResult(StaffSession session, SessionTokens tokens) {
    public SignInResult {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(tokens, "tokens");
    }
    @Override
    public String toString() {
        return "SignInResult[session=" + session + ", tokens omitted]";
    }
}
