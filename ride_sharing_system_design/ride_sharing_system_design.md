# Ride-Sharing Service (Uber) — Low-Level Design

**Tier:** Hard · **New concept introduced:** matching engine over a contended, mobile resource pool — atomic resource claiming via CAS state transitions, and time-boxed offers (offer → accept/decline/timeout).

**How this connects to prior problems:** Concert Ticket Booking and Airline taught you *pessimistic* claiming (per-seat `ReentrantLock`, two-phase reserve-with-TTL). Ride-Sharing teaches the *optimistic* sibling: claim a resource by atomically compare-and-setting its status enum. Same invariant ("a resource serves at most one consumer"), different primitive, different trade-offs — interviewers love asking when you'd pick which.

---

## Step 1 — Requirements

### Functional Requirements

1. **Ride request:** A rider requests a ride with pickup location, destination, and ride type (`REGULAR`, `PREMIUM`).
2. **Driver lifecycle:** Drivers go online/offline. While online they are `AVAILABLE`, temporarily `OFFERED` (deciding on a request), or `ON_TRIP`.
3. **Matching:** The system ranks nearby available drivers (pluggable strategy, default nearest-first) and offers the ride to **one driver at a time**. A driver may accept or decline; an unanswered offer **expires** after a timeout, and the system moves to the next candidate.
4. **Ride lifecycle:** `REQUESTED → DRIVER_ASSIGNED → IN_PROGRESS → COMPLETED`, with `CANCELLED` reachable from `REQUESTED` (rider) and `DRIVER_ASSIGNED` (rider or driver). Driver cancellation after assignment triggers re-matching.
5. **Fare calculation:** Fare = f(distance, duration, ride type), pluggable; surge pricing supported as a decorator.
6. **Payment:** Processed at ride completion via an external gateway abstraction. A completed ride is never rolled back; failed payment is retried.
7. **Notifications & tracking:** Riders and drivers are notified on every ride status transition. Driver location updates are tracked (high-frequency writes).

### Non-Functional Requirements

1. **Concurrency & consistency:** Many concurrent ride requests may target the same driver. Invariant: **a driver is offered/assigned to at most one ride at any instant**, and a ride is assigned at most one driver. Payment is exactly-once per completed ride.
2. **Extensibility (OCP):** New ride types, fare strategies (surge), and matching strategies (rating-weighted, ETA-based) without modifying existing classes.
3. **Latency:** Matching should avoid full scans at scale — in this in-memory LLD we scan + filter, but we explicitly note the geo-index (geohash / quad-tree) upgrade path.
4. **Scope:** Single-JVM, in-memory repositories (`ConcurrentHashMap`), no real persistence or networking. External payment gateway and driver app are simulated behind interfaces.

### Assumptions locked in

- **Push-based sequential offers** (system → driver), not driver-browses-requests. Covers requirements 3 and 4 of the original prompt in one coherent design.
- Euclidean distance on `(lat, lon)`; road-network routing is out of scope.
- Offer acceptance window: 15 seconds (configurable).
- Rider may cancel any time before `IN_PROGRESS`. No cancellation fees.
- Ratings out of scope (extension question).

---

## Step 2 — Entities & Relationships

| Entity | Kind | Why it exists |
|---|---|---|
| `Location` | Immutable value object | `(lat, lon)` + distance computation. Immutability makes it safe to share across threads with a single `volatile` write. |
| `RideType` | Enum with fare multiplier | Behavior-carrying enum (multiplier lives on the constant) — avoids `switch` sprawl. |
| `Rider` | Entity | Identity + payment method. Deliberately thin. |
| `Driver` | Entity with a **state machine** | `AtomicReference<DriverStatus>` + `volatile Location`. The driver's status field *is* the concurrency control for matching. |
| `DriverStatus` | Enum | `OFFLINE, AVAILABLE, OFFERED, ON_TRIP`. The `OFFERED` state is the design's keystone — it reserves a driver while they decide. |
| `Vehicle` | Entity | Owned by driver; determines which `RideType`s the driver can serve. |
| `Ride` | Entity, aggregate root | Owns its status transitions behind a `ReentrantLock`. References rider and (eventually) driver. |
| `RideStatus` | Enum | Guarded transitions, same idiom as `BookingStatus` in Airline. |
| `RideOffer` | Short-lived coordination object | Resolves the three-way race accept / decline / timeout with one CAS. **Not** persisted — it's a synchronization artifact, not domain data. |
| `FarePricingStrategy` | Interface (Strategy) | `StandardFareStrategy`, wrapped by `SurgeFareDecorator`. |
| `DriverMatchingStrategy` | Interface (Strategy) | `NearestDriverStrategy` default. |
| `PaymentService` / `Payment` | Interface + record | External gateway boundary, like Airline. `PaymentStatus: PENDING, COMPLETED, FAILED`. |
| `RideObserver` | Interface (Observer) | `NotificationService` implements; registered in a `CopyOnWriteArrayList`. |
| `RideService` | Singleton façade | Holder idiom. Owns registries, matching pool, strategies. |

