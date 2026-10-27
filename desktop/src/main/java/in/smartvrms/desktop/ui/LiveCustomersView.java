package in.smartvrms.desktop.ui;

import in.smartvrms.contracts.CustomerRecord;
import in.smartvrms.contracts.CustomerVisitRecord;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.desktop.integration.CustomerHttpClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Connected customer repository and historical visit timeline view (FR-CUST-001..008).
 * Enforces FR-CUST-002: Standalone customer creation is blocked. Registration occurs exclusively inside booking.
 * All network and file I/O operations run off the EDT via SwingWorker.
 */
public final class LiveCustomersView extends JPanel {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    private final CustomerHttpClient api;
    private final StaffSession session;
    private final Consumer<CustomerRecord> onCustomerBooking;

    // Search controls
    public final JTextField searchField = new JTextField(18);
    public final JButton searchButton = Design.button("Search customers", true);
    public final JButton clearButton = Design.button("Clear search", false);
    public final JLabel feedbackLabel = Design.label("Enter search criteria to query registered customers.", 12, Design.MUTED);

    // Customer table
    public final DefaultTableModel customerTableModel = new DefaultTableModel(
            new String[]{"ID", "Full Name", "Mobile Number", "Email Address", "Address", "Created At"}, 0
    ) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    public final TableRowSorter<DefaultTableModel> customerSorter = new TableRowSorter<>(customerTableModel);
    public final JTable customerTable = new JTable(customerTableModel);

    // Selected customer profile components
    public final JLabel selectedNameLabel = Design.label("Select a customer from the search results", 16, Design.INK);
    public final JLabel selectedContactLabel = Design.label("—", 13, Design.MUTED);
    public final JLabel selectedAddressLabel = Design.label("—", 12, Design.MUTED);
    public final JButton startBookingBtn = Design.button("Start booking for customer", false);

    // Visit history table
    public final DefaultTableModel visitTableModel = new DefaultTableModel(
            new String[]{"Visit Date & Time", "Age at Visit (FR-CUST-008)", "Service / Purpose", "Visit ID"}, 0
    ) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    public final JTable visitTable = new JTable(visitTableModel);
    public final JLabel visitFeedbackLabel = Design.label("No customer selected.", 12, Design.MUTED);

    private List<CustomerRecord> currentResults = new ArrayList<>();
    private CustomerRecord selectedCustomer;
    private SwingWorker<?, ?> pendingSearch;
    private SwingWorker<?, ?> pendingVisits;
    private boolean active = true;

    public LiveCustomersView(CustomerHttpClient api, StaffSession session) {
        this(api, session, null);
    }

