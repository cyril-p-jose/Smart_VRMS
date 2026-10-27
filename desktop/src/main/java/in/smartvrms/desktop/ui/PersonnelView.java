package in.smartvrms.desktop.ui;

import in.smartvrms.contracts.StaffSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Connected personnel, attendance, and compensation management view.
 * Enforces role-based isolation: Compensation is strictly Administrator-only (FR-ACC-003, FR-EMP-006).
 */
public final class PersonnelView extends JPanel {
    private final StaffSession session;
    private final CardLayout tabs = new CardLayout();
    private final JPanel tabBody = new JPanel(tabs);
    private final ButtonGroup tabButtons = new ButtonGroup();

    // Roster specimens
    private final DefaultListModel<String> employeeListModel = new DefaultListModel<>();
    private final JList<String> employeeList = new JList<>(employeeListModel);

    // Register Form Fields
    public final JTextField fullName = new JTextField();
    public final JTextField phone = new JTextField();
    public final JTextField email = new JTextField();
    public final JTextField address = new JTextField();
    public final JTextField joiningDate = new JTextField(java.time.LocalDate.now().toString());
    public final JCheckBox isDriver = new JCheckBox("Assign driver role (FR-EMP-008)");
    public final JButton registerButton = Design.button("Register Employee", true);
    public final JLabel registerStatus = Design.label("", 12, Design.PRIMARY);

    // Deactivation Fields
    public final JTextField deactivateReason = new JTextField();
    public final JButton deactivateButton = Design.button("Soft-Deactivate Employee", false);
    public final JLabel deactivationFeedback = Design.label("", 12, Design.CAUTION);

    // Attendance Fields
    public final JTextField attendanceDate = new JTextField(java.time.LocalDate.now().toString());
    public final JComboBox<String> attendanceStatus = new JComboBox<>(new String[]{"PRESENT", "ABSENT", "ON_LEAVE"});
    public final JTextField attendanceNotes = new JTextField();
    public final JButton recordAttendanceBtn = Design.button("Record Attendance", true);
    public final JLabel attendanceStatusLabel = Design.label("", 12, Design.PRIMARY);

    // Compensation Fields (Admin only)
    public final JComboBox<String> compArrangement = new JComboBox<>(new String[]{"MONTHLY_SALARY", "DAILY_RATE", "PER_TRIP"});
    public final JTextField compBasePaise = new JTextField("2500000"); // 25,000 INR
    public final JTextField compTripPaise = new JTextField("50000");   // 500 INR
    public final JTextField compEffective = new JTextField(java.time.LocalDate.now().toString());
    public final JButton recordCompBtn = Design.button("Record Compensation Arrangement", true);
    public final JLabel compStatusLabel = Design.label("", 12, Design.PRIMARY);

    public PersonnelView(StaffSession session) {
        super(new BorderLayout(0, 16));
        this.session = session;
        setOpaque(false);

        // Sub-navigation bar
        JPanel navBar = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        navBar.setOpaque(false);
        addTabButton(navBar, "Roster & Personnel", "roster", true);
        addTabButton(navBar, "Attendance & Leave", "attendance", false);
        addTabButton(navBar, "Compensation (Admin)", "compensation", false);
        add(navBar, BorderLayout.NORTH);

        // Tab Bodies
        tabBody.add(buildRosterTab(), "roster");
        tabBody.add(buildAttendanceTab(), "attendance");
        tabBody.add(buildCompensationTab(), "compensation");

        add(tabBody, BorderLayout.CENTER);
    }

    private void addTabButton(JPanel parent, String title, String tabKey, boolean active) {
        JToggleButton btn = new JToggleButton(title);
        Design.styleButton(btn, active);
        btn.addActionListener(e -> {
            tabs.show(tabBody, tabKey);
            btn.setSelected(true);
        });
        tabButtons.add(btn);
        parent.add(btn);
        if (active) btn.setSelected(true);
    }

