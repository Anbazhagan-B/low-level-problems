# Airline Management System — Low Level Design (Java)

> Problem source: awesome-low-level-design (Hard). Design is original, framework-agnostic Java 11+.
> Core assumption (locked in Step 1): **two-phase booking** — seats are *reserved* with a timeout,
> *confirmed* on successful payment, and auto-released on expiry or payment failure.

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| F1 | Search flights by **source, destination, departure date**; results show schedule, seat availability and fares. |
| F2 | **Book** a flight: select seats, attach passenger details, pay. Booking is CONFIRMED only on successful payment. |
| F3 | **Seat selection** with seat classes (Economy / Business / First). A seat on a given flight can be held by at most one booking. |
| F4 | **Payments** via multiple methods (card, UPI, wallet, …); failure must release held seats. |
| F5 | **Cancellation & refund**: cancel a confirmed booking → seats return to inventory → refund computed by a rule (time-before-departure tiers). |
| F6 | **Flight operations**: create/maintain schedules, assign an aircraft to a flight, assign crew (with a no-double-booking check). |
| F7 | **Passenger data**: personal details + baggage per passenger per booking. One booking may carry multiple passengers. |
| F8 | **User roles**: Passenger, Staff, Admin — different permitted operations. |
| F9 | **Flight changes**: status transitions (SCHEDULED → BOARDING → DEPARTED → ARRIVED, or CANCELLED) with passenger notification. |

### Non-Functional Requirements

| # | Requirement | Design consequence |
|---|-------------|--------------------|
| N1 | **Concurrency safety** — two users racing for the last seat must not both win. | Per-flight lock guarding atomic check-and-reserve of seats. |
| N2 | **Consistency** — no payment without seats; no seats held forever without payment. | Two-phase booking with reservation **timeout** (scheduled auto-release). |
| N3 | **Extensibility** — add payment methods, refund rules, fare rules, notification channels without touching existing code (OCP). | Strategy interfaces for Payment / Refund / Fare; Observer for notifications. |
| N4 | **Scalability** — object-level design, but no choice should preclude scaling. | Lock granularity is **per flight**, never global; services are stateless over thread-safe repositories. |
| N5 | In-memory, single process (standard LLD-round scope), persistence-ready. | Repositories behind interfaces; entities don't know about storage. |

### Assumptions (stated explicitly, as you would to the interviewer)

- Single-leg flights (no itineraries/connections).
- Fixed base fare per seat class on a flight (no dynamic pricing engine).
- Reservation hold time: 10 minutes (configurable).
- Crew assignment validates only the "crew member not on two overlapping flights" constraint.
- Authentication/authorization is modeled as roles, not implemented (out of LLD scope).

---

## Step 2 — Entities & Relationships

### The one modeling decision that separates strong candidates

A **Seat** is a *physical* property of an **Aircraft** (seat 12A exists on the airframe).
Whether 12A is *available* is a property of a **Flight** (the same aircraft flies many flights).
Conflating these — putting `SeatStatus` on `Seat` — is the most common mistake in this problem.
So we split:

- `Seat` — immutable physical seat: number + class. Lives on the Aircraft.
- `FlightSeat` (per flight, per seat) — mutable: status (AVAILABLE / RESERVED / BOOKED), fare. Lives on the Flight.

Similarly, **User** (an account with a role that operates the system) is distinct from
**Passenger** (a traveler named on a booking — who may not even have an account, e.g. you booking for your parents).

### Entity list with relationship types

| Entity | Key attributes | Relationship | Type & why |
|---|---|---|---|
| `Airline` / `AirlineManagementSystem` | flights, aircraft, bookings | has Flights, Aircraft | **Aggregation** — flights/aircraft are owned by the airline catalog but have independent lifecycles from the facade object. |
| `Aircraft` | tailNumber, model, seats | has `Seat`s | **Composition** — a seat cannot exist without its aircraft; created and destroyed with it. |
| `Seat` | seatNumber, `SeatClass` | — | Immutable value-ish entity. |
| `Flight` | flightNumber, source, dest, departure/arrival time, status, aircraft, flightSeats, crew | references `Aircraft` | **Association/Aggregation** — the aircraft exists independently and serves many flights. |
| `Flight` → `FlightSeat` | | owns per-seat state | **Composition** — a FlightSeat is meaningless outside its flight. |
| `Flight` → `CrewMember` | | assigned crew | **Aggregation** — crew exist independently; assignment is a link, not ownership. |
| `User` (abstract) → `PassengerUser`, `Staff`, `Admin` | id, name, email, `UserRole` | — | **Inheritance** for role-specific operations (kept shallow; role checks could also be data-driven). |
| `Passenger` | name, age, govt ID, `Baggage` list | belongs to a `Booking` | **Composition from Booking's view** — passenger records on a booking live and die with it. |
| `Baggage` | count, weightKg | belongs to `Passenger` | **Composition**. |
| `Booking` | id, flight, passengers, seat assignment map, status, fare, payment | references `Flight` | **Association** — booking points at a flight it does not own. |
| `Payment` | id, amount, method, status | owned by `Booking` | **Composition** — a payment record exists only in the context of its booking. |
| `PaymentStrategy` (interface) | | used by `PaymentService` | **Dependency** — passed in, not stored. |
| `RefundPolicy` (interface) | | used by `BookingService` | **Dependency** (Strategy). |
| `SeatReservation` | bookingId, seat numbers, expiry | transient hold inside `Flight` | **Composition** — internal bookkeeping of the flight's inventory. |
| `FlightObserver` (interface) → `PassengerNotifier` | | observes `Flight` status | **Dependency / Observer** — flight knows only the interface. |

