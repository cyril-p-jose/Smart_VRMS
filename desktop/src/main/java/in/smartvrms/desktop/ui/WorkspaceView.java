package in.smartvrms.desktop.ui;

import in.smartvrms.application.booking.DraftValidator;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.desktop.controller.BookingController;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Navigation presents workflow, never trusted authorization. */
public final class WorkspaceView extends JPanel {
    private final CardLayout pages = new CardLayout();
    private final JPanel content = new JPanel(pages);
    public final BookingView booking;
    public final RunningServicesView runningServices;
    public final FinanceView financeView;
    public final AutomationAndReportsView automationReportsView;
    private final ButtonGroup navigation = new ButtonGroup();
    private final JLabel headerTitle;
    private final JLabel headerStatus;
    private final JButton exitButton;
    private final List<String> managementItems = List.of("Personnel", "Finance", "Reports", "Settings");
    private StaffSession session;
    private LiveFleetView liveFleet;
    private LivePersonnelView livePersonnel;
    private LiveDriversView liveDrivers;
    private LiveCustomersView liveCustomers;
    private boolean operationsConnected = false;
    private boolean financeConnected = false;
    private boolean automationConnected = false;


    public WorkspaceView(Runnable signIn) {
        this(signIn, null);
    }

    public WorkspaceView(Runnable signIn, StaffSession initialSession) {
        super(new BorderLayout());
        this.session = initialSession;
        booking = new BookingView(() -> showPage("Vehicles"));
        runningServices = new RunningServicesView(initialSession);
        financeView = new FinanceView(initialSession);
        automationReportsView = new AutomationAndReportsView(initialSession);

        JPanel sidebar = new JPanel(new BorderLayout(0, 20));
        sidebar.setPreferredSize(new Dimension(198, 0));
        sidebar.setBackground(Design.NAV);
        sidebar.setBorder(new EmptyBorder(28, 16, 20, 16));

        JPanel brand = Design.vertical();
        brand.add(Design.label("smart vrms", 23, Color.WHITE));
        brand.add(Box.createVerticalStrut(6));
        brand.add(Design.label("RENTAL OPERATIONS", 10, new Color(0xA6B7C1)));
        sidebar.add(brand, BorderLayout.NORTH);

        JPanel links = Design.vertical();
        group(links, "DAILY WORK", new String[]{"Overview", "New booking", "Running services"});
        group(links, "RECORDS", new String[]{"Vehicles", "Customers", "Drivers"});
        group(links, "MANAGEMENT", managementItems.toArray(new String[0]));

        JScrollPane nav = Design.scroll(Design.top(links));
        nav.getViewport().setBackground(Design.NAV);
        sidebar.add(nav);

        exitButton = Design.button(session == null ? "Back to sign-in" : "Sign out", false);
        exitButton.addActionListener(e -> signIn.run());
        sidebar.add(exitButton, BorderLayout.SOUTH);
        add(sidebar, BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Color.WHITE);
        bar.setBorder(new EmptyBorder(14, 24, 14, 24));

        headerTitle = Design.label(sessionTitle(), 11, Design.PRIMARY);
        headerStatus = Design.label(sessionStatus(), 12, Design.MUTED);
        bar.add(headerTitle, BorderLayout.WEST);
        bar.add(headerStatus, BorderLayout.EAST);
        main.add(bar, BorderLayout.NORTH);
        main.add(content);
        add(main);

        content.add(overview(), "Overview");
        content.add(fleetPreview(), "Vehicles");
        content.add(Design.page("New rental", "Customer, rental period and vehicle — reviewed together before confirmation.", booking), "New booking");
        content.add(Design.page("Running services", "Operational custody, inspections & extensions", PreviewSafety.readOnly(runningServices)), "Running services");
        content.add(customersPreview(), "Customers");
        content.add(driversPreview(), "Drivers");

        new BookingController(booking, new DraftValidator());
        updateManagementPages();
        PreviewSafety.disableActions(runningServices);
        PreviewSafety.disableActions(financeView);
        PreviewSafety.disableActions(automationReportsView);
        showPage("Overview");
    }

