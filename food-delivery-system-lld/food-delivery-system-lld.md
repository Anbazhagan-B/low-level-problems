# Online Food Delivery System (Swiggy-like) — Low-Level Design

> **Tier:** Hard | **Language:** Java 11+ | **Scope:** In-memory LLD, interview-ready
>
> **What this problem uniquely adds to your toolkit:** a *matching* problem (orders ↔ delivery agents) where the contended resource is a *person* who can say no. Concert Ticket taught you to claim seats atomically; this teaches you to claim an agent atomically **and then survive the agent declining** — a two-step handshake with rollback, which is the in-memory cousin of distributed two-phase commit.

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|---|
| FR1 | Customers can browse restaurants, view menus, and place an order containing items from **one** restaurant. |
| FR2 | Restaurants manage their menu: add/update items, change prices, mark items unavailable, open/close the restaurant. |
| FR3 | Restaurants accept or reject incoming orders, and progress accepted orders through preparation. |
| FR4 | The system auto-assigns a delivery agent when the restaurant **accepts** an order, using a pluggable assignment strategy (default: nearest available agent). |
| FR5 | Agents receive an **offer** they may accept or decline; on decline, the system retries with the next candidate. |
| FR6 | Order lifecycle: `PLACED → ACCEPTED → PREPARING → READY_FOR_PICKUP → OUT_FOR_DELIVERY → DELIVERED`, with `REJECTED` and `CANCELLED` as terminal branches. Every transition is validated. |
| FR7 | Multiple payment methods (Card, UPI, Cash-on-Delivery) behind one abstraction. Prepaid orders are created only after payment succeeds; COD defers capture to delivery. |
| FR8 | Customers may cancel only **before** the restaurant accepts. |
| FR9 | Customer, restaurant, and agent each receive notifications for the order events relevant to them. |

### Non-Functional Requirements

| # | Requirement | Design consequence |
|---|---|---|
| NFR1 | **Concurrency:** many simultaneous orders; one agent pool. An agent must never be double-assigned; an order must never have two agents. | CAS on agent status + per-order lock. |
| NFR2 | **Consistency:** order status, payment, and assignment never disagree. | All order mutations go through one guarded transition method. |
| NFR3 | **Extensibility:** new payment methods, assignment policies, notification channels — without touching core logic. | Strategy + Observer; depend on interfaces (DIP). |
| NFR4 | **Scalability story:** no global locks; per-entity synchronization; stateless service over concurrent registries. | `ConcurrentHashMap` registries, lock-per-order, CAS-per-agent. |
| NFR5 | **Historical integrity:** menu price changes must not corrupt in-flight or past orders. | `OrderItem` snapshots name + price at order time (immutable). |

### Assumptions (stated to the interviewer up front)

1. One order = one restaurant (Swiggy model). Multi-restaurant carts are out of scope.
2. Assignment happens **on restaurant acceptance** so the agent travels while food cooks. (Alternative — assign-on-ready — is discussed in Step 5; know both.)
3. No-agent-available leaves the order progressing normally; a retry hook exists but a full retry scheduler is out of scope.
4. Geo is modeled as `Location(lat, lng)` with straight-line distance — enough to make "nearest agent" real without a geospatial index.
5. In-memory storage; payment gateway and push infrastructure are interfaces with stub implementations.

---

## Step 2 — Entities & Relationships

### Core entities

| Entity | Responsibility | Why it exists as a class |
|---|---|---|
| `Customer` | Identity + delivery address; receives notifications. | Actor with state (address) and behavior (observer). |
| `Restaurant` | Owns a menu, open/closed flag, accepts/rejects orders. | Aggregate root for menu mutations. |
| `MenuItem` | Catalog entry: name, current price, availability flag. | Mutable catalog data — deliberately **separate** from order data. |
| `OrderItem` | **Immutable snapshot**: item name, unit price *at order time*, quantity. | The item-type/instance split again (Library `Book`/`BookCopy`, Car Rental): catalog mutations must not rewrite history. |
| `Order` | The aggregate: items, customer, restaurant, status, payment, assigned agent. Owns its own lock. | The unit of consistency — everything that must change together lives here. |
| `DeliveryAgent` | Location, atomic status (`OFFLINE/AVAILABLE/OFFERED/DELIVERING`), current order. | The contended resource. Its status field **is** the concurrency mechanism. |
| `Payment` | Amount, method name, status, timestamp. | Audit record; immutable once terminal. |
| `Location` | Immutable `(lat, lng)` value object with `distanceTo()`. | Value object — no identity, safely shared. |

### Deliberately NOT modeled (under-modeling is a skill)

- **No `Cart` entity.** The order placement API takes `Map<itemId, quantity>` directly. A cart is session state, not domain state — in an interview, *say* you'd add it for a real product, but it adds nothing to the core design. (Same instinct as no `Like`/`Friendship` entities in the social-network problems.)
- **No `Menu` class.** A menu is just `Restaurant`'s map of items. Wrapping it in a class adds a layer with no behavior.
- **No `Assignment` entity.** The assignment is a relationship: `Order.assignedAgent` + `DeliveryAgent.currentOrderId`. Reifying it into a class would only be justified if assignments had their own lifecycle/audit needs.

### Relationships

| Relationship | Type | Why |
|---|---|---|
| `Restaurant` ─◆ `MenuItem` | **Composition** | Menu items have no meaning outside their restaurant; deleting the restaurant deletes the menu. |
| `Order` ─◆ `OrderItem` | **Composition** | Order items are created by and die with the order; immutable parts of the aggregate. |
| `Order` → `Customer`, `Order` → `Restaurant` | **Association** | Order references them by identity; both exist independently of any order. |
| `Order` → `DeliveryAgent` | **Association** (nullable, set late) | Bound during the handshake; agent outlives the order. |
| `Order` ─◆ `Payment` | **Composition** | Payment record belongs to exactly one order. |
| `FoodDeliveryService` → `AssignmentStrategy`, `PaymentStrategy` | **Dependency / aggregation** | Injected behaviors (Strategy); swappable at runtime. |
| `FoodDeliveryService` → `NotificationService` | **Aggregation** | Service publishes events; channel implementations vary. |

