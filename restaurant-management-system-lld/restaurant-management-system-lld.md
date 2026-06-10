# Low Level Design — Restaurant Management System (Java)

> Problem #5 in our sequence (after Logging Framework, Vending Machine, Parking Lot, Task Management). Difficulty: **Hard**.
> What makes this one hard is not any single mechanism — it's **decomposition**: six subsystems that must stay modular without collapsing into a God class, plus our first *multi-resource atomic operation* (all-or-nothing ingredient deduction) and our first *producer-consumer* (the kitchen queue).

---

## Step 1 — Requirements

### Functional Requirements

1. **Menu** — maintain menu items (name, price, category, recipe); customers can view the menu.
2. **Orders** — place dine-in (at a table) or takeaway orders of one or more line items; lifecycle `PLACED → PREPARING → READY → SERVED → PAID`, with `CANCELLED` allowed only before preparation begins.
3. **Reservations** — reserve a table for a date + fixed time slot + party size; the same table cannot be double-booked for the same slot.
4. **Inventory** — track ingredient stock; each menu item has a recipe (ingredient → units per serving); placing an order deducts all required ingredients **atomically, all-or-nothing**; cancellation returns them.
5. **Kitchen workflow** — placed orders enter a queue; chef workers pull and prepare them (producer-consumer).
6. **Billing & payment** — a bill (subtotal + tax) is generated only for a SERVED order; payment via **cash, card, or mobile/UPI**; a bill can be paid exactly once.
7. **Staff** — staff with roles (`WAITER, CHEF, MANAGER, CASHIER`), shift schedules with overlap rejection, and a performance counter (orders handled).
8. **Reports** — daily sales report (count, revenue, breakdown by payment method) and inventory analysis (low-stock list).

### Non-Functional Requirements

| Concern | Decision |
|---|---|
| **Concurrency** | Many waiter terminals + chef threads run simultaneously. Hot spots: same-slot reservations, same-table claims, shared ingredient stock, status updates on one order, double payment of one bill. |
| **Consistency** | No double-booked slots, no negative or partially-deducted inventory, no order both cancelled *and* cooked, no bill paid twice. |
| **Extensibility (OCP)** | New payment method = new class; new report = new method on the report side; new order status = one row in the transition table. No `switch` statements that grow. |
| **Modularity** | Each subsystem is its own service object with one responsibility. The facade *coordinates*, it never *decides*. In Spring these services would be singleton `@Service` beans wired by constructor injection — which is exactly how the demo wires them by hand. |
| **Scope** | Single restaurant, single JVM, in-memory storage. Fixed 2-hour reservation slots (overlap check reduces to key equality). One payment per bill. |

### Assumptions (stated to the interviewer)

- Inventory is deducted **at placement**, not at preparation start. Trade-off: placement-time deduction guarantees an *accepted order is always cookable* (better customer promise) at the cost of holding stock for orders that may be cancelled (we compensate by returning stock on cancel). Preparation-time deduction wastes no stock on cancellations but can accept orders it later cannot cook — a worse failure mode for a restaurant.
- Reservations don't auto-occupy tables; occupancy is claimed when the party is seated (walk-in flow shown; linking the two is a follow-up).
- Reports are computed on demand from immutable in-memory sale records.

---

## Step 2 — Entities & Relationships

### Entities

| Entity | Responsibility |
|---|---|
| `MenuItem` | Immutable: id, name, price (long, paise — never floats for money), category, **recipe** (ingredientId → units/serving) |
| `Menu` | Catalog of menu items; read-mostly |
| `Ingredient` | id, name, mutable stock — stock guarded by `InventoryService`, not by itself |
| `InventoryService` | Owns all stock mutation; the atomic multi-ingredient `deduct` |
| `Customer` | id, name, phone — pure value holder |
| `RestaurantTable` | number, capacity, occupancy flag with atomic `occupy()` (named to avoid clashing with `java.sql`/AWT `Table`s in real codebases) |
| `Reservation` | immutable: customer, table, date, slot, party size |
| `ReservationService` | the (table, date, slot) booking book; atomic claim |
| `OrderItem` | immutable line: menu item, qty, **price snapshot** |
| `Order` | aggregate root: id, type, table, waiter, line items, mutable status behind a validated transition |
| `OrderStatus` (enum) | carries its own legal-transition table |
| `OrderService` | placement orchestration (deduct → register → enqueue), cancellation |
| `KitchenService` | `BlockingQueue<Order>` + chef worker threads |
| `Bill` | order, subtotal, tax, total, paid flag; `pay()` is the critical section |
| `PaymentStrategy` (+ Cash/Card/Mobile) | Strategy interface per payment method |
| `BillingService` | bill generation rules, settlement, sale recording |
| `Staff`, `Shift` | role, performance counter; shift value object with overlap test |
| `StaffService` | hiring, schedule with overlap rejection |
| `SaleRecord`, `ReportService` | immutable sale facts; on-demand aggregation |
| `RestaurantFacade` | thin coordination API for terminals |

### Relationships (and why each type)

| Relationship | Type | Why |
|---|---|---|
| `Order` → `OrderItem` | **Composition** | Line items are created inside the order, are immutable, and have no identity or life outside it. Delete the order, the lines are meaningless. |
| `Menu` → `MenuItem` | **Aggregation** | The menu holds items, but an item can be removed from the menu and still be referenced by historical orders — it outlives membership. |
| `OrderItem` → `MenuItem` | **Association** (+ snapshot) | The line *knows* the menu item but copies the price at order time. If we held only a live reference, a price change mid-meal would silently change a customer's bill — a temporal-consistency bug interviewers love to probe. |
| `MenuItem` → `Ingredient` | **Dependency** (by id) | The recipe references ingredient *ids*, not objects. Loose coupling: menu doesn't hold live stock objects, inventory remains the single writer of stock. |
| `Reservation` → `Customer`, `RestaurantTable` | **Association** | The reservation links two independent entities; neither owns the other. |
| `Order` → `RestaurantTable`, `Staff` | **Association** | Same: a table and a waiter exist independently of any order. |
| `KitchenService` → `Order` | **Dependency** | Orders flow *through* the queue transiently; the kitchen doesn't own them. |
| `Bill` → `Order` | **Association (1:1)** | A bill is derived from exactly one served order. Not composition — the order exists before and independently of its bill. |
| `StaffService` → `Shift` | **Composition** (per staff schedule) | Shifts exist only inside a staff member's schedule. |
| `BillingService` → `PaymentStrategy` | **Dependency** | Strategy arrives as a method parameter at settlement time — the classic "dependency, not field" form, because the method varies per transaction, not per service. |

**What candidates typically get wrong here** (critique I'd give if you'd attempted this step): (1) making `Order` hold live `MenuItem` prices instead of snapshots; (2) giving `Ingredient` its own synchronized methods — which makes *each* ingredient safe but the *set* deduction non-atomic (the real hazard); (3) modeling `Waiter`/`Chef` as subclasses of `Staff` — a role is data, not a type; subclassing blocks one person holding two roles and adds an inheritance tree with zero behavioral variance (composition/enum over inheritance).

