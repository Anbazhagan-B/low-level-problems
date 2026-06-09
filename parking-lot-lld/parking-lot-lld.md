# Low-Level Design: Parking Lot System (with Fee Model)

A complete, interview-ready object-oriented design and reference Java implementation.
Scope includes multi-level layout, typed spots, concurrent multi-gate access, ticketing,
and a pluggable fee model.

---

## 1. Requirements

### Functional
- Parking lot has **multiple levels**; each level has a fixed set of **typed spots**.
- Supports multiple **vehicle types**: motorcycle, car, truck (extensible).
- Each **spot has a type** and accepts compatible vehicles.
- **Assign** a spot on entry; **release** it on exit.
- **Ticketing**: issue a ticket on entry, close it on exit and compute the **fee**.
- **Payment** must complete before exit is finalized.
- Track **real-time availability** and expose it to customers.
- Multiple **entry/exit gates** operating **concurrently**.

### Non-Functional
- **Correctness under concurrency**: no two vehicles ever get the same spot (the core challenge).
- **Extensibility (OCP)**: new vehicle types, spot types, and fee policies without editing existing code.
- **Cheap availability reads**: O(1)-ish, no full scans on every query.
- **Scale**: thousands of spots, dozens of gates, single JVM in-memory (distributed notes at the end).

### Assumptions
1. Spot matching is **exact-type** (motorcycle→motorcycle, car→compact, truck→large), but vehicles expose a
   *preference-ordered list* of compatible spot types, so a size-fallback policy drops in without rework.
2. **FCFS**, no pre-reservation.
3. **Single JVM**, in-memory state, thread-safe via `java.util.concurrent`.
4. Spot assignment is **strongly consistent** (exactly-once); the availability *counter* is an O(1) gauge that is
   eventually consistent for display — the queue is the source of truth for assignment.
5. Fee model in scope; payment is simulated (a real gateway slots behind the `Payment` abstraction).

---

## 2. Entity Model

### Enums (closed value sets)
| Enum | Values | Why an enum |
|---|---|---|
| `VehicleType` | MOTORCYCLE, CAR, TRUCK | Fixed, exhaustive vehicle categories |
| `SpotType` | MOTORCYCLE, COMPACT, LARGE | Physical spot sizes |
| `TicketStatus` | ACTIVE, PAID, CLOSED | Lifecycle of a stay |
| `PaymentStatus` | PENDING, COMPLETED, FAILED | Payment lifecycle |

### Core classes and relationships
| From | To | Relationship | Why |
|---|---|---|---|
| `ParkingLot` | `ParkingLevel` | **Composition** | Levels have no meaning outside their lot; they die with it |
| `ParkingLevel` | `ParkingSpot` | **Composition** | Spots are created and owned by the level |
| `ParkingSpot` | `Vehicle` | **Association** | A spot *references* the vehicle currently in it; the vehicle outlives the parking event |
| `Ticket` | `Vehicle`, `ParkingSpot`, `ParkingLevel` | **Association** | A ticket records who/where, but owns none of them |
| `Ticket` | `Payment` | **Composition** | A payment is created for and belongs to one ticket |
| `ParkingLot` | `ParkingFeeStrategy` | **Aggregation / Dependency** | The lot is *configured with* a fee policy it doesn't own; swappable |
| `ParkingLot` | `Ticket` | **Aggregation** | Active tickets are held while a vehicle is parked, then handed off/archived |

**Where the logic lives (the two decisions interviewers probe):**
- **Spot assignment** lives in `ParkingLevel` (it owns its spots and their free-lists). `ParkingLot` is a *facade*
  that iterates levels — it does not know how a level picks a spot. This keeps the concurrency primitive local to
  the data it guards.
- **Fee calculation** lives in a **`ParkingFeeStrategy`**, injected into `ParkingLot`. The lot orchestrates exit
  (free spot → compute fee → take payment → close ticket) but delegates the *how much* to the strategy.

---

## 3. Class Diagram (text/UML)

