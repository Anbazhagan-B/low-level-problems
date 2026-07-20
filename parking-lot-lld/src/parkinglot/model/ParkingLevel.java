package parkinglot.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import parkinglot.enums.SpotType;
import parkinglot.vehicle.Vehicle;

/**
 * Owns spot assignment and the concurrency primitive. Localizing the primitive
 * next to the state it guards is why swapping to a distributed store later
 * touches only this class.
 */
public class ParkingLevel {
    private final int floor;
    // One lock-free queue of FREE spots per type. poll() == atomically claim a spot.
    private final Map<SpotType, Queue<ParkingSpot>> freeSpots = new EnumMap<>(SpotType.class);
    // O(1) availability gauge. Eventually consistent for display; the queue is the source of truth.
    private final Map<SpotType, AtomicInteger> freeCounts = new EnumMap<>(SpotType.class);
    private final Map<String, ParkingSpot> allSpots = new ConcurrentHashMap<>();

    public ParkingLevel(int floor, Map<SpotType, Integer> layout) {
        this.floor = floor;
        for (SpotType t : SpotType.values()) {
            freeSpots.put(t, new ConcurrentLinkedQueue<>());
            freeCounts.put(t, new AtomicInteger(0));
        }
        layout.forEach((type, count) -> {
            for (int i = 0; i < count; i++) {
                ParkingSpot s = new ParkingSpot(floor + "-" + type + "-" + i, type);
                freeSpots.get(type).offer(s);
                freeCounts.get(type).incrementAndGet();
                allSpots.put(s.getId(), s);
            }
        });
    }

    /**
     * Atomically claims the first compatible free spot, honoring the vehicle's
     * preference order. The poll() is the single atomic point — two threads can
     * never receive the same spot.
     */
    public Optional<ParkingSpot> assignSpot(Vehicle v) {
        for (SpotType t : v.getCompatibleSpotTypes()) {
            ParkingSpot spot = freeSpots.get(t).poll();   // atomic claim, no lock
            if (spot != null) {
                freeCounts.get(t).decrementAndGet();
                spot.assign(v);
                return Optional.of(spot);
            }
        }
        return Optional.empty();
    }

    public void releaseSpot(ParkingSpot spot) {
        spot.release();
        freeSpots.get(spot.getType()).offer(spot);        // atomic return
        freeCounts.get(spot.getType()).incrementAndGet();
    }

    public int availableCount(SpotType t) {
        return freeCounts.get(t).get();
    }

    public Map<SpotType, Integer> snapshot() {
        Map<SpotType, Integer> m = new EnumMap<>(SpotType.class);
        freeCounts.forEach((t, c) -> m.put(t, c.get()));
        return m;
    }

    public int getFloor() {
        return floor;
    }
}
