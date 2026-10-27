package in.smartvrms.verification;

import in.smartvrms.contracts.*;
import in.smartvrms.server.domain.JdbcFleetService;
import in.smartvrms.server.security.JdbcBusinessAudit;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

public final class AtomicFleetWriteChecks {
    static int checks;
    static void check(boolean value){if(!value)throw new AssertionError();checks++;}
    @SuppressWarnings("unchecked")static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static class Database{
        UUID user=UUID.randomUUID(),company=UUID.randomUUID(),vehicle=UUID.randomUUID(),category=UUID.randomUUID();
        boolean failAudit;List<String> events=new ArrayList<>();
        StaffSession session(){return new StaffSession(user,company,"ADMINISTRATOR","",Set.of());}
        DataSource source(){return proxy(DataSource.class,(p,m,a)->{if(m.getName().equals("getConnection"))return connection();throw new AssertionError(m.getName());});}
        Connection connection(){return proxy(Connection.class,(p,m,a)->{
            switch(m.getName()){
                case "getAutoCommit":return false;
                case "setAutoCommit":check(Boolean.FALSE.equals(a[0]));return null;
                case "prepareStatement":return statement((String)a[0]);
                case "commit":events.add("commit");return null;
                case "rollback":events.add("rollback");return null;
                case "close":events.add("close");return null;
                default:throw new AssertionError(m.getName());
            }
        });}
        PreparedStatement statement(String sql){return proxy(PreparedStatement.class,(p,m,a)->{
            if(m.getName().startsWith("set"))return null;
            if(m.getName().equals("close"))return null;
            if(m.getName().equals("executeUpdate")){check(sql.contains("audit_events"));events.add("audit");if(failAudit)throw new SQLException("fixture audit failure");return 1;}
            if(!m.getName().equals("executeQuery"))throw new AssertionError(m.getName());
            Object[][] values;
            if(sql.contains("pg_roles"))values=new Object[][]{{true}};
            else if(sql.contains("set_config"))values=new Object[0][];
            else if(sql.contains("memberships"))values=new Object[][]{{company,"ADMINISTRATOR",true}};
            else if(sql.startsWith("SELECT name FROM"))values=new Object[][]{{"SUV"}};
            else if(sql.contains("INSERT INTO vrms_private.vehicles")){events.add("vehicle");values=new Object[][]{{vehicle,Timestamp.from(java.time.Instant.now()),1L}};}
            else throw new AssertionError("Unexpected query");
            int[] row={-1};return proxy(ResultSet.class,(rp,rm,ra)->{
                switch(rm.getName()){
                    case "next":return ++row[0]<values.length;
                    case "getBoolean":case "getString":case "getLong":case "getObject":return values[row[0]][(Integer)ra[0]-1];
                    case "close":return null;
                    default:throw new AssertionError(rm.getName());
                }
            });
        });}
        void save(){new JdbcFleetService(source(),null).registerVehicle(session(),category,"KL07TEST","Test","Vehicle",5,240000,10000,null);}
    }
    public static void main(String[] args){
        Database success=new Database();success.save();check(success.events.equals(List.of("vehicle","audit","commit","close")));
        Database failure=new Database();failure.failAudit=true;try{failure.save();throw new AssertionError();}catch(RuntimeException expected){check(failure.events.equals(List.of("vehicle","audit","rollback","close")));}
        String detail=JdbcBusinessAudit.detail("reason","quote\" newline\nMalayalam മലയാളം");
        check(com.google.gson.JsonParser.parseString(detail).getAsJsonObject().get("reason").getAsString().equals("quote\" newline\nMalayalam മലയാളം"));
        System.out.println("PASS: "+checks+" actual fleet-adapter transaction ordering/audit escaping fixture checks. Live write acceptance still required.");
    }
}
