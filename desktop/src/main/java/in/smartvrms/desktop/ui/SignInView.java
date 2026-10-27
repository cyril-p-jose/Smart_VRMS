package in.smartvrms.desktop.ui;

import in.smartvrms.application.port.AuthenticationPort;
import in.smartvrms.contracts.SignInResult;
import in.smartvrms.desktop.integration.UnavailableAuthentication;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;
import java.util.function.Consumer;

public final class SignInView extends JPanel {
    public final JTextField email;
    public final JPasswordField password;
    public final JButton signIn;
    public final JButton inspect;
    public final JLabel feedback;

    public SignInView(Runnable preview) {
        this(new UnavailableAuthentication(), result -> {}, preview);
    }

    public SignInView(AuthenticationPort auth, Consumer<SignInResult> onSignedIn, Runnable preview) {
        super(new GridBagLayout());
        setBackground(Design.BACKGROUND);

        JPanel welcome = Design.vertical();
        welcome.setOpaque(true);
        welcome.setBackground(Design.NAV);
        welcome.setBorder(new EmptyBorder(38, 32, 38, 32));
        welcome.add(Design.label("smart vrms", 30, Color.WHITE));
        welcome.add(Box.createVerticalStrut(18));
        JLabel title = Design.label("A clearer rental day.", 23, Color.WHITE);
        welcome.add(title);
        welcome.add(Box.createVerticalStrut(18));
        JTextArea description = Design.note("From the first enquiry to the final return. Keep your fleet, people and rental records in one workspace.");
        description.setForeground(new Color(0xC7D1D7));
        welcome.add(description);
        welcome.add(Box.createVerticalStrut(62));
        welcome.add(Design.label("KERALA  /  STAFF WORKSPACE", 11, new Color(0xA6B7C1)));

        JPanel form = Design.card("Staff sign-in", "Use your administrator-issued account.");
        email = new JTextField();
        password = new JPasswordField();

        boolean configured = auth != null && auth.isConfigured();
        email.setEnabled(configured);
        password.setEnabled(configured);

        form.add(Design.field("Work email", email));
        form.add(Box.createVerticalStrut(14));
        form.add(Design.field("Password", password));
        form.add(Box.createVerticalStrut(10));

        feedback = Design.label(configured ? " " : " ", 12, Design.MUTED);
        form.add(feedback);
        form.add(Box.createVerticalStrut(8));

        signIn = Design.button(configured ? "Sign in" : "Sign in — not connected", true);
        signIn.setEnabled(configured);
        form.add(signIn);
        form.add(Box.createVerticalStrut(12));

        if (!configured) {
            form.add(Design.note("Account access is awaiting service setup. You can explore the interface without entering credentials."));
        } else {
            form.add(Design.note("Sign in with your verified staff email and password. Session tokens are held in-memory only."));
        }
        form.add(Box.createVerticalStrut(12));

        inspect = Design.button("Explore interface preview", false);
        inspect.addActionListener(e -> preview.run());
        form.add(inspect);

        if (configured) {
            Runnable performSignIn = () -> {
                String emailText = email.getText().strip();
                char[] passChars = password.getPassword();
                if (emailText.isEmpty() || passChars.length == 0) {
                    Arrays.fill(passChars, '\0');
                    feedback.setText("Enter your work email and password.");
                    feedback.setForeground(new Color(0xB71C1C));
                    return;
                }
                signIn.setEnabled(false);
                inspect.setEnabled(false);
                signIn.setText("Signing in...");
                email.setEnabled(false);
                password.setEnabled(false);
                feedback.setText("Connecting securely… first connection may take about a minute.");
                feedback.setForeground(Design.MUTED);

                new SwingWorker<SignInResult, Void>() {
                    @Override
                    protected SignInResult doInBackground() {
                        try {
                            auth.prepareConnection();
                            return auth.signIn(emailText, passChars);
                        } finally {
                            Arrays.fill(passChars, '\0');
                        }
                    }

                    @Override
                    protected void done() {
                        try {
                            SignInResult result = get();
                            feedback.setText("Signed in successfully.");
                            feedback.setForeground(Design.PRIMARY);
                            password.setText("");
                            signIn.setText("Sign in");
                            signIn.setEnabled(true);
                            inspect.setEnabled(true);
                            email.setEnabled(true);
                            password.setEnabled(true);
                            onSignedIn.accept(result);
                        } catch (Exception ex) {
                            String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            if (msg == null || msg.isBlank()) msg = "Invalid email or password.";
                            feedback.setText(msg);
                            feedback.setForeground(new Color(0xB71C1C));
                            signIn.setText("Sign in");
                            signIn.setEnabled(true);
                            inspect.setEnabled(true);
                            email.setEnabled(true);
                            password.setEnabled(true);
                            password.setText("");
                            password.requestFocusInWindow();
                        }
                    }
                }.execute();
            };

            signIn.addActionListener(e -> performSignIn.run());
            password.addActionListener(e -> performSignIn.run());
            email.addActionListener(e -> password.requestFocusInWindow());
        }

        JPanel center = new JPanel(new GridLayout(1, 2, 0, 0));
        center.setPreferredSize(new Dimension(850, 490));
        center.add(welcome);
        center.add(form);
        add(center);
    }
}
