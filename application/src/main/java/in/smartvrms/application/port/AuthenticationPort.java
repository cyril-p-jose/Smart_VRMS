package in.smartvrms.application.port;

import in.smartvrms.contracts.SessionTokens;
import in.smartvrms.contracts.SignInResult;
import in.smartvrms.contracts.StaffSession;

/** Implementations must validate with the approved identity provider; no local password system. */
public interface AuthenticationPort {
    boolean isConfigured();
    /** Optional safe GET warm-up before sending credentials to a sleeping free-tier API. */
    default void prepareConnection() { }
    SignInResult signIn(String email, char[] password);
    SignInResult refresh(SessionTokens tokens);
    void logout(SessionTokens tokens);
    StaffSession verifySession(String accessToken);
}
