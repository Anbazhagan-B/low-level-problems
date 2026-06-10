# Online Auction System — Low Level Design (Java)

> Target: Senior Java / OOD interview. Plain Java 11+, framework-agnostic core.
> Patterns exercised: **State**, **Strategy**, **Observer**, **Singleton (service)**, plus real concurrency control.

---

## Step 1 — Requirements

### Functional Requirements

| #  | Requirement |
|----|-------------|
| F1 | Users can register and log in (modeled as identity only — no real auth/password handling). |
| F2 | A seller can create an auction listing: item name, description, category, starting price, duration. |
| F3 | Users can browse and search listings by item name, category, price range, and auction status. |
| F4 | Users can place bids on **ACTIVE** auctions only. A bid must be strictly greater than the current highest bid (or ≥ starting price for the first bid). |
| F5 | The system tracks the current highest bid. The **previous highest bidder is notified when outbid**; all participants are notified when the auction ends. |
| F6 | When the duration elapses, the auction **closes automatically** (scheduler — not triggered by a user action) and the highest bidder is declared the winner. |
| F7 | A seller cannot bid on their own auction. |
| F8 | A seller may cancel an auction **only if it has zero bids**. |

### Non-Functional Requirements

- **Concurrency & consistency (the core of this problem):** many bidders may hit the same auction simultaneously. Two bids must never both be accepted as "highest"; a bid must never land *after* the close has committed. Bid placement and auction closing must be mutually exclusive per auction.
- **Extensibility:** new auction types (Dutch, sealed-bid), new bid-validation rules (minimum increment, reserve price), and new notification channels (email, push) must plug in without modifying core classes (OCP).
- **Timeliness:** closing happens at end-time via a scheduler, independent of traffic.
- **Scale assumption:** in-memory, single JVM for the interview. Repositories are isolated behind the service so a DB could replace the maps later.

### Assumptions (stated to the interviewer)

1. English (open ascending) auction only; design leaves a seam for other types.
2. No bid retraction. No proxy/auto-bidding (good follow-up).
3. No payments — the auction ends with a declared winner; settlement is a separate subsystem.
4. Notifications are synchronous console/log stubs behind an interface; real channels are an Observer implementation detail.

---

## Step 2 — Entities & Relationships

| Entity | Responsibility | Key relationships |
|---|---|---|
| `User` | Identity of a buyer/seller (a user can be both). | — |
| `Item` | What is being sold: name, description, category. | **Composition** inside `Auction` — an item listing has no lifecycle independent of its auction in this system. |
| `Auction` | The aggregate root: owns item, status, end time, bid history, highest bid. All state transitions go through it. | **Association** to `User` (seller); **Aggregation** of `Bid`s (bids reference users that exist independently); **Dependency** on `BidValidationPolicy`; notifies `AuctionEventListener`s. |
| `Bid` | Immutable value: bidder, amount, timestamp. | **Association** to `User` (bidder). |
| `AuctionStatus` (enum) | `SCHEDULED, ACTIVE, CLOSED, CANCELLED` — drives the State behavior. | Used by `Auction`. |
| `Category` (enum) | `ELECTRONICS, FASHION, ART, ...` | Used by `Item` and `SearchCriteria`. |
| `BidValidationPolicy` (interface) | **Strategy** — decides whether a new bid is acceptable (strictly-higher, min-increment, reserve...). | Implemented by `HigherThanCurrentPolicy`, `MinIncrementPolicy`. |
| `AuctionEventListener` (interface) | **Observer** — receives outbid / won / closed events. | Implemented by `NotificationService` (or per-user notifiers). |
| `AuctionService` | Façade + **Singleton**: registry of users/auctions, search, and the **scheduler** that closes auctions on time. | Manages `Auction`s (aggregation), owns `ScheduledExecutorService`. |
| `SearchCriteria` | Value object for F3 (name keyword, category, price range, status). | Consumed by `AuctionService.search`. |

