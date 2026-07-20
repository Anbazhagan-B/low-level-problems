package parkinglot.fee;

import java.time.Instant;

import parkinglot.model.Ticket;

/** Strategy: fee rules vary independently of the parking workflow. */
public interface ParkingFeeStrategy {
    double calculateFee(Ticket ticket, Instant exitTime);
}