> **Foundations refresher — composition vs aggregation:** composition is exclusive ownership with shared lifecycle (the part cannot be re-parented and dies with the whole: `Order`→`OrderItem`); aggregation is a whole-part grouping where the part lives independently (`Service`→`Strategy`). Plain association is mere acquaintance by reference (`Order`→`Customer`). Interviewers probe this with "what happens to X when you delete Y?" — answer from lifecycle, not from UML arrow memorization.

---

## Step 3 — UML Class Design

```
                        «enum» OrderStatus                 «enum» AgentStatus
                        PLACED, ACCEPTED, PREPARING,       OFFLINE, AVAILABLE,
                        READY_FOR_PICKUP,                  OFFERED, DELIVERING
                        OUT_FOR_DELIVERY, DELIVERED,
                        CANCELLED, REJECTED
                        + canTransitionTo(next): boolean

+--------------------------+        +-----------------------------------+
| Customer                 |        | Restaurant                        |
+--------------------------+        +-----------------------------------+
| - id: String             |        | - id, name: String                |
| - name: String           |        | - location: Location              |
| - address: Location      |        | - menu: ConcurrentHashMap<String, |
+--------------------------+        |          MenuItem>                |
| + onOrderUpdate(...)     |        | - open: volatile boolean          |
+--------------------------+        +-----------------------------------+
        ▲ implements                | + addItem/updatePrice/setAvail.   |
        |                           | + onOrderUpdate(...)              |
  «interface» OrderObserver         +-----------------------------------+
  + onOrderUpdate(order, status)             ▲ implements
        ▲ implements                          ◆ composition (menu)
        |                           +------------------+
+---------------------------+      | MenuItem         |
| DeliveryAgent             |      | - id, name       |
+---------------------------+      | - price: volatile|
| - id, name                |      | - available: vol.|
| - location: volatile      |      +------------------+
| - status: AtomicReference |
|     <AgentStatus>         |      +-----------------------------------+
| - currentOrderId: volatile|      | Order                             |
+---------------------------+      +-----------------------------------+
| + tryOffer(): boolean     |      | - id: String                      |
| + acceptOffer(): boolean  |      | - customer: Customer        (assoc)|
| + declineOffer()          |      | - restaurant: Restaurant    (assoc)|
| + completeDelivery()      |      | - items: List<OrderItem>    ◆     |
+---------------------------+      | - status: OrderStatus             |
                                   | - agent: DeliveryAgent (nullable) |
+---------------------------+      | - payment: Payment          ◆     |
| OrderItem  «immutable»    |◆-----| - lock: ReentrantLock             |
| - itemName, unitPrice, qty|      +-----------------------------------+
| + lineTotal()             |      | + transitionTo(next): void        |
+---------------------------+      | + getTotal(): BigDecimal          |
                                   +-----------------------------------+

«interface» PaymentStrategy                «interface» AssignmentStrategy
+ pay(amount): PaymentResult               + rank(order, candidates):
        ▲                                        List<DeliveryAgent>
        | implements                               ▲
  CardPayment, UpiPayment,                         | implements
  CashOnDeliveryPayment                    NearestAgentStrategy
                                           (LeastLoadedStrategy, ...)

+------------------------------------------------------------------+
| FoodDeliveryService  «singleton, holder idiom»                   |
+------------------------------------------------------------------+
| - customers/restaurants/agents/orders: ConcurrentHashMap         |
| - assignmentStrategy: AssignmentStrategy                         |
| - notifier: NotificationService                                  |
+------------------------------------------------------------------+
| + placeOrder(custId, restId, items, paymentStrategy): Order      |
| + cancelOrder(orderId, customerId)                               |
| + acceptOrder(orderId) / rejectOrder(orderId)                    |
| + markPreparing/markReady(orderId)                               |
| + agentAccepts(agentId, orderId) / agentDeclines(...)            |
| + markPickedUp/markDelivered(orderId)                            |
+------------------------------------------------------------------+
```

### Pattern → reason mapping (the "exactly why", not just the name)

| Pattern | Where | Why it fits *here* |
|---|---|---|
| **Strategy** | `PaymentStrategy`, `AssignmentStrategy` | Both are axes of *pure policy variation* behind a stable interface: the order flow doesn't care *how* money is captured or *which* agent ranks first. Adding "rating-weighted assignment" or "wallet payment" means a new class, zero edits to `FoodDeliveryService` — that's OCP in action, and it's the textbook trigger for Strategy. |
| **Observer** | `OrderObserver` implemented by `Customer`, `Restaurant`, `DeliveryAgent` | One event (status change) → three differently-interested parties, and the set of listeners varies per order. Hard-coding `notifyCustomer(); notifyRestaurant(); notifyAgent();` couples the order flow to every party type forever; Observer inverts it so the order just publishes. Same shape as Auction outbid alerts and Airline flight updates. |
| **State (enum-guarded)** | `OrderStatus.canTransitionTo` + `Order.transitionTo` | The lifecycle is rich enough to need validation but each state has **no state-specific behavior** beyond "which transitions are legal" — so a full GoF State pattern (one class per state) would be eight classes of ceremony for one table of rules. The enum + transition map gives the same safety in 20 lines. *Say this trade-off out loud in the interview; it shows judgment, and be ready to upgrade to full State if asked "what if PREPARING orders need per-state behavior?"* |
| **Singleton (holder idiom)** | `FoodDeliveryService` | One coordinator over the registries. Holder idiom over `static synchronized getInstance()`: lazy, thread-safe via class-loading guarantees, zero lock overhead on every call. In Spring you'd skip this entirely — default bean scope *is* singleton, managed by the container; mention that, then keep the design framework-agnostic. |
| **Facade** | `FoodDeliveryService`'s public API | Callers see `placeOrder(...)`, not "validate, snapshot, pay, persist, notify." The facade is also where the *lock choreography* lives, which keeps locking rules in one auditable place. |