```
                         «facade, singleton-scoped»
                              ParkingLot
        - levels: List<ParkingLevel>            (composition 1..*)
        - activeTickets: Map<String,Ticket>     (aggregation 0..*)
        - feeStrategy: ParkingFeeStrategy        (aggregation 1)
        - ticketSeq: AtomicLong
        + parkVehicle(Vehicle): Ticket
        + exitVehicle(ticketId): double
        + availability(): Map<Integer,Map<SpotType,Integer>>
                 |                         |
      composition|                         | uses
                 v                         v
            ParkingLevel            «interface» ParkingFeeStrategy
   - floor: int                      + calculateFee(Ticket, Instant): double
   - freeSpots: Map<SpotType,Queue<ParkingSpot>>        ^      ^
   - freeCounts: Map<SpotType,AtomicInteger>            |      |
   - allSpots: Map<String,ParkingSpot>          HourlyRate  FlatRate
   + assignSpot(Vehicle): Optional<ParkingSpot>  Strategy   Strategy
   + releaseSpot(ParkingSpot): void
   + availableCount(SpotType): int
                 |
      composition| 1..*
                 v
            ParkingSpot
   - id: String
   - type: SpotType
   - vehicle: volatile Vehicle      ---assoc--->  «abstract» Vehicle
   + assign(Vehicle) / release()                  - licensePlate: String
   + isOccupied(): boolean                        - type: VehicleType
                                                  + getCompatibleSpotTypes(): List<SpotType>
                                                        ^
                                          +-------------+-------------+
                                       Motorcycle      Car         Truck

            Ticket  ---assoc--->  Vehicle, ParkingSpot, ParkingLevel
   - id, entryTime, exitTime, fee, status
   - payment: Payment            (composition)
   + close(exit, fee, Payment)
```

---

## 4. Design Patterns & SOLID

**Patterns and *why* each fits:**
- **Strategy** — `ParkingFeeStrategy`. Fee rules vary independently of the parking workflow (hourly, flat,
  tiered-by-vehicle, surge pricing). Strategy lets us swap the algorithm at runtime/config time without touching
  `ParkingLot`. This is the textbook case: a family of interchangeable algorithms behind one interface.
- **Facade** — `ParkingLot`. Clients (gate controllers) call `parkVehicle`/`exitVehicle` and never touch levels,
  spots, queues, or the fee engine. The complexity is hidden behind a small surface.
- **Factory** — `VehicleFactory`. Gate hardware reports a type string; the factory centralizes vehicle creation so
  callers don't `switch` on type everywhere (and adding a type touches one place).
- **Singleton (scoped, via DI)** — there is one `ParkingLot`. I implement it as a normal object and let the
  container manage its lifetime (Spring `@Component` is singleton-scoped by default). Classic `getInstance()` is
  shown but discouraged: it's a global that fights unit testing and makes the fee strategy hard to inject/mock.
- **Builder** (optional) — `ParkingLotBuilder` for readable construction of the level/spot layout.
- **Observer** (extension, not built) — real-time *push* to display boards. The core uses a *pull* `availability()`
  query; Observer is the right move if boards must update reactively (covered in follow-ups).

**SOLID mapping:**
- **S** — Each class has one reason to change: `ParkingSpot` tracks occupancy, `ParkingLevel` manages its spots,
  `Ticket` holds stay data, `ParkingFeeStrategy` prices, `Payment` settles.
- **O** — New vehicle type = new `Vehicle` subclass; new pricing = new strategy. No edits to `ParkingLot`.
- **L** — Any `Vehicle` subclass is substitutable wherever `Vehicle` is expected.
- **I** — `ParkingFeeStrategy` is a single-method, focused interface; no fat contracts.
- **D** — `ParkingLot` depends on the `ParkingFeeStrategy` abstraction, injected via constructor, not a concrete class.

---

## 5. Implementation

### Enums

```java
public enum VehicleType { MOTORCYCLE, CAR, TRUCK }

public enum SpotType { MOTORCYCLE, COMPACT, LARGE }

public enum TicketStatus { ACTIVE, PAID, CLOSED }

public enum PaymentStatus { PENDING, COMPLETED, FAILED }
```

