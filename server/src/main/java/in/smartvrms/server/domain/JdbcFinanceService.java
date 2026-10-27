package in.smartvrms.server.domain;

import in.smartvrms.application.port.AuditPort;
import in.smartvrms.application.port.FinancePort;
import in.smartvrms.contracts.BillingDocumentRecord;
import in.smartvrms.contracts.BillingItemRecord;
import in.smartvrms.contracts.CustomerStatement;
import in.smartvrms.contracts.FinancialCorrectionCommand;
import in.smartvrms.contracts.FinancialCorrectionRecord;
import in.smartvrms.contracts.LedgerSummary;
import in.smartvrms.contracts.PaymentAllocationRecord;
import in.smartvrms.contracts.PaymentRecord;
import in.smartvrms.contracts.PaymentRequestRecord;
import in.smartvrms.contracts.QrIntentRecord;
import in.smartvrms.contracts.RecordPaymentCommand;
import in.smartvrms.contracts.RefundableDepositRecord;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.server.finance.FinancialLedgerEngine;
import in.smartvrms.server.finance.PdfDocumentGenerator;
import in.smartvrms.server.finance.QrCodeGenerator;
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

public class JdbcFinanceService implements FinancePort {

    private final DataSource dataSource;
    private final AuditPort auditPort;

    public JdbcFinanceService(DataSource dataSource, AuditPort auditPort) {
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
        checkStaff(session);
        if (!"ADMINISTRATOR".equals(session.role())) {
            throw new SecurityException("Forbidden: Only Administrators can " + actionDesc);
        }
    }

    private void setContext(Connection c, StaffSession session, Capability capability) throws SQLException {
        if (c.getAutoCommit()) c.setAutoCommit(false);
        JdbcStaffContext.require(c, session, capability);
    }

    @Override
    public PaymentRequestRecord createPaymentRequest(
            StaffSession session,
            UUID bookingId,
            long amountPaise,
            String paymentType,
            Instant dueAt,
            String notes
    ) {
        checkStaff(session);
        Objects.requireNonNull(bookingId, "bookingId");
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Payment request amount must be greater than zero");
        }
        Instant due = dueAt != null ? dueAt : Instant.now().plusSeconds(86400 * 2);

