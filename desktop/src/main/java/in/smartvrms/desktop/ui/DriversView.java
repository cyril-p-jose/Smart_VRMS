package in.smartvrms.desktop.ui;

import in.smartvrms.contracts.StaffSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Driver operational view and duty status coordination.
 * Note: Operational driver projections exclude salary and company margin fields (FR-ACC-003, FR-DRV-001).
 */
public final class DriversView extends JPanel {
    private final StaffSession session;
    private final DefaultListModel<String> driverListModel = new DefaultListModel<>();
    private final JList<String> driverList = new JList<>(driverListModel);

    // Selected Driver Details
    public final JLabel driverName = Design.label("Select a driver", 18, Design.INK);
    public final JLabel driverLicence = Design.label("Licence: —", 13, Design.MUTED);
    public final JLabel driverExperience = Design.label("Experience: —", 13, Design.MUTED);
    public final JLabel currentDutyStatus = Design.label("Status: —", 14, Design.PRIMARY);

    // Duty status updater
    public final JComboBox<String> newDutyStatus = new JComboBox<>(new String[]{"AVAILABLE", "ON_DUTY", "ON_LEAVE", "UNAVAILABLE"});
    public final JButton updateStatusBtn = Design.button("Update Duty Status", true);
    public final JLabel updateFeedback = Design.label("", 12, Design.PRIMARY);

    public DriversView(StaffSession session) {
        super(new BorderLayout(16, 16));
        this.session = session;
        setOpaque(false);

        // Header privacy notice
        JPanel noticeCard = Design.card("Driver Operational Profiles", "10 fictional driver examples · no records are saved.");
        noticeCard.add(Design.note("Sample duty and licence classes are illustrative. No licence numbers or personal contact details are shown. Live company data appears after staff sign-in."));
        add(noticeCard, BorderLayout.NORTH);

        // Left Panel: Driver list
        JPanel leftPanel = Design.vertical();
        JPanel rosterCard = Design.card("Operational Driver Roster", "Available and assigned drivers.");

        DemoData.DRIVERS.forEach(d -> driverListModel.addElement(d.name()+" • "+d.licenceClasses()+" • "+d.yearsExperience()+" Yrs • "+d.dutyStatus()));

        driverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        driverList.setFixedCellHeight(44);
        driverList.setBorder(BorderFactory.createLineBorder(Design.BORDER));
        rosterCard.add(new JScrollPane(driverList));
        leftPanel.add(rosterCard);

        // Right Panel: Details and Status updater
        JPanel rightPanel = Design.vertical();
        JPanel detailCard = Design.card("Driver Details & Status", "Licence credentials and shift availability.");
        detailCard.add(driverName);
        detailCard.add(Box.createVerticalStrut(4));
        detailCard.add(driverLicence);
        detailCard.add(Box.createVerticalStrut(4));
        detailCard.add(driverExperience);
        detailCard.add(Box.createVerticalStrut(4));
        detailCard.add(currentDutyStatus);
        detailCard.add(Box.createVerticalStrut(16));
        detailCard.add(new JSeparator());
        detailCard.add(Box.createVerticalStrut(14));

        detailCard.add(Design.label("Change Shift Availability (FR-DRV-003)", 14, Design.INK));
        detailCard.add(Box.createVerticalStrut(8));
        detailCard.add(Design.note("Availability updates immediately reflect on rental scheduling and eligibility checks."));
        detailCard.add(Box.createVerticalStrut(10));
        detailCard.add(Design.row(Design.label("New Status:", 13, Design.INK), newDutyStatus));
        detailCard.add(Box.createVerticalStrut(12));

        updateStatusBtn.addActionListener(e -> {
            String sel = driverList.getSelectedValue();
            if (sel == null) {
                updateFeedback.setText("Select a driver from the roster first.");
                return;
            }
            String selectedStatus = (String) newDutyStatus.getSelectedItem();
            updateFeedback.setText("Demo only — duty status was not changed or saved.");
        });

        detailCard.add(Design.row(updateStatusBtn, updateFeedback));
        rightPanel.add(detailCard);

        driverList.addListSelectionListener(e -> {
            String sel = driverList.getSelectedValue();
            if (sel != null) {
                String[] parts = sel.split("•");
                driverName.setText(parts[0].strip());
                driverLicence.setText("Licence Classes: " + (parts.length > 1 ? parts[1].strip() : "—"));
                driverExperience.setText("Experience: " + (parts.length > 2 ? parts[2].strip() : "—"));
                currentDutyStatus.setText("Status: " + (parts.length > 3 ? parts[3].strip() : "AVAILABLE"));
            }
        });

        Design.WidthPanel widthLeft = new Design.WidthPanel(new BorderLayout());
        widthLeft.add(leftPanel, BorderLayout.NORTH);

        Design.WidthPanel widthRight = new Design.WidthPanel(new BorderLayout());
        widthRight.add(rightPanel, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Design.scroll(widthLeft), Design.scroll(widthRight));
        split.setBorder(null);
        split.setResizeWeight(0.45);
        split.setDividerSize(8);
        split.setDividerLocation(380);

        add(split, BorderLayout.CENTER);
    }
}