**Relationship-type rationale (interview language):**
- `Auction` **composes** `Item`: the item record dies with the auction listing — exclusive ownership, same lifecycle.
- `Auction` **aggregates** `Bid`s and is **associated** with `User`s: users exist before and after any auction; bids are owned by the auction's history but reference independent users.
- `Auction` **depends on** `BidValidationPolicy`: passed in/configured, used transiently per bid — classic Strategy injection.

### Deliberately *not* modeled (over-modeling traps)

- `BidHistory` as its own class — a `List<Bid>` inside `Auction` is enough until requirements say otherwise.
- `Seller`/`Buyer` subclasses of `User` — roles are contextual (same user sells one item, bids on another). Subclassing would force an artificial split; a plain `User` referenced from different fields is correct.
- A `Payment`/`Wallet` entity — out of scope per assumptions.

---

## Step 3 — Class Design (UML, text form)

```
                 «interface»                          «interface»
            BidValidationPolicy                   AuctionEventListener
            + validate(auction, bid)              + onOutbid(auction, outbidUser, newBid)
                   ▲        ▲                     + onAuctionClosed(auction, winnerOrNull)
                   |        |                                ▲
   HigherThanCurrentPolicy  MinIncrementPolicy               |
                                                    NotificationService (stub: log/email/push)

 User                         Bid «immutable»
 - id: String                 - bidder: User
 - name: String               - amount: BigDecimal
 - email: String              - timestamp: Instant

 Item «value, composed»       AuctionStatus «enum»: SCHEDULED | ACTIVE | CLOSED | CANCELLED
 - name, description
 - category: Category

 Auction  (aggregate root — ALL mutation goes through it)
 - id: String
 - seller: User                       (association, 1)
 - item: Item                         (composition, 1)
 - startingPrice: BigDecimal
 - endTime: Instant
 - status: AuctionStatus              (State)
 - bids: List<Bid>                    (aggregation, 0..*)
 - highestBid: Bid (nullable)
 - policy: BidValidationPolicy        (Strategy, injected)
 - listeners: List<AuctionEventListener>   (Observer, 0..*)
 - lock: ReentrantLock                (per-auction critical section)
 + placeBid(bidder, amount): Bid
 + close(): void
 + cancel(by: User): void
 + addListener(l): void

 AuctionService «singleton façade»
 - users: ConcurrentHashMap<String, User>
 - auctions: ConcurrentHashMap<String, Auction>
 - scheduler: ScheduledExecutorService
 + registerUser(...): User
 + createAuction(seller, item, startingPrice, duration, policy): Auction   // schedules close()
 + placeBid(auctionId, bidderId, amount): Bid
 + search(criteria): List<Auction>
 + shutdown(): void

 SearchCriteria «value/builder»
 - keyword?, category?, minPrice?, maxPrice?, status?
```

Multiplicities: `AuctionService 1 — * Auction`, `Auction 1 — * Bid`, `Auction * — 1 User (seller)`, `Bid * — 1 User (bidder)`, `Auction 1 — * AuctionEventListener`.

### Pattern & SOLID mapping — *why*, not just names

- **Observer (`AuctionEventListener`)** — requirement F5 is literally "notify bidders on events." The auction must announce *that* something happened without knowing *how* anyone reacts (email vs push vs websocket). Decouples event source from N reaction channels; adding a channel = adding a listener class (OCP).
- **Strategy (`BidValidationPolicy`)** — bid-acceptance rules are the most volatile business logic (min increment, reserve price, sealed-bid rules). Encapsulating them behind an interface means `Auction.placeBid` never changes when rules do. This is OCP + DIP in one move.
- **State (`AuctionStatus` guarding transitions)** — an auction's legal operations depend entirely on its lifecycle phase (`placeBid` only when ACTIVE, `cancel` only when bid-free, `close` only once). We use an enum + guarded transitions inside the lock rather than full State classes: with 4 states and ~3 operations, full GoF State objects would be ceremony. *Say this trade-off out loud in the interview* — knowing when a pattern is overkill scores points.
- **Singleton (`AuctionService`)** — one registry + one scheduler per JVM. In Spring you would NOT hand-roll this: a `@Service` bean is a container-managed singleton, which is the better version of the same idea (testable, injectable). We hand-roll only because the interview is plain Java.
- **SRP** — `Auction` owns auction state rules; `AuctionService` owns orchestration/registry/scheduling; `NotificationService` owns delivery. No god class.
- **LSP/ISP** — any `BidValidationPolicy` is substitutable; listener interface is small and focused.

