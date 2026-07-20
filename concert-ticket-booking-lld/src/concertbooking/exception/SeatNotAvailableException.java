package concertbooking.exception;

/** Thrown by Seat.book() when a seat is not AVAILABLE; the facade compensates and rethrows. */
public class SeatNotAvailableException extends RuntimeException {
    public SeatNotAvailableException(String msg) {
        super(msg);
    }
}
