import org.postgresql.PGConnection;
import java.lang.reflect.*;
import java.sql.Statement;
import java.util.Arrays;

/** Exercises the actual approved driver's password helper without any network/database. */
public final class RuntimePasswordChecks {
    public static void main(String[] args) throws Exception {
        char[] retained = "Fixture-only-password-quote'123".toCharArray();
        char[] supplied = retained.clone();
        String[] command = {null};
        Statement statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (p,m,a) -> {
            if (m.getName().equals("execute")) { command[0] = (String)a[0]; return false; }
            if (m.getName().equals("close")) return null;
            throw new AssertionError(m.getName());
        });
        PGConnection connection = (PGConnection) Proxy.newProxyInstance(PGConnection.class.getClassLoader(), new Class<?>[]{PGConnection.class, java.sql.Connection.class}, (p,m,a) -> {
            if (m.isDefault()) return InvocationHandler.invokeDefault(p,m,a);
            if (m.getName().equals("createStatement")) return statement;
            throw new AssertionError(m.getName());
        });
        connection.alterUserPassword("smart_vrms_api", supplied, "scram-sha-256");
        if (command[0] == null || !command[0].contains("SCRAM-SHA-256$") || command[0].contains(new String(retained))) throw new AssertionError("Expected SCRAM verifier");
        if (!Arrays.equals(supplied, new char[supplied.length])) throw new AssertionError("Driver should wipe supplied password");
        if (retained[0] != 'F') throw new AssertionError("Separate login password should remain available");
        Arrays.fill(retained, '\0');
        command[0] = null;
        System.out.println("PASS: 3 approved-driver SCRAM/password-lifecycle checks. No provisioning or network request.");
    }
}
