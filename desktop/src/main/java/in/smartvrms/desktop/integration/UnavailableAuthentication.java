package in.smartvrms.desktop.integration;

import in.smartvrms.application.port.AuthenticationPort;
import in.smartvrms.contracts.SessionTokens;
import in.smartvrms.contracts.SignInResult;
import in.smartvrms.contracts.StaffSession;

/** Explicit Stage 1 capability boundary. Does not accept credentials or create sessions. */
public final class UnavailableAuthentication implements AuthenticationPort {
    @Override public boolean isConfigured() { return false; }
    @Override public SignInResult signIn(String email, char[] password) { throw new IllegalStateException("Authentication service is not configured."); }
    @Override public SignInResult refresh(SessionTokens tokens) { throw new IllegalStateException("Authentication service is not configured."); }
    @Override public void logout(SessionTokens tokens) { /* no-op when not configured */ }
    @Override public StaffSession verifySession(String accessToken) { throw new IllegalStateException("Authentication service is not configured."); }
}
