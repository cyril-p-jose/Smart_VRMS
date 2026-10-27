package in.smartvrms.server.setup;

import org.postgresql.PGConnection;
import java.io.Console;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.*;

/** Operator-invoked provisioning; no credential is written to disk or sent to the desktop. */
public final class RuntimeRoleSetupMain {
    private static final String HOST = "aws-0-ap-northeast-1.pooler.supabase.com";
    private static Connection connect(String role, char[] password) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", role + ".wvtnepxynjjbazczkukh");
        p.setProperty("password", new String(password));
        p.setProperty("sslmode", "verify-full");
        p.setProperty("sslrootcert", Path.of("local-config/supabase-ca.crt").toAbsolutePath().toString());
        p.setProperty("connectTimeout", "10"); p.setProperty("socketTimeout", "15"); p.setProperty("loginTimeout", "20");
        p.setProperty("ApplicationName", "SmartVRMS-RoleSetup");
        try { return DriverManager.getConnection("jdbc:postgresql://" + HOST + ":5432/postgres", p); }
        finally { p.remove("password"); }
    }
    private static void require(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(10);
            try (ResultSet r = s.executeQuery(sql)) {
                if (!r.next() || !r.getBoolean(1)) throw new SQLException("Readiness assertion failed", "VR001");
            }
        }
    }
    public static void main(String[] args) {
        int result = run(args);
        if (result != 0) System.exit(result);
    }
    private static int run(String[] args) {
        Console console = System.console();
        if (console == null) { System.err.println("Run from Windows PowerShell directly; private password prompts require a console."); return 2; }
        boolean checkOnly = args.length == 1 && args[0].equals("--check-only");
        if (args.length > 0 && !checkOnly) { System.err.println("Unsupported option."); return 2; }
        Logger.getLogger("org.postgresql").setLevel(Level.OFF);
        char[] operator = null, runtime = null, repeated = null;
        boolean committed = false;
        try {
            if (!Files.isRegularFile(Path.of("local-config/supabase-ca.crt"))) {
                System.err.println("Missing local-config/supabase-ca.crt. Complete certificate setup first."); return 2;
            }
            if (!checkOnly) operator = console.readPassword("Existing DATABASE administrator password (hidden): ");
            runtime = console.readPassword(checkOnly ? "Server-role password (hidden): " : "NEW server-role password, at least 20 characters (hidden; retain in your password manager): ");
            if (runtime == null || runtime.length < 20 || (!checkOnly && (operator == null || operator.length == 0))) {
                System.err.println("Password entry missing or server-role password shorter than 20 characters."); return 2;
            }
            if (!checkOnly) {
                repeated = console.readPassword("Repeat NEW server-role password (hidden): ");
                if (!Arrays.equals(runtime, repeated) || Arrays.equals(runtime, operator)) {
                    System.err.println("New password must match its confirmation and differ from the administrator password."); return 2;
                }
                try (Connection c = connect("postgres", operator)) {
                    c.setAutoCommit(false);
                    try {
                        require(c, "SELECT current_user = 'postgres'");
                        require(c, "SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolinherit FROM pg_roles WHERE rolname = 'smart_vrms_api'");
                        // Approved driver's SCRAM helper, rather than string-building password SQL.
                        // pgJDBC wipes its input; keep the original for the separate login check.
                        c.unwrap(PGConnection.class).alterUserPassword("smart_vrms_api", runtime.clone(), "scram-sha-256");
                        try (Statement s = c.createStatement()) {
                            s.setQueryTimeout(10);
                            s.execute("ALTER ROLE smart_vrms_api LOGIN CONNECTION LIMIT 5");
                        }
                        c.commit(); committed = true;
                    } finally { if (!committed) c.rollback(); }
                }
                System.out.println("Restricted role provisioned. Retain its password privately; it was not saved locally.");
            }
            try (Connection c = connect("smart_vrms_api", runtime)) {
                c.setReadOnly(true); c.setAutoCommit(false);
                try {
                    require(c, "SELECT current_user = 'smart_vrms_api' AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolinherit FROM pg_roles WHERE rolname = current_user");
                    require(c, "SELECT NOT has_schema_privilege(current_user, 'vrms_private', 'CREATE') AND NOT has_schema_privilege(current_user, 'public', 'CREATE') AND NOT has_database_privilege(current_user, current_database(), 'CREATE')");
                    require(c, "SELECT NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member = current_user::regrole)");
                    require(c, "SELECT NOT EXISTS (SELECT 1 FROM vrms_private.memberships) AND NOT EXISTS (SELECT 1 FROM vrms_private.companies)");
                    try (PreparedStatement s = c.prepareStatement("SELECT set_config('vrms.user_id', ?, true), set_config('vrms.company_id', ?, true)")) {
                        s.setQueryTimeout(10); s.setString(1, UUID.randomUUID().toString()); s.setString(2, UUID.randomUUID().toString());
                        try (ResultSet ignored = s.executeQuery()) { }
                    }
                    require(c, "SELECT NOT EXISTS (SELECT 1 FROM vrms_private.memberships) AND NOT EXISTS (SELECT 1 FROM vrms_private.companies)");
                } finally { c.rollback(); }
                try {
                    require(c, "SELECT nullif(current_setting('vrms.user_id', true), '') IS NULL AND nullif(current_setting('vrms.company_id', true), '') IS NULL");
                } finally { c.rollback(); }
            }
            System.out.println("SETUP-07 restricted login, verified TLS, no-identity denial and rollback context cleanup: PASS");
            return 0;
        } catch (Exception ex) {
            System.err.println(committed || checkOnly
                ? "Runtime check failed. Keep the server-role password and retry with -CheckOnly. Do not rerun provisioning or weaken TLS."
                : "Provisioning not confirmed. Check the database password, certificate, project connectivity and that the role is not already enabled. If it may already be enabled, use -CheckOnly.");
            return 2;
        } finally {
            for (char[] value : new char[][]{operator, runtime, repeated}) if (value != null) Arrays.fill(value, '\0');
        }
    }
}