### Vehicle hierarchy

```java
import java.util.List;

public abstract class Vehicle {
    private final String licensePlate;
    private final VehicleType type;

    protected Vehicle(String licensePlate, VehicleType type) {
        if (licensePlate == null || licensePlate.isBlank())
            throw new IllegalArgumentException("licensePlate required");
        this.licensePlate = licensePlate;
        this.type = type;
    }

    public String getLicensePlate() { return licensePlate; }
    public VehicleType getType() { return type; }

    /**
     * Preference-ordered list of spot types this vehicle can occupy.
     * Exact-match today; to enable size fallback, just widen this list
     * (e.g. a motorcycle returning [MOTORCYCLE, COMPACT, LARGE]) — no other code changes.
     */
    public abstract List<SpotType> getCompatibleSpotTypes();
}

public class Motorcycle extends Vehicle {
    public Motorcycle(String plate) { super(plate, VehicleType.MOTORCYCLE); }
    @Override public List<SpotType> getCompatibleSpotTypes() { return List.of(SpotType.MOTORCYCLE); }
}

public class Car extends Vehicle {
    public Car(String plate) { super(plate, VehicleType.CAR); }
    @Override public List<SpotType> getCompatibleSpotTypes() { return List.of(SpotType.COMPACT); }
}

public class Truck extends Vehicle {
    public Truck(String plate) { super(plate, VehicleType.TRUCK); }
    @Override public List<SpotType> getCompatibleSpotTypes() { return List.of(SpotType.LARGE); }
}
```

### VehicleFactory (Factory pattern)

```java
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
```

### ParkingSpot

```java
public class ParkingSpot {
    private final String id;
    private final SpotType type;
    // Owned by the thread that claimed it from the level's free-list, so a plain
    // volatile reference (for cross-thread visibility) is sufficient — no lock needed here.
    private volatile Vehicle vehicle;

    public ParkingSpot(String id, SpotType type) {
        this.id = id;
        this.type = type;
    }

    void assign(Vehicle v) { this.vehicle = v; }
    void release()         { this.vehicle = null; }

    public boolean isOccupied() { return vehicle != null; }
    public String getId()       { return id; }
    public SpotType getType()   { return type; }
    public Vehicle getVehicle() { return vehicle; }
}
```

### ParkingLevel — owns assignment & the concurrency primitive

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

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
     * Atomically claims the first compatible free spot, honoring the vehicle's preference order.
     * The poll() is the single atomic point — two threads can never receive the same spot.
     */
    public Optional<ParkingSpot> assignSpot(Vehicle v) {
        for (SpotType t : v.getCompatibleSpotTypes()) {
            ParkingSpot spot = freeSpots.get(t).poll();   // <-- atomic claim, no lock
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
        freeSpots.get(spot.getType()).offer(spot);        // <-- atomic return
        freeCounts.get(spot.getType()).incrementAndGet();
    }

    public int availableCount(SpotType t) { return freeCounts.get(t).get(); }

    public Map<SpotType, Integer> snapshot() {
        Map<SpotType, Integer> m = new EnumMap<>(SpotType.class);
        freeCounts.forEach((t, c) -> m.put(t, c.get()));
        return m;
    }

    public int getFloor() { return floor; }
}
```

### Ticket

```java
import java.time.Instant;

public class Ticket {
    private final String id;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final ParkingLevel level;
    private final Instant entryTime;

    private Instant exitTime;
    private double fee;
    private Payment payment;
    private TicketStatus status;

    public Ticket(String id, Vehicle vehicle, ParkingSpot spot, ParkingLevel level) {
        this.id = id;
        this.vehicle = vehicle;
        this.spot = spot;
        this.level = level;
        this.entryTime = Instant.now();
        this.status = TicketStatus.ACTIVE;
    }

    void close(Instant exitTime, double fee, Payment payment) {
        this.exitTime = exitTime;
        this.fee = fee;
        this.payment = payment;
        this.status = TicketStatus.CLOSED;
    }