### What's deliberately NOT an entity

- **SeatMap/Inventory as a separate top-level class** — encapsulated inside `Flight`; exposing it invites callers to mutate seat state without the flight's lock (encapsulation = the lock and the data it guards live together).
- **Ticket** — for this scope a confirmed `Booking` *is* the ticket. Add a `Ticket` value object only if per-passenger e-ticket numbers are required.

---

## Step 3 — UML Class Design

### Class diagram (text UML)

```
                          <<interface>>                    <<interface>>
                         PaymentStrategy                   RefundPolicy
                  +pay(amount): PaymentResult       +refundAmount(b: Booking, now): BigDecimal
                        ^         ^                              ^
                        |         |                              |
              CreditCardPayment  UpiPayment            TimeTieredRefundPolicy

 <<enum>> SeatClass {ECONOMY, BUSINESS, FIRST}
 <<enum>> SeatStatus {AVAILABLE, RESERVED, BOOKED}
 <<enum>> BookingStatus {PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED}
 <<enum>> FlightStatus {SCHEDULED, BOARDING, DEPARTED, ARRIVED, CANCELLED}

 +----------------------+ 1     1..* +---------------+
 |       Aircraft       |<>----------|     Seat      |        (composition)
 +----------------------+            +---------------+
 | tailNumber, model    |            | number        |
 | seats: List<Seat>    |            | seatClass     |
 +----------------------+            +---------------+

 +-------------------------------------------+ 1    * +-------------------+
 |                 Flight                    |<>------|    FlightSeat     |  (composition)
 +-------------------------------------------+        +-------------------+
 | flightNumber, source, destination         |        | seat: Seat        |
 | departureTime, arrivalTime                |        | status: SeatStatus|
 | status: FlightStatus                      |        | fare: BigDecimal  |
 | aircraft: Aircraft           (aggregation)|        +-------------------+
 | crew: Set<CrewMember>        (aggregation)|
 | observers: List<FlightObserver>           |------> <<interface>> FlightObserver
 | lock: ReentrantLock  // guards seat state |            +onStatusChange(f, old, new)
 +-------------------------------------------+
 | +reserveSeats(bookingId, seatNos, ttl)    |   <-- the critical section
 | +confirmSeats(bookingId)                  |
 | +releaseSeats(bookingId)                  |
 | +changeStatus(newStatus)                  |
 +-------------------------------------------+

 +---------------------------+ 1   1..* +-------------+ 1   * +---------+
 |          Booking          |<>--------|  Passenger  |<>-----| Baggage |   (composition x2)
 +---------------------------+          +-------------+       +---------+
 | id, flight (association)  |
 | seatAssignment:           |
 |   Map<Passenger,String>   |
 | status: BookingStatus     |
 | totalFare, payment        |
 +---------------------------+

 Services (each depends on repositories + strategies, all stateless):
   FlightSearchService  --uses--> FlightRepository
   BookingService       --uses--> Flight, PaymentService, RefundPolicy, ScheduledExecutorService
   SchedulingService    --uses--> Flight, Aircraft, CrewMember   (assign + conflict check)
   AirlineManagementSystem (Facade, Singleton) --delegates to--> all services
```

Multiplicities worth saying out loud in an interview: `Booking 1 — 1..* Passenger`,
`Passenger 1 — 1 FlightSeat (via the assignment map)`, `Aircraft 1 — * Flight` over time.

### Design patterns — and *why* each one earns its place

