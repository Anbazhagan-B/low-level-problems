package parkinglot.vehicle;

import java.util.List;

import parkinglot.enums.SpotType;
import parkinglot.enums.VehicleType;

/**
 * Abstract vehicle. Exposes a preference-ordered list of compatible spot types
 * so a size-fallback policy drops in by widening the list — no other code changes.
 */
public abstract class Vehicle {
    private final String licensePlate;
    private final VehicleType type;

    protected Vehicle(String licensePlate, VehicleType type) {
        if (licensePlate == null || licensePlate.isBlank()) {
            throw new IllegalArgumentException("licensePlate required");
        }
        this.licensePlate = licensePlate;
        this.type = type;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public VehicleType getType() {
        return type;
    }

    /**
     * Preference-ordered list of spot types this vehicle can occupy.
     * Exact-match today; to enable size fallback, just widen this list
     * (e.g. a motorcycle returning [MOTORCYCLE, COMPACT, LARGE]) — no other code changes.
     */
    public abstract List<SpotType> getCompatibleSpotTypes();
}