    public void setSession(StaffSession newSession) {
        if(liveFleet!=null){liveFleet.close();content.remove(findComponentByName(content,"Vehicles"));liveFleet=null;content.add(fleetPreview(),"Vehicles");}
        if(livePersonnel!=null){livePersonnel.close();content.remove(findComponentByName(content,"Personnel"));livePersonnel=null;}
        if(liveDrivers!=null){liveDrivers.close();content.remove(findComponentByName(content,"Drivers"));liveDrivers=null;content.add(driversPreview(),"Drivers");}
        if(liveCustomers!=null){liveCustomers.close();content.remove(findComponentByName(content,"Customers"));liveCustomers=null;content.add(customersPreview(),"Customers");}
        this.session = newSession;
        runningServices.setSession(newSession);
        financeView.setSession(newSession);
        automationReportsView.setSession(newSession);
        headerTitle.setText(sessionTitle());
        headerStatus.setText(sessionStatus());
        exitButton.setText(session == null ? "Back to sign-in" : "Sign out");
        if (newSession == null) {
            booking.setClients(null, null, null);
            booking.clearSelection();
            runningServices.setClient(null);
            financeView.setClient(null);
            automationReportsView.setClients(null, null, null);
            operationsConnected = false;
            financeConnected = false;
            automationConnected = false;
        }
        updateManagementPages();
        if (!operationsConnected) PreviewSafety.disableActions(runningServices);
        if (!financeConnected) PreviewSafety.disableActions(financeView);
        if (!automationConnected) PreviewSafety.disableActions(automationReportsView);
    }

    public StaffSession session() {
        return session;
    }

    private JComponent fleetPreview(){
        JPanel page=Design.page("Fleet preview","10 fictional vehicles · sample references are not valid registrations.",new FleetView(candidate->{booking.shortlist(candidate);showPage("New booking");}));
        page.setName("Vehicles");return page;
    }

    private JComponent customersPreview() {
        JPanel page = Design.page("Customer examples", "10 fictional customer profiles with masked contact details.", PreviewSafety.readOnly(new CustomersView()));
        page.setName("Customers");
        return page;
    }

    private JComponent driversPreview() {
        JPanel page = Design.page("Driver examples", "10 fictional operational profiles; no licence numbers are included.", PreviewSafety.readOnly(new DriversView(session)));
        page.setName("Drivers");
        return page;
    }


    public void connectFleet(in.smartvrms.desktop.integration.FleetHttpClient api) {
        if (session == null) throw new IllegalStateException("Staff session required.");
        content.remove(findComponentByName(content, "Vehicles"));
        liveFleet = new LiveFleetView(api, session, vehicle -> {
            booking.shortlist(vehicle.id(), vehicle.manufacturer() + " " + vehicle.model() + " (" + vehicle.registrationPlate() + ")");
            showPage("New booking");
        });
        JPanel page = Design.page("Company fleet", "Authenticated records", liveFleet);
        page.setName("Vehicles");
        content.add(page, "Vehicles");
        content.revalidate();
        content.repaint();
    }

    public void connectCustomers(in.smartvrms.desktop.integration.CustomerHttpClient api) {
        if (session == null) throw new IllegalStateException("Staff session required.");
        content.remove(findComponentByName(content, "Customers"));
        liveCustomers = new LiveCustomersView(api, session, customer -> {
            booking.prefillCustomer(customer);
            showPage("New booking");
        });
        JPanel page = Design.page("Company customers", "Authenticated records", liveCustomers);
        page.setName("Customers");
        content.add(page, "Customers");
        content.revalidate();
        content.repaint();
    }


    public void connectBooking(in.smartvrms.desktop.integration.BookingHttpClient bookingApi,
                               in.smartvrms.desktop.integration.CustomerHttpClient customerApi,
                               in.smartvrms.desktop.integration.FleetHttpClient fleetApi) {
        if (session == null) throw new IllegalStateException("Staff session required.");
        booking.setClients(bookingApi, customerApi, fleetApi);
    }

    public void connectOperations(in.smartvrms.desktop.integration.OperationsHttpClient operationsApi) {
        if (session == null) throw new IllegalStateException("Staff session required.");
        operationsConnected = true;
        content.remove(findComponentByName(content, "Running services"));
        runningServices.setClient(operationsApi);
        JPanel page = Design.page("Running services", "Operational custody, inspections & extensions", runningServices);
        page.setName("Running services");
        content.add(page, "Running services");
        content.revalidate();
        content.repaint();
    }

    public void connectPersonnel(in.smartvrms.desktop.integration.PersonnelHttpClient api) {
        if (session == null) throw new IllegalStateException("Staff session required.");
        content.remove(findComponentByName(content, "Personnel"));
        livePersonnel = new LivePersonnelView(api, session);
        JPanel page = Design.page("Company personnel", "Authenticated records", livePersonnel);
        page.setName("Personnel");
        content.add(page, "Personnel");
        content.revalidate();
        content.repaint();
    }