    public LiveCustomersView(CustomerHttpClient api, StaffSession session, Consumer<CustomerRecord> onCustomerBooking) {
        super(new BorderLayout(14, 14));
        this.api = Objects.requireNonNull(api, "api");
        this.session = Objects.requireNonNull(session, "session");
        this.onCustomerBooking = onCustomerBooking;
        setOpaque(false);

        // Header Card & FR-CUST-002 Compliance Notice
        JPanel header = Design.card(
                "Customer Repository & Historical Visits",
                "Authenticated customer database and historical age-at-visit records (FR-CUST-001..008)."
        );
        header.add(Design.note("NOTICE (FR-CUST-002): Standalone customer creation is blocked. New customer records are registered strictly inside the active booking workflow to bind KYC identity with rental custody."));
        header.add(Box.createVerticalStrut(10));

        searchField.putClientProperty("html.disable", Boolean.TRUE);
        searchField.getAccessibleContext().setAccessibleName("Customer search query");
        JPanel searchBar = Design.row(
                Design.label("Search:", 12, Design.MUTED),
                searchField,
                searchButton,
                clearButton
        );
        header.add(searchBar);
        header.add(Box.createVerticalStrut(6));
        header.add(feedbackLabel);
        add(header, BorderLayout.NORTH);

        // Left Panel: Customer Search Results
        JPanel leftPanel = Design.vertical();
        JPanel leftCard = Design.card("Search Results", "Matches based on name, mobile phone or email address.");

        customerTable.setModel(customerTableModel);
        customerTable.setRowSorter(customerSorter);
        customerTable.setRowHeight(30);
        customerTable.setFillsViewportHeight(true);
        customerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Hide internal UUID column
        customerTable.getColumnModel().getColumn(0).setMinWidth(0);
        customerTable.getColumnModel().getColumn(0).setMaxWidth(0);
        customerTable.getColumnModel().getColumn(0).setWidth(0);

        var plainCell = new DefaultTableCellRenderer();
        plainCell.putClientProperty("html.disable", Boolean.TRUE);
        customerTable.setDefaultRenderer(Object.class, plainCell);

        JScrollPane customerScroll = new JScrollPane(customerTable);
        customerScroll.setPreferredSize(new Dimension(380, 360));
        leftCard.add(customerScroll);
        leftPanel.add(leftCard);

        // Right Panel: Selected Customer Profile & Historical Visits
        JPanel rightPanel = Design.vertical();
        JPanel profileCard = Design.card("Customer Profile & Verified Visit History", "Repeat visits preserve identity while recording current age-at-visit (FR-CUST-003, FR-CUST-008).");
        profileCard.add(selectedNameLabel);
        profileCard.add(Box.createVerticalStrut(4));
        profileCard.add(selectedContactLabel);
        profileCard.add(Box.createVerticalStrut(2));
        profileCard.add(selectedAddressLabel);
        profileCard.add(Box.createVerticalStrut(10));

        startBookingBtn.setEnabled(false);
        profileCard.add(Design.row(startBookingBtn));
        profileCard.add(Box.createVerticalStrut(14));
        profileCard.add(new JSeparator());
        profileCard.add(Box.createVerticalStrut(10));

        profileCard.add(Design.label("Verified Visit Timeline (FR-CUST-004, FR-CUST-008):", 13, Design.INK));
        profileCard.add(Box.createVerticalStrut(6));

        visitTable.setModel(visitTableModel);
        visitTable.setRowHeight(28);
        visitTable.setFillsViewportHeight(true);
        visitTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        visitTable.setDefaultRenderer(Object.class, plainCell);

        JScrollPane visitScroll = new JScrollPane(visitTable);
        visitScroll.setPreferredSize(new Dimension(480, 220));
        profileCard.add(visitScroll);
        profileCard.add(Box.createVerticalStrut(6));
        profileCard.add(visitFeedbackLabel);

        rightPanel.add(profileCard);

        // Responsive Split Layout with WidthPanels
        Design.WidthPanel widthLeft = new Design.WidthPanel(new BorderLayout());
        widthLeft.add(leftPanel, BorderLayout.NORTH);

        Design.WidthPanel widthRight = new Design.WidthPanel(new BorderLayout());
        widthRight.add(rightPanel, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Design.scroll(widthLeft), Design.scroll(widthRight));
        split.setBorder(null);
        split.setDividerLocation(420);
        split.setResizeWeight(0.42);
        split.setDividerSize(8);
        add(split, BorderLayout.CENTER);

        setupListeners();
    }

