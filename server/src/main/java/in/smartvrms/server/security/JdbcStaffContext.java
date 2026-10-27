package in.smartvrms.server.security;

import in.smartvrms.contracts.StaffSession;
import java.sql.*;
import java.util.Objects;
import java.util.UUID;

/** Revalidates membership/capability inside the SAME transaction as the operation. */
public final class JdbcStaffContext {
    private JdbcStaffContext() { }

    public static void require(Connection connection, StaffSession supplied, Capability action) throws SQLException {
        Objects.requireNonNull(connection);
        if (supplied == null || action == null) throw denied();
        if (connection.getAutoCommit())
            throw new SQLException("Staff context requires an explicit transaction.", "VR002");
        try (PreparedStatement guard = connection.prepareStatement(
                "SELECT current_user = 'smart_vrms_api' AND NOT rolsuper AND NOT rolbypassrls "
                + "AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication FROM pg_roles WHERE rolname = current_user")) {
            guard.setQueryTimeout(10);
            try (ResultSet row = guard.executeQuery()) { if (!row.next() || !row.getBoolean(1)) throw denied(); }
        }
        // Clear all prior context, including role, before consulting RLS-protected membership.
        install(connection, supplied.userId().toString(), "", "");
        UUID company;
        String role;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT company_id, role, active FROM vrms_private.memberships WHERE auth_user_id = ?")) {
            query.setQueryTimeout(10);
            query.setObject(1, supplied.userId());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw denied();
                company = row.getObject(1, UUID.class);
                role = row.getString(2);
                boolean active = row.getBoolean(3);
                if (!active || !supplied.companyId().equals(company) || !supplied.role().equals(role) || row.next()) throw denied();
            }
        }
        MembershipSnapshot.Role parsed;
        try { parsed = MembershipSnapshot.Role.valueOf(role); }
        catch (RuntimeException ex) { throw denied(); }
        AccessPolicy.require(new MembershipSnapshot(supplied.userId(), company, parsed, true), company, action);
        install(connection, supplied.userId().toString(), company.toString(), role);
        // Caller owns commit/rollback. This helper never commits a business operation.
    }

    private static void install(Connection connection, String user, String company, String role) throws SQLException {
        try (PreparedStatement context = connection.prepareStatement(
                "SELECT set_config('vrms.user_id', ?, true), set_config('vrms.company_id', ?, true), set_config('vrms.role', ?, true)")) {
            context.setQueryTimeout(10);
            context.setString(1, user); context.setString(2, company); context.setString(3, role);
            try (ResultSet ignored = context.executeQuery()) { }
        }
    }
    private static SecurityException denied() { return new SecurityException("Access denied."); }
}
