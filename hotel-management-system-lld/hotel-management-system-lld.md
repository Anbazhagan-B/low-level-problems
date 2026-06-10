# Hotel Management System — Low Level Design

Target: Senior Java / OOD interview. Plain Java 11+, framework-agnostic.
Scope assumption: single hotel, staff-operated, **specific-room booking with date ranges**, full payment at checkout, in-memory thread-safe storage.

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| F1 | Search available rooms by type and date range |
| F2 | Book a specific room for a guest for [checkInDate, checkOutDate) |
| F3 | Cancel a reservation (only before check-in) |
| F4 | Check-in a guest against a confirmed reservation |
| F5 | Check-out: compute bill (nights × room rate), accept payment, free the room |
| F6 | Manage room catalog: SINGLE, DOUBLE, DELUXE, SUITE, each with a nightly rate |
| F7 | Manage guest profiles (name, email, phone) |
| F8 | Support multiple payment methods: cash, credit card, online/UPI |
| F9 | Reporting hooks: occupancy, revenue per period (design the seam, not full BI) |

### Non-Functional Requirements

| # | Requirement | Design consequence |
|---|-------------|--------------------|
| N1 | **Consistency under concurrency** — two clients must never book the same room for overlapping dates | Atomic check-then-act per room; per-room locking |
| N2 | **Extensibility** — new room types, payment methods, pricing rules without modifying existing code | Strategy pattern, enums with data, OCP |
| N3 | **Scalability** — thousands of rooms/guests, O(1) lookups | `ConcurrentHashMap` keyed by id; design maps cleanly onto a repository/DB layer later |
| N4 | **Valid state transitions** — can't check out a reservation that never checked in | Explicit reservation state machine with guarded transitions |

### Key clarifications & the assumptions taken

1. *Booking a specific room vs. a room type?* → **Specific room** (repo framing). Real hotels book by type and assign at check-in — a likely follow-up, addressed in Step 5.
2. *Date-ranged or boolean availability?* → **Date-ranged**, with check-out date **exclusive** (industry standard: a guest leaving on the 10th and one arriving on the 10th do not conflict).
3. *Cancellation policy?* → allowed only while CONFIRMED, no fee modeling.
4. *Payments?* → single full payment at checkout, pluggable method.

---

## Step 2 — Entities & Relationships

### Core entities

| Entity | Responsibility |
|--------|----------------|
| `Guest` | Identity + contact info. Pure data holder. |
| `Room` | Physical room: number, type, rate, current physical status, **its own lock and its own reservation calendar**. |
| `RoomType` (enum) | SINGLE, DOUBLE, DELUXE, SUITE — carries the base nightly rate (enum-with-data, avoids a parallel price table). |
| `RoomStatus` (enum) | AVAILABLE, OCCUPIED, UNDER_MAINTENANCE — the *physical now-state*, distinct from future bookings. |
| `Reservation` | The booking aggregate: guest + room + date range + status. Owns its state machine. |
| `ReservationStatus` (enum) | CONFIRMED → CHECKED_IN → CHECKED_OUT, or CONFIRMED → CANCELLED. |
| `PaymentStrategy` (interface) | `processPayment(amount)` — implemented by `CashPayment`, `CreditCardPayment`, `UpiPayment`. |
| `Invoice` | Immutable bill produced at checkout: nights, rate, total, payment method. |
| `HotelManagementSystem` | Singleton facade: registries of rooms/guests/reservations + the booking/check-in/check-out use cases. |

### Relationships (with type and rationale)

