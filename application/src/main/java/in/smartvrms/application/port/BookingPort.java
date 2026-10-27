package in.smartvrms.application.port;

import in.smartvrms.contracts.BookingConfirmationCommand;
import in.smartvrms.contracts.BookingQuote;
import in.smartvrms.contracts.BookingRecord;
import in.smartvrms.contracts.RatePlanRecord;
import in.smartvrms.contracts.StaffSession;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port for authoritative booking pricing, availability, and atomic confirmation (FR-BOOK-001 through FR-BOOK-010). */
public interface BookingPort {

    BookingQuote calculateQuote(UUID companyId, Instant startTime, Instant endTime, List<BookingConfirmationCommand.PackageItem> items);

    boolean isVehicleAvailable(UUID companyId, UUID vehicleId, Instant startTime, Instant endTime);

    boolean isDriverAvailable(UUID companyId, UUID driverId, Instant startTime, Instant endTime);

    BookingRecord confirmBooking(StaffSession session, BookingConfirmationCommand command);

    BookingRecord getBookingById(UUID companyId, UUID bookingId);

    List<BookingRecord> listBookings(UUID companyId, int limit, int offset);

    RatePlanRecord configureRatePlan(StaffSession session, RatePlanRecord ratePlan);

    RatePlanRecord getRatePlanForVehicle(UUID companyId, UUID vehicleId);
}
