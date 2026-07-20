package parkinglot.vehicle;

import parkinglot.enums.VehicleType;

/** Factory pattern: centralizes vehicle creation so callers don't switch on type everywhere. */
public final class VehicleFactory {
    private VehicleFactory() {}

    public static Vehicle create(VehicleType type, String plate) {
        switch (type) {
            case MOTORCYCLE: return new Motorcycle(plate);
            case CAR:        return new Car(plate);
            case TRUCK:      return new Truck(plate);
            default: throw new IllegalArgumentException("Unsupported vehicle type: " + type);
        }
    }
}