    private void setupListeners() {
        searchButton.addActionListener(e -> performSearch());
        searchField.addActionListener(e -> performSearch());
        clearButton.addActionListener(e -> {
            searchField.setText("");
            customerTableModel.setRowCount(0);
            currentResults.clear();
            selectedCustomer = null;
            clearSelectionDetails();
            feedbackLabel.setText("Search cleared. Enter criteria to query customers.");
        });

        customerTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = customerTable.getSelectedRow();
                if (row >= 0) {
                    int modelRow = customerTable.convertRowIndexToModel(row);
                    if (modelRow >= 0 && modelRow < currentResults.size()) {
                        selectCustomer(currentResults.get(modelRow));
                    }
                } else {
                    selectedCustomer = null;
                    clearSelectionDetails();
                }
            }
        });

        startBookingBtn.addActionListener(e -> {
            if (selectedCustomer != null && onCustomerBooking != null) {
                onCustomerBooking.accept(selectedCustomer);
            }
        });
    }

    public void performSearch() {
        if (!active) return;
        String query = searchField.getText().strip();
        searchButton.setEnabled(false);
        feedbackLabel.setText("Searching customer database...");

        if (pendingSearch != null) pendingSearch.cancel(true);
        pendingSearch = new SwingWorker<List<CustomerRecord>, Void>() {
            @Override
            protected List<CustomerRecord> doInBackground() {
                return api.searchCustomers(query);
            }

            @Override
            protected void done() {
                if (!active) return;
                searchButton.setEnabled(true);
                try {
                    List<CustomerRecord> list = get();
                    currentResults = list;
                    customerTableModel.setRowCount(0);
                    for (CustomerRecord c : list) {
                        customerTableModel.addRow(new Object[]{
                                c.id().toString(),
                                c.fullName(),
                                c.mobileNumber(),
                                c.email() != null ? c.email() : "—",
                                c.address(),
                                TIME_FMT.format(c.createdAt())
                        });
                    }
                    if (list.isEmpty()) {
                        feedbackLabel.setText(query.isBlank()
                                ? "No customer records registered yet."
                                : "No customers found matching '" + query + "'.");
                    } else {
                        feedbackLabel.setText("Found " + list.size() + " customer record(s).");
                        customerTable.setRowSelectionInterval(0, 0);
                    }
                } catch (Exception ex) {
                    feedbackLabel.setText("Search failed: " + ex.getMessage());
                }
            }
        };
        pendingSearch.execute();
    }

    private void selectCustomer(CustomerRecord customer) {
        this.selectedCustomer = customer;
        selectedNameLabel.setText(customer.fullName());
        selectedContactLabel.setText("Mobile: " + customer.mobileNumber() + (customer.email() != null && !customer.email().isBlank() ? " • Email: " + customer.email() : ""));
        selectedAddressLabel.setText("Address: " + customer.address());
        startBookingBtn.setEnabled(onCustomerBooking != null);

        loadVisitHistory(customer.id());
    }

    private void clearSelectionDetails() {
        selectedNameLabel.setText("Select a customer from the search results");
        selectedContactLabel.setText("—");
        selectedAddressLabel.setText("—");
        startBookingBtn.setEnabled(false);
        visitTableModel.setRowCount(0);
        visitFeedbackLabel.setText("No customer selected.");
    }

    private void loadVisitHistory(UUID customerId) {
        if (!active) return;
        visitFeedbackLabel.setText("Loading visit history from server...");
        visitTableModel.setRowCount(0);

        if (pendingVisits != null) pendingVisits.cancel(true);
        pendingVisits = new SwingWorker<List<CustomerVisitRecord>, Void>() {
            @Override
            protected List<CustomerVisitRecord> doInBackground() {
                return api.getVisitHistory(customerId);
            }

            @Override
            protected void done() {
                if (!active) return;
                try {
                    List<CustomerVisitRecord> visits = get();
                    visitTableModel.setRowCount(0);
                    for (CustomerVisitRecord v : visits) {
                        visitTableModel.addRow(new Object[]{
                                TIME_FMT.format(v.visitDate()),
                                v.ageAtVisit() + " years",
                                v.purpose() != null ? v.purpose() : "Vehicle Rental",
                                v.id().toString().substring(0, 8) + "..."
                        });
                    }
                    if (visits.isEmpty()) {
                        visitFeedbackLabel.setText("No previous visits recorded for this customer.");
                    } else {
                        visitFeedbackLabel.setText(visits.size() + " historical visit(s) loaded.");
                    }
                } catch (Exception ex) {
                    visitFeedbackLabel.setText("Failed to load visit history: " + ex.getMessage());
                }
            }
        };
        pendingVisits.execute();
    }

    public void close() {
        active = false;
        if (pendingSearch != null) pendingSearch.cancel(true);
        if (pendingVisits != null) pendingVisits.cancel(true);
        customerTableModel.setRowCount(0);
        visitTableModel.setRowCount(0);
        currentResults.clear();
        selectedCustomer = null;
    }
}
