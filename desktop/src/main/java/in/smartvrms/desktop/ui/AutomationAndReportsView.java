package in.smartvrms.desktop.ui;

import in.smartvrms.contracts.*;
import in.smartvrms.desktop.integration.AutomationHttpClient;
import in.smartvrms.desktop.integration.ReportingHttpClient;
import in.smartvrms.desktop.integration.ReviewHttpClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Desktop UI for Stage 7: Durable Automation Outbox, Service Reviews, and Periodic Reports.
 * Satisfies:
 * - FR-DOC-003, FR-DOC-004: Document expiry scans and renewal invalidation
 * - FR-BOOK-011, FR-MSG-001, FR-MSG-002: Transactional outbox, delivery attempts, and resend protection
 * - FR-REV-001, FR-REV-002: Optional staff-recorded reviews with correction history
 * - FR-FIN-003, FR-FIN-005, FR-ACC-003: Operational and Admin-only financial period reports
 * - NFR-HOST-001, NFR-REL-001: Honest host scheduling disclosure and zero simulated delivery
 */
public final class AutomationAndReportsView extends JPanel {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.of("Asia/Kolkata"));
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.of("Asia/Kolkata"));

    // Session State & Clients
    private StaffSession session;
    private AutomationHttpClient automationClient;
    private ReviewHttpClient reviewClient;
    private ReportingHttpClient reportingClient;

    // Feedback status
    public final JLabel feedbackLabel = Design.label("", 12, Design.MUTED);

    // ==========================================
    // Tab 1: Automation & Outbox Components
    // ==========================================
    public final JLabel workerHealthLabel = Design.label("NO SENDER", 16, new Color(0x775B28));
    public final JLabel pendingCountLabel = Design.label("—", 18, Design.INK);
    public final JLabel claimedCountLabel = Design.label("—", 18, Design.INK);
    public final JLabel acceptedCountLabel = Design.label("—", 18, new Color(0x245B4D));
    public final JLabel failedCountLabel = Design.label("—", 18, new Color(0x775B28));
    public final JLabel lastScanLabel = Design.label("Not scanned yet", 13, Design.MUTED);

    public final JButton scanRemindersBtn = Design.button("Scan Expiry Reminders (<72h)", false);
    public final JButton processBatchBtn = Design.button("Email delivery unavailable", true);
    public final JButton refreshOutboxBtn = Design.button("Refresh Outbox", false);

    public final DefaultTableModel outboxTableModel = new DefaultTableModel(
            new Object[]{"ID", "Event Type", "Recipient", "Role", "Subject", "Status", "Attempts", "Next Attempt"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int col) { return false; }
    };
    public final JTable outboxTable = new JTable(outboxTableModel);

    public final DefaultTableModel attemptsTableModel = new DefaultTableModel(
            new Object[]{"Attempt #", "Started At", "Provider", "Outcome", "Response Code", "Error / Details"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int col) { return false; }
    };
    public final JTable attemptsTable = new JTable(attemptsTableModel);

    // ==========================================
    // Tab 2: Service Reviews Components
    // ==========================================
    public final JLabel totalReviewsLabel = Design.label("0", 18, Design.INK);
    public final JLabel avgRatingLabel = Design.label("—", 18, new Color(0x245B4D));

    public final JButton recordReviewBtn = Design.button("+ Record Customer Review", true);
    public final JButton correctReviewBtn = Design.button("Correct Review (Admin)", false);
    public final JButton refreshReviewsBtn = Design.button("Refresh Reviews", false);

    public final DefaultTableModel reviewsTableModel = new DefaultTableModel(
            new Object[]{"ID", "Booking ID", "Rating", "Feedback", "Source", "Status", "Recorded At"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int col) { return false; }
    };
    public final JTable reviewsTable = new JTable(reviewsTableModel);

    // ==========================================
    // Tab 3: Period Reporting Components
    // ==========================================
    public final JComboBox<String> periodGranularityCombo = new JComboBox<>(new String[]{
            "Indian Financial Year (Apr–Mar)",
            "Monday-start Week",
            "Monthly Period",
            "Daily / Custom Range"
    });
    public final JTextField fromDateField = new JTextField(LocalDate.now().withDayOfMonth(1).toString(), 10);
    public final JTextField toDateField = new JTextField(LocalDate.now().toString(), 10);
    public final JButton generateReportBtn = Design.button("Generate Summary", true);
    public final JButton exportCsvBtn = Design.button("Export CSV", false);

    // Operational Metrics Labels (Visible to Employee & Admin)
    public final JLabel opPeriodLabel = Design.label("—", 14, Design.INK);
    public final JLabel opTotalBookingsLabel = Design.label("0", 16, Design.INK);
    public final JLabel opActiveRentalsLabel = Design.label("0", 16, Design.INK);
    public final JLabel opCompletedRentalsLabel = Design.label("0", 16, new Color(0x245B4D));
    public final JLabel opOverdueRentalsLabel = Design.label("0", 16, new Color(0x775B28));
    public final JLabel opMaintenanceCasesLabel = Design.label("0", 16, Design.INK);
    public final JLabel opReviewsCountLabel = Design.label("0", 16, Design.INK);
    public final JLabel opAvgRatingLabel = Design.label("—", 16, new Color(0x245B4D));

    // Financial Metrics Labels (Admin Only)
    public final JPanel financialContainer = new JPanel(new BorderLayout(12, 12));
    public final JLabel finPeriodLabel = Design.label("—", 14, Design.INK);
    public final JLabel finGrossBilledLabel = Design.label("₹0.00", 16, Design.INK);
    public final JLabel finCashCollectedLabel = Design.label("₹0.00", 16, new Color(0x245B4D));
    public final JLabel finReceivablesLabel = Design.label("₹0.00", 16, new Color(0x775B28));
    public final JLabel finDepositsHeldLabel = Design.label("₹0.00", 16, Design.PRIMARY);
    public final JLabel finMaintenanceExpensesLabel = Design.label("₹0.00", 16, Design.INK);
    public final JLabel finStaffCompensationLabel = Design.label("₹0.00", 16, Design.INK);
    public final JLabel finTotalExpensesLabel = Design.label("₹0.00", 16, Design.INK);
    public final JLabel finNetAccrualMarginLabel = Design.label("₹0.00", 18, new Color(0x245B4D));

    // Specimen lists for preview mode
    public final List<OutboxMessageRecord> specimenOutbox = new ArrayList<>();
    public final List<ServiceReviewRecord> specimenReviews = new ArrayList<>();

    public AutomationAndReportsView() {
        this(null);
    }

    public AutomationAndReportsView(StaffSession session) {
        super(new BorderLayout(16, 16));
        setBackground(Design.BACKGROUND);
        setBorder(new EmptyBorder(20, 24, 20, 24));

        initUI();
        populateSpecimens();
        setSession(session);
        wireActions();
    }

    public void setClients(AutomationHttpClient automationClient, ReviewHttpClient reviewClient, ReportingHttpClient reportingClient) {
        this.automationClient = automationClient;
        this.reviewClient = reviewClient;
        this.reportingClient = reportingClient;
        boolean isAdmin = session != null && "ADMINISTRATOR".equalsIgnoreCase(session.role());
        scanRemindersBtn.setEnabled(isAdmin && automationClient != null);
        processBatchBtn.setEnabled(false);
        processBatchBtn.setToolTipText("No outbound email sender is configured in this build. Queued messages are not delivered.");
        correctReviewBtn.setEnabled(isAdmin && reviewClient != null);
        if (automationClient != null) {
            refreshOutbox();
        }
        if (reviewClient != null) {
            refreshReviews();
        }
    }

    public void setSession(StaffSession session) {
        this.session = session;
        boolean isAdmin = session != null && "ADMINISTRATOR".equalsIgnoreCase(session.role());

        scanRemindersBtn.setEnabled(isAdmin);
        scanRemindersBtn.setToolTipText(isAdmin ? "Trigger document expiry scan" : "Administrator role required");
        processBatchBtn.setEnabled(false);
        processBatchBtn.setToolTipText("No outbound email sender is configured in this build. Queued messages are not delivered.");
        correctReviewBtn.setEnabled(isAdmin);
        correctReviewBtn.setToolTipText(isAdmin ? "Correct recorded feedback with versioning" : "Administrator role required");

        updateFinancialView(isAdmin);
    }


    private void initUI() {
        // Top Header
        JPanel header = Design.vertical();
        header.add(Design.label("Automation, Reviews & Periodic Reporting", 24, Design.INK));
        header.add(Box.createVerticalStrut(4));
        header.add(Design.note("Transactional outbox delivery, post-settlement customer reviews, and reconciliation reports (FR-MSG-001, FR-REV-001, FR-FIN-003)."));
        header.add(Box.createVerticalStrut(8));

        // NFR-HOST-001 Hosting Disclosure Banner
        JPanel bannerCard = new JPanel(new BorderLayout(8, 8));
        bannerCard.setBackground(new Color(0xFAF4E8));
        bannerCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD6B875)),
                new EmptyBorder(8, 12, 8, 12)
        ));
        JLabel noticeTitle = Design.label("HOSTING & SCHEDULER BOUNDARY (NFR-HOST-001 / NFR-REL-001):", 11, new Color(0x775B28));
        JLabel noticeDesc = Design.label(
                "No scheduled worker or outbound email sender is configured in this build. A staff-triggered scan can queue reminders, but it does not send email. Render free services can sleep, so daily laptop-off reminders are not available yet. Do not treat queued messages as sent or delivered.",
                11, Design.INK
        );
        bannerCard.add(noticeTitle, BorderLayout.NORTH);
        bannerCard.add(noticeDesc, BorderLayout.CENTER);
        header.add(bannerCard);

        header.add(Box.createVerticalStrut(8));
        header.add(feedbackLabel);
        add(header, BorderLayout.NORTH);

        // Tabbed Pane
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        tabbedPane.addTab("Automation & Outbox", createOutboxTab());
        tabbedPane.addTab("Customer Reviews", createReviewsTab());
        tabbedPane.addTab("Periodic Reports", createReportsTab());

        add(tabbedPane, BorderLayout.CENTER);
    }

    private JPanel createOutboxTab() {
        JPanel panel = new Design.WidthPanel(new BorderLayout(16, 16));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Health & Queue Summary Card
        JPanel summaryCard = Design.card("Worker Health & Queue Status", "Render free tier sleeps on idle: automatic background scheduling unavailable without external webhook/cron.");
        JPanel metricsRow = new JPanel(new GridLayout(1, 5, 12, 12));
        metricsRow.setOpaque(false);
        metricsRow.add(createMetricBox("Worker Status", workerHealthLabel));
        metricsRow.add(createMetricBox("Pending Queue", pendingCountLabel));
        metricsRow.add(createMetricBox("Claimed / Leased", claimedCountLabel));
        metricsRow.add(createMetricBox("Accepted Sent", acceptedCountLabel));
        metricsRow.add(createMetricBox("Failed / Retrying", failedCountLabel));
        summaryCard.add(metricsRow);
        summaryCard.add(Box.createVerticalStrut(8));
        summaryCard.add(Design.row(Design.label("Last Expiry Scan: ", 12, Design.MUTED), lastScanLabel));

        // Action Buttons Row
        JPanel actionsRow = Design.row(scanRemindersBtn, processBatchBtn, refreshOutboxBtn);
        summaryCard.add(Box.createVerticalStrut(12));
        summaryCard.add(actionsRow);

        panel.add(summaryCard, BorderLayout.NORTH);

        // Tables Split: Outbox Messages & Delivery Attempts
        JPanel tablesPanel = new JPanel(new GridLayout(2, 1, 12, 12));
        tablesPanel.setOpaque(false);

        JPanel outboxCard = Design.card("Transactional Outbox Messages", "Queued communications with lease-based deduplication and version invalidation.");
        JScrollPane outboxScroll = new JScrollPane(outboxTable);
        outboxScroll.setPreferredSize(new Dimension(800, 160));
        outboxCard.add(outboxScroll);
        tablesPanel.add(outboxCard);

        JPanel attemptsCard = Design.card("Durable Delivery Attempts", "Audit trail of provider attempts, response codes, and exponential backoff.");
        JScrollPane attemptsScroll = new JScrollPane(attemptsTable);
        attemptsScroll.setPreferredSize(new Dimension(800, 140));
        attemptsCard.add(attemptsScroll);
        tablesPanel.add(attemptsCard);

        panel.add(tablesPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createReviewsTab() {
        JPanel panel = new Design.WidthPanel(new BorderLayout(16, 16));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Summary Card
        JPanel summaryCard = Design.card("Customer Service Reviews", "Voluntary feedback recorded only after vehicle return AND full payment settlement (FR-REV-001).");
        JPanel metricsRow = new JPanel(new GridLayout(1, 2, 16, 16));
        metricsRow.setOpaque(false);
        metricsRow.add(createMetricBox("Total Feedback Collected", totalReviewsLabel));
        metricsRow.add(createMetricBox("Average Service Rating", avgRatingLabel));
        summaryCard.add(metricsRow);

        // Action Buttons Row
        JPanel actionsRow = Design.row(recordReviewBtn, correctReviewBtn, refreshReviewsBtn);
        summaryCard.add(Box.createVerticalStrut(12));
        summaryCard.add(actionsRow);

        panel.add(summaryCard, BorderLayout.NORTH);

        // Reviews Table Card
        JPanel tableCard = Design.card("Recorded Service Feedback History", "Authoritative customer sentiment with versioned correction audit (FR-REV-002).");
        JScrollPane reviewsScroll = new JScrollPane(reviewsTable);
        reviewsScroll.setPreferredSize(new Dimension(800, 260));
        tableCard.add(reviewsScroll);

        panel.add(tableCard, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createReportsTab() {
        JPanel panel = new Design.WidthPanel(new BorderLayout(16, 16));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Filter Card
        JPanel filterCard = Design.card("Periodic Reconciliation Filter", "Indian Financial Year (1 Apr–31 Mar), Monday-start weeks, and reconciled ledger sums.");
        JPanel filterRow = Design.row(
                Design.label("Granularity:", 13, Design.INK),
                periodGranularityCombo,
                Box.createHorizontalStrut(12),
                Design.label("From:", 13, Design.INK),
                fromDateField,
                Design.label("To:", 13, Design.INK),
                toDateField,
                Box.createHorizontalStrut(12),
                generateReportBtn,
                exportCsvBtn
        );
        filterCard.add(filterRow);
        panel.add(filterCard, BorderLayout.NORTH);

        // Results Container: Operational (Employee & Admin) + Financial (Admin Only)
        JPanel resultsPanel = new JPanel(new GridLayout(2, 1, 16, 16));
        resultsPanel.setOpaque(false);

        // Operational Report Card
        JPanel opCard = Design.card("Operational Performance Summary", "Fleet utilization and rental operations. Contains zero payroll or company margin figures (FR-ACC-003).");
        JPanel opGrid = new JPanel(new GridLayout(2, 4, 12, 12));
        opGrid.setOpaque(false);
        opGrid.add(createMetricBox("Total Bookings", opTotalBookingsLabel));
        opGrid.add(createMetricBox("Active Rentals", opActiveRentalsLabel));
        opGrid.add(createMetricBox("Completed Rentals", opCompletedRentalsLabel));
        opGrid.add(createMetricBox("Overdue Rentals", opOverdueRentalsLabel));
        opGrid.add(createMetricBox("Maintenance Cases", opMaintenanceCasesLabel));
        opGrid.add(createMetricBox("Customer Reviews", opReviewsCountLabel));
        opGrid.add(createMetricBox("Average Review Score", opAvgRatingLabel));
        opGrid.add(createMetricBox("Active Period", opPeriodLabel));
        opCard.add(opGrid);
        resultsPanel.add(opCard);

        // Financial Report Card Container
        resultsPanel.add(financialContainer);

        panel.add(resultsPanel, BorderLayout.CENTER);
        return panel;
    }

    private void updateFinancialView(boolean isAdmin) {
        financialContainer.removeAll();
        if (isAdmin) {
            JPanel finCard = Design.card("Authoritative Financial Performance (Administrator Only)", "Reconciled ledger postings. Indian Financial Year (1 Apr – 31 Mar) and operating accrual margins.");
            JPanel finGrid = new JPanel(new GridLayout(2, 4, 12, 12));
            finGrid.setOpaque(false);
            finGrid.add(createMetricBox("Billed Revenue", finGrossBilledLabel));
            finGrid.add(createMetricBox("Cash Collected", finCashCollectedLabel));
            finGrid.add(createMetricBox("Receivables Due", finReceivablesLabel));
            finGrid.add(createMetricBox("Deposits Held", finDepositsHeldLabel));
            finGrid.add(createMetricBox("Maintenance Expenses", finMaintenanceExpensesLabel));
            finGrid.add(createMetricBox("Staff Compensation", finStaffCompensationLabel));
            finGrid.add(createMetricBox("Total Expenses", finTotalExpensesLabel));
            finGrid.add(createMetricBox("Net Operating Margin", finNetAccrualMarginLabel));
            finCard.add(finGrid);
            financialContainer.add(finCard, BorderLayout.CENTER);
        } else {
            JPanel lockedCard = Design.card("Financial Performance (Confidential)", "Restricted under Role-Based Access Control (FR-ACC-003, FR-FIN-005).");
            JLabel lockedLabel = Design.label("🔒 403 Forbidden: Company margins, expense breakdowns, and staff compensation are restricted to Administrator accounts.", 13, new Color(0x775B28));
            lockedCard.add(lockedLabel);
            financialContainer.add(lockedCard, BorderLayout.CENTER);
        }
        financialContainer.revalidate();
        financialContainer.repaint();
    }

    private JPanel createMetricBox(String title, JLabel valueLabel) {
        JPanel box = new JPanel(new BorderLayout(4, 4));
        box.setBackground(Design.SURFACE);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Design.BORDER),
                new EmptyBorder(10, 12, 10, 12)
        ));
        box.add(Design.label(title, 11, Design.MUTED), BorderLayout.NORTH);
        box.add(valueLabel, BorderLayout.CENTER);
        return box;
    }

    private void wireActions() {
        scanRemindersBtn.addActionListener(e -> {
            if (automationClient == null) {
                feedbackLabel.setText("Automation client not connected.");
                return;
            }
            scanRemindersBtn.setEnabled(false);
            feedbackLabel.setText("Scanning vehicle document expiries (<72h) in Asia/Kolkata...");
            feedbackLabel.setForeground(Design.PRIMARY);
            new SwingWorker<Integer, Void>() {
                @Override
                protected Integer doInBackground() {
                    return automationClient.scanReminders();
                }

                @Override
                protected void done() {
                    scanRemindersBtn.setEnabled(session != null && session.isAdministrator());
                    try {
                        int count = get();
                        feedbackLabel.setText("Scan completed: " + count + " expiry reminder outbox message(s) queued.");
                        feedbackLabel.setForeground(new Color(0x245B4D));
                        refreshOutbox();
                    } catch (Exception ex) {
                        feedbackLabel.setText("Scan failed: " + ex.getMessage());
                        feedbackLabel.setForeground(new Color(0x775B28));
                    }
                }
            }.execute();
        });

        processBatchBtn.addActionListener(e -> {
            feedbackLabel.setText("Email delivery is unavailable; no outbox items were claimed or marked failed.");
            feedbackLabel.setForeground(new Color(0x775B28));
        });

        refreshOutboxBtn.addActionListener(e -> refreshOutbox());

        outboxTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && automationClient != null) {
                int row = outboxTable.getSelectedRow();
                if (row >= 0 && row < outboxTableModel.getRowCount()) {
                    String idStr = (String) outboxTableModel.getValueAt(row, 0);
                    // Match with loaded messages
                    new SwingWorker<List<DeliveryAttemptRecord>, Void>() {
                        @Override
                        protected List<DeliveryAttemptRecord> doInBackground() {
                            List<OutboxMessageRecord> msgs = automationClient.listRecentMessages(50);
                            for (OutboxMessageRecord m : msgs) {
                                if (m.id().toString().startsWith(idStr.replace("...", ""))) {
                                    return automationClient.listAttempts(m.id());
                                }
                            }
                            return List.of();
                        }

                        @Override
                        protected void done() {
                            try {
                                List<DeliveryAttemptRecord> atts = get();
                                attemptsTableModel.setRowCount(0);
                                for (DeliveryAttemptRecord a : atts) {
                                    attemptsTableModel.addRow(new Object[]{
                                            "#" + a.attemptNumber(),
                                            TIME_FMT.format(a.startedAt()),
                                            a.providerName(),
                                            a.outcome(),
                                            a.providerResponseCode() != null ? String.valueOf(a.providerResponseCode()) : "—",
                                            a.errorMessage() != null ? a.errorMessage() : "OK"
                                    });
                                }
                            } catch (Exception ignored) {}
                        }
                    }.execute();
                }
            }
        });

        recordReviewBtn.addActionListener(e -> showRecordReviewDialog());
        correctReviewBtn.addActionListener(e -> showCorrectReviewDialog());
        refreshReviewsBtn.addActionListener(e -> refreshReviews());

        generateReportBtn.addActionListener(e -> {
            if (reportingClient == null) {
                feedbackLabel.setText("Reporting client not connected.");
                return;
            }
            generateReportBtn.setEnabled(false);
            feedbackLabel.setText("Generating periodic reports...");
            new SwingWorker<Void, Void>() {
                private OperationalReportSummary op;
                private FinancialReportSummary fin;

                @Override
                protected Void doInBackground() throws Exception {
                    String granStr = (String) periodGranularityCombo.getSelectedItem();
                    String granularity = switch (granStr) {
                        case "Indian Financial Year (Apr–Mar)" -> "INDIAN_FINANCIAL_YEAR";
                        case "Monday-start Week" -> "WEEK_MONDAY";
                        case "Monthly Period" -> "MONTH";
                        default -> "CUSTOM";
                    };
                    Instant from = LocalDate.parse(fromDateField.getText().trim()).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
                    Instant to = LocalDate.parse(toDateField.getText().trim()).atTime(23, 59, 59).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
                    ReportingPeriodFilter filter = new ReportingPeriodFilter(from, to, granularity, null, null);

                    op = reportingClient.getOperationalReport(filter);
                    if (session != null && session.isAdministrator()) {
                        fin = reportingClient.getFinancialReport(filter);
                    }
                    return null;
                }

                @Override
                protected void done() {
                    generateReportBtn.setEnabled(true);
                    try {
                        get();
                        if (op != null) displayOperationalReport(op);
                        if (fin != null) displayFinancialReport(fin);
                        feedbackLabel.setText("Report generated for " + periodGranularityCombo.getSelectedItem());
                        feedbackLabel.setForeground(new Color(0x245B4D));
                    } catch (Exception ex) {
                        feedbackLabel.setText("Report failed: " + ex.getMessage());
                        feedbackLabel.setForeground(new Color(0x775B28));
                    }
                }
            }.execute();
        });

        exportCsvBtn.addActionListener(e -> {
            if (reportingClient == null) {
                feedbackLabel.setText("Reporting client not connected.");
                return;
            }
            exportCsvBtn.setEnabled(false);
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    String granStr = (String) periodGranularityCombo.getSelectedItem();
                    String granularity = switch (granStr) {
                        case "Indian Financial Year (Apr–Mar)" -> "INDIAN_FINANCIAL_YEAR";
                        case "Monday-start Week" -> "WEEK_MONDAY";
                        case "Monthly Period" -> "MONTH";
                        default -> "CUSTOM";
                    };
                    Instant from = LocalDate.parse(fromDateField.getText().trim()).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
                    Instant to = LocalDate.parse(toDateField.getText().trim()).atTime(23, 59, 59).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
                    ReportingPeriodFilter filter = new ReportingPeriodFilter(from, to, granularity, null, null);
                    return reportingClient.exportCsv(filter);
                }

                @Override
                protected void done() {
                    exportCsvBtn.setEnabled(true);
                    try {
                        String csv = get();
                        JTextArea area = new JTextArea(csv, 16, 60);
                        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                        area.setEditable(false);
                        JOptionPane.showMessageDialog(AutomationAndReportsView.this, new JScrollPane(area),
                                "Exported Report CSV Preview (FR-FIN-003)", JOptionPane.INFORMATION_MESSAGE);
                        feedbackLabel.setText("CSV export generated successfully (" + csv.lines().count() + " lines).");
                        feedbackLabel.setForeground(new Color(0x245B4D));
                    } catch (Exception ex) {
                        feedbackLabel.setText("CSV export failed: " + ex.getMessage());
                        feedbackLabel.setForeground(new Color(0x775B28));
                    }
                }
            }.execute();
        });
    }

    private void refreshOutbox() {
        if (automationClient == null) return;
        refreshOutboxBtn.setEnabled(false);
        new SwingWorker<Void, Void>() {
            private WorkerHealthStatus health;
            private List<OutboxMessageRecord> msgs;

            @Override
            protected Void doInBackground() {
                health = automationClient.getWorkerHealth();
                msgs = automationClient.listRecentMessages(50);
                return null;
            }

            @Override
            protected void done() {
                refreshOutboxBtn.setEnabled(true);
                try {
                    get();
                    if (health != null) displayWorkerHealth(health);
                    if (msgs != null) {
                        outboxTableModel.setRowCount(0);
                        for (OutboxMessageRecord m : msgs) {
                            outboxTableModel.addRow(new Object[]{
                                    m.id().toString().substring(0, 8) + "...",
                                    m.eventType(),
                                    m.recipientEmail(),
                                    m.recipientRole(),
                                    m.subject(),
                                    m.status(),
                                    m.attemptsCount(),
                                    m.nextAttemptAt() != null ? TIME_FMT.format(m.nextAttemptAt()) : "—"
                            });
                        }
                    }
                } catch (Exception ex) {
                    feedbackLabel.setText("Failed to refresh outbox: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void refreshReviews() {
        if (reviewClient == null) return;
        refreshReviewsBtn.setEnabled(false);
        new SwingWorker<List<ServiceReviewRecord>, Void>() {
            @Override
            protected List<ServiceReviewRecord> doInBackground() {
                return reviewClient.listReviews(null, null);
            }

            @Override
            protected void done() {
                refreshReviewsBtn.setEnabled(true);
                try {
                    List<ServiceReviewRecord> list = get();
                    reviewsTableModel.setRowCount(0);
                    double totalRating = 0.0;
                    for (ServiceReviewRecord r : list) {
                        totalRating += r.rating();
                        reviewsTableModel.addRow(new Object[]{
                                r.id().toString().substring(0, 8) + "...",
                                r.bookingId().toString().substring(0, 8) + "...",
                                r.rating() + " ★",
                                r.feedbackText(),
                                r.communicationSource(),
                                r.status(),
                                TIME_FMT.format(r.recordedAt())
                        });
                    }
                    totalReviewsLabel.setText(String.valueOf(list.size()));
                    avgRatingLabel.setText(!list.isEmpty() ? String.format("%.1f ★", totalRating / list.size()) : "—");
                } catch (Exception ex) {
                    feedbackLabel.setText("Failed to refresh reviews: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void showRecordReviewDialog() {
        JTextField bookingField = new JTextField(UUID.randomUUID().toString(), 24);
        JComboBox<Integer> ratingCombo = new JComboBox<>(new Integer[]{5, 4, 3, 2, 1});
        JComboBox<String> sourceCombo = new JComboBox<>(new String[]{"DESK_FORM", "PHONE", "EMAIL", "IN_PERSON"});
        JCheckBox consentCheck = new JCheckBox("Customer gave explicit consent for recording voluntary feedback (FR-REV-001)", true);
        JTextArea feedbackArea = new JTextArea(3, 24);
        feedbackArea.setLineWrap(true);
        feedbackArea.setText("Vehicle condition was excellent and return inspection was swift.");

        JPanel p = new JPanel(new GridLayout(5, 1, 8, 8));
        p.add(Design.row(new JLabel("Booking ID (UUID):"), bookingField));
        p.add(Design.row(new JLabel("Rating (1–5 Stars):"), ratingCombo));
        p.add(Design.row(new JLabel("Communication Source:"), sourceCombo));
        p.add(consentCheck);
        p.add(new JScrollPane(feedbackArea));

        int res = JOptionPane.showConfirmDialog(this, p, "Record Service Review (Eligibility: Returned + Settled)", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION && reviewClient != null) {
            try {
                UUID bookingId = UUID.fromString(bookingField.getText().trim());
                int rating = (Integer) ratingCombo.getSelectedItem();
                String source = (String) sourceCombo.getSelectedItem();
                boolean consent = consentCheck.isSelected();
                String text = feedbackArea.getText().trim();

                ServiceReviewCommand cmd = new ServiceReviewCommand(bookingId, rating, text, source, consent);
                new SwingWorker<ServiceReviewRecord, Void>() {
                    @Override
                    protected ServiceReviewRecord doInBackground() {
                        return reviewClient.recordReview(cmd);
                    }

                    @Override
                    protected void done() {
                        try {
                            ServiceReviewRecord rec = get();
                            feedbackLabel.setText("Review recorded successfully (ID: " + rec.id() + ").");
                            feedbackLabel.setForeground(new Color(0x245B4D));
                            refreshReviews();
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(AutomationAndReportsView.this,
                                    "Review recording rejected:\n" + ex.getMessage(),
                                    "Eligibility Check Failed", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } catch (Exception ex) {
                feedbackLabel.setText("Invalid input: " + ex.getMessage());
            }
        }
    }

    private void showCorrectReviewDialog() {
        int row = reviewsTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select a review row from the table first.", "Selection Required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String idPrefix = (String) reviewsTableModel.getValueAt(row, 0);

        JComboBox<Integer> ratingCombo = new JComboBox<>(new Integer[]{5, 4, 3, 2, 1});
        JTextArea revisedArea = new JTextArea(3, 24);
        revisedArea.setLineWrap(true);
        JTextField reasonField = new JTextField("Typographical correction entered on customer request", 24);

        JPanel p = new JPanel(new GridLayout(3, 1, 8, 8));
        p.add(Design.row(new JLabel("Revised Rating:"), ratingCombo));
        p.add(Design.row(new JLabel("Reason for Revision:"), reasonField));
        p.add(new JScrollPane(revisedArea));

        int res = JOptionPane.showConfirmDialog(this, p, "Admin Correction (Preserves Immutable History)", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION && reviewClient != null) {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    List<ServiceReviewRecord> list = reviewClient.listReviews(null, null);
                    UUID targetId = null;
                    for (ServiceReviewRecord r : list) {
                        if (r.id().toString().startsWith(idPrefix.replace("...", ""))) {
                            targetId = r.id();
                            break;
                        }
                    }
                    if (targetId == null) throw new IllegalStateException("Selected review not found in live records.");

                    ServiceReviewCorrectionCommand cmd = new ServiceReviewCorrectionCommand(
                            targetId,
                            (Integer) ratingCombo.getSelectedItem(),
                            revisedArea.getText().trim(),
                            reasonField.getText().trim()
                    );
                    reviewClient.correctReview(cmd);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        feedbackLabel.setText("Review correction committed with audit history.");
                        feedbackLabel.setForeground(new Color(0x245B4D));
                        refreshReviews();
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(AutomationAndReportsView.this,
                                "Correction failed:\n" + ex.getMessage(),
                                "Correction Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }
    }

    public void displayOperationalReport(OperationalReportSummary summary) {
        opTotalBookingsLabel.setText(String.valueOf(summary.totalBookingsCount()));
        opActiveRentalsLabel.setText(String.valueOf(summary.activeRentalsCount()));
        opCompletedRentalsLabel.setText(String.valueOf(summary.completedRentalsCount()));
        opOverdueRentalsLabel.setText(String.valueOf(summary.overdueRentalsCount()));
        opMaintenanceCasesLabel.setText(String.valueOf(summary.maintenanceCasesCount()));
        opReviewsCountLabel.setText(String.valueOf(summary.reviewsCount()));
        opAvgRatingLabel.setText(summary.averageReviewRating() > 0 ? String.format("%.1f / 5.0", summary.averageReviewRating()) : "—");
        opPeriodLabel.setText(DATE_FMT.format(summary.fromTime()) + " – " + DATE_FMT.format(summary.toTime()));
    }

    public void displayFinancialReport(FinancialReportSummary summary) {
        finPeriodLabel.setText(summary.periodLabel());
        finGrossBilledLabel.setText(formatPaise(summary.totalRevenueBilledPaise()));
        finCashCollectedLabel.setText(formatPaise(summary.totalCashCollectedPaise()));
        finReceivablesLabel.setText(formatPaise(summary.outstandingReceivablesPaise()));
        finDepositsHeldLabel.setText(formatPaise(summary.refundableDepositLiabilityPaise()));
        finMaintenanceExpensesLabel.setText(formatPaise(summary.maintenanceExpensesPaise()));
        finStaffCompensationLabel.setText(formatPaise(summary.compensationExpensesPaise()));
        finTotalExpensesLabel.setText(formatPaise(summary.totalOperatingExpensesPaise()));
        finNetAccrualMarginLabel.setText(formatPaise(summary.netOperatingAccrualMarginPaise()));
    }

    public void displayWorkerHealth(WorkerHealthStatus health) {
        workerHealthLabel.setText("NO SENDER");
        workerHealthLabel.setForeground(new Color(0x775B28));
        pendingCountLabel.setText(String.valueOf(health.pendingMessagesCount()));
        claimedCountLabel.setText(String.valueOf(health.claimedMessagesCount()));
        acceptedCountLabel.setText(String.valueOf(health.acceptedMessagesCount()));
        failedCountLabel.setText(String.valueOf(health.failedMessagesCount()));
        lastScanLabel.setText(health.lastScanTime() != null ? TIME_FMT.format(health.lastScanTime()) : "Never");
    }

    private static String formatPaise(long paise) {
        return String.format("₹%,.2f", paise / 100.0);
    }

    private void populateSpecimens() {
        specimenOutbox.clear();
        specimenReviews.clear();
        outboxTableModel.setRowCount(0);
        reviewsTableModel.setRowCount(0);
        int ratingTotal=0;
        for(int i=0;i<DemoData.REVIEWS.size();i++) {
            DemoData.Review review=DemoData.REVIEWS.get(i);
            ratingTotal+=review.rating();
            reviewsTableModel.addRow(new Object[]{
                    String.format("DEMO-REV-%04d",i+1),String.format("DEMO-BK-%04d",i+1),review.rating()+" / 5",
                    review.comment(),"SAMPLE · NOT SUBMITTED","EXAMPLE",
                    DATE_FMT.format(Instant.now().minusSeconds(86400L*review.daysAgo()))
            });
        }
        totalReviewsLabel.setText(String.valueOf(DemoData.REVIEWS.size()));
        avgRatingLabel.setText(String.format("%.1f / 5.0",ratingTotal/(double)DemoData.REVIEWS.size()));
        opTotalBookingsLabel.setText("10");
        opActiveRentalsLabel.setText("10");
        opCompletedRentalsLabel.setText("0");
        opOverdueRentalsLabel.setText("3");
        opMaintenanceCasesLabel.setText("0");
        opReviewsCountLabel.setText(String.valueOf(DemoData.REVIEWS.size()));
        opAvgRatingLabel.setText(avgRatingLabel.getText());
        opPeriodLabel.setText("Demo data · not a live report");
        finPeriodLabel.setText("Demo data · not a live report");
        finGrossBilledLabel.setText(String.format("₹%,.2f",DemoData.totalBilledPaise()/100.0));
        finCashCollectedLabel.setText(String.format("₹%,.2f",DemoData.totalPaidPaise()/100.0));
        finReceivablesLabel.setText(String.format("₹%,.2f",(DemoData.totalBilledPaise()-DemoData.totalPaidPaise())/100.0));
        finDepositsHeldLabel.setText(String.format("₹%,.2f",DemoData.depositsHeldPaise()/100.0));
        feedbackLabel.setText("10 sample reviews and illustrative report totals. No messages were sent and no report was generated from company data.");
    }
}