### The two decisions an interviewer will probe

1. **Where does the lock live, and what does it protect?** Per-auction `ReentrantLock` guarding the *check-validate-update-record* sequence of `placeBid`, and the same lock used by `close()` — so bidding and closing are mutually exclusive on one auction, while different auctions proceed in parallel. (Full argument in Step 5.)
2. **Why does `Auction` validate its own invariants instead of the service doing it?** Because the invariant ("highest bid only increases; no bids after close") belongs to the aggregate. If the service enforced it, any new code path could bypass it. Rich domain object > anemic model here.

---

## Step 4 — Implementation (Java 11+)

> Boilerplate (trivial getters, `equals/hashCode`) is trimmed; the interesting parts — Strategy, Observer, the locked critical section, and the scheduler — are complete and compilable.

### Enums and value types

```java
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

enum AuctionStatus { SCHEDULED, ACTIVE, CLOSED, CANCELLED }

enum Category { ELECTRONICS, FASHION, ART, BOOKS, COLLECTIBLES, OTHER }

final class User {
    private final String id;
    private final String name;
    private final String email;

    User(String name, String email) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.email = email;
    }
    String getId()    { return id; }
    String getName()  { return name; }
    String getEmail() { return email; }
}

/** Composed inside Auction — no independent lifecycle in this system. */
final class Item {
    private final String name;
    private final String description;
    private final Category category;

    Item(String name, String description, Category category) {
        this.name = Objects.requireNonNull(name);
        this.description = description;
        this.category = Objects.requireNonNull(category);
    }
    String getName()        { return name; }
    String getDescription() { return description; }
    Category getCategory()  { return category; }
}

/** Immutable — a bid is a fact; it never changes after creation. */
final class Bid {
    private final User bidder;
    private final BigDecimal amount;
    private final Instant timestamp;

    Bid(User bidder, BigDecimal amount) {
        this.bidder = Objects.requireNonNull(bidder);
        this.amount = Objects.requireNonNull(amount);
        this.timestamp = Instant.now();
    }
    User getBidder()       { return bidder; }
    BigDecimal getAmount() { return amount; }
    Instant getTimestamp() { return timestamp; }
}
```

### Strategy — pluggable bid validation

```java
/** STRATEGY: the most volatile business rule, isolated behind an interface (OCP). */
interface BidValidationPolicy {
    /** @throws InvalidBidException if the bid is not acceptable. */
    void validate(Auction auction, User bidder, BigDecimal amount);
}

class HigherThanCurrentPolicy implements BidValidationPolicy {
    @Override
    public void validate(Auction auction, User bidder, BigDecimal amount) {
        BigDecimal floor = auction.getHighestBid()
                .map(Bid::getAmount)
                .orElse(auction.getStartingPrice().subtract(BigDecimal.ONE.scaleByPowerOfTen(-2)));
        // first bid: must be >= startingPrice; later bids: strictly > current highest
        boolean first = auction.getHighestBid().isEmpty();
        boolean ok = first ? amount.compareTo(auction.getStartingPrice()) >= 0
                           : amount.compareTo(auction.getHighestBid().get().getAmount()) > 0;
        if (!ok) {
            throw new InvalidBidException("Bid " + amount + " does not beat current price");
        }
    }
}

/** Drop-in alternative rule — note Auction never changes when we swap this in. */
class MinIncrementPolicy implements BidValidationPolicy {
    private final BigDecimal increment;
    MinIncrementPolicy(BigDecimal increment) { this.increment = increment; }

    @Override
    public void validate(Auction auction, User bidder, BigDecimal amount) {
        BigDecimal required = auction.getHighestBid()
                .map(b -> b.getAmount().add(increment))
                .orElse(auction.getStartingPrice());
        if (amount.compareTo(required) < 0) {
            throw new InvalidBidException("Bid must be at least " + required);
        }
    }
}
```

