package concertbooking.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import concertbooking.enums.SeatStatus;

/**
 * A show: artist, venue, date/time, and its seats (composition — seats are
 * created with the concert and have no meaning outside it).
 */
public class Concert {
    private final String id;
    private final String artist;
    private final String venue;
    private final LocalDateTime dateTime;
    private final List<Seat> seats;     // composition: created with the concert

    public Concert(String id, String artist, String venue,
                   LocalDateTime dateTime, List<Seat> seats) {
        this.id = id;
        this.artist = artist;
        this.venue = venue;
        this.dateTime = dateTime;
        this.seats = List.copyOf(seats);   // defensive: list itself immutable
    }

    /**
     * Snapshot — may be stale by the time the user clicks "book".
     * Correctness is enforced in Seat.book(), never here.
     */
    public List<Seat> getAvailableSeats() {
        return seats.stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    public String getId() {
        return id;
    }

    public String getArtist() {
        return artist;
    }

    public String getVenue() {
        return venue;
    }

    public LocalDateTime getDateTime() {
        return dateTime;
    }

    public List<Seat> getSeats() {
        return seats;
    }
}
