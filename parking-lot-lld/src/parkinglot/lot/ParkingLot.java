package parkinglot.lot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import parkinglot.enums.SpotType;
import parkinglot.enums.TicketStatus;
import parkinglot.exception.InvalidTicketException;
import parkinglot.exception.ParkingFullException;
import parkinglot.exception.PaymentFailedException;
import parkinglot.fee.ParkingFeeStrategy;
import parkinglot.model.ParkingLevel;
import parkinglot.model.ParkingSpot;
import parkinglot.model.Payment;
import parkinglot.model.Ticket;
import parkinglot.vehicle.Vehicle;

/**
 * Facade + orchestration. Clients call parkVehicle/exitVehicle and never touch
 * levels, spots, queues, or the fee engine. Depends on the ParkingFeeStrategy
 * abstraction, injected via constructor (DIP).
 */
public class ParkingLot {
    private final List<ParkingLevel> levels;
    private final Map<String, Ticket> activeTickets = new ConcurrentHashMap<>();
    private final ParkingFeeStrategy feeStrategy;   // injected abstraction (DIP)
    private final AtomicLong ticketSeq = new AtomicLong(1);

    public ParkingLot(List<ParkingLevel> levels, ParkingFeeStrategy feeStrategy) {
        if (levels == null || levels.isEmpty()) {
            throw new IllegalArgumentException("at least one level required");
        }
        this.levels = List.copyOf(levels);
        this.feeStrategy = Objects.requireNonNull(feeStrategy, "feeStrategy");
    }

    /** Entry: claim the first compatible spot across levels and issue a ticket. */
    public Ticket parkVehicle(Vehicle vehicle) {
        Objects.requireNonNull(vehicle, "vehicle");
        for (ParkingLevel level : levels) {
            Optional<ParkingSpot> spot = level.assignSpot(vehicle);
            if (spot.isPresent()) {
                String id = "T-" + ticketSeq.getAndIncrement();
                Ticket ticket = new Ticket(id, vehicle, spot.get(), level);
                activeTickets.put(id, ticket);
                return ticket;
            }
        }
        throw new ParkingFullException("No spot available for " + vehicle.getType());
    }

    /** Exit: price the stay, take payment, free the spot, close the ticket. Returns the fee. */
    public double exitVehicle(String ticketId) {
        Ticket ticket = activeTickets.get(ticketId);
        if (ticket == null) {
            throw new InvalidTicketException("Unknown or already-closed ticket: " + ticketId);
        }

        // Lock per-ticket so a double-scan of the same ticket can't double-free a spot or double-charge.
        synchronized (ticket) {
            if (ticket.getStatus() == TicketStatus.CLOSED) {
                throw new InvalidTicketException("Ticket already closed: " + ticketId);
            }

            Instant exit = Instant.now();
            double fee = feeStrategy.calculateFee(ticket, exit);

            Payment payment = new Payment(fee);
            if (!payment.process()) {
                throw new PaymentFailedException("Payment failed for ticket " + ticketId);
            }

            ticket.getLevel().releaseSpot(ticket.getSpot()); // thread-safe (queue offer)
            ticket.close(exit, fee, payment);
            activeTickets.remove(ticketId);
            return fee;
        }
    }

    /** Real-time availability per level and spot type (pull model). */
    public Map<Integer, Map<SpotType, Integer>> availability() {
        Map<Integer, Map<SpotType, Integer>> result = new TreeMap<>();
        for (ParkingLevel level : levels) {
            result.put(level.getFloor(), level.snapshot());
        }
        return result;
    }
}