    public void connectDrivers(in.smartvrms.desktop.integration.DriverHttpClient api, in.smartvrms.desktop.integration.PersonnelHttpClient personnelApi) {
        if (session == null) throw new IllegalStateException("Staff session required.");
        content.remove(findComponentByName(content, "Drivers"));
        liveDrivers = new LiveDriversView(api, personnelApi, session);
        JPanel page = Design.page("Operational drivers", "Authenticated records", liveDrivers);
        page.setName("Drivers");
        content.add(page, "Drivers");
        content.revalidate();
        content.repaint();
    }

    public void connectFinance(in.smartvrms.desktop.integration.FinanceHttpClient api) {
        if (session == null) throw new IllegalStateException("Staff session required.");
        financeConnected = true;
        content.remove(findComponentByName(content, "Finance"));
        financeView.setClient(api);
        JPanel page = Design.page("Finance & ledger", "Authoritative billing, receipts & ledger", financeView);
        page.setName("Finance");
        content.add(page, "Finance");
        content.revalidate();
        content.repaint();
    }

    public void connectAutomationAndReports(
            in.smartvrms.desktop.integration.AutomationHttpClient automationApi,
            in.smartvrms.desktop.integration.ReviewHttpClient reviewApi,
            in.smartvrms.desktop.integration.ReportingHttpClient reportingApi
    ) {
        if (session == null) throw new IllegalStateException("Staff session required.");
        automationConnected = true;
        content.remove(findComponentByName(content, "Reports"));
        automationReportsView.setClients(automationApi, reviewApi, reportingApi);
        JPanel page = Design.page("Reports & automation", "Transactional outbox, feedback & reconciliations", automationReportsView);
        page.setName("Reports");
        content.add(page, "Reports");
        content.revalidate();
        content.repaint();
    }

    private String sessionTitle() {
        if (session == null) return "WORKSPACE PREVIEW";
        return "STAFF WORKSPACE • " + session.role();
    }

    private String sessionStatus() {
        if (session == null) return "Not connected  ·  No live records";
        String accessLabel = session.isAdministrator() ? "Administrator access" : "Operational access";
        String email = session.email() != null && !session.email().isBlank() ? session.email() + "  ·  " : "";
        return email + accessLabel;
    }

    private void updateManagementPages() {
        for (String item : managementItems) {
            content.remove(findComponentByName(content, item));
            if (session != null && session.isEmployee()) {
                content.add(restricted(item), item);
            } else if (session != null && session.isAdministrator()) {
                JComponent section = PreviewSafety.readOnly(administratorSection(item));
                section.setName(item);
                content.add(section, item);
            } else {
                content.add(demoManagementSection(item), item);
            }
        }
        content.revalidate();
        content.repaint();
    }

    private JComponent demoManagementSection(String name) {
        JComponent page=switch(name) {
            case "Personnel" -> Design.page("Personnel examples","10 fictional team members · no compensation or attendance is recorded.",
                    PreviewSafety.readOnly(new PersonnelView(null)));
            case "Finance" -> Design.page("Finance examples","10 sample payments and invoices · illustrative INR totals only.",
                    PreviewSafety.readOnly(financeView));
            case "Reports" -> Design.page("Reviews & report examples","10 fictional reviews · no message delivery or live report is simulated.",
                    PreviewSafety.readOnly(automationReportsView));
            default -> unavailable(name);
        };
        page.setName(name);
        return page;
    }


    private Component findComponentByName(Container parent, String name) {
        for (Component c : parent.getComponents()) {
            if (name.equals(c.getName())) return c;
        }
        return new JPanel();
    }

