# Concert Ticket Booking System — Low Level Design

*Scope agreed: direct `AVAILABLE → BOOKED` seat lifecycle (no HELD/hold-timeout state), no waiting list. Single-process, in-memory, plain Java 11+.*

---

## Step 1 — Requirements

### Functional Requirements

1. **View concerts & seating** — list concerts; for a concert, view its seats with seat number, type, price, and live availability.
2. **Search concerts** — filter by artist, venue, and date/time (any combination; null criteria are ignored).
3. **Select & book seats** — a user books one or more *specific* seats for a concert in a single atomic operation, pays, and receives a `Booking`.
4. **No double-booking** — two concurrent requests for the same seat: exactly one succeeds, the other gets a clean failure.
5. **Fairness** — under contention, requests are served roughly in arrival order; no thread is starved.
6. **Payment** — booking is finalized only after successful payment; on payment failure all seats in the request are released.
7. **Confirmation** — on success the system confirms the booking and notifies the user (notification channel is pluggable).
8. **Cancellation** — a confirmed booking can be cancelled; its seats return to `AVAILABLE`.

### Non-Functional Requirements

- **Thread-safety / atomicity** — a multi-seat booking is all-or-nothing. Partial success (2 of 3 seats booked) is a correctness bug, not a degraded mode.
- **Liveness** — the locking scheme must be demonstrably free of deadlock; fairness handled at the primitive level (fair lock / FIFO monitor queueing argument).
- **Extensibility (OCP)** — adding a seat type, payment method, or notification channel must not require editing existing classes; these vary behind enums/interfaces.
- **Encapsulation** — seat state transitions happen only inside `Seat` via guarded methods; no caller ever sets `status` directly.
- **Scale posture** — in-memory by design (LLD scope). The same invariants map to a distributed system as DB row-level locks (`SELECT ... FOR UPDATE`) or optimistic versioning; called out in Step 5 but not designed here.

---

## Step 2 — Entities & Relationships

| Entity | Role | Relationship | Type & Why |
|---|---|---|---|
| `ConcertTicketBookingSystem` | Singleton facade: registry of concerts and bookings, owns the booking workflow | manages `Concert`, `Booking` | **Aggregation** — the system holds collections of concerts/bookings, but a `Concert` is conceptually independent of the registry (it could exist in another container). |
| `Concert` | A show: artist, venue, date/time, and its seats | contains `Seat` | **Composition** — seats are created with, and have no meaning outside, their concert. Delete the concert, the seats go with it. Strongest ownership in the model. |
| `Seat` | One bookable unit; owns its own state machine | uses `SeatType`, `SeatStatus` | **Dependency** on the enums (type usage, not ownership). |
| `Booking` | Immutable-ish record of *who* booked *which seats* of *which concert*, with status lifecycle | references `User`, `Concert`, `List<Seat>` | **Association** — a booking points at user/concert/seats but owns none of them; they all outlive any single booking. |
| `User` | Identity + contact for notification | — | Plain entity; referenced by `Booking`. |
| `SeatType` (enum) | `REGULAR`, `PREMIUM`, `VIP` | — | Drives pricing/display; new tiers = new constant, no class edits (OCP). |
| `SeatStatus` (enum) | `AVAILABLE`, `BOOKED` | — | The simplified two-state lifecycle. |
| `BookingStatus` (enum) | `PENDING`, `CONFIRMED`, `CANCELLED` | — | Booking-level lifecycle: `PENDING` between seat capture and payment, then `CONFIRMED` or (on cancel) `CANCELLED`. |

**Modeling judgment calls**

- **`Venue` stays a `String`.** With no seating-map reuse or venue-level queries in scope, promoting it to a class is over-modeling. Say this out loud in an interview — knowing what *not* to model scores points.
- **State lives in two places deliberately.** `Seat.status` answers "can this physical seat be sold *right now*?"; `Booking.status` answers "where is this purchase in its lifecycle?". Collapsing them is a classic mistake — cancellation needs both.
- **`Booking` references seats rather than copying seat data**, so cancellation can release the *actual* seat objects.

