package in.smartvrms.server.security;

import in.smartvrms.application.port.StaffProvisioningPort;
import in.smartvrms.contracts.MemberManagementRecord;
import in.smartvrms.contracts.StaffSession;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class JdbcStaffProvisioningService implements StaffProvisioningPort {
    @FunctionalInterface
    public interface InviteProvider {
        UUID invite(String email, String fullName);
    }

    private final DataSource dataSource;
    private final InviteProvider inviteProvider;

    public JdbcStaffProvisioningService(DataSource dataSource) {
        this(dataSource, (email, fullName) -> {
            throw new IllegalStateException("Employee invitation service unavailable: Supabase service-role secret is not configured.");
        });
    }

    public JdbcStaffProvisioningService(DataSource dataSource, InviteProvider inviteProvider) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.inviteProvider = Objects.requireNonNull(inviteProvider, "inviteProvider");
    }

    public static InviteProvider createSupabaseInviteProvider(java.net.URI endpoint, String serviceRoleKey) {
        if (endpoint == null || serviceRoleKey == null || serviceRoleKey.isBlank()) {
            return (email, fullName) -> {
                throw new IllegalStateException("Employee invitation service unavailable: Supabase service-role secret is not configured.");
            };
        }
        return new SupabaseAdminInviteProvider(endpoint, serviceRoleKey);
    }

    @Override
    public MemberManagementRecord inviteEmployee(StaffSession session, String email, String fullName) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(email, "email");
        if (!session.hasCapability(Capability.INVITE_EMPLOYEE.name())) {
            throw new SecurityException("Forbidden: Only administrators can invite employees");
        }

        UUID newUserId = inviteProvider.invite(email, fullName);
        String sql = """
            INSERT INTO vrms_private.memberships (auth_user_id, company_id, role, active, created_by)
            VALUES (?, ?, 'EMPLOYEE', true, ?)
            RETURNING auth_user_id, company_id, role, active, created_by, created_at, version
            """;

        String auditSql = """
            INSERT INTO vrms_private.membership_audit (company_id, actor_user_id, subject_user_id, event)
            VALUES (?, ?, ?, 'MEMBER_INVITED')
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                JdbcStaffContext.require(c, session, Capability.INVITE_EMPLOYEE);
                MemberManagementRecord record;
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setObject(1, newUserId);
                    s.setObject(2, session.companyId());
                    s.setObject(3, session.userId());
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new IllegalStateException("Failed to insert membership");
                        record = new MemberManagementRecord(
                            r.getObject(1, UUID.class),
                            r.getObject(2, UUID.class),
                            r.getString(3),
                            r.getBoolean(4),
                            r.getObject(5, UUID.class),
                            r.getTimestamp(6).toInstant(),
                            r.getLong(7)
                        );
                    }
                }
                try (PreparedStatement as = c.prepareStatement(auditSql)) {
                    as.setObject(1, session.companyId());
                    as.setObject(2, session.userId());
                    as.setObject(3, newUserId);
                    as.executeUpdate();
                }
                c.commit();
                return record;
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SecurityException) throw (SecurityException) e;
                if (e instanceof IllegalStateException) throw (IllegalStateException) e;
                if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
                throw new RuntimeException("Failed to invite employee", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error during invitation", e);
        }
    }

    @Override
    public MemberManagementRecord revokeMembership(StaffSession session, UUID targetAuthUserId, String reason) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(targetAuthUserId, "targetAuthUserId");
        if (!session.hasCapability(Capability.REVOKE_MEMBERSHIP.name())) {
            throw new SecurityException("Forbidden: Only administrators can revoke memberships");
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                JdbcStaffContext.require(c, session, Capability.REVOKE_MEMBERSHIP);

                // Check 1: Cannot revoke company primary administrator
                try (PreparedStatement ps = c.prepareStatement("SELECT primary_admin_user_id FROM vrms_private.companies WHERE id = ?")) {
                    ps.setObject(1, session.companyId());
                    try (ResultSet r = ps.executeQuery()) {
                        if (r.next()) {
                            UUID primaryAdmin = r.getObject(1, UUID.class);
                            if (targetAuthUserId.equals(primaryAdmin)) {
                                throw new IllegalStateException("Cannot revoke the primary administrator. Transfer administrator role first.");
                            }
                        }
                    }
                }

                // Check 2: Check target role and ensure we don't revoke the last active administrator
                String role = null;
                try (PreparedStatement ps = c.prepareStatement("SELECT role, active FROM vrms_private.memberships WHERE company_id = ? AND auth_user_id = ?")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, targetAuthUserId);
                    try (ResultSet r = ps.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Target membership not found");
                        role = r.getString(1);
                        if (!r.getBoolean(2)) {
                            // already inactive
                            return new MemberManagementRecord(targetAuthUserId, session.companyId(), role, false, session.userId(), Instant.now(), 1);
                        }
                    }
                }

                if ("ADMINISTRATOR".equals(role)) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT count(*) FROM vrms_private.memberships WHERE company_id = ? AND role = 'ADMINISTRATOR' AND active = true")) {
                        ps.setObject(1, session.companyId());
                        try (ResultSet r = ps.executeQuery()) {
                            if (r.next() && r.getInt(1) <= 1) {
                                throw new IllegalStateException("Cannot revoke the last active administrator");
                            }
                        }
                    }
                }

                // Update membership to inactive
                String updateSql = """
                    UPDATE vrms_private.memberships
                    SET active = false, version = version + 1
                    WHERE company_id = ? AND auth_user_id = ?
                    RETURNING auth_user_id, company_id, role, active, created_by, created_at, version
                    """;

                MemberManagementRecord record;
                try (PreparedStatement s = c.prepareStatement(updateSql)) {
                    s.setObject(1, session.companyId());
                    s.setObject(2, targetAuthUserId);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new IllegalStateException("Revoke updated no rows");
                        record = new MemberManagementRecord(
                            r.getObject(1, UUID.class),
                            r.getObject(2, UUID.class),
                            r.getString(3),
                            r.getBoolean(4),
                            r.getObject(5, UUID.class),
                            r.getTimestamp(6).toInstant(),
                            r.getLong(7)
                        );
                    }
                }

                try (PreparedStatement as = c.prepareStatement(
                        "INSERT INTO vrms_private.membership_audit (company_id, actor_user_id, subject_user_id, event) VALUES (?, ?, ?, 'MEMBER_REVOKED')")) {
                    as.setObject(1, session.companyId());
                    as.setObject(2, session.userId());
                    as.setObject(3, targetAuthUserId);
                    as.executeUpdate();
                }

                c.commit();
                return record;
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SecurityException) throw (SecurityException) e;
                if (e instanceof IllegalStateException) throw (IllegalStateException) e;
                if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
                throw new RuntimeException("Failed to revoke membership", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error during revocation", e);
        }
    }

    @Override
    public void handoverPrimaryAdministrator(StaffSession session, UUID newPrimaryAdminUserId) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(newPrimaryAdminUserId, "newPrimaryAdminUserId");
        if (!"ADMINISTRATOR".equals(session.role())) {
            throw new SecurityException("Forbidden: Only an administrator can execute administrator handover");
        }
        if (session.userId().equals(newPrimaryAdminUserId)) {
            throw new IllegalArgumentException("Cannot handover administrator role to oneself");
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                JdbcStaffContext.require(c, session, Capability.REVOKE_MEMBERSHIP);

                // Verify new admin is an active administrator in the same company
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM vrms_private.memberships WHERE company_id = ? AND auth_user_id = ? AND role = 'ADMINISTRATOR' AND active = true")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, newPrimaryAdminUserId);
                    try (ResultSet r = ps.executeQuery()) {
                        if (!r.next()) {
                            throw new IllegalArgumentException("Target user is not an active administrator in this company");
                        }
                    }
                }

                // Update company primary_admin_user_id
                try (PreparedStatement ps = c.prepareStatement("UPDATE vrms_private.companies SET primary_admin_user_id = ? WHERE id = ?")) {
                    ps.setObject(1, newPrimaryAdminUserId);
                    ps.setObject(2, session.companyId());
                    ps.executeUpdate();
                }

                // Audit the handover
                try (PreparedStatement as = c.prepareStatement(
                        "INSERT INTO vrms_private.membership_audit (company_id, actor_user_id, subject_user_id, event) VALUES (?, ?, ?, 'ADMIN_TRANSFERRED')")) {
                    as.setObject(1, session.companyId());
                    as.setObject(2, session.userId());
                    as.setObject(3, newPrimaryAdminUserId);
                    as.executeUpdate();
                }

                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SecurityException) throw (SecurityException) e;
                if (e instanceof IllegalStateException) throw (IllegalStateException) e;
                if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
                throw new RuntimeException("Failed to handover administrator role", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error during handover", e);
        }
    }

    @Override
    public List<MemberManagementRecord> listMembers(StaffSession session) {
        Objects.requireNonNull(session, "session");
        String sql = """
            SELECT auth_user_id, company_id, role, active, created_by, created_at, version
            FROM vrms_private.memberships
            WHERE company_id = ?
            ORDER BY created_at ASC
            """;

        List<MemberManagementRecord> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                JdbcStaffContext.require(c, session, Capability.MANAGE_PERSONNEL);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setObject(1, session.companyId());
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) {
                            list.add(new MemberManagementRecord(
                                r.getObject(1, UUID.class),
                                r.getObject(2, UUID.class),
                                r.getString(3),
                                r.getBoolean(4),
                                r.getObject(5, UUID.class),
                                r.getTimestamp(6).toInstant(),
                                r.getLong(7)
                            ));
                        }
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SecurityException) throw (SecurityException) e;
                if (e instanceof IllegalStateException) throw (IllegalStateException) e;
                if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
                throw new RuntimeException("Failed to list members", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error listing members", e);
        }
        return Collections.unmodifiableList(list);
    }

    static final class SupabaseAdminInviteProvider implements InviteProvider {
        private final java.net.URI endpoint;
        private final String serviceRoleKey;
        private final in.smartvrms.server.integration.BoundedHttp http = new in.smartvrms.server.integration.BoundedHttp();

        SupabaseAdminInviteProvider(java.net.URI endpoint, String serviceRoleKey) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            this.serviceRoleKey = Objects.requireNonNull(serviceRoleKey, "serviceRoleKey");
        }

        @Override
        public UUID invite(String email, String fullName) {
            if (email == null || !email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                throw new IllegalArgumentException("Invalid email format for employee invitation.");
            }
            com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
            payload.addProperty("email", email.strip());
            if (fullName != null && !fullName.isBlank()) {
                com.google.gson.JsonObject data = new com.google.gson.JsonObject();
                data.addProperty("full_name", fullName.strip());
                payload.add("data", data);
            }
            byte[] body = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(endpoint.resolve("/auth/v1/invite"))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("apikey", serviceRoleKey)
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            var response = http.send(request);
            if (response.status() == 422 || response.status() == 400) {
                String responseBody = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
                if (responseBody.contains("email_exists") || responseBody.contains("already exists")) {
                    throw new IllegalArgumentException("A user with this email address already exists.");
                }
                throw new IllegalArgumentException("Invalid invitation request: " + response.status());
            }
            if (response.status() == 429) {
                throw new IllegalStateException("Supabase rate limited invitation request.");
            }
            if (response.status() != 200) {
                throw new IllegalStateException("Employee invitation provider returned status " + response.status());
            }
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(
                    new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            String idStr = json.get("id").getAsString();
            return UUID.fromString(idStr);
        }
    }
}