### Observer — event listeners

```java
/** OBSERVER: the auction announces events; channels (email/push/log) react. */
interface AuctionEventListener {
    default void onOutbid(Auction auction, User outbidUser, Bid newHighest) {}
    default void onAuctionClosed(Auction auction, Optional<Bid> winningBid) {}
}

class ConsoleNotificationService implements AuctionEventListener {
    @Override
    public void onOutbid(Auction a, User outbidUser, Bid newHighest) {
        System.out.printf("[notify %s] You were outbid on '%s'. New highest: %s%n",
                outbidUser.getName(), a.getItem().getName(), newHighest.getAmount());
    }
    @Override
    public void onAuctionClosed(Auction a, Optional<Bid> winner) {
        String result = winner.map(b -> "Winner: " + b.getBidder().getName() + " at " + b.getAmount())
                              .orElse("No bids — unsold");
        System.out.printf("[notify] Auction '%s' closed. %s%n", a.getItem().getName(), result);
    }
}
```

### Exceptions

```java
class AuctionException extends RuntimeException {
    AuctionException(String msg) { super(msg); }
}
class InvalidBidException extends AuctionException {
    InvalidBidException(String msg) { super(msg); }
}
class AuctionNotActiveException extends AuctionException {
    AuctionNotActiveException(String msg) { super(msg); }
}
class AuctionNotFoundException extends AuctionException {
    AuctionNotFoundException(String msg) { super(msg); }
}
```

### Auction — the aggregate root and the critical section

```java
class Auction {
    private final String id = UUID.randomUUID().toString();
    private final User seller;
    private final Item item;
    private final BigDecimal startingPrice;
    private final Instant endTime;
    private final BidValidationPolicy policy;

    // --- shared mutable state, guarded by `lock` ---
    private AuctionStatus status = AuctionStatus.ACTIVE;
    private final List<Bid> bids = new ArrayList<>();
    private Bid highestBid; // nullable

    /** One lock per auction: bids on DIFFERENT auctions never contend. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Thread-safe for iteration while listeners are added/removed. */
    private final List<AuctionEventListener> listeners = new CopyOnWriteArrayList<>();

    Auction(User seller, Item item, BigDecimal startingPrice,
            Instant endTime, BidValidationPolicy policy) {
        if (startingPrice.signum() < 0) throw new IllegalArgumentException("negative starting price");
        if (!endTime.isAfter(Instant.now())) throw new IllegalArgumentException("end time in past");
        this.seller = seller;
        this.item = item;
        this.startingPrice = startingPrice;
        this.endTime = endTime;
        this.policy = policy;
    }

    /**
     * CRITICAL SECTION: status check + validation + update must be atomic,
     * otherwise two threads can both pass validation against the same
     * "current highest" and both believe they won (lost update).
     */
    Bid placeBid(User bidder, BigDecimal amount) {
        if (bidder.getId().equals(seller.getId())) {
            throw new InvalidBidException("Seller cannot bid on own auction");
        }
        User outbidUser;
        Bid newBid;
        lock.lock();
        try {
            if (status != AuctionStatus.ACTIVE) {
                throw new AuctionNotActiveException("Auction is " + status);
            }
            // Defense in depth: even if the scheduler is late, reject expired bids.
            if (Instant.now().isAfter(endTime)) {
                throw new AuctionNotActiveException("Auction time has elapsed");
            }
            policy.validate(this, bidder, amount);   // Strategy call, inside the lock

            newBid = new Bid(bidder, amount);
            outbidUser = (highestBid != null) ? highestBid.getBidder() : null;
            highestBid = newBid;
            bids.add(newBid);
        } finally {
            lock.unlock();
        }
        // Notify OUTSIDE the lock: listener code is alien code — it may be slow
        // or call back into us; holding the lock here risks contention/deadlock.
        if (outbidUser != null && !outbidUser.getId().equals(bidder.getId())) {
            for (AuctionEventListener l : listeners) l.onOutbid(this, outbidUser, newBid);
        }
        return newBid;
    }

    /** Idempotent close — the scheduler calls this; safe if called twice. */
    void close() {
        Optional<Bid> winner;
        lock.lock();
        try {
            if (status != AuctionStatus.ACTIVE) return;   // already closed/cancelled
            status = AuctionStatus.CLOSED;
            winner = Optional.ofNullable(highestBid);
        } finally {
            lock.unlock();
        }
        for (AuctionEventListener l : listeners) l.onAuctionClosed(this, winner);
    }

    void cancel(User by) {
        lock.lock();
        try {
            if (!by.getId().equals(seller.getId()))
                throw new AuctionException("Only the seller can cancel");
            if (status != AuctionStatus.ACTIVE)
                throw new AuctionNotActiveException("Auction is " + status);
            if (!bids.isEmpty())
                throw new AuctionException("Cannot cancel: bids already placed");
            status = AuctionStatus.CANCELLED;
        } finally {
            lock.unlock();
        }
    }

    void addListener(AuctionEventListener l) { listeners.add(l); }

    // --- reads (cheap snapshot reads; see Step 5 on read consistency) ---
    String getId()                  { return id; }
    User getSeller()                { return seller; }
    Item getItem()                  { return item; }
    BigDecimal getStartingPrice()   { return startingPrice; }
    Instant getEndTime()            { return endTime; }
    AuctionStatus getStatus()       { lock.lock(); try { return status; } finally { lock.unlock(); } }
    Optional<Bid> getHighestBid()   { lock.lock(); try { return Optional.ofNullable(highestBid); } finally { lock.unlock(); } }
    List<Bid> getBidHistory()       { lock.lock(); try { return List.copyOf(bids); } finally { lock.unlock(); } }
}
```

