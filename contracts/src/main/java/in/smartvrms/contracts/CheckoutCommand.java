package in.smartvrms.contracts;

import java.time.Instant;
import java.util.UUID;

/** Command to record actual physical vehicle checkout (FR-OPS-002). */
public record CheckoutCommand(
        UUID assignmentId,
        Instant actualTime,
        int odometer,
        int fuelPercent,
        String notes
) {}