---

## Step 3 — UML Class Design

```text
                            <<enum>> OrderStatus
                            PLACED PREPARING READY SERVED PAID CANCELLED
                            + canTransitionTo(next): boolean

+----------------+        +---------------------+       +--------------------+
|     Menu       |<>------|      MenuItem       |..... >|     Ingredient     |
+----------------+ 1    * +---------------------+ recipe+--------------------+
| addItem()      |        | id, name, price     | byId  | id, name, stock    |
| getItem(id)    |        | category            |       +--------------------+
| listAll()      |        | recipe: Map<id,int> |                ^ guarded by
+----------------+        +---------------------+                |
                                                        +--------------------+
+----------------+   1  * +---------------------+      |  InventoryService  |
|     Order      |◆-------|      OrderItem      |      +--------------------+
+----------------+        +---------------------+      | deduct(Map) ALL/NONE|
| id, type       |        | menuItem  ----------+----> | returnStock(Map)   |
| table, waiter  |        | qty, unitPrice(snap)|      | isAvailable(Map)   |
| status         |        | lineTotal()         |      | lowStock(thresh)   |
| transitionTo() |        +---------------------+      +--------------------+
| subtotal()     |
+----------------+        +---------------------+      +--------------------+
        ^ flows through   |   KitchenService    |      | ReservationService |
        '.................| queue:BlockingQueue |      +--------------------+
                          | chefPool: Executor  |      | book: CHM<key,Res> |
+----------------+        | submit(Order)       |      | reserve() putIfAbsent
|  OrderService  |------->| chefLoop(chef)      |      | cancel() remove(k,v)|
+----------------+        +---------------------+      +--------------------+
| placeOrder():            
|  deduct->register->enqueue                            +--------------------+
| cancelOrder()  |        +---------------------+      |  RestaurantTable   |
+----------------+        |        Bill         |      +--------------------+
                          +---------------------+      | occupy(): atomic   |
+----------------+  uses  | subtotal, tax, total|      | release()          |
| BillingService |------->| paid (guarded)      |      +--------------------+
+----------------+        | pay(strategy) SYNC  |
| generateBill() |        +---------------------+      +--------------------+
|  (only SERVED) |                  | uses             |   Staff / Shift    |
| settle()       |                  v                  +--------------------+
+----------------+        <<interface>>                | role: Role (enum,  |
                          PaymentStrategy              |   NOT subclasses)  |
+----------------+        + pay(amount)                | ordersHandled:     |
| ReportService  |            ^      ^      ^          |   LongAdder        |
+----------------+            |      |      |          +--------------------+
| recordSale()   |        Cash   Card   MobilePayment
| salesReport()  |
| inventoryReport|        +-----------------------------------------------+
+----------------+        |              RestaurantFacade                 |
                          |  viewMenu / reserveTable / placeDineInOrder   |
                          |  placeTakeawayOrder / serve / cancel          |
                          |  checkout / pay / dailySales / lowStock       |
                          |  (coordinates services; owns NO rules)        |
                          +-----------------------------------------------+
Legend: ◆ composition   <> aggregation   --> association   ....> dependency
```

### Design Patterns — and exactly why each fits

| Pattern | Where | Why it fits *here* |
|---|---|---|
| **Strategy** | `PaymentStrategy` → Cash/Card/Mobile | The *algorithm* (how to charge) varies per transaction while the *workflow* (validate → charge → mark paid → record sale) is fixed. Requirement #4 explicitly demands new methods over time → each is a new class, settlement code untouched (OCP). This is the third time Strategy has appeared in our series (parking fees, here payments) — it's the default answer to "supports multiple X". |
| **Producer-Consumer** (via `BlockingQueue`) | `KitchenService` | Waiters (producers) and chefs (consumers) run at different speeds and must be decoupled. `LinkedBlockingQueue` provides the buffer, the blocking hand-off, and the thread-safety in one primitive — no `wait/notify`, no explicit lock. First appearance of this idiom in our series; it returns in nearly every Concurrency-tier problem. |
| **Facade** | `RestaurantFacade` | Terminals need one simple API over six subsystems. Crucially the facade holds *zero business rules* — it delegates. That's the anti-God-class discipline this problem actually tests. |
| **Validated enum, deliberately NOT the State pattern** | `OrderStatus` | The GoF State pattern earns its complexity when each state has *different behavior* (vending machine: insertCoin means different things per state). Here states differ only in *which transitions are legal* — a transition table on the enum is simpler, equally safe, and you should say this trade-off out loud; rejecting a pattern with a reason scores higher than applying one reflexively. |
| **Singleton (by scope, not by pattern)** | All services | One instance each, but injected via constructors — not `getInstance()`. Identical to Spring singleton beans. Testable (mock the `InventoryService`), no hidden global state. |
| **Observer (extension)** | "order READY → notify waiter" | Not implemented (the demo prints); the natural extension point and a standard follow-up — see Step 6. |

### SOLID Mapping