### AuctionService — façade, registry, scheduler

```java
class AuctionService {
    // SINGLETON (hand-rolled for plain Java; in Spring this is just a @Service bean,
    // which is the superior, testable version of the same idea).
    private static final AuctionService INSTANCE = new AuctionService();
    static AuctionService getInstance() { return INSTANCE; }

    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Auction> auctions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "auction-closer");
                t.setDaemon(true);
                return t;
            });

    private AuctionService() {}

    User registerUser(String name, String email) {
        User u = new User(name, email);
        users.put(u.getId(), u);
        return u;
    }

    Auction createAuction(User seller, Item item, BigDecimal startingPrice,
                          Duration duration, BidValidationPolicy policy,
                          AuctionEventListener notifier) {
        Auction auction = new Auction(seller, item, startingPrice,
                                      Instant.now().plus(duration), policy);
        auction.addListener(notifier);
        auctions.put(auction.getId(), auction);
        // F6: auto-close exactly when the duration elapses — no user action needed.
        scheduler.schedule(auction::close, duration.toMillis(), TimeUnit.MILLISECONDS);
        return auction;
    }

    Bid placeBid(String auctionId, String bidderId, BigDecimal amount) {
        Auction auction = auctions.get(auctionId);
        if (auction == null) throw new AuctionNotFoundException(auctionId);
        User bidder = users.get(bidderId);
        if (bidder == null) throw new AuctionException("Unknown user " + bidderId);
        return auction.placeBid(bidder, amount);
    }

    List<Auction> search(SearchCriteria c) {
        return auctions.values().stream()
                .filter(a -> c.keyword == null
                        || a.getItem().getName().toLowerCase().contains(c.keyword.toLowerCase()))
                .filter(a -> c.category == null || a.getItem().getCategory() == c.category)
                .filter(a -> c.status == null || a.getStatus() == c.status)
                .filter(a -> {
                    BigDecimal price = a.getHighestBid().map(Bid::getAmount)
                                        .orElse(a.getStartingPrice());
                    return (c.minPrice == null || price.compareTo(c.minPrice) >= 0)
                        && (c.maxPrice == null || price.compareTo(c.maxPrice) <= 0);
                })
                .collect(Collectors.toList());
    }

    void shutdown() { scheduler.shutdownNow(); }
}

/** Simple value object; a Builder is warranted if criteria grow. */
class SearchCriteria {
    String keyword;
    Category category;
    BigDecimal minPrice, maxPrice;
    AuctionStatus status;
}
```

