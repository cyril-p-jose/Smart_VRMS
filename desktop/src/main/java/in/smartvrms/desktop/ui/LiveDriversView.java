package in.smartvrms.desktop.ui;

import in.smartvrms.contracts.DriverProfileRecord;
import in.smartvrms.contracts.EmployeeRecord;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.desktop.integration.DriverHttpClient;
import in.smartvrms.desktop.integration.PersonnelHttpClient;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;
import java.util.*;
import java.util.List;

/**
 * Live, authenticated operational driver master view.
 * Privacy guarantee: Projections strictly exclude salary, compensation arrangements, and profit margin (FR-ACC-003, FR-DRV-001).
 * All network calls run off the EDT via SwingWorker.
 */
public final class LiveDriversView extends JPanel {
    private final DriverHttpClient api;
    private final PersonnelHttpClient personnelApi;
    private final StaffSession session;
    private final boolean administrator;

    private final JButton refreshBtn = Design.button("Refresh drivers", false);
    private final JButton registerBtn = Design.button("Register driver profile", true);
    private final JComboBox<String> filterStatus = new JComboBox<>(new String[]{"ALL", "AVAILABLE", "ASSIGNED", "ON_LEAVE", "UNAVAILABLE"});
    private final JLabel feedback = Design.label("Loading driver roster…", 12, Design.MUTED);

