package in.smartvrms.server.security;

import in.smartvrms.contracts.StaffSession;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

/** JDBC boundary fixtures; live role/RLS acceptance remains a separate gate. */
public final class JdbcStaffContextChecks {
    private static int checks;
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); checks++; }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static final class Database {
        UUID user = UUID.randomUUID(), company = UUID.randomUUID();
        String role = "ADMINISTRATOR";
        boolean autoCommit, restricted = true, present = true, active = true, duplicate;
        int statements;
        List<List<String>> contexts = new ArrayList<>();
        StaffSession session() { return new StaffSession(user,company,"ADMINISTRATOR","",Set.of()); }
        Connection connection() { return proxy(Connection.class,(p,m,a) -> {
            if (m.getName().equals("getAutoCommit")) return autoCommit;
            if (m.getName().equals("prepareStatement")) { statements++; return statement((String)a[0]); }
            throw new AssertionError("Helper must not commit, roll back, close, or mutate connection ownership: " + m.getName());
        }); }
        PreparedStatement statement(String sql) {
            Map<Integer,Object> values = new HashMap<>();
            return proxy(PreparedStatement.class,(p,m,a) -> {
                switch (m.getName()) {
                    case "setQueryTimeout": check((Integer)a[0] == 10); return null;
                    case "setString": case "setObject": values.put((Integer)a[0],a[1]); return null;
                    case "close": return null;
                    case "executeQuery":
                        List<Object[]> data = new ArrayList<>();
                        if (sql.contains("pg_roles")) data.add(new Object[]{restricted});
                        else if (sql.contains("set_config")) {
                            check(sql.contains("?, true"));
                            contexts.add(List.of((String)values.get(1),(String)values.get(2),(String)values.get(3)));
                        } else if (sql.contains("memberships")) {
                            check(user.equals(values.get(1)));
                            if (present) data.add(new Object[]{company,role,active});
                            if (present && duplicate) data.add(data.get(0));
                        } else throw new AssertionError("Unexpected SQL");
                        int[] position={-1};
                        return proxy(ResultSet.class,(rp,rm,ra) -> {
                            switch (rm.getName()) {
                                case "next": return ++position[0] < data.size();
                                case "getObject": case "getBoolean": case "getString": return data.get(position[0])[(Integer)ra[0]-1];
                                case "close": return null;
                                default: throw new AssertionError(rm.getName());
                            }
                        });
                    default: throw new AssertionError(m.getName());
                }
            });
        }
    }
    private static void denied(Database db, StaffSession session, Capability capability) throws SQLException {
        try { JdbcStaffContext.require(db.connection(),session,capability); throw new AssertionError("Unauthorized context installed"); }
        catch (SecurityException expected) { check("Access denied.".equals(expected.getMessage())); }
        check(db.contexts.size() < 2); // No trusted company/role may be installed after denial.
    }
    public static void main(String[] args) throws Exception {
        Database db = new Database();
        JdbcStaffContext.require(db.connection(),db.session(),Capability.MANAGE_VEHICLE_MASTER);
        check(db.contexts.equals(List.of(List.of(db.user.toString(),"",""),List.of(db.user.toString(),db.company.toString(),"ADMINISTRATOR"))));
        db=new Database(); db.autoCommit=true;
        try { JdbcStaffContext.require(db.connection(),db.session(),Capability.VIEW_FLEET); throw new AssertionError(); }
        catch(SQLException expected) { check("VR002".equals(expected.getSQLState())); check(db.statements==0); }
        db=new Database(); db.restricted=false; denied(db,db.session(),Capability.VIEW_FLEET);
        db=new Database(); db.present=false; denied(db,db.session(),Capability.VIEW_FLEET);
        db=new Database(); db.active=false; denied(db,db.session(),Capability.VIEW_FLEET);
        db=new Database(); db.duplicate=true; denied(db,db.session(),Capability.VIEW_FLEET);
        db=new Database(); denied(db,new StaffSession(db.user,UUID.randomUUID(),"ADMINISTRATOR","",Set.of()),Capability.VIEW_FLEET);
        db=new Database(); db.role="EMPLOYEE"; denied(db,db.session(),Capability.VIEW_FLEET); // Stale administrator role denied.
        db=new Database(); db.role="EMPLOYEE";
        StaffSession employee=new StaffSession(db.user,db.company,"EMPLOYEE","",Set.of("MANAGE_VEHICLE_MASTER"));
        denied(db,employee,Capability.MANAGE_VEHICLE_MASTER); // Claimed capability never escalates DB role.
        JdbcStaffContext.require(db.connection(),employee,Capability.VIEW_FLEET);
        check(db.contexts.get(db.contexts.size()-1).get(2).equals("EMPLOYEE"));
        System.out.println("PASS: "+checks+" in-transaction staff-context assertions. No live database access.");
    }
}
