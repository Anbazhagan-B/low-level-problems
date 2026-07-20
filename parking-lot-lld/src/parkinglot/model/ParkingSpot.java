package parkinglot.model;

import parkinglot.enums.SpotType;
import parkinglot.vehicle.Vehicle;

/**
 * One bookable spot. Owned by the thread that claimed it from the level's
 * free-list, so a plain volatile reference (for cross-thread visibility) is
 * sufficient — no lock needed here. assign()/release() are package-private:
 * only ParkingLevel (same package) drives them.
 */
public class ParkingSpot {
    private final String id;
    private final SpotType type;
    private volatile Vehicle vehicle;

    public ParkingSpot(String id, SpotType type) {
        this.id = id;
        this.type = type;
    }

    void assign(Vehicle v) {
        this.vehicle = v;
    }

    void release() {
        this.vehicle = null;
    }

    public boolean isOccupied() {
        return vehicle != null;
    }

    public String getId() {
        return id;
    }

    public SpotType getType() {
        return type;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }
}
