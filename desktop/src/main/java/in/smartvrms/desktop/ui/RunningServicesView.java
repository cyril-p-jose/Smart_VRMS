package in.smartvrms.desktop.ui;

import in.smartvrms.contracts.BookingRecord;
import in.smartvrms.contracts.CustodyRecord;
import in.smartvrms.contracts.ExtensionQuote;
import in.smartvrms.contracts.MaintenanceCaseRecord;
import in.smartvrms.contracts.RunningAssignmentSummary;
import in.smartvrms.contracts.RunningServiceSummary;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.desktop.integration.OperationsHttpClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Running Services operations workspace (FR-OPS-001, FR-OPS-002, FR-OPS-003, FR-EXT-001..003, FR-DMG-001..003).
 * Manages active rentals, checkout custody, return inspection, extensions with saved rates, and maintenance restrictions.
 */
public final class RunningServicesView extends JPanel {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    public final JComboBox<String> filterCombo = new JComboBox<>(new String[]{"All active rentals", "Overdue only"});
    public final JButton refreshButton = Design.button("Refresh queue", false);
    public final DefaultListModel<String> servicesListModel = new DefaultListModel<>();
    public final JList<String> servicesList = new JList<>(servicesListModel);

    // Selected service details
    public final JLabel bookingNumberLabel = Design.label("Select a running service", 18, Design.INK);
    public final JLabel customerInfoLabel = Design.label("—", 13, Design.MUTED);
    public final JLabel periodLabel = Design.label("—", 13, Design.INK);
    public final JLabel overdueBadge = Design.label("OVERDUE", 11, Color.WHITE);
    public final JLabel financialSummaryLabel = Design.label("Agreed: ₹0.00 • Received: ₹0.00 • Balance: ₹0.00", 13, Design.PRIMARY);

    public final DefaultListModel<String> assignmentsModel = new DefaultListModel<>();
    public final JList<String> assignmentsList = new JList<>(assignmentsModel);

    // Action buttons
    public final JButton checkoutButton = Design.button("Record checkout", true);
    public final JButton returnButton = Design.button("Return & inspection", false);
    public final JButton extendButton = Design.button("Extend rental", false);
    public final JButton maintenanceButton = Design.button("Open damage/maintenance", false);

    // Operational Feedback
    public final JLabel feedbackLabel = Design.label("", 12, Design.MUTED);

    private StaffSession session;
    private OperationsHttpClient client;
    private final List<RunningServiceSummary> currentSummaries = new ArrayList<>();
    private final List<RunningServiceSummary> visibleSummaries = new ArrayList<>();
    private RunningServiceSummary selectedSummary;

    public RunningServicesView() {
        this(null);
    }

