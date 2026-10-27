package in.smartvrms.desktop;

import in.smartvrms.application.port.AuthenticationPort;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import in.smartvrms.desktop.integration.HttpAuthenticationClient;
import in.smartvrms.desktop.integration.UnavailableAuthentication;
import in.smartvrms.desktop.ui.Design;
import in.smartvrms.desktop.ui.SignInView;
import in.smartvrms.desktop.ui.WorkspaceView;
import in.smartvrms.desktop.infrastructure.AppVersion;
import in.smartvrms.desktop.integration.UpdateChecker;
import in.smartvrms.contracts.SignInResult;

import javax.swing.*;
import java.awt.*;
import java.util.logging.Logger;

public final class DesktopMain {
    private static final Logger LOG = Logger.getLogger(DesktopMain.class.getName());

    private DesktopMain() { }

    public static JPanel createContent() {
        return createContent(new UnavailableAuthentication());
    }

    public static JPanel createContent(AuthenticationPort authPort) {
        return createContent(authPort,new DesktopConfig(java.util.Optional.empty()));
    }
    public static JPanel createContent(AuthenticationPort authPort,DesktopConfig config) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build views on the EDT");
        CardLayout cards = new CardLayout();
        JPanel root = new JPanel(cards);
        var activeSession = new in.smartvrms.desktop.integration.DesktopSession(authPort);
        WorkspaceView workspace = new WorkspaceView(() -> {
            SignInResult previous = activeSession.clear();
            Object currentWorkspace = root.getClientProperty("workspace");
            if (currentWorkspace instanceof WorkspaceView current) current.setSession(null);
            cards.show(root, "signin");
            if (previous != null) new SwingWorker<Void, Void>() {
                protected Void doInBackground() { authPort.logout(previous.tokens()); return null; }
            }.execute();
        });
        root.putClientProperty("workspace", workspace);
        SignInView signIn = new SignInView(authPort, result -> {
            activeSession.accept(result);
            workspace.setSession(result.session());
            if (config.apiEndpoint().isPresent()) {
                var fleetHttp = new in.smartvrms.desktop.integration.FleetHttpClient(config, activeSession);
                var personnelHttp = new in.smartvrms.desktop.integration.PersonnelHttpClient(config, activeSession);
                var driverHttp = new in.smartvrms.desktop.integration.DriverHttpClient(config, activeSession);
                var customerHttp = new in.smartvrms.desktop.integration.CustomerHttpClient(config, activeSession);
                var bookingHttp = new in.smartvrms.desktop.integration.BookingHttpClient(config, activeSession);
                var operationsHttp = new in.smartvrms.desktop.integration.OperationsHttpClient(config, activeSession);
                var financeHttp = new in.smartvrms.desktop.integration.FinanceHttpClient(config, activeSession);
                var automationHttp = new in.smartvrms.desktop.integration.AutomationHttpClient(config, activeSession);
                var reviewHttp = new in.smartvrms.desktop.integration.ReviewHttpClient(config, activeSession);
                var reportingHttp = new in.smartvrms.desktop.integration.ReportingHttpClient(config, activeSession);
                workspace.connectFleet(fleetHttp);
                workspace.connectCustomers(customerHttp);
                workspace.connectPersonnel(personnelHttp);
                workspace.connectDrivers(driverHttp, personnelHttp);
                workspace.connectBooking(bookingHttp, customerHttp, fleetHttp);
                workspace.connectOperations(operationsHttp);
                workspace.connectFinance(financeHttp);
                workspace.connectAutomationAndReports(automationHttp, reviewHttp, reportingHttp);
            }
            cards.show(root, "workspace");
        }, () -> cards.show(root, "workspace"));
        root.add(signIn, "signin");
        root.add(workspace, "workspace");
        return root;
    }

    public static void main(String[] args) {
        final DesktopConfig config;
        try { config = DesktopConfig.load(); }
        catch (IllegalArgumentException ex) { System.err.println(ex.getMessage()); System.exit(2); return; }
        final AuthenticationPort auth = config.apiEndpoint().isPresent()
                ? new HttpAuthenticationClient(config)
                : new UnavailableAuthentication();
        SwingUtilities.invokeLater(() -> {
            Design.install();
            String title = auth.isConfigured() ? "Smart VRMS • Staff workspace" : "Smart VRMS • Foundation preview";
            JFrame frame = new JFrame(title);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JPanel layout = new JPanel(new BorderLayout());
            layout.add(createContent(auth,config));
            JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            footer.add(new JLabel("Smart VRMS " + AppVersion.CURRENT + (auth.isConfigured()
                    ? " · Staff sign-in ready" : " · Demo preview")));
            JButton updates = new JButton("Check for updates");
            footer.add(updates);
            JButton connection = new JButton("Connection settings");
            footer.add(connection);
            connection.addActionListener(event -> {
                String value = JOptionPane.showInputDialog(frame, "Public HTTPS address of your Smart VRMS Java API:",
                        config.apiEndpoint().map(Object::toString).orElse(""));
                if (value == null || value.isBlank()) return;
                try {
                    DesktopConfig.saveEndpoint(DesktopConfig.fromEndpoint(value).apiEndpoint().orElseThrow());
                    JOptionPane.showMessageDialog(frame, "Connection saved. Restart Smart VRMS to use it.");
                } catch (Exception ex) { JOptionPane.showMessageDialog(frame, "Enter a valid HTTPS API address without credentials, query or fragment."); }
            });
            updates.addActionListener(event -> {
                updates.setEnabled(false);
                new SwingWorker<java.util.Optional<java.net.URI>, Void>() {
                    protected java.util.Optional<java.net.URI> doInBackground() throws Exception { return new UpdateChecker().check(); }
                    protected void done() {
                        updates.setEnabled(true);
                        try {
                            var release = get();
                            if (release.isPresent() && JOptionPane.showConfirmDialog(frame,
                                    "A newer version is available. Open its official download page?", "Update available",
                                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) Desktop.getDesktop().browse(release.get());
                            else if (release.isEmpty()) JOptionPane.showMessageDialog(frame, "No newer stable release is published.");
                        } catch (Exception ex) { JOptionPane.showMessageDialog(frame, "Could not check for updates. Try again when connected."); }
                    }
                }.execute();
            });
            layout.add(footer, BorderLayout.SOUTH);
            frame.setContentPane(layout);
            frame.setMinimumSize(new Dimension(1024, 720));
            frame.setSize(1280, 820);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            new SwingWorker<java.util.Optional<java.net.URI>, Void>() {
                protected java.util.Optional<java.net.URI> doInBackground() throws Exception { return new UpdateChecker().check(); }
                protected void done() {
                    try { if (get().isPresent()) { updates.setText("Update available — download"); updates.setToolTipText("A newer stable GitHub release is available."); } }
                    catch (Exception ignored) { /* Offline launches remain usable. */ }
                }
            }.execute();
            LOG.info(auth.isConfigured()
                    ? "Connected to API server at " + config.apiEndpoint().get()
                    : "Foundation preview opened; no service connection or authenticated session.");
        });
    }
}
