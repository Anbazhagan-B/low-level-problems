package parkinglot.model;

import java.time.Instant;

import parkinglot.enums.TicketStatus;
import parkinglot.vehicle.Vehicle;

/**
 * Records who/where for a stay, plus its lifecycle and payment.
 *
 * close() drives the lifecycle and is intended to be called only by the
 * ParkingLot facade. (Package-private in the single-package version of this
 * design; split across packages here it is public — treat it as facade-driven.)
 */
public class Ticket {
    private final String id;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final ParkingLevel level;
    private final Instant entryTime;

    private Instant exitTime;
    private double fee;
    private Payment payment;
    private TicketStatus status;

    public Ticket(String id, Vehicle vehicle, ParkingSpot spot, ParkingLevel level) {
        this.id = id;
        this.vehicle = vehicle;
        this.spot = spot;
        this.level = level;
        this.entryTime = Instant.now();
        this.status = TicketStatus.ACTIVE;
    }

    /** Facade-driven: ACTIVE -> CLOSED, recording exit time, fee, and payment. */
    public void close(Instant exitTime, double fee, Payment payment) {
        this.exitTime = exitTime;
        this.fee = fee;
        this.payment = payment;
        this.status = TicketStatus.CLOSED;
    }

    public String getId() {
        return id;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public ParkingSpot getSpot() {
        return spot;
    }

    public ParkingLevel getLevel() {
        return level;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public Instant getExitTime() {
        return exitTime;
    }

    public double getFee() {
        return fee;
    }

    public Payment getPayment() {
        return payment;
    }

    public TicketStatus getStatus() {
        return status;
    }
}
