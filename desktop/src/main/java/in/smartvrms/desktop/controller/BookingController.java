package in.smartvrms.desktop.controller;

import in.smartvrms.application.booking.DraftValidator;
import in.smartvrms.contracts.BookingConfirmationCommand;
import in.smartvrms.contracts.BookingDraft;
import in.smartvrms.contracts.BookingQuote;
import in.smartvrms.desktop.ui.BookingView;

import javax.swing.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

public final class BookingController {
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    public BookingController(BookingView view, DraftValidator validator) {
        view.check.addActionListener(e -> {
            LocalDateTime pickupLdt = parse(view.pickup.getText());
            LocalDateTime returnLdt = parse(view.expectedReturn.getText());
            BookingDraft draft = new BookingDraft(
                    view.customer.getText().strip(),
                    view.phone.getText().strip(),
                    pickupLdt,
                    returnLdt,
                    view.driver.isSelected()
            );

            var issues = validator.validate(draft);
            if (!issues.isEmpty()) {
                view.feedback.setText(String.join("\n", issues));
                return;
            }

            int age = 0;
            try {
                age = Integer.parseInt(view.ageAtVisit.getText().strip());
            } catch (NumberFormatException ignored) {}
            if (age < 18 || age > 120) {
                view.feedback.setText("Age at visit must be between 18 and 120 (FR-CUST-008).");
                return;
            }

            Instant start = pickupLdt.atZone(KOLKATA).toInstant();
            Instant end = returnLdt.atZone(KOLKATA).toInstant();

            if (view.bookingClient() != null && view.candidateVehicleId() != null) {
                view.check.setEnabled(false);
                view.feedback.setText("Fetching authoritative quote snapshot from server...");
                new SwingWorker<BookingQuote, Void>() {
                    @Override
                    protected BookingQuote doInBackground() {
                        return view.bookingClient().calculateQuote(
                                start,
                                end,
                                List.of(new BookingConfirmationCommand.PackageItem(view.candidateVehicleId(), view.driver.isSelected(), null))
                        );
                    }

                    @Override
                    protected void done() {
                        view.check.setEnabled(true);
                        try {
                            BookingQuote quote = get();
                            view.feedback.setText("Authoritative quote snapshot: " + quote.billableDays() + "d "
                                    + quote.billableRemainderHours() + "h remainder (first 24h min rule). Total: ₹"
                                    + (quote.grandTotalPaise() / 100));
                            view.showReviewWithQuote(start, end, quote);
                        } catch (Exception ex) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            view.feedback.setText("Quote request failed: " + cause.getMessage());
                        }
                    }
                }.execute();
            } else {
                view.feedback.setText("Fields are valid locally. Availability, pricing and identity are not checked. Nothing has been saved or booked.");
                view.showReview();
            }
        });

        view.clear.addActionListener(e -> {
            view.customer.setText("");
            view.phone.setText("");
            view.email.setText("");
            view.address.setText("");
            view.ageAtVisit.setText("25");
            view.pickup.setText("");
            view.expectedReturn.setText("");
            view.agreedAdvance.setText("0");
            view.deposit.setText("0");
            view.driver.setSelected(false);
            view.customerLookupNote.setText("New customer creation occurs exclusively inside this booking workflow (FR-CUST-002). Age-at-visit is verified per rental (FR-CUST-008).");
            view.clearSelection();
            view.feedback.setText("Unsaved local draft cleared. No records were changed.");
            view.customer.requestFocusInWindow();
        });
    }

    private static LocalDateTime parse(String value) {
        try {
            return LocalDateTime.parse(value.strip(), INPUT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