---

## Step 3 — UML Class Design

```
┌──────────────────────────────────────────────────────────┐
│            ConcertTicketBookingSystem        «Singleton» │
├──────────────────────────────────────────────────────────┤
│ - INSTANCE : holder-idiom singleton                      │
│ - concerts : Map<String, Concert>      (ConcurrentHashMap)│
│ - bookings : Map<String, Booking>      (ConcurrentHashMap)│
│ - paymentProcessor : PaymentProcessor                    │
│ - notifier : NotificationService                         │
├──────────────────────────────────────────────────────────┤
│ + getInstance() : ConcertTicketBookingSystem             │
│ + addConcert(Concert)                                    │
│ + getConcert(id) : Concert                               │
│ + searchConcerts(artist, venue, dateTime) : List<Concert>│
│ + bookTickets(User, Concert, List<Seat>) : Booking       │
│ + cancelBooking(bookingId)                               │
└───────────────┬─────────────────────────┬────────────────┘
                │ manages 1..*            │ manages 0..*
                ▼                         ▼
┌───────────────────────────┐   ┌─────────────────────────────┐
│         Concert           │   │          Booking            │
├───────────────────────────┤   ├─────────────────────────────┤
│ - id, artist, venue : Str │   │ - id : String               │
│ - dateTime : LocalDateTime│   │ - user : User          ─────┼──► User
│ - seats : List<Seat>      │   │ - concert : Concert    ─────┼──► Concert
├───────────────────────────┤   │ - seats : List<Seat>        │
│ + getAvailableSeats()     │   │ - totalPrice : double       │
│   : List<Seat>            │   │ - status : BookingStatus    │
└───────────┬───────────────┘   ├─────────────────────────────┤
            │ contains 1..*     │ + confirm()  (pkg-private)  │
            │ (composition ◆)   │ + cancel()   (pkg-private)  │
            ▼                   └─────────────────────────────┘
┌───────────────────────────┐
│           Seat            │   ┌──────────────┐ ┌─────────────────┐
├───────────────────────────┤   │ «enum»       │ │ «enum»          │
│ - id, seatNumber : String │──►│ SeatType     │ │ SeatStatus      │
│ - type : SeatType         │   │ REGULAR      │ │ AVAILABLE       │
│ - price : double          │   │ PREMIUM      │ │ BOOKED          │
│ - status : SeatStatus     │   │ VIP          │ └─────────────────┘
├───────────────────────────┤   └──────────────┘
│ + book()    «synchronized»│   ┌──────────────────────────────┐
│ + release() «synchronized»│   │ «enum» BookingStatus         │
└───────────────────────────┘   │ PENDING, CONFIRMED, CANCELLED│
                                └──────────────────────────────┘

PaymentProcessor «interface»          NotificationService «interface»
  + process(amount) : boolean           + notify(User, message)
  ▲ implemented by e.g.                 ▲ implemented by
  CreditCardProcessor, UpiProcessor     EmailNotifier, SmsNotifier
```

### SOLID mapping