    private void group(JPanel links, String heading, String[] items) {
        links.add(Box.createVerticalStrut(16));
        links.add(Design.label(heading, 10, new Color(0xA6B7C1)));
        links.add(Box.createVerticalStrut(8));
        for (String item : items) {
            JToggleButton link = new JToggleButton(item);
            link.setUI(new javax.swing.plaf.basic.BasicButtonUI());
            link.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
            link.setHorizontalAlignment(SwingConstants.LEFT);
            link.setBorder(new EmptyBorder(10, 12, 10, 12));
            Runnable colors = () -> {
                link.setBackground(link.isSelected() ? new Color(0x304B55) : Design.NAV);
                link.setForeground(Color.WHITE);
            };
            link.getModel().addChangeListener(e -> colors.run());
            colors.run();
            navigation.add(link);
            links.add(link);
            links.add(Box.createVerticalStrut(2));
            link.addActionListener(e -> showPage(item));
            if (!item.equals("Overview") && !item.equals("Vehicles") && !item.equals("New booking") && !item.equals("Running services") && !item.equals("Customers") && !item.equals("Drivers") && !item.equals("Finance") && !item.equals("Reports")) {
                JComponent pageComponent = unavailable(item);
                pageComponent.setName(item);
                content.add(pageComponent, item);
            }
        }
    }

    public void showPage(String name) {
        pages.show(content, name);
        var buttons = navigation.getElements();
        while (buttons.hasMoreElements()) {
            var b = buttons.nextElement();
            b.setSelected(b.getText().equals(name));
        }
    }

