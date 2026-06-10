# Movie Ticket Booking System (BookMyShow) — LLD Design Document

> **Position in your sequence:** Hard tier. Direct successor to Concert Ticket Booking (multi-seat atomic claim) and Airline Management (two-phase booking, no lock across payment). New material: the deep catalog hierarchy (City → Theater → Screen → Show) and the physical-seat vs. per-show-seat split.

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| FR1 | Users can browse movies playing across theaters, filtered by city, and list shows (theater + screen + timing) for a chosen movie. |
| FR2 | For a selected show, display the seat map with live availability; users select one or more specific seats. |
| FR3 | Booking is **multi-seat and all-or-nothing**: every requested seat is claimed atomically, or the whole request fails. |
| FR4 | **Two-phase booking**: seats are *held* (PENDING) with a TTL (~10 min in production, seconds in our demo); payment happens during the hold; booking is CONFIRMED only on successful payment. Holds that expire release their seats automatically. |
| FR5 | Seats have categories (SILVER, GOLD, PLATINUM) with per-show, per-category pricing. |
| FR6 | Theater admins can add/update/remove movies, theaters, screens (with seat layouts), and shows. |
| FR7 | Users can cancel a CONFIRMED booking; seats return to the pool. (No refund accounting — out of scope by agreement.) |

### Non-Functional Requirements

| # | Requirement |
|---|-------------|
| NFR1 | **Concurrency correctness** — two users must never hold or confirm the same seat of the same show. No oversell. This is the requirement the entire design orbits. |
| NFR2 | **No undersell** — seats held by a failed/expired/cancelled booking must always return to AVAILABLE. Compensation must be guaranteed on every failure path. |
| NFR3 | **Lock granularity** — bookings on *different shows* must proceed fully in parallel. A global lock is unacceptable. |
| NFR4 | **No lock across I/O** — the payment-gateway call must never execute while a lock is held (Airline lesson). |
| NFR5 | **Extensibility** — new seat categories, pricing rules, and payment methods plug in without modifying booking logic (OCP). |
| NFR6 | **Read-heavy workload** — catalog browsing and seat-map reads vastly outnumber bookings; the read path must not serialize behind the write path. |

### Assumptions (locked at checkpoint)

- In-memory, single JVM; `ConcurrentHashMap`-backed registries; no persistence.
- TTL-based hold **included** (the interview-differentiating piece), implemented with `ScheduledExecutorService`.
- Cancellation frees seats; refund flow is acknowledged but not modeled.
- Search is simple in-memory filtering; no full-text search or recommendations.
- Payment is a mocked `PaymentService` interface — design point is the *interaction*, not the gateway.

---

## Step 2 — Entities & Relationships

