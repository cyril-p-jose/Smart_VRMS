package in.smartvrms.server.setup;

import in.smartvrms.server.integration.*;
import java.nio.file.*;
import java.util.Properties;

/** Read-only diagnostics: never creates users, signs in, or changes provider configuration. */
public final class AuthReadinessMain {
    public static void main(String[] args) {
        try {
            Path file=Path.of("local-config","supabase-public.properties");
            Properties properties=new Properties();try(var reader=Files.newBufferedReader(file)){properties.load(reader);}
            var settings=new SupabaseAuth(SupabaseConfig.from(properties)).settings();
            System.out.println("Public Auth settings reachable: yes");
            System.out.println("Public signup disabled: "+settings.signupDisabled());
            System.out.println("Email confirmation required: "+settings.emailConfirmationRequired());
            System.out.println("Anonymous sign-in, region and Session pooler: verify in Dashboard.");
            System.out.println("User authentication, membership, RLS and database connection: not tested.");
            if(!settings.signupDisabled()||!settings.emailConfirmationRequired())System.exit(2);
        } catch(AuthFailure ex) { System.err.println(ex.getMessage());System.exit(2); }
        catch(Exception ex) { System.err.println("Readiness check could not load valid local public configuration. Run scripts/configure-supabase.ps1.");System.exit(2); }
    }
}