### SOLID mapping

- **S:** `Order` guards its own consistency; `Restaurant` owns menu rules; strategies own policy; the service only *coordinates*. No god class doing validation + payment + matching.
- **O:** New payment method / assignment policy / notification channel = new class, no modification.
- **L:** Every `PaymentStrategy` is substitutable — including COD, which honors the contract by returning a *deferred-success* result rather than throwing. (Designing COD so it doesn't violate LSP is a nice talking point.)
- **I:** `OrderObserver` is one method. Agents aren't forced to implement menu-management methods; small role interfaces per actor.
- **D:** `FoodDeliveryService` depends on `PaymentStrategy`/`AssignmentStrategy`/`NotificationService` abstractions, injected at construction. Spring parallel: constructor injection of `@Component` beans.

### The two decisions an interviewer will probe

1. **How do you guarantee an agent is never double-assigned without a global lock?**
   The agent's status is an `AtomicReference<AgentStatus>`, and claiming an agent is a single CAS: `status.compareAndSet(AVAILABLE, OFFERED)`. Two orders racing for the same agent both call `tryOffer()`; exactly one CAS wins, the loser moves to the next candidate. No lock is held while iterating candidates, so there's no lock to deadlock on. This is the same atomic check-and-set you used for Concert Ticket seats — but here the claim is *provisional* (the agent can decline), which forces the compensation path: `declineOffer()` CASes `OFFERED → AVAILABLE` and the service retries.

2. **What happens when a cancel races the restaurant's accept?**
   Both operations funnel through `order.transitionTo(...)` under the order's `ReentrantLock`. Whichever thread acquires the lock first wins; the loser finds the order in a state from which its transition is illegal and gets `InvalidOrderStateException`. The lock makes the race *serializable*; the transition table makes the loser's failure *explicit* rather than silent corruption. One lock per order → contention only between operations on the *same* order, which is the per-resource-locking lesson from Parking Lot onward.

---

## Step 4 — Implementation (with demo)

> Compilable Java 11+. Boilerplate getters trimmed where uninteresting; everything concurrency- or pattern-relevant is complete.

### Enums and value objects

```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.math.BigDecimal;
import java.time.Instant;

// ---------- Order lifecycle: the transition table IS the state machine ----------
enum OrderStatus {
    PLACED, ACCEPTED, PREPARING, READY_FOR_PICKUP,
    OUT_FOR_DELIVERY, DELIVERED, CANCELLED, REJECTED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
        PLACED,            Set.of(ACCEPTED, REJECTED, CANCELLED),
        ACCEPTED,          Set.of(PREPARING),
        PREPARING,         Set.of(READY_FOR_PICKUP),
        READY_FOR_PICKUP,  Set.of(OUT_FOR_DELIVERY),
        OUT_FOR_DELIVERY,  Set.of(DELIVERED),
        DELIVERED,         Set.of(),
        CANCELLED,         Set.of(),
        REJECTED,          Set.of()
    );

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }
}

enum AgentStatus { OFFLINE, AVAILABLE, OFFERED, DELIVERING }

enum PaymentStatus { SUCCESS, FAILED, PENDING_ON_DELIVERY }

// Immutable value object: no identity, safe to share across threads freely.
final class Location {
    private final double lat, lng;
    Location(double lat, double lng) { this.lat = lat; this.lng = lng; }
    double distanceTo(Location o) {
        double dx = lat - o.lat, dy = lng - o.lng;
        return Math.sqrt(dx * dx + dy * dy);   // straight-line is enough for LLD
    }
    @Override public String toString() { return "(" + lat + "," + lng + ")"; }
}
```

### Catalog vs. snapshot — the item-type/instance split

```java
// Mutable CATALOG entry. volatile: price/availability are single references/
// primitives written by one actor (the restaurant) and read by many — we need
// visibility, not atomic compound updates, so volatile beats a lock here.
class MenuItem {
    private final String id, name;
    private volatile BigDecimal price;
    private volatile boolean available = true;

    MenuItem(String id, String name, BigDecimal price) {
        this.id = id; this.name = name; this.price = price;
    }
    String getId() { return id; }
    String getName() { return name; }
    BigDecimal getPrice() { return price; }
    boolean isAvailable() { return available; }
    void setPrice(BigDecimal p) { this.price = p; }
    void setAvailable(boolean a) { this.available = a; }
}

// Immutable SNAPSHOT. Once the order exists, restaurant price edits cannot
// touch it. Immutability also means zero synchronization needed to read it.
final class OrderItem {
    private final String itemName;
    private final BigDecimal unitPrice;
    private final int quantity;

    OrderItem(MenuItem item, int quantity) {
        this.itemName = item.getName();
        this.unitPrice = item.getPrice();   // snapshot happens HERE
        this.quantity = quantity;
    }
    BigDecimal lineTotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
    @Override public String toString() { return quantity + "x " + itemName + " @" + unitPrice; }
}
```

### Actors

```java
// One-method role interface (ISP): every party observes orders the same way.
interface OrderObserver {
    void onOrderUpdate(Order order, OrderStatus newStatus);
}

class Customer implements OrderObserver {
    private final String id, name;
    private final Location address;
    Customer(String id, String name, Location address) {
        this.id = id; this.name = name; this.address = address;
    }
    String getId() { return id; }
    Location getAddress() { return address; }
    @Override public void onOrderUpdate(Order o, OrderStatus s) {
        System.out.println("[notify customer " + name + "] order " + o.getId() + " -> " + s);
    }
}

class Restaurant implements OrderObserver {
    private final String id, name;
    private final Location location;
    private final ConcurrentHashMap<String, MenuItem> menu = new ConcurrentHashMap<>();
    private volatile boolean open = true;

    Restaurant(String id, String name, Location location) {
        this.id = id; this.name = name; this.location = location;
    }
    String getId() { return id; }
    Location getLocation() { return location; }
    boolean isOpen() { return open; }
    void setOpen(boolean o) { this.open = o; }

    void addItem(MenuItem item) { menu.put(item.getId(), item); }
    Optional<MenuItem> getItem(String itemId) { return Optional.ofNullable(menu.get(itemId)); }
    Collection<MenuItem> getMenu() { return Collections.unmodifiableCollection(menu.values()); }

    @Override public void onOrderUpdate(Order o, OrderStatus s) {
        System.out.println("[notify restaurant " + name + "] order " + o.getId() + " -> " + s);
    }
}
```

### DeliveryAgent — the CAS handshake (heart of the problem)

```java
class DeliveryAgent implements OrderObserver {
    private final String id, name;
    private volatile Location location;           // agent moves; visibility only
    private final AtomicReference<AgentStatus> status =
        new AtomicReference<>(AgentStatus.OFFLINE);
    private volatile String currentOrderId;

    DeliveryAgent(String id, String name, Location location) {
        this.id = id; this.name = name; this.location = location;
    }
    String getId() { return id; }
    String getName() { return name; }
    Location getLocation() { return location; }
    AgentStatus getStatus() { return status.get(); }

    void goOnline()  { status.compareAndSet(AgentStatus.OFFLINE, AgentStatus.AVAILABLE); }
    void goOffline() { status.compareAndSet(AgentStatus.AVAILABLE, AgentStatus.OFFLINE); }

    /**
     * The whole double-assignment guarantee is this one line. If two orders
     * race for this agent, exactly one CAS succeeds — lock-free, wait-free,
     * and there is nothing held that could deadlock.
     */
    boolean tryOffer(String orderId) {
        if (status.compareAndSet(AgentStatus.AVAILABLE, AgentStatus.OFFERED)) {
            this.currentOrderId = orderId;        // safe: we exclusively own
            return true;                          // the OFFERED state now
        }
        return false;
    }

    /** Provisional claim -> firm claim. Fails if offer was revoked meanwhile. */
    boolean acceptOffer() {
        return status.compareAndSet(AgentStatus.OFFERED, AgentStatus.DELIVERING);
    }

    /** Compensation: release the provisional claim so others can grab the agent. */
    void declineOffer() {
        currentOrderId = null;
        status.compareAndSet(AgentStatus.OFFERED, AgentStatus.AVAILABLE);
    }

    void completeDelivery() {
        currentOrderId = null;
        status.set(AgentStatus.AVAILABLE);
    }

    @Override public void onOrderUpdate(Order o, OrderStatus s) {
        System.out.println("[notify agent " + name + "] order " + o.getId() + " -> " + s);
    }
}
```

### Payment — Strategy

```java
final class PaymentResult {
    final PaymentStatus status; final String reference;
    PaymentResult(PaymentStatus status, String reference) {
        this.status = status; this.reference = reference;
    }
}

interface PaymentStrategy {
    PaymentResult pay(BigDecimal amount);
    String methodName();
}

class CardPayment implements PaymentStrategy {
    private final String maskedCard;
    CardPayment(String maskedCard) { this.maskedCard = maskedCard; }
    @Override public PaymentResult pay(BigDecimal amount) {
        // Real impl calls a gateway. NOTE: this is exactly why we never call
        // pay() while holding any lock — gateway latency is unbounded.
        return new PaymentResult(PaymentStatus.SUCCESS, "CARD-" + UUID.randomUUID());
    }
    @Override public String methodName() { return "CARD " + maskedCard; }
}

class UpiPayment implements PaymentStrategy {
    private final String vpa;
    UpiPayment(String vpa) { this.vpa = vpa; }
    @Override public PaymentResult pay(BigDecimal amount) {
        return new PaymentResult(PaymentStatus.SUCCESS, "UPI-" + UUID.randomUUID());
    }
    @Override public String methodName() { return "UPI " + vpa; }
}

// LSP point: COD substitutes cleanly by returning a deferred status instead
// of throwing UnsupportedOperationException (which WOULD violate LSP).
class CashOnDeliveryPayment implements PaymentStrategy {
    @Override public PaymentResult pay(BigDecimal amount) {
        return new PaymentResult(PaymentStatus.PENDING_ON_DELIVERY, "COD");
    }
    @Override public String methodName() { return "COD"; }
}

final class Payment {
    private final BigDecimal amount;
    private final String method, reference;
    private volatile PaymentStatus status;
    private final Instant createdAt = Instant.now();

    Payment(BigDecimal amount, String method, PaymentResult result) {
        this.amount = amount; this.method = method;
        this.reference = result.reference; this.status = result.status;
    }
    PaymentStatus getStatus() { return status; }
    void markCaptured() { this.status = PaymentStatus.SUCCESS; }   // COD at doorstep
}
```

### Order — the aggregate that defends itself

```java
class Order {
    private final String id;
    private final Customer customer;
    private final Restaurant restaurant;
    private final List<OrderItem> items;          // immutable after construction
    private final BigDecimal total;
    private final Payment payment;
    private volatile OrderStatus status = OrderStatus.PLACED;
    private volatile DeliveryAgent agent;         // null until handshake completes

    // ONE lock per order: operations on different orders never contend.
    private final ReentrantLock lock = new ReentrantLock();

    Order(String id, Customer c, Restaurant r, List<OrderItem> items, Payment p) {
        this.id = id; this.customer = c; this.restaurant = r;
        this.items = List.copyOf(items);
        this.total = items.stream().map(OrderItem::lineTotal)
                          .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.payment = p;
    }

    String getId() { return id; }
    Customer getCustomer() { return customer; }
    Restaurant getRestaurant() { return restaurant; }
    OrderStatus getStatus() { return status; }
    BigDecimal getTotal() { return total; }
    Payment getPayment() { return payment; }
    DeliveryAgent getAgent() { return agent; }

    /**
     * Single guarded gate for ALL status mutations. Lock serializes racing
     * transitions (cancel vs accept); the enum table rejects the loser loudly.
     */
    void transitionTo(OrderStatus next) {
        lock.lock();
        try {
            if (!status.canTransitionTo(next)) {
                throw new InvalidOrderStateException(
                    "Order " + id + ": illegal transition " + status + " -> " + next);
            }
            status = next;
        } finally {
            lock.unlock();
        }
    }

    void assignAgent(DeliveryAgent a) {
        lock.lock();
        try {
            if (this.agent != null)
                throw new IllegalStateException("Order " + id + " already has an agent");
            if (status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED)
                throw new InvalidOrderStateException("Order " + id + " is terminal");
            this.agent = a;
        } finally {
            lock.unlock();
        }
    }
}
```

### Exceptions

```java
class FoodDeliveryException extends RuntimeException {
    FoodDeliveryException(String m) { super(m); }
}
class RestaurantClosedException extends FoodDeliveryException {
    RestaurantClosedException(String m) { super(m); }
}
class ItemUnavailableException extends FoodDeliveryException {
    ItemUnavailableException(String m) { super(m); }
}
class InvalidOrderStateException extends FoodDeliveryException {
    InvalidOrderStateException(String m) { super(m); }
}
class PaymentFailedException extends FoodDeliveryException {
    PaymentFailedException(String m) { super(m); }
}
class NoAgentAvailableException extends FoodDeliveryException {
    NoAgentAvailableException(String m) { super(m); }
}
```

### Assignment strategy + notification

```java
interface AssignmentStrategy {
    /** Rank candidates best-first. Claiming is the SERVICE's job, not the
     *  strategy's — separating "policy" (ranking) from "mechanism" (CAS claim)
     *  keeps strategies trivially testable and free of concurrency code. */
    List<DeliveryAgent> rank(Order order, Collection<DeliveryAgent> candidates);
}

class NearestAgentStrategy implements AssignmentStrategy {
    @Override public List<DeliveryAgent> rank(Order order, Collection<DeliveryAgent> cands) {
        Location pickup = order.getRestaurant().getLocation();
        return cands.stream()
            .filter(a -> a.getStatus() == AgentStatus.AVAILABLE)   // cheap pre-filter;
            .sorted(Comparator.comparingDouble(                    // CAS re-checks anyway
                a -> a.getLocation().distanceTo(pickup)))
            .collect(java.util.stream.Collectors.toList());
    }
}

// Observer dispatch. CopyOnWriteArrayList: listener sets are read-heavy and
// rarely mutated — iteration is snapshot-safe with zero locking.
class NotificationService {
    void publish(Order order, OrderStatus status, OrderObserver... interested) {
        for (OrderObserver o : interested) {
            if (o != null) o.onOrderUpdate(order, status);
        }
    }
}
```

### FoodDeliveryService — facade, singleton, lock choreography

```java
class FoodDeliveryService {
    // Initialization-on-demand holder: lazy + thread-safe via JLS class-loading
    // guarantees, no synchronization cost on getInstance().
    private FoodDeliveryService() {}
    private static class Holder { static final FoodDeliveryService I = new FoodDeliveryService(); }
    public static FoodDeliveryService getInstance() { return Holder.I; }

    private final ConcurrentHashMap<String, Customer> customers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Restaurant> restaurants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeliveryAgent> agents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();

    private volatile AssignmentStrategy assignmentStrategy = new NearestAgentStrategy();
    private final NotificationService notifier = new NotificationService();
    private final AtomicInteger orderSeq = new AtomicInteger();

    // ---- registration (trivial) ----
    void register(Customer c) { customers.put(c.getId(), c); }
    void register(Restaurant r) { restaurants.put(r.getId(), r); }
    void register(DeliveryAgent a) { agents.put(a.getId(), a); }
    void setAssignmentStrategy(AssignmentStrategy s) { this.assignmentStrategy = s; }
    Order getOrder(String id) {
        return Optional.ofNullable(orders.get(id))
            .orElseThrow(() -> new FoodDeliveryException("No such order " + id));
    }

    // ---- customer: place order ----
    Order placeOrder(String customerId, String restaurantId,
                     Map<String, Integer> itemQuantities, PaymentStrategy paymentStrategy) {
        Customer customer = Optional.ofNullable(customers.get(customerId))
            .orElseThrow(() -> new FoodDeliveryException("Unknown customer"));
        Restaurant restaurant = Optional.ofNullable(restaurants.get(restaurantId))
            .orElseThrow(() -> new FoodDeliveryException("Unknown restaurant"));

        if (!restaurant.isOpen())
            throw new RestaurantClosedException(restaurant.getId() + " is closed");
        if (itemQuantities.isEmpty())
            throw new FoodDeliveryException("Order must contain at least one item");

        // Validate + snapshot in one pass. Best-effort availability check:
        // a race with the restaurant marking sold-out right now is accepted
        // risk — the restaurant's accept/reject step is the real arbiter.
        List<OrderItem> snapshot = new ArrayList<>();
        for (var e : itemQuantities.entrySet()) {
            if (e.getValue() <= 0)
                throw new FoodDeliveryException("Quantity must be positive");
            MenuItem item = restaurant.getItem(e.getKey())
                .orElseThrow(() -> new ItemUnavailableException("No item " + e.getKey()));
            if (!item.isAvailable())
                throw new ItemUnavailableException(item.getName() + " is sold out");
            snapshot.add(new OrderItem(item, e.getValue()));
        }

        BigDecimal total = snapshot.stream().map(OrderItem::lineTotal)
                                   .reduce(BigDecimal.ZERO, BigDecimal::add);

        // PAY BEFORE CREATE, and with NO locks held: payment hits an external
        // gateway with unbounded latency (Airline two-phase lesson).
        PaymentResult result = paymentStrategy.pay(total);
        if (result.status == PaymentStatus.FAILED)
            throw new PaymentFailedException("Payment declined for " + total);

        Order order = new Order("ORD-" + orderSeq.incrementAndGet(), customer, restaurant,
                                snapshot, new Payment(total, paymentStrategy.methodName(), result));
        orders.put(order.getId(), order);
        notifier.publish(order, OrderStatus.PLACED, customer, restaurant);
        return order;
    }

    // ---- customer: cancel (pre-acceptance only — the transition table enforces it) ----
    void cancelOrder(String orderId, String customerId) {
        Order order = getOrder(orderId);
        if (!order.getCustomer().getId().equals(customerId))
            throw new FoodDeliveryException("Only the ordering customer may cancel");
        order.transitionTo(OrderStatus.CANCELLED);   // throws if already ACCEPTED
        notifier.publish(order, OrderStatus.CANCELLED,
                         order.getCustomer(), order.getRestaurant());
        // Real system: trigger refund via payment.reference here.
    }

    // ---- restaurant: accept / reject ----
    void acceptOrder(String orderId) {
        Order order = getOrder(orderId);
        order.transitionTo(OrderStatus.ACCEPTED);    // loses cleanly to a racing cancel
        notifier.publish(order, OrderStatus.ACCEPTED, order.getCustomer());
        offerToBestAgent(order);                     // outside the order lock
    }

    void rejectOrder(String orderId) {
        Order order = getOrder(orderId);
        order.transitionTo(OrderStatus.REJECTED);
        notifier.publish(order, OrderStatus.REJECTED, order.getCustomer());
        // Real system: refund.
    }

    /**
     * Claim loop: strategy ranks (policy), service CAS-claims (mechanism).
     * We hold NO lock while iterating — failure to claim just means another
     * order won that agent; we move on. Lock-free competition, no deadlock
     * possible because nothing is held.
     */
    private void offerToBestAgent(Order order) {
        for (DeliveryAgent candidate : assignmentStrategy.rank(order, agents.values())) {
            if (candidate.tryOffer(order.getId())) {
                System.out.println("[system] offered " + order.getId()
                                   + " to agent " + candidate.getName());
                notifier.publish(order, order.getStatus(), candidate);
                return;                              // provisional claim made
            }
        }
        // Soft failure: order proceeds (food gets cooked); a real system would
        // enqueue a retry on a ScheduledExecutorService. Hook, not exception.
        System.out.println("[system] no agent available yet for " + order.getId());
    }

    // ---- agent handshake ----
    void agentAccepts(String agentId, String orderId) {
        DeliveryAgent agent = requireAgent(agentId);
        Order order = getOrder(orderId);
        if (!agent.acceptOffer())
            throw new FoodDeliveryException("Offer no longer valid for agent " + agentId);
        try {
            order.assignAgent(agent);                // can fail if order got cancelled
        } catch (RuntimeException e) {
            agent.completeDelivery();                // COMPENSATE: free the agent
            throw e;
        }
        System.out.println("[system] agent " + agent.getName() + " locked in for " + orderId);
    }

    void agentDeclines(String agentId, String orderId) {
        DeliveryAgent agent = requireAgent(agentId);
        agent.declineOffer();                        // OFFERED -> AVAILABLE
        offerToBestAgent(getOrder(orderId));         // retry next candidate
    }

    // ---- progress updates (each = guarded transition + fan-out) ----
    void markPreparing(String orderId)  { progress(orderId, OrderStatus.PREPARING); }
    void markReady(String orderId)      { progress(orderId, OrderStatus.READY_FOR_PICKUP); }
    void markPickedUp(String orderId)   { progress(orderId, OrderStatus.OUT_FOR_DELIVERY); }

    void markDelivered(String orderId) {
        Order order = getOrder(orderId);
        order.transitionTo(OrderStatus.DELIVERED);
        if (order.getPayment().getStatus() == PaymentStatus.PENDING_ON_DELIVERY)
            order.getPayment().markCaptured();       // COD captured at doorstep
        DeliveryAgent agent = order.getAgent();
        if (agent != null) agent.completeDelivery(); // back in the pool
        notifier.publish(order, OrderStatus.DELIVERED,
                         order.getCustomer(), order.getRestaurant(), agent);
    }

    private void progress(String orderId, OrderStatus next) {
        Order order = getOrder(orderId);
        order.transitionTo(next);
        // Notify OUTSIDE the lock (transitionTo released it): observer code is
        // foreign code; calling it under our lock invites deadlock & latency.
        notifier.publish(order, next, order.getCustomer(),
                         order.getRestaurant(), order.getAgent());
    }

    private DeliveryAgent requireAgent(String id) {
        return Optional.ofNullable(agents.get(id))
            .orElseThrow(() -> new FoodDeliveryException("Unknown agent " + id));
    }
}
```

### Demo

```java
public class FoodDeliveryDemo {
    public static void main(String[] args) throws Exception {
        FoodDeliveryService svc = FoodDeliveryService.getInstance();

        // --- setup ---
        Customer anu = new Customer("C1", "Anu", new Location(12.95, 79.10));
        svc.register(anu);

        Restaurant dosaHouse = new Restaurant("R1", "Dosa House", new Location(12.96, 79.11));
        dosaHouse.addItem(new MenuItem("M1", "Masala Dosa", new BigDecimal("80")));
        dosaHouse.addItem(new MenuItem("M2", "Filter Coffee", new BigDecimal("30")));
        svc.register(dosaHouse);

        DeliveryAgent ravi = new DeliveryAgent("A1", "Ravi", new Location(12.955, 79.105));
        DeliveryAgent muthu = new DeliveryAgent("A2", "Muthu", new Location(13.20, 79.50));
        ravi.goOnline(); muthu.goOnline();
        svc.register(ravi); svc.register(muthu);

        // --- happy path ---
        Order order = svc.placeOrder("C1", "R1",
            Map.of("M1", 2, "M2", 1), new UpiPayment("anu@upi"));
        System.out.println("Placed " + order.getId() + " total=" + order.getTotal());

        // price change AFTER placing — order total must NOT move (snapshot proof)
        dosaHouse.getItem("M1").ifPresent(i -> i.setPrice(new BigDecimal("999")));
        System.out.println("After price hike, order total still = " + order.getTotal());

        svc.acceptOrder(order.getId());        // -> ACCEPTED, offers to Ravi (nearest)
        svc.agentDeclines("A1", order.getId());// Ravi busy -> retries, offers Muthu
        svc.agentAccepts("A2", order.getId()); // Muthu locked in
        svc.markPreparing(order.getId());
        svc.markReady(order.getId());
        svc.markPickedUp(order.getId());
        svc.markDelivered(order.getId());
        System.out.println("Muthu after delivery: " + muthu.getStatus()); // AVAILABLE

        // --- illegal transition is rejected loudly ---
        try { svc.cancelOrder(order.getId(), "C1"); }
        catch (InvalidOrderStateException e) { System.out.println("Expected: " + e.getMessage()); }

        // --- concurrency proof: N orders race for ONE available agent ---
        ravi.goOnline();                       // only Ravi is AVAILABLE now
        int racers = 8;
        var pool = Executors.newFixedThreadPool(racers);
        var start = new CountDownLatch(1);
        var wins = new AtomicInteger();
        for (int i = 0; i < racers; i++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                if (ravi.tryOffer("RACE-ORDER")) wins.incrementAndGet();
            });
        }
        start.countDown();
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.SECONDS);
        System.out.println("Racers=" + racers + ", successful claims=" + wins.get()); // always 1
    }
}
```

**Expected demo output (abridged):**

```
[notify customer Anu] order ORD-1 -> PLACED
[notify restaurant Dosa House] order ORD-1 -> PLACED
Placed ORD-1 total=190
After price hike, order total still = 190
[notify customer Anu] order ORD-1 -> ACCEPTED
[system] offered ORD-1 to agent Ravi
[system] offered ORD-1 to agent Muthu
[system] agent Muthu locked in for ORD-1
... PREPARING / READY_FOR_PICKUP / OUT_FOR_DELIVERY / DELIVERED fan-outs ...
Muthu after delivery: AVAILABLE
Expected: Order ORD-1: illegal transition DELIVERED -> CANCELLED
Racers=8, successful claims=1
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### 5.1 Exception design

- One unchecked root (`FoodDeliveryException`) with intent-revealing subclasses; callers catch by *failure category*, not by parsing messages.
- **Validation fails fast and before money moves:** unknown IDs, empty order, non-positive quantity, closed restaurant, sold-out item — all thrown before `pay()` is invoked.
- **Illegal transitions are loud, not silent:** `transitionTo` throws rather than no-ops. A silent no-op on `DELIVERED → CANCELLED` is how systems end up refunding delivered food.
- **Soft vs hard failures:** no-agent-available is *soft* (order proceeds, retry hook) because failing the order would punish the customer for a supply problem. Payment failure is *hard* (order never exists). Knowing which failures abort and which degrade is a senior-signal answer.

### 5.2 Edge cases

| Edge case | Handling |
|---|---|
| Price changed between browsing and ordering | `OrderItem` snapshots at placement; customer pays what they saw at click time (acceptable product call; alternatives: re-confirm if delta > X%). |
| Item sold out between browsing and ordering | Checked at placement → `ItemUnavailableException`. Race with the restaurant flipping the flag *during* placement is tolerated: restaurant accept/reject is the final arbiter. |
| Restaurant closes with orders in flight | `open=false` blocks *new* orders only; in-flight orders complete. |
| Customer cancels while restaurant clicks accept | Order lock serializes; transition table rejects the loser. No partial state either way. |
| Agent accepts an offer for an order that just got cancelled | `agentAccepts` does CAS first, then `assignAgent` under the order lock; if the order is terminal, we **compensate** by freeing the agent and rethrow. Claim-then-compensate, same shape as multi-seat booking rollback. |
| Agent app crashes while in `OFFERED` | In-memory gap: agent is stuck. Real fix: offer carries a TTL; a `ScheduledExecutorService` sweeps expired offers back to `AVAILABLE` and re-runs `offerToBestAgent` — exactly the Airline reserve-with-TTL pattern. Say this proactively. |
| COD order delivered | Payment flips `PENDING_ON_DELIVERY → SUCCESS` inside `markDelivered`, keeping payment/status consistent at the same instant. |
| Duplicate `placeOrder` from a double-tap | Not handled here; real fix is an idempotency key per client request. Name it. |

### 5.3 Concurrency analysis (the interview centerpiece)

**Shared mutable state inventory:**

| State | Written by | Protection |
|---|---|---|
| Registries (customers/restaurants/agents/orders) | registration, lookup | `ConcurrentHashMap` |
| `Order.status`, `Order.agent` | customer cancel, restaurant flow, agent flow | per-order `ReentrantLock`; `volatile` for lock-free reads |
| `DeliveryAgent.status` | competing orders, the agent | `AtomicReference` + CAS — the claim itself is the atomic operation |
| `MenuItem.price/available`, `Restaurant.open` | restaurant | `volatile` (single-writer, visibility-only) |
| Order id sequence | placement | `AtomicInteger` |

**Why CAS for agents but a lock for orders?** An agent claim is a *single-variable* state flip — CAS is sufficient, cheaper, and unblockable. An order transition is a *compound invariant* (status + agent + payment must stay mutually consistent), which needs a critical section — hence the lock. Choosing the lightest primitive that preserves each invariant, rather than one hammer everywhere, is the transferable skill.

**Critical sections, kept minimal:**
- `Order.transitionTo` / `assignAgent` — a few field checks and writes; microseconds.
- Nothing slow under any lock: payment runs before the order exists; notifications run after the lock is released; the agent claim loop holds nothing.

**Deadlock-freedom argument (rehearse this verbatim-ish):**
"No code path ever holds two locks. Order operations take exactly one order lock; agent claims are lock-free CAS; registries are internally synchronized but we never call out while inside them. With at most one lock held per thread, a circular wait is impossible, so deadlock is impossible by construction — I don't need lock ordering because I never nest."

**Livelock/starvation:** CAS failures don't retry on the *same* agent — the loser advances to the next candidate, so competing threads make progress past each other rather than colliding repeatedly. `ReentrantLock` in default (non-fair) mode is acceptable here because critical sections are tiny; mention `new ReentrantLock(true)` if asked about fairness.

**Race conditions closed:**
1. *Two orders, one agent:* one CAS wins — proven by the demo (8 racers, 1 claim).
2. *Cancel vs accept:* serialized by the order lock; loser throws.
3. *Agent accept vs order cancel:* compensation path frees the agent.
4. *Stale availability reads in the strategy:* harmless — the pre-filter is advisory; `tryOffer`'s CAS is the source of truth.

### 5.4 Scaling the story (when asked "what if 10x?")

- Per-order lock → per-order row/document with optimistic versioning (`@Version` in JPA terms); agent CAS → conditional update (`UPDATE agent SET status='OFFERED' WHERE id=? AND status='AVAILABLE'`) — the in-memory design maps 1:1 onto the distributed one.
- Notifications → message broker (Kafka/SNS) instead of in-process Observer; same publish/subscribe shape.
- Nearest-agent → geospatial index (Redis GEO / PostGIS); the `AssignmentStrategy` interface is unchanged.
- Offer TTL sweep → delayed-message queue instead of `ScheduledExecutorService`.

---

## Interviewer Follow-ups (with model answers)

**Q1. Add surge pricing / delivery-fee calculation that varies by demand.**
Introduce a `PricingStrategy { BigDecimal deliveryFee(Order, DemandSnapshot) }` injected into the service, applied at placement and snapshotted into the order like item prices. Strategy again, because fee policy varies independently of order flow; snapshotting again, because the quoted fee must not drift before payment.

**Q2. Agents should auto-reject offers after 30 seconds.**
Stamp `offerExpiresAt` when `tryOffer` succeeds; a `ScheduledExecutorService` task at offer time attempts `declineOffer()` after the TTL — CAS semantics make the sweep safe even if it races a genuine accept (`OFFERED→DELIVERING` already happened, so the sweep's `OFFERED→AVAILABLE` CAS just fails harmlessly). This is the two-phase reserve/TTL pattern from Airline, applied to a person instead of a seat.

**Q3. Support scheduled (future) orders.**
Add `scheduledFor` to `Order` and a `SCHEDULED` status preceding `PLACED` in the transition table; a scheduler promotes them at fire time. The enum-table state machine absorbs new states with one map entry — this is why we kept transitions data-driven.

**Q4. One customer must not have two concurrent COD orders (fraud rule).**
A per-customer check inside placement needs atomicity: `ConcurrentHashMap<customerId, AtomicInteger> activeCodCount` with `compute(...)`, or a per-customer lock. Point out that a naive `if (count(...) < 1) place(...)` is a classic check-then-act race.

**Q5. How would you test the concurrency?**
Deterministically: `CountDownLatch`-coordinated racers asserting exactly one `tryOffer` win (as in the demo); cancel-vs-accept with two threads asserting exactly one success and one `InvalidOrderStateException`. Stress: thousands of orders over a small agent pool, asserting no agent ever holds two orders (invariant check, not output check). Mention `jcstress` for memory-model-level confidence.

---

## Transferable Lessons

1. **Provisional claim + compensation (the offer handshake)** — the new pattern this problem adds: CAS claims a resource *tentatively*, and every downstream failure path must release it. This is saga/compensation thinking in miniature; it reappears in Ride Hailing (driver matching) and any marketplace problem.
2. **Policy/mechanism split in strategies** — `AssignmentStrategy` ranks; the service claims. Strategies stay concurrency-free and unit-testable.
3. **Lightest-primitive selection** — CAS for single-variable flips, lock for compound invariants, `volatile` for single-writer visibility. One problem, three primitives, each justified.
4. **Snapshot pattern, third appearance** — catalog vs. historical record (Library, Car Rental, now menu prices). If an interviewer's problem has a "catalog," ask yourself what must be frozen at transaction time.
5. **Never call foreign code under a lock** — payments before locks, notifications after locks. Same rule that drove Airline's two-phase booking.
