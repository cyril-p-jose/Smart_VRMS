package in.smartvrms.application.booking;

import in.smartvrms.contracts.BookingDraft;
import java.util.ArrayList;
import java.util.List;

/** Shared input feedback. Authoritative availability/pricing belongs to later server use cases. */
public final class DraftValidator {
    public List<String> validate(BookingDraft draft) {
        List<String> issues = new ArrayList<>();
        if (draft.customerName() == null || draft.customerName().isBlank()) issues.add("Enter the customer's name.");
        if (draft.phone() == null || !draft.phone().matches("[+0-9 ()-]{7,24}")) issues.add("Enter a contact number (7–24 characters).");
        if (draft.pickup() == null || draft.expectedReturn() == null) issues.add("Enter pickup and return as DD/MM/YYYY HH:mm.");
        else if (!draft.expectedReturn().isAfter(draft.pickup())) issues.add("Return must be after pickup.");
        return List.copyOf(issues);
    }
}
