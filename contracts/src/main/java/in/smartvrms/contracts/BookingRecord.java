package in.smartvrms.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Confirmed atomic booking record with full financial and assignment lineage (FR-BOOK-001, FR-BOOK-010). */
public record BookingRecord(
        UUID id,
        UUID companyId,
        UUID customerId,
        UUID visitId,
        String bookingNumber,
        String status,
        Instant startTime,
        Instant endTime,
        long baseTotalPaise,
        long driverTotalPaise,
        long taxPaise,
        long totalPaise,
        long agreedAdvancePaise,
        long actualAdvanceReceivedPaise,
        long depositPaise,
        String pricingSnapshotJson,
        List<BookingAssignmentRecord> assignments,
        UUID createdBy,
        Instant createdAt,
        long version
) {}