- **S** — Each service owns exactly one subsystem. `Bill` computes and guards payment; `ReportService` aggregates; neither knows the other's internals. The facade's single responsibility is *routing*.
- **O** — New payment method, new report, new order status: all additive. No growing conditionals.
- **L** — Any `PaymentStrategy` is substitutable in `settle()`; all honor the same contract (return a `PaymentResult`, throw `PaymentException` on invalid input).
- **I** — `PaymentStrategy` has one method. We did *not* create a fat `RestaurantOperations` interface forcing every service to stub irrelevant methods.
- **D** — `OrderService` depends on `InventoryService` and `KitchenService` through constructor injection; `BillingService` receives the strategy from outside. High-level policy never `new`s its collaborators (the demo's wiring block is hand-rolled DI — Spring would do it with `@Service` + constructor injection).

### The two decisions an interviewer will probe hardest

1. **Why is `InventoryService.deduct` one coarse `synchronized` method instead of per-ingredient locks?** Because the invariant spans *multiple* ingredients: "get ALL of this order's ingredients or NONE." Per-ingredient locks make each ingredient individually safe but allow two orders to each grab half of a shared requirement — partial deduction, both orders unfulfillable. Fine-grained locking *can* be done (lock ingredients in sorted-id order to prevent deadlock — see Step 5), but coarse-first-correct-first is the senior answer; you optimize only with evidence of contention.
2. **Who wins when a customer cancels while a chef picks up the order?** Both sides funnel through `Order.transitionTo` (synchronized + table-validated). `PLACED→CANCELLED` and `PLACED→PREPARING` are both legal, but whichever commits first makes the other throw — exactly one wins, deterministically, with no flag-checking sprinkled around the codebase.

---

## Step 4 — Implementation (Java, compiled & run — demo output at the end)

> Money in paise (`long`), Java 11-compatible (string slot keys instead of records; a record is the Java 16+ upgrade). Boilerplate getters trimmed where uninteresting.

### Enums, transition table, exceptions
```java
import java.util.*;

// ---------- Enums ----------

enum MenuCategory { STARTER, MAIN_COURSE, DESSERT, BEVERAGE }

enum OrderType { DINE_IN, TAKEAWAY }

enum Role { WAITER, CHEF, MANAGER, CASHIER }

enum TimeSlot { LUNCH_12_2, EVENING_5_7, DINNER_7_9, DINNER_9_11 }

/**
 * Order lifecycle. The legal-transition table lives WITH the enum so no caller
 * can invent a transition the domain doesn't allow (encapsulated invariant).
 * We deliberately use a validated enum instead of the full State pattern:
 * states differ only in WHICH transitions are legal, not in behavior.
 */
enum OrderStatus {
    PLACED, PREPARING, READY, SERVED, PAID, CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PLACED,    EnumSet.of(PREPARING, CANCELLED),
            PREPARING, EnumSet.of(READY),
            READY,     EnumSet.of(SERVED),
            SERVED,    EnumSet.of(PAID),
            PAID,      EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    boolean canTransitionTo(OrderStatus next) { return ALLOWED.get(this).contains(next); }
}

// ---------- Exceptions (unchecked: caller bugs / business-rule violations) ----------

class RestaurantException extends RuntimeException {
    RestaurantException(String msg) { super(msg); }
}
class ItemUnavailableException extends RestaurantException {
    ItemUnavailableException(String m) { super(m); }
}
class InsufficientStockException extends RestaurantException {
    InsufficientStockException(String m) { super(m); }
}
class TableUnavailableException extends RestaurantException {
    TableUnavailableException(String m) { super(m); }
}
class InvalidOrderStateException extends RestaurantException {
    InvalidOrderStateException(String m) { super(m); }
}
class PaymentException extends RestaurantException {
    PaymentException(String m) { super(m); }
}
```

### Menu & Inventory — the atomic all-or-nothing deduction
```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Stock quantity is guarded by InventoryService's lock — NOT by this class. */
class Ingredient {
    private final String id;
    private final String name;
    private int stock; // guarded by InventoryService monitor

    Ingredient(String id, String name, int stock) {
        this.id = id; this.name = name; this.stock = stock;
    }
    String getId() { return id; }
    String getName() { return name; }
    int getStock() { return stock; }
    void add(int qty) { stock += qty; }
    void reduce(int qty) { stock -= qty; }
}

/**
 * A menu item knows its RECIPE: ingredientId -> units consumed per serving.
 * Price in paise/cents (long) — never floating point for money.
 */
class MenuItem {
    private final String id;
    private final String name;
    private final long price;
    private final MenuCategory category;
    private final Map<String, Integer> recipe; // immutable

    MenuItem(String id, String name, long price, MenuCategory category, Map<String, Integer> recipe) {
        this.id = id; this.name = name; this.price = price;
        this.category = category;
        this.recipe = Map.copyOf(recipe);
    }
    String getId() { return id; }
    String getName() { return name; }
    long getPrice() { return price; }
    MenuCategory getCategory() { return category; }
    Map<String, Integer> getRecipe() { return recipe; }
}

/** Read-mostly catalog. ConcurrentHashMap gives safe lock-free reads. */
class Menu {
    private final Map<String, MenuItem> items = new ConcurrentHashMap<>();

    void addItem(MenuItem item) { items.put(item.getId(), item); }
    void removeItem(String itemId) { items.remove(itemId); }

    MenuItem getItem(String itemId) {
        MenuItem item = items.get(itemId);
        if (item == null) throw new ItemUnavailableException("No such menu item: " + itemId);
        return item;
    }
    Collection<MenuItem> listAll() { return Collections.unmodifiableCollection(items.values()); }
}

/**
 * Owns all ingredient stock. The interview-critical method is deduct():
 * an order needs MANY ingredients and must get ALL of them or NONE
 * (no partial deduction, no negative stock). One coarse monitor makes
 * check-then-act atomic across the whole ingredient set.
 */
class InventoryService {
    private final Map<String, Ingredient> ingredients = new HashMap<>(); // guarded by 'this'

    synchronized void addIngredient(Ingredient ing) { ingredients.put(ing.getId(), ing); }

    synchronized void restock(String id, int qty) { require(id).add(qty); }

    /** ALL-OR-NOTHING: validate the entire requirement set, then mutate. */
    synchronized void deduct(Map<String, Integer> required) {
        for (Map.Entry<String, Integer> e : required.entrySet()) {          // phase 1: check
            Ingredient ing = require(e.getKey());
            if (ing.getStock() < e.getValue())
                throw new InsufficientStockException(
                        "Out of " + ing.getName() + " (need " + e.getValue() + ", have " + ing.getStock() + ")");
        }
        required.forEach((id, qty) -> ingredients.get(id).reduce(qty));     // phase 2: act
    }

    /** Inverse of deduct, used when an order is cancelled. */
    synchronized void returnStock(Map<String, Integer> qtys) {
        qtys.forEach((id, qty) -> require(id).add(qty));
    }

    synchronized boolean isAvailable(Map<String, Integer> required) {
        return required.entrySet().stream()
                .allMatch(e -> ingredients.containsKey(e.getKey())
                        && ingredients.get(e.getKey()).getStock() >= e.getValue());
    }

    synchronized Map<String, Integer> lowStock(int threshold) {
        return ingredients.values().stream()
                .filter(i -> i.getStock() <= threshold)
                .collect(Collectors.toMap(Ingredient::getName, Ingredient::getStock));
    }

    private Ingredient require(String id) {
        Ingredient ing = ingredients.get(id);
        if (ing == null) throw new RestaurantException("Unknown ingredient: " + id);
        return ing;
    }
}
```

### Customers, Tables, Reservations — lock-free atomic slot claim
```java
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

class Customer {
    private final String id;
    private final String name;
    private final String phone;
    Customer(String id, String name, String phone) { this.id = id; this.name = name; this.phone = phone; }
    String getId() { return id; }
    String getName() { return name; }
    String getPhone() { return phone; }
}

/** A physical table. Occupancy claim must be atomic — two waiters race for it. */
class RestaurantTable {
    private final int number;
    private final int capacity;
    private boolean occupied; // guarded by 'this'

    RestaurantTable(int number, int capacity) { this.number = number; this.capacity = capacity; }
    int getNumber() { return number; }
    int getCapacity() { return capacity; }

    /** Atomic test-and-set: exactly one caller wins. */
    synchronized boolean occupy() {
        if (occupied) return false;
        occupied = true;
        return true;
    }
    synchronized void release() { occupied = false; }
    synchronized boolean isOccupied() { return occupied; }
}

class Reservation {
    private final String id;
    private final Customer customer;
    private final RestaurantTable table;
    private final LocalDate date;
    private final TimeSlot slot;
    private final int partySize;

    Reservation(String id, Customer customer, RestaurantTable table,
                LocalDate date, TimeSlot slot, int partySize) {
        this.id = id; this.customer = customer; this.table = table;
        this.date = date; this.slot = slot; this.partySize = partySize;
    }
    String getId() { return id; }
    Customer getCustomer() { return customer; }
    RestaurantTable getTable() { return table; }
    LocalDate getDate() { return date; }
    TimeSlot getSlot() { return slot; }
    int getPartySize() { return partySize; }
}

/**
 * Double-booking prevention WITHOUT explicit locks:
 * the booking book is keyed by (table, date, slot); ConcurrentHashMap.putIfAbsent
 * is atomic, so of N racing reservations for the same key exactly one wins.
 */
class ReservationService {
    private final Map<String, Reservation> book = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    Reservation reserve(Customer customer, RestaurantTable table,
                        LocalDate date, TimeSlot slot, int partySize) {
        if (partySize <= 0 || partySize > table.getCapacity())
            throw new RestaurantException("Party of " + partySize + " doesn't fit table " + table.getNumber());

        String key = slotKey(table, date, slot);
        Reservation r = new Reservation("R" + seq.incrementAndGet(), customer, table, date, slot, partySize);

        if (book.putIfAbsent(key, r) != null)                       // the atomic claim
            throw new TableUnavailableException(
                    "Table " + table.getNumber() + " already reserved for " + slot + " on " + date);
        return r;
    }

    void cancel(Reservation r) {
        // remove(key, value) is conditional: only removes OUR reservation,
        // never someone else's that replaced it.
        book.remove(slotKey(r.getTable(), r.getDate(), r.getSlot()), r);
    }

    Optional<Reservation> find(RestaurantTable t, LocalDate d, TimeSlot s) {
        return Optional.ofNullable(book.get(slotKey(t, d, s)));
    }

    private String slotKey(RestaurantTable t, LocalDate d, TimeSlot s) {
        return t.getNumber() + "|" + d + "|" + s; // value-object key; a record in Java 16+
    }
}
```

### Orders & Kitchen — price snapshots, validated transitions, producer-consumer
```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable line item. SNAPSHOTS the price at order time — if the menu price
 * changes mid-meal, this customer's bill must not change (temporal consistency).
 */
class OrderItem {
    private final MenuItem menuItem;
    private final int quantity;
    private final long unitPrice; // snapshot, not a live reference to menu price

    OrderItem(MenuItem menuItem, int quantity) {
        if (quantity <= 0) throw new RestaurantException("Quantity must be positive");
        this.menuItem = menuItem;
        this.quantity = quantity;
        this.unitPrice = menuItem.getPrice();
    }
    MenuItem getMenuItem() { return menuItem; }
    int getQuantity() { return quantity; }
    long lineTotal() { return unitPrice * quantity; }

    /** ingredientId -> total units this line consumes. */
    Map<String, Integer> ingredientNeeds() {
        Map<String, Integer> needs = new HashMap<>();
        menuItem.getRecipe().forEach((ing, perServing) -> needs.put(ing, perServing * quantity));
        return needs;
    }
}

/**
 * Aggregate root for an order. Line items are a COMPOSITION (created in the
 * constructor, immutable, die with the order). Status is the only mutable
 * field; every change goes through the synchronized, validated transition.
 */
class Order {
    private final String id;
    private final OrderType type;
    private final RestaurantTable table;     // null for TAKEAWAY
    private final Staff waiter;
    private final List<OrderItem> items;     // immutable after construction
    private OrderStatus status = OrderStatus.PLACED; // guarded by 'this'

    Order(String id, OrderType type, RestaurantTable table, Staff waiter, List<OrderItem> items) {
        if (items.isEmpty()) throw new RestaurantException("Order must contain at least one item");
        if (type == OrderType.DINE_IN && table == null)
            throw new RestaurantException("Dine-in order requires a table");
        this.id = id; this.type = type; this.table = table;
        this.waiter = waiter;
        this.items = List.copyOf(items);
    }

    /** Single choke point for ALL state changes — validation cannot be bypassed. */
    synchronized void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next))
            throw new InvalidOrderStateException(
                    "Order " + id + ": illegal transition " + status + " -> " + next);
        status = next;
    }

    synchronized OrderStatus getStatus() { return status; }

    String getId() { return id; }
    OrderType getType() { return type; }
    RestaurantTable getTable() { return table; }
    Staff getWaiter() { return waiter; }
    List<OrderItem> getItems() { return items; }

    long subtotal() { return items.stream().mapToLong(OrderItem::lineTotal).sum(); }

    /** Total ingredient demand across all lines (merged). */
    Map<String, Integer> totalIngredientNeeds() {
        Map<String, Integer> total = new HashMap<>();
        for (OrderItem li : items)
            li.ingredientNeeds().forEach((ing, qty) -> total.merge(ing, qty, Integer::sum));
        return total;
    }
}

/**
 * PRODUCER-CONSUMER: waiters produce orders, chef threads consume them.
 * BlockingQueue gives us the hand-off, the buffering, and the thread-safety
 * in one primitive — no explicit locks, no wait/notify.
 */
class KitchenService {
    private final BlockingQueue<Order> queue = new LinkedBlockingQueue<>();
    private final ExecutorService chefPool;
    private final StaffService staffService;

    KitchenService(List<Staff> chefs, StaffService staffService) {
        this.staffService = staffService;
        this.chefPool = Executors.newFixedThreadPool(chefs.size());
        chefs.forEach(chef -> chefPool.submit(() -> chefLoop(chef)));
    }

    void submit(Order order) { queue.add(order); }

    private void chefLoop(Staff chef) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Order order = queue.take(); // blocks until work arrives
                try {
                    // RACE with cancellation resolved here: if the customer
                    // cancelled after queuing, this transition throws and
                    // the chef simply skips the order. Exactly one side wins
                    // because transitionTo is synchronized + validated.
                    order.transitionTo(OrderStatus.PREPARING);
                } catch (InvalidOrderStateException cancelled) {
                    System.out.println("  [Kitchen] " + chef.getName() + " skipped cancelled " + order.getId());
                    continue;
                }
                Thread.sleep(50); // simulate cooking
                order.transitionTo(OrderStatus.READY);
                staffService.recordOrderHandled(chef);
                System.out.println("  [Kitchen] " + chef.getName() + " finished " + order.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore flag, exit loop
        }
    }

    void shutdown() { chefPool.shutdownNow(); } // interrupts blocked take()
}

/**
 * Orchestrates order placement. The ORDERING of effects is the design point:
 * inventory is deducted (atomic, all-or-nothing) BEFORE the order exists,
 * so an accepted order is always cookable.
 */
class OrderService {
    private final InventoryService inventory;
    private final KitchenService kitchen;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    OrderService(InventoryService inventory, KitchenService kitchen) {
        this.inventory = inventory;
        this.kitchen = kitchen;
    }

    Order placeOrder(OrderType type, RestaurantTable table, Staff waiter, List<OrderItem> items) {
        Order order = new Order("ORD" + seq.incrementAndGet(), type, table, waiter, items);

        inventory.deduct(order.totalIngredientNeeds()); // may throw -> order never registered
        orders.put(order.getId(), order);
        kitchen.submit(order);
        return order;
    }

    /** Allowed only before the kitchen picks it up; returns ingredients to stock. */
    void cancelOrder(Order order) {
        order.transitionTo(OrderStatus.CANCELLED);      // throws if chef won the race
        inventory.returnStock(order.totalIngredientNeeds());
        if (order.getTable() != null) order.getTable().release();
    }

    void markServed(Order order) {
        order.transitionTo(OrderStatus.SERVED);
        order.getWaiter().recordHandled();
    }

    Optional<Order> find(String id) { return Optional.ofNullable(orders.get(id)); }
}
```

### Staff & Schedules
```java
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

class Staff {
    private final String id;
    private final String name;
    private final Role role;
    // LongAdder over AtomicLong: write-heavy counter bumped by many threads,
    // read rarely (only when a report runs) — exactly LongAdder's sweet spot.
    private final LongAdder ordersHandled = new LongAdder();

    Staff(String id, String name, Role role) { this.id = id; this.name = name; this.role = role; }
    String getId() { return id; }
    String getName() { return name; }
    Role getRole() { return role; }
    void recordHandled() { ordersHandled.increment(); }
    long getOrdersHandled() { return ordersHandled.sum(); }
}

/** A scheduled work window. Pure value object. */
class Shift {
    private final DayOfWeek day;
    private final LocalTime start;
    private final LocalTime end;

    Shift(DayOfWeek day, LocalTime start, LocalTime end) {
        if (!end.isAfter(start)) throw new RestaurantException("Shift must end after it starts");
        this.day = day; this.start = start; this.end = end;
    }
    DayOfWeek getDay() { return day; }
    LocalTime getStart() { return start; }
    LocalTime getEnd() { return end; }
    boolean overlaps(Shift other) {
        return day == other.day && start.isBefore(other.end) && other.start.isBefore(end);
    }
    @Override public String toString() { return day + " " + start + "-" + end; }
}

class StaffService {
    private final Map<String, Staff> staff = new ConcurrentHashMap<>();
    // CopyOnWriteArrayList: schedules are written rarely, read often.
    private final Map<String, List<Shift>> schedule = new ConcurrentHashMap<>();

    void hire(Staff s) {
        staff.put(s.getId(), s);
        schedule.put(s.getId(), new CopyOnWriteArrayList<>());
    }

    void assignShift(Staff s, Shift shift) {
        List<Shift> shifts = schedule.get(s.getId());
        if (shifts == null) throw new RestaurantException("Unknown staff: " + s.getId());
        synchronized (shifts) { // overlap check + add must be one atomic step
            if (shifts.stream().anyMatch(existing -> existing.overlaps(shift)))
                throw new RestaurantException(s.getName() + " already has an overlapping shift");
            shifts.add(shift);
        }
    }

    void recordOrderHandled(Staff s) { s.recordHandled(); }

    List<Shift> shiftsOf(Staff s) {
        return List.copyOf(schedule.getOrDefault(s.getId(), List.of()));
    }

    Collection<Staff> all() { return Collections.unmodifiableCollection(staff.values()); }
}
```

### Billing (Strategy), Reports, and the Facade
```java
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ---------- Billing ----------

class Bill {
    private final String id;
    private final Order order;
    private final long subtotal;
    private final long tax;
    private final long total;
    private boolean paid; // guarded by 'this'

    Bill(String id, Order order, double taxRate) {
        this.id = id; this.order = order;
        this.subtotal = order.subtotal();
        this.tax = Math.round(subtotal * taxRate);
        this.total = subtotal + tax;
    }
    String getId() { return id; }
    Order getOrder() { return order; }
    long getSubtotal() { return subtotal; }
    long getTax() { return tax; }
    long getTotal() { return total; }
    synchronized boolean isPaid() { return paid; }

    /**
     * The whole pay sequence is one critical section on the bill, so the
     * check (already paid?) and the act (charge + mark paid) cannot interleave
     * with another cashier — a double-charge is structurally impossible.
     */
    synchronized PaymentResult pay(PaymentStrategy strategy) {
        if (paid) throw new PaymentException("Bill " + id + " is already paid");
        PaymentResult result = strategy.pay(total);
        if (result.isSuccess()) {
            paid = true;
            order.transitionTo(OrderStatus.PAID);
        }
        return result;
    }
}

class PaymentResult {
    private final boolean success;
    private final String reference;
    PaymentResult(boolean success, String reference) { this.success = success; this.reference = reference; }
    boolean isSuccess() { return success; }
    String getReference() { return reference; }
}

/**
 * STRATEGY: each payment method encapsulates its own charging logic.
 * Adding UPI/gift-card/wallet = new class, zero edits elsewhere (OCP).
 */
interface PaymentStrategy {
    PaymentResult pay(long amount);
    String name();
}

class CashPayment implements PaymentStrategy {
    private final long tendered;
    CashPayment(long tendered) { this.tendered = tendered; }
    @Override public PaymentResult pay(long amount) {
        if (tendered < amount)
            throw new PaymentException("Cash tendered " + tendered + " < bill " + amount);
        return new PaymentResult(true, "CASH change=" + (tendered - amount));
    }
    @Override public String name() { return "CASH"; }
}

class CardPayment implements PaymentStrategy {
    private final String cardLast4;
    CardPayment(String cardLast4) { this.cardLast4 = cardLast4; }
    @Override public PaymentResult pay(long amount) {
        // real impl: gateway call, may fail/timeout — hence PaymentResult
        return new PaymentResult(true, "CARD-" + cardLast4 + "-AUTH" + System.nanoTime() % 10000);
    }
    @Override public String name() { return "CARD"; }
}

class MobilePayment implements PaymentStrategy {
    private final String vpa;
    MobilePayment(String vpa) { this.vpa = vpa; }
    @Override public PaymentResult pay(long amount) {
        return new PaymentResult(true, "UPI-" + vpa);
    }
    @Override public String name() { return "UPI"; }
}

class BillingService {
    private static final double TAX_RATE = 0.05;
    private final AtomicLong seq = new AtomicLong();
    private final ReportService reports;

    BillingService(ReportService reports) { this.reports = reports; }

    Bill generateBill(Order order) {
        if (order.getStatus() != OrderStatus.SERVED)
            throw new InvalidOrderStateException("Bill only after serving; order is " + order.getStatus());
        return new Bill("BILL" + seq.incrementAndGet(), order, TAX_RATE);
    }

    PaymentResult settle(Bill bill, PaymentStrategy strategy) {
        PaymentResult result = bill.pay(strategy);
        if (result.isSuccess()) {
            reports.recordSale(bill, strategy.name());
            if (bill.getOrder().getTable() != null) bill.getOrder().getTable().release();
        }
        return result;
    }
}

// ---------- Reporting ----------

/** Immutable fact, appended once, never mutated — safe to aggregate lock-free. */
class SaleRecord {
    final String billId;
    final long amount;
    final String method;
    final LocalDateTime at;
    SaleRecord(String billId, long amount, String method) {
        this.billId = billId; this.amount = amount; this.method = method;
        this.at = LocalDateTime.now();
    }
}

class ReportService {
    private final ConcurrentLinkedQueue<SaleRecord> sales = new ConcurrentLinkedQueue<>();
    private final InventoryService inventory;

    ReportService(InventoryService inventory) { this.inventory = inventory; }

    void recordSale(Bill bill, String method) {
        sales.add(new SaleRecord(bill.getId(), bill.getTotal(), method));
    }

    String salesReport(LocalDate day) {
        List<SaleRecord> todays = sales.stream()
                .filter(s -> s.at.toLocalDate().equals(day)).collect(Collectors.toList());
        long revenue = todays.stream().mapToLong(s -> s.amount).sum();
        Map<String, Long> byMethod = todays.stream()
                .collect(Collectors.groupingBy(s -> s.method, Collectors.summingLong(s -> s.amount)));
        return "Sales " + day + ": " + todays.size() + " bills, revenue=" + revenue + ", byMethod=" + byMethod;
    }

    String inventoryReport(int lowStockThreshold) {
        return "Low stock (<=" + lowStockThreshold + "): " + inventory.lowStock(lowStockThreshold);
    }
}

// ---------- Facade ----------

/**
 * FACADE: one simple API for the terminals/UI. It owns NO business rules —
 * it only wires calls to the right service. All real logic lives in the
 * services, which keeps this from becoming a God class.
 */
class RestaurantFacade {
    private final Menu menu;
    private final InventoryService inventory;
    private final ReservationService reservations;
    private final OrderService orders;
    private final BillingService billing;
    private final StaffService staffService;
    private final ReportService reports;

    RestaurantFacade(Menu menu, InventoryService inventory, ReservationService reservations,
                     OrderService orders, BillingService billing,
                     StaffService staffService, ReportService reports) {
        this.menu = menu; this.inventory = inventory; this.reservations = reservations;
        this.orders = orders; this.billing = billing;
        this.staffService = staffService; this.reports = reports;
    }

    Collection<MenuItem> viewMenu() { return menu.listAll(); }

    Reservation reserveTable(Customer c, RestaurantTable t, LocalDate d, TimeSlot s, int party) {
        return reservations.reserve(c, t, d, s, party);
    }

    Order placeDineInOrder(RestaurantTable table, Staff waiter, Map<String, Integer> itemQuantities) {
        if (!table.occupy())                                  // atomic claim; one waiter wins
            throw new TableUnavailableException("Table " + table.getNumber() + " is occupied");
        try {
            return orders.placeOrder(OrderType.DINE_IN, table, waiter, toLineItems(itemQuantities));
        } catch (RuntimeException e) {
            table.release();                                  // COMPENSATE: undo the seat claim
            throw e;
        }
    }

    Order placeTakeawayOrder(Staff waiter, Map<String, Integer> itemQuantities) {
        return orders.placeOrder(OrderType.TAKEAWAY, null, waiter, toLineItems(itemQuantities));
    }

    void serve(Order order) { orders.markServed(order); }
    void cancel(Order order) { orders.cancelOrder(order); }
    Bill checkout(Order order) { return billing.generateBill(order); }
    PaymentResult pay(Bill bill, PaymentStrategy how) { return billing.settle(bill, how); }
    String dailySales(LocalDate day) { return reports.salesReport(day); }
    String lowStock(int threshold) { return reports.inventoryReport(threshold); }

    private List<OrderItem> toLineItems(Map<String, Integer> itemQuantities) {
        List<OrderItem> items = new ArrayList<>();
        itemQuantities.forEach((itemId, qty) -> items.add(new OrderItem(menu.getItem(itemId), qty)));
        return items;
    }
}
```

### Demo — deliberately racing every hazard
```java
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

public class RestaurantDemo {

    public static void main(String[] args) throws Exception {
        // ---------- Wiring (in Spring these are singleton @Service beans) ----------
        InventoryService inventory = new InventoryService();
        Menu menu = new Menu();
        ReservationService reservations = new ReservationService();
        StaffService staffService = new StaffService();
        ReportService reports = new ReportService(inventory);
        BillingService billing = new BillingService(reports);

        // Staff
        Staff alice = new Staff("S1", "Alice", Role.WAITER);
        Staff bob   = new Staff("S2", "Bob",   Role.WAITER);
        Staff chef1 = new Staff("S3", "ChefRamu", Role.CHEF);
        Staff chef2 = new Staff("S4", "ChefMeena", Role.CHEF);
        for (Staff s : List.of(alice, bob, chef1, chef2)) staffService.hire(s);
        staffService.assignShift(alice, new Shift(DayOfWeek.WEDNESDAY, LocalTime.of(11, 0), LocalTime.of(19, 0)));
        try {
            staffService.assignShift(alice, new Shift(DayOfWeek.WEDNESDAY, LocalTime.of(18, 0), LocalTime.of(23, 0)));
        } catch (RestaurantException e) {
            System.out.println("[Schedule] rejected: " + e.getMessage());
        }

        KitchenService kitchen = new KitchenService(List.of(chef1, chef2), staffService);
        OrderService orderService = new OrderService(inventory, kitchen);
        RestaurantFacade restaurant = new RestaurantFacade(
                menu, inventory, reservations, orderService, billing, staffService, reports);

        // Inventory + menu. Dosa needs 2 batter; only 5 batter in stock => at most 2 dosa orders of qty 1... (2*2=4, third fails)
        inventory.addIngredient(new Ingredient("ING1", "DosaBatter", 5));
        inventory.addIngredient(new Ingredient("ING2", "Paneer", 10));
        inventory.addIngredient(new Ingredient("ING3", "CoffeeBeans", 100));
        menu.addItem(new MenuItem("M1", "Masala Dosa", 12000, MenuCategory.MAIN_COURSE, Map.of("ING1", 2)));
        menu.addItem(new MenuItem("M2", "Paneer Tikka", 22000, MenuCategory.STARTER, Map.of("ING2", 3)));
        menu.addItem(new MenuItem("M3", "Filter Coffee", 4000, MenuCategory.BEVERAGE, Map.of("ING3", 1)));

        RestaurantTable t1 = new RestaurantTable(1, 4);
        RestaurantTable t2 = new RestaurantTable(2, 2);
        Customer ravi = new Customer("C1", "Ravi", "98400xxxxx");
        Customer priya = new Customer("C2", "Priya", "98841xxxxx");

        // ---------- 1) Reservation race: two customers, same table+slot ----------
        System.out.println("== Reservation race ==");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        Runnable r1 = () -> tryReserve(restaurant, ravi, t1, start);
        Runnable r2 = () -> tryReserve(restaurant, priya, t1, start);
        pool.submit(r1); pool.submit(r2);
        start.countDown();
        Thread.sleep(200);

        // ---------- 2) Inventory contention: 3 racing dosa orders, stock for 2 ----------
        System.out.println("\n== Inventory race (batter=5, each order needs 2) ==");
        CountDownLatch start2 = new CountDownLatch(1);
        List<Future<Order>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(pool.submit(() -> {
                start2.await();
                return restaurant.placeTakeawayOrder(alice, Map.of("M1", 1));
            }));
        }
        start2.countDown();
        List<Order> placed = new ArrayList<>();
        for (Future<Order> f : futures) {
            try { placed.add(f.get()); System.out.println("[Order] accepted " + placed.get(placed.size()-1).getId()); }
            catch (ExecutionException e) { System.out.println("[Order] rejected: " + e.getCause().getMessage()); }
        }

        // ---------- 3) Dine-in happy path + table race ----------
        System.out.println("\n== Table occupancy race ==");
        Order dineIn = restaurant.placeDineInOrder(t2, bob, Map.of("M2", 1, "M3", 2));
        System.out.println("[Order] " + dineIn.getId() + " seated at table " + t2.getNumber());
        try {
            restaurant.placeDineInOrder(t2, alice, Map.of("M3", 1));
        } catch (TableUnavailableException e) {
            System.out.println("[Order] rejected: " + e.getMessage());
        }

        // ---------- 4) Cancel-vs-kitchen race demonstration ----------
        System.out.println("\n== Cancellation ==");
        Order toCancel = restaurant.placeTakeawayOrder(alice, Map.of("M3", 1));
        try {
            restaurant.cancel(toCancel);
            System.out.println("[Cancel] " + toCancel.getId() + " cancelled, coffee beans returned");
        } catch (InvalidOrderStateException e) {
            System.out.println("[Cancel] too late, chef already cooking: " + e.getMessage());
        }

        Thread.sleep(300); // let kitchen finish

        // ---------- 5) Serve, bill, pay (and a double-payment attempt) ----------
        System.out.println("\n== Billing ==");
        restaurant.serve(dineIn);
        Bill bill = restaurant.checkout(dineIn);
        System.out.printf("[Bill] %s subtotal=%d tax=%d total=%d%n",
                bill.getId(), bill.getSubtotal(), bill.getTax(), bill.getTotal());
        PaymentResult pr = restaurant.pay(bill, new MobilePayment("ravi@upi"));
        System.out.println("[Pay] success ref=" + pr.getReference());
        try {
            restaurant.pay(bill, new CashPayment(100000));
        } catch (PaymentException e) {
            System.out.println("[Pay] rejected: " + e.getMessage());
        }

        // settle the accepted takeaway orders too
        for (Order o : placed) {
            while (o.getStatus() != OrderStatus.READY) Thread.sleep(20);
            restaurant.serve(o);
            restaurant.pay(restaurant.checkout(o), new CardPayment("4242"));
        }

        // ---------- 6) Reports ----------
        System.out.println("\n== Reports ==");
        System.out.println(restaurant.dailySales(LocalDate.now()));
        System.out.println(restaurant.lowStock(3));
        staffService.all().forEach(s ->
                System.out.println("  " + s.getName() + " (" + s.getRole() + ") handled " + s.getOrdersHandled()));

        kitchen.shutdown();
        pool.shutdownNow();
    }

    private static void tryReserve(RestaurantFacade r, Customer c, RestaurantTable t, CountDownLatch start) {
        try {
            start.await();
            Reservation res = r.reserveTable(c, t, LocalDate.now().plusDays(1), TimeSlot.DINNER_7_9, 3);
            System.out.println("[Reserve] " + c.getName() + " got " + res.getId());
        } catch (TableUnavailableException e) {
            System.out.println("[Reserve] " + c.getName() + " lost the race: " + e.getMessage());
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
```

### Verified output (`javac *.java && java RestaurantDemo`)
```text
[Schedule] rejected: Alice already has an overlapping shift
== Reservation race ==
[Reserve] Priya lost the race: Table 1 already reserved for DINNER_7_9 on 2026-06-11
[Reserve] Ravi got R2

== Inventory race (batter=5, each order needs 2) ==
[Order] accepted ORD2
[Order] rejected: Out of DosaBatter (need 2, have 1)
[Order] accepted ORD3

== Table occupancy race ==
[Order] ORD4 seated at table 2
[Order] rejected: Table 2 is occupied

== Cancellation ==
[Cancel] ORD5 cancelled, coffee beans returned
  [Kitchen] ChefRamu finished ORD2
  [Kitchen] ChefMeena finished ORD3
  [Kitchen] ChefRamu skipped cancelled ORD5
  [Kitchen] ChefMeena finished ORD4

== Billing ==
[Bill] BILL1 subtotal=30000 tax=1500 total=31500
[Pay] success ref=UPI-ravi@upi
[Pay] rejected: Bill BILL1 is already paid

== Reports ==
Sales 2026-06-10: 3 bills, revenue=56700, byMethod={CARD=25200, UPI=31500}
Low stock (<=3): {DosaBatter=1}
  ChefRamu (CHEF) handled 1
  ChefMeena (CHEF) handled 2
  Alice (WAITER) handled 2
  Bob (WAITER) handled 1
```

Read the output as a checklist: schedule-overlap rejected ✓ · exactly one reservation winner ✓ · 2 of 3 racing orders accepted on stock for 2 ✓ · second waiter rejected at the occupied table ✓ · cancelled order skipped by the chef ✓ · double payment rejected ✓ · reports consistent with the above ✓.

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Exception strategy

All domain exceptions are **unchecked**, extending one root `RestaurantException`. They represent business-rule violations (out of stock, double booking, illegal transition, double payment) — the caller's recovery is "tell the user and move on," and forcing `throws` clauses through six service layers buys nothing. Each subtype is specific enough for the terminal to render a meaningful message. Note the **fail-before-mutate** discipline everywhere: validation happens before any state changes (`deduct` phase 1 vs phase 2, `Bill.pay` check before charge, constructor validation in `Order`/`OrderItem`/`Shift`), so a thrown exception never leaves partial state behind. The one place a mutation must be undone is the facade's dine-in flow — seat claimed, then order fails — handled with an explicit **compensating action** (`table.release()` in the catch), the in-process miniature of a saga.

### Edge cases handled

| Edge case | Behavior |
|---|---|
| Empty order / non-positive quantity / party exceeds capacity / shift ends before it starts | Rejected at construction — invalid objects can never exist |
| Order references unknown menu item | `ItemUnavailableException` from `Menu.getItem` before anything mutates |
| Stock sufficient for some lines but not all | All-or-nothing: nothing deducted, order rejected with the precise shortfall |
| Cancel after chef started | `InvalidOrderStateException` — too late; the transition table enforces it |
| Cancel before chef started | Stock returned, table released, chef finds the order CANCELLED and skips it |
| Bill requested before serving | Rejected — billing rule lives in `BillingService`, not the facade |
| Cash tendered < total | `PaymentException`; bill remains unpaid and payable |
| Second payment of a paid bill | Rejected inside the same critical section that marked it paid |
| Overlapping shift assignment | Rejected atomically (check+add under one lock) |
| Menu price change mid-meal | Customer's bill unaffected — `OrderItem` snapshotted the price |

### Concurrency analysis (the core of this problem)

**Shared mutable state inventory** — first step of any concurrency answer is naming it:

| Shared state | Hazard | Primitive chosen | Why this one |
|---|---|---|---|
| Ingredient stock (a *set* of counters) | Two orders check-then-deduct overlapping ingredients → partial deduction / negative stock | One `synchronized` monitor on `InventoryService` | The invariant spans multiple ingredients, so the critical section must too. Coarse, simple, provably correct; contention is bounded by order-placement rate, not customer count. |
| Reservation book | Two customers reserve same (table, date, slot) | `ConcurrentHashMap.putIfAbsent` | The claim is naturally a single-key insert → the map's atomic insert IS the lock. Lock-free, and `remove(key, value)` makes cancellation conditional so you can only cancel your own reservation. Same atomic-claim idiom as the parking lot's `poll()`. |
| Table occupancy | Two waiters seat parties at one table | `synchronized` test-and-set (`occupy()`) | Single boolean per table → smallest possible critical section; an `AtomicBoolean.compareAndSet` would be equally right. |
| Order status | Cancel vs chef pickup; duplicate serve | `synchronized transitionTo` + transition table | One choke point makes every race a *deterministic* winner-takes-all; the loser gets an exception, not corruption. |
| Bill paid flag + charging | Two cashiers settle the same bill | `synchronized pay()` spanning check + charge + mark | The check and the act must be one unit; marking paid *before* charging (CAS-style) would need rollback on gateway failure — synchronizing the whole sequence is simpler and the bill is the natural lock granule. |
| Kitchen hand-off | Lost wakeups, busy-waiting chefs | `LinkedBlockingQueue` + `ExecutorService` | `take()` blocks correctly, `add()` publishes the order safely (happens-before via the queue). Shutdown = `shutdownNow()` → interrupt → `take()` throws → loop exits, flag restored. |
| Performance counters | Lost increments | `LongAdder` | Write-heavy, read-rare counter — `LongAdder` shards contention; `AtomicLong` would also be correct, just slower under load. |
| Sales log | Concurrent appends during reads | `ConcurrentLinkedQueue` of *immutable* `SaleRecord`s | Append-only facts never mutate, so readers need no locking — immutability as a concurrency strategy. |

**Deadlock argument.** A deadlock needs a cycle of lock acquisition. Audit the lock graph: `InventoryService`'s monitor is acquired alone (its methods call out to nothing that locks). `Order.transitionTo` acquires only the order. `Bill.pay` holds the bill and calls `order.transitionTo` — bill → order is the *only* two-lock path, and no code path ever acquires order → bill (nothing inside `transitionTo` calls back out). One-directional edges, no cycle, no deadlock. Being able to *walk this argument* matters more than the design itself.

**Livelock/starvation.** No retry loops exist (losers throw, they don't spin), `LinkedBlockingQueue` is FIFO so orders can't starve, and `synchronized` blocks here are short (no I/O under any lock — the simulated cooking happens *outside* `transitionTo`).

**The fine-grained alternative (say it before they ask).** If the single inventory monitor ever became a bottleneck: give each `Ingredient` a `ReentrantLock`, and have `deduct` lock the order's ingredients **in sorted ingredient-id order**, check all, mutate all, unlock in reverse. Global lock ordering removes the deadlock cycle by construction. Costs: more code, harder proof, lock/unlock bookkeeping in `finally`. The senior move is naming this option *and* declining it without measured contention.

**Why this is thread-safe under composition.** Each invariant is guarded by exactly one mechanism, and cross-service flows are sequenced so each step is independently atomic with a compensating action if a later step fails (deduct → register → enqueue; occupy → place → release-on-failure). There is no moment where an invariant depends on two locks being held together — that's the property that keeps a multi-subsystem design provable.

---

## Likely Interviewer Follow-ups (with model answers)

**1. "Notify the waiter when an order is READY — don't make them poll."**
Observer. Add `OrderReadyListener { void onReady(Order o); }`; `KitchenService` holds a `CopyOnWriteArrayList<OrderReadyListener>` and fires after the READY transition. Waiter displays, KDS screens, SMS senders all subscribe without the kitchen knowing any of them. Keep the callback fast or hand it to an executor — never run slow listener code on a chef thread.

**2. "Two restaurants? Ten? A chain?"**
Today's services assume one restaurant. Introduce a `Restaurant` aggregate owning its own Menu/Inventory/Tables/Kitchen instances — the services are already instance-scoped (no statics, no singletons-by-pattern), so this is composition, not rewrite. Cross-restaurant reporting becomes a layer that aggregates per-restaurant `ReportService`s. The single-JVM assumption breaks at a chain: inventory and reservations move behind a database with transactions, and our in-memory atomicity arguments translate to `SELECT … FOR UPDATE` / unique constraints (the `putIfAbsent` reservation becomes a DB unique index on (table, date, slot) — same idea, different enforcement layer).

**3. "Split the bill across 3 friends."**
`Bill` gains a list of `Payment` records and a remaining-balance; `pay(strategy, amount)` appends under the bill's lock until balance hits zero, then transitions the order. The exactly-once flag generalizes to exactly-`total`. Watch rounding: assign the remainder paise to the last payer.

**4. "The payment gateway hangs for 30 seconds. What breaks?"**
`Bill.pay` holds the bill's lock through the charge — for *that bill* a hung gateway blocks a concurrent double-pay attempt (arguably correct!) but ties up a thread. Fix: state machine the bill (`UNPAID → PAYING → PAID`), claim `PAYING` with a quick CAS, do the gateway call **outside** any lock, then commit/rollback the state. That's the general rule it illustrates: never do I/O under a lock.

**5. "Reservations should auto-release if the party doesn't show in 15 minutes."**
Add `ScheduledExecutorService`: on seating-time, schedule a task that atomically flips the reservation to `NO_SHOW` and frees the slot *iff* still unclaimed (`remove(key, value)` again — conditional removal prevents killing a claimed reservation). The same delayed-expiry idiom shows up in seat-hold for BookMyShow and lock leases.

---

## Transferable Lessons

1. **Multi-resource atomicity → one lock spanning the invariant.** "All ingredients or none" is the same shape as transferring money between accounts or booking a multi-leg flight. The lock must cover the *invariant*, not the *objects*. Fine-grained + ordered locking is the scalable variant; mention it, don't start with it.
2. **`putIfAbsent` as a lock-free atomic claim** — third appearance of the claim idiom (parking `poll()`, task-id insert, now reservations). Any "exactly one winner for a keyed resource" problem reduces to it.
3. **Validated transition table beats the State pattern when states vary only in legality, not behavior.** Knowing when *not* to use a pattern is the senior signal.
4. **BlockingQueue producer-consumer** — the workhorse for "X submits work, Y processes it." You'll reuse it verbatim in the Concurrency tier (thread pool, rate limiter, logger).
5. **Compensating actions** (release table on failed order, return stock on cancel) — the in-process seed of the saga pattern you already know from microservices.

---

## Progress & Next Problem

**Completed:** Logging Framework → Vending Machine → Parking Lot → Task Management → **Restaurant Management ✓**

**Next:** Two good options, pick by goal —
- **Hotel Management / BookMyShow (movie ticket booking)** — stays in Hard tier; BookMyShow especially sharpens the *seat-hold-with-timeout* concurrency (follow-up #5 above becomes the main course).
- Or jump to the **Concurrency tier** (start: thread-safe LRU Cache or a custom Thread Pool) — the BlockingQueue intuition from this problem is fresh, which makes Thread Pool the natural continuation.