> **Foundational refresher — the four relationship types.** *Association* is "knows about" — both objects have independent lifecycles (Booking → User). *Aggregation* is a whole–part where the part survives the whole (Booking aggregates ShowSeats; deleting a booking must not delete seats). *Composition* is a whole–part where the part dies with the whole (a Screen's Seats are meaningless without the Screen). *Dependency* is a transient "uses" — typically a method parameter or injected collaborator (BookingService uses PaymentService).

### The pivotal modeling decision: `Seat` vs. `ShowSeat`

This is the **item-type/instance split** from Library (`Book`/`BookCopy`) reappearing in a sharper form. A physical seat A1 in Screen 2 exists *once*, but its availability is *per show* — A1 can be booked for the 6 PM show and free for the 9 PM show. Conflating these forces you to store a `Map<Show, Status>` inside `Seat`, which couples a stable layout entity to volatile booking state and makes the locking story incoherent. So:

- **`Seat`** — physical, immutable: row, number, `SeatCategory`. Part of the Screen's layout. Created by admins, never touched by booking logic.
- **`ShowSeat`** — per-show, mutable: references a `Seat`, carries a `SeatStatus` (AVAILABLE / HELD / BOOKED). One `ShowSeat` is materialized per physical seat *per show*, at show-creation time. **All booking-time mutation happens here and only here.**

### Entity Catalog

| Entity | Role | Key relationships |
|---|---|---|
| `Movie` | Catalog metadata (title, duration, language, genre) | Referenced by `Show` (association) |
| `Theater` | A venue in a city | **Composition** of `Screen`s — screens have no meaning outside their theater. City is a plain `String` field (see under-modeling note). |
| `Screen` | An auditorium with a fixed layout | **Composition** of `Seat`s — the layout dies with the screen |
| `Seat` | Physical seat: row, number, category | Owned by `Screen` |
| `Show` | A screening: movie + screen + start time + per-category pricing | **Association** to `Movie` and `Screen` (it borrows them, doesn't own them); **Composition** of `ShowSeat`s (per-show state is created and destroyed with the show). Owns the per-show `ReentrantLock`. |
| `ShowSeat` | Per-show booking state of one physical seat | **Association** to `Seat` |
| `Booking` | A user's claim on N seats of one show; lifecycle PENDING → CONFIRMED / EXPIRED / CANCELLED | **Association** to `User` and `Show`; **Aggregation** of `ShowSeat`s (releasing a booking must not destroy the seats) |
| `User` | Identity holder | — |
| `PricingStrategy` | Computes price for (show, seats) | **Dependency** of `BookingService` |
| `PaymentService` | Processes payment for a booking | **Dependency** of `BookingService` |
| `BookingService` | Orchestrates hold → pay → confirm, expiry, cancellation | Depends on registries, pricing, payment |
| `CatalogService` | Admin CRUD + browse/search | — |
| Enums | `SeatCategory`, `SeatStatus`, `BookingStatus` | State via enum-guarded transitions, not a full GoF State pattern — the lifecycles are short and linear |

### Deliberate under-modeling (what we did NOT create, and why)

- **No `City` entity.** It carries no behavior and no owned state — a `String` field on `Theater` plus a filter in `CatalogService` does the job. An entity earns its existence by having behavior or invariants.
- **No `SeatLock` / `SeatHold` entity.** The hold *is* the PENDING booking plus the HELD seat status. A separate hold object duplicates state that must then be kept consistent — a classic source of bugs. (Some reference solutions model `SeatLockProvider`; we get the same guarantee from `ShowSeat.status` + the show lock, with one less moving part.)
- **No `Ticket` entity.** A confirmed `Booking` *is* the ticket. Add a `Ticket` only if it acquires independent behavior (QR generation, per-seat transfer).
- **No `Payment` entity stored.** We model the *interaction* (`PaymentService.charge(...)` returning success/failure); persisting payment records is real-world necessary but adds nothing to the OOD discussion.

---

## Step 3 — UML Class Design

```text
┌──────────────────────────── CATALOG (admin-written, user-read) ───────────────────────────┐
│                                                                                           │
│  Movie                      Theater                       Screen                          │
│  ─────                      ───────                       ──────                          │
│  - id: String               - id: String                  - id: String                    │
│  - title: String            - name: String                - name: String                  │
│  - durationMin: int         - city: String                - seats: List<Seat>   ◆──── Seat│
│  - language: String         - screens: List<Screen> ◆──┐  + getSeats()          (compos.) │
│                             (composition)              └─►                                │
│                                                            Seat                           │
│                                                            ────                           │
│                                                            - id: String   (e.g. "A1")    │
│                                                            - row: char                    │
│                                                            - number: int                  │
│                                                            - category: SeatCategory       │
│                                                            (IMMUTABLE — no status here!)  │
└───────────────────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────── BOOKING DOMAIN (concurrent-write) ────────────────────────────┐
│                                                                                           │
│  Show ───────────────────► Movie (association)                                            │
│  ────  ──────────────────► Screen (association)                                           │
│  - id: String                                                                             │
│  - startTime: LocalDateTime                                                               │
│  - pricing: Map<SeatCategory, BigDecimal>                                                 │
│  - showSeats: Map<String, ShowSeat>   ◆──────► ShowSeat (composition)                     │
│  - lock: ReentrantLock          ◄── the unit of mutual exclusion                          │
│                                                                                           │
│  ShowSeat ───────────────► Seat (association)                                             │
│  ────────                                                                                 │
│  - seat: Seat                                                                             │
│  - status: SeatStatus          AVAILABLE ⇄ HELD → BOOKED → AVAILABLE (on cancel)          │
│                                                                                           │
│  Booking ────────────────► User, Show (association); ◇────► ShowSeat (aggregation)        │
│  ───────                                                                                  │
│  - id, user, show                                                                         │
│  - seats: List<ShowSeat>                                                                  │
│  - amount: BigDecimal                                                                     │
│  - status: BookingStatus       PENDING → CONFIRMED | EXPIRED;  CONFIRMED → CANCELLED      │
└───────────────────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────── SERVICES ─────────────────────────────────────────────────────┐
│                                                                                            │
│  «interface» PricingStrategy            «interface» PaymentService                         │
│  + price(show, seats): BigDecimal       + charge(user, amount): boolean                    │
│        ▲                                      ▲                                            │
│  CategoryPricingStrategy                MockPaymentService                                  │
│                                                                                            │
│  BookingService (Singleton — holder idiom)                                                 │
│  + requestBooking(user, showId, seatIds): Booking      // phase 1: atomic hold             │
│  + confirmBooking(bookingId): Booking                  // phase 2: pay, then flip          │
│  + cancelBooking(bookingId): void                                                          │
│  - expire(booking): void                               // TTL compensation                 │
│  uses: ScheduledExecutorService, PricingStrategy, PaymentService                           │
│                                                                                            │
│  CatalogService (Singleton)                                                                │
│  + addTheater/addMovie/addShow(...)                    // addShow materializes ShowSeats   │
│  + moviesInCity(city) / showsForMovie(movieId, city)                                       │
│  registries: ConcurrentHashMap<String, ·>                                                  │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Pattern Mapping — and exactly why each fits

| Pattern | Where | Why it fits *here* |
|---|---|---|
| **Strategy** | `PricingStrategy`, `PaymentService` | Pricing rules (weekend surge, matinee discount) and payment methods vary independently of the booking algorithm. The booking flow calls `price(...)` and `charge(...)` against interfaces — new rules are new classes, not edits to `BookingService` (OCP). |
| **State (lightweight, enum-guarded)** | `BookingStatus`, `SeatStatus` transitions validated inside critical sections | The lifecycles are short and linear (4 states, ~4 legal transitions). A full GoF State pattern (one class per state) would be ceremony without payoff. Your Facebook/Auction precedent: *use enum guards until per-state behavior diverges enough to earn classes.* |
| **Singleton (holder idiom)** | `BookingService`, `CatalogService` | One registry of truth per JVM. Holder idiom gives lazy init + thread safety with zero synchronization cost on the hot path, unlike `static synchronized getInstance()`. |
| **Facade** | `BookingService` / `CatalogService` as the only public surface | The demo (and an API layer) talks to two services; the seat-state machinery is invisible. Interviewers read this as "knows how to define a public contract." |
| **Composition over inheritance** | `SeatCategory` as enum field, not `PremiumSeat extends Seat` | Categories differ only in *data* (price multiplier), not behavior. Subclassing for data differences is the classic over-engineering trap interviewers probe. |
| **Observer (named, not built)** | Booking-confirmed notifications | Mention as the extension point for email/SMS; wiring it adds bulk without new insight — you've built it twice (Auction, LinkedIn). |

### SOLID mapping

- **S** — `Show` holds schedule+seat state; `BookingService` orchestrates; `PricingStrategy` prices; `PaymentService` pays. No god class.
- **O** — new seat categories, prices, payment rails: add enum values / strategy classes, never edit booking logic.
- **L** — any `PaymentService` impl is substitutable; the contract is "charge returns success/failure, may be slow, may throw."
- **I** — `PricingStrategy` and `PaymentService` are separate, narrow interfaces; no fat `BookingDependencies` interface.
- **D** — `BookingService` depends on the two abstractions, injected via constructor. (Spring note: these would be `@Component`s injected into a `@Service`; the singleton-holder idiom is what you do *without* a container managing bean scope for you.)

### The two decisions an interviewer will probe

**1. Lock granularity: why per-show, not per-seat or global?**
A global lock serializes the entire site — unacceptable (NFR3). Per-*seat* locking (or CAS on each `ShowSeat`) maximizes parallelism but makes the **multi-seat atomic claim** hard: you must acquire N seat locks in a consistent order to avoid deadlock, and handle partial-acquisition rollback. Per-*show* `ReentrantLock` is the sweet spot: a screen has 100–300 seats, the critical section is a few microseconds of status flips, and real contention is concentrated on *hot shows* — where users are mostly fighting for the *same* seats anyway, so per-seat locking buys little. Different shows never contend. Be ready to say: *"If profiling showed the show lock was hot, I'd move to CAS per ShowSeat with lock-free claim + compensation — claim seats one by one with `compareAndSet(AVAILABLE, HELD)`, and on the first failure, release the ones already claimed."* That's your Concert Ticket compensation pattern, restated lock-free.

**2. The hold-TTL design: why two phases, and where the lock is NOT held.**
Payment takes seconds; locks are held for microseconds. If you hold the show lock across `charge(...)`, one slow gateway call freezes every booking for that show (and you've recreated the Airline anti-pattern). So: **Phase 1** (under lock): validate all seats AVAILABLE → flip to HELD → create PENDING booking → schedule expiry. **Phase 2** (no lock): call payment. **Phase 3** (under lock again): re-check the booking is still PENDING — it may have EXPIRED while the gateway dawdled — then flip seats to BOOKED and the booking to CONFIRMED. The confirm-vs-expiry race is resolved by making *both* transitions take the same lock and guard on status. This is the subtlest part of the implementation; see Step 5.

---

## Step 4 — Implementation (Java 11+) with Demo

> Boilerplate getters/constructors are trimmed where uninteresting; everything structural is shown. The interesting 20% — the two-phase booking, the expiry race, the atomic claim — is complete.

### 4.1 Enums — lifecycles as data

```java
public enum SeatCategory { SILVER, GOLD, PLATINUM }

public enum SeatStatus  { AVAILABLE, HELD, BOOKED }

public enum BookingStatus { PENDING, CONFIRMED, EXPIRED, CANCELLED }
```

### 4.2 Catalog entities (admin-written, effectively immutable at booking time)

```java
public class Movie {
    private final String id;
    private final String title;
    private final int durationMin;
    private final String language;

    public Movie(String id, String title, int durationMin, String language) {
        this.id = id; this.title = title;
        this.durationMin = durationMin; this.language = language;
    }
    public String getId() { return id; }
    public String getTitle() { return title; }
}

public class Seat {                       // PHYSICAL seat: layout only, no status
    private final String id;              // e.g. "A1"
    private final char row;
    private final int number;
    private final SeatCategory category;

    public Seat(char row, int number, SeatCategory category) {
        this.row = row; this.number = number; this.category = category;
        this.id = "" + row + number;
    }
    public String getId() { return id; }
    public SeatCategory getCategory() { return category; }
}

public class Screen {
    private final String id;
    private final String name;
    private final List<Seat> seats = new ArrayList<>();   // composition

    public Screen(String id, String name) { this.id = id; this.name = name; }

    public void addSeat(Seat seat) { seats.add(seat); }   // admin-time only
    public List<Seat> getSeats() { return Collections.unmodifiableList(seats); }
    public String getId() { return id; }
}

public class Theater {
    private final String id;
    private final String name;
    private final String city;                            // String, not entity — no behavior
    private final List<Screen> screens = new ArrayList<>(); // composition

    public Theater(String id, String name, String city) {
        this.id = id; this.name = name; this.city = city;
    }
    public void addScreen(Screen s) { screens.add(s); }
    public String getCity() { return city; }
    public String getName() { return name; }
    public List<Screen> getScreens() { return Collections.unmodifiableList(screens); }
}
```

### 4.3 Booking-domain entities (concurrent-write)

```java
public class ShowSeat {                   // PER-SHOW state of one physical seat
    private final Seat seat;              // association to layout
    // volatile: seat-map renders read status WITHOUT the show lock (NFR6).
    // Correctness never depends on these unlocked reads — every state
    // CHANGE happens under the show lock; volatile just keeps the UI fresh.
    private volatile SeatStatus status = SeatStatus.AVAILABLE;

    public ShowSeat(Seat seat) { this.seat = seat; }
    public Seat getSeat() { return seat; }
    public SeatStatus getStatus() { return status; }
    void setStatus(SeatStatus s) { this.status = s; }   // package-private: only BookingService mutates
}

public class Show {
    private final String id;
    private final Movie movie;            // association
    private final Screen screen;          // association
    private final Theater theater;        // association (navigability convenience)
    private final LocalDateTime startTime;
    private final Map<SeatCategory, BigDecimal> basePrice;
    private final Map<String, ShowSeat> showSeats;        // composition; keyed by seat id

    // THE unit of mutual exclusion. One lock per show => bookings on
    // different shows never contend (NFR3).
    private final ReentrantLock lock = new ReentrantLock();

    public Show(String id, Movie movie, Theater theater, Screen screen,
                LocalDateTime startTime, Map<SeatCategory, BigDecimal> basePrice) {
        this.id = id; this.movie = movie; this.theater = theater;
        this.screen = screen; this.startTime = startTime;
        this.basePrice = Map.copyOf(basePrice);
        // Materialize per-show seat state from the physical layout — the
        // Seat/ShowSeat split made concrete. (A small Factory moment.)
        Map<String, ShowSeat> m = new LinkedHashMap<>();
        for (Seat s : screen.getSeats()) m.put(s.getId(), new ShowSeat(s));
        this.showSeats = Collections.unmodifiableMap(m);  // map itself never mutates after init
    }

    public ReentrantLock lock() { return lock; }
    public ShowSeat seatOrThrow(String seatId) {
        ShowSeat ss = showSeats.get(seatId);
        if (ss == null) throw new InvalidSeatException(seatId + " does not exist in show " + id);
        return ss;
    }
    public BigDecimal priceOf(SeatCategory c) { return basePrice.get(c); }
    public Collection<ShowSeat> seatMap() { return showSeats.values(); }  // lock-free read path
    public String getId() { return id; }
    public Movie getMovie() { return movie; }
    public Theater getTheater() { return theater; }
    public LocalDateTime getStartTime() { return startTime; }
}

public class User {
    private final String id;
    private final String name;
    public User(String id, String name) { this.id = id; this.name = name; }
    public String getName() { return name; }
}

public class Booking {
    private static final AtomicLong SEQ = new AtomicLong();

    private final String id = "BKG-" + SEQ.incrementAndGet();
    private final User user;
    private final Show show;
    private final List<ShowSeat> seats;   // aggregation: seats outlive the booking
    private final BigDecimal amount;
    // Mutated only under show.lock(); volatile so status queries stay fresh.
    private volatile BookingStatus status = BookingStatus.PENDING;

    public Booking(User user, Show show, List<ShowSeat> seats, BigDecimal amount) {
        this.user = user; this.show = show;
        this.seats = List.copyOf(seats); this.amount = amount;
    }
    public String getId() { return id; }
    public User getUser() { return user; }
    public Show getShow() { return show; }
    public List<ShowSeat> getSeats() { return seats; }
    public BigDecimal getAmount() { return amount; }
    public BookingStatus getStatus() { return status; }
    void setStatus(BookingStatus s) { this.status = s; }
}
```

### 4.4 Strategies

```java
public interface PricingStrategy {
    BigDecimal price(Show show, List<ShowSeat> seats);
}

/** Sum of the show's per-category base prices. Surge/discount = new strategy class (OCP). */
public class CategoryPricingStrategy implements PricingStrategy {
    @Override
    public BigDecimal price(Show show, List<ShowSeat> seats) {
        return seats.stream()
                .map(ss -> show.priceOf(ss.getSeat().getCategory()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

public interface PaymentService {
    /** May be SLOW. May fail. Must never be called while holding a lock. */
    boolean charge(User user, BigDecimal amount);
    void refund(User user, BigDecimal amount);   // compensation path (see Step 5, EC4)
}

public class MockPaymentService implements PaymentService {
    private final boolean succeed;
    private final long latencyMs;
    public MockPaymentService(boolean succeed, long latencyMs) {
        this.succeed = succeed; this.latencyMs = latencyMs;
    }
    @Override public boolean charge(User u, BigDecimal amt) {
        try { Thread.sleep(latencyMs); } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return false;
        }
        return succeed;
    }
    @Override public void refund(User u, BigDecimal amt) {
        System.out.println("  [payment] refunded " + amt + " to " + u.getName());
    }
}
```

### 4.5 Exceptions (unchecked — caller bugs and business rejections, not recoverable plumbing)

```java
public class BookingException extends RuntimeException {
    public BookingException(String msg) { super(msg); }
}
public class SeatUnavailableException extends BookingException {
    public SeatUnavailableException(String m) { super(m); }
}
public class InvalidSeatException extends BookingException {
    public InvalidSeatException(String m) { super(m); }
}
public class IllegalBookingStateException extends BookingException {
    public IllegalBookingStateException(String m) { super(m); }
}
public class PaymentFailedException extends BookingException {
    public PaymentFailedException(String m) { super(m); }
}
```

### 4.6 CatalogService — admin + browse facade

```java
public class CatalogService {
    private CatalogService() {}
    private static class Holder { static final CatalogService I = new CatalogService(); }
    public static CatalogService getInstance() { return Holder.I; }   // holder idiom

    private final Map<String, Movie>   movies   = new ConcurrentHashMap<>();
    private final Map<String, Theater> theaters = new ConcurrentHashMap<>();
    private final Map<String, Show>    shows    = new ConcurrentHashMap<>();

    // ---- admin (FR6) ----
    public void addMovie(Movie m)     { movies.put(m.getId(), m); }
    public void addTheater(Theater t) { theaters.put(t.getId(), t); }
    public Show addShow(String id, String movieId, Theater theater, Screen screen,
                        LocalDateTime start, Map<SeatCategory, BigDecimal> pricing) {
        Show show = new Show(id, movieOrThrow(movieId), theater, screen, start, pricing);
        shows.put(id, show);
        return show;
    }
    public void removeShow(String showId) { shows.remove(showId); } // caveat: Step 5, EC6

    // ---- browse (FR1) ----
    public List<Movie> moviesInCity(String city) {
        return shows.values().stream()
                .filter(s -> s.getTheater().getCity().equalsIgnoreCase(city))
                .map(Show::getMovie).distinct().collect(Collectors.toList());
    }
    public List<Show> showsFor(String movieId, String city) {
        return shows.values().stream()
                .filter(s -> s.getMovie().getId().equals(movieId)
                          && s.getTheater().getCity().equalsIgnoreCase(city))
                .sorted(Comparator.comparing(Show::getStartTime))
                .collect(Collectors.toList());
    }

    public Show showOrThrow(String id) {
        Show s = shows.get(id);
        if (s == null) throw new BookingException("No such show: " + id);
        return s;
    }
    private Movie movieOrThrow(String id) {
        Movie m = movies.get(id);
        if (m == null) throw new BookingException("No such movie: " + id);
        return m;
    }
}
```

### 4.7 BookingService — the heart of the design

```java
public class BookingService {

    private static final Duration HOLD_TTL = Duration.ofSeconds(3); // demo TTL; ~10 min in prod

    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hold-expiry"); t.setDaemon(true); return t;
            });

    private final CatalogService catalog;
    private final PricingStrategy pricing;     // Strategy — injected (DIP)
    private final PaymentService payment;      // Strategy — injected (DIP)

    public BookingService(CatalogService catalog, PricingStrategy pricing, PaymentService payment) {
        this.catalog = catalog; this.pricing = pricing; this.payment = payment;
    }

    /**
     * PHASE 1 — atomic multi-seat HOLD. Entire claim happens inside ONE
     * acquisition of the show lock: validate ALL seats first, mutate only
     * after every seat passes. That ordering is what makes the claim
     * all-or-nothing without needing rollback code.
     */
    public Booking requestBooking(User user, String showId, List<String> seatIds) {
        if (seatIds == null || seatIds.isEmpty())
            throw new BookingException("Select at least one seat");
        Show show = catalog.showOrThrow(showId);

        show.lock().lock();
        try {
            // 1) validate ALL before mutating ANY
            List<ShowSeat> claimed = new ArrayList<>(seatIds.size());
            for (String seatId : seatIds) {
                ShowSeat ss = show.seatOrThrow(seatId);
                if (ss.getStatus() != SeatStatus.AVAILABLE)
                    throw new SeatUnavailableException(
                        "Seat " + seatId + " is " + ss.getStatus() + " for show " + showId);
                claimed.add(ss);
            }
            // 2) mutate — cannot fail past this point
            claimed.forEach(ss -> ss.setStatus(SeatStatus.HELD));

            Booking booking = new Booking(user, show, claimed, pricing.price(show, claimed));
            bookings.put(booking.getId(), booking);

            // 3) schedule compensation: the hold dies unless confirmed in time
            expiryScheduler.schedule(() -> expire(booking),
                    HOLD_TTL.toMillis(), TimeUnit.MILLISECONDS);
            return booking;
        } finally {
            show.lock().unlock();   // lock held only for in-memory flips: microseconds
        }
    }

    /**
     * PHASE 2 (no lock): charge.  PHASE 3 (lock): re-check & flip.
     * The re-check is the whole point — the booking may have EXPIRED while
     * the gateway was slow. Both confirm and expire take the SAME show lock
     * and guard on status, so exactly one of them wins.
     */
    public Booking confirmBooking(String bookingId) {
        Booking b = bookingOrThrow(bookingId);

        if (b.getStatus() != BookingStatus.PENDING)            // cheap fast-fail; advisory only —
            throw new IllegalBookingStateException(            // the authoritative check is below,
                bookingId + " is " + b.getStatus());           // under the lock

        boolean paid = payment.charge(b.getUser(), b.getAmount());  // SLOW. NO LOCK HELD.

        Show show = b.getShow();
        show.lock().lock();
        try {
            if (b.getStatus() != BookingStatus.PENDING) {
                // Expired (or cancelled) while we were paying.
                if (paid) payment.refund(b.getUser(), b.getAmount());   // compensation
                throw new IllegalBookingStateException(
                    bookingId + " expired during payment" + (paid ? "; amount refunded" : ""));
            }
            if (!paid) {
                releaseSeats(b, BookingStatus.EXPIRED);   // free seats immediately, don't wait for TTL
                throw new PaymentFailedException("Payment declined for " + bookingId);
            }
            b.getSeats().forEach(ss -> ss.setStatus(SeatStatus.BOOKED));
            b.setStatus(BookingStatus.CONFIRMED);
            return b;
        } finally {
            show.lock().unlock();
        }
    }

    /** TTL compensation. Runs on the scheduler thread; same lock, status-guarded. */
    private void expire(Booking b) {
        Show show = b.getShow();
        show.lock().lock();
        try {
            if (b.getStatus() == BookingStatus.PENDING) {     // lost the race to confirm? do nothing
                releaseSeats(b, BookingStatus.EXPIRED);
                System.out.println("  [expiry] " + b.getId() + " expired; seats released");
            }
        } finally {
            show.lock().unlock();
        }
    }

    /** FR7 — cancellation frees seats (refund accounting out of scope). */
    public void cancelBooking(String bookingId) {
        Booking b = bookingOrThrow(bookingId);
        Show show = b.getShow();
        show.lock().lock();
        try {
            if (b.getStatus() != BookingStatus.CONFIRMED)
                throw new IllegalBookingStateException(
                    "Only CONFIRMED bookings can be cancelled; " + bookingId + " is " + b.getStatus());
            releaseSeats(b, BookingStatus.CANCELLED);
        } finally {
            show.lock().unlock();
        }
    }

    // invariant: callers hold show.lock()
    private void releaseSeats(Booking b, BookingStatus terminal) {
        b.getSeats().forEach(ss -> ss.setStatus(SeatStatus.AVAILABLE));
        b.setStatus(terminal);
    }

    private Booking bookingOrThrow(String id) {
        Booking b = bookings.get(id);
        if (b == null) throw new BookingException("No such booking: " + id);
        return b;
    }
}
```

### 4.8 Demo — including the concurrency race

```java
public class MovieBookingDemo {
    public static void main(String[] args) throws Exception {
        // ---------- admin setup (FR6) ----------
        CatalogService catalog = CatalogService.getInstance();
        Movie inception = new Movie("M1", "Inception", 148, "EN");
        catalog.addMovie(inception);

        Theater pvr = new Theater("T1", "PVR Grand", "Chennai");
        Screen screen1 = new Screen("S1", "Screen 1");
        for (char row = 'A'; row <= 'B'; row++)
            for (int n = 1; n <= 5; n++)
                screen1.addSeat(new Seat(row, n,
                        row == 'A' ? SeatCategory.PLATINUM : SeatCategory.GOLD));
        pvr.addScreen(screen1);
        catalog.addTheater(pvr);

        Show show = catalog.addShow("SH1", "M1", pvr, screen1,
                LocalDateTime.now().plusHours(3),
                Map.of(SeatCategory.GOLD, new BigDecimal("250"),
                       SeatCategory.PLATINUM, new BigDecimal("450")));

        BookingService bookingService = new BookingService(
                catalog, new CategoryPricingStrategy(), new MockPaymentService(true, 300));

        // ---------- browse (FR1) ----------
        System.out.println("Movies in Chennai: " + catalog.moviesInCity("Chennai")
                .stream().map(Movie::getTitle).collect(Collectors.joining(", ")));

        // ---------- the race: two users want A1+A2 (NFR1) ----------
        User alice = new User("U1", "Alice");
        User bob   = new User("U2", "Bob");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        List<Future<String>> results = new ArrayList<>();
        for (User u : List.of(alice, bob)) {
            results.add(pool.submit(() -> {
                start.await();                       // maximize collision probability
                try {
                    Booking b = bookingService.requestBooking(u, "SH1", List.of("A1", "A2"));
                    bookingService.confirmBooking(b.getId());
                    return u.getName() + " CONFIRMED " + b.getId() + " for ₹" + b.getAmount();
                } catch (BookingException e) {
                    return u.getName() + " rejected: " + e.getMessage();
                }
            }));
        }
        start.countDown();
        for (Future<String> f : results) System.out.println(f.get());
        // Exactly ONE confirms; the other gets SeatUnavailableException. Never both.

        // ---------- hold expiry (FR4) ----------
        Booking heldOnly = bookingService.requestBooking(alice, "SH1", List.of("B1"));
        System.out.println("B1 status after hold: " + show.seatOrThrow("B1").getStatus()); // HELD
        Thread.sleep(3500);                          // > HOLD_TTL; user abandoned payment
        System.out.println("B1 after TTL: " + show.seatOrThrow("B1").getStatus()           // AVAILABLE
                + ", booking: " + heldOnly.getStatus());                                    // EXPIRED

        // ---------- cancellation (FR7) ----------
        Booking c = bookingService.requestBooking(bob, "SH1", List.of("B2"));
        bookingService.confirmBooking(c.getId());
        bookingService.cancelBooking(c.getId());
        System.out.println("B2 after cancel: " + show.seatOrThrow("B2").getStatus());       // AVAILABLE

        pool.shutdown();
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### 5.1 Exception strategy

All exceptions are unchecked, rooted at `BookingException` — these signal *business rejections* (seat taken, booking expired) or *caller bugs* (unknown seat id), neither of which the immediate caller can meaningfully recover from; an API layer would map them to 4xx responses. The hierarchy lets a caller catch coarsely (`BookingException`) or precisely (`SeatUnavailableException` → "show the user an updated seat map").

| Exception | Thrown when | Caller's correct reaction |
|---|---|---|
| `SeatUnavailableException` | Phase-1 claim finds any requested seat HELD/BOOKED | Refresh seat map, let user re-pick |
| `InvalidSeatException` | Seat id not in the show's layout | Client bug — fix the request |
| `IllegalBookingStateException` | Confirm on non-PENDING / cancel on non-CONFIRMED | Show booking's actual status |
| `PaymentFailedException` | Gateway declined | Offer retry — but note seats were already released, so retry restarts at seat selection (see EC3 for the trade-off) |
| `BookingException` (base) | Unknown show/booking id, empty seat list | Validation error |

Failure paths never strand state: every path out of a critical section either completes the transition or releases the seats — the `try/finally` around each lock guarantees the lock is released even if a listener-style callback ever threw inside.

### 5.2 Edge cases

- **EC1 — Duplicate seat ids in one request** (`["A1","A1"]`): validation pass sees A1 AVAILABLE twice, then HELD-flips it twice — harmless but the booking lists it twice and charges double. Fix: de-dupe via `new LinkedHashSet<>(seatIds)` at method entry. Worth saying aloud in an interview; it shows you test your own API against hostile input.
- **EC2 — Confirm called twice**: first confirm flips to CONFIRMED; second fails the fast-fail pre-check (or, in the race, the under-lock check). Idempotency alternative: make second confirm return the already-confirmed booking instead of throwing — defensible either way; say which you chose and why.
- **EC3 — Payment fails**: we release seats *immediately* rather than letting the TTL do it — failing fast frees inventory sooner on hot shows. Trade-off: the user loses their seats and must re-select; BookMyShow actually keeps the hold alive for retries within the TTL. Both are valid; ours optimizes inventory, theirs optimizes UX. Know both.
- **EC4 — Payment succeeds but hold already expired** (slow gateway): the under-lock re-check catches it and triggers `refund(...)`. This is *compensation for the compensation* — the expiry freed the seats, so the money must come back. The interviewer follow-up is "what if the refund call fails?" → in-memory answer: retry queue; real-world answer: this is exactly why payment systems are built on idempotent operations and reconciliation jobs.
- **EC5 — Empty/null seat list**: rejected before any lock is taken.
- **EC6 — Admin removes a show with PENDING/CONFIRMED bookings**: our `removeShow` is naive. Production answer: shows transition through a lifecycle (SCHEDULED → CANCELLED) rather than being deleted; cancelling a show triggers bulk booking cancellation + notification (the Observer extension point). Flag this gap proactively — interviewers reward candidates who know where their design is thin.
- **EC7 — Booking right at showtime**: not modeled; would be a guard in phase 1 (`startTime.isBefore(now + cutoff)` → reject). One `if`, mention it.

### 5.3 Concurrency analysis — the full argument

**Shared mutable state (exhaustive inventory):**
1. `ShowSeat.status` — the contended heart of the system
2. `Booking.status` — transitions raced by confirm / expiry / cancel
3. Registries (`movies`, `theaters`, `shows`, `bookings` maps)
4. Catalog structures (`Screen.seats`, `Show.showSeats`)

**How each is protected:**

| State | Mechanism | Why this primitive |
|---|---|---|
| `ShowSeat.status` | All writes under the owning show's `ReentrantLock`; reads either under the lock (booking path) or lock-free via `volatile` (seat-map render) | The multi-seat claim must be atomic *as a group* — a single per-show lock makes "validate all, then flip all" trivially atomic. `volatile` gives the read path visibility without contention (NFR6): a render may be microseconds stale, which is fine because the *authoritative* check re-runs under the lock at claim time. |
| `Booking.status` | All writes under the same show lock, guarded by status checks | Confirm, expire, and cancel all race on the same booking; funneling them through one lock + status guard means exactly one transition wins. Using the *show* lock (not a per-booking lock) keeps the system at **one lock class total**. |
| Registries | `ConcurrentHashMap` | Independent key-space operations; no compound invariants across entries, so per-bucket internal locking suffices. |
| Catalog layout | Effectively immutable after admin setup (`Map.copyOf`, `unmodifiableMap`, seats added before shows exist) | Immutability is the cheapest thread-safety there is. The `Show.showSeats` *map* never mutates — only the statuses inside its values do. |

**Critical sections (all on `show.lock()`):**
1. `requestBooking`: validate-all-then-flip-all + booking creation + expiry scheduling
2. `confirmBooking` phase 3: status re-check + flip to BOOKED/CONFIRMED
3. `expire`: status check + release
4. `cancelBooking`: status check + release

Each is a handful of in-memory operations — microseconds. The one slow operation, `payment.charge(...)`, sits strictly *between* lock acquisitions (NFR4).

**Deadlock-freedom argument** (the one-sentence version interviewers want): *the system has exactly one lock class, and no code path ever holds more than one lock at a time, so a wait-for cycle is impossible.* Compare: per-seat locking would require acquiring N locks for a multi-seat claim, forcing the lock-ordering discipline you used in Digital Wallet transfers. We avoided needing it by choosing coarser granularity.

**Livelock/starvation:** no retry loops exist (failures throw, they don't spin), so no livelock. `ReentrantLock` default is unfair, so starvation is theoretically possible under pathological contention; `new ReentrantLock(true)` trades throughput for fairness — mention, don't default to it.

**Race walkthroughs:**
- *Two users, same seats (the demo race):* both reach `requestBooking`; the lock serializes them; the second sees HELD and throws. The validate-before-mutate ordering inside the critical section means the loser causes zero partial state.
- *Confirm vs. expiry:* gateway takes 12 s, TTL is 10 s. Expiry fires at t=10: acquires lock, sees PENDING, releases seats, sets EXPIRED. Confirm returns at t=12: acquires lock, sees EXPIRED, refunds. Reverse interleaving: confirm wins, expiry later sees CONFIRMED and no-ops. Both orders are correct **because both transitions take the same lock and guard on status** — this is the design's subtlest invariant.
- *Cancel vs. re-book:* cancel releases B2 under the lock; a concurrent `requestBooking` for B2 either runs before (sees BOOKED, rejected) or after (sees AVAILABLE, claims it). No interleaving observes a half-released state.

**The scale-up answer (NFR-level follow-up):** in-memory per-show locks die at multi-JVM scale. The same *shape* survives translation: the show lock becomes a DB transaction with `SELECT ... FOR UPDATE` on the show-seat rows (or optimistic locking via a `version` column — CAS at the database layer), the TTL becomes a row with an expiry timestamp swept by a job, and the refund compensation becomes a saga step. Being able to say "my in-memory design maps 1:1 onto the distributed design" is the strongest possible close.

---

## Interviewer Follow-ups (with model answers)

1. **"Add bulk discount: 10% off for 5+ seats."** New `PricingStrategy` decorator or composite: `BulkDiscountPricing(delegate)` wraps `CategoryPricingStrategy`. Zero changes to `BookingService` — that's OCP demonstrated live.
2. **"Support seat-map reads at 100x booking volume."** Already handled: reads are lock-free on `volatile` status. If renders needed a *consistent snapshot* (no torn view across seats), take the lock briefly to copy statuses into a DTO — or accept per-seat staleness, which every real booking site does.
3. **"Make holds retry-friendly: payment failure shouldn't lose the seats."** On `!paid`, keep status PENDING instead of releasing; the TTL remains the backstop. One branch changes. Discuss the inventory-vs-UX trade-off from EC3.
4. **"What if two shows share a screen with overlapping times?"** Admin-side invariant: `addShow` validates no time overlap per screen (interval check against existing shows on that screen, under a per-screen guard or in the catalog's single-writer admin path). Distinct from booking concurrency — different writers, different invariant.
5. **"10x scale — what breaks first?"** The hot-show lock isn't actually first; the single `ScheduledExecutorService` thread and the in-memory maps go first (memory, no durability). Then: shard by show (natural partition key), externalize state to a DB with row-level locking, and the design's seams (repositories, strategies) are exactly where the cuts happen.

## Transferable Lesson

**The unit of locking should be the unit of invariance.** The invariant here is "the set of seats in one claim flips together" — the smallest entity that contains every seat of any possible claim is the *show*, so the show carries the lock. Concert Ticket taught the atomic claim; Airline taught two-phase with no lock across I/O; this problem teaches *how to find the lock's home* by asking what the invariant spans. Hotel Booking (rooms × date ranges) will stress this again with a twist: the invariant spans a *range*, not a fixed set.

## Next Problem

**Hotel Management System** — same two-phase machinery, but availability is per-room *per date range*, so "is it free?" becomes an interval-overlap question and the claim unit gets more interesting. After that: Stack Overflow (rich domain modeling, light concurrency — a deliberate palate cleanser).
