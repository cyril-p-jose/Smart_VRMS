package in.smartvrms.verification;

import in.smartvrms.application.port.AuthenticationPort;
import in.smartvrms.contracts.SessionTokens;
import in.smartvrms.contracts.SignInResult;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.desktop.DesktopMain;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import in.smartvrms.desktop.integration.HttpAuthenticationClient;
import in.smartvrms.desktop.integration.UnavailableAuthentication;
import in.smartvrms.desktop.ui.Design;
import in.smartvrms.desktop.ui.SignInView;
import in.smartvrms.desktop.ui.WorkspaceView;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.integration.AuthFailure;
import in.smartvrms.server.integration.BoundedHttp;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.StaffAuthorizer;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Complete Stage 2A verification suite: identity, sessions, minimal JDK API server, and protected desktop access. */
public final class Stage2AIntegrationChecks {
    private static int checks;
    private static final String URL = "https://abcdefghijklmnopqrst.supabase.co";
    private static final String KEY = "sb_publishable_fixture0123456789";
    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EMPLOYEE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMPANY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 2A integration checks...");

        testTokenMemoryRedaction();
        testPasswordAuthenticationCases();
        testTokenRefreshAndConcurrency();
        testLogoutFlow();
        testVrmsHttpServerEndToEnd();
        testDesktopClientAndSwingIntegration();

