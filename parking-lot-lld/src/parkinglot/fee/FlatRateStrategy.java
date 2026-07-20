package parkinglot.fee;

import java.time.Instant;

import parkinglot.model.Ticket;

/** Flat fee regardless of duration. */
public class FlatRateStrategy implements ParkingFeeStrategy {
    private final double flatFee;

    public FlatRateStrategy(double flatFee) {
        this.flatFee = flatFee;
    }

    @Override
    public double calculateFee(Ticket ticket, Instant exitTime) {
        return flatFee;
    }
}
