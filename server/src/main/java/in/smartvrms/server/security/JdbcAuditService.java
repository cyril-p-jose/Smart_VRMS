package in.smartvrms.server.security;

import in.smartvrms.application.port.AuditPort;
import in.smartvrms.contracts.AuditEventRecord;
import in.smartvrms.contracts.StaffSession;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class JdbcAuditService implements AuditPort {
    private final DataSource dataSource;

    public JdbcAuditService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public AuditEventRecord recordEvent(StaffSession session, String action, String resourceType, String resourceId, String detailsJson) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        if (detailsJson == null) detailsJson = "{}";

        String sql = """
            INSERT INTO vrms_private.audit_events (company_id, actor_user_id, action, resource_type, resource_id, details)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            RETURNING id, occurred_at
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setObject(1, session.companyId());
                    s.setObject(2, session.userId());
                    s.setString(3, action);
                    s.setString(4, resourceType);
                    s.setString(5, resourceId);
                    s.setString(6, detailsJson);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new IllegalStateException("Insert returned no row");
                        UUID id = r.getObject(1, UUID.class);
                        Instant occurredAt = r.getTimestamp(2).toInstant();
                        c.commit();
                        return new AuditEventRecord(id, session.companyId(), session.userId(), action, resourceType, resourceId, detailsJson, occurredAt);
                    }
                }
            } catch (Exception e) {
                c.rollback();
                throw new RuntimeException("Failed to record audit event", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Audit database error", e);
        }
    }

    @Override
    public List<AuditEventRecord> queryEvents(StaffSession session, String resourceType, String resourceId, int limit) {
        Objects.requireNonNull(session, "session");
        String sql = """
            SELECT id, company_id, actor_user_id, action, resource_type, resource_id, details::text, occurred_at
            FROM vrms_private.audit_events
            WHERE company_id = ?
              AND (? IS NULL OR resource_type = ?)
              AND (? IS NULL OR resource_id = ?)
            ORDER BY occurred_at DESC
            LIMIT ?
            """;

        List<AuditEventRecord> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setObject(1, session.companyId());
                    s.setString(2, resourceType);
                    s.setString(3, resourceType);
                    s.setString(4, resourceId);
                    s.setString(5, resourceId);
                    s.setInt(6, Math.max(1, Math.min(limit, 100)));
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) {
                            list.add(new AuditEventRecord(
                                r.getObject(1, UUID.class),
                                r.getObject(2, UUID.class),
                                r.getObject(3, UUID.class),
                                r.getString(4),
                                r.getString(5),
                                r.getString(6),
                                r.getString(7),
                                r.getTimestamp(8).toInstant()
                            ));
                        }
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw new RuntimeException("Failed to query audit events", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Audit query error", e);
        }
        return Collections.unmodifiableList(list);
    }

    private static void setContext(Connection c, StaffSession session) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT set_config('vrms.user_id', ?, true), set_config('vrms.company_id', ?, true), set_config('vrms.role', ?, true)")) {
            s.setString(1, session.userId().toString());
            s.setString(2, session.companyId().toString());
            s.setString(3, session.role());
            s.execute();
        }
    }
}