### Relationships

- `Driver` **composes** `Vehicle` (composition — vehicle has no meaning in the system without its driver; lifecycle-bound).
- `Ride` **associates** `Rider` (1) and `Driver` (0..1) — association, not aggregation: ride doesn't own them, they exist independently.
- `Ride` **composes** `Payment` (0..1) — payment record cannot outlive its ride.
- `RideService` **aggregates** `Driver`s and `Ride`s via registries (aggregation — it holds them but doesn't define their lifecycle conceptually).
- `RideService` **depends on** `FarePricingStrategy`, `DriverMatchingStrategy`, `PaymentService` (dependency via interfaces — DIP).
- `RideOffer` **associates** one `Ride` and one `Driver` transiently.

### Deliberate under-modeling (recurring principle)

- **No `Trip` separate from `Ride`** — one lifecycle, one entity. Splitting them is over-modeling here.
- **No `MatchingEngine` entity class holding state** — matching is a *behavior* (a method + strategy), not a stateful domain object.
- **No `Notification` entity** — notifications are events flowing through Observer, same call as Facebook/LinkedIn.
- `RideOffer` exists *only* because the accept/decline/timeout race needs a dedicated synchronization point. If matching were instant-assign (no driver consent), it would be deleted.

---

## Step 3 — UML Class Design

```
+----------------------+         +---------------------------+
|      Location        |         |     RideType (enum)       |
|----------------------|         |---------------------------|
| - latitude:  double  |         | REGULAR(1.0)              |
| - longitude: double  |         | PREMIUM(1.5)              |
|----------------------|         | + fareMultiplier(): double|
| + distanceTo(Location)|        +---------------------------+
+----------------------+

+--------------------------+ 1    1 +-----------------+
|         Driver           |<>------|     Vehicle     |   (composition)
|--------------------------|        |-----------------|
| - id: String             |        | - plate: String |
| - status:                |        | - supported:    |
|   AtomicReference        |        |   Set<RideType> |
|   <DriverStatus>         |        +-----------------+
| - location: volatile Loc |
|--------------------------|
| + tryTransition(from,to) |  <-- CAS; the atomic claim primitive
| + goOnline()/goOffline() |
+--------------------------+

DriverStatus: OFFLINE -> AVAILABLE <-> OFFERED -> ON_TRIP -> AVAILABLE

+---------------------------+ *      1 +----------+
|          Ride             |----------|  Rider   |     (association)
|---------------------------| 0..1   1 +----------+
| - id, pickup, destination |----------- Driver         (association)
| - type: RideType          |
| - status: RideStatus      |  1     0..1 +-----------+
| - lock: ReentrantLock     |<>-----------|  Payment  |  (composition)
|---------------------------|             +-----------+
| + assignDriver(d): bool   |
| + start()/complete()      |
| + cancel(by): bool        |
+---------------------------+

RideStatus: REQUESTED -> DRIVER_ASSIGNED -> IN_PROGRESS -> COMPLETED
                 \------------\--> CANCELLED

+-----------------------------+
|         RideOffer           |   transient coordination object
|-----------------------------|
| - state: AtomicReference    |   PENDING -> ACCEPTED | DECLINED | EXPIRED
|          <OfferState>       |   (exactly one CAS winner)
| - resolved: CountDownLatch  |
|-----------------------------|
| + accept(): boolean         |
| + decline(): boolean        |
| + awaitResolution(timeout)  |
+-----------------------------+

<<interface>> DriverMatchingStrategy        <<interface>> FarePricingStrategy
 + rank(drivers, pickup, type): List         + calculate(ctx): BigDecimal
   ^                                            ^                ^
   |                                            |                |
 NearestDriverStrategy               StandardFareStrategy  SurgeFareDecorator
                                                           (wraps a delegate)

<<interface>> PaymentService                <<interface>> RideObserver
 + charge(rider, amount): Payment            + onRideUpdate(ride)
   ^                                            ^
 MockPaymentGateway                        NotificationService

+------------------------------------------------+
|            RideService  (Singleton)            |
|------------------------------------------------|
| - drivers: ConcurrentHashMap<String,Driver>    |
| - rides:   ConcurrentHashMap<String,Ride>      |
| - pendingOffers: ConcurrentHashMap<String,     |
|                  RideOffer>   (key = driverId) |
| - matchingPool: ExecutorService                |
| - observers: CopyOnWriteArrayList<RideObserver>|
| - matchingStrategy / fareStrategy / payments   |
|------------------------------------------------|
| + requestRide(rider, pickup, dest, type): Ride |
| + acceptOffer(driverId) / declineOffer(...)    |
| + startRide / completeRide / cancelRide        |
+------------------------------------------------+
```

### Pattern & SOLID mapping — and *why each fits*

| Concept | Where | Why it fits (the part interviewers want) |
|---|---|---|
| **State (enum-guarded)** | `RideStatus`, `DriverStatus` | Both lifecycles are small and linear; full GoF State (one class per state) would add 8 classes for no behavioral variance. Enum + guarded transition methods keeps invalid transitions impossible while staying readable. Same call you made for `BookingStatus` in Airline. |
| **Strategy** | Matching, Fare | These are the two axes the business changes most (surge, ETA-based matching). Strategy isolates each algorithm behind an interface so adding one is *additive* (OCP), and `RideService` depends only on abstractions (DIP). |
| **Decorator** | `SurgeFareDecorator` | Surge is a *modifier* of any base fare, not a new fare algorithm — decorating composes (`surge(standard)`) instead of forcing a `StandardSurgeFareStrategy` class explosion. |
| **Observer** | Ride status notifications | Ride lifecycle code must not know about push/SMS/email channels (SRP); observers subscribe. `CopyOnWriteArrayList` because reads (notifications) vastly outnumber listener registration. |
| **Singleton (holder idiom)** | `RideService` | One authoritative registry per JVM; holder idiom gives lazy init + thread safety with zero locking, unlike `static synchronized getInstance()`. |
| **Optimistic CAS claim** | `Driver.tryTransition` | The *new* lesson. The driver's status enum doubles as its lock: `compareAndSet(AVAILABLE, OFFERED)` admits exactly one winner among racing matchers, with no lock object, no blocking, no deadlock possibility. |
| **SRP** | `Ride` guards its own transitions; `RideService` orchestrates; strategies price/match; gateway pays | Each class has one reason to change. |
| **LSP/ISP** | Strategies, `PaymentService`, `RideObserver` | All implementations are substitutable; interfaces are single-method and role-specific. |

### The two decisions an interviewer will probe

1. **Why CAS on `DriverStatus` instead of a per-driver `ReentrantLock`?**
   The claim is a *single atomic decision* ("is this driver free? then he's mine"), not a critical *section* spanning multiple operations. CAS expresses exactly that: one instruction, lock-free, zero deadlock surface, and a failed claim costs nothing — the matcher just tries the next candidate. A lock earns its keep when you must hold exclusivity across several reads/writes (seat + payment in Concert Booking); here there is nothing to hold. Bonus: a CAS-based claim can never be forgotten-unlocked.

2. **How does one offer resolve the 3-way race (driver accepts / driver declines / timer expires) exactly once?**
   `RideOffer.state` is an `AtomicReference<OfferState>` starting at `PENDING`. Accept, decline, and expiry each attempt `compareAndSet(PENDING, X)`. Exactly one CAS succeeds; the losers see `false` and become no-ops. A `CountDownLatch` wakes the matching thread the instant any winner lands, so a decline doesn't burn the rest of the 15-second window.

---

## Step 4 — Implementation

> Focus: the matching engine, the CAS claim, the offer race, and guarded transitions. Getters/constructors compressed.

### Value objects and enums

```java
public final class Location {
    private final double latitude;
    private final double longitude;

    public Location(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /** Euclidean is fine for LLD; swap for Haversine without touching callers. */
    public double distanceTo(Location other) {
        double dx = latitude - other.latitude;
        double dy = longitude - other.longitude;
        return Math.sqrt(dx * dx + dy * dy);
    }
    // getters omitted
}

public enum RideType {
    REGULAR(1.0), PREMIUM(1.5);

    private final double fareMultiplier;
    RideType(double m) { this.fareMultiplier = m; }
    public double fareMultiplier() { return fareMultiplier; }
}

public enum DriverStatus { OFFLINE, AVAILABLE, OFFERED, ON_TRIP }

public enum RideStatus { REQUESTED, DRIVER_ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED }

public enum PaymentStatus { PENDING, COMPLETED, FAILED }
```

### Driver — the status field *is* the concurrency control

```java
public class Driver {
    private final String id;
    private final String name;
    private final Vehicle vehicle;

    // AtomicReference, not plain field + synchronized: the matcher's claim
    // must be a single atomic decision visible across threads.
    private final AtomicReference<DriverStatus> status =
            new AtomicReference<>(DriverStatus.OFFLINE);

    // Written by one thread (the driver's app), read by many matchers.
    // Location is immutable, so a volatile reference is fully safe.
    private volatile Location location;

    public Driver(String id, String name, Vehicle vehicle, Location start) {
        this.id = id; this.name = name; this.vehicle = vehicle; this.location = start;
    }

    /** The atomic claim primitive. Exactly one caller wins a given transition. */
    public boolean tryTransition(DriverStatus expect, DriverStatus next) {
        return status.compareAndSet(expect, next);
    }

    public void goOnline()  { status.compareAndSet(DriverStatus.OFFLINE, DriverStatus.AVAILABLE); }

    /** Going offline is only legal from AVAILABLE — never mid-offer or mid-trip. */
    public boolean goOffline() {
        return status.compareAndSet(DriverStatus.AVAILABLE, DriverStatus.OFFLINE);
    }

    public void updateLocation(Location loc) { this.location = loc; }

    public DriverStatus status()   { return status.get(); }
    public Location location()     { return location; }
    public Vehicle vehicle()       { return vehicle; }
    public String id()             { return id; }
}
```

### RideOffer — one CAS resolves a three-way race

```java
public class RideOffer {
    public enum OfferState { PENDING, ACCEPTED, DECLINED, EXPIRED }

    private final Ride ride;
    private final Driver driver;
    private final AtomicReference<OfferState> state =
            new AtomicReference<>(OfferState.PENDING);
    private final CountDownLatch resolved = new CountDownLatch(1);

    public RideOffer(Ride ride, Driver driver) {
        this.ride = ride; this.driver = driver;
    }

    /** Returns true only for the single winning resolver. */
    public boolean accept()  { return resolve(OfferState.ACCEPTED); }
    public boolean decline() { return resolve(OfferState.DECLINED); }

    private boolean resolve(OfferState target) {
        if (state.compareAndSet(OfferState.PENDING, target)) {
            resolved.countDown();           // wake the matcher immediately
            return true;
        }
        return false;                       // lost the race; no-op
    }

    /**
     * Matcher blocks here. Returns the final state.
     * If the timeout fires first, EXPIRED competes in the same CAS —
     * so a last-millisecond accept and the expiry can never both win.
     */
    public OfferState awaitResolution(long timeoutMillis) throws InterruptedException {
        resolved.await(timeoutMillis, TimeUnit.MILLISECONDS);
        state.compareAndSet(OfferState.PENDING, OfferState.EXPIRED);
        return state.get();
    }

    public Ride ride()     { return ride; }
    public Driver driver() { return driver; }
}
```

### Ride — guarded transitions behind its own lock

```java
public class Ride {
    private final String id;
    private final Rider rider;
    private final Location pickup;
    private final Location destination;
    private final RideType type;

    private final ReentrantLock lock = new ReentrantLock();
    private RideStatus status = RideStatus.REQUESTED;   // guarded by lock
    private Driver driver;                              // guarded by lock
    private Payment payment;                            // guarded by lock
    private Instant startedAt;
    private Instant completedAt;

    public Ride(String id, Rider rider, Location pickup, Location dest, RideType type) {
        this.id = id; this.rider = rider; this.pickup = pickup;
        this.destination = dest; this.type = type;
    }

    /** Succeeds only from REQUESTED — a cancelled ride rejects late assignment. */
    public boolean assignDriver(Driver d) {
        lock.lock();
        try {
            if (status != RideStatus.REQUESTED) return false;
            this.driver = d;
            this.status = RideStatus.DRIVER_ASSIGNED;
            return true;
        } finally { lock.unlock(); }
    }

    public boolean start() {
        lock.lock();
        try {
            if (status != RideStatus.DRIVER_ASSIGNED) return false;
            status = RideStatus.IN_PROGRESS;
            startedAt = Instant.now();
            return true;
        } finally { lock.unlock(); }
    }

    public boolean complete() {
        lock.lock();
        try {
            if (status != RideStatus.IN_PROGRESS) return false;
            status = RideStatus.COMPLETED;
            completedAt = Instant.now();
            return true;
        } finally { lock.unlock(); }
    }

    /** Cancellable from REQUESTED or DRIVER_ASSIGNED; returns the driver to free, if any. */
    public Optional<Driver> cancel() {
        lock.lock();
        try {
            if (status != RideStatus.REQUESTED && status != RideStatus.DRIVER_ASSIGNED) {
                return Optional.empty();    // too late, or already cancelled
            }
            status = RideStatus.CANCELLED;
            Driver freed = this.driver;
            this.driver = null;
            return Optional.ofNullable(freed);
        } finally { lock.unlock(); }
    }

    public RideStatus statusSnapshot() {
        lock.lock();
        try { return status; } finally { lock.unlock(); }
    }
    // getters omitted
}
```

### Strategies

```java
public interface DriverMatchingStrategy {
    /** Ranked candidates. Best-effort snapshot — the CAS claim is the real gate. */
    List<Driver> rank(Collection<Driver> drivers, Location pickup, RideType type);
}

public class NearestDriverStrategy implements DriverMatchingStrategy {
    private static final double MAX_RADIUS = 5.0;

    @Override
    public List<Driver> rank(Collection<Driver> drivers, Location pickup, RideType type) {
        return drivers.stream()
                .filter(d -> d.status() == DriverStatus.AVAILABLE)   // advisory only!
                .filter(d -> d.vehicle().supports(type))
                .filter(d -> d.location().distanceTo(pickup) <= MAX_RADIUS)
                .sorted(Comparator.comparingDouble(d -> d.location().distanceTo(pickup)))
                .collect(Collectors.toList());
    }
}

public interface FarePricingStrategy {
    BigDecimal calculate(double distanceKm, long durationMinutes, RideType type);
}

public class StandardFareStrategy implements FarePricingStrategy {
    private static final BigDecimal BASE    = new BigDecimal("50.00");
    private static final BigDecimal PER_KM  = new BigDecimal("12.00");
    private static final BigDecimal PER_MIN = new BigDecimal("2.00");

    @Override
    public BigDecimal calculate(double km, long minutes, RideType type) {
        BigDecimal fare = BASE
                .add(PER_KM.multiply(BigDecimal.valueOf(km)))
                .add(PER_MIN.multiply(BigDecimal.valueOf(minutes)));
        return fare.multiply(BigDecimal.valueOf(type.fareMultiplier()))
                   .setScale(2, RoundingMode.HALF_UP);
    }
}

/** Decorator: surge modifies ANY base strategy instead of forking it. */
public class SurgeFareDecorator implements FarePricingStrategy {
    private final FarePricingStrategy delegate;
    private final double surgeFactor;

    public SurgeFareDecorator(FarePricingStrategy delegate, double surgeFactor) {
        this.delegate = delegate; this.surgeFactor = surgeFactor;
    }

    @Override
    public BigDecimal calculate(double km, long minutes, RideType type) {
        return delegate.calculate(km, minutes, type)
                       .multiply(BigDecimal.valueOf(surgeFactor))
                       .setScale(2, RoundingMode.HALF_UP);
    }
}
```

### RideService — the matching engine

```java
public class RideService {

    // Initialization-on-demand holder: lazy, thread-safe, lock-free.
    private static class Holder { static final RideService INSTANCE = new RideService(); }
    public static RideService getInstance() { return Holder.INSTANCE; }

    private static final long OFFER_TIMEOUT_MS = 15_000;

    private final Map<String, Driver> drivers = new ConcurrentHashMap<>();
    private final Map<String, Ride>   rides   = new ConcurrentHashMap<>();
    // driverId -> the single offer that driver is currently deciding on
    private final Map<String, RideOffer> pendingOffers = new ConcurrentHashMap<>();

    private final ExecutorService matchingPool = Executors.newCachedThreadPool();
    private final List<RideObserver> observers = new CopyOnWriteArrayList<>();

    private volatile DriverMatchingStrategy matchingStrategy = new NearestDriverStrategy();
    private volatile FarePricingStrategy fareStrategy = new StandardFareStrategy();
    private volatile PaymentService paymentService = new MockPaymentGateway();

    private RideService() { }

    // ---------- Rider-facing ----------

    public Ride requestRide(Rider rider, Location pickup, Location dest, RideType type) {
        Ride ride = new Ride(UUID.randomUUID().toString(), rider, pickup, dest, type);
        rides.put(ride.id(), ride);
        notifyAll(ride);
        matchingPool.submit(() -> matchDriver(ride));   // matching is async by nature
        return ride;
    }

    public void cancelRide(String rideId) {
        Ride ride = requireRide(rideId);
        ride.cancel().ifPresent(freed ->
                // Driver was already assigned: release them for new requests.
                freed.tryTransition(DriverStatus.ON_TRIP, DriverStatus.AVAILABLE));
        notifyAll(ride);
        // If matching is mid-search, its next loop check sees CANCELLED and stops.
    }

    // ---------- The matching loop ----------

    private void matchDriver(Ride ride) {
        List<Driver> candidates =
                matchingStrategy.rank(drivers.values(), ride.pickup(), ride.type());

        for (Driver candidate : candidates) {
            if (ride.statusSnapshot() == RideStatus.CANCELLED) return;

            // THE claim: among all concurrent matchers eyeing this driver,
            // exactly one CAS succeeds. No locks, no deadlock, losers move on.
            if (!candidate.tryTransition(DriverStatus.AVAILABLE, DriverStatus.OFFERED)) {
                continue;   // someone else claimed them between rank() and now
            }

            RideOffer offer = new RideOffer(ride, candidate);
            pendingOffers.put(candidate.id(), offer);
            // (real system: push notification to driver app here)

            RideOffer.OfferState result;
            try {
                result = offer.awaitResolution(OFFER_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                releaseDriver(candidate);
                return;
            } finally {
                pendingOffers.remove(candidate.id());
            }

            if (result == RideOffer.OfferState.ACCEPTED) {
                if (ride.assignDriver(candidate)) {
                    candidate.tryTransition(DriverStatus.OFFERED, DriverStatus.ON_TRIP);
                    notifyAll(ride);
                    return;                           // matched!
                }
                // Ride was cancelled while the driver was deciding —
                // assignment refused; free the driver and stop.
                releaseDriver(candidate);
                return;
            }
            // DECLINED or EXPIRED: release and try the next candidate.
            releaseDriver(candidate);
        }
        // Exhausted candidates: in a real system, retry with wider radius / surge.
        ride.cancel();
        notifyAll(ride);
    }

    private void releaseDriver(Driver d) {
        d.tryTransition(DriverStatus.OFFERED, DriverStatus.AVAILABLE);
    }

    // ---------- Driver-facing ----------

    public boolean acceptOffer(String driverId) {
        RideOffer offer = pendingOffers.get(driverId);
        return offer != null && offer.accept();   // CAS inside; idempotent on retry
    }

    public boolean declineOffer(String driverId) {
        RideOffer offer = pendingOffers.get(driverId);
        return offer != null && offer.decline();
    }

    public void startRide(String rideId) {
        Ride ride = requireRide(rideId);
        if (ride.start()) notifyAll(ride);
    }

    public void completeRide(String rideId) {
        Ride ride = requireRide(rideId);
        if (!ride.complete()) return;

        long minutes = Duration.between(ride.startedAt(), ride.completedAt()).toMinutes();
        double km = ride.pickup().distanceTo(ride.destination());
        BigDecimal fare = fareStrategy.calculate(km, Math.max(1, minutes), ride.type());

        // Driver becomes available immediately — payment must not block them.
        ride.driver().tryTransition(DriverStatus.ON_TRIP, DriverStatus.AVAILABLE);
        notifyAll(ride);

        // Payment AFTER completion, never under any lock (Airline lesson).
        Payment payment = paymentService.charge(ride.rider(), fare);
        ride.attachPayment(payment);   // PENDING/COMPLETED/FAILED recorded on ride
        notifyAll(ride);
    }

    // ---------- Plumbing ----------

    public void registerDriver(Driver d) { drivers.put(d.id(), d); }
    public void addObserver(RideObserver o) { observers.add(o); }

    private void notifyAll(Ride ride) {
        for (RideObserver o : observers) o.onRideUpdate(ride);
    }

    private Ride requireRide(String id) {
        Ride r = rides.get(id);
        if (r == null) throw new RideNotFoundException(id);
        return r;
    }
}
```

### Observer and payment boundary

```java
public interface RideObserver { void onRideUpdate(Ride ride); }

public class NotificationService implements RideObserver {
    @Override
    public void onRideUpdate(Ride ride) {
        System.out.printf("[NOTIFY] ride=%s status=%s%n", ride.id(), ride.statusSnapshot());
    }
}

public interface PaymentService { Payment charge(Rider rider, BigDecimal amount); }

public class MockPaymentGateway implements PaymentService {
    @Override
    public Payment charge(Rider rider, BigDecimal amount) {
        return new Payment(UUID.randomUUID().toString(), amount, PaymentStatus.COMPLETED);
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Exceptions

| Exception | Thrown when | Design note |
|---|---|---|
| `RideNotFoundException` | Unknown ride id | Unchecked — caller bug, not a recoverable condition. |
| `IllegalStateException` avoided | — | Note we mostly *return* `false` from transitions instead of throwing: in a racy world, "you lost the race" is a normal outcome, not an exception. Throwing would force callers into try/catch control flow. Interviewers respect this distinction. |
| `PaymentFailedException` (or `FAILED` status) | Gateway declines | Ride stays `COMPLETED`; payment status `FAILED` is retried by a background job. Service rendered ≠ rollback-able. |

### Edge cases

1. **All candidates decline / time out** → candidate list exhausted → ride auto-cancelled (real system: widen radius, apply surge, retry). The loop terminates; no infinite spin.
2. **Rider cancels during search** → `cancel()` flips status under the ride lock; the matching loop checks `statusSnapshot()` each iteration and, crucially, `assignDriver()` re-checks under the lock — so even an accept that lands *after* cancellation is refused and the driver is released. There is no window where a cancelled ride holds a driver.
3. **Rider cancels after assignment** → `cancel()` returns the assigned driver, who is CAS'd `ON_TRIP → AVAILABLE`.
4. **Driver tries to go offline while OFFERED/ON_TRIP** → `goOffline()` only CASes from `AVAILABLE`, so it simply fails. The state machine forbids abandoning a live obligation.
5. **Driver accepts twice / accept races decline (two taps, flaky network)** → second resolution loses the offer's CAS and returns `false`. Idempotent by construction.
6. **Zero-duration ride** → `Math.max(1, minutes)` floors billing; degenerate same-point rides bill base fare only.
7. **Stale ranking** → `rank()`'s `AVAILABLE` filter is a *best-effort snapshot*; drivers may be claimed between ranking and claiming. Correctness never depends on the snapshot — only on the CAS. This advisory-check / authoritative-claim split is the chapter's key idea.

### Concurrency analysis

**Shared mutable state and its protection:**

| State | Primitive | Why |
|---|---|---|
| `Driver.status` | `AtomicReference` + CAS | The claim is one atomic decision; lock-free, deadlock-impossible. |
| `Driver.location` | `volatile` ref to immutable `Location` | Single-writer, many-reader; visibility is all we need. |
| `Ride.status/driver/payment` | per-ride `ReentrantLock` | Transitions read-then-write multiple fields — a genuine critical section. Per-ride, so no cross-ride contention. |
| `RideOffer.state` | `AtomicReference` + CAS + `CountDownLatch` | Three racing resolvers, exactly-once semantics, instant wakeup. |
| Registries / `pendingOffers` | `ConcurrentHashMap` | Standard registry toolkit. |
| `observers` | `CopyOnWriteArrayList` | Notify ≫ register. |

**Race walkthrough — two rides, one driver:** Matchers M1 and M2 both rank driver D first. Both call `tryTransition(AVAILABLE, OFFERED)`. The JVM guarantees exactly one CAS wins; the loser's call returns `false` and it continues to its next candidate. No blocking, no retry storm.

**Race walkthrough — accept vs. timeout:** Driver taps Accept at t=14.999s while the matcher's `await` times out at t=15.000s. Both race to CAS `PENDING → {ACCEPTED|EXPIRED}`. Whichever lands first defines reality; the other is a silent no-op. The matcher then reads the *final* state, so it never misreads a last-instant accept as an expiry.

**Deadlock argument:** A thread holds **at most one lock at a time**. `Ride.assignDriver` holds the ride lock but touches the driver only via CAS (lock-free); `completeRide` releases conceptual ride work before calling the payment gateway, and **no lock is ever held across the external payment call** (the Airline rule). With a single-lock-per-thread discipline and lock-free driver claims, a cycle in the wait-for graph is impossible.

**Livelock/starvation note:** A declined driver returns to `AVAILABLE` and may be immediately re-offered the same ride class — acceptable here; a production system adds a per-(driver, ride) decline memo. Worth volunteering before the interviewer asks.

---

## Interviewer follow-ups (with model answers)

**Q1. Offer to the 3 nearest drivers simultaneously, first-accept-wins — what changes?**
Move the exactly-once CAS from the offer to the *ride*: each accepting driver's path calls `ride.assignDriver(driver)`, and the per-ride lock (or an `AtomicReference<Driver>` with CAS) admits exactly one winner. Losers are released `OFFERED → AVAILABLE` and their offers force-expired. The pattern is identical — one atomic point of truth — just relocated from the driver side to the ride side.

**Q2. Scale 10×: ranking scans every driver. Fix it?**
Replace the linear scan with a spatial index: geohash buckets (`ConcurrentHashMap<GeohashCell, Set<Driver>>`) — query the pickup's cell plus 8 neighbors — or a quad-tree. Driver location updates move them between buckets. Note this changes only `NearestDriverStrategy` internals: the Strategy interface means the matching loop is untouched. That's OCP paying rent.

**Q3. Implement surge pricing properly.**
Surge factor = f(demand, supply) per geo-cell, recomputed periodically by a `ScheduledExecutorService` into a `ConcurrentHashMap<GeohashCell, Double>`. `SurgeFareDecorator` reads the cell factor at quote time. Critical UX/consistency point: **quote the fare at request time and store it on the ride**, so the rider pays the quoted surge, not whatever the factor is at completion.

**Q4. Driver app loses connectivity mid-trip — how does the system cope?**
Heartbeats: drivers post location every few seconds; a sweeper (`ScheduledExecutorService`) marks drivers with stale heartbeats as suspect. Mid-trip, the ride continues (the rider's app can also report); for `OFFERED` drivers, the existing offer timeout already self-heals. The design degrades gracefully because every wait is time-boxed.

**Q5. Payment fails at completion — why not roll the ride back, and what do you do?**
The service was already rendered — unlike Airline (pay *before* the seat is yours), here value transfers before money. Rolling back is meaningless. So: ride `COMPLETED`, payment `FAILED`, a retry queue (e.g., `ScheduledExecutorService` + exponential backoff) re-attempts, and the rider's account is flagged until settled. This is the *inverse* of two-phase booking, and articulating that inversion is a strong interview moment.

---

## Transferable lessons

1. **Optimistic CAS claim vs. pessimistic lock** — when the decision is a single atomic step ("free → mine"), CAS on a status enum beats a lock: no deadlock surface, no unlock to forget, losers fail fast. When exclusivity must span multiple operations, use a lock. You now own both ends of this spectrum (Concert Booking locks ↔ Ride-Sharing CAS).
2. **Advisory snapshot, authoritative claim** — filters/rankings over concurrent state are hints; correctness must rest on one atomic point (the CAS). This reappears in any "search then reserve" system: hotel search, inventory checkout, job schedulers.
3. **Time-boxed offers via CAS + latch** — the `PENDING → exactly-one-of{ACCEPTED, DECLINED, EXPIRED}` idiom generalizes to any human-in-the-loop confirmation: delivery-partner assignment, approval workflows, auction "going-once" windows. It's the in-memory cousin of Airline's reserve-with-TTL.
4. **Never hold a lock across external I/O** — reaffirmed: payment happens after all locks are released.

## Suggested next problem

**Food Delivery Service (Swiggy/DoorDash)** — it re-exercises this exact matching engine (restaurant → delivery-partner assignment) *plus* a multi-party order lifecycle (restaurant prep state + partner state + order state), forcing you to coordinate three state machines. Alternatively, **Movie Ticket Booking System** if you'd rather consolidate the multi-resource atomic claim (seats) before adding matching complexity. Recommended order: Movie Ticket Booking → Food Delivery, since Food Delivery then becomes pure synthesis of everything prior.
