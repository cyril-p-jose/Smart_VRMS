package in.smartvrms.server.security;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

/** JDBC protocol fixtures, not a live database/RLS substitute. */
public final class StaffAuthorizerChecks {
    static int checks;
    static void check(boolean value) { if (!value) throw new AssertionError(); checks++; }
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    static final class Database {
        final UUID user = UUID.randomUUID(), company = UUID.randomUUID();
        boolean roleAllowed = true, active = true, present = true, companyPresent = true, duplicate, wrongUser, failQuery, failRollback;
        String role = "ADMINISTRATOR";
        int opens, rollbacks, closes, commits;
        final List<String> contexts = new ArrayList<>();
        DataSource source() { return proxy(DataSource.class, (p,m,a) -> {
            if (m.getName().equals("getConnection")) { opens++; return connection(); }
            throw new AssertionError(m.getName());
        }); }
        Connection connection() { return proxy(Connection.class, (p,m,a) -> {
            switch (m.getName()) {
                case "setReadOnly": check(Boolean.TRUE.equals(a[0])); return null;
                case "setAutoCommit": check(Boolean.FALSE.equals(a[0])); return null;
                case "prepareStatement": return statement((String)a[0]);
                case "rollback": rollbacks++; if (failRollback) throw new SQLException("private database detail"); return null;
                case "commit": commits++; return null;
                case "close": closes++; return null;
                default: throw new AssertionError(m.getName());
            }
        }); }
        PreparedStatement statement(String sql) {
            Map<Integer,Object> parameters = new HashMap<>();
            return proxy(PreparedStatement.class, (p,m,a) -> {
                switch (m.getName()) {
                    case "setQueryTimeout": check((Integer)a[0] == 10); return null;
                    case "setString": case "setObject": parameters.put((Integer)a[0], a[1]); return null;
                    case "close": return null;
                    case "executeQuery":
                        if (failQuery) throw new SQLException("private database detail");
                        List<Object[]> data = new ArrayList<>();
                        if (sql.contains("pg_roles")) data.add(new Object[]{roleAllowed});
                        else if (sql.contains("set_config")) {
                            check(sql.contains("?, true"));
                            contexts.add(parameters.get(1) + "/" + parameters.get(2));
                        } else if (sql.contains("memberships")) {
                            check(user.equals(parameters.get(1)));
                            if (present) data.add(new Object[]{wrongUser ? UUID.randomUUID() : user, company, role, active});
                            if (duplicate && present) data.add(data.get(0));
                        } else if (sql.contains("companies")) {
                            check(company.equals(parameters.get(1)));
                            if (companyPresent) data.add(new Object[]{company});
                        } else throw new AssertionError(sql);
                        int[] index = {-1};
                        return proxy(ResultSet.class, (rp,rm,ra) -> {
                            switch (rm.getName()) {
                                case "next": return ++index[0] < data.size();
                                case "getBoolean": case "getString": case "getObject": return data.get(index[0])[(Integer)ra[0]-1];
                                case "close": return null;
                                default: throw new AssertionError(rm.getName());
                            }
                        });
                    default: throw new AssertionError(m.getName());
                }
            });
        }
        StaffAuthorizer authorizer() { return new StaffAuthorizer(token -> user, source()); }
        void cleaned() { check(rollbacks == 1 && closes == 1 && commits == 0); }
    }
    static void denied(Database db, Capability capability) {
        try { db.authorizer().authorize("fixture", capability); throw new AssertionError("Allowed"); }
        catch (SecurityException ex) { check(ex.getMessage().equals("Access denied.")); }
        db.cleaned();
    }
    public static void main(String[] args) {
        Database db = new Database();
        var result = db.authorizer().authorize("fixture", Capability.CONFIGURE_BUSINESS);
        check(result.companyId().equals(db.company) && result.userId().equals(db.user)); db.cleaned();
        check(db.contexts.equals(List.of(db.user + "/", db.user + "/" + db.company)));
        db = new Database(); db.role = "EMPLOYEE";
        check(db.authorizer().authorize("fixture", Capability.VIEW_FLEET).role().equals("EMPLOYEE")); db.cleaned();
        db = new Database(); db.role = "EMPLOYEE"; denied(db, Capability.POST_REFUND);
        db = new Database(); db.roleAllowed = false; denied(db, Capability.VIEW_FLEET); check(db.contexts.isEmpty());
        db = new Database(); db.present = false; denied(db, Capability.VIEW_FLEET);
        db = new Database(); db.active = false; denied(db, Capability.VIEW_FLEET);
        db = new Database(); db.role = "OWNER"; denied(db, Capability.VIEW_FLEET);
        db = new Database(); db.wrongUser = true; denied(db, Capability.VIEW_FLEET);
        db = new Database(); db.duplicate = true; denied(db, Capability.VIEW_FLEET);
        db = new Database(); db.companyPresent = false; denied(db, Capability.VIEW_FLEET);
        for (boolean rollbackFailure : new boolean[]{false, true}) {
            db = new Database(); db.failRollback = rollbackFailure; db.failQuery = !rollbackFailure;
            try { db.authorizer().authorize("fixture", Capability.VIEW_FLEET); throw new AssertionError(); }
            catch (IllegalStateException ex) { check(ex.getMessage().equals("Staff authorization is unavailable.") && ex.getCause() == null); }
            db.cleaned();
        }
        db = new Database();
        try { new StaffAuthorizer(token -> { throw new SecurityException("rejected"); }, db.source()).authorize("bad", Capability.VIEW_FLEET); throw new AssertionError(); }
        catch (SecurityException expected) { check(db.opens == 0); }
        System.out.println("PASS: " + checks + " staff authorization JDBC fixture assertions. Live login/context reuse remain pending.");
    }
}
