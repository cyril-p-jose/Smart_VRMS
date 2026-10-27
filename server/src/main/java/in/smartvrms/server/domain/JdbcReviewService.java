package in.smartvrms.server.domain;

import in.smartvrms.application.port.AuditPort;
import in.smartvrms.application.port.ReviewPort;
import in.smartvrms.contracts.ServiceReviewCommand;
import in.smartvrms.contracts.ServiceReviewCorrectionCommand;
import in.smartvrms.contracts.ServiceReviewRecord;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.JdbcStaffContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class JdbcReviewService implements ReviewPort {

    private final DataSource dataSource;
    private final AuditPort auditPort;

    public JdbcReviewService(DataSource dataSource, AuditPort auditPort) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.auditPort = auditPort;
    }

    private void checkStaff(StaffSession session) {
        Objects.requireNonNull(session, "session");
        if (!"ADMINISTRATOR".equals(session.role()) && !"EMPLOYEE".equals(session.role())) {
            throw new SecurityException("Forbidden: Staff session required");
        }
    }

    private void checkAdminOnly(StaffSession session, String actionDesc) {
        if (!"ADMINISTRATOR".equals(session.role())) {
            throw new SecurityException("Forbidden: Only administrators may " + actionDesc + ".");
        }
    }

    @Override
    public ServiceReviewRecord recordReview(StaffSession session, ServiceReviewCommand command) {
        checkStaff(session);
        Objects.requireNonNull(command, "command");

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                JdbcStaffContext.require(c, session, Capability.RECORD_REVIEW);

                // 1. Check booking exists and resolve customer_id and visit_id
                UUID customerId = null;
                UUID visitId = null;
                String bookingStatus = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT customer_id, visit_id, booking_status FROM vrms_private.bookings " +
                        "WHERE id = ? AND company_id = ? FOR UPDATE"
                )) {
                    ps.setObject(1, command.bookingId());
                    ps.setObject(2, session.companyId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            customerId = (UUID) rs.getObject("customer_id");
                            visitId = (UUID) rs.getObject("visit_id");
                            bookingStatus = rs.getString("booking_status");
                        }
                    }
                }

                if (customerId == null) {
                    throw new IllegalArgumentException("Booking not found: " + command.bookingId());
                }

                // 2. Eligibility Gate: All assignments must be physically returned (FR-REV-001)
                int openCustodyCount = 0;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM vrms_private.booking_assignments a " +
                        "LEFT JOIN vrms_private.custody_records c ON a.id = c.assignment_id " +
                        "WHERE a.booking_id = ? AND a.company_id = ? AND (c.id IS NULL OR c.actual_return_time IS NULL)"
                )) {
                    ps.setObject(1, command.bookingId());
                    ps.setObject(2, session.companyId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            openCustodyCount = rs.getInt(1);
                        }
                    }
                }

                if (openCustodyCount > 0) {
                    throw new IllegalStateException("Review can only be collected after all vehicles are physically returned (FR-REV-001)");
                }

                // 3. Eligibility Gate: Outstanding balance must be settled (FR-REV-001)
                long balanceDue = 0L;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT balance_due_paise FROM vrms_private.billing_documents " +
                        "WHERE booking_id = ? AND company_id = ? ORDER BY version DESC LIMIT 1"
                )) {
                    ps.setObject(1, command.bookingId());
                    ps.setObject(2, session.companyId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            balanceDue = rs.getLong("balance_due_paise");
                        }
                    }
                }

                if (balanceDue > 0) {
                    throw new IllegalStateException("Review can only be collected after service balance is fully settled (FR-REV-001)");
                }

                // 4. Check for duplicate review
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM vrms_private.service_reviews WHERE booking_id = ? AND company_id = ?"
                )) {
                    ps.setObject(1, command.bookingId());
                    ps.setObject(2, session.companyId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new IllegalStateException("Service review already exists for booking: " + command.bookingId());
                        }
                    }
                }

                UUID reviewId = UUID.randomUUID();
                Instant now = Instant.now();

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vrms_private.service_reviews " +
                        "(id, company_id, booking_id, customer_id, visit_id, rating, feedback_text, " +
                        "communication_source, consent_given, status, recorded_by, recorded_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECORDED', ?, ?)"
                )) {
                    ps.setObject(1, reviewId);
                    ps.setObject(2, session.companyId());
                    ps.setObject(3, command.bookingId());
                    ps.setObject(4, customerId);
                    ps.setObject(5, visitId);
                    ps.setInt(6, command.rating());
                    ps.setString(7, command.feedbackText().trim());
                    ps.setString(8, command.communicationSource());
                    ps.setBoolean(9, command.consentGiven());
                    ps.setObject(10, session.userId());
                    ps.setTimestamp(11, Timestamp.from(now));
                    ps.executeUpdate();
                }

                c.commit();

                return new ServiceReviewRecord(
                        reviewId, session.companyId(), command.bookingId(), customerId, visitId,
                        command.rating(), command.feedbackText().trim(), command.communicationSource(),
                        command.consentGiven(), "RECORDED", session.userId(), now
                );
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record service review: " + e.getMessage(), e);
        }
    }

    @Override
    public ServiceReviewRecord correctReview(StaffSession session, ServiceReviewCorrectionCommand command) {
        checkStaff(session);
        checkAdminOnly(session, "correct reviews");
        Objects.requireNonNull(command, "command");

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                JdbcStaffContext.require(c, session, Capability.CORRECT_SERVICE_REVIEW);

                ServiceReviewRecord existing = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, company_id, booking_id, customer_id, visit_id, rating, feedback_text, " +
                        "communication_source, consent_given, status, recorded_by, recorded_at " +
                        "FROM vrms_private.service_reviews WHERE id = ? AND company_id = ? FOR UPDATE"
                )) {
                    ps.setObject(1, command.reviewId());
                    ps.setObject(2, session.companyId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            existing = mapReview(rs);
                        }
                    }
                }

                if (existing == null) {
                    throw new IllegalArgumentException("Review not found: " + command.reviewId());
                }

                Instant now = Instant.now();

                // 1. Record correction in history (FR-REV-002)
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vrms_private.service_review_corrections " +
                        "(company_id, review_id, previous_rating, revised_rating, previous_feedback, " +
                        "revised_feedback, correction_reason, corrected_by, corrected_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, command.reviewId());
                    ps.setInt(3, existing.rating());
                    ps.setInt(4, command.revisedRating());
                    ps.setString(5, existing.feedbackText());
                    ps.setString(6, command.revisedFeedback().trim());
                    ps.setString(7, command.correctionReason().trim());
                    ps.setObject(8, session.userId());
                    ps.setTimestamp(9, Timestamp.from(now));
                    ps.executeUpdate();
                }

                // 2. Update current active review record
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.service_reviews " +
                        "SET rating = ?, feedback_text = ?, status = 'CORRECTED' " +
                        "WHERE id = ? AND company_id = ?"
                )) {
                    ps.setInt(1, command.revisedRating());
                    ps.setString(2, command.revisedFeedback().trim());
                    ps.setObject(3, command.reviewId());
                    ps.setObject(4, session.companyId());
                    ps.executeUpdate();
                }

                c.commit();

                return new ServiceReviewRecord(
                        existing.id(), existing.companyId(), existing.bookingId(), existing.customerId(),
                        existing.visitId(), command.revisedRating(), command.revisedFeedback().trim(),
                        existing.communicationSource(), existing.consentGiven(), "CORRECTED",
                        existing.recordedBy(), existing.recordedAt()
                );
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to correct service review: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ServiceReviewRecord> listReviews(StaffSession session, UUID customerId, UUID bookingId) {
        checkStaff(session);
        List<ServiceReviewRecord> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            JdbcStaffContext.require(c, session, Capability.VIEW_OPERATIONAL_HISTORY);

            StringBuilder sql = new StringBuilder(
                    "SELECT id, company_id, booking_id, customer_id, visit_id, rating, feedback_text, " +
                    "communication_source, consent_given, status, recorded_by, recorded_at " +
                    "FROM vrms_private.service_reviews WHERE company_id = ? "
            );
            List<Object> params = new ArrayList<>();
            params.add(session.companyId());

            if (customerId != null) {
                sql.append("AND customer_id = ? ");
                params.add(customerId);
            }
            if (bookingId != null) {
                sql.append("AND booking_id = ? ");
                params.add(bookingId);
            }
            sql.append("ORDER BY recorded_at DESC");

            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapReview(rs));
                    }
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list reviews: " + e.getMessage(), e);
        }
        return list;
    }

    private static ServiceReviewRecord mapReview(ResultSet rs) throws SQLException {
        return new ServiceReviewRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("company_id"),
                (UUID) rs.getObject("booking_id"),
                (UUID) rs.getObject("customer_id"),
                (UUID) rs.getObject("visit_id"),
                rs.getInt("rating"),
                rs.getString("feedback_text"),
                rs.getString("communication_source"),
                rs.getBoolean("consent_given"),
                rs.getString("status"),
                (UUID) rs.getObject("recorded_by"),
                rs.getTimestamp("recorded_at").toInstant()
        );
    }
}
