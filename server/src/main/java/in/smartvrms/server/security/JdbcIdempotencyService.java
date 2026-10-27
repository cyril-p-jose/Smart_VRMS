package in.smartvrms.server.security;

import in.smartvrms.application.port.IdempotencyPort;
import in.smartvrms.contracts.IdempotencyRecord;
import in.smartvrms.contracts.StaffSession;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcIdempotencyService implements IdempotencyPort {
    private final DataSource dataSource;

    public JdbcIdempotencyService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public static String computeRequestHash(String body) {
        if (body == null) body = "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }

    @Override
    public Optional<IdempotencyRecord> check(StaffSession session, String idempotencyKey, String operation, String requestHash) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(requestHash, "requestHash");

        String sql = """
            SELECT id, company_id, idempotency_key, actor_user_id, operation, request_hash, status, response_status, response_body, created_at, expires_at
            FROM vrms_private.idempotency_records
            WHERE company_id = ? AND idempotency_key = ?
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setObject(1, session.companyId());
                    s.setString(2, idempotencyKey);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            String storedHash = r.getString("request_hash");
                            if (!storedHash.equals(requestHash)) {
                                throw new IllegalArgumentException("Idempotency key reused with different request payload");
                            }
                            IdempotencyRecord rec = new IdempotencyRecord(
                                r.getObject("id", UUID.class),
                                r.getObject("company_id", UUID.class),
                                r.getString("idempotency_key"),
                                r.getObject("actor_user_id", UUID.class),
                                r.getString("operation"),
                                storedHash,
                                r.getString("status"),
                                (Integer) r.getObject("response_status"),
                                r.getString("response_body"),
                                r.getTimestamp("created_at").toInstant(),
                                r.getTimestamp("expires_at").toInstant()
                            );
                            c.commit();
                            return Optional.of(rec);
                        }
                    }
                }
                c.commit();
                return Optional.empty();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
                throw new RuntimeException("Failed to check idempotency", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Idempotency database error", e);
        }
    }

    @Override
    public IdempotencyRecord recordPending(StaffSession session, String idempotencyKey, String operation, String requestHash, int ttlSeconds) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(requestHash, "requestHash");

        String sql = """
            INSERT INTO vrms_private.idempotency_records
                (company_id, idempotency_key, actor_user_id, operation, request_hash, status, expires_at)
            VALUES (?, ?, ?, ?, ?, 'PENDING', now() + (? || ' seconds')::interval)
            RETURNING id, created_at, expires_at
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setObject(1, session.companyId());
                    s.setString(2, idempotencyKey);
                    s.setObject(3, session.userId());
                    s.setString(4, operation);
                    s.setString(5, requestHash);
                    s.setInt(6, Math.max(60, ttlSeconds));
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new IllegalStateException("Failed to insert idempotency record");
                        UUID id = r.getObject(1, UUID.class);
                        Instant createdAt = r.getTimestamp(2).toInstant();
                        Instant expiresAt = r.getTimestamp(3).toInstant();
                        c.commit();
                        return new IdempotencyRecord(id, session.companyId(), idempotencyKey, session.userId(), operation, requestHash, "PENDING", null, null, createdAt, expiresAt);
                    }
                }
            } catch (Exception e) {
                c.rollback();
                throw new RuntimeException("Failed to record pending idempotency", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Idempotency insert error", e);
        }
    }

    @Override
    public void recordCompletion(StaffSession session, String idempotencyKey, int responseStatus, String responseBody) {
        updateStatus(session, idempotencyKey, "COMPLETED", responseStatus, responseBody);
    }

    @Override
    public void recordFailure(StaffSession session, String idempotencyKey, int responseStatus, String responseBody) {
        updateStatus(session, idempotencyKey, "FAILED", responseStatus, responseBody);
    }

    private void updateStatus(StaffSession session, String idempotencyKey, String status, int responseStatus, String responseBody) {
        String sql = """
            UPDATE vrms_private.idempotency_records
            SET status = ?, response_status = ?, response_body = ?
            WHERE company_id = ? AND idempotency_key = ?
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setString(1, status);
                    s.setInt(2, responseStatus);
                    s.setString(3, responseBody);
                    s.setObject(4, session.companyId());
                    s.setString(5, idempotencyKey);
                    s.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw new RuntimeException("Failed to update idempotency status", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Idempotency update error", e);
        }
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