        try (Connection c = dataSource.getConnection()) {
            setContext(c, session, Capability.BOOK_STANDARD_RENTAL);

            UUID customerId;
            String bookingNumber;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT customer_id, booking_number FROM vrms_private.bookings WHERE company_id = ? AND id = ?")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Booking not found: " + bookingId);
                    customerId = (UUID) rs.getObject(1);
                    bookingNumber = rs.getString(2);
                }
            }

            String merchantVpa = "payments@smartvrms";
            String merchantName = "Smart VRMS Enterprise";
            String upiUri = QrCodeGenerator.buildUpiUri(
                    merchantVpa,
                    merchantName,
                    amountPaise,
                    "Booking " + bookingNumber,
                    bookingId.toString().substring(0, 8)
            );

            UUID reqId;
            Instant createdAt;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO vrms_private.payment_requests " +
                    "(company_id, booking_id, customer_id, amount_paise, currency, payment_type, due_at, status, " +
                    "merchant_vpa, merchant_name, qr_payload, notes, created_by) " +
                    "VALUES (?, ?, ?, ?, 'INR', ?, ?, 'PENDING', ?, ?, ?, ?, ?) RETURNING id, created_at")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, bookingId);
                ps.setObject(3, customerId);
                ps.setLong(4, amountPaise);
                ps.setString(5, paymentType != null ? paymentType : "RENTAL_BALANCE");
                ps.setTimestamp(6, Timestamp.from(due));
                ps.setString(7, merchantVpa);
                ps.setString(8, merchantName);
                ps.setString(9, upiUri);
                ps.setString(10, notes);
                ps.setObject(11, session.userId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    reqId = (UUID) rs.getObject(1);
                    createdAt = rs.getTimestamp(2).toInstant();
                }
            }

            c.commit();
            if (auditPort != null) {
                auditPort.recordEvent(session, "CREATE_PAYMENT_REQUEST", "PAYMENT_REQUEST", reqId.toString(),
                        "{\"amountPaise\":" + amountPaise + ",\"bookingId\":\"" + bookingId + "\"}");
            }

            return new PaymentRequestRecord(
                    reqId,
                    session.companyId(),
                    bookingId,
                    customerId,
                    amountPaise,
                    "INR",
                    paymentType,
                    due,
                    "PENDING",
                    merchantVpa,
                    merchantName,
                    upiUri,
                    notes,
                    session.userId(),
                    createdAt
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create payment request: " + e.getMessage(), e);
        }
    }

    @Override
    public QrIntentRecord getPaymentQr(StaffSession session, UUID requestId) {
        checkStaff(session);
        Objects.requireNonNull(requestId, "requestId");

        try (Connection c = dataSource.getConnection()) {
            setContext(c, session, Capability.VIEW_OPERATIONAL_BALANCE);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT qr_payload, merchant_vpa, merchant_name, amount_paise " +
                    "FROM vrms_private.payment_requests WHERE company_id = ? AND id = ?")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Payment request not found: " + requestId);
                    String payload = rs.getString(1);
                    String vpa = rs.getString(2);
                    String name = rs.getString(3);
                    long amount = rs.getLong(4);

                    String base64Png = QrCodeGenerator.generateQrBase64Png(payload, 300, 300);
                    return new QrIntentRecord(requestId, payload, vpa, name, amount, base64Png);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to retrieve payment QR: " + e.getMessage(), e);
        }
    }

    @Override
    public PaymentRecord recordVerifiedPayment(StaffSession session, RecordPaymentCommand cmd) {
        checkStaff(session);
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(cmd.bookingId(), "bookingId");
        if (cmd.amountPaise() <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
        if (cmd.verificationEvidence() == null || cmd.verificationEvidence().isBlank()) {
            throw new IllegalArgumentException("Verification evidence is mandatory (e.g., bank reference / UTR)");
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session, Capability.RECORD_VERIFIED_PAYMENT);

                // Check duplicate external reference if provided
                if (cmd.externalReference() != null && !cmd.externalReference().isBlank()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT 1 FROM vrms_private.payments " +
                            "WHERE company_id = ? AND external_reference = ? AND status = 'VERIFIED'")) {
                        ps.setObject(1, session.companyId());
                        ps.setString(2, cmd.externalReference().trim());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                throw new IllegalStateException("Duplicate payment reference / UTR already recorded: " + cmd.externalReference());
                            }
                        }
                    }
                }

                // Lock booking row
                UUID customerId;
                long currentTotal;
                long currentActualAdvance;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT customer_id, total_paise, actual_advance_received_paise " +
                        "FROM vrms_private.bookings WHERE company_id = ? AND id = ? FOR UPDATE")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, cmd.bookingId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("Booking not found: " + cmd.bookingId());
                        customerId = (UUID) rs.getObject(1);
                        currentTotal = rs.getLong(2);
                        currentActualAdvance = rs.getLong(3);
                    }
                }

                // Insert payment record
                UUID paymentId;
                Instant createdAt;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vrms_private.payments " +
                        "(company_id, booking_id, customer_id, payment_request_id, amount_paise, currency, " +
                        "payment_method, external_reference, status, verification_evidence, verified_by, notes) " +
                        "VALUES (?, ?, ?, ?, ?, 'INR', ?, ?, 'VERIFIED', ?, ?, ?) RETURNING id, created_at")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, cmd.bookingId());
                    ps.setObject(3, customerId);
                    ps.setObject(4, cmd.paymentRequestId());
                    ps.setLong(5, cmd.amountPaise());
                    ps.setString(6, cmd.paymentMethod() != null ? cmd.paymentMethod() : "CASH");
                    ps.setString(7, cmd.externalReference() != null ? cmd.externalReference().trim() : null);
                    ps.setString(8, cmd.verificationEvidence().trim());
                    ps.setObject(9, session.userId());
                    ps.setString(10, cmd.notes());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        paymentId = (UUID) rs.getObject(1);
                        createdAt = rs.getTimestamp(2).toInstant();
                    }
                }

                // Allocate payment
                long advAllocation = cmd.allocateToAdvancePaise() > 0 ? cmd.allocateToAdvancePaise() : 0;
                long depAllocation = cmd.allocateToDepositPaise() > 0 ? cmd.allocateToDepositPaise() : 0;
                long chargeAllocation = cmd.amountPaise() - (advAllocation + depAllocation);
                if (chargeAllocation < 0) {
                    throw new IllegalArgumentException("Total allocations exceed received payment amount");
                }

                // If allocations not explicitly divided, allocate to rental balance
                if (advAllocation == 0 && depAllocation == 0) {
                    chargeAllocation = cmd.amountPaise();
                }

                if (advAllocation > 0) {
                    insertAllocation(c, session.companyId(), paymentId, cmd.bookingId(), "ADVANCE", advAllocation);
                }
                if (depAllocation > 0) {
                    insertAllocation(c, session.companyId(), paymentId, cmd.bookingId(), "SECURITY_DEPOSIT", depAllocation);
                    recordDepositReceived(c, session.companyId(), cmd.bookingId(), customerId, depAllocation);
                }
                if (chargeAllocation > 0) {
                    insertAllocation(c, session.companyId(), paymentId, cmd.bookingId(), "RENTAL_CHARGE", chargeAllocation);
                }

                // Update booking received cash total
                long totalPaidIncrement = advAllocation + chargeAllocation;
                if (totalPaidIncrement > 0) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vrms_private.bookings SET actual_advance_received_paise = actual_advance_received_paise + ? " +
                            "WHERE id = ?")) {
                        ps.setLong(1, totalPaidIncrement);
                        ps.setObject(2, cmd.bookingId());
                        ps.executeUpdate();
                    }
                }

                // Ledger entries: CASH_RECEIVED
                insertLedgerEntry(c, session.companyId(), cmd.bookingId(), "CASH_RECEIVED", "CREDIT",
                        cmd.amountPaise(), "PAYMENT", paymentId, "Verified payment received via " + cmd.paymentMethod(), session.userId());

                // Mark payment request paid if linked
                if (cmd.paymentRequestId() != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vrms_private.payment_requests SET status = 'PAID' WHERE id = ?")) {
                        ps.setObject(1, cmd.paymentRequestId());
                        ps.executeUpdate();
                    }
                }

                c.commit();

                if (auditPort != null) {
                    auditPort.recordEvent(session, "RECORD_PAYMENT", "PAYMENT", paymentId.toString(),
                            "{\"amountPaise\":" + cmd.amountPaise() + ",\"method\":\"" + cmd.paymentMethod() + "\"}");
                }

                return new PaymentRecord(
                        paymentId,
                        session.companyId(),
                        cmd.bookingId(),
                        customerId,
                        cmd.paymentRequestId(),
                        cmd.amountPaise(),
                        "INR",
                        cmd.paymentMethod(),
                        cmd.externalReference(),
                        "VERIFIED",
                        cmd.verificationEvidence(),
                        session.userId(),
                        createdAt,
                        cmd.notes(),
                        createdAt
                );
            } catch (Exception e) {
                c.rollback();
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new IllegalStateException(e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record payment: " + e.getMessage(), e);
        }
    }

    private void insertAllocation(Connection c, UUID companyId, UUID paymentId, UUID bookingId, String type, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO vrms_private.payment_allocations (company_id, payment_id, booking_id, allocation_type, allocated_paise) " +
                "VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, companyId);
            ps.setObject(2, paymentId);
            ps.setObject(3, bookingId);
            ps.setString(4, type);
            ps.setLong(5, amount);
            ps.executeUpdate();
        }
    }

    private void recordDepositReceived(Connection c, UUID companyId, UUID bookingId, UUID customerId, long amount) throws SQLException {
        UUID existingId = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM vrms_private.refundable_deposits WHERE company_id = ? AND booking_id = ? FOR UPDATE")) {
            ps.setObject(1, companyId);
            ps.setObject(2, bookingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    existingId = (UUID) rs.getObject(1);
                }
            }
        }

        if (existingId == null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO vrms_private.refundable_deposits " +
                    "(company_id, booking_id, customer_id, deposit_received_paise, status) " +
                    "VALUES (?, ?, ?, ?, 'HELD')")) {
                ps.setObject(1, companyId);
                ps.setObject(2, bookingId);
                ps.setObject(3, customerId);
                ps.setLong(4, amount);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE vrms_private.refundable_deposits " +
                    "SET deposit_received_paise = deposit_received_paise + ?, status = 'HELD' " +
                    "WHERE id = ?")) {
                ps.setLong(1, amount);
                ps.setObject(2, existingId);
                ps.executeUpdate();
            }
        }
    }

    private void insertLedgerEntry(Connection c, UUID companyId, UUID bookingId, String entryType, String direction,
                                   long amount, String refType, UUID refId, String desc, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO vrms_private.ledger_entries " +
                "(company_id, booking_id, entry_type, direction, amount_paise, source_ref_type, source_ref_id, description, posted_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, companyId);
            ps.setObject(2, bookingId);
            ps.setString(3, entryType);
            ps.setString(4, direction);
            ps.setLong(5, amount);
            ps.setString(6, refType);
            ps.setObject(7, refId);
            ps.setString(8, desc);
            ps.setObject(9, userId);
            ps.executeUpdate();
        }
    }

    @Override
    public List<PaymentRecord> listPaymentsForBooking(StaffSession session, UUID bookingId) {
        checkStaff(session);
        Objects.requireNonNull(bookingId, "bookingId");
        List<PaymentRecord> list = new ArrayList<>();

        try (Connection c = dataSource.getConnection()) {
            setContext(c, session, Capability.VIEW_OPERATIONAL_BALANCE);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, company_id, booking_id, customer_id, payment_request_id, amount_paise, currency, " +
                    "payment_method, external_reference, status, verification_evidence, verified_by, verified_at, notes, created_at " +
                    "FROM vrms_private.payments WHERE company_id = ? AND booking_id = ? ORDER BY created_at ASC")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new PaymentRecord(
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("company_id"),
                                (UUID) rs.getObject("booking_id"),
                                (UUID) rs.getObject("customer_id"),
                                (UUID) rs.getObject("payment_request_id"),
                                rs.getLong("amount_paise"),
                                rs.getString("currency"),
                                rs.getString("payment_method"),
                                rs.getString("external_reference"),
                                rs.getString("status"),
                                rs.getString("verification_evidence"),
                                (UUID) rs.getObject("verified_by"),
                                rs.getTimestamp("verified_at").toInstant(),
                                rs.getString("notes"),
                                rs.getTimestamp("created_at").toInstant()
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list payments: " + e.getMessage(), e);
        }
        return list;
    }

    @Override
    public RefundableDepositRecord getDepositForBooking(StaffSession session, UUID bookingId) {
        checkStaff(session);
        Objects.requireNonNull(bookingId, "bookingId");

        try (Connection c = dataSource.getConnection()) {
            setContext(c, session, Capability.VIEW_OPERATIONAL_BALANCE);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, company_id, booking_id, customer_id, deposit_received_paise, deposit_refunded_paise, " +
                    "deposit_applied_paise, status, held_at, refunded_at, refund_ref, refund_approved_by, notes " +
                    "FROM vrms_private.refundable_deposits WHERE company_id = ? AND booking_id = ?")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new RefundableDepositRecord(
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("company_id"),
                                (UUID) rs.getObject("booking_id"),
                                (UUID) rs.getObject("customer_id"),
                                rs.getLong("deposit_received_paise"),
                                rs.getLong("deposit_refunded_paise"),
                                rs.getLong("deposit_applied_paise"),
                                rs.getString("status"),
                                rs.getTimestamp("held_at").toInstant(),
                                rs.getTimestamp("refunded_at") != null ? rs.getTimestamp("refunded_at").toInstant() : null,
                                rs.getString("refund_ref"),
                                (UUID) rs.getObject("refund_approved_by"),
                                rs.getString("notes")
                        );
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get deposit: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public RefundableDepositRecord manageDeposit(StaffSession session, UUID bookingId, String action, long amountPaise, String reason) {
        checkStaff(session);
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(action, "action");
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }

        // Refund or forfeiture requires Administrator
        if ("REFUND".equalsIgnoreCase(action) || "APPLY".equalsIgnoreCase(action)) {
            checkAdminOnly(session, "refund or apply security deposits");
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session, Capability.POST_REFUND);

                UUID depositId;
                UUID customerId;
                long rec;
                long ref;
                long app;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, customer_id, deposit_received_paise, deposit_refunded_paise, deposit_applied_paise " +
                        "FROM vrms_private.refundable_deposits WHERE company_id = ? AND booking_id = ? FOR UPDATE")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, bookingId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalStateException("No deposit record found for booking " + bookingId);
                        depositId = (UUID) rs.getObject(1);
                        customerId = (UUID) rs.getObject(2);
                        rec = rs.getLong(3);
                        ref = rs.getLong(4);
                        app = rs.getLong(5);
                    }
                }

                long remaining = rec - (ref + app);
                if (amountPaise > remaining) {
                    throw new IllegalArgumentException("Action amount (₹" + (amountPaise/100) + ") exceeds remaining deposit held (₹" + (remaining/100) + ")");
                }

                long newRef = ref;
                long newApp = app;
                String newStatus;

                if ("REFUND".equalsIgnoreCase(action)) {
                    newRef += amountPaise;
                    newStatus = (newRef + newApp >= rec) ? "FULLY_REFUNDED" : "PARTIALLY_REFUNDED";
                    insertLedgerEntry(c, session.companyId(), bookingId, "REFUND_PAID", "DEBIT",
                            amountPaise, "DEPOSIT_REFUND", depositId, "Security deposit refund: " + reason, session.userId());
                } else if ("APPLY".equalsIgnoreCase(action)) {
                    newApp += amountPaise;
                    newStatus = (newRef + newApp >= rec) ? "FORFEITED_APPLIED" : "HELD";
                    insertLedgerEntry(c, session.companyId(), bookingId, "REVENUE_EARNED", "CREDIT",
                            amountPaise, "DEPOSIT_FORFEITURE", depositId, "Deposit applied to charges: " + reason, session.userId());
                } else {
                    throw new IllegalArgumentException("Unknown deposit action: " + action);
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vrms_private.refundable_deposits SET " +
                        "deposit_refunded_paise = ?, deposit_applied_paise = ?, status = ?, refunded_at = now(), " +
                        "refund_ref = ?, refund_approved_by = ?, notes = coalesce(notes, '') || ? " +
                        "WHERE id = ?")) {
                    ps.setLong(1, newRef);
                    ps.setLong(2, newApp);
                    ps.setString(3, newStatus);
                    ps.setString(4, "REF-" + bookingId.toString().substring(0, 8));
                    ps.setObject(5, session.userId());
                    ps.setString(6, " | " + action + ": ₹" + (amountPaise/100) + " (" + reason + ")");
                    ps.setObject(7, depositId);
                    ps.executeUpdate();
                }

                c.commit();

                return new RefundableDepositRecord(
                        depositId,
                        session.companyId(),
                        bookingId,
                        customerId,
                        rec,
                        newRef,
                        newApp,
                        newStatus,
                        Instant.now(),
                        Instant.now(),
                        "REF-" + bookingId.toString().substring(0, 8),
                        session.userId(),
                        reason
                );
            } catch (Exception e) {
                c.rollback();
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new IllegalStateException(e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to manage deposit: " + e.getMessage(), e);
        }
    }

    @Override
    public FinancialCorrectionRecord approveCorrection(StaffSession session, FinancialCorrectionCommand cmd) {
        checkAdminOnly(session, "approve financial corrections (credit notes and refunds)");
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(cmd.bookingId(), "bookingId");
        if (cmd.amountPaise() <= 0) {
            throw new IllegalArgumentException("Correction amount must be positive");
        }
        if (cmd.reason() == null || cmd.reason().isBlank()) {
            throw new IllegalArgumentException("Correction reason is mandatory");
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session, Capability.CORRECT_FINANCIAL_ENTRY);

                UUID corrId;
                Instant createdAt;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vrms_private.financial_corrections " +
                        "(company_id, booking_id, payment_id, correction_type, amount_paise, reason, approved_by, bank_payout_ref, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSED') RETURNING id, created_at")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, cmd.bookingId());
                    ps.setObject(3, cmd.paymentId());
                    ps.setString(4, cmd.correctionType() != null ? cmd.correctionType() : "CREDIT_NOTE");
                    ps.setLong(5, cmd.amountPaise());
                    ps.setString(6, cmd.reason().trim());
                    ps.setObject(7, session.userId());
                    ps.setString(8, cmd.bankPayoutRef());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        corrId = (UUID) rs.getObject(1);
                        createdAt = rs.getTimestamp(2).toInstant();
                    }
                }

                // If credit note, post ledger debit adjustment
                String entryType = "REFUND".equalsIgnoreCase(cmd.correctionType()) ? "REFUND_PAID" : "REVENUE_EARNED";
                String dir = "REFUND".equalsIgnoreCase(cmd.correctionType()) ? "DEBIT" : "DEBIT";
                insertLedgerEntry(c, session.companyId(), cmd.bookingId(), entryType, dir,
                        cmd.amountPaise(), "CORRECTION", corrId, cmd.correctionType() + ": " + cmd.reason(), session.userId());

                c.commit();

                if (auditPort != null) {
                    auditPort.recordEvent(session, "APPROVE_FINANCIAL_CORRECTION", "FINANCIAL_CORRECTION", corrId.toString(),
                            "{\"type\":\"" + cmd.correctionType() + "\",\"amountPaise\":" + cmd.amountPaise() + "}");
                }

                return new FinancialCorrectionRecord(
                        corrId,
                        session.companyId(),
                        cmd.bookingId(),
                        cmd.paymentId(),
                        cmd.correctionType(),
                        cmd.amountPaise(),
                        cmd.reason(),
                        session.userId(),
                        cmd.bankPayoutRef(),
                        "PROCESSED",
                        createdAt
                );
            } catch (Exception e) {
                c.rollback();
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new IllegalStateException(e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to approve correction: " + e.getMessage(), e);
        }
    }

    @Override
    public BillingDocumentRecord issueBillingDocument(StaffSession session, UUID bookingId, String documentType, String notes) {
        checkStaff(session);
        Objects.requireNonNull(bookingId, "bookingId");
        String type = documentType != null ? documentType : "PRO_FORMA_INVOICE";

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, session, Capability.FINALIZE_STANDARD_INVOICE);

                UUID customerId;
                String bookingNumber;
                long totalPaise;
                long actualAdvance;
                long depositPaise;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT customer_id, booking_number, total_paise, actual_advance_received_paise, deposit_paise " +
                        "FROM vrms_private.bookings WHERE company_id = ? AND id = ?")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, bookingId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("Booking not found: " + bookingId);
                        customerId = (UUID) rs.getObject(1);
                        bookingNumber = rs.getString(2);
                        totalPaise = rs.getLong(3);
                        actualAdvance = rs.getLong(4);
                        depositPaise = rs.getLong(5);
                    }
                }

                long balanceDue = totalPaise - actualAdvance;
                String docNumber = "VRMS-" + bookingNumber + "-" + System.currentTimeMillis() % 10000;
                String snapshotJson = "{\"bookingNumber\":\"" + bookingNumber + "\",\"totalPaise\":" + totalPaise + ",\"balanceDue\":" + balanceDue + "}";

                UUID docId;
                Instant issuedAt;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vrms_private.billing_documents " +
                        "(company_id, booking_id, customer_id, document_type, document_number, document_version, " +
                        "status, total_paise, advance_paid_paise, balance_due_paise, deposit_held_paise, tax_paise, " +
                        "is_beta_proforma, snapshot_json, issued_by) " +
                        "VALUES (?, ?, ?, ?, ?, 1, 'ISSUED', ?, ?, ?, ?, 0, true, ?, ?) RETURNING id, issued_at")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, bookingId);
                    ps.setObject(3, customerId);
                    ps.setString(4, type);
                    ps.setString(5, docNumber);
                    ps.setLong(6, totalPaise);
                    ps.setLong(7, actualAdvance);
                    ps.setLong(8, balanceDue);
                    ps.setLong(9, depositPaise);
                    ps.setString(10, snapshotJson);
                    ps.setObject(11, session.userId());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        docId = (UUID) rs.getObject(1);
                        issuedAt = rs.getTimestamp(2).toInstant();
                    }
                }

                // Add default billing item
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vrms_private.billing_items " +
                        "(company_id, billing_document_id, item_type, description, quantity, unit_rate_paise, total_paise) " +
                        "VALUES (?, ?, 'RENTAL_CHARGE', 'Commercial Vehicle Rental Package', 1, ?, ?)")) {
                    ps.setObject(1, session.companyId());
                    ps.setObject(2, docId);
                    ps.setLong(3, totalPaise);
                    ps.setLong(4, totalPaise);
                    ps.executeUpdate();
                }

                c.commit();

                return new BillingDocumentRecord(
                        docId,
                        session.companyId(),
                        bookingId,
                        customerId,
                        type,
                        docNumber,
                        1,
                        "ISSUED",
                        totalPaise,
                        actualAdvance,
                        balanceDue,
                        depositPaise,
                        0,
                        true,
                        snapshotJson,
                        null,
                        session.userId(),
                        issuedAt,
                        issuedAt
                );
            } catch (Exception e) {
                c.rollback();
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new IllegalStateException(e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to issue billing document: " + e.getMessage(), e);
        }
    }

    @Override
    public List<BillingDocumentRecord> listBillingDocuments(StaffSession session, UUID bookingId) {
        checkStaff(session);
        Objects.requireNonNull(bookingId, "bookingId");
        List<BillingDocumentRecord> list = new ArrayList<>();

        try (Connection c = dataSource.getConnection()) {
            setContext(c, session, Capability.READ_RENTAL_DOCUMENT);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, company_id, booking_id, customer_id, document_type, document_number, document_version, " +
                    "status, total_paise, advance_paid_paise, balance_due_paise, deposit_held_paise, tax_paise, " +
                    "is_beta_proforma, snapshot_json, file_metadata_id, issued_by, issued_at, created_at " +
                    "FROM vrms_private.billing_documents WHERE company_id = ? AND booking_id = ? ORDER BY created_at DESC")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new BillingDocumentRecord(
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("company_id"),
                                (UUID) rs.getObject("booking_id"),
                                (UUID) rs.getObject("customer_id"),
                                rs.getString("document_type"),
                                rs.getString("document_number"),
                                rs.getInt("document_version"),
                                rs.getString("status"),
                                rs.getLong("total_paise"),
                                rs.getLong("advance_paid_paise"),
                                rs.getLong("balance_due_paise"),
                                rs.getLong("deposit_held_paise"),
                                rs.getLong("tax_paise"),
                                rs.getBoolean("is_beta_proforma"),
                                rs.getString("snapshot_json"),
                                (UUID) rs.getObject("file_metadata_id"),
                                (UUID) rs.getObject("issued_by"),
                                rs.getTimestamp("issued_at").toInstant(),
                                rs.getTimestamp("created_at").toInstant()
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list billing documents: " + e.getMessage(), e);
        }
        return list;
    }

    @Override
    public byte[] renderDocumentPdf(StaffSession session, UUID documentId) {
        checkStaff(session);
        Objects.requireNonNull(documentId, "documentId");

        try (Connection c = dataSource.getConnection()) {
            setContext(c, session, Capability.READ_RENTAL_DOCUMENT);

            BillingDocumentRecord doc = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, company_id, booking_id, customer_id, document_type, document_number, document_version, " +
                    "status, total_paise, advance_paid_paise, balance_due_paise, deposit_held_paise, tax_paise, " +
                    "is_beta_proforma, snapshot_json, file_metadata_id, issued_by, issued_at, created_at " +
                    "FROM vrms_private.billing_documents WHERE company_id = ? AND id = ?")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, documentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        doc = new BillingDocumentRecord(
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("company_id"),
                                (UUID) rs.getObject("booking_id"),
                                (UUID) rs.getObject("customer_id"),
                                rs.getString("document_type"),
                                rs.getString("document_number"),
                                rs.getInt("document_version"),
                                rs.getString("status"),
                                rs.getLong("total_paise"),
                                rs.getLong("advance_paid_paise"),
                                rs.getLong("balance_due_paise"),
                                rs.getLong("deposit_held_paise"),
                                rs.getLong("tax_paise"),
                                rs.getBoolean("is_beta_proforma"),
                                rs.getString("snapshot_json"),
                                (UUID) rs.getObject("file_metadata_id"),
                                (UUID) rs.getObject("issued_by"),
                                rs.getTimestamp("issued_at").toInstant(),
                                rs.getTimestamp("created_at").toInstant()
                        );
                    }
                }
            }
            if (doc == null) {
                throw new IllegalArgumentException("Billing document not found: " + documentId);
            }

            // Customer details
            String customerName = "Valued Customer";
            String customerContact = "N/A";
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, contact_phone FROM vrms_private.customers WHERE company_id = ? AND id = ?")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, doc.customerId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        customerName = rs.getString(1);
                        customerContact = rs.getString(2);
                    }
                }
            }

            // Line items
            List<BillingItemRecord> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, company_id, billing_document_id, assignment_id, item_type, description, quantity, unit_rate_paise, total_paise, created_at " +
                    "FROM vrms_private.billing_items WHERE company_id = ? AND billing_document_id = ?")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, documentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(new BillingItemRecord(
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("company_id"),
                                (UUID) rs.getObject("billing_document_id"),
                                (UUID) rs.getObject("assignment_id"),
                                rs.getString("item_type"),
                                rs.getString("description"),
                                rs.getInt("quantity"),
                                rs.getLong("unit_rate_paise"),
                                rs.getLong("total_paise"),
                                rs.getTimestamp("created_at").toInstant()
                        ));
                    }
                }
            }

            // Verified payments
            List<PaymentRecord> payments = listPaymentsForBooking(session, doc.bookingId());

            return PdfDocumentGenerator.generateBillingDocumentPdf(doc, customerName, customerContact, items, payments);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to render PDF: " + e.getMessage(), e);
        }
    }

    @Override
    public CustomerStatement getCustomerStatement(StaffSession session, UUID customerId) {
        checkStaff(session);
        Objects.requireNonNull(customerId, "customerId");

        try (Connection c = dataSource.getConnection()) {
            setContext(c, session, Capability.VIEW_OPERATIONAL_BALANCE);

            String customerName = "Customer";
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM vrms_private.customers WHERE company_id = ? AND id = ?")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, customerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) customerName = rs.getString(1);
                }
            }

            long totalBilled = 0;
            long totalPaid = 0;
            long depositHeld = 0;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(total_paise), 0), coalesce(sum(actual_advance_received_paise), 0) " +
                    "FROM vrms_private.bookings WHERE company_id = ? AND customer_id = ?")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, customerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalBilled = rs.getLong(1);
                        totalPaid = rs.getLong(2);
                    }
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(deposit_received_paise - deposit_refunded_paise - deposit_applied_paise), 0) " +
                    "FROM vrms_private.refundable_deposits WHERE company_id = ? AND customer_id = ?")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, customerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        depositHeld = Math.max(0, rs.getLong(1));
                    }
                }
            }

            long balanceDue = totalBilled - totalPaid;

            List<PaymentRecord> payments = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, company_id, booking_id, customer_id, payment_request_id, amount_paise, currency, " +
                    "payment_method, external_reference, status, verification_evidence, verified_by, verified_at, notes, created_at " +
                    "FROM vrms_private.payments WHERE company_id = ? AND customer_id = ? ORDER BY created_at DESC LIMIT 50")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, customerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        payments.add(new PaymentRecord(
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("company_id"),
                                (UUID) rs.getObject("booking_id"),
                                (UUID) rs.getObject("customer_id"),
                                (UUID) rs.getObject("payment_request_id"),
                                rs.getLong("amount_paise"),
                                rs.getString("currency"),
                                rs.getString("payment_method"),
                                rs.getString("external_reference"),
                                rs.getString("status"),
                                rs.getString("verification_evidence"),
                                (UUID) rs.getObject("verified_by"),
                                rs.getTimestamp("verified_at").toInstant(),
                                rs.getString("notes"),
                                rs.getTimestamp("created_at").toInstant()
                        ));
                    }
                }
            }

            List<BillingDocumentRecord> documents = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, company_id, booking_id, customer_id, document_type, document_number, document_version, " +
                    "status, total_paise, advance_paid_paise, balance_due_paise, deposit_held_paise, tax_paise, " +
                    "is_beta_proforma, snapshot_json, file_metadata_id, issued_by, issued_at, created_at " +
                    "FROM vrms_private.billing_documents WHERE company_id = ? AND customer_id = ? ORDER BY created_at DESC LIMIT 50")) {
                ps.setObject(1, session.companyId());
                ps.setObject(2, customerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        documents.add(new BillingDocumentRecord(
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("company_id"),
                                (UUID) rs.getObject("booking_id"),
                                (UUID) rs.getObject("customer_id"),
                                rs.getString("document_type"),
                                rs.getString("document_number"),
                                rs.getInt("document_version"),
                                rs.getString("status"),
                                rs.getLong("total_paise"),
                                rs.getLong("advance_paid_paise"),
                                rs.getLong("balance_due_paise"),
                                rs.getLong("deposit_held_paise"),
                                rs.getLong("tax_paise"),
                                rs.getBoolean("is_beta_proforma"),
                                rs.getString("snapshot_json"),
                                (UUID) rs.getObject("file_metadata_id"),
                                (UUID) rs.getObject("issued_by"),
                                rs.getTimestamp("issued_at").toInstant(),
                                rs.getTimestamp("created_at").toInstant()
                        ));
                    }
                }
            }

            return new CustomerStatement(
                    customerId,
                    customerName,
                    totalBilled,
                    totalPaid,
                    balanceDue,
                    depositHeld,
                    payments,
                    documents
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get customer statement: " + e.getMessage(), e);
        }
    }

    @Override
    public LedgerSummary getLedgerSummary(StaffSession session, Instant from, Instant to) {
        checkAdminOnly(session, "view company financial ledger or operating margin (FR-ACC-003, FR-FIN-005)");
        Instant f = from != null ? from : Instant.now().minusSeconds(86400 * 30);
        Instant t = to != null ? to : Instant.now();

        try (Connection c = dataSource.getConnection()) {
            setContext(c, session, Capability.VIEW_COMPANY_MARGIN);

            long revenueEarned = 0;
            long cashCollected = 0;
            long depositLiability = 0;
            long expenses = 0;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(amount_paise), 0) FROM vrms_private.ledger_entries " +
                    "WHERE company_id = ? AND entry_type = 'CASH_RECEIVED' AND posted_at BETWEEN ? AND ?")) {
                ps.setObject(1, session.companyId());
                ps.setTimestamp(2, Timestamp.from(f));
                ps.setTimestamp(3, Timestamp.from(t));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) cashCollected = rs.getLong(1);
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(total_paise), 0) FROM vrms_private.bookings " +
                    "WHERE company_id = ? AND created_at BETWEEN ? AND ?")) {
                ps.setObject(1, session.companyId());
                ps.setTimestamp(2, Timestamp.from(f));
                ps.setTimestamp(3, Timestamp.from(t));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) revenueEarned = rs.getLong(1);
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(deposit_received_paise - deposit_refunded_paise - deposit_applied_paise), 0) " +
                    "FROM vrms_private.refundable_deposits WHERE company_id = ?")) {
                ps.setObject(1, session.companyId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) depositLiability = Math.max(0, rs.getLong(1));
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(actual_cost_paise), 0) FROM vrms_private.maintenance_cases " +
                    "WHERE company_id = ? AND created_at BETWEEN ? AND ?")) {
                ps.setObject(1, session.companyId());
                ps.setTimestamp(2, Timestamp.from(f));
                ps.setTimestamp(3, Timestamp.from(t));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) expenses += rs.getLong(1);
                }
            }

            long receivables = Math.max(0, revenueEarned - cashCollected);

            return FinancialLedgerEngine.calculateSummary(f, t, revenueEarned, cashCollected, receivables, depositLiability, expenses);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to calculate ledger summary: " + e.getMessage(), e);
        }
    }
}
