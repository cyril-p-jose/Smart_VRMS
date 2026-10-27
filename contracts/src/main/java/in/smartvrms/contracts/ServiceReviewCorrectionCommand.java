package in.smartvrms.contracts;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to correct recorded feedback while preserving audit history (FR-REV-002).
 */
public record ServiceReviewCorrectionCommand(
        UUID reviewId,
        int revisedRating,
        String revisedFeedback,
        String correctionReason
) {
    public ServiceReviewCorrectionCommand {
        Objects.requireNonNull(reviewId, "reviewId");
        if (revisedRating < 1 || revisedRating > 5) {
            throw new IllegalArgumentException("Revised rating must be between 1 and 5 stars");
        }
        Objects.requireNonNull(revisedFeedback, "revisedFeedback");
        if (revisedFeedback.trim().isEmpty()) {
            throw new IllegalArgumentException("Revised feedback cannot be blank");
        }
        Objects.requireNonNull(correctionReason, "correctionReason");
        if (correctionReason.trim().isEmpty()) {
            throw new IllegalArgumentException("Correction reason cannot be blank");
        }
    }
}