| Relationship | Type | Why |
|--------------|------|-----|
| Reservation → Guest | **Association** | A reservation references a guest; both have independent lifecycles. Guest exists before and after the reservation. |
| Reservation → Room | **Association** | Same: the room outlives the reservation. |
| Room → its reservation list | **Aggregation** | The room *holds* its calendar of reservations for fast overlap checks, but reservations are also owned/indexed by the system registry — the room doesn't control their lifecycle alone. |
| Reservation → Invoice | **Composition** | An invoice cannot exist without its reservation; created at checkout, dies with it conceptually. |
| HotelManagementSystem → Rooms/Guests/Reservations | **Aggregation** | The facade manages collections of independently meaningful objects. |
| HotelManagementSystem → PaymentStrategy | **Dependency** | The strategy is *passed in* per checkout call, not stored — the loosest coupling, and exactly what Strategy wants. |

**Common modeling mistakes this avoids:**

- ❌ Putting `isBooked: boolean` on Room — collapses the date dimension; breaks F1/F2 immediately.
- ❌ A `Payment` class hierarchy stored *inside* Reservation from the start — payment is behavior, not identity; Strategy + an Invoice record is cleaner.
- ❌ Modeling `Receptionist`/`Admin` actors — out of scope here; mention them, don't build them.
- ❌ One global `synchronized` on the whole system — correct but serializes every booking; interviewers will dock this at senior level.

---

## Step 3 — UML Class Design

```text
                          <<enum>>                    <<enum>>
                          RoomType                    RoomStatus
                          --------                    ----------
                          SINGLE(2500)                AVAILABLE
                          DOUBLE(4000)                OCCUPIED
                          DELUXE(7000)                UNDER_MAINTENANCE
                          SUITE(12000)
                          + getBaseRate(): BigDecimal

 +----------------+        +---------------------------------------+
 |     Guest      |        |                 Room                  |
 +----------------+        +---------------------------------------+
 | - id: String   |        | - roomNumber: String                  |
 | - name: String |        | - type: RoomType                      |
 | - email: String|        | - status: RoomStatus                  |
 | - phone: String|        | - reservations: List<Reservation>     |
 +----------------+        | - lock: ReentrantLock                 |
        ^                  +---------------------------------------+
        |                  | + isAvailable(in,out): boolean        |
        | association      | + addReservation(r) / removeRes(r)    |
        |                  | + lock() / unlock()                   |
        |                  +---------------------------------------+
        |                                   ^
        |                                   | association
 +--------------------------------------------------+
 |                   Reservation                    |
 +--------------------------------------------------+
 | - id: String                                     |
 | - guest: Guest                                   |
 | - room: Room                                     |
 | - checkInDate: LocalDate   (inclusive)           |
 | - checkOutDate: LocalDate  (exclusive)           |
 | - status: ReservationStatus                      |
 +--------------------------------------------------+
 | + overlaps(in, out): boolean                     |
 | + checkIn() / checkOut() / cancel()   <-- guarded state machine
 | + isBlocking(): boolean  (CONFIRMED || CHECKED_IN)|
 +--------------------------------------------------+
        | composition (created at checkout)
        v
 +-------------------+        <<interface>>
 |     Invoice       |        PaymentStrategy
 +-------------------+        +---------------------------+
 | - reservationId   |        | + processPayment(amount)  |
 | - nights: long    |        +---------------------------+
 | - rate, total     |          ^           ^           ^
 +-------------------+          |           |           |
                          CashPayment  CreditCard   UpiPayment
                                        Payment

 +-----------------------------------------------------------+
 |            HotelManagementSystem  <<Singleton>>           |
 +-----------------------------------------------------------+
 | - rooms: ConcurrentHashMap<String, Room>                  |
 | - guests: ConcurrentHashMap<String, Guest>                |
 | - reservations: ConcurrentHashMap<String, Reservation>    |
 +-----------------------------------------------------------+
 | + addRoom(room) / addGuest(guest)                         |
 | + searchRooms(type, in, out): List<Room>                  |
 | + bookRoom(guestId, roomNo, in, out): Reservation         |
 | + cancelReservation(resId): void                          |
 | + checkIn(resId): void                                    |
 | + checkOut(resId, strategy): Invoice                      |
 +-----------------------------------------------------------+
   dependency ----> PaymentStrategy (parameter of checkOut)
```

