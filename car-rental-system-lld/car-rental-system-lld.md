# Car Rental System — Low Level Design

Target: Senior Java / OOD interview. Plain Java 11+, framework-agnostic, thread-safe, in-memory.

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| FR1 | Manage car inventory: add/remove cars. Each car has make, model, year, license plate (unique ID), car type, and rental price per day. |
| FR2 | Search cars by combinable criteria: car type, price range, and availability for a requested date range. |
| FR3 | Reservation lifecycle: create, modify (change dates), and cancel reservations. A reservation binds customer + car + date range + total price. |
| FR4 | Availability tracking: a car must never be double-booked for overlapping date ranges. |
| FR5 | Customer management: name, contact details, driver's license number. |
| FR6 | Payment processing: a reservation is CONFIRMED only after successful payment; cancellation triggers a (simulated) refund. |

### Non-Functional Requirements

| # | Requirement |
|---|-------------|
| NFR1 | **Concurrency safety** — concurrent reservation attempts on the same car for overlapping dates: exactly one succeeds. |
| NFR2 | **Extensibility (OCP)** — new car types, search criteria, and payment methods addable without modifying existing code. |
| NFR3 | **Consistency** — no paid-but-unreserved or confirmed-but-unpaid state; failure paths roll back cleanly. |
| NFR4 | **Interview scope** — single-process, in-memory store, but interfaces shaped so a DB/repository could replace the maps later. |

### Key Assumptions (stated to the interviewer)