        System.out.println("PASS: " + checks + " Stage 2A identity, session and protected application access assertions.");
    }

    private static void testTokenMemoryRedaction() {
        SessionTokens tokens = new SessionTokens("access.jwt.token", "refresh-token-secret-123", 3600);
        require(!tokens.toString().contains("access.jwt.token"), "access token omitted from toString");
        require(!tokens.toString().contains("refresh-token-secret"), "refresh token omitted from toString");
        require(tokens.toString().contains("tokens omitted"), "tokens omitted marker present");

        StaffSession session = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@example.invalid", Set.of("VIEW_FLEET"));
        require(session.isAdministrator(), "admin role identified");
        require(!session.isEmployee(), "admin is not employee");
        require(session.toString().contains(ADMIN_ID.toString()), "session toString includes user ID");
        require(!session.toString().contains("password"), "session toString clean");

        SignInResult result = new SignInResult(session, tokens);
        require(!result.toString().contains("refresh-token"), "signInResult toString redacts tokens");
    }

    private static void testPasswordAuthenticationCases() {
        SupabaseConfig config = new SupabaseConfig(URL, KEY);

        String successJson = "{"
                + "\"access_token\":\"eyJhbGci.eyJzdWIi.eyJpZCI\","
                + "\"token_type\":\"bearer\","
                + "\"expires_in\":3600,"
                + "\"refresh_token\":\"def-456-refresh\","
                + "\"user\":{"
                + "\"id\":\"" + ADMIN_ID + "\","
                + "\"email\":\"admin@example.invalid\","
                + "\"email_confirmed_at\":\"2026-01-01T00:00:00Z\","
                + "\"is_anonymous\":false"
                + "}}";

        // Successful password sign-in
        SupabaseAuth authSuccess = new SupabaseAuth(config, r -> {
            require(r.uri().toString().contains("grant_type=password"), "password grant URI");
            require("POST".equalsIgnoreCase(r.method()), "POST method");
            return new SupabaseAuth.Response(200, "application/json", bytes(successJson));
        }, CLOCK);

        SupabaseAuth.AuthTokens tokens = authSuccess.signInWithPassword("admin@example.invalid", "validPassword123!");
        require(tokens.userId().equals(ADMIN_ID), "user ID matched");
        require("admin@example.invalid".equals(tokens.email()), "email matched");
        require(!tokens.toString().contains("eyJhbGci"), "AuthTokens toString redacts access token");
        require(!tokens.toString().contains("def-456"), "AuthTokens toString redacts refresh token");

        // Invalid credentials (400 or 401)
        SupabaseAuth authInvalid = new SupabaseAuth(config, r -> new SupabaseAuth.Response(400, "application/json", bytes("{\"error\":\"invalid_grant\"}")), CLOCK);
        try {
            authInvalid.signInWithPassword("admin@example.invalid", "wrongPassword");
            throw new AssertionError("Expected invalid credentials failure");
        } catch (AuthFailure ex) {
            require(ex.kind() == AuthFailure.Kind.INVALID_CREDENTIALS, "mapped to INVALID_CREDENTIALS");
            require(!ex.getMessage().contains("wrongPassword"), "password omitted from error message");
        }

        // Malformed inputs rejected before network
        SupabaseAuth noNet = new SupabaseAuth(config, r -> { throw new AssertionError("Network called on invalid input"); }, CLOCK);
        try { noNet.signInWithPassword("bad-email", "pass"); throw new AssertionError(); } catch (AuthFailure ex) { require(ex.kind() == AuthFailure.Kind.INVALID_CREDENTIALS, "bad email rejected"); }
        try { noNet.signInWithPassword("admin@example.invalid", ""); throw new AssertionError(); } catch (AuthFailure ex) { require(ex.kind() == AuthFailure.Kind.INVALID_CREDENTIALS, "empty pass rejected"); }

        // Unconfirmed email user
        String unconfirmedJson = successJson.replace("\"email_confirmed_at\":\"2026-01-01T00:00:00Z\"", "\"email_confirmed_at\":null");
        SupabaseAuth authUnconfirmed = new SupabaseAuth(config, r -> new SupabaseAuth.Response(200, "application/json", bytes(unconfirmedJson)), CLOCK);
        try { authUnconfirmed.signInWithPassword("admin@example.invalid", "pass"); throw new AssertionError(); } catch (AuthFailure ex) { require(ex.kind() == AuthFailure.Kind.INVALID_RESPONSE, "unconfirmed email rejected"); }

        // Anonymous user denial
        String anonJson = successJson.replace("\"is_anonymous\":false", "\"is_anonymous\":true");
        SupabaseAuth authAnon = new SupabaseAuth(config, r -> new SupabaseAuth.Response(200, "application/json", bytes(anonJson)), CLOCK);
        try { authAnon.signInWithPassword("admin@example.invalid", "pass"); throw new AssertionError(); } catch (AuthFailure ex) { require(ex.kind() == AuthFailure.Kind.INVALID_SESSION, "anonymous user rejected"); }

        // Rate limited (429)
        SupabaseAuth authRate = new SupabaseAuth(config, r -> new SupabaseAuth.Response(429, "application/json", bytes("{}")), CLOCK);
        try { authRate.signInWithPassword("admin@example.invalid", "pass"); throw new AssertionError(); } catch (AuthFailure ex) { require(ex.kind() == AuthFailure.Kind.RATE_LIMITED, "rate limit mapped"); }

        // Service unavailable (503)
        SupabaseAuth authUnavail = new SupabaseAuth(config, r -> new SupabaseAuth.Response(503, "text/plain", bytes("down")), CLOCK);
        try { authUnavail.signInWithPassword("admin@example.invalid", "pass"); throw new AssertionError(); } catch (AuthFailure ex) { require(ex.kind() == AuthFailure.Kind.UNAVAILABLE, "unavailable mapped"); }
    }

    private static void testTokenRefreshAndConcurrency() throws Exception {
        SupabaseConfig config = new SupabaseConfig(URL, KEY);
        AtomicInteger refreshCalls = new AtomicInteger(0);

        String refreshSuccessJson = "{"
                + "\"access_token\":\"eyJhbGci.eyJzdWIi.newAccessToken\","
                + "\"token_type\":\"bearer\","
                + "\"expires_in\":3600,"
                + "\"refresh_token\":\"new-refresh-token-789\","
                + "\"user\":{"
                + "\"id\":\"" + ADMIN_ID + "\","
                + "\"email\":\"admin@example.invalid\","
                + "\"email_confirmed_at\":\"2026-01-01T00:00:00Z\","
                + "\"is_anonymous\":false"
                + "}}";

        SupabaseAuth auth = new SupabaseAuth(config, r -> {
            refreshCalls.incrementAndGet();
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new SupabaseAuth.Response(200, "application/json", bytes(refreshSuccessJson));
        }, CLOCK);

        // Single refresh
        SupabaseAuth.AuthTokens tokens = auth.refreshToken("valid-refresh-token-123");
        require(tokens.userId().equals(ADMIN_ID), "refreshed tokens user ID");

        // Concurrent refresh serialization test
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Future<SupabaseAuth.AuthTokens>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return auth.refreshToken("refresh-token-" + UUID.randomUUID());
            }));
        }
        startLatch.countDown();
        for (var f : futures) {
            SupabaseAuth.AuthTokens t = f.get(5, TimeUnit.SECONDS);
            require(t.userId().equals(ADMIN_ID), "concurrent refresh completed safely");
        }
        executor.shutdown();
        require(refreshCalls.get() == threads + 1, "all serialized refreshes executed");

        // Invalid refresh token format rejected
        try { auth.refreshToken(""); throw new AssertionError(); } catch (AuthFailure ex) { require(ex.kind() == AuthFailure.Kind.INVALID_SESSION, "empty refresh token rejected"); }
    }

    private static void testLogoutFlow() {
        SupabaseConfig config = new SupabaseConfig(URL, KEY);
        boolean[] logoutCalled = { false };
        SupabaseAuth auth = new SupabaseAuth(config, r -> {
            require(r.uri().getPath().equals("/auth/v1/logout") && "scope=local".equals(r.uri().getQuery()), "logout endpoint revokes this session only");
            require(r.headers().firstValue("Authorization").orElse("").equals("Bearer eyJhbGci.eyJzdWIi.eyJpZCI"), "bearer header in logout");
            logoutCalled[0] = true;
            return new SupabaseAuth.Response(204, "application/json", new byte[0]);
        }, CLOCK);

        auth.logout("eyJhbGci.eyJzdWIi.eyJpZCI");
        require(logoutCalled[0], "logout executed successfully");
    }

    private static void testVrmsHttpServerEndToEnd() throws Exception {
        SupabaseConfig config = new SupabaseConfig(URL, KEY);

        String adminJson = "{"
                + "\"access_token\":\"eyJhbGci.admin.token\","
                + "\"token_type\":\"bearer\","
                + "\"expires_in\":3600,"
                + "\"refresh_token\":\"admin-refresh-token\","
                + "\"user\":{\"id\":\"" + ADMIN_ID + "\",\"email\":\"admin@example.invalid\",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\",\"is_anonymous\":false}}";

        String employeeJson = "{"
                + "\"access_token\":\"eyJhbGci.employee.token\","
                + "\"token_type\":\"bearer\","
                + "\"expires_in\":3600,"
                + "\"refresh_token\":\"employee-refresh-token\","
                + "\"user\":{\"id\":\"" + EMPLOYEE_ID + "\",\"email\":\"employee@example.invalid\",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\",\"is_anonymous\":false}}";

        SupabaseAuth auth = new SupabaseAuth(config, r -> {
            String path = r.uri().toString();
            if (path.contains("grant_type=password")) {
                return new SupabaseAuth.Response(200, "application/json", bytes(adminJson));
            }
            if (path.contains("grant_type=refresh_token")) {
                return new SupabaseAuth.Response(200, "application/json", bytes(adminJson));
            }
            if (path.endsWith("/logout")) {
                return new SupabaseAuth.Response(204, "application/json", new byte[0]);
            }
            if (path.endsWith("/user")) {
                return new SupabaseAuth.Response(200, "application/json", bytes(adminJson.substring(adminJson.indexOf("\"user\":") + 7, adminJson.length() - 1)));
            }
            return new SupabaseAuth.Response(404, "application/json", bytes("{}"));
        }, CLOCK);

        // Seam authorizer for integration testing
        VrmsHttpServer.Authorizer authorizer = (token, capability) -> {
            if (token.contains("admin")) {
                return new StaffAuthorizer.Staff(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR");
            }
            if (token.contains("employee")) {
                if (capability.administratorOnly()) {
                    throw new SecurityException("Access denied.");
                }
                return new StaffAuthorizer.Staff(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE");
            }
            throw new SecurityException("Access denied.");
        };

        // Start server on loopback port 0 (ephemeral)
        HttpServerConfig serverConfig = new HttpServerConfig(0);
        VrmsHttpServer server = new VrmsHttpServer(serverConfig, auth, authorizer);
        server.start();

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            URI base = server.baseUri();
            require(base.getHost().equals("127.0.0.1"), "strictly bound to loopback 127.0.0.1");

            // 1. POST /api/v1/auth/login - Success
            HttpRequest loginReq = HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@example.invalid\",\"password\":\"adminPass123\"}"))
                    .build();
            HttpResponse<String> loginRes = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
            require(loginRes.statusCode() == 200, "login HTTP 200");
            require(loginRes.headers().firstValue("X-Correlation-ID").isPresent(), "correlation ID header present");
            require("nosniff".equals(loginRes.headers().firstValue("X-Content-Type-Options").orElse("")), "nosniff header present");
            require("no-store".equals(loginRes.headers().firstValue("Cache-Control").orElse("")), "cache-control no-store");
            require(loginRes.body().contains("ADMINISTRATOR"), "role in login response");
            require(loginRes.body().contains("accessToken"), "access token in login response");
            require(loginRes.body().contains("VIEW_FLEET"), "capabilities array in login response");

            // 2. Bad Request: empty email
            HttpRequest badReq = HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"\",\"password\":\"adminPass123\"}"))
                    .build();
            HttpResponse<String> badRes = client.send(badReq, HttpResponse.BodyHandlers.ofString());
            require(badRes.statusCode() == 400, "empty field returns 400");
            require(badRes.body().contains("correlationId"), "error includes correlation ID");

            // 3. Bad Content-Type
            HttpRequest badCtReq = HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> badCtRes = client.send(badCtReq, HttpResponse.BodyHandlers.ofString());
            require(badCtRes.statusCode() == 400, "wrong content-type returns 400");

            // 4. Method Not Allowed
            HttpRequest getLoginReq = HttpRequest.newBuilder(base.resolve("/api/v1/auth/login")).GET().build();
            HttpResponse<String> getLoginRes = client.send(getLoginReq, HttpResponse.BodyHandlers.ofString());
            require(getLoginRes.statusCode() == 405, "GET on login returns 405");

            // 5. POST /api/v1/auth/refresh
            HttpRequest refReq = HttpRequest.newBuilder(base.resolve("/api/v1/auth/refresh"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"valid-token\"}"))
                    .build();
            HttpResponse<String> refRes = client.send(refReq, HttpResponse.BodyHandlers.ofString());
            require(refRes.statusCode() == 200, "refresh returns 200");
            require(refRes.body().contains("ADMINISTRATOR"), "refresh response includes role");

            // 6. POST /api/v1/auth/logout
            HttpRequest outReq = HttpRequest.newBuilder(base.resolve("/api/v1/auth/logout"))
                    .header("Authorization", "Bearer eyJhbGci.admin.token")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> outRes = client.send(outReq, HttpResponse.BodyHandlers.ofString());
            require(outRes.statusCode() == 200, "logout returns 200");

            // 7. GET /api/v1/auth/me - without token
            HttpRequest meNoToken = HttpRequest.newBuilder(base.resolve("/api/v1/auth/me")).GET().build();
            HttpResponse<String> meNoTokenRes = client.send(meNoToken, HttpResponse.BodyHandlers.ofString());
            require(meNoTokenRes.statusCode() == 401, "me without token returns 401");

            // 8. GET /api/v1/auth/me - with valid admin token
            HttpRequest meAdmin = HttpRequest.newBuilder(base.resolve("/api/v1/auth/me"))
                    .header("Authorization", "Bearer eyJhbGci.admin.token")
                    .GET().build();
            HttpResponse<String> meAdminRes = client.send(meAdmin, HttpResponse.BodyHandlers.ofString());
            require(meAdminRes.statusCode() == 200, "me with admin token returns 200");
            require(meAdminRes.body().contains("ADMINISTRATOR"), "admin role in me response");

            // 9. Employee role check: Employee capability boundary (FR-ACC-002, FR-ACC-003)
            require(authorizer.authorize("eyJhbGci.employee.token", Capability.VIEW_FLEET).role().equals("EMPLOYEE"), "employee authorized for fleet");
            try {
                authorizer.authorize("eyJhbGci.employee.token", Capability.VIEW_COMPENSATION);
                throw new AssertionError("Employee must not be authorized for VIEW_COMPENSATION");
            } catch (SecurityException ex) {
                require("Access denied.".equals(ex.getMessage()), "employee denied compensation access");
            }

            // 10. Desktop client integration with live server
            DesktopConfig dConfig = DesktopConfig.fromEndpoint(base.toString());
            HttpAuthenticationClient authClient = new HttpAuthenticationClient(dConfig);
            require(authClient.isConfigured(), "client configured with loopback");

            SignInResult signInResult = authClient.signIn("admin@example.invalid", "adminPass123".toCharArray());
            require(signInResult.session().isAdministrator(), "client sign in returns administrator session");
            require(signInResult.session().hasCapability("VIEW_FLEET"), "session has VIEW_FLEET");

            StaffSession verifiedSession = authClient.verifySession(signInResult.tokens().accessToken());
            require(verifiedSession.userId().equals(ADMIN_ID), "verifySession returns matching user ID");

            SignInResult refreshResult = authClient.refresh(signInResult.tokens());
            require(refreshResult.session().isAdministrator(), "client refresh returns valid session");

            authClient.logout(signInResult.tokens());
        } finally {
            server.stop(0);
        }
    }

    private static void testDesktopClientAndSwingIntegration() throws Exception {
        // Test Swing desktop on EDT
        SwingUtilities.invokeAndWait(() -> {
            try {
                Design.install();

                // 1. Unconfigured mode (foundation preview)
                JPanel previewRoot = DesktopMain.createContent(new UnavailableAuthentication());
                SignInView unconfSignIn = findComponent(previewRoot, SignInView.class);
                require(unconfSignIn != null, "SignInView present in unconfigured mode");
                require(!unconfSignIn.signIn.isEnabled(), "unconfigured signIn button disabled");
                require("Sign in — not connected".equals(unconfSignIn.signIn.getText()), "unconfigured button label");
                require(!unconfSignIn.email.isEnabled(), "unconfigured email field disabled");
                require(!unconfSignIn.password.isEnabled(), "unconfigured password field disabled");

                // 2. Configured mode
                DesktopConfig config = DesktopConfig.fromEndpoint("http://127.0.0.1:8080");
                HttpAuthenticationClient configuredClient = new HttpAuthenticationClient(config);
                JPanel configuredRoot = DesktopMain.createContent(configuredClient);
                SignInView confSignIn = findComponent(configuredRoot, SignInView.class);
                require(confSignIn != null, "SignInView present in configured mode");
                require(confSignIn.signIn.isEnabled(), "configured signIn button enabled");
                require("Sign in".equals(confSignIn.signIn.getText()), "configured button label is 'Sign in'");
                require(confSignIn.email.isEnabled(), "configured email field enabled");
                require(confSignIn.password.isEnabled(), "configured password field enabled");

                // 3. WorkspaceView role adaptation: Administrator
                StaffSession adminSession = new StaffSession(ADMIN_ID, COMPANY_ID, "ADMINISTRATOR", "admin@smartvrms.invalid", Set.of("VIEW_FLEET"));
                WorkspaceView adminWorkspace = new WorkspaceView(() -> {}, adminSession);
                require(adminWorkspace.session().isAdministrator(), "admin workspace session active");
                adminWorkspace.showPage("Personnel");
                require(findText(adminWorkspace, "Administrator Workspace"), "admin sees personnel workspace");

                // 4. WorkspaceView role adaptation: Employee (FR-ACC-001, FR-ACC-003)
                StaffSession empSession = new StaffSession(EMPLOYEE_ID, COMPANY_ID, "EMPLOYEE", "employee@smartvrms.invalid", Set.of("VIEW_FLEET"));
                WorkspaceView empWorkspace = new WorkspaceView(() -> {}, empSession);
                require(empWorkspace.session().isEmployee(), "employee workspace session active");
                empWorkspace.showPage("Personnel");
                require(findText(empWorkspace, "Access Restricted to Administrators"), "employee sees restricted card on Personnel");
                empWorkspace.showPage("Finance");
                require(findText(empWorkspace, "Access Restricted to Administrators"), "employee sees restricted card on Finance");

                // 5. Dynamic session transition & sign-out
                WorkspaceView dynamicWorkspace = new WorkspaceView(() -> {});
                require(dynamicWorkspace.session() == null, "starts in preview");
                dynamicWorkspace.setSession(adminSession);
                require(dynamicWorkspace.session() != null && dynamicWorkspace.session().isAdministrator(), "session updated to admin");
                dynamicWorkspace.setSession(null);
                require(dynamicWorkspace.session() == null, "signed out session cleared");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T findComponent(Container parent, Class<T> clazz) {
        if (clazz.isInstance(parent)) return (T) parent;
        for (Component child : parent.getComponents()) {
            if (clazz.isInstance(child)) return (T) child;
            if (child instanceof Container c) {
                T found = findComponent(c, clazz);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean findText(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel l && text.equals(l.getText())) return true;
            if (child instanceof JTextArea t && t.getText().contains(text)) return true;
            if (child instanceof AbstractButton b && text.equals(b.getText())) return true;
            if (child instanceof Container c) {
                if (findText(c, text)) return true;
            }
        }
        return false;
    }
}