Multiplicity: `Guest 1 —— * Reservation`, `Room 1 —— * Reservation`, `Reservation 1 —— 0..1 Invoice`.

### Design patterns and why each fits

| Pattern | Where | Why it fits (the interview answer) |
|---------|-------|------------------------------------|
| **Strategy** | `PaymentStrategy` | Payment *method* varies independently of the checkout *algorithm*. New methods (wallet, crypto) are new classes — zero edits to `checkOut` → OCP. Passing it as a method parameter (dependency, not field) keeps Reservation payment-agnostic. |
| **State (enum-guarded)** | `ReservationStatus` + guarded transition methods on Reservation | The lifecycle CONFIRMED→CHECKED_IN→CHECKED_OUT has illegal moves (cancel after check-in). Guarding transitions *inside* Reservation means invalid states are unrepresentable from outside. Full GoF State (one class per state) is overkill for 4 states with trivial per-state behavior — saying that out loud is a senior-level trade-off. |
| **Singleton** | `HotelManagementSystem` | One authoritative registry per JVM so all threads contend on the *same* maps and locks. In Spring you'd drop the pattern and use a default-scoped `@Service` bean — same effect, container-managed, testable. Worth saying in the interview. |
| **Facade** | Same class | Callers see use cases (`bookRoom`, `checkOut`), not the choreography of locks, overlap checks, and registries. |
| **Factory (optional)** | `RoomFactory` | Justified only if room construction grows per-type logic; with enum-carried rates it's currently unnecessary — another trade-off to state, not hide. |

### SOLID mapping

- **S** — Room knows availability; Reservation knows its lifecycle; Invoice knows billing math; payment classes know payment. No god class doing all four.
- **O** — New payment methods and room types require additions, not modifications.
- **L** — Any `PaymentStrategy` substitutes for any other; `checkOut` cannot tell the difference.
- **I** — `PaymentStrategy` is one method; nobody implements methods they don't need.
- **D** — `checkOut` depends on the `PaymentStrategy` abstraction, never on `CreditCardPayment` concretely.

### The two decisions an interviewer will probe

1. **How is the book-same-room race prevented?** → per-room `ReentrantLock` around the *check-availability-then-create-reservation* critical section (Step 5 argues correctness).
2. **Why check-out date exclusive?** → enables same-day turnover (guest A leaves on the 10th, guest B arrives on the 10th) and makes the overlap predicate the clean half-open interval test: `newIn < existingOut && existingIn < newOut`.

---

## Step 4 — Implementation

### Enums

```java
public enum RoomType {
    SINGLE(new BigDecimal("2500")),
    DOUBLE(new BigDecimal("4000")),
    DELUXE(new BigDecimal("7000")),
    SUITE (new BigDecimal("12000"));

    private final BigDecimal baseRate;          // BigDecimal, never double, for money
    RoomType(BigDecimal baseRate) { this.baseRate = baseRate; }
    public BigDecimal getBaseRate() { return baseRate; }
}

public enum RoomStatus { AVAILABLE, OCCUPIED, UNDER_MAINTENANCE }

public enum ReservationStatus { CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED }
```

### Guest

```java
public class Guest {
    private final String id;
    private final String name;
    private final String email;
    private final String phone;

    public Guest(String id, String name, String email, String phone) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.email = email;
        this.phone = phone;
    }
    public String getId()   { return id; }
    public String getName() { return name; }
}
```

### Room — owns its calendar and its lock