    private final DefaultTableModel driverModel = new DefaultTableModel(
            new String[]{"ID", "Driver Name", "Contact", "Licence Number", "Classes", "Expiry", "Exp (Yrs)", "Duty Status", "Employed"}, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable driverTable = new JTable(driverModel);

    // Selected Driver panel
    private final JLabel selectedDriverLabel = Design.label("Select a driver to update shift availability", 14, Design.INK);
    private final JComboBox<String> newDutyStatus = new JComboBox<>(new String[]{"AVAILABLE", "ASSIGNED", "ON_LEAVE", "UNAVAILABLE"});
    private final JButton updateStatusBtn = Design.button("Update Duty Status", true);
    private final JLabel statusFeedback = Design.label("", 12, Design.PRIMARY);
    private UUID selectedDriverId;

    private List<DriverProfileRecord> currentDrivers = List.of();
    private SwingWorker<?, ?> pending;
    private boolean active = true;

    public LiveDriversView(DriverHttpClient api, PersonnelHttpClient personnelApi, StaffSession session) {
        super(new BorderLayout(0, 16));
        this.api = Objects.requireNonNull(api, "api");
        this.personnelApi = personnelApi;
        this.session = Objects.requireNonNull(session, "session");
        this.administrator = session.isAdministrator();
        setOpaque(false);

        // Header privacy notice and controls
        JPanel headerCard = Design.card("Driver Operational Profiles", "Driver master repository and shift availability (FR-DRV-001, FR-DRV-003).");
        headerCard.add(Design.note("PRIVACY GUARANTEE: Operational driver projections strictly exclude salary, compensation arrangements, and company margin data (FR-ACC-003, FR-DRV-001)."));
        headerCard.add(Box.createVerticalStrut(10));
        JPanel filterRow = Design.row(refreshBtn, registerBtn, Design.label("Filter Status:", 12, Design.MUTED), filterStatus);
        headerCard.add(filterRow);
        headerCard.add(Box.createVerticalStrut(8));
        headerCard.add(feedback);
        add(headerCard, BorderLayout.NORTH);

        // Center: Driver table
        driverTable.setRowHeight(32);
        driverTable.setAutoCreateRowSorter(true);
        driverTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        driverTable.setFillsViewportHeight(true);
        // Hide internal UUID column from visual display
        driverTable.getColumnModel().getColumn(0).setMinWidth(0);
        driverTable.getColumnModel().getColumn(0).setMaxWidth(0);
        driverTable.getColumnModel().getColumn(0).setWidth(0);

        driverTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = driverTable.getSelectedRow();
                if (row >= 0) {
                    int modelRow = driverTable.convertRowIndexToModel(row);
                    String idStr = (String) driverModel.getValueAt(modelRow, 0);
                    String name = (String) driverModel.getValueAt(modelRow, 1);
                    String curStatus = (String) driverModel.getValueAt(modelRow, 7);
                    selectedDriverId = UUID.fromString(idStr);
                    selectedDriverLabel.setText("Selected Driver: " + name + " (Current: " + curStatus + ")");
                    newDutyStatus.setSelectedItem(curStatus);
                    updateStatusBtn.setEnabled(true);
                } else {
                    selectedDriverId = null;
                    selectedDriverLabel.setText("Select a driver to update shift availability");
                    updateStatusBtn.setEnabled(false);
                }
            }
        });

        JScrollPane tableScroll = new JScrollPane(driverTable);
        tableScroll.setPreferredSize(new Dimension(0, 260));

        // Bottom: Status updater card
        JPanel statusCard = Design.card("Shift Availability & Duty Transition", "Update operational duty status independently of vehicle availability (FR-DRV-003).");
        statusCard.add(selectedDriverLabel);
        statusCard.add(Box.createVerticalStrut(10));
        statusCard.add(Design.row(Design.label("New Duty Status:", 13, Design.INK), newDutyStatus, updateStatusBtn, statusFeedback));

        JPanel centerPanel = new JPanel(new BorderLayout(0, 12));
        centerPanel.setOpaque(false);
        centerPanel.add(tableScroll, BorderLayout.CENTER);
        centerPanel.add(statusCard, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);

        // Listeners
        refreshBtn.addActionListener(e -> reload());
        registerBtn.addActionListener(e -> registerDialog());
        filterStatus.addActionListener(e -> reload());
        updateStatusBtn.addActionListener(e -> updateStatusAction());

        registerBtn.setEnabled(administrator);
        updateStatusBtn.setEnabled(false);

        reload();
    }

    public void close() {
        active = false;
        if (pending != null) pending.cancel(true);
        driverModel.setRowCount(0);
        currentDrivers = List.of();
    }

    private void busy(boolean value) {
        refreshBtn.setEnabled(!value);
        registerBtn.setEnabled(!value && administrator);
        filterStatus.setEnabled(!value);
        updateStatusBtn.setEnabled(!value && selectedDriverId != null);
    }

    public void reload() {
        if (!active) return;
        busy(true);
        feedback.setText("Loading driver records…");
        String filter = (String) filterStatus.getSelectedItem();
        String queryStatus = "ALL".equalsIgnoreCase(filter) ? null : filter;

        pending = new SwingWorker<List<DriverProfileRecord>, Void>() {
            @Override
            protected List<DriverProfileRecord> doInBackground() {
                return api.listDrivers(queryStatus);
            }

            @Override
            protected void done() {
                if (!active) return;
                try {
                    currentDrivers = get();
                    driverModel.setRowCount(0);
                    for (var d : currentDrivers) {
                        driverModel.addRow(new Object[]{
                                d.id().toString(),
                                d.fullName(),
                                d.contactNumber(),
                                d.licenceNumber(),
                                String.join(", ", d.licenceClasses()),
                                d.licenceExpiry().toString(),
                                d.experienceYears(),
                                d.dutyStatus(),
                                d.isEmployed() ? "Active" : "Inactive"
                        });
                    }
                    feedback.setText(currentDrivers.isEmpty()
                            ? "No driver profiles registered yet."
                            : currentDrivers.size() + " operational driver records loaded.");
                } catch (Exception ex) {
                    driverModel.setRowCount(0);
                    currentDrivers = List.of();
                    failure(ex);
                } finally {
                    busy(false);
                }
            }
        };
        pending.execute();
    }

    private void registerDialog() {
        if (personnelApi == null) {
            feedback.setText("Personnel client is required to select an employee for driver registration.");
            return;
        }

        busy(true);
        feedback.setText("Loading staff list for driver registration…");
        new SwingWorker<List<EmployeeRecord>, Void>() {
            @Override
            protected List<EmployeeRecord> doInBackground() {
                return personnelApi.listEmployees(true);
            }

            @Override
            protected void done() {
                busy(false);
                try {
                    List<EmployeeRecord> employees = get();
                    if (employees.isEmpty()) {
                        feedback.setText("No active employees found. Register a staff member first.");
                        return;
                    }
                    showRegisterForm(employees);
                } catch (Exception ex) {
                    failure(ex);
                }
            }
        }.execute();
    }

    private void showRegisterForm(List<EmployeeRecord> employees) {
        JComboBox<String> empCombo = new JComboBox<>();
        Map<String, UUID> empMap = new LinkedHashMap<>();
        for (var e : employees) {
            String label = e.fullName() + " (" + e.contactNumber() + (e.isDriver() ? " - Driver role" : "") + ")";
            empCombo.addItem(label);
            empMap.put(label, e.id());
        }

        JTextField licenceF = new JTextField();
        JTextField classesF = new JTextField("LMV, HMV");
        JTextField expiryF = new JTextField(LocalDate.now().plusYears(3).toString());
        JTextField expYearsF = new JTextField("5");

        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("Staff Member *")); form.add(empCombo);
        form.add(new JLabel("Licence Number *")); form.add(licenceF);
        form.add(new JLabel("Licence Classes (comma-separated) *")); form.add(classesF);
        form.add(new JLabel("Licence Expiry (YYYY-MM-DD) *")); form.add(expiryF);
        form.add(new JLabel("Experience (Years) *")); form.add(expYearsF);

        if (JOptionPane.showConfirmDialog(this, form, "Register Driver Operational Profile", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }

        String selectedEmp = (String) empCombo.getSelectedItem();
        UUID empId = empMap.get(selectedEmp);
        String licence = licenceF.getText().strip();
        String classesStr = classesF.getText().strip();
        String expStr = expiryF.getText().strip();
        String yrsStr = expYearsF.getText().strip();

        if (empId == null || licence.isEmpty() || classesStr.isEmpty() || expStr.isEmpty() || yrsStr.isEmpty()) {
            feedback.setText("All fields are mandatory for driver profile registration.");
            return;
        }

        LocalDate expiryDate;
        int years;
        try {
            expiryDate = LocalDate.parse(expStr);
            years = Integer.parseInt(yrsStr);
        } catch (Exception ex) {
            feedback.setText("Invalid date or experience format.");
            return;
        }

        List<String> classes = new ArrayList<>();
        for (String c : classesStr.split(",")) {
            if (!c.strip().isEmpty()) classes.add(c.strip().toUpperCase(Locale.ROOT));
        }

        busy(true);
        feedback.setText("Registering driver profile…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                api.registerDriver(empId, licence, classes, expiryDate, years);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    feedback.setText("Driver profile registered successfully.");
                    reload();
                } catch (Exception ex) {
                    failure(ex);
                    busy(false);
                }
            }
        }.execute();
    }

    private void updateStatusAction() {
        if (selectedDriverId == null) return;
        String status = (String) newDutyStatus.getSelectedItem();
        statusFeedback.setText("Updating status…");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                api.updateDutyStatus(selectedDriverId, status);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    statusFeedback.setText("Duty status updated to " + status);
                    reload();
                } catch (Exception ex) {
                    statusFeedback.setText("Failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void failure(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        feedback.setText(cause instanceof SecurityException
                ? "Access denied. Role unauthorized for this operation."
                : "Request failed: " + cause.getMessage());
    }
}