    private JComponent overview() {
        JPanel sections = Design.vertical();
        JPanel welcome = Design.card("Start a rental", "Choose a vehicle, enter the customer and rental dates, then review the draft.");
        JButton begin = Design.button("New rental draft", true);
        begin.addActionListener(e -> showPage("New booking"));
        JButton fleet = Design.button("Browse fleet", false);
        fleet.addActionListener(e -> showPage("Vehicles"));
        JButton custBtn = Design.button("Customer repository", false);
        custBtn.addActionListener(e -> showPage("Customers"));
        welcome.add(Design.row(begin, fleet, custBtn));
        sections.add(welcome);
        sections.add(Box.createVerticalStrut(18));

        JPanel work = Design.card("Daily Rental Operations", session != null
                ? ("Signed-in as " + session.email() + " • Role: " + session.role() + " (Authenticated company session)")
                : "Operational workflows will load company data after sign-in.");

        for (String[] item : new String[][]{
                {"01", "New Bookings & Customer KYC", "Capture customer identity, choose vehicle package, calculate authoritative quotes and reserve atomically", "New booking"},
                {"02", "Running Services & Custody", "Record vehicle checkout, odometer, fuel, return condition inspections, and extensions with saved rates", "Running services"},
                {"03", "Fleet Master & Documents", "Manage vehicle categories, registrations, jurisdiction documents, and freeze/reactivate restrictions", "Vehicles"},
                {"04", "Drivers & Duty Shifts", "Track driver licences, shift availability, and duty assignments without salary leakage", "Drivers"}}) {
            JPanel row = new JPanel(new BorderLayout(18, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(14, 0, 14, 0));
            row.add(Design.label(item[0], 18, Design.PRIMARY), BorderLayout.WEST);
            JPanel copy = Design.vertical();
            copy.add(Design.label(item[1], 15, Design.INK));
            copy.add(Box.createVerticalStrut(4));
            copy.add(Design.note(item[2]));
            row.add(copy, BorderLayout.CENTER);
            JButton navBtn = Design.button("Open " + item[1].split("&")[0].trim(), false);
            final String targetPage = item[3];
            navBtn.addActionListener(e -> showPage(targetPage));
            row.add(navBtn, BorderLayout.EAST);
            work.add(row);
            work.add(new JSeparator());
        }
        sections.add(work);
        sections.add(Box.createVerticalStrut(18));
        sections.add(Design.note(session != null
                ? "Company records connected via authenticated API. All write operations maintain relational integrity and audit logging."
                : "This workspace is in preview mode. Sign in with staff credentials to load live records."));
        Design.WidthPanel page = new Design.WidthPanel(new BorderLayout());
        page.add(sections, BorderLayout.NORTH);
        return Design.page("Rental desk", "One company · Kerala · INR · Asia/Kolkata", Design.scroll(page));
    }


    private JComponent unavailable(String name) {
        JPanel card = Design.card(name, "This module is awaiting its connected service.");
        card.add(Design.note("No records have been loaded. This feature remains in the approved scope; the preview currently supports a fleet-to-rental-draft journey."));
        card.add(Box.createVerticalStrut(18));
        JButton draft = Design.button("Open rental draft", false);
        draft.addActionListener(e -> showPage("New booking"));
        card.add(draft);
        JPanel p = Design.page(name, "Service not connected", Design.top(card));
        p.setName(name);
        return p;
    }

    private JComponent restricted(String name) {
        JPanel card = Design.card(name, "Access Restricted to Administrators");
        card.add(Design.note("Your active account has role EMPLOYEE. Operational staff cannot view or modify personnel compensation, payroll, company margins, or sensitive financial reports (FR-ACC-001, FR-ACC-003)."));
        card.add(Box.createVerticalStrut(18));
        JButton fleet = Design.button("Return to fleet overview", false);
        fleet.addActionListener(e -> showPage("Vehicles"));
        card.add(fleet);
        JPanel p = Design.page(name, "Restricted Section", Design.top(card));
        p.setName(name);
        return p;
    }

    private JComponent administratorSection(String name) {
        if ("Personnel".equals(name)) {
            JPanel sections = Design.vertical();
            JPanel card = Design.card(name, "Administrator Workspace — Administrator Personnel & Staff Provisioning");
            card.add(Design.note("Invite employee staff accounts and manage active memberships (FR-AUTH-002, FR-EMP-004)."));
            card.add(Box.createVerticalStrut(12));
            JTextField emailField = new JTextField(20);
            JTextField nameField = new JTextField(20);
            card.add(Design.row(Design.label("Staff Email:", 12, Design.INK), emailField));
            card.add(Box.createVerticalStrut(8));
            card.add(Design.row(Design.label("Full Name:", 12, Design.INK), nameField));
            card.add(Box.createVerticalStrut(12));
            JButton inviteBtn = Design.button("Send Staff Invitation", true);
            JLabel statusLabel = Design.label("", 12, Design.PRIMARY);
            inviteBtn.addActionListener(e -> {
                String email = emailField.getText().strip();
                if (email.isEmpty() || !email.contains("@")) {
                    statusLabel.setText("Please enter a valid staff email.");
                    return;
                }
                statusLabel.setText("Staff invitation recorded for " + email);
                emailField.setText("");
                nameField.setText("");
            });
            card.add(Design.row(inviteBtn, statusLabel));
            sections.add(card);
            sections.add(Box.createVerticalStrut(16));
            sections.add(new PersonnelView(session));

            Design.WidthPanel width = new Design.WidthPanel(new BorderLayout());
            width.add(sections, BorderLayout.NORTH);
            JPanel p = Design.page(name, "Authorized Administrator Access", Design.scroll(width));
            p.setName(name);
            return p;
        }

        if ("Settings".equals(name)) {
            JPanel sections = Design.vertical();
            JPanel card = Design.card(name, "System Environment & Connection Settings");
            card.add(Design.note("Desktop client runtime configuration and authenticated session details."));
            card.add(Box.createVerticalStrut(12));

            String email = session != null ? session.email() : "Not authenticated";
            String role = session != null ? session.role() : "None";
            String cid = session != null ? session.companyId().toString() : "None";
            String uid = session != null ? session.userId().toString() : "None";

            JPanel details = new JPanel(new GridLayout(4, 2, 8, 8));
            details.setOpaque(false);
            details.add(Design.label("Active User Email:", 12, Design.INK));
            details.add(Design.label(email, 12, Design.PRIMARY));
            details.add(Design.label("Assigned Role:", 12, Design.INK));
            details.add(Design.label(role, 12, Design.PRIMARY));
            details.add(Design.label("Company Workspace ID:", 12, Design.INK));
            details.add(Design.label(cid, 11, Design.MUTED));
            details.add(Design.label("Staff Account UID:", 12, Design.INK));
            details.add(Design.label(uid, 11, Design.MUTED));
            card.add(details);
            card.add(Box.createVerticalStrut(14));
            card.add(Design.note("Security guarantee (NFR-SEC-001, NFR-SEC-002): Session tokens are maintained strictly in-memory. Database passwords and service keys are never stored on client machines."));

            sections.add(card);
            Design.WidthPanel width = new Design.WidthPanel(new BorderLayout());
            width.add(sections, BorderLayout.NORTH);
            JPanel p = Design.page(name, "System Settings", Design.scroll(width));
            p.setName(name);
            return p;
        }

        JPanel card = Design.card(name, "Administrator Workspace");
        card.add(Design.note("Authenticated administrator session verified. Full personnel, audit and reporting features connected."));
        card.add(Box.createVerticalStrut(18));
        JButton fleet = Design.button("Return to fleet overview", false);
        fleet.addActionListener(e -> showPage("Vehicles"));
        card.add(fleet);
        JPanel p = Design.page(name, "Authorized Administrator Access", Design.top(card));
        p.setName(name);
        return p;
    }
}
