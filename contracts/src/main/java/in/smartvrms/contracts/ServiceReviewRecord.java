package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service-specific customer feedback record (FR-REV-001, FR-REV-002).
 */
public record ServiceReviewRecord(
        UUID id,
        UUID companyId,
        UUID bookingId,
        UUID customerId,
        UUID visitId,
        int rating,
        String feedbackText,
        String communicationSource,
        boolean consentGiven,
        String status,
        UUID recordedBy,
        Instant recordedAt
) {
    public ServiceReviewRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(visitId, "visitId");
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5 stars");
        }
        Objects.requireNonNull(feedbackText, "feedbackText");
        Objects.requireNonNull(communicationSource, "communicationSource");
        Objects.requireNonNull(status, "status");
    }
}
