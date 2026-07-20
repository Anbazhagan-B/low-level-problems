package concertbooking.system;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import concertbooking.exception.PaymentFailedException;
import concertbooking.exception.SeatNotAvailableException;
import concertbooking.model.Booking;
import concertbooking.model.Concert;
import concertbooking.model.Seat;
import concertbooking.model.User;
import concertbooking.notification.EmailNotifier;
import concertbooking.notification.NotificationService;
import concertbooking.payment.CreditCardProcessor;
import concertbooking.payment.PaymentProcessor;

/**
 * Singleton facade: the single authoritative registry of seat state and the
 * owner of the booking workflow. Implemented with the initialization-on-demand
 * holder idiom (lazy, thread-safe, no locking on the getInstance() hot path).
 */
public class ConcertTicketBookingSystem {

    // Holder idiom: JVM class-loading guarantees thread-safe lazy init.
    private static class Holder {
        private static final ConcertTicketBookingSystem INSTANCE =
                new ConcertTicketBookingSystem(new CreditCardProcessor(), new EmailNotifier());
    }

    private final Map<String, Concert> concerts = new ConcurrentHashMap<>();
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final PaymentProcessor paymentProcessor;   // DIP: injected
    private final NotificationService notifier;

    private ConcertTicketBookingSystem(PaymentProcessor p, NotificationService n) {
        this.paymentProcessor = p;
        this.notifier = n;
    }

    public static ConcertTicketBookingSystem getInstance() {
        return Holder.INSTANCE;
    }

    public void addConcert(Concert concert) {
        concerts.put(concert.getId(), concert);
    }

    public Concert getConcert(String concertId) {
        Concert c = concerts.get(concertId);
        if (c == null) {
            throw new IllegalArgumentException("Unknown concert: " + concertId);
        }
        return c;
    }

    /** Null criteria are ignored; all supplied criteria must match (AND). */
    public List<Concert> searchConcerts(String artist, String venue, LocalDateTime dateTime) {
        return concerts.values().stream()
                .filter(c -> artist == null || c.getArtist().equalsIgnoreCase(artist))
                .filter(c -> venue == null || c.getVenue().equalsIgnoreCase(venue))
                .filter(c -> dateTime == null || c.getDateTime().equals(dateTime))
                .collect(Collectors.toList());
    }

    /**
     * Atomic multi-seat booking via capture-with-compensation:
     *   1) capture each seat independently (per-seat atomic book())
     *   2) any failure -> release everything captured so far, abort
     *   3) payment -> failure also releases everything
     *   4) confirm + notify
     * No thread ever holds two seat locks simultaneously => no deadlock.
     */
    public Booking bookTickets(User user, Concert concert, List<Seat> seats) {
        if (user == null || concert == null || seats == null || seats.isEmpty()) {
            throw new IllegalArgumentException("User, concert and seats are required.");
        }

        List<Seat> captured = new ArrayList<>();
        try {
            for (Seat seat : seats) {
                seat.book();              // throws if not AVAILABLE
                captured.add(seat);
            }
        } catch (SeatNotAvailableException e) {
            captured.forEach(Seat::release);   // compensation: all-or-nothing
            throw e;
        }

        Booking booking = new Booking(UUID.randomUUID().toString(), user, concert, seats);

        if (!paymentProcessor.process(booking.getTotalPrice())) {
            seats.forEach(Seat::release);      // payment failed: free the seats
            throw new PaymentFailedException("Payment of " + booking.getTotalPrice() + " failed.");
        }

        booking.confirm();
        bookings.put(booking.getId(), booking);
        notifier.notify(user, "Booking " + booking.getId() + " confirmed: "
                + seats.size() + " seat(s) for " + concert.getArtist()
                + " at " + concert.getVenue());
        return booking;
    }

    public void cancelBooking(String bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null) {
            throw new IllegalArgumentException("Unknown booking: " + bookingId);
        }
        booking.cancel();                       // releases seats internally
        notifier.notify(booking.getUser(), "Booking " + bookingId + " cancelled.");
    }
}
