package concertbooking.enums;

/** Booking-level lifecycle: PENDING between seat capture and payment, then CONFIRMED or CANCELLED. */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