    public RunningServicesView(StaffSession session) {
        super(new BorderLayout(16, 16));
        this.session = session;
        setOpaque(false);

        // Header Card
        JPanel headerCard = Design.card(
                "Running Services & Custody Operations",
                "Real-time operational custody, return inspection condition, and saved-rate extensions (FR-OPS-001..004, FR-EXT-001..003)."
        );
        headerCard.add(Design.note("RULE (FR-OPS-003): Acceptable physical return releases the vehicle immediately even if unpaid balance remains. Damaged return creates an active maintenance block. Driver release is independent of vehicle damage."));
        add(headerCard, BorderLayout.NORTH);

        // Overdue badge styling
        overdueBadge.setOpaque(true);
        overdueBadge.setBackground(new Color(0xD32F2F));
        overdueBadge.setBorder(new EmptyBorder(2, 6, 2, 6));
        overdueBadge.setVisible(false);

        // Left Panel: Queue & Filters
        JPanel leftPanel = Design.vertical();
        JPanel queueCard = Design.card("Running Services Queue", "Active package bookings and vehicles currently in custody.");

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filterRow.setOpaque(false);
        filterRow.add(Design.label("Filter:", 12, Design.MUTED));
        filterRow.add(filterCombo);
        filterRow.add(refreshButton);
        queueCard.add(filterRow);
        queueCard.add(Box.createVerticalStrut(12));

        servicesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        servicesList.setFixedCellHeight(44);
        servicesList.setBorder(BorderFactory.createLineBorder(Design.BORDER));
        queueCard.add(new JScrollPane(servicesList));

        leftPanel.add(queueCard);

        // Right Panel: Selected Service Detail & Actions
        JPanel rightPanel = Design.vertical();
        JPanel detailCard = Design.card("Rental Detail & Custody Actions", "Review assignments, condition notes, and execute custody transitions.");

        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        titleRow.setOpaque(false);
        titleRow.add(bookingNumberLabel);
        titleRow.add(overdueBadge);
        detailCard.add(titleRow);
        detailCard.add(Box.createVerticalStrut(4));
        detailCard.add(customerInfoLabel);
        detailCard.add(Box.createVerticalStrut(4));
        detailCard.add(periodLabel);
        detailCard.add(Box.createVerticalStrut(6));
        detailCard.add(financialSummaryLabel);
        detailCard.add(Box.createVerticalStrut(14));
        detailCard.add(new JSeparator());
        detailCard.add(Box.createVerticalStrut(12));

        detailCard.add(Design.label("Vehicle & Driver Assignments in Package:", 13, Design.INK));
        detailCard.add(Box.createVerticalStrut(6));

        assignmentsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        assignmentsList.setFixedCellHeight(38);
        assignmentsList.setBorder(BorderFactory.createLineBorder(Design.BORDER));
        detailCard.add(new JScrollPane(assignmentsList));
        detailCard.add(Box.createVerticalStrut(14));

        // Action Buttons Row
        JPanel actionsRow = Design.row(checkoutButton, returnButton, extendButton, maintenanceButton);
        detailCard.add(actionsRow);
        detailCard.add(Box.createVerticalStrut(10));
        detailCard.add(feedbackLabel);

        rightPanel.add(detailCard);

        // Split view with WidthPanel for clean responsiveness
        Design.WidthPanel widthLeft = new Design.WidthPanel(new BorderLayout());
        widthLeft.add(leftPanel, BorderLayout.NORTH);

        Design.WidthPanel widthRight = new Design.WidthPanel(new BorderLayout());
        widthRight.add(rightPanel, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Design.scroll(widthLeft), Design.scroll(widthRight));
        split.setDividerLocation(420);
        split.setResizeWeight(0.35);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        // Wire Mock Data & Interactions
        initMockSpecimens();
        setupListeners();
    }

    public void setSession(StaffSession session) {
        this.session = session;
    }

    public void setClient(OperationsHttpClient client) {
        this.client = client;
        refreshQueue();
    }

    private void initMockSpecimens() {
        currentSummaries.clear();
        servicesListModel.clear();
        visibleSummaries.clear();
        Instant now = Instant.now();
        for (int i=0;i<DemoData.CUSTOMERS.size();i++) {
            DemoData.Customer customer=DemoData.CUSTOMERS.get(i);
            DemoData.Vehicle vehicle=DemoData.VEHICLES.get(i);
            DemoData.Driver driver=DemoData.DRIVERS.get(i);
            Instant start=now.minus(Duration.ofHours(12L+i*9L));
            Instant expectedReturn=i<3?now.minus(Duration.ofHours(2L+i)):now.plus(Duration.ofHours(4L+i*3L));
            UUID vehicleId=demoId("vehicle",i);
            UUID driverId=i%2==0?demoId("driver",i):null;
            String assignedDriver=i%2==0?driver.name():null;
            RunningAssignmentSummary assignment=new RunningAssignmentSummary(
                    demoId("assignment",i),vehicleId,vehicle.demoRegistration(),vehicle.category(),driverId,assignedDriver,
                    start,expectedReturn,"CHECKED_OUT","CHECKED_OUT","SAMPLE"
            );
            DemoData.Invoice invoice=DemoData.INVOICES.get(i);
            long received=DemoData.PAYMENTS.get(i).amountPaise();
            currentSummaries.add(new RunningServiceSummary(
                    demoId("booking",i),String.format("DEMO-BK-%04d",i+1),demoId("customer",i),customer.name(),customer.maskedPhone(),
                    demoId("rate-plan",i),"ACTIVE",start,expectedReturn,expectedReturn.isBefore(now),1,
                    List.of(vehicle.demoRegistration()),assignedDriver==null?List.of():List.of(assignedDriver),
                    invoice.totalPaise(),received,Math.max(0,invoice.totalPaise()-received),List.of(assignment)
            ));
        }
        populateList(false);
        feedbackLabel.setText("10 fictional running rentals · example dates and balances only; no custody is recorded.");
    }