    private JComponent buildRosterTab() {
        JPanel sections = Design.vertical();

        // Fictional roster examples; PreviewSafety disables all local actions.
        DemoData.EMPLOYEES.forEach(e -> employeeListModel.addElement(e.name()+" • "+e.status()+" • "+e.role()));

        employeeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        employeeList.setFixedCellHeight(44);
        employeeList.setBorder(BorderFactory.createLineBorder(Design.BORDER));

        JPanel rosterCard = Design.card("Employee Roster", "Active and historical staff members (FR-EMP-001, FR-EMP-003).");
        rosterCard.add(new JScrollPane(employeeList));
        rosterCard.add(Box.createVerticalStrut(14));

        // Soft-deactivation panel
        rosterCard.add(Design.label("Employee Deactivation", 14, Design.INK));
        rosterCard.add(Box.createVerticalStrut(6));
        rosterCard.add(Design.note("Soft-deactivating preserves all historical bookings, compensation records, and audit events."));
        rosterCard.add(Box.createVerticalStrut(8));
        rosterCard.add(Design.field("Mandatory reason for deactivation", deactivateReason));
        rosterCard.add(Box.createVerticalStrut(8));

        deactivateButton.addActionListener(e -> {
            String sel = employeeList.getSelectedValue();
            if (sel == null) {
                deactivationFeedback.setText("Select an employee from the roster first.");
                return;
            }
            if (deactivateReason.getText().strip().isEmpty()) {
                deactivationFeedback.setText("Deactivation reason cannot be empty (FR-EMP-003).");
                return;
            }
            deactivationFeedback.setText("Employee soft-deactivated. Historical references preserved.");
            deactivateReason.setText("");
        });
        rosterCard.add(Design.row(deactivateButton, deactivationFeedback));

        sections.add(rosterCard);
        sections.add(Box.createVerticalStrut(16));

        // Registration Card
        JPanel regCard = Design.card("Register New Staff Member", "Personnel identity and operational role assignment.");
        regCard.add(Design.fields(Design.field("Full Name", fullName), Design.field("Contact Number", phone)));
        regCard.add(Box.createVerticalStrut(10));
        regCard.add(Design.fields(Design.field("Email Address", email), Design.field("Residential Address", address)));
        regCard.add(Box.createVerticalStrut(10));
        regCard.add(Design.fields(Design.field("Joining Date (YYYY-MM-DD)", joiningDate), Design.top(isDriver)));
        regCard.add(Box.createVerticalStrut(12));

        registerButton.addActionListener(e -> {
            if (fullName.getText().strip().isEmpty() || phone.getText().strip().isEmpty() || email.getText().strip().isEmpty()) {
                registerStatus.setText("Name, phone, and email are required.");
                return;
            }
            employeeListModel.addElement(fullName.getText().strip() + " • Active • " + (isDriver.isSelected() ? "Driver" : "Desk Staff"));
            registerStatus.setText("Registered " + fullName.getText().strip() + " successfully.");
            fullName.setText("");
            phone.setText("");
            email.setText("");
            address.setText("");
        });
        regCard.add(Design.row(registerButton, registerStatus));

        sections.add(regCard);

        Design.WidthPanel width = new Design.WidthPanel(new BorderLayout());
        width.add(sections, BorderLayout.NORTH);
        return Design.scroll(width);
    }

    private JComponent buildAttendanceTab() {
        JPanel card = Design.card("Attendance & Shift Calendar", "Track personnel attendance and leave schedules (FR-EMP-005).");
        card.add(Design.note("Exact-period eligibility incorporates active leave records into operational dispatch."));
        card.add(Box.createVerticalStrut(14));

        card.add(Design.fields(Design.field("Record Date (YYYY-MM-DD)", attendanceDate), Design.top(attendanceStatus)));
        card.add(Box.createVerticalStrut(10));
        card.add(Design.field("Notes / Shift Remarks", attendanceNotes));
        card.add(Box.createVerticalStrut(14));

        recordAttendanceBtn.addActionListener(e -> {
            attendanceStatusLabel.setText("Attendance recorded: " + attendanceDate.getText() + " as " + attendanceStatus.getSelectedItem());
        });
        card.add(Design.row(recordAttendanceBtn, attendanceStatusLabel));

        Design.WidthPanel width = new Design.WidthPanel(new BorderLayout());
        width.add(card, BorderLayout.NORTH);
        return Design.scroll(width);
    }

    private JComponent buildCompensationTab() {
        boolean isAdmin = session != null && session.isAdministrator();
        if (!isAdmin) {
            JPanel card = Design.card("Compensation Records", "Access Restricted to Administrators (FR-ACC-003)");
            card.add(Design.note("Personnel compensation, wage arrangements and margin fields are strictly hidden from Employee roles. Contact an Administrator for payroll records."));
            return Design.top(card);
        }

        JPanel card = Design.card("Compensation & Expense Records", "Administrator-only remuneration arrangements (FR-EMP-006).");
        card.add(Design.note("Records linked to company operational expenses. In accordance with FR-EMP-007, this system tracks compensation records without calculating statutory payroll deductions (PF/ESI/TDS)."));
        card.add(Box.createVerticalStrut(16));

        card.add(Design.fields(Design.top(compArrangement), Design.field("Base Rate (Paise, e.g. 2500000 = 25k INR)", compBasePaise)));
        card.add(Box.createVerticalStrut(10));
        card.add(Design.fields(Design.field("Trip Allowance (Paise)", compTripPaise), Design.field("Effective From (YYYY-MM-DD)", compEffective)));
        card.add(Box.createVerticalStrut(14));

        recordCompBtn.addActionListener(e -> {
            compStatusLabel.setText("Compensation arrangement recorded effective " + compEffective.getText());
        });
        card.add(Design.row(recordCompBtn, compStatusLabel));

        Design.WidthPanel width = new Design.WidthPanel(new BorderLayout());
        width.add(card, BorderLayout.NORTH);
        return Design.scroll(width);
    }
}