```java
public class Room {
    private final String roomNumber;
    private final RoomType type;
    private volatile RoomStatus status = RoomStatus.AVAILABLE;

    // The room's future calendar. Guarded by `lock` — never read/written without it.
    private final List<Reservation> reservations = new ArrayList<>();

    // Fine-grained locking: contention is per-room, not system-wide.
    private final ReentrantLock lock = new ReentrantLock();

    public Room(String roomNumber, RoomType type) {
        this.roomNumber = Objects.requireNonNull(roomNumber);
        this.type = Objects.requireNonNull(type);
    }

    public void lock()   { lock.lock(); }
    public void unlock() { lock.unlock(); }

    /** Caller MUST hold this room's lock. Half-open interval overlap test. */
    public boolean isAvailable(LocalDate in, LocalDate out) {
        if (status == RoomStatus.UNDER_MAINTENANCE) return false;
        return reservations.stream()
                .filter(Reservation::isBlocking)            // CONFIRMED or CHECKED_IN only
                .noneMatch(r -> r.overlaps(in, out));
    }

    /** Caller MUST hold this room's lock. */
    public void addReservation(Reservation r)    { reservations.add(r); }
    public void removeReservation(Reservation r) { reservations.remove(r); }

    public String getRoomNumber() { return roomNumber; }
    public RoomType getType()     { return type; }
    public void setStatus(RoomStatus s) { this.status = s; }
}
```

### Reservation — the guarded state machine

```java
public class Reservation {
    private final String id;
    private final Guest guest;
    private final Room room;
    private final LocalDate checkInDate;    // inclusive
    private final LocalDate checkOutDate;   // exclusive
    private ReservationStatus status = ReservationStatus.CONFIRMED;

    public Reservation(String id, Guest guest, Room room,
                       LocalDate in, LocalDate out) {
        if (!in.isBefore(out))
            throw new IllegalArgumentException("checkIn must be before checkOut");
        if (in.isBefore(LocalDate.now()))
            throw new IllegalArgumentException("cannot book in the past");
        this.id = id; this.guest = guest; this.room = room;
        this.checkInDate = in; this.checkOutDate = out;
    }

    /** Half-open intervals [in, out) overlap iff each starts before the other ends. */
    public boolean overlaps(LocalDate in, LocalDate out) {
        return in.isBefore(checkOutDate) && checkInDate.isBefore(out);
    }

    /** Does this reservation still block the room's calendar? */
    public boolean isBlocking() {
        return status == ReservationStatus.CONFIRMED
            || status == ReservationStatus.CHECKED_IN;
    }

    // ---- State machine: every transition validates its precondition. ----
    // synchronized: two staff terminals must not double-check-in / race
    // a cancel against a check-in on the same reservation.

    public synchronized void checkIn() {
        requireStatus(ReservationStatus.CONFIRMED, "check-in");
        status = ReservationStatus.CHECKED_IN;
        room.setStatus(RoomStatus.OCCUPIED);
    }

    public synchronized void checkOut() {
        requireStatus(ReservationStatus.CHECKED_IN, "check-out");
        status = ReservationStatus.CHECKED_OUT;
        room.setStatus(RoomStatus.AVAILABLE);
    }

    public synchronized void cancel() {
        requireStatus(ReservationStatus.CONFIRMED, "cancel");
        status = ReservationStatus.CANCELLED;
    }

    private void requireStatus(ReservationStatus expected, String action) {
        if (status != expected)
            throw new IllegalStateException(
                "Cannot " + action + " reservation " + id + " in status " + status);
    }

    public synchronized ReservationStatus getStatus() { return status; }
    public String getId() { return id; }
    public Room getRoom() { return room; }
    public LocalDate getCheckInDate()  { return checkInDate; }
    public LocalDate getCheckOutDate() { return checkOutDate; }
}
```

### Payment — Strategy

```java
public interface PaymentStrategy {
    boolean processPayment(BigDecimal amount);
}

public class CashPayment implements PaymentStrategy {
    @Override public boolean processPayment(BigDecimal amount) {
        System.out.println("Collected cash: " + amount);
        return true;
    }
}

public class CreditCardPayment implements PaymentStrategy {
    private final String cardNumber;            // in reality: a token, never raw PAN
    public CreditCardPayment(String cardNumber) { this.cardNumber = cardNumber; }
    @Override public boolean processPayment(BigDecimal amount) {
        // gateway call would go here; return its result
        return true;
    }
}

public class UpiPayment implements PaymentStrategy {
    private final String vpa;
    public UpiPayment(String vpa) { this.vpa = vpa; }
    @Override public boolean processPayment(BigDecimal amount) { return true; }
}
```

