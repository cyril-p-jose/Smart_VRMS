package in.smartvrms.desktop.ui;

import in.smartvrms.contracts.*;
import in.smartvrms.desktop.integration.PersonnelHttpClient;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.List;

/**
 * Live, authenticated personnel workspace.
 * Enforces role boundaries: Compensation is strictly Administrator-only (FR-ACC-003, FR-EMP-006).
 * All network calls run off the EDT via SwingWorker.
 */
public final class LivePersonnelView extends JPanel {
    private final PersonnelHttpClient api;
    private final StaffSession session;
    private final boolean administrator;

    private final JButton refreshBtn = Design.button("Refresh personnel", false);
    private final JButton registerBtn = Design.button("Register staff", true);
    private final JButton inviteBtn = Design.button("Invite staff (Admin)", false);
    private final JButton deactivateBtn = Design.button("Deactivate selected", false);
    private final JLabel feedback = Design.label("Loading personnel records…", 12, Design.MUTED);

    private final DefaultTableModel rosterModel = new DefaultTableModel(
            new String[]{"ID", "Full Name", "Contact", "Email", "Status", "Driver", "Joined"}, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable rosterTable = new JTable(rosterModel);

    // Selected Employee State
    private final JLabel selectedEmpLabel = Design.label("Select a staff member from the roster", 13, Design.MUTED);
    private UUID selectedEmployeeId;

    // Attendance inputs
    private final JTextField attDate = new JTextField(LocalDate.now().toString(), 10);
    private final JComboBox<String> attStatus = new JComboBox<>(new String[]{"PRESENT", "ABSENT", "PARTIAL_DAY", "ON_LEAVE"});
    private final JTextField attNotes = new JTextField(15);
    private final JButton saveAttBtn = Design.button("Record Attendance", true);
    private final JLabel attFeedback = Design.label("", 12, Design.PRIMARY);

    // Compensation inputs (Admin only)
    private final JComboBox<String> compType = new JComboBox<>(new String[]{"FIXED_MONTHLY", "DAILY_WAGE", "PER_TRIP", "MIXED"});
    private final JTextField compBase = new JTextField("25000", 8);
    private final JTextField compTrip = new JTextField("500", 6);
    private final JTextField compDate = new JTextField(LocalDate.now().toString(), 10);
    private final JButton saveCompBtn = Design.button("Record Compensation", true);
    private final JLabel compFeedback = Design.label("", 12, Design.PRIMARY);

    private List<EmployeeRecord> currentEmployees = List.of();
    private SwingWorker<?, ?> pending;
    private boolean active = true;

    public LivePersonnelView(PersonnelHttpClient api, StaffSession session) {
        super(new BorderLayout(0, 16));
        this.api = Objects.requireNonNull(api, "api");
        this.session = Objects.requireNonNull(session, "session");
        this.administrator = session.isAdministrator();
        setOpaque(false);

        // Header controls
        JPanel headerCard = Design.card("Company Personnel", "Active staff profiles, attendance and role administration.");
        JPanel actions = Design.row(refreshBtn, registerBtn);
        if (administrator) actions.add(inviteBtn);
        actions.add(deactivateBtn);
        headerCard.add(actions);
        headerCard.add(Box.createVerticalStrut(10));
        headerCard.add(feedback);
        add(headerCard, BorderLayout.NORTH);

        // Center: Roster table
        rosterTable.setRowHeight(32);
        rosterTable.setAutoCreateRowSorter(true);
        rosterTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rosterTable.setFillsViewportHeight(true);
        // Hide internal UUID column from visual display
        rosterTable.getColumnModel().getColumn(0).setMinWidth(0);
        rosterTable.getColumnModel().getColumn(0).setMaxWidth(0);
        rosterTable.getColumnModel().getColumn(0).setWidth(0);

        rosterTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = rosterTable.getSelectedRow();
                if (row >= 0) {
                    int modelRow = rosterTable.convertRowIndexToModel(row);
                    String idStr = (String) rosterModel.getValueAt(modelRow, 0);
                    String name = (String) rosterModel.getValueAt(modelRow, 1);
                    selectedEmployeeId = UUID.fromString(idStr);
                    selectedEmpLabel.setText("Selected: " + name);
                    deactivateBtn.setEnabled(administrator);
                    saveAttBtn.setEnabled(true);
                    saveCompBtn.setEnabled(administrator);
                    loadCompensationForSelected();
                } else {
                    selectedEmployeeId = null;
                    selectedEmpLabel.setText("Select a staff member from the roster");
                    deactivateBtn.setEnabled(false);
                    saveAttBtn.setEnabled(false);
                    saveCompBtn.setEnabled(false);
                }
            }
        });

        JScrollPane tableScroll = new JScrollPane(rosterTable);
        tableScroll.setPreferredSize(new Dimension(0, 220));

        // Bottom: Operations tabbed card (Attendance & Compensation)
        JTabbedPane tabs = new JTabbedPane();

        // Tab 1: Attendance
        JPanel attPanel = Design.vertical();
        attPanel.add(selectedEmpLabel);
        attPanel.add(Box.createVerticalStrut(10));
        attPanel.add(Design.fields(
                Design.field("Date (YYYY-MM-DD)", attDate),
                Design.field("Shift / Leave Remarks", attNotes)
        ));
        attPanel.add(Box.createVerticalStrut(8));
        attPanel.add(Design.row(Design.label("Status:", 13, Design.INK), attStatus));
        attPanel.add(Box.createVerticalStrut(12));
        attPanel.add(Design.row(saveAttBtn, attFeedback));
        JPanel attCard = Design.card("Shift Attendance", "Record daily attendance or approved leave (FR-EMP-003).");
        attCard.add(attPanel);
        tabs.addTab("Attendance & Leave", attCard);

        // Tab 2: Compensation (Admin only)
        if (administrator) {
            JPanel compPanel = Design.vertical();
            compPanel.add(Design.note("Administrator private projection: Remuneration tracking without invented statutory deduction policy (FR-ACC-003, FR-EMP-006, FR-EMP-007)."));
            compPanel.add(Box.createVerticalStrut(10));
            compPanel.add(Design.fields(
                    Design.field("Base Rate (INR)", compBase),
                    Design.field("Trip Allowance (INR)", compTrip)
            ));
            compPanel.add(Box.createVerticalStrut(8));
            compPanel.add(Design.fields(
                    Design.field("Effective From (YYYY-MM-DD)", compDate),
                    Design.top(Design.row(Design.label("Arrangement:", 13, Design.INK), compType))
            ));
            compPanel.add(Box.createVerticalStrut(12));
            compPanel.add(Design.row(saveCompBtn, compFeedback));
            JPanel compCard = Design.card("Salary & Trip Wage Terms", "Administrator-only compensation tracking.");
            compCard.add(compPanel);
            tabs.addTab("Compensation (Admin)", compCard);
        }

        JPanel centerPanel = new JPanel(new BorderLayout(0, 12));
        centerPanel.setOpaque(false);
        centerPanel.add(tableScroll, BorderLayout.CENTER);
        centerPanel.add(tabs, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);

        // Listeners
        refreshBtn.addActionListener(e -> reload());
        registerBtn.addActionListener(e -> registerDialog());
        inviteBtn.addActionListener(e -> inviteDialog());
        deactivateBtn.addActionListener(e -> deactivateDialog());
        saveAttBtn.addActionListener(e -> recordAttendanceAction());
        saveCompBtn.addActionListener(e -> recordCompensationAction());

        saveAttBtn.setEnabled(false);
        saveCompBtn.setEnabled(false);
        deactivateBtn.setEnabled(false);

        reload();
    }

    public void close() {
        active = false;
        if (pending != null) pending.cancel(true);
        rosterModel.setRowCount(0);
        currentEmployees = List.of();
    }

    private void busy(boolean value) {
        refreshBtn.setEnabled(!value);
        registerBtn.setEnabled(!value && administrator);
        inviteBtn.setEnabled(!value && administrator);
        deactivateBtn.setEnabled(!value && administrator && selectedEmployeeId != null);
        saveAttBtn.setEnabled(!value && selectedEmployeeId != null);
        saveCompBtn.setEnabled(!value && administrator && selectedEmployeeId != null);
    }

    public void reload() {
        if (!active) return;
        busy(true);
        feedback.setText("Loading company personnel…");
        pending = new SwingWorker<List<EmployeeRecord>, Void>() {
            @Override
            protected List<EmployeeRecord> doInBackground() {
                return api.listEmployees(false);
            }

            @Override
            protected void done() {
                if (!active) return;
                try {
                    currentEmployees = get();
                    rosterModel.setRowCount(0);
                    for (var e : currentEmployees) {
                        rosterModel.addRow(new Object[]{
                                e.id().toString(),
                                e.fullName(),
                                e.contactNumber(),
                                e.email() != null ? e.email() : "—",
                                e.employmentStatus(),
                                e.isDriver() ? "Yes" : "No",
                                e.joiningDate().toString()
                        });
                    }
                    feedback.setText(currentEmployees.isEmpty()
                            ? "No personnel registered yet. Administrators can register or invite staff."
                            : currentEmployees.size() + " staff records loaded.");
                } catch (Exception ex) {
                    rosterModel.setRowCount(0);
                    currentEmployees = List.of();
                    failure(ex);
                } finally {
                    busy(false);
                }
            }
        };
        pending.execute();
    }

    private void registerDialog() {
        JTextField nameF = new JTextField();
        JTextField contactF = new JTextField();
        JTextField emailF = new JTextField();
        JTextField addrF = new JTextField();
        JTextField joinF = new JTextField(LocalDate.now().toString());
        JCheckBox isDriverBox = new JCheckBox("Assign driver role (FR-EMP-008)");

        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("Full Name *")); form.add(nameF);
        form.add(new JLabel("Contact Number *")); form.add(contactF);
        form.add(new JLabel("Email Address")); form.add(emailF);
        form.add(new JLabel("Residential Address *")); form.add(addrF);
        form.add(new JLabel("Joining Date (YYYY-MM-DD) *")); form.add(joinF);
        form.add(new JLabel("Role Assignment")); form.add(isDriverBox);

        if (JOptionPane.showConfirmDialog(this, form, "Register Staff Member", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }

        String name = nameF.getText().strip();
        String contact = contactF.getText().strip();
        String email = emailF.getText().strip();
        String addr = addrF.getText().strip();
        String joinStr = joinF.getText().strip();

        if (name.isEmpty() || contact.isEmpty() || addr.isEmpty() || joinStr.isEmpty()) {
            feedback.setText("Full name, contact number, address, and joining date are mandatory.");
            return;
        }

        LocalDate joinDate;
        try {
            joinDate = LocalDate.parse(joinStr);
        } catch (Exception ex) {
            feedback.setText("Invalid joining date format. Use YYYY-MM-DD.");
            return;
        }

        // Duplicate Check (FR-EMP-008)
        busy(true);
        feedback.setText("Checking duplicates…");
        new SwingWorker<Optional<EmployeeRecord>, Void>() {
            @Override
            protected Optional<EmployeeRecord> doInBackground() {
                return api.checkDuplicate(contact, email);
            }

            @Override
            protected void done() {
                try {
                    var dup = get();
                    if (dup.isPresent()) {
                        EmployeeRecord existing = dup.get();
                        int choice = JOptionPane.showConfirmDialog(
                                LivePersonnelView.this,
                                "Possible duplicate found!\nExisting Staff: " + existing.fullName() +
                                        " (" + existing.contactNumber() + " / " + (existing.email() != null ? existing.email() : "") + ")\n" +
                                        "Do you still want to register a separate employee record?",
                                "Duplicate Staff Warning (FR-EMP-008)",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE
                        );
                        if (choice != JOptionPane.YES_OPTION) {
                            busy(false);
                            feedback.setText("Registration cancelled to avoid duplication.");
                            return;
                        }
                    }
                    performRegister(name, contact, email.isEmpty() ? null : email, addr, joinDate, isDriverBox.isSelected());
                } catch (Exception ex) {
                    failure(ex);
                    busy(false);
                }
            }
        }.execute();
    }

    private void performRegister(String name, String contact, String email, String addr, LocalDate joinDate, boolean isDriver) {
        feedback.setText("Registering staff member…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                api.registerEmployee(name, contact, email, addr, joinDate, isDriver, null);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    feedback.setText("Staff member " + name + " registered successfully.");
                    reload();
                } catch (Exception ex) {
                    failure(ex);
                    busy(false);
                }
            }
        }.execute();
    }

    private void inviteDialog() {
        JTextField emailF = new JTextField();
        JTextField nameF = new JTextField();
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("Staff Email *")); form.add(emailF);
        form.add(new JLabel("Full Name")); form.add(nameF);

        if (JOptionPane.showConfirmDialog(this, form, "Invite Employee Account (Admin)", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }

        String email = emailF.getText().strip();
        String name = nameF.getText().strip();
        if (email.isEmpty() || !email.contains("@")) {
            feedback.setText("Enter a valid email address for invitation.");
            return;
        }

        busy(true);
        feedback.setText("Sending invitation…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                api.inviteStaff(email, name);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    feedback.setText("Invitation sent to " + email);
                    reload();
                } catch (Exception ex) {
                    failure(ex);
                    busy(false);
                }
            }
        }.execute();
    }

    private void deactivateDialog() {
        if (selectedEmployeeId == null) return;
        String reason = JOptionPane.showInputDialog(
                this,
                "Enter mandatory reason for soft-deactivation (FR-EMP-002, FR-EMP-005):\n" +
                        "(Historical bookings, attendance, compensation and audit events remain intact.)",
                "Soft-Deactivate Employee",
                JOptionPane.WARNING_MESSAGE
        );
        if (reason == null || reason.strip().isEmpty()) {
            feedback.setText("Deactivation cancelled. Reason is mandatory.");
            return;
        }

        busy(true);
        feedback.setText("Deactivating employee…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                api.deactivateEmployee(selectedEmployeeId, reason.strip());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    feedback.setText("Employee deactivated. Historical integrity preserved.");
                    reload();
                } catch (Exception ex) {
                    failure(ex);
                    busy(false);
                }
            }
        }.execute();
    }

    private void recordAttendanceAction() {
        if (selectedEmployeeId == null) return;
        LocalDate date;
        try {
            date = LocalDate.parse(attDate.getText().strip());
        } catch (Exception ex) {
            attFeedback.setText("Invalid date format. Use YYYY-MM-DD.");
            return;
        }
        String status = (String) attStatus.getSelectedItem();
        String notes = attNotes.getText().strip();

        attFeedback.setText("Recording attendance…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                api.recordAttendance(selectedEmployeeId, date, status, notes);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    attFeedback.setText("Recorded: " + date + " as " + status);
                } catch (Exception ex) {
                    attFeedback.setText("Failed to record attendance: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void recordCompensationAction() {
        if (selectedEmployeeId == null || !administrator) return;
        String arrType = (String) compType.getSelectedItem();
        long basePaise;
        long tripPaise;
        try {
            basePaise = new BigDecimal(compBase.getText().strip()).movePointRight(2).longValueExact();
            tripPaise = new BigDecimal(compTrip.getText().strip()).movePointRight(2).longValueExact();
        } catch (Exception ex) {
            compFeedback.setText("Enter valid numbers for base and trip rates.");
            return;
        }
        LocalDate effDate;
        try {
            effDate = LocalDate.parse(compDate.getText().strip());
        } catch (Exception ex) {
            compFeedback.setText("Invalid effective date format.");
            return;
        }

        compFeedback.setText("Saving compensation terms…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                api.recordCompensation(selectedEmployeeId, arrType, basePaise, tripPaise, effDate);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    compFeedback.setText("Compensation arrangement recorded effective " + effDate);
                } catch (Exception ex) {
                    compFeedback.setText("Failed to save compensation: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void loadCompensationForSelected() {
        if (!administrator || selectedEmployeeId == null) return;
        new SwingWorker<Optional<CompensationRecord>, Void>() {
            @Override
            protected Optional<CompensationRecord> doInBackground() {
                return api.getCompensation(selectedEmployeeId);
            }

            @Override
            protected void done() {
                try {
                    var comp = get();
                    if (comp.isPresent()) {
                        CompensationRecord c = comp.get();
                        compType.setSelectedItem(c.arrangementType());
                        compBase.setText(BigDecimal.valueOf(c.baseRatePaise(), 2).toPlainString());
                        compTrip.setText(BigDecimal.valueOf(c.tripAllowancePaise(), 2).toPlainString());
                        compDate.setText(c.effectiveFrom().toString());
                        compFeedback.setText("Loaded active terms effective " + c.effectiveFrom());
                    } else {
                        compFeedback.setText("No compensation arrangement on record yet.");
                    }
                } catch (Exception ignored) {}
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
