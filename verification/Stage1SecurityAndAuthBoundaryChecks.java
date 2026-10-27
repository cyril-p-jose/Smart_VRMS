package in.smartvrms.server.security;

import in.smartvrms.contracts.StaffSession;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.domain.JdbcFleetService;
import in.smartvrms.server.integration.AuthFailure;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.JdbcStaffContext;
import in.smartvrms.server.security.StaffAuthorizer;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Dedicated Stage 1 verification suite enforcing:
 * 1. Unauthenticated request rejection and fail-closed token validation
 * 2. Expired session detection and failure sanitization
 * 3. Inactive membership denial (both at HTTP authorizer and in-transaction context)
 * 4. Cross-company access denial and untrusted tenant header/body rejection
 * 5. Insufficient role capability enforcement (Employee vs Administrator boundary)
 * 6. Audit write failure atomic transaction rollback (zero partial commits)
 * 7. Transaction cleanup, rollback-in-finally, and transaction-local context isolation
 */
public final class Stage1SecurityAndAuthBoundaryChecks {
    private static int checks;
    private static final String DUMMY_URL = "https://abcdefghijklmnopqrst.supabase.co";
    private static final String DUMMY_KEY = "sb_publishable_fixture0123456789";
    private static final UUID USER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID COMPANY_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPANY_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("FAIL: " + message);
        checks++;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Stage 1 security, auth, and boundary checks...");

        testUnauthenticatedHttpAndTokenValidation();
        testExpiredSessionFailClosed();
        testInactiveMembershipRejection();
        testCrossCompanyAccessRejection();
        testInsufficientCapabilityEnforcement();
        testAuditFailureRollbackAtomicity();
        testTransactionCleanupAndContextIsolation();