- **SRP** — `Seat` guards one seat's state; `Concert` is a catalog entry; `Booking` is a purchase record; the system class only *orchestrates*. Payment and notification are extracted into their own abstractions rather than inlined into `bookTickets`.
- **OCP** — new payment method or notification channel = new implementation of an interface; `bookTickets` never changes. New seat tier = new enum constant.
- **LSP** — any `PaymentProcessor` is substitutable; the workflow depends only on the `boolean process(...)` contract.
- **ISP** — interfaces are single-method and role-shaped; no fat "ISystemService".
- **DIP** — the high-level booking workflow depends on `PaymentProcessor` / `NotificationService` abstractions, with concrete implementations injected at construction. *(Spring note: these would be `@Component` beans constructor-injected into a `@Service`; the singleton below is what Spring's default singleton bean scope gives you for free — one more reason `new`-free DI beats hand-rolled singletons in real apps.)*

### Design patterns and exactly why they fit

- **Singleton** (`ConcertTicketBookingSystem`) — there must be exactly one authoritative registry of seat state, or two registries could each "successfully" sell the same seat. The singleton is the *correctness* boundary here, not a convenience. Implemented with the **initialization-on-demand holder idiom**: lazy, thread-safe, no locking on the hot `getInstance()` path (improvement over `static synchronized getInstance()`).
- **Facade** — `bookTickets` hides a multi-step workflow (validate → capture seats → pay → confirm/rollback → notify) behind one call. The client never orchestrates seats and payment itself, so invariants can't be bypassed.
- **Strategy** (`PaymentProcessor`, `NotificationService`) — payment and notification are families of interchangeable algorithms selected at runtime; precisely Strategy's intent.
- **State (lightweight form)** — `SeatStatus` + guarded transitions in `book()`/`release()`. With only two states and two transitions, a full GoF State pattern (one class per state) would be over-engineering; the enum-with-guards version is the right-sized application. Knowing when *not* to deploy the full pattern is itself a pattern skill.

### The two decisions an interviewer will probe

1. **Lock granularity: per-seat monitors vs. one global booking lock.** A global lock is trivially correct but serializes *all* bookings — VIP row 1 contends with lawn seats. Per-seat `synchronized` check-and-set keeps the critical section tiny and lets disjoint bookings proceed fully in parallel. Cost: multi-seat atomicity is no longer free — addressed with compensation (below).
2. **Multi-seat atomicity without nested locks.** We never hold two seat locks at once. Each seat is captured independently; if seat *k* fails, seats *1..k-1* are rolled back (compensation / saga-in-miniature). Since no thread ever holds lock A while waiting for lock B, deadlock is impossible *by construction* — a stronger argument than "we lock in sorted order," and the one to lead with.

---

## Step 4 — Implementation

### Enums

```java
public enum SeatType { REGULAR, PREMIUM, VIP }

public enum SeatStatus { AVAILABLE, BOOKED }

public enum BookingStatus { PENDING, CONFIRMED, CANCELLED }
```

### Seat — the concurrency hot spot

```java
public class Seat {
    private final String id;
    private final String seatNumber;
    private final SeatType type;
    private final double price;
    private SeatStatus status;          // guarded by 'this'

    public Seat(String id, String seatNumber, SeatType type, double price) {
        this.id = id;
        this.seatNumber = seatNumber;
        this.type = type;
        this.price = price;
        this.status = SeatStatus.AVAILABLE;
    }

    /**
     * Atomic check-and-set. The check (is it AVAILABLE?) and the set
     * (mark BOOKED) MUST be one indivisible step — splitting them is
     * exactly the check-then-act race that causes double booking.
     */
    public synchronized void book() {
        if (status != SeatStatus.AVAILABLE) {
            throw new SeatNotAvailableException(
                "Seat " + seatNumber + " is already booked.");
        }
        status = SeatStatus.BOOKED;
    }

    /** Used by rollback (payment failure) and by cancellation. Idempotent. */
    public synchronized void release() {
        status = SeatStatus.AVAILABLE;
    }

    public synchronized SeatStatus getStatus() { return status; }

    public String getId()         { return id; }
    public String getSeatNumber() { return seatNumber; }
    public SeatType getType()     { return type; }
    public double getPrice()      { return price; }
}
```

### Concert

```java
public class Concert {
    private final String id;
    private final String artist;
    private final String venue;
    private final LocalDateTime dateTime;
    private final List<Seat> seats;     // composition: created with the concert

    public Concert(String id, String artist, String venue,
                   LocalDateTime dateTime, List<Seat> seats) {
        this.id = id;
        this.artist = artist;
        this.venue = venue;
        this.dateTime = dateTime;
        this.seats = List.copyOf(seats);   // defensive: list itself immutable
    }

    /** Snapshot — may be stale by the time the user clicks "book".
        Correctness is enforced in Seat.book(), never here. */
    public List<Seat> getAvailableSeats() {
        return seats.stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    public String getId()              { return id; }
    public String getArtist()          { return artist; }
    public String getVenue()           { return venue; }
    public LocalDateTime getDateTime() { return dateTime; }
    public List<Seat> getSeats()       { return seats; }
}
```

### User & Booking

```java
public class User {
    private final String id;
    private final String name;
    private final String email;

    public User(String id, String name, String email) {
        this.id = id; this.name = name; this.email = email;
    }
    public String getId()    { return id; }
    public String getName()  { return name; }
    public String getEmail() { return email; }
}
```

```java
public class Booking {
    private final String id;
    private final User user;
    private final Concert concert;
    private final List<Seat> seats;
    private final double totalPrice;
    private volatile BookingStatus status;   // visibility across threads

    public Booking(String id, User user, Concert concert, List<Seat> seats) {
        this.id = id;
        this.user = user;
        this.concert = concert;
        this.seats = List.copyOf(seats);
        this.totalPrice = seats.stream().mapToDouble(Seat::getPrice).sum();
        this.status = BookingStatus.PENDING;
    }

    // Package-private: only the system facade may drive the lifecycle.
    void confirm() {
        if (status != BookingStatus.PENDING)
            throw new IllegalStateException("Only PENDING bookings can be confirmed.");
        status = BookingStatus.CONFIRMED;
    }

    void cancel() {
        if (status != BookingStatus.CONFIRMED)
            throw new IllegalStateException("Only CONFIRMED bookings can be cancelled.");
        status = BookingStatus.CANCELLED;
        seats.forEach(Seat::release);   // seats go back on sale
    }

    public String getId()            { return id; }
    public User getUser()            { return user; }
    public Concert getConcert()      { return concert; }
    public List<Seat> getSeats()     { return seats; }
    public double getTotalPrice()    { return totalPrice; }
    public BookingStatus getStatus() { return status; }
}
```

### Strategy seams

```java
public interface PaymentProcessor {
    boolean process(double amount);
}

public class CreditCardProcessor implements PaymentProcessor {
    @Override public boolean process(double amount) {
        // Gateway integration lives behind this seam; demo always succeeds.
        return true;
    }
}

public interface NotificationService {
    void notify(User user, String message);
}

public class EmailNotifier implements NotificationService {
    @Override public void notify(User user, String message) {
        System.out.println("Email to " + user.getEmail() + ": " + message);
    }
}
```

### The system facade

```java
public class ConcertTicketBookingSystem {

    // Holder idiom: JVM class-loading guarantees thread-safe lazy init,
    // zero locking on subsequent getInstance() calls.
    private static class Holder {
        private static final ConcertTicketBookingSystem INSTANCE =
            new ConcertTicketBookingSystem(new CreditCardProcessor(),
                                           new EmailNotifier());
    }

    private final Map<String, Concert> concerts = new ConcurrentHashMap<>();
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final PaymentProcessor paymentProcessor;   // DIP: injected
    private final NotificationService notifier;

    private ConcertTicketBookingSystem(PaymentProcessor p, NotificationService n) {
        this.paymentProcessor = p;
        this.notifier = n;
    }

    public static ConcertTicketBookingSystem getInstance() {
        return Holder.INSTANCE;
    }

    public void addConcert(Concert concert) {
        concerts.put(concert.getId(), concert);
    }

    public Concert getConcert(String concertId) {
        Concert c = concerts.get(concertId);
        if (c == null) throw new IllegalArgumentException("Unknown concert: " + concertId);
        return c;
    }

    /** Null criteria are ignored; all supplied criteria must match (AND). */
    public List<Concert> searchConcerts(String artist, String venue,
                                        LocalDateTime dateTime) {
        return concerts.values().stream()
            .filter(c -> artist == null   || c.getArtist().equalsIgnoreCase(artist))
            .filter(c -> venue == null    || c.getVenue().equalsIgnoreCase(venue))
            .filter(c -> dateTime == null || c.getDateTime().equals(dateTime))
            .collect(Collectors.toList());
    }

    /**
     * Atomic multi-seat booking via capture-with-compensation:
     *   1) capture each seat independently (per-seat atomic CAS-style book())
     *   2) any failure -> release everything captured so far, abort
     *   3) payment -> failure also releases everything
     *   4) confirm + notify
     * No thread ever holds two seat locks simultaneously => no deadlock.
     */
    public Booking bookTickets(User user, Concert concert, List<Seat> seats) {
        if (user == null || concert == null || seats == null || seats.isEmpty()) {
            throw new IllegalArgumentException("User, concert and seats are required.");
        }

        List<Seat> captured = new ArrayList<>();
        try {
            for (Seat seat : seats) {
                seat.book();              // throws if not AVAILABLE
                captured.add(seat);
            }
        } catch (SeatNotAvailableException e) {
            captured.forEach(Seat::release);   // compensation: all-or-nothing
            throw e;
        }

        Booking booking = new Booking(UUID.randomUUID().toString(),
                                      user, concert, seats);

        if (!paymentProcessor.process(booking.getTotalPrice())) {
            seats.forEach(Seat::release);      // payment failed: free the seats
            throw new PaymentFailedException(
                "Payment of " + booking.getTotalPrice() + " failed.");
        }

        booking.confirm();
        bookings.put(booking.getId(), booking);
        notifier.notify(user, "Booking " + booking.getId() + " confirmed: "
            + seats.size() + " seat(s) for " + concert.getArtist()
            + " at " + concert.getVenue());
        return booking;
    }

    public void cancelBooking(String bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null)
            throw new IllegalArgumentException("Unknown booking: " + bookingId);
        booking.cancel();                       // releases seats internally
        notifier.notify(booking.getUser(),
            "Booking " + bookingId + " cancelled.");
    }
}
```

### Exceptions

```java
public class SeatNotAvailableException extends RuntimeException {
    public SeatNotAvailableException(String msg) { super(msg); }
}

public class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String msg) { super(msg); }
}
```

### Demo

```java
public class Demo {
    public static void main(String[] args) {
        ConcertTicketBookingSystem system = ConcertTicketBookingSystem.getInstance();

        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= 5; i++)
            seats.add(new Seat("S" + i, "A" + i, SeatType.VIP, 250.0));

        Concert concert = new Concert("C1", "Coldplay", "DY Patil Stadium",
                LocalDateTime.of(2026, 9, 18, 19, 30), seats);
        system.addConcert(concert);

        User alice = new User("U1", "Alice", "alice@example.com");
        User bob   = new User("U2", "Bob",   "bob@example.com");

        // Both race for the SAME seat A1 — exactly one must win.
        Seat contested = concert.getSeats().get(0);
        Runnable attempt = () -> {
            try {
                Booking b = system.bookTickets(
                    Thread.currentThread().getName().equals("alice") ? alice : bob,
                    concert, List.of(contested));
                System.out.println(Thread.currentThread().getName()
                    + " WON booking " + b.getId());
            } catch (SeatNotAvailableException e) {
                System.out.println(Thread.currentThread().getName()
                    + " lost: " + e.getMessage());
            }
        };
        new Thread(attempt, "alice").start();
        new Thread(attempt, "bob").start();
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input & boundary conditions

| Case | Handling |
|---|---|
| Null/empty user, concert, or seat list | `IllegalArgumentException` before any state changes — fail fast, fail clean. |
| Unknown concert/booking id | `IllegalArgumentException` from the lookup. |
| Seat already booked | `SeatNotAvailableException` from `Seat.book()`; facade compensates and rethrows. |
| Same seat listed twice in one request | Second `book()` on it fails → whole request rolls back. (A `Set`-based dedupe at the API edge is a friendlier alternative — worth mentioning.) |
| Payment failure | All seats released, `PaymentFailedException`; no booking is recorded. The bookings map only ever contains CONFIRMED (or later CANCELLED) bookings — never half-done ones. |
| Cancel a non-CONFIRMED booking | `IllegalStateException` from the `Booking` state guard; double-cancel is rejected. |
| Stale availability view | `getAvailableSeats()` is an advisory snapshot. The UI may show a seat that's gone by click time; `Seat.book()` is the single source of truth. Read paths are cheap, write path is guarded — a deliberate trade. |

### Concurrency analysis (the part to narrate in an interview)

**Shared mutable state**
1. `Seat.status` — the contended resource.
2. `Booking.status` — written by booking/cancel flows, read anywhere.
3. `concerts` / `bookings` maps — concurrent structural access.

**Critical sections & chosen primitives**

| State | Primitive | Why this one |
|---|---|---|
| `Seat.status` | `synchronized` on the seat instance | The critical section is a two-line check-and-set; an intrinsic monitor per seat gives minimal granularity — threads contending on *different* seats never block each other. `ReentrantLock` would add ceremony with no benefit *unless* fairness is demanded (below). |
| `Booking.status` | `volatile` + state-guard checks | Single writer at a time per booking in practice; `volatile` guarantees cross-thread visibility of CONFIRMED/CANCELLED. If concurrent cancel attempts were a real path, upgrade `cancel()` to `synchronized`. |
| Registry maps | `ConcurrentHashMap` | Lock-free reads, striped writes; `put`/`get` are atomic. No compound check-then-act is performed on the maps, so no external locking needed. |

**Race freedom.** The only dangerous interleaving is two threads passing the `status == AVAILABLE` check before either writes `BOOKED`. Making `book()` `synchronized` fuses check and write into one atomic step under the seat's monitor — the race is structurally eliminated, not just made unlikely.

**Deadlock freedom — by construction.** Deadlock requires hold-and-wait on multiple locks. Here, `book()` acquires one seat monitor, mutates, and releases *before* the next seat is touched; no thread ever holds two seat locks. With one of the four Coffman conditions impossible, deadlock cannot occur. (The classic alternative — acquire all seat locks in a globally sorted order — also works, but the compensation approach needs no ordering discipline and keeps each critical section tiny.)

**Livelock/starvation & fairness (req #5).** Intrinsic monitors are *unfair* (no FIFO guarantee), but the critical section is ~2 instructions, so practical starvation is implausible; losers fail fast with an exception rather than spinning, so livelock can't arise. If the interviewer insists on *strict* FIFO fairness, swap the seat's monitor for `new ReentrantLock(true)` — fair locks hand the lock to the longest-waiting thread at a measurable throughput cost (every handoff forces a context switch). Stating that cost is the senior-level answer.

**Atomicity of multi-seat booking.** Achieved via **compensation**, not a covering lock: capture seats one by one; on any failure (unavailable seat *or* payment), release everything captured. Externally observable intermediate state (a seat briefly BOOKED then released) is acceptable here because no booking record exists until confirmation — the rollback is invisible at the API level.

**Graceful degradation & scale-out story.** Under contention the system stays correct and merely rejects losers quickly — back-pressure by fast failure, not queueing. In a multi-instance deployment, per-JVM monitors stop being sufficient: the same invariant moves to the database as pessimistic row locks (`SELECT ... FOR UPDATE` on the seat row) or optimistic locking (a `version` column / JPA `@Version`, retry on `OptimisticLockException`) — optimistic wins for low contention, pessimistic for flash-sale hotspots. The *design* doesn't change; only where the atomic check-and-set executes does.

**Known simplification (state it proactively).** With direct `AVAILABLE → BOOKED`, payment executes while seats are already marked BOOKED, and a slow gateway makes seats look sold during processing. The production fix is the `HELD`-with-timeout state we descoped (`AVAILABLE → HELD → BOOKED`, scheduled release on timeout). Naming this trade-off unprompted is exactly what distinguishes a senior candidate.
