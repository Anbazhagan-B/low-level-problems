package parkinglot.fee;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import parkinglot.enums.VehicleType;
import parkinglot.model.Ticket;

/** Per-vehicle hourly rate, rounded up to the next hour, minimum one hour. */
public class HourlyRateStrategy implements ParkingFeeStrategy {
    private final Map<VehicleType, Double> hourlyRates;
    private final double defaultRate;

    public HourlyRateStrategy(Map<VehicleType, Double> hourlyRates, double defaultRate) {
        this.hourlyRates = Map.copyOf(hourlyRates);
        this.defaultRate = defaultRate;
    }

    @Override
    public double calculateFee(Ticket ticket, Instant exitTime) {
        long minutes = Math.max(0, Duration.between(ticket.getEntryTime(), exitTime).toMinutes());
        long hours = Math.max(1, (long) Math.ceil(minutes / 60.0)); // min 1 hour
        double rate = hourlyRates.getOrDefault(ticket.getVehicle().getType(), defaultRate);
        return hours * rate;
    }
}