    public String getId()            { return id; }
    public Vehicle getVehicle()      { return vehicle; }
    public ParkingSpot getSpot()     { return spot; }
    public ParkingLevel getLevel()   { return level; }
    public Instant getEntryTime()    { return entryTime; }
    public Instant getExitTime()     { return exitTime; }
    public double getFee()           { return fee; }
    public TicketStatus getStatus()  { return status; }
}
```

### Fee model (Strategy pattern)

```java
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public interface ParkingFeeStrategy {
    double calculateFee(Ticket ticket, Instant exitTime);
}

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

/** Flat fee regardless of duration. */
public class FlatRateStrategy implements ParkingFeeStrategy {
    private final double flatFee;
    public FlatRateStrategy(double flatFee) { this.flatFee = flatFee; }

    @Override
    public double calculateFee(Ticket ticket, Instant exitTime) { return flatFee; }
}
```

### Payment

```java
public class Payment {
    private final double amount;
    private PaymentStatus status;

    public Payment(double amount) {
        if (amount < 0) throw new IllegalArgumentException("amount must be >= 0");
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    /** Replace the body with a real gateway call; returns true on success. */
    public boolean process() {
        this.status = PaymentStatus.COMPLETED; // simulated
        return true;
    }

    public PaymentStatus getStatus() { return status; }
    public double getAmount()        { return amount; }
}
```

### Custom exceptions

```java
public class ParkingException extends RuntimeException {
    public ParkingException(String msg) { super(msg); }
}
public class ParkingFullException extends ParkingException {
    public ParkingFullException(String msg) { super(msg); }
}
public class InvalidTicketException extends ParkingException {
    public InvalidTicketException(String msg) { super(msg); }
}
public class PaymentFailedException extends ParkingException {
    public PaymentFailedException(String msg) { super(msg); }
}
```

### ParkingLot — facade + orchestration

```java
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ParkingLot {
    private final List<ParkingLevel> levels;
    private final Map<String, Ticket> activeTickets = new ConcurrentHashMap<>();
    private final ParkingFeeStrategy feeStrategy;   // injected abstraction (DIP)
    private final AtomicLong ticketSeq = new AtomicLong(1);

    public ParkingLot(List<ParkingLevel> levels, ParkingFeeStrategy feeStrategy) {
        if (levels == null || levels.isEmpty())
            throw new IllegalArgumentException("at least one level required");
        this.levels = List.copyOf(levels);
        this.feeStrategy = Objects.requireNonNull(feeStrategy, "feeStrategy");
    }

    /** Entry: claim the first compatible spot across levels and issue a ticket. */
    public Ticket parkVehicle(Vehicle vehicle) {
        Objects.requireNonNull(vehicle, "vehicle");
        for (ParkingLevel level : levels) {
            Optional<ParkingSpot> spot = level.assignSpot(vehicle);
            if (spot.isPresent()) {
                String id = "T-" + ticketSeq.getAndIncrement();
                Ticket ticket = new Ticket(id, vehicle, spot.get(), level);
                activeTickets.put(id, ticket);
                return ticket;
            }
        }
        throw new ParkingFullException("No spot available for " + vehicle.getType());
    }

    /** Exit: price the stay, take payment, free the spot, close the ticket. Returns the fee. */
    public double exitVehicle(String ticketId) {
        Ticket ticket = activeTickets.get(ticketId);
        if (ticket == null)
            throw new InvalidTicketException("Unknown or already-closed ticket: " + ticketId);

        // Lock per-ticket so a double-scan of the same ticket can't double-free a spot or double-charge.
        synchronized (ticket) {
            if (ticket.getStatus() == TicketStatus.CLOSED)
                throw new InvalidTicketException("Ticket already closed: " + ticketId);

            Instant exit = Instant.now();
            double fee = feeStrategy.calculateFee(ticket, exit);

            Payment payment = new Payment(fee);
            if (!payment.process())
                throw new PaymentFailedException("Payment failed for ticket " + ticketId);

            ticket.getLevel().releaseSpot(ticket.getSpot()); // thread-safe (queue offer)
            ticket.close(exit, fee, payment);
            activeTickets.remove(ticketId);
            return fee;
        }
    }

    /** Real-time availability per level and spot type (pull model). */
    public Map<Integer, Map<SpotType, Integer>> availability() {
        Map<Integer, Map<SpotType, Integer>> result = new TreeMap<>();
        for (ParkingLevel level : levels) result.put(level.getFloor(), level.snapshot());
        return result;
    }
}
```

### Builder (optional, readable setup)

```java
import java.util.*;

public class ParkingLotBuilder {
    private final List<ParkingLevel> levels = new ArrayList<>();
    private ParkingFeeStrategy feeStrategy;

