package concertbooking.model;

import java.util.List;

import concertbooking.enums.BookingStatus;

/**
 * Immutable-ish record of who booked which seats of which concert, with a
 * status lifecycle. References user/concert/seats but owns none of them.
 *
 * confirm()/cancel() drive the lifecycle and are intended to be called only by
 * the system facade. (In the single-package version of this design they are
 * package-private; split across packages here, they are public — treat them as
 * facade-driven and do not call from client code.)
 */
public class Booking {
    private final String id;
    private final User user;
    private final Concert concert;
    private final List<Seat> seats;
    private final double totalPrice;
    private volatile BookingStatus status;   // visibility across threads

    public Booking(String id, User user, Concert concert, List<Seat> seats) {
        this.id = id;
        this.user = user;
        this.concert = concert;
        this.seats = List.copyOf(seats);
        this.totalPrice = seats.stream().mapToDouble(Seat::getPrice).sum();
        this.status = BookingStatus.PENDING;
    }

    /** Facade-driven: PENDING -> CONFIRMED. */
    public void confirm() {
        if (status != BookingStatus.PENDING) {
            throw new IllegalStateException("Only PENDING bookings can be confirmed.");
        }
        status = BookingStatus.CONFIRMED;
    }

    /** Facade-driven: CONFIRMED -> CANCELLED, releasing seats back on sale. */
    public void cancel() {
        if (status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Only CONFIRMED bookings can be cancelled.");
        }
        status = BookingStatus.CANCELLED;
        seats.forEach(Seat::release);   // seats go back on sale
    }

    public String getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Concert getConcert() {
        return concert;
    }

    public List<Seat> getSeats() {
        return seats;
    }

    public double getTotalPrice() {
        return totalPrice;
    }

    public BookingStatus getStatus() {
        return status;
    }
}
