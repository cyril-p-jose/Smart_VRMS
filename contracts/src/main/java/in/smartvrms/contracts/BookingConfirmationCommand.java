package in.smartvrms.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Command to confirm a booking transaction with idempotency and resource selections (FR-BOOK-001, FR-BOOK-010). */
public record BookingConfirmationCommand(
        String idempotencyKey,
        UUID existingCustomerId,
        String customerFullName,
        String customerContact,
        String customerEmail,
        String customerAddress,
        int ageAtVisit,
        String visitReason,
        Instant startTime,
        Instant endTime,
        List<PackageItem> items,
        long agreedAdvancePaise,
        long depositPaise
) {
    public record PackageItem(
            UUID vehicleId,
            boolean driverRequired,
            UUID driverId
    ) {}
}