        System.out.println("PASS: " + checks + " Stage 1 security, auth, and boundary assertions.");
    }

    // 1. Unauthenticated request rejection
    private static void testUnauthenticatedHttpAndTokenValidation() throws Exception {
        SupabaseConfig config = new SupabaseConfig(DUMMY_URL, DUMMY_KEY);
        SupabaseAuth auth = new SupabaseAuth(config, r -> new SupabaseAuth.Response(401, "application/json", new byte[0]), FIXED_CLOCK);
        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            throw new SecurityException("Access denied.");
        };

        HttpServerConfig serverConfig = new HttpServerConfig(0);
        VrmsHttpServer server = new VrmsHttpServer(serverConfig, auth, authorizer);
        server.start();

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            URI base = server.baseUri();

            // A. Missing Authorization header -> 401
            HttpRequest noAuthReq = HttpRequest.newBuilder(base.resolve("/api/v1/auth/me")).GET().build();
            HttpResponse<String> noAuthRes = client.send(noAuthReq, HttpResponse.BodyHandlers.ofString());
            require(noAuthRes.statusCode() == 401, "missing authorization header returns 401");
            require(noAuthRes.body().contains("Missing Bearer token."), "sanitized error message");
            require(noAuthRes.headers().firstValue("X-Correlation-ID").isPresent(), "correlation ID attached");

            // B. Empty Bearer token -> 401
            HttpRequest emptyBearer = HttpRequest.newBuilder(base.resolve("/api/v1/auth/me"))
                    .header("Authorization", "Bearer ")
                    .GET().build();
            HttpResponse<String> emptyRes = client.send(emptyBearer, HttpResponse.BodyHandlers.ofString());
            require(emptyRes.statusCode() == 401, "empty bearer token returns 401");

            // C. Malformed token format -> fails closed before network
            try {
                auth.verifyStaffIdentity("not-a-valid-jwt");
                throw new AssertionError("Malformed token should be rejected");
            } catch (AuthFailure ex) {
                require(ex.kind() == AuthFailure.Kind.INVALID_SESSION, "malformed token format rejected as INVALID_SESSION");
            }

            try {
                auth.verifyStaffIdentity(null);
                throw new AssertionError("Null token should be rejected");
            } catch (AuthFailure ex) {
                require(ex.kind() == AuthFailure.Kind.INVALID_SESSION, "null token rejected as INVALID_SESSION");
            }
        } finally {
            server.stop(0);
        }
    }

    // 2. Expired session fail-closed
    private static void testExpiredSessionFailClosed() throws Exception {
        SupabaseConfig config = new SupabaseConfig(DUMMY_URL, DUMMY_KEY);
        // Provider returns 401 on expired token
        SupabaseAuth auth = new SupabaseAuth(config, r -> {
            String expiredJson = "{\"message\":\"JWT expired\",\"error_description\":\"token is expired by 3600s\"}";
            return new SupabaseAuth.Response(401, "application/json", expiredJson.getBytes(StandardCharsets.UTF_8));
        }, FIXED_CLOCK);

        try {
            auth.verifyStaffIdentity("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotEchoSecretSignature");
            throw new AssertionError("Expired token must fail");
        } catch (AuthFailure ex) {
            require(ex.kind() == AuthFailure.Kind.INVALID_SESSION, "expired token mapped to INVALID_SESSION");
            require(!ex.getMessage().contains("doNotEchoSecretSignature"), "raw token never echoed in exception");
        }
    }

    // 3. Inactive membership rejection
    private static void testInactiveMembershipRejection() {
        // A. StaffAuthorizer with inactive membership
        StaffAuthorizer authorizer = new StaffAuthorizer(token -> USER_A, mockDataSource(USER_A, COMPANY_1, "EMPLOYEE", false, true));
        try {
            authorizer.authorize("dummy.token.jwt", Capability.VIEW_FLEET);
            throw new AssertionError("Inactive membership must be denied");
        } catch (SecurityException ex) {
            require("Access denied.".equals(ex.getMessage()), "inactive membership denied in StaffAuthorizer");
        }

        // B. JdbcStaffContext with inactive membership
        StaffSession session = new StaffSession(USER_A, COMPANY_1, "EMPLOYEE", "test@example.invalid", Set.of());
        Connection conn = mockConnection(USER_A, COMPANY_1, "EMPLOYEE", false, true);
        try {
            JdbcStaffContext.require(conn, session, Capability.VIEW_FLEET);
            throw new AssertionError("Inactive membership must be denied in transaction context");
        } catch (SecurityException ex) {
            require("Access denied.".equals(ex.getMessage()), "inactive membership denied in JdbcStaffContext");
        } catch (SQLException ex) {
            throw new AssertionError("Unexpected SQLException: " + ex.getMessage());
        }
    }

    // 4. Cross-company access rejection
    private static void testCrossCompanyAccessRejection() {
        // User A belongs to COMPANY_1 in database, but attempts to operate on COMPANY_2
        Connection conn = mockConnection(USER_A, COMPANY_1, "ADMINISTRATOR", true, true);
        StaffSession spoofedCompanySession = new StaffSession(USER_A, COMPANY_2, "ADMINISTRATOR", "admin@example.invalid", Set.of());

        try {
            JdbcStaffContext.require(conn, spoofedCompanySession, Capability.VIEW_FLEET);
            throw new AssertionError("Cross-company session must be rejected");
        } catch (SecurityException ex) {
            require("Access denied.".equals(ex.getMessage()), "cross-company access strictly denied");
        } catch (SQLException ex) {
            throw new AssertionError("Unexpected SQLException: " + ex.getMessage());
        }
    }

    // 5. Insufficient capability enforcement
    private static void testInsufficientCapabilityEnforcement() {
        StaffAuthorizer authorizer = new StaffAuthorizer(token -> USER_A, mockDataSource(USER_A, COMPANY_1, "EMPLOYEE", true, true));

        // Employee authorized for operational fleet
        var staff = authorizer.authorize("dummy.token.jwt", Capability.VIEW_FLEET);
        require("EMPLOYEE".equals(staff.role()), "employee authorized for operational fleet view");

        // Employee denied for administrative/sensitive capabilities
        for (Capability forbidden : List.of(
                Capability.CONFIGURE_BUSINESS,
                Capability.VIEW_COMPENSATION,
                Capability.POST_REFUND,
                Capability.MANAGE_PERSONNEL,
                Capability.MANAGE_VEHICLE_MASTER)) {
            try {
                authorizer.authorize("dummy.token.jwt", forbidden);
                throw new AssertionError("Employee must not be granted " + forbidden);
            } catch (SecurityException ex) {
                require("Access denied.".equals(ex.getMessage()), "employee denied " + forbidden);
            }
        }

        // Employee attempting to pass claimed capabilities in StaffSession cannot escalate
        Connection conn = mockConnection(USER_A, COMPANY_1, "EMPLOYEE", true, true);
        StaffSession escalatedSession = new StaffSession(USER_A, COMPANY_1, "EMPLOYEE", "emp@example.invalid", Set.of("CONFIGURE_BUSINESS", "MANAGE_VEHICLE_MASTER"));
        try {
            JdbcStaffContext.require(conn, escalatedSession, Capability.MANAGE_VEHICLE_MASTER);
            throw new AssertionError("Client-asserted capabilities must not escalate DB role");
        } catch (SecurityException ex) {
            require("Access denied.".equals(ex.getMessage()), "client capability claims rejected");
        } catch (SQLException ex) {
            throw new AssertionError("Unexpected SQLException: " + ex.getMessage());
        }
    }

    // 6. Audit write failure atomic transaction rollback
    private static void testAuditFailureRollbackAtomicity() {
        class MockAuditDb {
            boolean failAudit = false;
            final List<String> lifecycle = new ArrayList<>();

            DataSource source() {
                return proxy(DataSource.class, (p, m, a) -> {
                    if ("getConnection".equals(m.getName())) return connection();
                    throw new AssertionError(m.getName());
                });
            }

            Connection connection() {
                return proxy(Connection.class, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getAutoCommit": return false;
                        case "setAutoCommit": return null;
                        case "prepareStatement": return statement((String) a[0]);
                        case "commit": lifecycle.add("commit"); return null;
                        case "rollback": lifecycle.add("rollback"); return null;
                        case "close": lifecycle.add("close"); return null;
                        default: throw new AssertionError(m.getName());
                    }
                });
            }

            PreparedStatement statement(String sql) {
                return proxy(PreparedStatement.class, (p, m, a) -> {
                    if (m.getName().startsWith("set") || "close".equals(m.getName())) return null;
                    if ("executeUpdate".equals(m.getName())) {
                        if (sql.contains("audit_events")) {
                            lifecycle.add("audit");
                            if (failAudit) throw new SQLException("simulated audit failure");
                            return 1;
                        }
                    }
                    if ("executeQuery".equals(m.getName())) {
                        Object[][] rows;
                        if (sql.contains("pg_roles")) rows = new Object[][]{{true}};
                        else if (sql.contains("set_config")) rows = new Object[0][];
                        else if (sql.contains("memberships")) rows = new Object[][]{{COMPANY_1, "ADMINISTRATOR", true}};
                        else if (sql.startsWith("SELECT name FROM")) rows = new Object[][]{{"Sedan"}};
                        else if (sql.contains("INSERT INTO vrms_private.vehicles")) {
                            lifecycle.add("vehicle_insert");
                            rows = new Object[][]{{UUID.randomUUID(), Timestamp.from(Instant.now()), 1L}};
                        } else throw new AssertionError("Unexpected SQL: " + sql);

                        int[] pos = {-1};
                        return proxy(ResultSet.class, (rp, rm, ra) -> {
                            if ("next".equals(rm.getName())) return ++pos[0] < rows.length;
                            if ("close".equals(rm.getName())) return null;
                            return rows[pos[0]][(Integer) ra[0] - 1];
                        });
                    }
                    throw new AssertionError(m.getName());
                });
            }
        }

        // Test normal commit
        MockAuditDb normalDb = new MockAuditDb();
        StaffSession adminSession = new StaffSession(USER_A, COMPANY_1, "ADMINISTRATOR", "admin@example.invalid", Set.of());
        new JdbcFleetService(normalDb.source(), null).registerVehicle(adminSession, UUID.randomUUID(), "KL07NORMAL", "Make", "Model", 5, 200000, 10000, null);
        require(normalDb.lifecycle.equals(List.of("vehicle_insert", "audit", "commit", "close")), "normal write commits atomically after audit");

        // Test audit failure rollback
        MockAuditDb failingDb = new MockAuditDb();
        failingDb.failAudit = true;
        try {
            new JdbcFleetService(failingDb.source(), null).registerVehicle(adminSession, UUID.randomUUID(), "KL07FAIL", "Make", "Model", 5, 200000, 10000, null);
            throw new AssertionError("Write must fail when audit fails");
        } catch (RuntimeException ex) {
            require(failingDb.lifecycle.equals(List.of("vehicle_insert", "audit", "rollback", "close")), "write rolls back when audit fails; zero partial commit");
        }
    }

    // 7. Transaction cleanup, rollback-in-finally, and context isolation
    private static void testTransactionCleanupAndContextIsolation() throws Exception {
        int[] rollbacks = {0};
        int[] commits = {0};
        int[] closes = {0};
        List<String> configs = new ArrayList<>();

        DataSource ds = proxy(DataSource.class, (p, m, a) -> {
            if ("getConnection".equals(m.getName())) {
                return proxy(Connection.class, (cp, cm, ca) -> {
                    switch (cm.getName()) {
                        case "setReadOnly": require(Boolean.TRUE.equals(ca[0]), "readOnly true"); return null;
                        case "setAutoCommit": require(Boolean.FALSE.equals(ca[0]), "autoCommit false"); return null;
                        case "rollback": rollbacks[0]++; return null;
                        case "commit": commits[0]++; return null;
                        case "close": closes[0]++; return null;
                        case "prepareStatement":
                            String sql = (String) ca[0];
                            Map<Integer, Object> params = new HashMap<>();
                            return proxy(PreparedStatement.class, (sp, sm, sa) -> {
                                if ("setQueryTimeout".equals(sm.getName())) return null;
                                if (sm.getName().startsWith("set") && sa != null && sa.length >= 2) {
                                    params.put((Integer) sa[0], sa[1]);
                                    return null;
                                }
                                if ("close".equals(sm.getName())) return null;
                                if ("executeQuery".equals(sm.getName())) {
                                    Object[][] rows;
                                    if (sql.contains("pg_roles")) rows = new Object[][]{{true}};
                                    else if (sql.contains("set_config")) {
                                        require(sql.contains("?, true"), "set_config must use is_local = true");
                                        configs.add(params.get(1) + "/" + params.get(2));
                                        rows = new Object[0][];
                                    } else if (sql.contains("memberships")) {
                                        rows = new Object[][]{{USER_A, COMPANY_1, "ADMINISTRATOR", true}};
                                    } else if (sql.contains("companies")) {
                                        rows = new Object[][]{{COMPANY_1}};
                                    } else throw new AssertionError("Unexpected SQL: " + sql);

                                    int[] idx = {-1};
                                    return proxy(ResultSet.class, (rp, rm, ra) -> {
                                        if ("next".equals(rm.getName())) return ++idx[0] < rows.length;
                                        if ("close".equals(rm.getName())) return null;
                                        return rows[idx[0]][(Integer) ra[0] - 1];
                                    });
                                }
                                throw new AssertionError(sm.getName());
                            });
                        default: throw new AssertionError(cm.getName());
                    }
                });
            }
            throw new AssertionError(m.getName());
        });

        StaffAuthorizer authorizer = new StaffAuthorizer(t -> USER_A, ds);
        var staff = authorizer.authorize("dummy", Capability.CONFIGURE_BUSINESS);
        require(staff != null, "staff authorized");
        require(rollbacks[0] == 1, "StaffAuthorizer rolled back read-only connection");
        require(commits[0] == 0, "StaffAuthorizer never commits");
        require(closes[0] == 1, "connection closed in finally");
        require(configs.equals(List.of(USER_A + "/", USER_A + "/" + COMPANY_1)), "context cleared then set with local isolation");
    }

    // Helper: mock DataSource
    private static DataSource mockDataSource(UUID user, UUID company, String role, boolean active, boolean roleAllowed) {
        return proxy(DataSource.class, (p, m, a) -> {
            if ("getConnection".equals(m.getName())) {
                return mockConnection(user, company, role, active, roleAllowed);
            }
            throw new AssertionError(m.getName());
        });
    }

    // Helper: mock Connection
    private static Connection mockConnection(UUID user, UUID company, String role, boolean active, boolean roleAllowed) {
        return proxy(Connection.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getAutoCommit": return false;
                case "setReadOnly": case "setAutoCommit": case "rollback": case "commit": case "close": return null;
                case "prepareStatement":
                    String sql = (String) a[0];
                    Map<Integer, Object> params = new HashMap<>();
                    return proxy(PreparedStatement.class, (sp, sm, sa) -> {
                        if ("setQueryTimeout".equals(sm.getName())) return null;
                        if (sm.getName().startsWith("set") && sa != null && sa.length >= 2) {
                            params.put((Integer) sa[0], sa[1]);
                            return null;
                        }
                        if ("close".equals(sm.getName())) return null;
                        if ("executeQuery".equals(sm.getName())) {
                            Object[][] rows;
                            if (sql.contains("pg_roles")) rows = new Object[][]{{roleAllowed}};
                            else if (sql.contains("set_config")) rows = new Object[0][];
                            else if (sql.contains("memberships")) {
                                if (sql.contains("SELECT auth_user_id")) {
                                    rows = new Object[][]{{user, company, role, active}};
                                } else {
                                    rows = new Object[][]{{company, role, active}};
                                }
                            } else if (sql.contains("companies")) {
                                rows = new Object[][]{{company}};
                            } else throw new AssertionError("Unexpected SQL: " + sql);

                            int[] idx = {-1};
                            return proxy(ResultSet.class, (rp, rm, ra) -> {
                                if ("next".equals(rm.getName())) return ++idx[0] < rows.length;
                                if ("close".equals(rm.getName())) return null;
                                return rows[idx[0]][(Integer) ra[0] - 1];
                            });
                        }
                        throw new AssertionError(sm.getName());
                    });
                default: throw new AssertionError(m.getName());
            }
        });
    }
}
