package in.smartvrms.contracts;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to record customer service feedback (FR-REV-001).
 */
public record ServiceReviewCommand(
        UUID bookingId,
        int rating,
        String feedbackText,
        String communicationSource,
        boolean consentGiven
) {
    public ServiceReviewCommand {
        Objects.requireNonNull(bookingId, "bookingId");
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5 stars");
        }
        Objects.requireNonNull(feedbackText, "feedbackText");
        if (feedbackText.trim().isEmpty()) {
            throw new IllegalArgumentException("Feedback text cannot be blank");
        }
        Objects.requireNonNull(communicationSource, "communicationSource");
    }
}
