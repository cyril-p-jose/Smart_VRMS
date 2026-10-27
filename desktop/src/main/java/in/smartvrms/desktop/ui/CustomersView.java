package in.smartvrms.desktop.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Customer profile repository and visit history view.
 * Note: Standalone customer creation is disabled. Customer creation occurs strictly within booking (FR-CUST-002).
 */
public final class CustomersView extends JPanel {
    public final JTextField searchField = new JTextField();
    public final JButton searchButton = Design.button("Search Customer", true);
    public final DefaultListModel<String> resultsModel = new DefaultListModel<>();
    public final JList<String> resultsList = new JList<>(resultsModel);

    // Selected customer profile view
    public final JLabel customerName = Design.label("Select a customer", 18, Design.INK);
    public final JLabel customerContact = Design.label("—", 13, Design.MUTED);
    public final DefaultListModel<String> visitHistoryModel = new DefaultListModel<>();
    public final JList<String> visitList = new JList<>(visitHistoryModel);

    public CustomersView() {
        super(new BorderLayout(16, 16));
        setOpaque(false);

        // Header notice for FR-CUST-002
        JPanel noticeCard = Design.card("Customer Identity & History Repository", "10 fictional customer examples · no records are saved.");
        noticeCard.add(Design.note("Demo workspace only. Contact numbers are masked examples. In the connected workspace, real customer records are loaded from the company service; new identities are created inside a confirmed booking."));
        add(noticeCard, BorderLayout.NORTH);

        // Left: Search and Results
        JPanel leftPanel = Design.vertical();
        JPanel searchCard = Design.card("Lookup Customer", "Search by mobile number, email, or name.");
        searchCard.add(Design.field("Search query", searchField));
        searchCard.add(Box.createVerticalStrut(10));

        // Fictional, masked samples are kept local to the signed-out preview.
        DemoData.CUSTOMERS.forEach(customer -> resultsModel.addElement(format(customer)));

        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsList.setFixedCellHeight(40);
        resultsList.setBorder(BorderFactory.createLineBorder(Design.BORDER));

        searchButton.addActionListener(e -> {
            String q = searchField.getText().strip().toLowerCase(java.util.Locale.ROOT);
            resultsModel.clear();
            DemoData.CUSTOMERS.stream().filter(c -> q.isEmpty() || format(c).toLowerCase(java.util.Locale.ROOT).contains(q))
                    .forEach(c -> resultsModel.addElement(format(c)));
        });

        searchCard.add(Design.row(searchButton));
        searchCard.add(Box.createVerticalStrut(14));
        searchCard.add(new JScrollPane(resultsList));
        leftPanel.add(searchCard);

        // Right: Customer Profile & Visit Timeline
        JPanel rightPanel = Design.vertical();
        JPanel profileCard = Design.card("Customer Profile & Visit Timeline", "Verified rental history and age-at-visit logs.");
        profileCard.add(customerName);
        profileCard.add(Box.createVerticalStrut(4));
        profileCard.add(customerContact);
        profileCard.add(Box.createVerticalStrut(16));
        profileCard.add(new JSeparator());
        profileCard.add(Box.createVerticalStrut(14));

        profileCard.add(Design.label("Visit History (Age recorded per visit - FR-CUST-008)", 14, Design.INK));
        profileCard.add(Box.createVerticalStrut(8));

        visitList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        visitList.setFixedCellHeight(36);
        visitList.setBorder(BorderFactory.createLineBorder(Design.BORDER));
        profileCard.add(new JScrollPane(visitList));

        resultsList.addListSelectionListener(e -> {
            String sel = resultsList.getSelectedValue();
            if (sel != null) {
                String[] parts = sel.split("•");
                customerName.setText(parts[0].strip());
                customerContact.setText(parts.length > 1 ? parts[1].strip() : "");
                DemoData.CUSTOMERS.stream().filter(c -> c.name().equals(parts[0].strip())).findFirst().ifPresent(c -> {
                    visitHistoryModel.clear();
                    for (int i=0;i<c.completedRentals();i++) {
                        String date=java.time.LocalDate.now().minusDays(14L+i*23L).toString();
                        visitHistoryModel.addElement(date+" • Age at visit: "+c.ageAtVisit()+" • DEMO-BK-"+String.format("%04d",DemoData.CUSTOMERS.indexOf(c)*4+i+1)+" (Example)");
                    }
                });
            }
        });

        rightPanel.add(profileCard);

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

    private static String format(DemoData.Customer customer) {
        return customer.name()+" • "+customer.maskedPhone()+" • "+customer.city();
    }
}