### Demo / driver

```java
import java.time.Duration;

public class AuctionDemo {
    public static void main(String[] args) throws Exception {
        AuctionService svc = AuctionService.getInstance();
        AuctionEventListener notifier = new ConsoleNotificationService();

        User seller = svc.registerUser("Sara", "sara@x.com");
        User alice  = svc.registerUser("Alice", "alice@x.com");
        User bob    = svc.registerUser("Bob", "bob@x.com");

        Auction a = svc.createAuction(seller,
                new Item("Vintage Camera", "1970s SLR", Category.COLLECTIBLES),
                new BigDecimal("100.00"), Duration.ofSeconds(2),
                new HigherThanCurrentPolicy(), notifier);

        svc.placeBid(a.getId(), alice.getId(), new BigDecimal("100.00"));
        svc.placeBid(a.getId(), bob.getId(),   new BigDecimal("120.00")); // Alice notified: outbid

        Thread.sleep(2500); // auction auto-closes; everyone notified, Bob wins
        System.out.println("Final status: " + a.getStatus()
                + ", winner bid: " + a.getHighestBid().map(Bid::getAmount).orElse(null));
        svc.shutdown();
    }
}
```
---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Exception strategy

- A small **domain exception hierarchy** rooted at `AuctionException` (unchecked): `InvalidBidException`, `AuctionNotActiveException`, `AuctionNotFoundException`. Unchecked because callers cannot meaningfully recover mid-call — they translate to a user-facing error (in Spring: an `@ControllerAdvice` mapping to 400/404/409).
- **Fail fast at construction:** negative starting price and past end-times are rejected in the `Auction` constructor — invalid auctions can never exist.
- **Validation lives where the invariant lives:** `placeBid` enforces auction invariants inside the aggregate, not in the service, so no code path can bypass them.

### Edge cases

| Case | Handling |
|---|---|
| First bid below starting price | Rejected by policy (first bid must be ≥ starting price). |
| Bid equal to current highest | Rejected — must be strictly greater (`compareTo > 0`, never `==` on `BigDecimal`). |
| Seller bids on own auction | `InvalidBidException`, checked before taking the lock (immutable data, no lock needed). |
| Highest bidder re-bids higher | Allowed; we skip the self-outbid notification (`outbidUser == bidder` check). |
| Auction with zero bids ends | Closes with `Optional.empty()` winner — "unsold", listeners still notified. |
| Cancel after a bid exists | Rejected (F8): bidders have a legitimate expectation the auction completes. |
| Bid arrives after end-time but before scheduler fires | Defense-in-depth time check inside the lock rejects it — we never rely solely on the scheduler's punctuality. |
| `close()` called twice (scheduler + manual) | Idempotent: second call sees `status != ACTIVE` and returns. |
| Money as `double` | Avoided entirely — `BigDecimal` with `compareTo`, the only defensible choice for currency. |

### Concurrency analysis (the part the interviewer cares about)

**Shared mutable state:** per auction — `status`, `highestBid`, `bids`. Per service — the `users`/`auctions` registries.

**Critical sections:**
1. `placeBid`: *check status → check time → validate against current highest → write highestBid + bids*. If this is not atomic, two threads can both validate against the same "current highest" of 100, both write, and one update is silently lost — both bidders believe they lead. Classic **check-then-act race**.
2. `close()`: *check status → set CLOSED → snapshot winner*. Must be mutually exclusive with `placeBid`, or a bid can slip in after the winner snapshot — the "bid after close" anomaly from requirement F7.

**Primitive chosen and why:**
- **One `ReentrantLock` per `Auction`** guarding both critical sections. Granularity is the point: bids on different auctions never contend (a global lock would serialize the whole marketplace). `ReentrantLock` over `synchronized` here for explicitness and the option to upgrade to `tryLock(timeout)` or a fairness policy; with the current code, `synchronized(this)` blocks would be functionally equivalent — say so, it shows you know the primitives are interchangeable at this level.
- **`ConcurrentHashMap`** for the registries: per-key atomic puts/gets, no global service lock.
- **`CopyOnWriteArrayList`** for listeners: iterated on every event, mutated rarely — the exact read-heavy profile COW is built for, and it lets us iterate safely without holding the auction lock.
- **`ScheduledExecutorService`** (daemon threads) for auto-close — the textbook replacement for `Timer` (which dies on one uncaught exception and uses a single thread).

