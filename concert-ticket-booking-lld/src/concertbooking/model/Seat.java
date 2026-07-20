package concertbooking.model;

import concertbooking.enums.SeatStatus;
import concertbooking.enums.SeatType;
import concertbooking.exception.SeatNotAvailableException;

/**
 * One bookable unit; owns its own two-state machine. The concurrency hot spot:
 * book() and release() are synchronized on the seat instance so that check-and-set
 * is one indivisible step. Threads contending on different seats never block.
 */
public class Seat {
    private final String id;
    private final String seatNumber;
    private final SeatType type;
    private final double price;
    private SeatStatus status;          // guarded by 'this'

    public Seat(String id, String seatNumber, SeatType type, double price) {
        this.id = id;
        this.seatNumber = seatNumber;
        this.type = type;
        this.price = price;
        this.status = SeatStatus.AVAILABLE;
    }

    /**
     * Atomic check-and-set. The check (is it AVAILABLE?) and the set (mark BOOKED)
     * MUST be one indivisible step — splitting them is exactly the check-then-act
     * race that causes double booking.
     */
    public synchronized void book() {
        if (status != SeatStatus.AVAILABLE) {
            throw new SeatNotAvailableException("Seat " + seatNumber + " is already booked.");
        }
        status = SeatStatus.BOOKED;
    }

    /** Used by rollback (payment failure) and by cancellation. Idempotent. */
    public synchronized void release() {
        status = SeatStatus.AVAILABLE;
    }

    public synchronized SeatStatus getStatus() {
        return status;
    }

    public String getId() {
        return id;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public SeatType getType() {
        return type;
    }

    public double getPrice() {
        return price;
    }
}
