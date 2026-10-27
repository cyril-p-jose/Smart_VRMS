package in.smartvrms.server.setup;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Interactive setup diagnostic only. Never used by the application runtime. */
public final class DatabaseReadinessMain {
    public static boolean validHost(String host) {
        return host != null && host.matches("aws-[0-9]+-ap-northeast-1\\.pooler\\.supabase\\.com");
    }

    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) {
            System.err.println("Open Windows PowerShell directly and run scripts/check-database.ps1. A private password prompt requires a console.");
            System.exit(2);
        }
        // Driver diagnostics must not expose connection details or server responses.
        Logger.getLogger("org.postgresql").setLevel(Level.OFF);
        char[] password = null;
        Properties properties = new Properties();
        try {
            String host = console.readLine("Session pooler HOST only (from Connect, port 5432): ");
            if (host == null || !validHost(host.trim())) {
                System.err.println("Expected the ap-northeast-1 Session pooler hostname only. Do not paste a URL or password.");
                System.exit(2);
            }
            Path certificate = Path.of("local-config", "supabase-ca.crt").toAbsolutePath();
            if (!Files.isRegularFile(certificate)) {
                System.err.println("Download the database root certificate to local-config/supabase-ca.crt first.");
                System.exit(2);
            }
            password = console.readPassword("Database password (hidden; not saved): ");
            if (password == null || password.length == 0) {
                System.err.println("No password supplied. No connection attempted.");
                System.exit(2);
            }
            properties.setProperty("user", "postgres.wvtnepxynjjbazczkukh");
            properties.setProperty("password", new String(password));
            properties.setProperty("sslmode", "verify-full");
            properties.setProperty("sslrootcert", certificate.toString());
            properties.setProperty("connectTimeout", "10");
            properties.setProperty("socketTimeout", "15");
            properties.setProperty("loginTimeout", "20");
            properties.setProperty("ApplicationName", "SmartVRMS-Readiness");
            String url = "jdbc:postgresql://" + host.trim() + ":5432/postgres";
            try (Connection connection = DriverManager.getConnection(url, properties)) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    try (ResultSet result = statement.executeQuery("SELECT current_database() = 'postgres', current_user = 'postgres', current_setting('transaction_read_only') = 'on'")) {
                        if (!result.next() || !result.getBoolean(1) || !result.getBoolean(2) || !result.getBoolean(3)) {
                            throw new SQLException("Unexpected database context", "VR001");
                        }
                    }
                } finally { connection.rollback(); }
            }
            System.out.println("Database connection: PASS");
            System.out.println("TLS certificate and hostname verification: PASS");
            System.out.println("Read-only transaction: PASS");
            System.out.println("No schema or business data changed. Application role, membership and RLS: not configured.");
        } catch (SQLException ex) {
            String state = ex.getSQLState();
            String category = "28P01".equals(state) ? "Password rejected. Use the database password, not your Supabase account password or API key."
                    : "VR001".equals(state) ? "Unexpected database/user/read-only context. Check the Session pooler selection."
                    : "Connection failed. Check the copied host, certificate, project status, password and network. Do not disable TLS verification.";
            System.err.println(category);
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("Setup check could not complete. Check the local certificate file and follow SETUP-04.");
            System.exit(2);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            properties.remove("password");
        }
    }
}
