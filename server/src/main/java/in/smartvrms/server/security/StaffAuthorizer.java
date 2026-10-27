package in.smartvrms.server.security;

import in.smartvrms.server.integration.SupabaseAuth;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Server-only authorization boundary. No identity/company/role comes from request metadata. */
public final class StaffAuthorizer {
    public record Staff(UUID userId, UUID companyId, String role) { }
    private final Function<String, UUID> identity;
    private final DataSource database;

    public StaffAuthorizer(SupabaseAuth auth, DataSource database) {
        this(Objects.requireNonNull(auth)::verifyStaffIdentity, database);
    }

    // Test seam stays package-private; production always uses fresh provider verification.
    StaffAuthorizer(Function<String, UUID> identity, DataSource database) {
        this.identity = Objects.requireNonNull(identity);
        this.database = Objects.requireNonNull(database);
    }

    /** Authorizes only this read. Later business operations must reauthorize in their own transaction. */
    public Staff authorize(String token, Capability capability) {
        if (capability == null) throw new SecurityException("Access denied.");
        UUID user = identity.apply(token); // Before opening a database connection.
        if (user == null) throw new SecurityException("Access denied.");
        try (Connection connection = database.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                requireRestrictedRole(connection);
                context(connection, user.toString(), "");
                MembershipSnapshot membership;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT auth_user_id, company_id, role, active FROM vrms_private.memberships WHERE auth_user_id = ?")) {
                    query.setQueryTimeout(10);
                    query.setObject(1, user);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) throw new SecurityException("Access denied.");
                        UUID returnedUser = rows.getObject(1, UUID.class);
                        UUID company = rows.getObject(2, UUID.class);
                        String role = rows.getString(3);
                        boolean active = rows.getBoolean(4);
                        if (!user.equals(returnedUser) || company == null || role == null || rows.next())
                            throw new SecurityException("Access denied.");
                        MembershipSnapshot.Role parsed;
                        try { parsed = MembershipSnapshot.Role.valueOf(role); }
                        catch (IllegalArgumentException ex) { throw new SecurityException("Access denied."); }
                        membership = new MembershipSnapshot(user, company, parsed, active);
                    }
                }
                AccessPolicy.require(membership, membership.companyId(), capability);
                context(connection, user.toString(), membership.companyId().toString());
                try (PreparedStatement query = connection.prepareStatement("SELECT id FROM vrms_private.companies WHERE id = ?")) {
                    query.setQueryTimeout(10);
                    query.setObject(1, membership.companyId());
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next() || !membership.companyId().equals(rows.getObject(1, UUID.class)) || rows.next())
                            throw new SecurityException("Access denied.");
                    }
                }
                return new Staff(user, membership.companyId(), membership.role().name());
            } finally {
                // Read-only work never commits. Failure here also prevents returning an authorization result.
                connection.rollback();
            }
        } catch (SQLException ex) {
            // Never expose JDBC error details, SQL, provider data or credentials to callers.
            throw new IllegalStateException("Staff authorization is unavailable.");
        }
    }

    private static void requireRestrictedRole(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT current_user = 'smart_vrms_api' AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole "
                + "AND NOT rolcreatedb AND NOT rolreplication FROM pg_roles WHERE rolname = current_user")) {
            query.setQueryTimeout(10);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1)) throw new SecurityException("Access denied.");
            }
        }
    }

    private static void context(Connection connection, String user, String company) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT set_config('vrms.user_id', ?, true), set_config('vrms.company_id', ?, true)")) {
            query.setQueryTimeout(10);
            query.setString(1, user);
            query.setString(2, company);
            try (ResultSet ignored = query.executeQuery()) { /* transaction-local context only */ }
        }
    }
}
