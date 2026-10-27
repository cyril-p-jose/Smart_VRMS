package in.smartvrms.application.port;

import in.smartvrms.contracts.ServiceReviewCommand;
import in.smartvrms.contracts.ServiceReviewCorrectionCommand;
import in.smartvrms.contracts.ServiceReviewRecord;
import in.smartvrms.contracts.StaffSession;

import java.util.List;
import java.util.UUID;

/**
 * Port for service feedback, optional customer reviews, and versioned corrections (FR-REV-001, FR-REV-002).
 */
public interface ReviewPort {
    /**
     * Records service-specific feedback.
     * Service must be physically returned AND fully settled (zero debt).
     */
    ServiceReviewRecord recordReview(StaffSession session, ServiceReviewCommand command);

    /**
     * Corrects a recorded review while preserving previous version in audit history.
     */
    ServiceReviewRecord correctReview(StaffSession session, ServiceReviewCorrectionCommand command);

    /**
     * Lists reviews for a specific customer or booking.
     */
    List<ServiceReviewRecord> listReviews(StaffSession session, UUID customerId, UUID bookingId);
}