### Invoice — immutable billing record

```java
public final class Invoice {
    private final String reservationId;
    private final long nights;
    private final BigDecimal nightlyRate;
    private final BigDecimal total;

    public Invoice(Reservation r) {
        this.reservationId = r.getId();
        this.nights = ChronoUnit.DAYS.between(r.getCheckInDate(), r.getCheckOutDate());
        this.nightlyRate = r.getRoom().getType().getBaseRate();
        this.total = nightlyRate.multiply(BigDecimal.valueOf(nights));
    }
    public BigDecimal getTotal() { return total; }
}
```

### HotelManagementSystem — Singleton facade with the critical sections

```java
public class HotelManagementSystem {

    // Eager, final instance: simplest correct Singleton (JVM class-init is thread-safe).
    private static final HotelManagementSystem INSTANCE = new HotelManagementSystem();
    public static HotelManagementSystem getInstance() { return INSTANCE; }
    private HotelManagementSystem() {}

    private final ConcurrentMap<String, Room> rooms               = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Guest> guests             = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final AtomicLong reservationSeq = new AtomicLong();

    public void addRoom(Room room)   { rooms.put(room.getRoomNumber(), room); }
    public void addGuest(Guest g)    { guests.put(g.getId(), g); }

    /** Read-only search. Briefly locks each candidate room for a consistent read
     *  of its calendar. Results are advisory — booking re-validates atomically. */
    public List<Room> searchRooms(RoomType type, LocalDate in, LocalDate out) {
        List<Room> result = new ArrayList<>();
        for (Room room : rooms.values()) {
            if (room.getType() != type) continue;
            room.lock();
            try {
                if (room.isAvailable(in, out)) result.add(room);
            } finally {
                room.unlock();
            }
        }
        return result;
    }

    /**
     * THE critical section: check availability and create the reservation
     * under the SAME per-room lock, so check-then-act is atomic.
     */
    public Reservation bookRoom(String guestId, String roomNumber,
                                LocalDate in, LocalDate out) {
        Guest guest = require(guests.get(guestId), "guest " + guestId);
        Room room   = require(rooms.get(roomNumber), "room " + roomNumber);

        room.lock();                                 // only ONE lock ever held -> no deadlock
        try {
            if (!room.isAvailable(in, out))
                throw new RoomNotAvailableException(roomNumber, in, out);

            String id = "RES-" + reservationSeq.incrementAndGet();
            Reservation res = new Reservation(id, guest, room, in, out);
            room.addReservation(res);                // both writes inside the lock
            reservations.put(id, res);
            return res;
        } finally {
            room.unlock();
        }
    }

    public void cancelReservation(String resId) {
        Reservation res = require(reservations.get(resId), "reservation " + resId);
        Room room = res.getRoom();
        room.lock();
        try {
            res.cancel();                            // throws if already CHECKED_IN
            room.removeReservation(res);             // calendar slot reopens atomically
        } finally {
            room.unlock();
        }
    }

    public void checkIn(String resId) {
        require(reservations.get(resId), "reservation " + resId).checkIn();
    }

    /** Payment first, state change second: if the gateway fails,
     *  the reservation stays CHECKED_IN and can be retried. */
    public Invoice checkOut(String resId, PaymentStrategy payment) {
        Reservation res = require(reservations.get(resId), "reservation " + resId);
        if (res.getStatus() != ReservationStatus.CHECKED_IN)
            throw new IllegalStateException("Reservation " + resId + " is not checked in");

        Invoice invoice = new Invoice(res);
        if (!payment.processPayment(invoice.getTotal()))
            throw new PaymentFailedException(resId, invoice.getTotal());

        res.checkOut();
        return invoice;
    }

    private static <T> T require(T value, String what) {
        if (value == null) throw new NoSuchElementException(what + " not found");
        return value;
    }
}
```

