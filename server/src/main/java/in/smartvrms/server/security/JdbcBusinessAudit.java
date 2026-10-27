package in.smartvrms.server.security;

import com.google.gson.JsonObject;
import in.smartvrms.contracts.StaffSession;
import java.sql.*;

/** Audit insertion participates in the caller's business transaction; failure rolls back both. */
public final class JdbcBusinessAudit {
    private JdbcBusinessAudit() { }
    public static String detail(String field, String value) {
        JsonObject json = new JsonObject(); json.addProperty(field,value); return json.toString();
    }
    public static void append(Connection connection, StaffSession session, String action,
                              String resourceType, String resourceId, String details) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Audit requires an explicit transaction.","VR002");
        try (PreparedStatement query = connection.prepareStatement(
                "INSERT INTO vrms_private.audit_events (company_id,actor_user_id,action,resource_type,resource_id,details) VALUES (?,?,?,?,?,?::jsonb)")) {
            query.setQueryTimeout(10);
            query.setObject(1,session.companyId()); query.setObject(2,session.userId());
            query.setString(3,action); query.setString(4,resourceType); query.setString(5,resourceId); query.setString(6,details);
            if (query.executeUpdate() != 1) throw new SQLException("Audit insertion failed.");
        }
    }
}
