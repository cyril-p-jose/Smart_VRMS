package in.smartvrms.contracts;

import java.time.LocalDateTime;

/** Unsaved input only. This is neither a reservation nor an authoritative quote. */
public record BookingDraft(String customerName, String phone, LocalDateTime pickup,
                           LocalDateTime expectedReturn, boolean driverRequired) { }