1. **Availability is computed from reservation date overlaps**, not a boolean `isAvailable` flag. A car booked July 1–5 is still rentable July 10–15. (This single decision shapes the whole design — a status flag is the classic wrong answer here.)
2. Reservation states: `PENDING → CONFIRMED → COMPLETED`; `CANCELLED` reachable from PENDING or CONFIRMED.
3. Modify = change dates on the same car; it re-validates availability atomically (ignoring the reservation's own dates).
4. Single rental location; flat per-day pricing; no cancellation fee.
5. Payment is abstracted behind an interface and simulated.

---

## Step 2 — Entities & Relationships

| Entity | Kind | Purpose |
|--------|------|---------|
| `Car` | Class | Inventory item: licensePlate (ID), make, model, year, type, pricePerDay. Immutable identity. |
| `CarType` | Enum | ECONOMY, SEDAN, SUV, LUXURY, VAN — closed, small, behavior-free set → enum, not subclassing. |
| `Customer` | Class | id, name, contact, drivingLicense. |
| `Reservation` | Class | id, customer, car, DateRange, totalPrice, status. Owns its lifecycle transitions. |
| `ReservationStatus` | Enum | PENDING, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED. |
| `DateRange` | Value object | start/end + `overlaps(other)`. Pulling overlap logic into one tested place prevents the most common bug in this problem. |
| `SearchCriteria` | Interface (Strategy) | `matches(Car)` — implementations: TypeCriteria, PriceRangeCriteria, AndCriteria (composite). |
| `PaymentProcessor` | Interface (Strategy) | `processPayment(amount)`, `refund(amount)` — CreditCardProcessor, UpiProcessor, ... |
| `RentalService` | Service / façade | Orchestrates search, reservation, cancellation; owns the concurrency control. |

### Relationships (with type and justification)

| Relationship | Type | Why |
|---|---|---|
| `Reservation → Customer` | **Association** | A reservation references a customer; the customer exists independently and outlives the reservation. |
| `Reservation → Car` | **Association** | Same reasoning — the car is not owned by the reservation. |
| `Reservation → DateRange` | **Composition** | The DateRange has no meaning or lifetime outside its reservation; created and destroyed with it. |
| `RentalService → Car`, `RentalService → Reservation` | **Aggregation** | The service holds collections of cars/reservations; conceptually they could be re-homed (e.g., to a repository) without dying with the service. |
| `RentalService → PaymentProcessor` | **Dependency (injected)** | Used, not owned. Injecting it (constructor) is exactly what Spring DI formalizes with `@Component` + constructor injection — but in plain Java we just pass it in. |
| `RentalService → SearchCriteria` | **Dependency** | Passed per-call as a method parameter; the service never stores it. |
| `Car → CarType` | **Association** (enum field) | Attribute of the car. |

### What candidates typically get wrong here

- **Over-modeling**: subclassing `Car` into `SUVCar`, `SedanCar`, etc. Wrong — there is no type-specific *behavior*, only data. An enum field is correct. Subclass only when behavior differs (LSP-relevant).
- **Under-modeling**: putting `isAvailable: boolean` on `Car`. Availability is a *function of (car, dateRange, existing reservations)*, not a stored attribute.
- Forgetting `DateRange` as a value object and scattering overlap checks across the service.

---

## Step 3 — UML Class Design

```
                         <<enum>>                      <<enum>>
                         CarType                       ReservationStatus
                         ECONOMY, SEDAN, SUV,          PENDING, CONFIRMED, IN_PROGRESS,
                         LUXURY, VAN                   COMPLETED, CANCELLED

 +----------------------+        +---------------------------+
 | Car                  |        | Customer                  |
 +----------------------+        +---------------------------+
 | -licensePlate: String|        | -id: String               |
 | -make, model: String |        | -name: String             |
 | -year: int           |        | -contact: String          |
 | -type: CarType       |        | -drivingLicense: String   |
 | -pricePerDay: double |        +---------------------------+
 +----------------------+
        ^ 1                              ^ 1
        | association                    | association
        |                                |
 +------+--------------------------------+-------+         +--------------------+
 | Reservation                                   |<>------>| DateRange (VO)     |
 +-----------------------------------------------+  compo- +--------------------+
 | -id: String                                   |  sition | -start: LocalDate  |
 | -customer: Customer                           |         | -end: LocalDate    |
 | -car: Car                                     |         +--------------------+
 | -dateRange: DateRange                         |         | +overlaps(o): bool |
 | -totalPrice: double                           |         | +days(): long      |
 | -status: ReservationStatus                    |         +--------------------+
 +-----------------------------------------------+
 | +confirm() +cancel() +complete()              |   <- state transitions guarded
 +-----------------------------------------------+      inside the entity

 <<interface>> SearchCriteria            <<interface>> PaymentProcessor
 +matches(car: Car): boolean             +processPayment(amount): boolean
        ^            ^         ^         +refund(amount): boolean
        |            |         |                ^              ^
 TypeCriteria  PriceRange   AndCriteria   CreditCard       UpiProcessor
               Criteria     (composite,   Processor
                            holds List<SearchCriteria>)

 +------------------------------------------------------------------+
 | RentalService                                                    |
 +------------------------------------------------------------------+
 | -cars: ConcurrentHashMap<String, Car>            (aggregation)   |
 | -reservations: ConcurrentHashMap<String, Reservation>            |
 | -carLocks: ConcurrentHashMap<String, ReentrantLock>              |
 | -paymentProcessor: PaymentProcessor              (injected dep)  |
 +------------------------------------------------------------------+
 | +addCar(car) +removeCar(plate)                                   |
 | +search(criteria, range): List<Car>                              |
 | +reserve(customer, plate, range): Reservation                    |
 | +modifyReservation(resId, newRange): Reservation                 |
 | +cancel(resId): void                                             |
 | -isAvailable(car, range, ignoreResId): boolean                   |
 +------------------------------------------------------------------+

 Multiplicity: Customer 1 --- * Reservation;  Car 1 --- * Reservation
```

### SOLID mapping

- **SRP** — `Car`/`Customer` hold data; `Reservation` guards its own lifecycle; `DateRange` owns overlap math; `RentalService` orchestrates; `PaymentProcessor` does payments. Each class has one reason to change.
- **OCP** — new search filters = new `SearchCriteria` impl; new payment rails = new `PaymentProcessor` impl. Zero edits to `RentalService`.
- **LSP** — any `SearchCriteria`/`PaymentProcessor` substitutes cleanly; we deliberately avoided a `Car` hierarchy so there's no hierarchy to violate.
- **ISP** — small, focused interfaces (one or two methods each).
- **DIP** — `RentalService` depends on the `PaymentProcessor` *abstraction*, injected via constructor. (Spring note: this is precisely constructor injection of a bean; a singleton bean scope replaces a hand-rolled Singleton.)

### Design patterns and exactly why they fit

| Pattern | Where | Why it fits *here* |
|---|---|---|
| **Strategy** | `SearchCriteria`, `PaymentProcessor` | The *algorithm varies* (how to filter, how to charge) while the orchestration is fixed. Callers pick the strategy at runtime; service code never branches on type. |
| **Composite** | `AndCriteria` | Requirement FR2 says criteria are *combinable*. Composite lets one criterion and a tree of criteria share the same `matches()` interface. |
| **State (lightweight)** | `ReservationStatus` + guarded transitions in `Reservation` | The full GoF State pattern (one class per state) is overkill for 5 states with trivial behavior; an enum + transition guards in the entity gives the same illegal-transition protection with far less ceremony. Say this trade-off out loud in the interview. |
| **Singleton — deliberately avoided** | `RentalService` | Many sample solutions make this a Singleton. Prefer a plain class wired once at composition root: testable, and identical in spirit to a Spring singleton-scoped bean. Mention you *could* and why you didn't. |

### The two decisions an interviewer will probe

1. **How do you prevent double-booking?** → per-car lock around check-then-reserve (Step 5). The naive answer (`synchronized` on the whole service) serializes *all* reservations; per-car locking serializes only contention on the *same* car.
2. **Why is availability computed, not stored?** → a boolean can't represent future date ranges; it also creates a second source of truth that drifts from the reservation list.

---

## Step 4 — Implementation

```java
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

// ---------- Enums ----------
enum CarType { ECONOMY, SEDAN, SUV, LUXURY, VAN }

enum ReservationStatus { PENDING, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED }

// ---------- Value Object ----------
final class DateRange {
    private final LocalDate start;
    private final LocalDate end;

    DateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        if (start.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("cannot book in the past");
        }
        this.start = start;
        this.end = end;
    }

    /** Half-open semantics [start, end): a car returned on the 5th
     *  can be picked up on the 5th. Decide this explicitly — most
     *  overlap bugs come from fuzzy boundary semantics. */
    boolean overlaps(DateRange other) {
        return this.start.isBefore(other.end) && other.start.isBefore(this.end);
    }

    long days() { return ChronoUnit.DAYS.between(start, end); }

    LocalDate getStart() { return start; }
    LocalDate getEnd()   { return end; }
}

// ---------- Domain entities ----------
final class Car {
    private final String licensePlate;   // natural unique ID
    private final String make;
    private final String model;
    private final int year;
    private final CarType type;
    private final double pricePerDay;

    Car(String licensePlate, String make, String model,
        int year, CarType type, double pricePerDay) {
        this.licensePlate = Objects.requireNonNull(licensePlate);
        this.make = make; this.model = model; this.year = year;
        this.type = Objects.requireNonNull(type);
        if (pricePerDay <= 0) throw new IllegalArgumentException("price must be > 0");
        this.pricePerDay = pricePerDay;
    }
    String getLicensePlate() { return licensePlate; }
    CarType getType()        { return type; }
    double getPricePerDay()  { return pricePerDay; }
    String getMake()         { return make; }
    String getModel()        { return model; }
    int getYear()            { return year; }
}

final class Customer {
    private final String id;
    private final String name;
    private final String contact;
    private final String drivingLicense;

    Customer(String id, String name, String contact, String drivingLicense) {
        this.id = id; this.name = name;
        this.contact = contact; this.drivingLicense = drivingLicense;
    }
    String getId() { return id; }
    String getName() { return name; }
}

class Reservation {
    private final String id;
    private final Customer customer;
    private final Car car;
    private volatile DateRange dateRange;     // mutable: modify-dates use case
    private volatile double totalPrice;
    private volatile ReservationStatus status;

    Reservation(String id, Customer customer, Car car, DateRange range) {
        this.id = id; this.customer = customer; this.car = car;
        this.dateRange = range;
        this.totalPrice = range.days() * car.getPricePerDay();
        this.status = ReservationStatus.PENDING;
    }

    // --- Guarded state transitions: the entity protects its own invariants ---
    synchronized void confirm() {
        requireStatus(ReservationStatus.PENDING, "confirm");
        status = ReservationStatus.CONFIRMED;
    }
    synchronized void cancel() {
        if (status == ReservationStatus.COMPLETED || status == ReservationStatus.CANCELLED)
            throw new IllegalStateException("Cannot cancel a " + status + " reservation");
        status = ReservationStatus.CANCELLED;
    }
    synchronized void complete() {
        requireStatus(ReservationStatus.IN_PROGRESS, "complete");
        status = ReservationStatus.COMPLETED;
    }
    synchronized void reschedule(DateRange newRange) {
        requireStatus(ReservationStatus.CONFIRMED, "reschedule");
        this.dateRange = newRange;
        this.totalPrice = newRange.days() * car.getPricePerDay();
    }
    private void requireStatus(ReservationStatus expected, String action) {
        if (status != expected)
            throw new IllegalStateException("Cannot " + action + " from " + status);
    }

    String getId() { return id; }
    Car getCar() { return car; }
    Customer getCustomer() { return customer; }
    DateRange getDateRange() { return dateRange; }
    ReservationStatus getStatus() { return status; }
    double getTotalPrice() { return totalPrice; }
}

// ---------- Strategy: search ----------
interface SearchCriteria {
    boolean matches(Car car);
}

class TypeCriteria implements SearchCriteria {
    private final CarType type;
    TypeCriteria(CarType type) { this.type = type; }
    public boolean matches(Car car) { return car.getType() == type; }
}

class PriceRangeCriteria implements SearchCriteria {
    private final double min, max;
    PriceRangeCriteria(double min, double max) { this.min = min; this.max = max; }
    public boolean matches(Car car) {
        return car.getPricePerDay() >= min && car.getPricePerDay() <= max;
    }
}

/** Composite: a tree of criteria is itself a criterion. */
class AndCriteria implements SearchCriteria {
    private final List<SearchCriteria> criteria;
    AndCriteria(SearchCriteria... criteria) { this.criteria = List.of(criteria); }
    public boolean matches(Car car) {
        return criteria.stream().allMatch(c -> c.matches(car));
    }
}

// ---------- Strategy: payment ----------
interface PaymentProcessor {
    boolean processPayment(double amount);
    boolean refund(double amount);
}

class CreditCardProcessor implements PaymentProcessor {
    public boolean processPayment(double amount) {
        // gateway call simulated
        return true;
    }
    public boolean refund(double amount) { return true; }
}

// ---------- Exceptions (domain-specific, unchecked) ----------
class CarNotFoundException extends RuntimeException {
    CarNotFoundException(String plate) { super("No car: " + plate); }
}
class CarUnavailableException extends RuntimeException {
    CarUnavailableException(String plate) { super("Car unavailable for range: " + plate); }
}
class ReservationNotFoundException extends RuntimeException {
    ReservationNotFoundException(String id) { super("No reservation: " + id); }
}
class PaymentFailedException extends RuntimeException {
    PaymentFailedException(String id) { super("Payment failed for reservation " + id); }
}

// ---------- Service / façade ----------
class RentalService {
    private final Map<String, Car> cars = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    // One lock per car: contention is per-car, not system-wide.
    private final Map<String, ReentrantLock> carLocks = new ConcurrentHashMap<>();
    private final PaymentProcessor paymentProcessor;   // DIP: injected abstraction

    RentalService(PaymentProcessor paymentProcessor) {
        this.paymentProcessor = paymentProcessor;
    }

    // ----- Inventory -----
    void addCar(Car car) {
        cars.put(car.getLicensePlate(), car);
        carLocks.putIfAbsent(car.getLicensePlate(), new ReentrantLock());
    }

    void removeCar(String plate) {
        ReentrantLock lock = lockFor(plate);
        lock.lock();
        try {
            boolean hasActive = reservations.values().stream()
                .anyMatch(r -> r.getCar().getLicensePlate().equals(plate)
                            && isActive(r));
            if (hasActive)
                throw new IllegalStateException("Car has active reservations");
            cars.remove(plate);
        } finally {
            lock.unlock();
        }
    }

    // ----- Search -----
    List<Car> search(SearchCriteria criteria, DateRange range) {
        return cars.values().stream()
            .filter(criteria::matches)
            .filter(car -> isAvailable(car, range, null))   // availability is COMPUTED
            .collect(Collectors.toList());
    }

    // ----- Reserve: the critical section -----
    Reservation reserve(Customer customer, String plate, DateRange range) {
        Car car = Optional.ofNullable(cars.get(plate))
                          .orElseThrow(() -> new CarNotFoundException(plate));

        ReentrantLock lock = lockFor(plate);
        lock.lock();                       // serialize ONLY this car's bookings
        try {
            if (!isAvailable(car, range, null))
                throw new CarUnavailableException(plate);

            Reservation res = new Reservation(
                UUID.randomUUID().toString(), customer, car, range);
            // Publish BEFORE payment so a concurrent thread inside this same
            // lock... can't exist (we hold the lock). Publish-then-pay keeps
            // the check+insert atomic; failure path removes it (compensating
            // action — a mini saga).
            reservations.put(res.getId(), res);

            if (!paymentProcessor.processPayment(res.getTotalPrice())) {
                reservations.remove(res.getId());          // rollback
                throw new PaymentFailedException(res.getId());
            }
            res.confirm();
            return res;
        } finally {
            lock.unlock();
        }
    }

    // ----- Modify dates: re-validate atomically, ignoring own reservation -----
    Reservation modifyReservation(String resId, DateRange newRange) {
        Reservation res = getReservation(resId);
        String plate = res.getCar().getLicensePlate();
        ReentrantLock lock = lockFor(plate);
        lock.lock();
        try {
            if (!isAvailable(res.getCar(), newRange, resId))  // ignore self!
                throw new CarUnavailableException(plate);

            double oldPrice = res.getTotalPrice();
            res.reschedule(newRange);
            double diff = res.getTotalPrice() - oldPrice;
            if (diff > 0) {
                if (!paymentProcessor.processPayment(diff)) {
                    // compensating action: revert (simplified)
                    throw new PaymentFailedException(resId);
                }
            } else if (diff < 0) {
                paymentProcessor.refund(-diff);
            }
            return res;
        } finally {
            lock.unlock();
        }
    }

    // ----- Cancel -----
    void cancel(String resId) {
        Reservation res = getReservation(resId);
        ReentrantLock lock = lockFor(res.getCar().getLicensePlate());
        lock.lock();
        try {
            res.cancel();                                  // entity guards state
            paymentProcessor.refund(res.getTotalPrice());
        } finally {
            lock.unlock();
        }
    }

    // ----- Helpers -----
    /** Availability = no ACTIVE reservation on this car overlaps the range.
     *  ignoreResId lets modify() exclude its own booking from the check. */
    private boolean isAvailable(Car car, DateRange range, String ignoreResId) {
        return reservations.values().stream()
            .filter(r -> r.getCar().getLicensePlate().equals(car.getLicensePlate()))
            .filter(this::isActive)
            .filter(r -> !r.getId().equals(ignoreResId))
            .noneMatch(r -> r.getDateRange().overlaps(range));
    }

    private boolean isActive(Reservation r) {
        ReservationStatus s = r.getStatus();
        return s == ReservationStatus.PENDING
            || s == ReservationStatus.CONFIRMED
            || s == ReservationStatus.IN_PROGRESS;
    }

    private ReentrantLock lockFor(String plate) {
        return carLocks.computeIfAbsent(plate, p -> new ReentrantLock());
    }

    private Reservation getReservation(String id) {
        return Optional.ofNullable(reservations.get(id))
                       .orElseThrow(() -> new ReservationNotFoundException(id));
    }
}

// ---------- Demo ----------
class CarRentalDemo {
    public static void main(String[] args) {
        RentalService service = new RentalService(new CreditCardProcessor());

        service.addCar(new Car("KA-01-1234", "Toyota", "Camry", 2023, CarType.SEDAN, 60));
        service.addCar(new Car("KA-02-5678", "Honda", "CR-V", 2024, CarType.SUV, 80));
        service.addCar(new Car("KA-03-9012", "Maruti", "Swift", 2022, CarType.ECONOMY, 30));

        Customer alice = new Customer("C1", "Alice", "alice@mail.com", "DL-111");

        DateRange july1to5 = new DateRange(
            LocalDate.now().plusDays(10), LocalDate.now().plusDays(14));

        SearchCriteria affordableSedanOrEco = new AndCriteria(
            new PriceRangeCriteria(0, 70));

        List<Car> found = service.search(affordableSedanOrEco, july1to5);
        found.forEach(c -> System.out.println("Available: " + c.getMake() + " " + c.getModel()));

        Reservation res = service.reserve(alice, "KA-01-1234", july1to5);
        System.out.println("Reserved: " + res.getId() + " status=" + res.getStatus()
            + " total=" + res.getTotalPrice());

        // Second attempt on overlapping dates fails:
        try {
            service.reserve(alice, "KA-01-1234", july1to5);
        } catch (CarUnavailableException e) {
            System.out.println("Correctly rejected: " + e.getMessage());
        }
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input & boundary conditions

| Case | Handling |
|---|---|
| `end <= start`, null dates, past dates | Rejected in the `DateRange` constructor — invalid value objects are unrepresentable (fail fast at the boundary). |
| Unknown car / reservation ID | `CarNotFoundException` / `ReservationNotFoundException` — domain-specific unchecked exceptions; callers can map them to HTTP 404 in a web layer. |
| Back-to-back bookings (return day = pickup day) | Decided explicitly: half-open `[start, end)` semantics — July 1–5 and July 5–8 do **not** overlap. Most overlap bugs are boundary-semantics bugs; say your convention out loud. |
| Cancel a COMPLETED/CANCELLED reservation | `Reservation.cancel()` guards its own transitions → `IllegalStateException`. State invariants live in the entity, not scattered in the service. |
| Modify to overlapping dates | `isAvailable(..., ignoreResId)` excludes the reservation's own dates — forgetting this makes every modification "conflict with itself". Classic bug. |
| Remove a car with active bookings | Rejected under the car's lock; alternative design: soft-delete (mark RETIRED) so history survives. |
| Payment fails mid-reserve | Compensating action: remove the PENDING reservation inside the same lock → no orphaned bookings (a one-step saga; mention the saga pattern if asked about a distributed version). |

### Concurrency analysis

**Shared mutable state**
1. `reservations` map (insertions, status mutations)
2. `Reservation.status` / `dateRange`
3. `cars` map

**Critical section** — the *check-then-act* in `reserve()`: "is the car free for these dates?" followed by "insert reservation". Unsynchronized, two threads can both pass the check (TOCTOU race) → double-booking.

**Chosen primitives and why**

| Primitive | Where | Why this one |
|---|---|---|
| `ReentrantLock` **per car** | `reserve` / `modify` / `cancel` / `removeCar` | Serializes only operations on the *same* car. A single `synchronized` service method would also be correct but serializes the whole fleet — needless contention. Per-car granularity is the answer interviewers want. |
| `ConcurrentHashMap` | `cars`, `reservations`, `carLocks` | Safe concurrent reads/writes without locking the maps; `computeIfAbsent` gives atomic lazy lock creation. |
| `synchronized` methods on `Reservation` | state transitions | Tiny critical sections protecting one object's invariant — intrinsic lock is the simplest correct tool. |
| `volatile` fields on `Reservation` | status/dateRange visibility | Readers outside the lock (e.g., `search` calling `getStatus()`) see fresh values. |

**Freedom-from-deadlock argument**
- Every public operation acquires **at most one car lock** at a time, plus possibly one `Reservation` monitor *strictly after* the car lock. Lock acquisition order is therefore globally consistent (carLock → reservationMonitor), and no path acquires two car locks → no cycle in the wait-for graph → no deadlock.
- No livelock: `ReentrantLock.lock()` blocks rather than spins/retries.
- No starvation concern at interview scale; if pressed, construct locks with `new ReentrantLock(true)` for fairness (FIFO) at a throughput cost.

**Race-freedom argument**
- All writes that decide availability (insert/cancel/reschedule) happen while holding that car's lock; the availability check in `reserve`/`modify` holds the same lock → check-then-act is atomic per car.
- `search()` is deliberately lock-free: it may return a car that gets booked a millisecond later, but `reserve()` re-validates under the lock. Optimistic read, pessimistic write — same idea as re-checking after acquiring a lock in double-checked locking.

**Scaling beyond one JVM (if the interviewer pushes)**
- In-memory locks don't span processes. Options: DB transaction with `SELECT ... FOR UPDATE` on the car row, an exclusion constraint on the date range (Postgres `tstzrange` + GiST), optimistic locking with a version column (`@Version` in JPA), or a distributed lock (Redis/ZooKeeper) — each trading latency vs. complexity.

---

## Interviewer Follow-ups (with model answers)

1. **"Add a loyalty discount / seasonal pricing."** → Introduce a `PricingStrategy` interface (`calculate(car, range, customer)`); `Reservation` delegates price computation to it. Pure Strategy/OCP — no existing class changes.
2. **"Support multiple rental locations."** → Add `Location` entity; `Car` gets a home location (association); search gains a `LocationCriteria`. Pickup ≠ drop-off introduces a relocation concern — model as a field on `Reservation`.
3. **"What if 10x the cars and reservations?"** → The O(n)-over-all-reservations availability scan becomes the bottleneck. Index reservations per car (`Map<plate, NavigableMap<startDate, Reservation>>`) for O(log n) overlap checks; eventually move to a DB with a range index.
4. **"Make search criteria support OR / NOT."** → Add `OrCriteria`, `NotCriteria` composites — the Composite pattern already gives the closed interface; this is a 10-line change, which is the point of the design.
5. **"How would you test the double-booking protection?"** → Spawn N threads via `ExecutorService`, all reserving the same car/range behind a `CountDownLatch` gate; assert exactly one success and N−1 `CarUnavailableException`s.

## Transferable Lessons

- **Availability is computed, never stored** — reappears in Hotel Booking, Movie Ticket Booking (seats), Parking Lot (spots), Meeting Scheduler.
- **Per-resource locking around check-then-act** — the universal fix for TOCTOU double-booking; same shape in BookMyShow seat locking.
- **Strategy + Composite for filterable/parameterizable behavior** — search filters, payment, pricing; you will reuse this trio constantly.
- **Entities guard their own state transitions** — enum + guarded methods beats a full State pattern until per-state *behavior* diverges.

**Next problem suggestion:** Hotel Management System (same overlap/locking core, adds rooms-by-type pooling) or jump to Movie Ticket Booking (seat-level locking, a harder concurrency variant).