### Custom exceptions

```java
public class RoomNotAvailableException extends RuntimeException {
    public RoomNotAvailableException(String roomNo, LocalDate in, LocalDate out) {
        super("Room " + roomNo + " unavailable for [" + in + ", " + out + ")");
    }
}

public class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String resId, BigDecimal amount) {
        super("Payment of " + amount + " failed for reservation " + resId);
    }
}
```

### Demo

```java
public class Demo {
    public static void main(String[] args) {
        HotelManagementSystem hms = HotelManagementSystem.getInstance();
        hms.addRoom(new Room("101", RoomType.DELUXE));
        hms.addGuest(new Guest("G1", "Anbu", "anbu@mail.com", "98xxxx"));

        Reservation res = hms.bookRoom("G1", "101",
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(4));

        hms.checkIn(res.getId());
        Invoice bill = hms.checkOut(res.getId(), new UpiPayment("anbu@upi"));
        System.out.println("Total: " + bill.getTotal());   // 3 nights x 7000 = 21000
    }
}
```

**Spring Boot note:** in a real service, `HotelManagementSystem` becomes a singleton-scoped `@Service`, the three maps become Spring Data repositories, and the per-room lock becomes either a DB row lock (`SELECT ... FOR UPDATE`), optimistic locking (`@Version`), or a unique constraint on (room, date) — same atomicity requirement, different enforcement layer.

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Input validation & exceptions

| Failure | Where caught | Behavior |
|---------|--------------|----------|
| Unknown guest / room / reservation id | `require(...)` in the facade | `NoSuchElementException` — fail fast at the boundary |
| `checkIn >= checkOut`, or check-in in the past | `Reservation` constructor | `IllegalArgumentException` — an invalid Reservation can never be constructed |
| Room not free for the range | inside the locked section of `bookRoom` | `RoomNotAvailableException` (domain exception, carries room + range) |
| Illegal lifecycle move (cancel after check-in, double check-out) | `requireStatus(...)` in Reservation | `IllegalStateException` — the state machine is the single gatekeeper |
| Payment gateway declines | `checkOut` | `PaymentFailedException`; reservation **stays CHECKED_IN**, retryable — payment is processed *before* the state transition, so a failure leaves no half-finished checkout |

Design note: all domain exceptions are unchecked. Callers can't meaningfully "handle" a corrupt booking mid-call; they translate to user-facing errors at the API layer (in Spring: `@ControllerAdvice`).

### Edge cases

