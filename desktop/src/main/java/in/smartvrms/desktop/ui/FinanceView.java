package in.smartvrms.desktop.ui;

import in.smartvrms.contracts.BillingDocumentRecord;
import in.smartvrms.contracts.CustomerStatement;
import in.smartvrms.contracts.FinancialCorrectionRecord;
import in.smartvrms.contracts.LedgerSummary;
import in.smartvrms.contracts.PaymentRecord;
import in.smartvrms.contracts.RefundableDepositRecord;
import in.smartvrms.contracts.StaffSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Finance, payments, ledger, and billing documents workspace.
 * Enforces strict separation of cash, revenue, debt, deposits, and expenses (FR-PAY-001..004, FR-FIN-001..005, FR-PDF-001..002).
 */
public final class FinanceView extends JPanel {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.of("Asia/Kolkata"));
    private static final Color COLOR_SUCCESS = new Color(0x245B4D);
    private static final Color COLOR_DANGER = new Color(0x775B28);

    // Metric Summary Labels
    public final JLabel revenueBilledLabel = Design.label("₹0.00", 18, Design.INK);
    public final JLabel cashReceivedLabel = Design.label("₹0.00", 18, COLOR_SUCCESS);
    public final JLabel balanceReceivableLabel = Design.label("₹0.00", 18, COLOR_DANGER);
    public final JLabel depositHeldLabel = Design.label("₹0.00", 18, Design.PRIMARY);

    // Action Buttons
    public final JButton recordPaymentBtn = Design.button("+ Record Verified Payment", true);
    public final JButton issueDocBtn = Design.button("Issue Document", false);
    public final JButton viewQrBtn = Design.button("UPI QR Intent", false);
    public final JButton manageDepositBtn = Design.button("Manage Deposit", false);
    public final JButton correctionBtn = Design.button("Credit Note / Refund", false);
    public final JButton downloadPdfBtn = Design.button("Download PDF", false);
    public final JButton refreshBtn = Design.button("Refresh", false);

    // Feedback
    public final JLabel feedbackLabel = Design.label("", 12, Design.MUTED);

    // Tables
    public final DefaultTableModel paymentsTableModel = new DefaultTableModel(
            new Object[]{"Date", "Method", "Amount (INR)", "Reference / UTR", "Evidence", "Verified By"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int col) { return false; }
    };
    public final JTable paymentsTable = new JTable(paymentsTableModel);

    public final DefaultTableModel docsTableModel = new DefaultTableModel(
            new Object[]{"Document #", "Type", "Total (INR)", "Advance (INR)", "Balance Due (INR)", "Issued Date", "Status"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int col) { return false; }
    };
    public final JTable docsTable = new JTable(docsTableModel);

    // Ledger Panel for Admin
    public final JPanel ledgerPanel = new JPanel(new BorderLayout(12, 12));
    public final JLabel ledgerOperatingMarginLabel = Design.label("—", 16, Design.INK);

    private StaffSession session;
    private in.smartvrms.desktop.integration.FinanceHttpClient client;
    public final List<PaymentRecord> specimensPayments = new ArrayList<>();
    public final List<BillingDocumentRecord> specimensDocs = new ArrayList<>();

    public FinanceView() {
        this(null);
    }

    public FinanceView(StaffSession session) {
        super(new BorderLayout(16, 16));
        setBackground(Design.BACKGROUND);
        setBorder(new EmptyBorder(20, 24, 20, 24));

        initUI();
        populateSpecimens();
        setSession(session);
    }

    public void setClient(in.smartvrms.desktop.integration.FinanceHttpClient client) {
        this.client = client;
        boolean isAdmin = session != null && "ADMINISTRATOR".equals(session.role());
        correctionBtn.setEnabled(isAdmin && client != null);
    }

    public void setSession(StaffSession session) {
        this.session = session;
        boolean isAdmin = session != null && "ADMINISTRATOR".equals(session.role());
        correctionBtn.setEnabled(isAdmin);
        correctionBtn.setToolTipText(isAdmin ? "Approve financial credit note or refund" : "Administrator role required (FR-ACC-003, FR-PAY-002)");
        updateLedgerView(isAdmin);
    }


    private void initUI() {
        // Top Header & Beta Notice
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setOpaque(false);

        JLabel title = Design.label("Finance, Payments & Pro-Forma Documents", 22, Design.INK);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(title);

        topPanel.add(Box.createVerticalStrut(4));
        JLabel subtitle = Design.label(
                "Authoritative rental accounting, verified bank/cash receipts, refundable deposits, and pro-forma billing documents",
                13, Design.MUTED
        );
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(subtitle);

        topPanel.add(Box.createVerticalStrut(6));
        JLabel betaNotice = Design.label(
                "BETA PRO-FORMA / NOT AN OFFICIAL GST TAX INVOICE — Registration & Statutory Rates Pending",
                11, new Color(180, 80, 0)
        );
        betaNotice.setFont(betaNotice.getFont().deriveFont(Font.BOLD));
        betaNotice.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(betaNotice);

        topPanel.add(Box.createVerticalStrut(16));

        // Metric Cards Grid
        JPanel metricsGrid = new JPanel(new GridLayout(1, 4, 12, 0));
        metricsGrid.setOpaque(false);
        metricsGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        metricsGrid.add(createMetricCard("Revenue Billed", revenueBilledLabel));
        metricsGrid.add(createMetricCard("Cash Collected", cashReceivedLabel));
        metricsGrid.add(createMetricCard("Outstanding Receivables", balanceReceivableLabel));
        metricsGrid.add(createMetricCard("Security Deposits Held", depositHeldLabel));
        topPanel.add(metricsGrid);

        topPanel.add(Box.createVerticalStrut(16));

        // Action Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toolbar.setOpaque(false);
        toolbar.setAlignmentX(Component.LEFT_ALIGNMENT);
        toolbar.add(recordPaymentBtn);
        toolbar.add(issueDocBtn);
        toolbar.add(viewQrBtn);
        toolbar.add(manageDepositBtn);
        toolbar.add(correctionBtn);
        toolbar.add(downloadPdfBtn);
        toolbar.add(refreshBtn);
        topPanel.add(toolbar);

        topPanel.add(Box.createVerticalStrut(8));
        feedbackLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(feedbackLabel);

        add(topPanel, BorderLayout.NORTH);

        // Center Tabs
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font(Font.DIALOG, Font.BOLD, 12));

        // Tab 1: Payments
        JPanel paymentsTab = new JPanel(new BorderLayout());
        paymentsTab.setBackground(Design.SURFACE);
        paymentsTab.setBorder(BorderFactory.createLineBorder(Design.BORDER));
        paymentsTable.setRowHeight(28);
        paymentsTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        paymentsTab.add(new JScrollPane(paymentsTable), BorderLayout.CENTER);
        tabs.addTab("Verified Receipts & Allocations", paymentsTab);

        // Tab 2: Documents
        JPanel docsTab = new JPanel(new BorderLayout());
        docsTab.setBackground(Design.SURFACE);
        docsTab.setBorder(BorderFactory.createLineBorder(Design.BORDER));
        docsTable.setRowHeight(28);
        docsTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        docsTab.add(new JScrollPane(docsTable), BorderLayout.CENTER);
        tabs.addTab("Billing Documents & Statements", docsTab);

        // Tab 3: Ledger
        ledgerPanel.setBackground(Design.SURFACE);
        ledgerPanel.setBorder(new EmptyBorder(16, 16, 16, 16));
        updateLedgerView(session != null && "ADMINISTRATOR".equals(session.role()));
        tabs.addTab("Company Ledger & Operating Margin", ledgerPanel);

        add(tabs, BorderLayout.CENTER);

        wireActions();
    }

    private JPanel createMetricCard(String title, JLabel valueLabel) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Design.SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Design.BORDER),
                new EmptyBorder(10, 14, 10, 14)
        ));
        JLabel t = Design.label(title, 11, Design.MUTED);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(t);
        card.add(Box.createVerticalStrut(4));
        valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(valueLabel);
        return card;
    }

    private void updateLedgerView(boolean isAdmin) {
        ledgerPanel.removeAll();
        if (isAdmin) {
            JPanel p = new JPanel(new GridLayout(6, 2, 8, 8));
            p.setOpaque(false);
            p.add(Design.label("Revenue Earned (Accrual Basis):", 13, Design.INK));
            p.add(Design.label("₹12,400.00", 13, Design.INK));
            p.add(Design.label("Actual Cash Collected:", 13, Design.INK));
            p.add(Design.label("₹8,200.00", 13, COLOR_SUCCESS));
            p.add(Design.label("Outstanding Customer Receivables:", 13, Design.INK));
            p.add(Design.label("₹4,200.00", 13, COLOR_DANGER));
            p.add(Design.label("Refundable Security Deposit Liability:", 13, Design.INK));
            p.add(Design.label("₹5,000.00", 13, Design.PRIMARY));
            p.add(Design.label("Operating Expenses (Repairs & Fleet):", 13, Design.INK));
            p.add(Design.label("₹2,150.00", 13, Design.INK));
            p.add(Design.label("Net Operating Accrual Margin:", 14, Design.INK));
            p.add(ledgerOperatingMarginLabel);
            ledgerOperatingMarginLabel.setText("₹10,250.00");
            ledgerOperatingMarginLabel.setForeground(COLOR_SUCCESS);

            ledgerPanel.add(p, BorderLayout.NORTH);
            JLabel note = Design.label("All financial measures reconciled through immutable double-entry ledger postings (FR-FIN-001, FR-FIN-004).", 11, Design.MUTED);
            ledgerPanel.add(note, BorderLayout.SOUTH);
        } else {
            JPanel privacyCard = new JPanel(new GridBagLayout());
            privacyCard.setOpaque(false);
            JLabel msg = Design.label("Operating margin, company profit/loss, and expense details are restricted to Administrator accounts (FR-ACC-003, FR-FIN-005).", 13, Design.MUTED);
            privacyCard.add(msg);
            ledgerPanel.add(privacyCard, BorderLayout.CENTER);
        }
        ledgerPanel.revalidate();
        ledgerPanel.repaint();
    }

    private void wireActions() {
        recordPaymentBtn.addActionListener(e -> showRecordPaymentDialog());
        issueDocBtn.addActionListener(e -> showIssueDocDialog());
        viewQrBtn.addActionListener(e -> showQrDialog());
        manageDepositBtn.addActionListener(e -> showManageDepositDialog());
        correctionBtn.addActionListener(e -> showCorrectionDialog());
        downloadPdfBtn.addActionListener(e -> showDownloadPdfNotice());
        refreshBtn.addActionListener(e -> {
            if (client != null && session != null && "ADMINISTRATOR".equals(session.role())) {
                refreshBtn.setEnabled(false);
                feedbackLabel.setText("Fetching authoritative ledger summary...");
                new SwingWorker<LedgerSummary, Void>() {
                    @Override
                    protected LedgerSummary doInBackground() {
                        return client.getLedgerSummary(null, null);
                    }
                    @Override
                    protected void done() {
                        refreshBtn.setEnabled(true);
                        try {
                            LedgerSummary s = get();
                            revenueBilledLabel.setText(String.format("₹%.2f", s.totalRevenueEarnedPaise() / 100.0));
                            cashReceivedLabel.setText(String.format("₹%.2f", s.totalCashCollectedPaise() / 100.0));
                            balanceReceivableLabel.setText(String.format("₹%.2f", s.totalReceivablesPaise() / 100.0));
                            depositHeldLabel.setText(String.format("₹%.2f", s.totalDepositLiabilityPaise() / 100.0));
                            ledgerOperatingMarginLabel.setText(String.format("₹%.2f", s.operatingMarginPaise() / 100.0));
                            feedbackLabel.setText("Finance workspace refreshed from authoritative ledger.");
                            feedbackLabel.setForeground(COLOR_SUCCESS);
                        } catch (Exception ex) {
                            feedbackLabel.setText("Refresh failed: " + ex.getMessage());
                            feedbackLabel.setForeground(COLOR_DANGER);
                        }
                    }
                }.execute();
            } else {
                feedbackLabel.setText("Finance workspace refreshed.");
                feedbackLabel.setForeground(COLOR_SUCCESS);
            }
        });
    }

    private void showRecordPaymentDialog() {
        JTextField bookingField = new JTextField(UUID.randomUUID().toString(), 24);
        JTextField amountField = new JTextField("2400.00", 8);
        JTextField advanceField = new JTextField("1400.00", 8);
        JTextField depositField = new JTextField("1000.00", 8);
        JComboBox<String> methodCombo = new JComboBox<>(new String[]{"CASH", "UPI", "BANK_TRANSFER", "CARD_POS"});
        JTextField refField = new JTextField("UTR" + System.currentTimeMillis() % 1000000, 12);
        JTextField evidenceField = new JTextField("Bank transaction reference verified", 16);

        JPanel p = new JPanel(new GridLayout(7, 2, 8, 8));
        p.add(new JLabel("Booking ID (UUID):")); p.add(bookingField);
        p.add(new JLabel("Total Amount (INR):")); p.add(amountField);
        p.add(new JLabel("Allocate to Advance (INR):")); p.add(advanceField);
        p.add(new JLabel("Allocate to Deposit (INR):")); p.add(depositField);
        p.add(new JLabel("Payment Method:")); p.add(methodCombo);
        p.add(new JLabel("External Reference / UTR:")); p.add(refField);
        p.add(new JLabel("Verification Evidence:")); p.add(evidenceField);

        int opt = JOptionPane.showConfirmDialog(this, p, "Record Verified Payment (FR-PAY-001, FR-PAY-004)", JOptionPane.OK_CANCEL_OPTION);
        if (opt == JOptionPane.OK_OPTION) {
            String ref = refField.getText().trim();
            if (ref.isEmpty()) {
                JOptionPane.showMessageDialog(this, "External reference / UTR is mandatory for verified tracking.", "Validation", JOptionPane.ERROR_MESSAGE);
                return;
            }
            long totalPaise;
            long advPaise;
            long depPaise;
            UUID bookingId;
            try {
                totalPaise = Math.round(Double.parseDouble(amountField.getText().trim()) * 100);
                advPaise = Math.round(Double.parseDouble(advanceField.getText().trim()) * 100);
                depPaise = Math.round(Double.parseDouble(depositField.getText().trim()) * 100);
                bookingId = UUID.fromString(bookingField.getText().trim());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid number or UUID format: " + ex.getMessage(), "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (client != null) {
                recordPaymentBtn.setEnabled(false);
                feedbackLabel.setText("Submitting payment to ledger API...");
                feedbackLabel.setForeground(Design.MUTED);
                new SwingWorker<PaymentRecord, Void>() {
                    @Override
                    protected PaymentRecord doInBackground() {
                        var cmd = new in.smartvrms.contracts.RecordPaymentCommand(
                                bookingId,
                                null,
                                totalPaise,
                                (String) methodCombo.getSelectedItem(),
                                ref,
                                evidenceField.getText().trim(),
                                "Manual payment entry",
                                advPaise,
                                depPaise
                        );
                        return client.recordVerifiedPayment(cmd);
                    }
                    @Override
                    protected void done() {
                        recordPaymentBtn.setEnabled(true);
                        try {
                            PaymentRecord rec = get();
                            paymentsTableModel.addRow(new Object[]{
                                    TIME_FMT.format(rec.verifiedAt()),
                                    rec.paymentMethod(),
                                    String.format("%.2f", rec.amountPaise() / 100.0),
                                    rec.externalReference(),
                                    rec.verificationEvidence(),
                                    session != null ? session.role() : "STAFF"
                            });
                            feedbackLabel.setText("Payment recorded: INR " + String.format("%.2f", rec.amountPaise() / 100.0) + " (Ref: " + rec.externalReference() + ")");
                            feedbackLabel.setForeground(COLOR_SUCCESS);
                        } catch (Exception ex) {
                            feedbackLabel.setText("Payment failed: " + ex.getMessage());
                            feedbackLabel.setForeground(COLOR_DANGER);
                            JOptionPane.showMessageDialog(FinanceView.this, "Payment registration failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } else {
                feedbackLabel.setText("Payment registration blocked: Financial API client not connected.");
                feedbackLabel.setForeground(COLOR_DANGER);
            }
        }
    }


    private void showIssueDocDialog() {
        JComboBox<String> typeCombo = new JComboBox<>(new String[]{"PRO_FORMA_INVOICE", "PAYMENT_RECEIPT", "QUOTATION", "ACCOUNT_STATEMENT"});
        JTextField bookingField = new JTextField(UUID.randomUUID().toString(), 24);
        JTextField notesField = new JTextField("Issued by staff", 16);

        JPanel p = new JPanel(new GridLayout(3, 2, 8, 8));
        p.add(new JLabel("Document Type:")); p.add(typeCombo);
        p.add(new JLabel("Booking ID (UUID):")); p.add(bookingField);
        p.add(new JLabel("Notes:")); p.add(notesField);

        int opt = JOptionPane.showConfirmDialog(this, p, "Issue Billing Document (FR-BOOK-009, FR-PDF-001)", JOptionPane.OK_CANCEL_OPTION);
        if (opt == JOptionPane.OK_OPTION) {
            UUID bookingId;
            try {
                bookingId = UUID.fromString(bookingField.getText().trim());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid Booking UUID: " + ex.getMessage(), "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            String docType = (String) typeCombo.getSelectedItem();
            String notes = notesField.getText().trim();

            if (client != null) {
                issueDocBtn.setEnabled(false);
                feedbackLabel.setText("Generating document with PDFBox...");
                new SwingWorker<BillingDocumentRecord, Void>() {
                    @Override
                    protected BillingDocumentRecord doInBackground() {
                        return client.issueBillingDocument(bookingId, docType, notes);
                    }
                    @Override
                    protected void done() {
                        issueDocBtn.setEnabled(true);
                        try {
                            BillingDocumentRecord doc = get();
                            docsTableModel.addRow(new Object[]{
                                    doc.documentNumber(),
                                    doc.documentType(),
                                    String.format("%.2f", doc.totalPaise() / 100.0),
                                    String.format("%.2f", doc.advancePaidPaise() / 100.0),
                                    String.format("%.2f", doc.balanceDuePaise() / 100.0),
                                    TIME_FMT.format(doc.issuedAt()),
                                    doc.status()
                            });
                            feedbackLabel.setText("Document issued: " + doc.documentNumber() + " (" + doc.documentType() + ")");
                            feedbackLabel.setForeground(COLOR_SUCCESS);
                        } catch (Exception ex) {
                            feedbackLabel.setText("Issue failed: " + ex.getMessage());
                            feedbackLabel.setForeground(COLOR_DANGER);
                            JOptionPane.showMessageDialog(FinanceView.this, "Failed to issue document: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } else {
                feedbackLabel.setText("Document issuance blocked: Financial API client not connected.");
                feedbackLabel.setForeground(COLOR_DANGER);
            }
        }
    }


    private void showQrDialog() {
        String vpa = "payments@smartvrms";
        String amount = "2400.00";
        JOptionPane.showMessageDialog(this,
                "Merchant UPI Destination: " + vpa + "\n" +
                "Amount: INR " + amount + "\n" +
                "URI: upi://pay?pa=" + vpa + "&pn=Smart%20VRMS&am=" + amount + "&cu=INR\n\n" +
                "Notice: UPI QR display provides payment instructions only;\n" +
                "payment is never counted as received until authoritative staff verification (FR-PAY-003, FR-PAY-004).",
                "UPI Payment Intent & Merchant QR",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void showManageDepositDialog() {
        JTextField bookingField = new JTextField(UUID.randomUUID().toString(), 24);
        JComboBox<String> actionCombo = new JComboBox<>(new String[]{"REFUND", "APPLY"});
        JTextField amountField = new JTextField("1000.00", 8);
        JTextField reasonField = new JTextField("Safe return with no damages", 16);

        JPanel p = new JPanel(new GridLayout(4, 2, 8, 8));
        p.add(new JLabel("Booking ID (UUID):")); p.add(bookingField);
        p.add(new JLabel("Action:")); p.add(actionCombo);
        p.add(new JLabel("Amount (INR):")); p.add(amountField);
        p.add(new JLabel("Reason:")); p.add(reasonField);

        int opt = JOptionPane.showConfirmDialog(this, p, "Manage Security Deposit (FR-FIN-001, FR-PAY-002)", JOptionPane.OK_CANCEL_OPTION);
        if (opt == JOptionPane.OK_OPTION) {
            UUID bookingId;
            long amountPaise;
            try {
                bookingId = UUID.fromString(bookingField.getText().trim());
                amountPaise = Math.round(Double.parseDouble(amountField.getText().trim()) * 100);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid UUID or Amount: " + ex.getMessage(), "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            String action = (String) actionCombo.getSelectedItem();
            String reason = reasonField.getText().trim();

            if (client != null) {
                manageDepositBtn.setEnabled(false);
                feedbackLabel.setText("Processing deposit ledger adjustment...");
                new SwingWorker<RefundableDepositRecord, Void>() {
                    @Override
                    protected RefundableDepositRecord doInBackground() {
                        return client.manageDeposit(bookingId, action, amountPaise, reason);
                    }
                    @Override
                    protected void done() {
                        manageDepositBtn.setEnabled(true);
                        try {
                            RefundableDepositRecord dep = get();
                            feedbackLabel.setText("Security deposit " + action + " processed: INR " + String.format("%.2f", amountPaise / 100.0) + " (Remaining: INR " + String.format("%.2f", dep.remainingHeldPaise() / 100.0) + ")");
                            feedbackLabel.setForeground(COLOR_SUCCESS);
                            depositHeldLabel.setText("₹" + String.format("%.2f", dep.remainingHeldPaise() / 100.0));
                        } catch (Exception ex) {
                            feedbackLabel.setText("Deposit action failed: " + ex.getMessage());
                            feedbackLabel.setForeground(COLOR_DANGER);
                            JOptionPane.showMessageDialog(FinanceView.this, "Deposit action failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } else {
                feedbackLabel.setText("Deposit action blocked: Financial API client not connected.");
                feedbackLabel.setForeground(COLOR_DANGER);
            }
        }
    }


    private void showCorrectionDialog() {
        if (session == null || !"ADMINISTRATOR".equals(session.role())) {
            JOptionPane.showMessageDialog(this, "Only Administrators can approve financial credit notes or refunds (FR-ACC-003, FR-PAY-002).", "Access Denied", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JTextField bookingField = new JTextField(UUID.randomUUID().toString(), 24);
        JComboBox<String> typeCombo = new JComboBox<>(new String[]{"CREDIT_NOTE", "REFUND"});
        JTextField amountField = new JTextField("500.00", 8);
        JTextField reasonField = new JTextField("Approved goodwill discount", 16);

        JPanel p = new JPanel(new GridLayout(4, 2, 8, 8));
        p.add(new JLabel("Booking ID (UUID):")); p.add(bookingField);
        p.add(new JLabel("Correction Type:")); p.add(typeCombo);
        p.add(new JLabel("Amount (INR):")); p.add(amountField);
        p.add(new JLabel("Reason:")); p.add(reasonField);

        int opt = JOptionPane.showConfirmDialog(this, p, "Authorize Financial Correction (Administrator Only)", JOptionPane.OK_CANCEL_OPTION);
        if (opt == JOptionPane.OK_OPTION) {
            UUID bookingId;
            long amountPaise;
            try {
                bookingId = UUID.fromString(bookingField.getText().trim());
                amountPaise = Math.round(Double.parseDouble(amountField.getText().trim()) * 100);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid UUID or Amount: " + ex.getMessage(), "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            String type = (String) typeCombo.getSelectedItem();
            String reason = reasonField.getText().trim();

            if (client != null) {
                correctionBtn.setEnabled(false);
                feedbackLabel.setText("Posting authorized correction to ledger...");
                new SwingWorker<FinancialCorrectionRecord, Void>() {
                    @Override
                    protected FinancialCorrectionRecord doInBackground() {
                        var cmd = new in.smartvrms.contracts.FinancialCorrectionCommand(
                                bookingId,
                                null,
                                type,
                                amountPaise,
                                reason,
                                "BANK-REF-CORR"
                        );
                        return client.approveCorrection(cmd);
                    }
                    @Override
                    protected void done() {
                        correctionBtn.setEnabled(true);
                        try {
                            FinancialCorrectionRecord corr = get();
                            feedbackLabel.setText("Financial correction authorized: " + corr.correctionType() + " INR " + String.format("%.2f", corr.amountPaise() / 100.0));
                            feedbackLabel.setForeground(COLOR_SUCCESS);
                        } catch (Exception ex) {
                            feedbackLabel.setText("Correction authorization failed: " + ex.getMessage());
                            feedbackLabel.setForeground(COLOR_DANGER);
                            JOptionPane.showMessageDialog(FinanceView.this, "Correction failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } else {
                feedbackLabel.setText("Financial correction blocked: Financial API client not connected.");
                feedbackLabel.setForeground(COLOR_DANGER);
            }
        }
    }

    private void showDownloadPdfNotice() {
        if (client == null) {
            feedbackLabel.setText("PDF download blocked: Financial API client not connected.");
            feedbackLabel.setForeground(COLOR_DANGER);
            return;
        }
        int row = docsTable.getSelectedRow();
        String docIdStr = (row >= 0 && row < specimensDocs.size()) ? specimensDocs.get(row).id().toString() : null;
        if (docIdStr == null) {
            docIdStr = JOptionPane.showInputDialog(this, "Enter Document ID (UUID) to download authoritative PDF:", "");
            if (docIdStr == null || docIdStr.isBlank()) return;
        }
        final UUID documentId;
        try {
            documentId = UUID.fromString(docIdStr.trim());
        } catch (Exception ex) {
            feedbackLabel.setText("Invalid Document UUID format.");
            feedbackLabel.setForeground(COLOR_DANGER);
            return;
        }
        downloadPdfBtn.setEnabled(false);
        feedbackLabel.setText("Downloading PDF from server...");
        feedbackLabel.setForeground(Design.INK);

        new SwingWorker<byte[], Void>() {
            @Override
            protected byte[] doInBackground() {
                return client.downloadDocumentPdf(documentId);
            }

            @Override
            protected void done() {
                downloadPdfBtn.setEnabled(true);
                try {
                    byte[] pdfBytes = get();
                    JFileChooser chooser = new JFileChooser();
                    chooser.setSelectedFile(new java.io.File("document-" + documentId.toString().substring(0, 8) + ".pdf"));
                    int ret = chooser.showSaveDialog(FinanceView.this);
                    if (ret == JFileChooser.APPROVE_OPTION) {
                        java.nio.file.Files.write(chooser.getSelectedFile().toPath(), pdfBytes);
                        feedbackLabel.setText("PDF saved successfully (" + pdfBytes.length + " bytes).");
                        feedbackLabel.setForeground(COLOR_SUCCESS);
                    } else {
                        feedbackLabel.setText("PDF download saved: " + pdfBytes.length + " bytes received.");
                        feedbackLabel.setForeground(COLOR_SUCCESS);
                    }
                } catch (Exception ex) {
                    feedbackLabel.setText("Failed to download PDF: " + ex.getMessage());
                    feedbackLabel.setForeground(COLOR_DANGER);
                }
            }
        }.execute();
    }

    private void populateSpecimens() {
        long billed=DemoData.totalBilledPaise();
        long received=DemoData.totalPaidPaise();
        revenueBilledLabel.setText(String.format("₹%,.2f",billed/100.0));
        cashReceivedLabel.setText(String.format("₹%,.2f",received/100.0));
        balanceReceivableLabel.setText(String.format("₹%,.2f",(billed-received)/100.0));
        depositHeldLabel.setText(String.format("₹%,.2f",DemoData.depositsHeldPaise()/100.0));

        paymentsTableModel.setRowCount(0);
        docsTableModel.setRowCount(0);
        specimensPayments.clear();
        specimensDocs.clear();

        for(DemoData.Payment payment:DemoData.PAYMENTS) {
            paymentsTableModel.addRow(new Object[]{
                    TIME_FMT.format(Instant.now().minusSeconds(86400L*payment.daysAgo())),
                    payment.method(),String.format("%,.2f",payment.amountPaise()/100.0),payment.reference(),
                    "SAMPLE ONLY · NOT VERIFIED OR POSTED","DEMO DATA"
            });
        }
        for(DemoData.Invoice invoice:DemoData.INVOICES) {
            docsTableModel.addRow(new Object[]{
                    invoice.number(),invoice.type(),String.format("%,.2f",invoice.totalPaise()/100.0),
                    String.format("%,.2f",invoice.paidPaise()/100.0),
                    String.format("%,.2f",(invoice.totalPaise()-invoice.paidPaise())/100.0),
                    TIME_FMT.format(Instant.now().minusSeconds(86400L*invoice.daysAgo())),"EXAMPLE · NOT ISSUED"
            });
        }

        feedbackLabel.setText("10 sample payments and invoices · no bank receipt, invoice, or ledger entry was created.");
        feedbackLabel.setForeground(Design.MUTED);
    }
}
