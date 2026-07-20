package parkinglot.vehicle;

import java.util.List;

import parkinglot.enums.SpotType;
import parkinglot.enums.VehicleType;

public class Car extends Vehicle {
    public Car(String plate) {
        super(plate, VehicleType.CAR);
    }

    @Override
    public List<SpotType> getCompatibleSpotTypes() {
        return List.of(SpotType.COMPACT);
    }
}