1. **Same-day turnover** — checkout exclusive: booking [10th, 12th) and [12th, 14th) on the same room is legal. The overlap predicate `in < existingOut && existingIn < out` handles it with zero special-casing.
2. **Zero-night stay** — `in == out` rejected at construction.
3. **Cancel reopens the calendar** — `cancel()` flips status AND `removeReservation` runs under the room lock, so a concurrent `bookRoom` either sees the old blocking reservation or the freed slot — never a torn state. (Even if removal lagged, `isBlocking()` filters CANCELLED out — defense in depth.)
4. **Search-then-book race** — search results are advisory by design. The room may be taken between search and book; `bookRoom` re-validates inside the lock and throws `RoomNotAvailableException` rather than trusting stale reads. This mirrors real booking sites ("this room is no longer available").
5. **Maintenance rooms** — `UNDER_MAINTENANCE` short-circuits `isAvailable`, excluded from search and booking without touching the calendar logic.
6. **No-show** — not modeled; the hook is a scheduled job that cancels CONFIRMED reservations past their check-in date (mention, don't build).

### Concurrency analysis (the senior-level section)

**Shared mutable state:**
1. Each `Room`'s `reservations` list + `status`
2. Each `Reservation`'s `status`
3. The three system registries

**Critical sections and chosen primitives:**

| Critical section | Primitive | Why this one |
|------------------|-----------|--------------|
| *check availability → create reservation* (the check-then-act) | **Per-room `ReentrantLock`** | The race is per-room: bookings on room 101 and 102 must not block each other. One global lock is correct but serializes the hotel; per-room locks give parallelism proportional to room count. `ReentrantLock` over `synchronized` here for `tryLock(timeout)` as a future bounded-wait upgrade. |
| Reservation state transitions | **`synchronized` methods** | Tiny critical sections on a single object's field; intrinsic lock is the simplest correct tool. Protects double check-in from two terminals. |
| Registries | **`ConcurrentHashMap`** | Lock-free reads, fine-grained write striping; `put`/`get` are atomic. No iteration-while-mutating hazards in our usage. |
| Reservation id generation | **`AtomicLong` (CAS)** | Uniqueness without any lock. |

**Why no deadlock:** deadlock needs circular wait across ≥2 locks. `bookRoom`/`cancelReservation` acquire exactly **one** room lock and never take a second room lock or a reservation monitor while holding it that another thread could hold in reverse order. (`cancel()` runs inside the room lock, but no code path takes the reservation monitor first and the room lock second — lock ordering is globally consistent: room → reservation.) No circular wait ⇒ no deadlock.

**Why no race:** the only check-then-act (`isAvailable` → `addReservation`) is entirely inside one lock acquisition. Two threads booking room 101 for overlapping dates serialize on the room's lock; the second sees the first's reservation and fails cleanly.

**Why no livelock/starvation in practice:** no retry loops exist (failed booking throws; it doesn't spin). `ReentrantLock` default is unfair, which can starve under pathological contention — switching to `new ReentrantLock(true)` is the one-line answer if asked.

**The follow-up you should expect — "what about multiple JVM instances?"** In-memory locks don't cross processes. The same atomicity moves to the database: unique constraint on `(room_id, stay_date)` rows, `SELECT ... FOR UPDATE` on the room row, or optimistic `@Version` retry — or a distributed lock (Redis/ZooKeeper) if you must stay out of the DB. The *design* is unchanged: the critical section just gets a bigger lock scope.

---

## Likely interviewer follow-ups (with sketch answers)

1. **"Book by room *type*, assign the physical room at check-in."** → Availability becomes counting: type is available for [in,out) iff for every date, `bookedCount(type, date) < totalRooms(type)`. Keep a per-type, per-date counter map; lock per type. Reservation holds `RoomType`, gains `assignedRoom` set at check-in.
2. **"Add seasonal/weekend pricing."** → `PricingStrategy { BigDecimal price(Room, LocalDate) }` — second Strategy; Invoice sums per-night prices instead of nights × flat rate.
3. **"Notify guests on booking/cancellation."** → Observer: `ReservationListener` registered with the facade; email/SMS observers. Decouples side effects from the transaction.
4. **"10x scale / multiple app servers?"** → see the multi-JVM note above: push the lock into the DB or a distributed lock; shard registries by room id.
5. **"Partial payments / deposits?"** → Invoice becomes a ledger of `Payment` records; Reservation gains `amountPaid`; checkout requires balance == 0.

## Transferable lesson

The skeleton here — **registry singleton/facade + entity-owned state machine + Strategy for the pluggable axis + fine-grained lock around one check-then-act** — is the same skeleton as Parking Lot (spot instead of room), Movie Ticket Booking (seat instead of room, same overlap-free atomic reserve), Car Rental, and Library Management. Master the *half-open interval overlap test* and the *per-resource lock around check-then-act* once; you will reuse both at least four more times in this problem set.

**Next problem suggestion:** Movie Ticket Booking System (Medium) — it re-tests the exact same concurrency pattern with seats, so it's the ideal reinforcement; or Parking Lot if you haven't done the Easy tier's anchor problem yet.