**Why notifications happen *outside* the lock:** listener code is foreign code — it may block on I/O (email), be slow, or call back into the auction (`getHighestBid()` is lock-acquiring). Calling alien code while holding a lock is the canonical recipe for deadlock and contention. We compute the event payload inside the lock, release, then notify. Trade-off to state aloud: notifications may arrive slightly out of order under heavy contention; the *state* is still strictly consistent, and ordering of courtesy notifications is not an invariant we promised.

**Deadlock-freedom argument:** the system only ever holds **one lock at a time** — an auction's own lock; no thread acquires lock A then lock B. The service maps are lock-free to the caller. Listener callbacks run lock-free. With a single-lock discipline there is no circular wait, hence no deadlock. No spinning/retry loops exist, so no livelock. Race freedom follows from every read-modify-write on auction state being inside that auction's lock, and reads taking the same lock for a consistent snapshot.

**What changes at real scale (distributed):** a JVM lock no longer protects anything across nodes. The same invariant is then enforced with **optimistic concurrency** (`UPDATE auction SET highest=?, version=version+1 WHERE id=? AND version=?` — retry on miss), a DB row lock (`SELECT ... FOR UPDATE`), or by **serializing all bids for one auction through a single partition** of a log (Kafka key = auctionId). The invariant is unchanged; only the enforcement mechanism moves.

---

## Interviewer follow-ups (with model answers)

1. **"Add proxy/auto-bidding (bid up to $X for me)."** Store a `maxAmount` per user per auction; when an outbid event occurs, the auction (inside the same lock, or via a re-entrant internal method) raises the proxy holder's bid by the increment up to their max. It composes naturally as another `BidValidationPolicy` + an internal listener — Strategy and Observer pay off.
2. **"Add a Dutch (descending price) auction."** Extract an `Auction` interface or abstract base with `placeBid`/`close`; `EnglishAuction` and `DutchAuction` differ in price progression and win condition. The Strategy seam (validation) and Observer seam (events) are reused as-is — that's the extensibility we designed for.
3. **"Sniping: bids in the last 5 seconds."** Soft-close / anti-sniping: inside `placeBid`'s lock, if `now > endTime - window`, extend `endTime` and reschedule the close task (make `endTime` mutable under the lock; cancel and re-schedule the `ScheduledFuture`).
4. **"Make this survive 10x load / multiple servers."** Per-auction locking already shards contention in-JVM. Across nodes: move the invariant to the datastore (optimistic versioning or row locks) or partition bid streams by auctionId; cache reads (search) separately from the write path; notifications go async via a queue.
5. **"Why not `synchronized` / why not `AtomicReference<Bid>`?"** `synchronized` would work (single guarded region); `ReentrantLock` adds tryLock/fairness options. A lone `AtomicReference` CAS on `highestBid` cannot atomically couple the status check, time check, policy validation, and history append — the invariant spans multiple fields, so it needs a lock, not a single CAS.

## Transferable lessons

- **Invariants that span multiple fields ⇒ one lock around the whole check-then-act**, scoped as narrowly as possible (per-entity, not global). Reappears in Parking Lot (spot allocation), Movie Ticket Booking (seat locking), and every Concurrency-section problem.
- **Never call alien code (listeners) while holding a lock.** Reappears anywhere Observer meets threads.
- **Strategy for the volatile rule, Observer for the fan-out, enum-State for small lifecycles** — the same trio drives Vending Machine, Elevator, and Splitwise.

**Next up:** suggested next problem — **Movie Ticket Booking System** (same lock-the-invariant idea, but with *multiple* resources per transaction: seat-set locking and lock-ordering, the natural escalation from this one).
