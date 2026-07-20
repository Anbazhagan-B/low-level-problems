package concertbooking.demo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import concertbooking.enums.SeatType;
import concertbooking.exception.SeatNotAvailableException;
import concertbooking.model.Booking;
import concertbooking.model.Concert;
import concertbooking.model.Seat;
import concertbooking.model.User;
import concertbooking.system.ConcertTicketBookingSystem;

/**
 * Runnable demo:
 *   1. two threads race for the SAME seat — exactly one wins,
 *   2. a happy-path multi-seat booking followed by cancellation.
 */
public class Demo {
    public static void main(String[] args) throws InterruptedException {
        ConcertTicketBookingSystem system = ConcertTicketBookingSystem.getInstance();

        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            seats.add(new Seat("S" + i, "A" + i, SeatType.VIP, 250.0));
        }

        Concert concert = new Concert("C1", "Coldplay", "DY Patil Stadium",
                LocalDateTime.of(2026, 9, 18, 19, 30), seats);
        system.addConcert(concert);

        User alice = new User("U1", "Alice", "alice@example.com");
        User bob = new User("U2", "Bob", "bob@example.com");

        // --- 1. Both race for the SAME seat A1 — exactly one must win. ---
        System.out.println("== contention: alice vs bob for seat A1 ==");
        Seat contested = concert.getSeats().get(0);
        Runnable attempt = () -> {
            User who = Thread.currentThread().getName().equals("alice") ? alice : bob;
            try {
                Booking b = system.bookTickets(who, concert, List.of(contested));
                System.out.println(Thread.currentThread().getName() + " WON booking " + b.getId());
            } catch (SeatNotAvailableException e) {
                System.out.println(Thread.currentThread().getName() + " lost: " + e.getMessage());
            }
        };
        Thread t1 = new Thread(attempt, "alice");
        Thread t2 = new Thread(attempt, "bob");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // --- 2. Happy-path multi-seat booking, then cancel. ---
        System.out.println("\n== multi-seat booking + cancel ==");
        List<Seat> pick = List.of(concert.getSeats().get(1), concert.getSeats().get(2));
        Booking booking = system.bookTickets(alice, concert, pick);
        System.out.println("booked total = " + booking.getTotalPrice()
                + ", status = " + booking.getStatus()
                + ", available now = " + concert.getAvailableSeats().size());

        system.cancelBooking(booking.getId());
        System.out.println("after cancel: status = " + booking.getStatus()
                + ", available now = " + concert.getAvailableSeats().size());
    }
}
