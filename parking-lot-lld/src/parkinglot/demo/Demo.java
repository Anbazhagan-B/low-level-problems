package parkinglot.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import parkinglot.enums.SpotType;
import parkinglot.enums.VehicleType;
import parkinglot.exception.ParkingFullException;
import parkinglot.fee.HourlyRateStrategy;
import parkinglot.fee.ParkingFeeStrategy;
import parkinglot.lot.ParkingLot;
import parkinglot.lot.ParkingLotBuilder;
import parkinglot.model.Ticket;
import parkinglot.vehicle.Vehicle;
import parkinglot.vehicle.VehicleFactory;

/** Concurrent multi-gate demo: 8 gates race to park 40 cars into 20 compact spots. */
public class Demo {
    public static void main(String[] args) throws InterruptedException {
        Map<SpotType, Integer> layout = Map.of(
                SpotType.MOTORCYCLE, 5,
                SpotType.COMPACT, 10,
                SpotType.LARGE, 3);

        ParkingFeeStrategy fees = new HourlyRateStrategy(
                Map.of(VehicleType.MOTORCYCLE, 10.0,
                        VehicleType.CAR, 20.0,
                        VehicleType.TRUCK, 40.0),
                20.0);

        ParkingLot lot = new ParkingLotBuilder()
                .addLevel(1, layout)
                .addLevel(2, layout)
                .withFeeStrategy(fees)
                .build();

        // Simulate many gates parking at once.
        ExecutorService gates = Executors.newFixedThreadPool(8);
        List<Future<Ticket>> issued = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            final int n = i;
            issued.add(gates.submit(() -> {
                Vehicle v = VehicleFactory.create(VehicleType.CAR, "CAR-" + n);
                try {
                    return lot.parkVehicle(v);
                } catch (ParkingFullException e) {
                    return null;
                }
            }));
        }
        gates.shutdown();
        gates.awaitTermination(5, TimeUnit.SECONDS);

        List<Ticket> tickets = new ArrayList<>();
        for (Future<Ticket> f : issued) {
            try {
                Ticket t = f.get();
                if (t != null) {
                    tickets.add(t);
                }
            } catch (Exception ignored) {
                // treated as not parked
            }
        }

        System.out.println("Cars parked: " + tickets.size());          // 20 (10 compact * 2 levels)
        System.out.println("Availability (full): " + lot.availability());

        // Exit one vehicle and show the computed fee + freed spot.
        Ticket first = tickets.get(0);
        double fee = lot.exitVehicle(first.getId());
        System.out.println("\nExited " + first.getId() + " -> fee = " + fee
                + " (min 1 hour * CAR rate 20.0)");
        System.out.println("Availability (after 1 exit): " + lot.availability());
    }
}
