package parkinglot.vehicle;

import java.util.List;

import parkinglot.enums.SpotType;
import parkinglot.enums.VehicleType;

public class Truck extends Vehicle {
    public Truck(String plate) {
        super(plate, VehicleType.TRUCK);
    }

    @Override
    public List<SpotType> getCompatibleSpotTypes() {
        return List.of(SpotType.LARGE);
    }
}
