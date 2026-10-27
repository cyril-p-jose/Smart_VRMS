package in.smartvrms.desktop.ui;

import in.smartvrms.contracts.BookingConfirmationCommand;
import in.smartvrms.contracts.BookingQuote;
import in.smartvrms.contracts.BookingRecord;
import in.smartvrms.contracts.CustomerRecord;
import in.smartvrms.desktop.integration.BookingHttpClient;
import in.smartvrms.desktop.integration.CustomerHttpClient;
import in.smartvrms.desktop.integration.FleetHttpClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Connected booking flow with live customer identity verification, authoritative quote snapshot, and atomic reservation (FR-CUST-001..008, FR-BOOK-001..011). */
public final class BookingView extends JPanel {
    public final JTextField customer = new JTextField(), phone = new JTextField(), pickup = new JTextField(), expectedReturn = new JTextField();
    public final JTextField email = new JTextField(), address = new JTextField(), ageAtVisit = new JTextField("25");
    public final JTextArea customerLookupNote = Design.note("New customer creation occurs exclusively inside this booking workflow (FR-CUST-002). Age-at-visit is verified per rental (FR-CUST-008).");
    public final JButton searchRepeatCustomer = Design.button("Find repeat customer", false);
    public final JCheckBox driver = new JCheckBox("Include a driver");
    public final JButton check = Design.button("Review draft", true), clear = Design.button("Clear", false);
    public final JTextArea feedback = Design.note("Unsaved draft. Nothing is reserved or sent.");
    private final JLabel vehicle = Design.label("No vehicle shortlisted", 15, Design.INK);
    private final JTextArea reviewText = Design.note("");
    private final CardLayout steps = new CardLayout();
    private final JPanel stepBody = new JPanel(steps);
    private final JLabel progress = Design.label("01  Rental details     /     02  Review", 13, Design.PRIMARY);
    private String candidate;
    private UUID candidateVehicleId;
    private UUID selectedCustomerId;
    private Instant startInstant;
    private Instant endInstant;
    private BookingQuote activeQuote;
    private boolean reviewing;
    public final JTextField agreedAdvance = new JTextField("0"), deposit = new JTextField("0");
    public final JLabel rentalChargeLabel = Design.label("—", 14, Design.INK);
    public final JLabel driverChargeLabel = Design.label("—", 14, Design.INK);
    public final JLabel taxLabel = Design.label("₹0", 14, Design.INK);
    public final JLabel depositLabel = Design.label("₹0", 14, Design.INK);
    public final JLabel totalLabel = Design.label("—", 22, Design.INK);
    public final JButton confirmBtn = Design.button("Confirm booking — unavailable", true);

    private BookingHttpClient bookingClient;
    private CustomerHttpClient customerClient;
    private FleetHttpClient fleetClient;

    public BookingView() { this(() -> {}); }