    public ParkingLotBuilder addLevel(int floor, Map<SpotType, Integer> layout) {
        levels.add(new ParkingLevel(floor, layout));
        return this;
    }

    public ParkingLotBuilder withFeeStrategy(ParkingFeeStrategy strategy) {
        this.feeStrategy = strategy;
        return this;
    }

    public ParkingLot build() {
        return new ParkingLot(levels, feeStrategy);
    }
}
```

> **Spring Boot note:** in a Spring app you'd drop the builder/singleton bookkeeping. `ParkingLot`,
> the chosen `ParkingFeeStrategy`, and a `VehicleFactory` become `@Component`/`@Bean`s (singleton scope by
> default), and the strategy is selected via `@Qualifier`/`@ConditionalOnProperty` or a config bean — the
> constructor injection above is exactly what `@Autowired` would wire.

---

## 6. Concurrency Analysis

**Shared mutable state**
1. The per-type free-spot lists on each level.
2. The per-type availability counters.
3. The `activeTickets` map.
4. Each ticket's `status`.

**Critical sections and the primitive chosen for each**
- *Claiming/returning a spot* → `ConcurrentLinkedQueue.poll()/offer()`. These are lock-free (CAS-based)
  and **atomic**: a polled spot is handed to exactly one thread. This is why two gates can never receive the
  same spot, and why concurrent parks into *different* spots proceed in parallel with no contention.
- *Availability counters* → `AtomicInteger`. Read in O(1). They can momentarily lag the queue (the poll and the
  decrement aren't one atomic step), so they are treated as an **eventually-consistent display gauge** — never as
  the assignment authority. State this trade-off explicitly in an interview.
- *Ticket registry* → `ConcurrentHashMap`. Safe concurrent put/get/remove.
- *Closing a ticket* → `synchronized (ticket)`. Guarantees exit is idempotent: a second exit on a closed ticket
  fails fast instead of double-freeing the spot or double-charging.

**Why it's free of the classic hazards**
- **Race conditions**: the only contended hand-off (the spot) goes through a single atomic queue operation; no
  read-modify-write on shared state outside that.
- **Deadlock**: no thread ever holds two locks at once. The only `synchronized` block (on a ticket) acquires one
  monitor and calls queue operations that take no application locks → no lock-ordering cycle possible.
- **Livelock**: lock-free queue ops make progress; no spin-retry loops in app code.

---

## 7. Exception Handling & Edge Cases

| Case | Handling |
|---|---|
| Null/blank plate or null vehicle | `IllegalArgumentException` / `NullPointerException` at construction/entry |
| No compatible spot anywhere | `ParkingFullException` from `parkVehicle` |
| Unknown / already-closed ticket | `InvalidTicketException` from `exitVehicle` |
| Double exit (same ticket, two gates) | `synchronized(ticket)` + status check → second call throws, no double-free |
| Negative fee | guarded in `Payment` constructor |
| Payment fails | `PaymentFailedException`; spot is **not** freed and ticket stays ACTIVE (no partial state) |
| Zero/near-zero stay | minimum one-hour billing in `HourlyRateStrategy` |
| Lot empty at startup | builder requires ≥1 level; empty layout simply yields no free spots |
| Spot freed twice | release is idempotent on the spot, but the per-ticket lock prevents the second release from ever running |

**Graceful degradation:** if the availability counter drifts under heavy load, displays may briefly under/over-count
by a handful, but assignment correctness is unaffected because the queue is authoritative.

---

## 8. Demo (concurrent multi-gate)

```java
import java.util.*;
import java.util.concurrent.*;

public class Demo {
    public static void main(String[] args) throws InterruptedException {
        Map<SpotType, Integer> layout = Map.of(
            SpotType.MOTORCYCLE, 5,
            SpotType.COMPACT,    10,
            SpotType.LARGE,      3
        );

        ParkingFeeStrategy fees = new HourlyRateStrategy(
            Map.of(VehicleType.MOTORCYCLE, 10.0,
                   VehicleType.CAR,        20.0,
                   VehicleType.TRUCK,      40.0),
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
                try { return lot.parkVehicle(v); }
                catch (ParkingFullException e) { return null; }
            }));
        }
        gates.shutdown();
        gates.awaitTermination(5, TimeUnit.SECONDS);

        long parked = issued.stream().filter(f -> {
            try { return f.get() != null; } catch (Exception e) { return false; }
        }).count();

        System.out.println("Cars parked: " + parked);          // 20 (10 compact * 2 levels)
        System.out.println("Availability: " + lot.availability());
    }
}
```

---

## 9. Likely Follow-Ups (with model answers)

1. **"Allow a car to use a larger spot when compacts are full."**
   Widen `Car.getCompatibleSpotTypes()` to `[COMPACT, LARGE]`. `assignSpot` already iterates the list in preference
   order, so no other code changes — this is exactly why the preference-list design was chosen over a hardcoded map.

2. **"Make availability push to digital boards in real time."**
   Add an **Observer**: `ParkingLevel` becomes the subject, display boards subscribe, and `assignSpot`/`releaseSpot`
   publish an availability event. Keep the pull `availability()` for APIs; the two coexist.

3. **"Scale 10x / go distributed across multiple JVMs."**
   In-memory queues no longer suffice. Move spot inventory to a store with atomic operations — e.g. Redis lists
   (`LPOP`/`RPUSH` mirror poll/offer) or a DB row with `SELECT ... FOR UPDATE` / optimistic version column. Shard by
   level. The class design is unchanged; only `ParkingLevel`'s claim/release implementation swaps. This is the payoff
   of localizing the concurrency primitive inside the level.

4. **"Add surge pricing / weekend rates."**
   New `ParkingFeeStrategy` (e.g. `SurgePricingStrategy` wrapping a base strategy, or a `TimeOfDayStrategy`).
   Inject it; `ParkingLot` is untouched. Strategy + composition.

5. **"Two trucks race for the last large spot — prove only one wins."**
   Both call `freeSpots.get(LARGE).poll()`. `ConcurrentLinkedQueue.poll()` is atomic, so exactly one thread gets the
   node and the other gets `null` → `ParkingFullException`. No lock, no double-assignment.

---

## 10. Transferable Lessons

- **Localize the concurrency primitive next to the state it guards** (claim/release inside `ParkingLevel`). This is
  why swapping to a distributed store later touches one class. You'll reuse this in Vending Machine, Elevator, and
  every concurrency problem in the set.
- **Strategy for "the rules that vary"** (fees here; pricing, routing, eviction elsewhere). Whenever a requirement
  reads "support different ways to X," reach for Strategy.
- **Facade + injected abstractions** keep the orchestrator thin and testable — the same shape recurs in almost every
  LLD answer.
- **Lock-free queue as an allocation pool** (free-list) is a pattern you'll see again wherever a fixed set of
  resources is claimed and returned under contention (connection pools, thread pools, seat booking).

---

## Next Problem

Recommended next in the **Easy** tier: **Vending Machine** — it's the canonical introduction to the **State**
pattern (idle → has-money → dispensing → out-of-stock) and pairs well with what you just built (it reuses Strategy
for pricing and Factory for products). Say the word and we'll start it in tutored mode, or switch to **interview
mode** if you'd rather be grilled.