    private static UUID demoId(String kind,int index) {
        return UUID.nameUUIDFromBytes(("smart-vrms-demo-"+kind+"-"+index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }


    public void refreshQueue() {
        boolean overdueOnly = filterCombo.getSelectedIndex() == 1;
        if (client != null) {
            new SwingWorker<List<RunningServiceSummary>, Void>() {
                @Override
                protected List<RunningServiceSummary> doInBackground() {
                    return client.listRunningServices(overdueOnly);
                }

                @Override
                protected void done() {
                    try {
                        List<RunningServiceSummary> list = get();
                        currentSummaries.clear();
                        currentSummaries.addAll(list);
                        populateList(overdueOnly);
                        feedbackLabel.setText("Queue updated from server (" + list.size() + " running services).");
                    } catch (Exception ex) {
                        feedbackLabel.setText("Queue refresh error: " + ex.getMessage());
                    }
                }
            }.execute();
        } else {
            feedbackLabel.setText("Demo queue refreshed locally; no database request was made.");
            populateList(overdueOnly);
        }
    }

    private void populateList(boolean overdueOnly) {
        servicesListModel.clear();
        visibleSummaries.clear();
        for (RunningServiceSummary s : currentSummaries) {
            if (!overdueOnly || s.isOverdue()) {
                visibleSummaries.add(s);
                String badge = s.isOverdue() ? "[OVERDUE] " : "";
                servicesListModel.addElement(badge + s.bookingNumber() + " • " + s.customerName() + " (" + s.vehicleCount() + " veh)");
            }
        }
        if (!servicesListModel.isEmpty()) {
            servicesList.setSelectedIndex(0);
        }
    }

    private void setupListeners() {
        filterCombo.addActionListener(e -> {
            boolean overdueOnly = filterCombo.getSelectedIndex() == 1;
            if (client != null) {
                refreshQueue();
            } else {
                populateList(overdueOnly);
            }
        });

        refreshButton.addActionListener(e -> refreshQueue());

        servicesList.addListSelectionListener(e -> {
            int idx = servicesList.getSelectedIndex();
            if (idx >= 0 && idx < visibleSummaries.size()) {
                selectService(visibleSummaries.get(idx));
            }
        });

        checkoutButton.addActionListener(e -> executeCheckoutDialog());
        returnButton.addActionListener(e -> executeReturnDialog());
        extendButton.addActionListener(e -> executeExtensionDialog());
        maintenanceButton.addActionListener(e -> executeMaintenanceDialog());
    }

    private void selectService(RunningServiceSummary summary) {
        this.selectedSummary = summary;
        bookingNumberLabel.setText("Booking #" + summary.bookingNumber());
        overdueBadge.setVisible(summary.isOverdue());
        customerInfoLabel.setText("Customer: " + summary.customerName() + " • Contact: " + summary.customerContact());
        periodLabel.setText("Window: " + TIME_FMT.format(summary.startTime()) + "  ➔  " + TIME_FMT.format(summary.endTime()));

        long totalRs = summary.totalPaise() / 100;
        long advanceRs = summary.advanceReceivedPaise() / 100;
        long balanceRs = summary.balancePaise() / 100;
        financialSummaryLabel.setText(String.format("Agreed Total: ₹%d • Received Advance: ₹%d • Outstanding Balance: ₹%d", totalRs, advanceRs, balanceRs));

        assignmentsModel.clear();
        for (RunningAssignmentSummary a : summary.assignments()) {
            String drv = a.driverName() != null ? " • Driver: " + a.driverName() : " • Self-drive";
            String cust = " • Custody: " + a.custodyStatus();
            if (a.inspectionStatus() != null) cust += " (" + a.inspectionStatus() + ")";
            assignmentsModel.addElement(a.registrationPlate() + " (" + a.categoryName() + ")" + drv + cust);
        }
        if (!assignmentsModel.isEmpty()) {
            assignmentsList.setSelectedIndex(0);
        }
        feedbackLabel.setText("");
    }

    private void executeCheckoutDialog() {
        if (selectedSummary == null) {
            feedbackLabel.setText("Select a booking first.");
            return;
        }
        int assignIdx = assignmentsList.getSelectedIndex();
        if (assignIdx < 0 || assignIdx >= selectedSummary.assignments().size()) {
            feedbackLabel.setText("Select an assignment to check out.");
            return;
        }
        RunningAssignmentSummary a = selectedSummary.assignments().get(assignIdx);
        if ("CHECKED_OUT".equals(a.custodyStatus()) || "RETURNED".equals(a.custodyStatus())) {
            feedbackLabel.setText("Assignment is already " + a.custodyStatus().toLowerCase() + ". Duplicate checkout denied (FR-OPS-002).");
            return;
        }

        JTextField odoField = new JTextField("12500");
        JTextField fuelField = new JTextField("100");
        JTextField notesField = new JTextField("Clean vehicle inspected before dispatch.");

        JPanel p = new JPanel(new GridLayout(3, 2, 8, 8));
        p.add(new JLabel("Starting Odometer (km):"));
        p.add(odoField);
        p.add(new JLabel("Fuel Level (%):"));
        p.add(fuelField);
        p.add(new JLabel("Dispatch Notes:"));
        p.add(notesField);

        int res = JOptionPane.showConfirmDialog(this, p, "Record Vehicle Checkout — " + a.registrationPlate(), JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            int odo;
            int fuel;
            try {
                odo = Integer.parseInt(odoField.getText().trim());
                fuel = Integer.parseInt(fuelField.getText().trim());
            } catch (NumberFormatException ex) {
                feedbackLabel.setText("Invalid numeric values for odometer or fuel.");
                return;
            }
            String notes = notesField.getText().trim();

            if (client != null) {
                checkoutButton.setEnabled(false);
                feedbackLabel.setText("Submitting checkout to server...");
                new SwingWorker<CustodyRecord, Void>() {
                    @Override
                    protected CustodyRecord doInBackground() {
                        return client.recordCheckout(a.assignmentId(), Instant.now(), odo, fuel, notes);
                    }

                    @Override
                    protected void done() {
                        checkoutButton.setEnabled(true);
                        try {
                            CustodyRecord rec = get();
                            feedbackLabel.setText("Checkout recorded for " + a.registrationPlate() + " at " + TIME_FMT.format(rec.checkoutTime()) + " (Status: CHECKED_OUT).");
                            refreshQueue();
                        } catch (Exception ex) {
                            feedbackLabel.setText("Checkout failed: " + ex.getMessage());
                        }
                    }
                }.execute();
            } else {
                feedbackLabel.setText("Action blocked: Operations API client not connected.");
            }
        }
    }


    private void executeReturnDialog() {
        if (selectedSummary == null) {
            feedbackLabel.setText("Select a booking first.");
            return;
        }
        int assignIdx = assignmentsList.getSelectedIndex();
        if (assignIdx < 0 || assignIdx >= selectedSummary.assignments().size()) {
            feedbackLabel.setText("Select an assignment to return.");
            return;
        }
        RunningAssignmentSummary a = selectedSummary.assignments().get(assignIdx);
        if (!"CHECKED_OUT".equals(a.custodyStatus())) {
            feedbackLabel.setText("Assignment is not checked out (current status: " + a.custodyStatus() + "). Return denied.");
            return;
        }

        JTextField odoField = new JTextField("12850");
        JTextField fuelField = new JTextField("95");
        JComboBox<String> condCombo = new JComboBox<>(new String[]{"ACCEPTABLE", "DAMAGED"});
        JTextField notesField = new JTextField("Vehicle returned in good order.");

        JPanel p = new JPanel(new GridLayout(4, 2, 8, 8));
        p.add(new JLabel("Return Odometer (km):"));
        p.add(odoField);
        p.add(new JLabel("Return Fuel Level (%):"));
        p.add(fuelField);
        p.add(new JLabel("Condition:"));
        p.add(condCombo);
        p.add(new JLabel("Inspection Notes:"));
        p.add(notesField);

        int res = JOptionPane.showConfirmDialog(this, p, "Record Return Inspection — " + a.registrationPlate(), JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            int odo;
            int fuel;
            try {
                odo = Integer.parseInt(odoField.getText().trim());
                fuel = Integer.parseInt(fuelField.getText().trim());
            } catch (NumberFormatException ex) {
                feedbackLabel.setText("Invalid numeric values for odometer or fuel.");
                return;
            }
            String cond = (String) condCombo.getSelectedItem();
            String notes = notesField.getText().trim();

            if (client != null) {
                returnButton.setEnabled(false);
                feedbackLabel.setText("Submitting return inspection to server...");
                new SwingWorker<CustodyRecord, Void>() {
                    @Override
                    protected CustodyRecord doInBackground() {
                        return client.recordReturnInspection(
                                a.assignmentId(), Instant.now(), odo, fuel, cond, notes, null,
                                "DAMAGED".equals(cond) ? "CUSTOMER_DAMAGE" : "ORDINARY_WEAR", 0, 0
                        );
                    }

                    @Override
                    protected void done() {
                        returnButton.setEnabled(true);
                        try {
                            get();
                            if ("ACCEPTABLE".equals(cond)) {
                                feedbackLabel.setText("Return inspection ACCEPTABLE: " + a.registrationPlate() + " released for future rentals (FR-OPS-003). Debt remains visible.");
                            } else {
                                feedbackLabel.setText("Return inspection DAMAGED: " + a.registrationPlate() + " BLOCKED with active maintenance restriction (FR-DMG-003).");
                            }
                            refreshQueue();
                        } catch (Exception ex) {
                            feedbackLabel.setText("Return inspection failed: " + ex.getMessage());
                        }
                    }
                }.execute();
            } else {
                feedbackLabel.setText("Action blocked: Operations API client not connected.");
            }
        }
    }


    private void executeExtensionDialog() {
        if (selectedSummary == null) {
            feedbackLabel.setText("Select a booking first.");
            return;
        }
        JTextField daysField = new JTextField("1");
        JTextField reasonField = new JTextField("Customer requested additional day.");

        JPanel p = new JPanel(new GridLayout(2, 2, 8, 8));
        p.add(new JLabel("Extend by (Days):"));
        p.add(daysField);
        p.add(new JLabel("Extension Reason:"));
        p.add(reasonField);

        int res = JOptionPane.showConfirmDialog(this, p, "Extend Booking — #" + selectedSummary.bookingNumber(), JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            int days;
            try {
                days = Integer.parseInt(daysField.getText().trim());
                if (days <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                feedbackLabel.setText("Enter a positive whole number of days.");
                return;
            }
            String reason = reasonField.getText().trim();
            Instant revisedEndTime = selectedSummary.endTime().plus(Duration.ofDays(days));

            if (client != null) {
                extendButton.setEnabled(false);
                feedbackLabel.setText("Calculating extension quote at saved rates...");
                new SwingWorker<ExtensionQuote, Void>() {
                    @Override
                    protected ExtensionQuote doInBackground() {
                        return client.quoteExtension(selectedSummary.bookingId(), null, revisedEndTime, reason);
                    }

                    @Override
                    protected void done() {
                        try {
                            ExtensionQuote quote = get();
                            long deltaRs = quote.totalDeltaPaise() / 100;
                            int confirm = JOptionPane.showConfirmDialog(
                                    RunningServicesView.this,
                                    "Extension Repricing Summary (Saved Rate Version):\n" +
                                            "• Extended Duration: +" + days + " day(s)\n" +
                                            "• Revised End: " + TIME_FMT.format(quote.revisedEndTime()) + "\n" +
                                            "• Itemized Delta Charge: ₹" + deltaRs + "\n" +
                                            "• Duplicate 24h min fee: None (FR-EXT-002)\n\n" +
                                            "Confirm extension?",
                                    "Confirm Extension — #" + selectedSummary.bookingNumber(),
                                    JOptionPane.OK_CANCEL_OPTION
                            );

                            if (confirm == JOptionPane.OK_OPTION) {
                                new SwingWorker<BookingRecord, Void>() {
                                    @Override
                                    protected BookingRecord doInBackground() {
                                        return client.extendAssignments(selectedSummary.bookingId(), null, revisedEndTime, reason);
                                    }

                                    @Override
                                    protected void done() {
                                        extendButton.setEnabled(true);
                                        try {
                                            get();
                                            feedbackLabel.setText("Extension approved at saved rates (FR-EXT-002): Repriced with delta ₹" + deltaRs + ", zero duplicate first-day fee.");
                                            refreshQueue();
                                        } catch (Exception ex) {
                                            feedbackLabel.setText("Extension confirmation failed: " + ex.getMessage());
                                        }
                                    }
                                }.execute();
                            } else {
                                extendButton.setEnabled(true);
                                feedbackLabel.setText("Extension cancelled.");
                            }
                        } catch (Exception ex) {
                            extendButton.setEnabled(true);
                            feedbackLabel.setText("Extension quoting failed: " + ex.getMessage());
                        }
                    }
                }.execute();
            } else {
                feedbackLabel.setText("Action blocked: Operations API client not connected.");
            }
        }
    }


    private void executeMaintenanceDialog() {
        if (selectedSummary == null) {
            feedbackLabel.setText("Select a booking first.");
            return;
        }
        int assignIdx = assignmentsList.getSelectedIndex();
        RunningAssignmentSummary a = (assignIdx >= 0 && assignIdx < selectedSummary.assignments().size())
                ? selectedSummary.assignments().get(assignIdx) : null;

        JComboBox<String> issueCombo = new JComboBox<>(new String[]{"CUSTOMER_DAMAGE", "ORDINARY_WEAR", "ROUTINE_SERVICE", "BREAKDOWN"});
        JTextField estField = new JTextField("15000"); // 150 INR in paise or 15000 paise
        JTextField notesField = new JTextField("Scratch on rear bumper during return.");

        JPanel p = new JPanel(new GridLayout(3, 2, 8, 8));
        p.add(new JLabel("Issue Classification:"));
        p.add(issueCombo);
        p.add(new JLabel("Estimated Repair Cost (Paise):"));
        p.add(estField);
        p.add(new JLabel("Incident Notes:"));
        p.add(notesField);

        int res = JOptionPane.showConfirmDialog(this, p, "Open Damage/Maintenance Case", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            String issue = (String) issueCombo.getSelectedItem();
            long estCost;
            try {
                estCost = Long.parseLong(estField.getText().trim());
            } catch (NumberFormatException ex) {
                feedbackLabel.setText("Invalid numeric value for estimated repair cost.");
                return;
            }
            String notes = notesField.getText().trim();
            UUID vehicleId = a != null ? a.vehicleId() : (selectedSummary.assignments().isEmpty() ? null : selectedSummary.assignments().get(0).vehicleId());
            UUID assignmentId = a != null ? a.assignmentId() : null;

            if (vehicleId == null) {
                feedbackLabel.setText("No vehicle associated with the selection.");
                return;
            }

            if (client != null) {
                maintenanceButton.setEnabled(false);
                feedbackLabel.setText("Opening maintenance case on server...");
                new SwingWorker<MaintenanceCaseRecord, Void>() {
                    @Override
                    protected MaintenanceCaseRecord doInBackground() {
                        long recovery = "ORDINARY_WEAR".equalsIgnoreCase(issue) ? 0L : 0L;
                        return client.openMaintenanceCase(vehicleId, assignmentId, null, issue, estCost, recovery, notes, null);
                    }

                    @Override
                    protected void done() {
                        maintenanceButton.setEnabled(true);
                        try {
                            get();
                            feedbackLabel.setText("Maintenance case opened (FR-DMG-001). Vehicle placed under active maintenance restriction.");
                            refreshQueue();
                        } catch (Exception ex) {
                            feedbackLabel.setText("Maintenance case failed: " + ex.getMessage());
                        }
                    }
                }.execute();
            } else {
                feedbackLabel.setText("Action blocked: Operations API client not connected.");
            }
        }
    }
}