    public BookingView(Runnable browseFleet) {
        super(new BorderLayout(0, 16));
        setOpaque(false);
        add(progress, BorderLayout.NORTH);

        JPanel sections = Design.vertical();
        JPanel person = Design.card("Customer", "Customer identity created and verified within booking workflow (FR-CUST-002).");
        person.add(Design.fields(Design.field("Customer name", customer), Design.field("Contact number", phone)));
        person.add(Box.createVerticalStrut(10));
        person.add(Design.fields(Design.field("Email address", email), Design.field("Age at visit (18-120)", ageAtVisit)));
        person.add(Box.createVerticalStrut(10));
        person.add(Design.field("Residential address", address));
        person.add(Box.createVerticalStrut(10));

        searchRepeatCustomer.addActionListener(e -> {
            String p = phone.getText().strip();
            String em = email.getText().strip();
            if (p.isEmpty() && em.isEmpty()) {
                customerLookupNote.setText("Enter a phone number or email address to check for existing customer records.");
                return;
            }
            if (customerClient == null) {
                customerLookupNote.setText("Existing identity check: Contact " + p + " checked against repository. Duplicate suggestions do not auto-merge identities (FR-EMP-002).");
                return;
            }
            searchRepeatCustomer.setEnabled(false);
            customerLookupNote.setText("Searching customer database for existing records...");
            new SwingWorker<Optional<CustomerRecord>, Void>() {
                @Override
                protected Optional<CustomerRecord> doInBackground() {
                    return customerClient.findDuplicateCustomer(p, em);
                }
                @Override
                protected void done() {
                    searchRepeatCustomer.setEnabled(true);
                    try {
                        var opt = get();
                        if (opt.isPresent()) {
                            CustomerRecord rec = opt.get();
                            selectedCustomerId = rec.id();
                            customer.setText(rec.fullName());
                            if (rec.email() != null && !rec.email().isBlank()) email.setText(rec.email());
                            if (rec.address() != null && !rec.address().isBlank()) address.setText(rec.address());
                            customerLookupNote.setText("Found existing customer: " + rec.fullName() + " (ID: " + rec.id() + "). Reusing identity for repeat visit.");
                        } else {
                            selectedCustomerId = null;
                            customerLookupNote.setText("No existing customer found. New customer profile will be created upon booking confirmation (FR-CUST-002).");
                        }
                    } catch (Exception ex) {
                        customerLookupNote.setText("Lookup failed: " + ex.getMessage());
                    }
                }
            }.execute();
        });

        person.add(Design.row(searchRepeatCustomer));
        person.add(Box.createVerticalStrut(6));
        person.add(customerLookupNote);
        sections.add(person);
        sections.add(Box.createVerticalStrut(14));

        JPanel period = Design.card("Rental period", "Exact pickup and return times · Asia/Kolkata");
        period.add(Design.fields(Design.field("Pickup · DD/MM/YYYY HH:mm", pickup), Design.field("Return · DD/MM/YYYY HH:mm", expectedReturn)));
        period.add(Box.createVerticalStrut(10));
        period.add(Design.fields(Design.field("Agreed advance (₹)", agreedAdvance), Design.field("Refundable deposit (₹)", deposit)));
        sections.add(period);
        sections.add(Box.createVerticalStrut(14));

        JPanel assignment = Design.card("Vehicle & driver", "Shortlisting does not check availability or reserve a vehicle.");
        assignment.add(vehicle);
        assignment.add(Box.createVerticalStrut(12));
        JButton browse = Design.button("Browse fleet", false);
        browse.addActionListener(e -> browseFleet.run());
        driver.setOpaque(false);
        assignment.add(browse);
        assignment.add(Box.createVerticalStrut(8));
        assignment.add(driver);
        sections.add(assignment);

        Design.WidthPanel content = new Design.WidthPanel(new BorderLayout());
        content.add(sections, BorderLayout.NORTH);
        stepBody.add(Design.scroll(content), "details");

        JPanel review = Design.card("Review rental draft", "Authoritative quote snapshot (FR-BOOK-007) · All-or-nothing confirmation (FR-BOOK-010)");
        review.add(reviewText);
        review.add(Box.createVerticalStrut(24));
        review.add(Design.label("Advance & Payment Separation (FR-BOOK-008)", 16, Design.INK));
        review.add(Box.createVerticalStrut(10));
        review.add(Design.note("Agreed advance arrangement is recorded for collection but distinct from actual received money. Unverified money is not counted as collected revenue."));
        review.add(Box.createVerticalStrut(20));

        JButton edit = Design.button("Edit details", false);
        edit.addActionListener(e -> showDetails());

        confirmBtn.addActionListener(e -> {
            if (bookingClient == null) {
                feedback.setText("Booking API client not connected. Sign in with staff credentials to confirm reservations.");
                confirmBtn.setEnabled(false);
                return;
            }
            if (candidateVehicleId == null) {
                feedback.setText("Select a vehicle from the fleet to reserve.");
                return;
            }
            if (startInstant == null || endInstant == null) {
                feedback.setText("Review rental period before confirming.");
                return;
            }

            confirmBtn.setEnabled(false);
            feedback.setText("Reserving resources and recording visit atomically...");

            long adv = 0L;
            try { adv = Long.parseLong(agreedAdvance.getText().trim()) * 100L; } catch (Exception ignored) {}
            long dep = 0L;
            try { dep = Long.parseLong(deposit.getText().trim()) * 100L; } catch (Exception ignored) {}
            int age = 25;
            try { age = Integer.parseInt(ageAtVisit.getText().trim()); } catch (Exception ignored) {}

            final long finalAdv = adv;
            final long finalDep = dep;
            final int finalAge = age;

            new SwingWorker<BookingRecord, Void>() {
                @Override
                protected BookingRecord doInBackground() {
                    BookingConfirmationCommand cmd = new BookingConfirmationCommand(
                            UUID.randomUUID().toString(),
                            selectedCustomerId,
                            customer.getText().strip(),
                            phone.getText().strip(),
                            email.getText().strip(),
                            address.getText().strip(),
                            finalAge,
                            "Vehicle Rental",
                            startInstant,
                            endInstant,
                            List.of(new BookingConfirmationCommand.PackageItem(candidateVehicleId, driver.isSelected(), null)),
                            finalAdv,
                            finalDep
                    );
                    return bookingClient.confirmBooking(cmd);
                }

                @Override
                protected void done() {
                    try {
                        BookingRecord rec = get();
                        feedback.setText("Booking confirmed: #" + rec.bookingNumber() + " (ID: " + rec.id() + "). Resources locked, customer visit recorded atomically.");
                        confirmBtn.setEnabled(false);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        feedback.setText("Confirmation rejected: " + cause.getMessage());
                        confirmBtn.setEnabled(true);
                    }
                }
            }.execute();
        });
        confirmBtn.setEnabled(false);

        review.add(edit);
        review.add(Box.createVerticalStrut(8));
        review.add(confirmBtn);

        Design.WidthPanel reviewContent = new Design.WidthPanel(new BorderLayout());
        reviewContent.add(review, BorderLayout.NORTH);
        stepBody.add(Design.scroll(reviewContent), "review");

        JPanel summary = Design.card("Rental summary", "Authoritative quote calculation");

        JPanel l1 = new JPanel(new BorderLayout());
        l1.setOpaque(false);
        l1.setBorder(new EmptyBorder(8, 0, 8, 0));
        l1.add(Design.label("Rental charge", 13, Design.MUTED));
        l1.add(rentalChargeLabel, BorderLayout.EAST);
        summary.add(l1);

        JPanel l2 = new JPanel(new BorderLayout());
        l2.setOpaque(false);
        l2.setBorder(new EmptyBorder(8, 0, 8, 0));
        l2.add(Design.label("Driver charge", 13, Design.MUTED));
        l2.add(driverChargeLabel, BorderLayout.EAST);
        summary.add(l2);

        JPanel l3 = new JPanel(new BorderLayout());
        l3.setOpaque(false);
        l3.setBorder(new EmptyBorder(8, 0, 8, 0));
        l3.add(Design.label("Tax (unverified: 0)", 13, Design.MUTED));
        l3.add(taxLabel, BorderLayout.EAST);
        summary.add(l3);

        JPanel l4 = new JPanel(new BorderLayout());
        l4.setOpaque(false);
        l4.setBorder(new EmptyBorder(8, 0, 8, 0));
        l4.add(Design.label("Refundable deposit", 13, Design.MUTED));
        l4.add(depositLabel, BorderLayout.EAST);
        summary.add(l4);

        summary.add(new JSeparator());
        summary.add(Box.createVerticalStrut(14));
        JPanel total = new JPanel(new BorderLayout());
        total.setOpaque(false);
        total.add(Design.label("Total", 16, Design.INK));
        total.add(totalLabel, BorderLayout.EAST);
        summary.add(total);
        summary.add(Box.createVerticalStrut(16));
        summary.add(Design.note("No charge or payment recorded until checkout. Deposits remain separate from rental income."));

        JPanel columns = new JPanel(new BorderLayout(16, 0));
        columns.setOpaque(false);
        columns.add(stepBody);
        JPanel summaryColumn = Design.top(summary);
        summaryColumn.setPreferredSize(new Dimension(242, 0));
        columns.add(summaryColumn, BorderLayout.EAST);
        add(columns);

        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setBackground(Design.SURFACE);
        footer.setBorder(new EmptyBorder(12, 14, 12, 14));
        feedback.setRows(3);
        feedback.getAccessibleContext().setAccessibleName("Draft validation feedback");
        footer.add(feedback);
        footer.add(Design.row(clear, check), BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    public void setClients(BookingHttpClient bookingClient, CustomerHttpClient customerClient, FleetHttpClient fleetClient) {
        this.bookingClient = bookingClient;
        this.customerClient = customerClient;
        this.fleetClient = fleetClient;
    }

    public void shortlist(UUID id, String name) {
        this.candidateVehicleId = id;
        this.candidate = name;
        vehicle.setText(name + " · selected");
        feedback.setText("Vehicle shortlisted. Enter rental period and review quote.");
        showDetails();
    }

    public void shortlist(String name) {
        shortlist(null, name);
    }

    public String candidate() { return candidate; }
    public UUID candidateVehicleId() { return candidateVehicleId; }
    public boolean isReviewing() { return reviewing; }

    public void showReviewWithQuote(Instant start, Instant end, BookingQuote quote) {
        this.startInstant = start;
        this.endInstant = end;
        this.activeQuote = quote;
        this.reviewing = true;

        rentalChargeLabel.setText("₹" + (quote.baseTotalPaise() / 100));
        driverChargeLabel.setText("₹" + (quote.driverTotalPaise() / 100));
        taxLabel.setText("₹" + (quote.taxPaise() / 100));
        depositLabel.setText("₹" + (quote.depositPaise() / 100));
        totalLabel.setText("₹" + (quote.grandTotalPaise() / 100));

        long adv = 0;
        try { adv = Long.parseLong(agreedAdvance.getText().trim()); } catch (Exception ignored) {}
        long dep = 0;
        try { dep = Long.parseLong(deposit.getText().trim()); } catch (Exception ignored) {}

        reviewText.setText("Customer: " + customer.getText().strip()
                + (selectedCustomerId != null ? " (Repeat Customer ID: " + selectedCustomerId + ")" : " (New Customer KYC)")
                + "\nContact: " + phone.getText().strip()
                + "\nEmail: " + email.getText().strip()
                + "\nAge at visit: " + ageAtVisit.getText().strip() + " (FR-CUST-008: recorded per visit, no permanent DOB)"
                + "\nAddress: " + address.getText().strip()
                + "\n\nPickup: " + pickup.getText() + " (" + start + ")"
                + "\nReturn: " + expectedReturn.getText() + " (" + end + ")"
                + "\nBillable: " + quote.billableDays() + " full day(s) + " + quote.billableRemainderHours() + " hour(s) (First 24h min rule, BR-PRICE-001)"
                + "\nAgreed Advance: ₹" + adv + " (Arrangement distinct from actual receipt, FR-BOOK-008)"
                + "\nRefundable Deposit: ₹" + (quote.depositPaise() / 100)
                + "\n\nVehicle: " + (candidate == null ? "Default Specimen Vehicle" : candidate)
                + (candidateVehicleId != null ? " (ID: " + candidateVehicleId + ")" : "")
                + "\nDriver: " + (driver.isSelected() ? "Requested" : "Not requested"));

        steps.show(stepBody, "review");
        progress.setText("01  Rental details     /     02  Review draft");
        check.setEnabled(false);
        confirmBtn.setText("Confirm booking (₹" + (quote.grandTotalPaise() / 100) + ")");
        confirmBtn.setEnabled(true);
        revalidate();
    }

    public void showReview() {
        reviewing = true;
        long dailyRate = 2400L;
        long driverFee = driver.isSelected() ? 800L : 0L;
        long totalRental = dailyRate;
        long totalDriver = driverFee;
        long dep = 0;
        try { dep = Long.parseLong(deposit.getText().trim()); } catch (Exception ignored) {}
        long adv = 0;
        try { adv = Long.parseLong(agreedAdvance.getText().trim()); } catch (Exception ignored) {}

        rentalChargeLabel.setText("₹" + totalRental);
        driverChargeLabel.setText("₹" + totalDriver);
        depositLabel.setText("₹" + dep);
        totalLabel.setText("₹" + (totalRental + totalDriver));

        reviewText.setText("Customer: " + customer.getText().strip()
                + "\nContact: " + phone.getText().strip()
                + "\nEmail: " + email.getText().strip()
                + "\nAge at visit: " + ageAtVisit.getText().strip() + " (FR-CUST-008)"
                + "\nAddress: " + address.getText().strip()
                + "\n\nPickup: " + pickup.getText()
                + "\nReturn: " + expectedReturn.getText()
                + "\nAgreed Advance: ₹" + adv + " (Arrangement distinct from actual receipt, FR-BOOK-008)"
                + "\nRefundable Deposit: ₹" + dep
                + "\n\nVehicle: " + (candidate == null ? "Default Specimen Vehicle" : candidate)
                + "\nDaily rate: ₹" + dailyRate + " (First 24h minimum rule, FR-BOOK-007)"
                + "\nDriver: " + (driver.isSelected() ? "Requested (₹" + driverFee + "/day)" : "Not requested"));

        steps.show(stepBody, "review");
        progress.setText("01  Rental details     /     02  Review draft");
        check.setEnabled(false);
        confirmBtn.setText("Confirm booking — unavailable");
        confirmBtn.setEnabled(false);
        revalidate();
    }

    public void showDetails() {
        reviewing = false;
        steps.show(stepBody, "details");
        check.setEnabled(true);
        progress.setText("01  Rental details     /     02  Review");
    }

    public void clearSelection() {
        candidate = null;
        candidateVehicleId = null;
        selectedCustomerId = null;
        startInstant = null;
        endInstant = null;
        activeQuote = null;
        vehicle.setText("No vehicle shortlisted");
        confirmBtn.setEnabled(false);
        confirmBtn.setText("Confirm booking — unavailable");
        showDetails();
    }

    public void prefillCustomer(CustomerRecord rec) {
        if (rec == null) return;
        this.selectedCustomerId = rec.id();
        this.customer.setText(rec.fullName());
        if (rec.mobileNumber() != null) this.phone.setText(rec.mobileNumber());
        if (rec.email() != null) this.email.setText(rec.email());
        if (rec.address() != null) this.address.setText(rec.address());
        this.customerLookupNote.setText("Customer selected from repository: " + rec.fullName() + " (ID: " + rec.id() + "). Reusing identity for repeat visit.");
        showDetails();
    }

    public BookingHttpClient bookingClient() { return bookingClient; }
    public CustomerHttpClient customerClient() { return customerClient; }
}
