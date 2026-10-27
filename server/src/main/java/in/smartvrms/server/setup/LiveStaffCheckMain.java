package in.smartvrms.server.setup;

import in.smartvrms.server.integration.*;
import in.smartvrms.server.security.*;
import in.smartvrms.server.domain.JdbcFleetService;
import in.smartvrms.contracts.StaffSession;
import org.postgresql.ds.PGSimpleDataSource;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

/** Private console diagnostic: verifies real staff Auth + restricted DB without business writes. */
public final class LiveStaffCheckMain {
    public static void main(String[] args) {
        int result = run();
        if (result != 0) System.exit(result);
    }
    private static int run() {
        var console = System.console();
        if (console == null) { System.err.println("Open Windows PowerShell directly for private prompts."); return 2; }
        Logger.getLogger("org.postgresql").setLevel(Level.OFF);
        char[] databasePassword = null, staffPassword = null;
        SupabaseAuth auth = null;
        SupabaseAuth.AuthTokens tokens = null;
        PGSimpleDataSource database = new PGSimpleDataSource();
        String step = "public configuration";
        try {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("local-config/supabase-public.properties"))) { properties.load(reader); }
            var config = SupabaseConfig.from(properties);
            Path certificate = Path.of("local-config/supabase-ca.crt").toAbsolutePath();
            if (!Files.isRegularFile(certificate)) throw new IllegalStateException();
            databasePassword = console.readPassword("Server-role DATABASE password (hidden; not postgres password): ");
            String email = console.readLine("Your confirmed staff email (not saved): ");
            staffPassword = console.readPassword("Staff sign-in password (hidden): ");
            if (databasePassword == null || databasePassword.length == 0 || staffPassword == null || staffPassword.length == 0
                    || email == null || email.isBlank()) throw new IllegalArgumentException();
            database.setServerNames(new String[]{"aws-0-ap-northeast-1.pooler.supabase.com"});
            database.setPortNumbers(new int[]{5432}); database.setDatabaseName("postgres");
            database.setUser("smart_vrms_api.wvtnepxynjjbazczkukh"); database.setPassword(new String(databasePassword));
            database.setSslMode("verify-full"); database.setSslRootCert(certificate.toString());
            database.setConnectTimeout(10); database.setSocketTimeout(15); database.setLoginTimeout(20);
            step = "staff authentication";
            auth = new SupabaseAuth(config);
            tokens = auth.signInWithPassword(email.strip(), new String(staffPassword));
            System.out.println("Live staff authentication: PASS");
            step = "membership and restricted database";
            var staff = new StaffAuthorizer(auth,database).authorize(tokens.accessToken(),Capability.VIEW_FLEET);
            System.out.println("Live active membership / restricted role / TLS: PASS");
            step = "in-transaction membership and fleet query";
            var session = new StaffSession(staff.userId(),staff.companyId(),staff.role(),"",Set.of());
            var records = new JdbcFleetService(database,null).listVehicles(session,null,null);
            System.out.println("Live in-transaction membership / fleet read: PASS");
            System.out.println("Role: " + staff.role());
            System.out.println("Fleet rows readable: " + records.size());
            System.out.println("No booking, payment, staff or fleet records were changed.");
            return 0;
        } catch (Exception ex) {
            System.err.println("Live check failed at: " + step + ". No raw provider/database error or credentials are printed.");
            System.err.println("Check that stage's account/password/configuration and share only this sanitized result.");
            return 2;
        } finally {
            if (auth != null && tokens != null) try { auth.logout(tokens.accessToken()); } catch (RuntimeException ignored) { }
            if (databasePassword != null) Arrays.fill(databasePassword,'\0');
            if (staffPassword != null) Arrays.fill(staffPassword,'\0');
            database.setPassword("");
        }
    }
}
