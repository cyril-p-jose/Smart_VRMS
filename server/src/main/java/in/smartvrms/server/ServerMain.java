package in.smartvrms.server;

import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.StaffAuthorizer;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/** Production/development entry point for the authenticated Java API server. Fail-closed without configuration. */
public final class ServerMain {
    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());

    private ServerMain() { }

    public static void main(String[] args) {
        int port = 8080;
        String portEnv = System.getenv("SMART_VRMS_PORT");
        if (portEnv == null || portEnv.isBlank()) {
            portEnv = System.getenv("PORT");
        }
        if (portEnv != null && !portEnv.isBlank()) {
            try { port = Integer.parseInt(portEnv.strip()); }
            catch (NumberFormatException ignored) { }
        }

        SupabaseConfig supabaseConfig = loadSupabaseConfig();
        if (supabaseConfig == null) {
            System.err.println("Smart VRMS API requires public Supabase configuration in local-config/supabase-public.properties.");
            System.exit(2);
            return;
        }

        DataSource dataSource = createRuntimeDataSource();
        if (dataSource == null) {
            System.err.println("Smart VRMS API database runtime connection is pending SETUP-07 restricted role credentials.");
            System.err.println("Please run scripts/setup-runtime-role.ps1 in a private terminal. Exiting (code 2).");
            System.exit(2);
            return;
        }

        String startupPhase = "restricted database connection";
        try {
            try(var connection=dataSource.getConnection()) {
                startupPhase = "restricted database role verification";
                connection.setReadOnly(true);connection.setAutoCommit(false);
                try(var query=connection.prepareStatement("SELECT current_user='smart_vrms_api' AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb FROM pg_roles WHERE rolname=current_user")) {
                    query.setQueryTimeout(10);
                    try(var row=query.executeQuery()){if(!row.next()||!row.getBoolean(1))throw new IllegalStateException("Restricted database role is required.");}
                } finally {connection.rollback();}
            }
            startupPhase = "API initialization";
            SupabaseAuth auth = new SupabaseAuth(supabaseConfig);
            StaffAuthorizer authorizer = new StaffAuthorizer(auth, dataSource);
            String serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY");
            var inviteProvider = in.smartvrms.server.security.JdbcStaffProvisioningService.createSupabaseInviteProvider(
                    supabaseConfig.endpoint(), serviceRoleKey);
            var provisioning = new in.smartvrms.server.security.JdbcStaffProvisioningService(dataSource, inviteProvider);
            String storageBaseUrl = supabaseConfig.endpoint().toString() + "/storage/v1";
            var fileStorage = new in.smartvrms.server.security.JdbcFileStorageService(dataSource, storageBaseUrl, serviceRoleKey);
            var auditService = new in.smartvrms.server.security.JdbcAuditService(dataSource);
            var idempotencyService = new in.smartvrms.server.security.JdbcIdempotencyService(dataSource);
            var customerService = new in.smartvrms.server.domain.JdbcCustomerService(dataSource, auditService);
            var bookingService = new in.smartvrms.server.domain.JdbcBookingService(dataSource, auditService, idempotencyService);
            var operationsService = new in.smartvrms.server.domain.JdbcOperationsService(dataSource, auditService);
            var financeService = new in.smartvrms.server.domain.JdbcFinanceService(dataSource, auditService);
            var automationService = new in.smartvrms.server.domain.JdbcAutomationService(dataSource, auditService);
            var reviewService = new in.smartvrms.server.domain.JdbcReviewService(dataSource, auditService);
            var reportingService = new in.smartvrms.server.domain.JdbcReportingService(dataSource, auditService);
            VrmsHttpServer server = new VrmsHttpServer(HttpServerConfig.fromEnvironment(System.getenv()), auth, authorizer::authorize,
                    fileStorage, auditService, idempotencyService, provisioning,
                    new in.smartvrms.server.domain.JdbcPersonnelService(dataSource, auditService),
                    new in.smartvrms.server.domain.JdbcFleetService(dataSource, auditService),
                    new in.smartvrms.server.domain.JdbcDriverService(dataSource, auditService),
                    customerService,
                    bookingService,
                    operationsService,
                    financeService,
                    automationService,
                    reviewService,
                    reportingService);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutdown signal received; stopping VrmsHttpServer...");
                server.stop(2);
            }));

            server.start();
            LOG.info("Smart VRMS API listening on " + server.baseUri());
        } catch (Exception ex) {
            System.err.println("Failed to start Smart VRMS API during " + startupPhase + safeFailureCategory(ex)
                    + ". Check the matching Render setting; credentials and provider error messages are not logged.");
            System.exit(1);
        }
    }

    /** Exposes enough diagnostic detail to distinguish connection, TLS, and authorization failures without leaking messages. */
    private static String safeFailureCategory(Throwable failure) {
        String sqlState = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof javax.net.ssl.SSLException) return " (TLS certificate or handshake failure)";
            if (current instanceof java.net.ConnectException) return " (connection refused)";
            if (current instanceof java.net.SocketTimeoutException) return " (connection timed out)";
            if (sqlState == null && current instanceof SQLException sqlException) {
                sqlState = sqlException.getSQLState();
            }
        }
        if (sqlState != null && !sqlState.isBlank()) return " (JDBC SQLSTATE " + sqlState + ")";
        if (failure instanceof SQLException) return " (JDBC failure)";
        return " (" + failure.getClass().getSimpleName() + ")";
    }

    private static SupabaseConfig loadSupabaseConfig() {
        Path configPath = Path.of("local-config/supabase-public.properties");
        if (Files.exists(configPath)) {
            try (InputStream in = new FileInputStream(configPath.toFile())) {
                Properties props = new Properties();
                props.load(in);
                return SupabaseConfig.from(props);
            } catch (Exception ignored) { }
        }
        String url = System.getenv("SUPABASE_URL");
        String key = System.getenv("SUPABASE_PUBLISHABLE_KEY");
        if (url != null && key != null) {
            try { return new SupabaseConfig(url, key); }
            catch (Exception ignored) { }
        }
        return null;
    }

    private static DataSource createRuntimeDataSource() {
        String dbPass = System.getenv("SMART_VRMS_DB_PASSWORD");
        if (dbPass == null || dbPass.isBlank()) {
            return null;
        }
        String host = System.getenv("SMART_VRMS_DB_HOST");
        if (host == null || host.isBlank()) host = "aws-0-ap-northeast-1.pooler.supabase.com";
        int port = 5432;
        String portStr = System.getenv("SMART_VRMS_DB_PORT");
        if (portStr != null) {
            try { port = Integer.parseInt(portStr.strip()); } catch (NumberFormatException ignored) {}
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{host});
        ds.setPortNumbers(new int[]{port});
        ds.setDatabaseName("postgres");
        String runtimeUser = System.getenv("SMART_VRMS_DB_USER");
        ds.setUser(runtimeUser == null || runtimeUser.isBlank()
                ? "smart_vrms_api.wvtnepxynjjbazczkukh" : runtimeUser);
        ds.setPassword(dbPass);
        ds.setSsl(true);
        ds.setSslMode("verify-full");
        String configuredCa = System.getenv("SMART_VRMS_DB_CA_PATH");
        Path caPath = Path.of(configuredCa == null || configuredCa.isBlank() ? "local-config/supabase-ca.crt" : configuredCa);
        if (Files.exists(caPath)) {
            ds.setSslRootCert(caPath.toAbsolutePath().toString());
        }
        ds.setConnectTimeout(10);
        ds.setSocketTimeout(15);
        ds.setLoginTimeout(20);
        return ds;
    }
}
