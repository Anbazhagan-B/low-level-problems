package parkinglot.vehicle;

import java.util.List;

import parkinglot.enums.SpotType;
import parkinglot.enums.VehicleType;

public class Motorcycle extends Vehicle {
    public Motorcycle(String plate) {
        super(plate, VehicleType.MOTORCYCLE);
    }

    @Override
    public List<SpotType> getCompatibleSpotTypes() {
        return List.of(SpotType.MOTORCYCLE);
    }
}