| Pattern | Where | Why it fits (the part interviewers want) |
|---|---|---|
| **Strategy** | `PaymentStrategy`, `RefundPolicy` (and `FareCalculator` if pricing grows) | Payment methods and refund rules are the two axes the business changes most often. Strategy isolates each rule behind one interface, so a new UPI provider or a new "festival sale: free cancellation" rule is a new class, not an edit — OCP in its purest form. |
| **State (lightweight, enum-guarded)** | `BookingStatus`, `FlightStatus` transitions | Bookings have strict legal transitions (PENDING→CONFIRMED, PENDING→EXPIRED, CONFIRMED→CANCELLED — never EXPIRED→CONFIRMED). We enforce them in one `transitionTo` method. A full GoF State pattern (one class per state) is overkill at 4 states with trivial per-state behavior — *saying that trade-off aloud scores points*. |
| **Observer** | `Flight` → `FlightObserver` (passenger notifier, crew notifier, ops dashboard) | A flight delay must fan out to parties the Flight shouldn't know about. Observer inverts the dependency: Flight knows an interface, subscribers come and go. |
| **Facade + Singleton** | `AirlineManagementSystem` | Client code (or the interviewer's driver `main`) wants one entry point; the facade hides service wiring. Singleton because there is exactly one system instance per process — in Spring you'd drop the hand-rolled singleton and let the container's default singleton bean scope do this (DI > static `getInstance` for testability). |
| **Factory (method)** | `PaymentStrategyFactory.of(method)` | Maps a `PaymentMethod` enum to a strategy instance so `BookingService` never `new`s a concrete payment class — keeps the dependency arrow pointing at the interface. |
| **Repository** | `FlightRepository`, `BookingRepository` | Thread-safe in-memory maps today; swap for JPA tomorrow without touching services (DIP). |

### SOLID mapping

- **S** — `Flight` owns seat inventory + its lock; `BookingService` owns the booking workflow; `PaymentService` owns charging. No god class.
- **O** — new payment/refund/notification types are additive (Strategy/Observer).
- **L** — any `PaymentStrategy` is substitutable; none strengthens preconditions (all accept any positive amount).
- **I** — `FlightObserver` is a one-method interface; observers aren't forced to implement search or booking concerns.
- **D** — `BookingService` depends on `PaymentStrategy`/`RefundPolicy` abstractions, injected via constructor (framework-agnostic DI; in Spring these become `@Component`s wired by the container).

### The two decisions an interviewer will probe

1. **Where does the seat lock live?** Inside `Flight`, guarding *only that flight's* seat map.
   Rationale: maximal contention is per flight; a global lock serializes the whole airline; a per-seat
   lock forces multi-lock acquisition for group bookings (deadlock surface). One per-flight
   `ReentrantLock` makes multi-seat reservation atomic with zero deadlock risk (single lock).
2. **Why two-phase booking (RESERVED with TTL) instead of book-and-pay atomically?**
   Payment is slow, external, and fallible — you cannot hold a lock across a payment-gateway call.
   So: hold the lock only for the in-memory check-and-reserve (microseconds), release it, then pay
   without the lock, then re-acquire briefly to confirm or release. The TTL guarantees liveness if
   the client vanishes mid-payment.

---

## Step 4 — Implementation

> Focus: enums, the Flight critical section, the booking state machine, strategies, and the
> booking workflow. Getters/boilerplate trimmed for signal.

### Enums

```java
public enum SeatClass { ECONOMY, BUSINESS, FIRST }

public enum SeatStatus { AVAILABLE, RESERVED, BOOKED }

public enum FlightStatus { SCHEDULED, BOARDING, DEPARTED, ARRIVED, CANCELLED }

public enum BookingStatus {
    PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED;

    /** Legal transitions in one place — the lightweight State pattern. */
    public boolean canTransitionTo(BookingStatus next) {
        switch (this) {
            case PENDING_PAYMENT: return next == CONFIRMED || next == EXPIRED || next == CANCELLED;
            case CONFIRMED:       return next == CANCELLED;
            default:              return false; // CANCELLED and EXPIRED are terminal
        }
    }
}
```

### Physical model: Aircraft & Seat

```java
public final class Seat {
    private final String number;       // "12A"
    private final SeatClass seatClass;

    public Seat(String number, SeatClass seatClass) {
        this.number = number;
        this.seatClass = seatClass;
    }
    public String getNumber() { return number; }
    public SeatClass getSeatClass() { return seatClass; }
}

public class Aircraft {
    private final String tailNumber;
    private final String model;
    private final List<Seat> seats;    // composition: built with the aircraft

    public Aircraft(String tailNumber, String model, List<Seat> seats) {
        this.tailNumber = tailNumber;
        this.model = model;
        this.seats = List.copyOf(seats); // defensive, immutable view
    }
    public List<Seat> getSeats() { return seats; }
    public String getTailNumber() { return tailNumber; }
}
```

### Flight — owner of seat inventory and its lock (the heart of the design)

```java
public class Flight {
    private final String flightNumber;
    private final String source;
    private final String destination;
    private final LocalDateTime departureTime;
    private final LocalDateTime arrivalTime;
    private final Aircraft aircraft;                       // aggregation
    private volatile FlightStatus status = FlightStatus.SCHEDULED;

    /** Per-flight mutable seat state. Guarded by `lock`. */
    private final Map<String, FlightSeat> seatMap = new HashMap<>();
    /** Active holds: bookingId -> reservation. Guarded by `lock`. */
    private final Map<String, SeatReservation> reservations = new HashMap<>();
    /** ONE lock per flight: multi-seat reservation is atomic, and there is
        never more than one lock to acquire => structurally deadlock-free. */
    private final ReentrantLock lock = new ReentrantLock();

    private final Set<CrewMember> crew = ConcurrentHashMap.newKeySet();
    private final List<FlightObserver> observers = new CopyOnWriteArrayList<>();

    public Flight(String flightNumber, String source, String destination,
                  LocalDateTime dep, LocalDateTime arr, Aircraft aircraft,
                  Map<SeatClass, BigDecimal> baseFares) {
        this.flightNumber = flightNumber;
        this.source = source;
        this.destination = destination;
        this.departureTime = dep;
        this.arrivalTime = arr;
        this.aircraft = aircraft;
        for (Seat s : aircraft.getSeats()) {
            seatMap.put(s.getNumber(), new FlightSeat(s, baseFares.get(s.getSeatClass())));
        }
    }

    /**
     * Phase 1 of booking: atomically check ALL requested seats and reserve them,
     * or reserve NONE (all-or-nothing for group bookings).
     */
    public SeatReservation reserveSeats(String bookingId, List<String> seatNumbers, Duration ttl) {
        lock.lock();
        try {
            if (status != FlightStatus.SCHEDULED)
                throw new IllegalStateException("Flight " + flightNumber + " not open for booking");

            // 1) validate every seat BEFORE mutating anything
            for (String n : seatNumbers) {
                FlightSeat fs = seatMap.get(n);
                if (fs == null) throw new InvalidSeatException(n);
                if (fs.getStatus() != SeatStatus.AVAILABLE)
                    throw new SeatUnavailableException(n);
            }
            // 2) only now mutate — no partial holds possible
            seatNumbers.forEach(n -> seatMap.get(n).setStatus(SeatStatus.RESERVED));
            SeatReservation r = new SeatReservation(
                    bookingId, List.copyOf(seatNumbers), Instant.now().plus(ttl));
            reservations.put(bookingId, r);
            return r;
        } finally {
            lock.unlock();
        }
    }

    /** Phase 2a: payment succeeded — RESERVED -> BOOKED. Idempotent-safe. */
    public void confirmSeats(String bookingId) {
        lock.lock();
        try {
            SeatReservation r = reservations.remove(bookingId);
            if (r == null) throw new ReservationExpiredException(bookingId); // TTL beat us
            r.seatNumbers().forEach(n -> seatMap.get(n).setStatus(SeatStatus.BOOKED));
        } finally {
            lock.unlock();
        }
    }

    /** Phase 2b: payment failed / TTL expired / booking cancelled — back to AVAILABLE. */
    public void releaseSeats(String bookingId, Collection<String> bookedSeats) {
        lock.lock();
        try {
            SeatReservation r = reservations.remove(bookingId);
            Collection<String> toFree = (r != null) ? r.seatNumbers() : bookedSeats;
            if (toFree != null)
                toFree.forEach(n -> seatMap.get(n).setStatus(SeatStatus.AVAILABLE));
        } finally {
            lock.unlock();
        }
    }

    /** True if the TTL reaper should still act for this booking. */
    public boolean hasActiveReservation(String bookingId) {
        lock.lock();
        try { return reservations.containsKey(bookingId); }
        finally { lock.unlock(); }
    }

    public void changeStatus(FlightStatus next) {
        FlightStatus old;
        synchronized (this) {            // status changes are rare; cheap lock is fine
            old = this.status;
            this.status = next;
        }
        observers.forEach(o -> o.onStatusChange(this, old, next)); // Observer fan-out
    }

    public void addObserver(FlightObserver o) { observers.add(o); }

    public int availableSeatCount() {
        lock.lock();
        try {
            return (int) seatMap.values().stream()
                    .filter(fs -> fs.getStatus() == SeatStatus.AVAILABLE).count();
        } finally { lock.unlock(); }
    }
    // getters: flightNumber, source, destination, departureTime, status ...
}

public class FlightSeat {                     // package-private mutator: only Flight touches status
    private final Seat seat;
    private final BigDecimal fare;
    private SeatStatus status = SeatStatus.AVAILABLE;

    FlightSeat(Seat seat, BigDecimal fare) { this.seat = seat; this.fare = fare; }
    public SeatStatus getStatus() { return status; }
    void setStatus(SeatStatus s) { this.status = s; }
    public BigDecimal getFare() { return fare; }
    public Seat getSeat() { return seat; }
}

/** Immutable record of a temporary hold. */
public record SeatReservation(String bookingId, List<String> seatNumbers, Instant expiresAt) {}

public interface FlightObserver {
    void onStatusChange(Flight flight, FlightStatus oldStatus, FlightStatus newStatus);
}
```

### People

```java
public abstract class User {
    protected final String id;
    protected final String name;
    protected final String email;
    protected final UserRole role;
    protected User(String id, String name, String email, UserRole role) {
        this.id = id; this.name = name; this.email = email; this.role = role;
    }
}
public enum UserRole { PASSENGER, STAFF, ADMIN }

public class Passenger {                       // traveler ON a booking, not necessarily a User
    private final String name;
    private final int age;
    private final String govtIdNumber;
    private final List<Baggage> baggage = new ArrayList<>();
    public Passenger(String name, int age, String govtIdNumber) { /* assign */
        this.name = name; this.age = age; this.govtIdNumber = govtIdNumber;
    }
    public void addBaggage(Baggage b) { baggage.add(b); }
    public String getName() { return name; }
}

public record Baggage(int pieces, double weightKg) {}
public record CrewMember(String id, String name, String crewRole) {}
```

### Booking — state machine enforced in one place

```java
public class Booking {
    private final String id;
    private final Flight flight;                              // association
    private final Map<Passenger, String> seatAssignment;      // passenger -> seat number
    private final BigDecimal totalFare;
    private final Instant createdAt = Instant.now();
    private BookingStatus status = BookingStatus.PENDING_PAYMENT;
    private Payment payment;

    public Booking(String id, Flight flight,
                   Map<Passenger, String> seatAssignment, BigDecimal totalFare) {
        this.id = id; this.flight = flight;
        this.seatAssignment = Map.copyOf(seatAssignment);
        this.totalFare = totalFare;
    }

    /** All state changes funnel here => illegal transitions are impossible,
        and `synchronized` makes confirm-vs-expire races resolve cleanly. */
    public synchronized void transitionTo(BookingStatus next) {
        if (!status.canTransitionTo(next))
            throw new IllegalBookingStateException(status, next);
        this.status = next;
    }
    public synchronized BookingStatus getStatus() { return status; }
    public Collection<String> seatNumbers() { return seatAssignment.values(); }
    public Flight getFlight() { return flight; }
    public BigDecimal getTotalFare() { return totalFare; }
    public String getId() { return id; }
    public void attachPayment(Payment p) { this.payment = p; }
}
```

### Strategies: payment & refund

```java
public interface PaymentStrategy {
    PaymentResult pay(BigDecimal amount);     // would call a gateway; slow & fallible
}
public record PaymentResult(boolean success, String transactionId, String failureReason) {}

public class CreditCardPayment implements PaymentStrategy {
    private final String cardToken;
    public CreditCardPayment(String cardToken) { this.cardToken = cardToken; }
    @Override public PaymentResult pay(BigDecimal amount) {
        // gateway call stub
        return new PaymentResult(true, UUID.randomUUID().toString(), null);
    }
}
public class UpiPayment implements PaymentStrategy {
    private final String vpa;
    public UpiPayment(String vpa) { this.vpa = vpa; }
    @Override public PaymentResult pay(BigDecimal amount) {
        return new PaymentResult(true, UUID.randomUUID().toString(), null);
    }
}

public interface RefundPolicy {
    BigDecimal refundAmount(Booking booking, Instant now);
}
/** 100% if >24h before departure, 50% if >2h, else 0. New rules = new class (OCP). */
public class TimeTieredRefundPolicy implements RefundPolicy {
    @Override public BigDecimal refundAmount(Booking b, Instant now) {
        Instant dep = b.getFlight().getDepartureTime().atZone(ZoneId.systemDefault()).toInstant();
        Duration toDeparture = Duration.between(now, dep);
        if (toDeparture.toHours() >= 24) return b.getTotalFare();
        if (toDeparture.toHours() >= 2)
            return b.getTotalFare().multiply(new BigDecimal("0.50"));
        return BigDecimal.ZERO;
    }
}
```

### BookingService — the two-phase workflow

```java
public class BookingService {
    private static final Duration HOLD_TTL = Duration.ofMinutes(10);

    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final RefundPolicy refundPolicy;                       // injected (DIP)
    private final ScheduledExecutorService reaper =                // releases expired holds
            Executors.newSingleThreadScheduledExecutor();

    public BookingService(RefundPolicy refundPolicy) { this.refundPolicy = refundPolicy; }

    /** Phase 1: reserve seats, create PENDING booking, arm the TTL. */
    public Booking createBooking(Flight flight, Map<Passenger, String> seatChoice) {
        String bookingId = UUID.randomUUID().toString();
        List<String> seats = List.copyOf(seatChoice.values());

        // atomic all-or-nothing hold inside the flight's lock
        flight.reserveSeats(bookingId, seats, HOLD_TTL);

        BigDecimal fare = seats.stream()
                .map(n -> flight.fareOf(n))                        // sums FlightSeat fares
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Booking booking = new Booking(bookingId, flight, seatChoice, fare);
        bookings.put(bookingId, booking);

        // liveness: if the user never pays, free the seats automatically
        reaper.schedule(() -> expireIfStillPending(booking), HOLD_TTL.toMillis(), TimeUnit.MILLISECONDS);
        return booking;
    }

    /** Phase 2: pay WITHOUT holding the flight lock, then confirm or release. */
    public Booking confirmBooking(String bookingId, PaymentStrategy paymentStrategy) {
        Booking b = require(bookingId);
        PaymentResult result = paymentStrategy.pay(b.getTotalFare());   // slow call, no locks held

        if (!result.success()) {
            b.transitionTo(BookingStatus.CANCELLED);
            b.getFlight().releaseSeats(bookingId, null);
            throw new PaymentFailedException(result.failureReason());
        }
        try {
            b.getFlight().confirmSeats(bookingId);    // throws if TTL reaper got there first
            b.transitionTo(BookingStatus.CONFIRMED);
            b.attachPayment(new Payment(result.transactionId(), b.getTotalFare()));
            return b;
        } catch (ReservationExpiredException e) {
            // we charged but lost the seats: compensate (refund) — say this out loud in interviews
            refund(result.transactionId(), b.getTotalFare());
            b.transitionTo(BookingStatus.EXPIRED);
            throw e;
        }
    }

    public BigDecimal cancelBooking(String bookingId) {
        Booking b = require(bookingId);
        b.transitionTo(BookingStatus.CANCELLED);      // synchronized; rejects double-cancel
        b.getFlight().releaseSeats(bookingId, b.seatNumbers());
        BigDecimal refund = refundPolicy.refundAmount(b, Instant.now());
        // trigger refund via payment record ...
        return refund;
    }

    private void expireIfStillPending(Booking b) {
        // Only act if the hold still exists; transitionTo is synchronized, so a racing
        // confirm either wins (we no-op) or loses (we expire) — never both.
        if (b.getStatus() == BookingStatus.PENDING_PAYMENT
                && b.getFlight().hasActiveReservation(b.getId())) {
            try {
                b.transitionTo(BookingStatus.EXPIRED);
                b.getFlight().releaseSeats(b.getId(), null);
            } catch (IllegalBookingStateException ignored) { /* confirm won the race */ }
        }
    }

    private Booking require(String id) {
        Booking b = bookings.get(id);
        if (b == null) throw new BookingNotFoundException(id);
        return b;
    }
    private void refund(String txnId, BigDecimal amt) { /* gateway refund stub */ }
}
```

### Search, scheduling, facade

```java
public class FlightSearchService {
    private final Map<String, Flight> flights;     // shared repo (ConcurrentHashMap)
    public FlightSearchService(Map<String, Flight> flights) { this.flights = flights; }

    public List<Flight> search(String source, String destination, LocalDate date) {
        return flights.values().stream()
                .filter(f -> f.getSource().equalsIgnoreCase(source))
                .filter(f -> f.getDestination().equalsIgnoreCase(destination))
                .filter(f -> f.getDepartureTime().toLocalDate().equals(date))
                .filter(f -> f.getStatus() == FlightStatus.SCHEDULED)
                .sorted(Comparator.comparing(Flight::getDepartureTime))
                .collect(Collectors.toList());
    }
}

public class SchedulingService {
    /** crewId -> flights they're on; used for the overlap check. */
    private final Map<String, List<Flight>> crewAssignments = new ConcurrentHashMap<>();

    public synchronized void assignCrew(CrewMember c, Flight f) {
        List<Flight> existing = crewAssignments.computeIfAbsent(c.id(), k -> new ArrayList<>());
        boolean clash = existing.stream().anyMatch(other -> overlaps(other, f));
        if (clash) throw new CrewConflictException(c.id(), f.getFlightNumber());
        existing.add(f);
        f.getCrew().add(c);
    }
    private boolean overlaps(Flight a, Flight b) {
        return !a.getArrivalTime().isBefore(b.getDepartureTime())
            && !b.getArrivalTime().isBefore(a.getDepartureTime());
    }
}

/** Facade + Singleton entry point. In Spring, delete getInstance() and make
    each service a singleton-scoped @Component wired by constructor injection. */
public class AirlineManagementSystem {
    private static final AirlineManagementSystem INSTANCE = new AirlineManagementSystem();
    public static AirlineManagementSystem getInstance() { return INSTANCE; }

    private final Map<String, Flight> flights = new ConcurrentHashMap<>();
    private final FlightSearchService searchService = new FlightSearchService(flights);
    private final BookingService bookingService = new BookingService(new TimeTieredRefundPolicy());
    private final SchedulingService schedulingService = new SchedulingService();

    private AirlineManagementSystem() {}

    public void addFlight(Flight f) { flights.put(f.getFlightNumber(), f); }
    public List<Flight> searchFlights(String src, String dst, LocalDate d) {
        return searchService.search(src, dst, d);
    }
    public Booking book(Flight f, Map<Passenger, String> seats) {
        return bookingService.createBooking(f, seats);
    }
    public Booking pay(String bookingId, PaymentStrategy ps) {
        return bookingService.confirmBooking(bookingId, ps);
    }
    public BigDecimal cancel(String bookingId) { return bookingService.cancelBooking(bookingId); }
}
```

### Custom exceptions

```java
public class SeatUnavailableException extends RuntimeException {
    public SeatUnavailableException(String seat) { super("Seat not available: " + seat); }
}
public class InvalidSeatException extends RuntimeException {
    public InvalidSeatException(String seat) { super("No such seat: " + seat); }
}
public class ReservationExpiredException extends RuntimeException {
    public ReservationExpiredException(String id) { super("Hold expired for booking " + id); }
}
public class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String reason) { super("Payment failed: " + reason); }
}
public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(String id) { super("No booking: " + id); }
}
public class IllegalBookingStateException extends RuntimeException {
    public IllegalBookingStateException(BookingStatus from, BookingStatus to) {
        super("Illegal transition " + from + " -> " + to);
    }
}
public class CrewConflictException extends RuntimeException {
    public CrewConflictException(String crewId, String flight) {
        super("Crew " + crewId + " already assigned to an overlapping flight (" + flight + ")");
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Input validation & error strategy

| Failure | Handling | Why this way |
|---|---|---|
| Unknown seat number | `InvalidSeatException` before any mutation | Validate-then-mutate inside the lock ⇒ no partial group holds. |
| Seat already RESERVED/BOOKED | `SeatUnavailableException` (whole request fails) | All-or-nothing semantics for group bookings; caller can re-pick. |
| Booking flight not SCHEDULED (cancelled/departed) | `IllegalStateException` at reserve time | Flight status is checked *inside* the same critical section as seats — no TOCTOU gap. |
| Payment declined | Booking → CANCELLED, seats released | Failure path is symmetric with the success path; nothing leaks. |
| Cancel a non-existent / already-cancelled booking | `BookingNotFoundException` / `IllegalBookingStateException` | The enum-guarded `transitionTo` makes **double-cancel (double-refund) structurally impossible**. |
| Crew assigned to overlapping flights | `CrewConflictException` | Interval-overlap check at assignment time. |

All exceptions are unchecked domain exceptions: callers can't meaningfully "handle" a taken seat
mid-call; they surface to the API layer (in Spring: one `@ControllerAdvice` mapping them to 4xx).

### Edge cases worth naming in the interview

1. **Last-seat race** — two threads call `reserveSeats` for 12A; the per-flight lock serializes them; the loser fails the AVAILABLE check. Deterministic, no double-sell.
2. **Pay-vs-expire race** — TTL fires while the gateway call is in flight. Both paths funnel through `confirmSeats`/`releaseSeats` under the flight lock and `transitionTo` under the booking monitor; exactly one wins. If payment won the money but the reaper won the seats, we **compensate with a refund** (a mini-saga — say that word, it bridges to system design rounds).
3. **Group booking partial availability** — 3 of 4 seats free ⇒ reserve none (validate-all-then-mutate).
4. **Flight cancelled with confirmed bookings** — `changeStatus(CANCELLED)` fans out via Observer; a `RefundOnCancellationObserver` can auto-refund 100% bypassing the time-tier policy.
5. **Booking-window boundary** — reserving at departure-time minus epsilon: the status check inside the lock is the single source of truth; close the flight by flipping status, no separate flag to drift.
6. **Clock skew on TTL** — single-process `Instant.now()` is consistent; in a distributed version the hold moves to Redis `SET ... NX PX` (mention only if asked).

### Concurrency analysis (the section the interviewer grades hardest)

**Shared mutable state inventory**

| State | Guarded by |
|---|---|
| `Flight.seatMap` + `Flight.reservations` | the flight's `ReentrantLock` — data and its lock co-located in one class, so no caller can bypass it |
| `Booking.status` | the booking's monitor (`synchronized transitionTo`) |
| `bookings`, `flights` registries | `ConcurrentHashMap` (point reads/writes only) |
| `Flight.status` | `volatile` + brief `synchronized` swap (rare writes, many reads) |
| `observers`, `crew` | `CopyOnWriteArrayList` / concurrent set (read-heavy, tiny write rate) |

**Critical sections** — deliberately tiny:
- `reserveSeats`: validate N seats + flip N statuses. Microseconds.
- `confirmSeats` / `releaseSeats`: flip statuses, remove hold.
- **Never** held across payment I/O — that's the whole point of two-phase booking.

**Primitive choices, justified**
- `ReentrantLock` per flight over `synchronized`: same semantics here, but it leaves the door open
  for `tryLock(timeout)` (fail fast under contention) and fairness if one flight becomes a hotspot.
- `ConcurrentHashMap` for registries: no compound check-then-act on them (those live inside Flight),
  so lock-free maps suffice.
- `ScheduledExecutorService` reaper over per-booking timer threads: O(1) threads, holds expire even
  if the client disappears ⇒ liveness.
- Why not per-SEAT locks? A 4-seat group booking would need 4 locks ⇒ deadlock risk unless you
  impose global lock ordering; the gain is negligible because the per-flight section is nanos-to-micros.
- Why not optimistic/CAS (`AtomicReference` per seat)? Multi-seat atomicity ("all 4 or none") is
  awkward with independent CAS; you'd hand-roll a mini-transaction. The coarse lock is simpler and
  contention is naturally partitioned per flight.

**Freedom-from arguments**
- **Race-free**: every read-modify-write of seat state happens under exactly one lock; booking status
  changes happen under exactly one monitor; cross-checks (`hasActiveReservation`) re-validate under
  the lock rather than trusting stale reads.
- **Deadlock-free**: no code path ever holds two locks simultaneously (flight lock and booking monitor
  are never nested — check the call graph: `transitionTo` is called *before or after* flight-lock
  sections, never inside). Single-lock acquisition ⇒ no circular wait ⇒ no deadlock, by construction.
- **Livelock/starvation-free**: no retry loops; `ReentrantLock` queues waiters; TTL guarantees a
  crashed payer can't starve everyone else of seats.

### Likely interviewer follow-ups (with the one-breath answers)

1. **"Make seat holds survive a process restart."** Move `reservations` to Redis with `SET bookingId NX PX <ttl>` per seat key; the in-process lock becomes a distributed lock or, better, atomic Lua/`MULTI` over the seat keys. Booking state goes to a DB with optimistic version columns.
2. **"Add dynamic pricing."** Introduce `FarePolicy` strategy on Flight (`BigDecimal fareFor(FlightSeat, demandSnapshot)`); `FlightSeat.fare` becomes computed-at-reserve and frozen into the Booking. No other class changes — that's the OCP payoff.
3. **"Support multi-leg itineraries."** New `Itinerary` aggregate holding ordered `Booking`s; reservation across legs becomes a saga: reserve leg by leg, compensate (release) on first failure. Per-flight locks acquired strictly in leg order if ever held together — or better, never held together.
4. **"10x flights and traffic?"** Nothing global to shard-split: contention is already partitioned per flight. Registries shard by flight number; search gets an index `(source, destination, date) -> flights` instead of a full scan.
5. **"Why not the full GoF State pattern for Booking?"** Four states, trivial behavior — a transition table on the enum gives the same illegal-transition safety with a quarter of the classes. I'd upgrade to State objects when per-state *behavior* (not just legality) diverges, e.g. state-specific cancellation fees.

### Transferable lesson

The reusable kernel here is **"reserve → external side-effect → confirm/compensate, with a TTL"** plus
**"the lock lives with the data it guards."** The same shape solves Movie Ticket Booking (BookMyShow),
Hotel Booking, Cab Booking, and E-commerce checkout — only the nouns change. Strategy-for-money-rules
(payment/refund/fare) and Observer-for-fan-out likewise recur across nearly every Medium/Hard problem.
