# Online Shopping System (Amazon-style) — Low-Level Design

**Tier:** Hard | **Language:** Java 11+ | **Scope:** In-memory, single-seller retail model

**Builds on:** Concert Ticket Booking (multi-resource atomicity), Airline Management (two-phase booking, no locks across I/O), Digital Wallet (per-resource locking, money as `BigDecimal`).

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement | Notes |
|---|------------|-------|
| FR1 | Browse products organized into categories | A product may belong to multiple categories |
| FR2 | Keyword search with filters | Name/description match, category filter, price range filter; filters composable |
| FR3 | Shopping cart per user | Add / remove / update quantity; cart reflects **live** price |
| FR4 | Place order (checkout) | Cart → Order; multi-item orders are **all-or-nothing**; price **snapshotted** into order at placement |
| FR5 | Order lifecycle | `PENDING_PAYMENT → PLACED → SHIPPED → DELIVERED`; `CANCELLED` reachable from PENDING_PAYMENT and PLACED only |
| FR6 | Cancellation | Restocks inventory and refunds payment |
| FR7 | Order history & tracking | List a user's orders; query status of any order |
| FR8 | User profile | Registration, profile details, one or more shipping addresses |
| FR9 | Inventory management | Per-product stock; decrement on order, restore on cancel; out-of-stock products are browsable but not orderable |
| FR10 | Multiple payment methods | Card / UPI / Wallet behind one abstraction; payment failure releases reserved stock |

### Non-Functional Requirements

| # | Requirement | Design consequence |
|---|------------|-------------------|
| NFR1 | **No overselling** under concurrent checkout | Per-product locking + atomic check-and-set across all items in an order |
| NFR2 | **Atomic checkout** | Reserve-all-or-nothing; compensation (restock) if payment fails |
| NFR3 | **No locks across external I/O** | Two-phase checkout: reserve → (locks released) → pay → confirm/compensate |
| NFR4 | **Extensibility** (OCP) | New payment method = new Strategy class; new search filter = new Predicate; no service edits |
| NFR5 | **Parallelism** | No global checkout lock — lock granularity is per product, so disjoint orders never contend |
| NFR6 | Scalability path | In-memory `ConcurrentHashMap` registries stand in for DB tables; lock-per-product maps to row-level locking / optimistic versioning in a real DB |

### Assumptions (stated to the interviewer)

1. **Single seller** (Amazon-retail), not a marketplace — no `Seller` entity, one inventory pool per product.
2. **Inventory is reserved at checkout, not at add-to-cart.** Carts are intent, not reservation; otherwise abandoned carts hold stock hostage.
3. **Payment is a synchronous external call that can fail or be slow** — hence NFR3.
4. **Price is frozen at order placement** (`OrderItem` stores `priceAtPurchase`); the cart always shows live price.
5. Reviews, wishlists, coupons, returns are out of scope (entity additions, not pattern drivers).
6. Search is in-memory predicate filtering; a real system swaps Elasticsearch behind the same `ProductCatalog.search(...)` interface.

---

## Step 2 — Entities & Relationships

### Core Entities

| Entity | Responsibility | Key fields |
|--------|---------------|-----------|
| `User` | Identity + profile | id, name, email, `List<Address>` |
| `Address` | **Value object** — immutable shipping address | street, city, state, zip |
| `Category` | Catalog grouping | id, name |
| `Product` | Catalog metadata + live price | id, name, description, `BigDecimal price`, `Set<Category>` |
| `Cart` | Mutable per-user intent | userId, `Map<productId, quantity>` |
| `Order` | Immutable line items + mutable status | id, userId, `List<OrderItem>`, status, total, shippingAddress, paymentId |
| `OrderItem` | **Price snapshot** of one line | productId, productName, quantity, `priceAtPurchase` |
| `Payment` | Record of one payment attempt | id, orderId, amount, method, status |
| `InventoryService` | Owns stock counts + per-product locks | `Map<productId, int>` |

### Enums

- `OrderStatus` — `PENDING_PAYMENT, PLACED, SHIPPED, DELIVERED, CANCELLED` (with an embedded legal-transition table)
- `PaymentStatus` — `PENDING, SUCCESS, FAILED, REFUNDED`

### Relationships

| Relationship | Type | Why |
|---|---|---|
| `User` → `Address` | **Composition** | Addresses have no identity or lifecycle outside their user; modeled as immutable value objects owned by the user |
| `User` → `Cart` | **Composition** (1:1) | A cart cannot exist without its user; destroyed with the user |
| `User` → `Order` | **Association** (1:N) | Orders reference `userId` but outlive cart state and are queried independently (order history) |
| `Order` → `OrderItem` | **Composition** | Line items are meaningless outside their order; created and frozen at checkout |
| `OrderItem` → `Product` | **Dependency** (by id + snapshot) | Deliberately *not* an object reference: the order must keep showing the price/name **as purchased**, even if the catalog later changes. Storing a `Product` reference would leak live mutations into historical orders |
| `Product` ↔ `Category` | **Association** (M:N) | Both have independent lifecycles |
| `Order` → `Payment` | **Association** (1:1) | Payment record is auditable independently |
| Stock | Held **in `InventoryService`**, not on `Product` | Item-type/instance split from Library & Car Rental, generalized: `Product` is catalog metadata (read-mostly, shared freely), stock is hot mutable state with its own locking discipline. Mixing them forces every catalog read to care about inventory locks |

### Deliberate under-modeling

- **No `CartItem` class** — a cart line is just `(productId → quantity)`; a `Map` is the honest representation. `OrderItem` *does* earn a class because it carries the price snapshot.
- **No `Inventory` entity per product** — a count plus a lock; an entity wrapper adds nothing.
- **No `SearchService` class** — search is one method on the catalog taking composable predicates.

---

## Step 3 — UML Class Design

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                    OnlineShoppingService  «Singleton, Facade»           │
│─────────────────────────────────────────────────────────────────────────│
│ - users: ConcurrentHashMap<String, User>                                │
│ - orders: ConcurrentHashMap<String, Order>                              │
│ - catalog: ProductCatalog                                               │
│ - inventory: InventoryService                                           │
│─────────────────────────────────────────────────────────────────────────│
│ + registerUser(name, email): User                                       │
│ + addToCart(userId, productId, qty)                                     │
│ + checkout(userId, address, PaymentStrategy): Order                     │
│ + cancelOrder(orderId): void                                            │
│ + getOrderHistory(userId): List<Order>                                  │
│ + search(SearchCriteria): List<Product>                                 │
└───────────────┬─────────────────────────┬───────────────────────────────┘
                │ uses                    │ uses
                ▼                         ▼
┌───────────────────────────┐   ┌──────────────────────────────────────┐
│       ProductCatalog      │   │          InventoryService            │
│───────────────────────────│   │──────────────────────────────────────│
│ - products: CHM<id,Product>│  │ - stock: Map<String,Integer>         │
│───────────────────────────│   │ - locks: CHM<String,ReentrantLock>   │
│ + addProduct(Product)     │   │──────────────────────────────────────│
│ + search(Predicate<Product>)│ │ + addStock(productId, qty)           │
│ + getProduct(id): Product │   │ + reserve(Map<id,qty>): boolean      │
└───────────────────────────┘   │ + release(Map<id,qty>): void         │
                                └──────────────────────────────────────┘

┌──────────────────┐  1   *  ┌──────────────────┐
│      User        │────────▶│      Order        │
│──────────────────│         │──────────────────│
│ - id, name, email│         │ - id, userId      │
│ - addresses ◆────│         │ - items ◆ List<OrderItem>  (composition)
│ - cart ◆ Cart    │         │ - status: OrderStatus (synchronized transitions)
└──────────────────┘         │ - total: BigDecimal│
                             │ - paymentId        │
   ◆ = composition           └──────────────────┘

┌────────────────────────┐        ┌─────────────────────────────────┐
│  «interface»           │        │ OrderStatus «enum + transitions»│
│  PaymentStrategy       │        │ PENDING_PAYMENT → PLACED|CANCELLED
│────────────────────────│        │ PLACED → SHIPPED|CANCELLED      │
│ + pay(orderId, amount) │        │ SHIPPED → DELIVERED             │
│ + refund(paymentId)    │        │ + canTransitionTo(next): boolean│
└───────┬────────────────┘        └─────────────────────────────────┘
        │ implements
   ┌────┴─────────┬───────────────┐
   ▼              ▼               ▼
CardPayment   UpiPayment    WalletPayment
```

### Design Patterns — and exactly why each fits

| Pattern | Where | Why it fits here (not just the name) |
|---|---|---|
| **Strategy** | `PaymentStrategy` | The checkout *algorithm* is fixed (reserve → pay → confirm/compensate) but one step — "collect money" — varies by method. Strategy isolates exactly that varying step behind an interface, so `CryptoPayment` is a new class, zero edits to `checkout()`. Pure OCP. |
| **Strategy via `Predicate<Product>` composition** | Search | Filters are independent, optional, and combinable (`nameMatches.and(inCategory).and(inPriceRange)`). Composing predicates beats a `search(name, category, minPrice, maxPrice, ...)` god-method that grows a parameter per feature. |
| **State (enum-guarded)** | `OrderStatus` | The lifecycle is a simple linear-with-branch DAG and transitions carry no per-state *behavior* — only legality. Full GoF State (one class per status) would be ceremony; an enum transition table gives the same illegal-transition protection in 15 lines. Same call you made for `BookingStatus` and `AuctionStatus`. |
| **Singleton (holder idiom)** | `OnlineShoppingService` | One system instance owning the registries. Holder idiom: lazy, thread-safe via class-loading guarantees, no `synchronized` on every `getInstance()` call. (Spring note: in a Boot app this is just a `@Service` singleton bean — DI replaces hand-rolled Singletons.) |
| **Facade** | `OnlineShoppingService` | Checkout coordinates cart, catalog, inventory, payment, and order registry. The client sees one method; the orchestration complexity is hidden behind it. |
| **Compensation / Saga-in-miniature** | `checkout()` | Reserve stock → pay → on payment failure, `release()` restocks. The in-memory version of a distributed saga's compensating transaction. |
| **Repository (implicit)** | `ConcurrentHashMap` registries | Standard LLD stand-in for persistence; swapping in a real repository changes no domain logic. |

### The two decisions an interviewer will probe

1. **Why does `OrderItem` copy `priceAtPurchase` instead of referencing `Product`?**
   Temporal correctness. The catalog price is *live* mutable state; an order is a *historical fact*. Referencing the live object means yesterday's order silently shows today's price. Snapshotting at the composition boundary is the fix — and it's why `OrderItem` exists as a class while a cart line doesn't.

2. **Why is stock reserved *before* payment, and how do you avoid holding locks during the gateway call?**
   Reserve-then-pay guarantees the money you take corresponds to stock you actually hold. The reservation itself (decrement under per-product locks) takes microseconds; the locks are **released before** the payment call. If payment fails, compensation restocks. The alternative — pay first, then try to reserve — risks taking money for stock that vanished, forcing a refund path on the *happy-path-adjacent* flow.

---

## Step 4 — Implementation

### 4.1 Enums with guarded transitions

```java
import java.util.*;

public enum OrderStatus {
    PENDING_PAYMENT, PLACED, SHIPPED, DELIVERED, CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING_PAYMENT, EnumSet.of(PLACED, CANCELLED),
            PLACED,          EnumSet.of(SHIPPED, CANCELLED),
            SHIPPED,         EnumSet.of(DELIVERED),
            DELIVERED,       EnumSet.noneOf(OrderStatus.class),
            CANCELLED,       EnumSet.noneOf(OrderStatus.class)
    );

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}

public enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }
```

### 4.2 Value objects and catalog entities

```java
import java.math.BigDecimal;
import java.util.*;

/** Immutable value object — equality by value, no identity. */
public final class Address {
    private final String street, city, state, zip;
    public Address(String street, String city, String state, String zip) {
        this.street = street; this.city = city; this.state = state; this.zip = zip;
    }
    @Override public String toString() { return street + ", " + city + ", " + state + " " + zip; }
}

public class Category {
    private final String id, name;
    public Category(String id, String name) { this.id = id; this.name = name; }
    public String getId() { return id; }
    public String getName() { return name; }
}

public class Product {
    private final String id, name, description;
    // volatile: price is the one mutable field, written rarely (admin),
    // read constantly (browsing). volatile guarantees visibility without locking reads.
    private volatile BigDecimal price;
    private final Set<String> categoryIds;

    public Product(String id, String name, String description,
                   BigDecimal price, Set<String> categoryIds) {
        this.id = id; this.name = name; this.description = description;
        this.price = price; this.categoryIds = Set.copyOf(categoryIds);
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Set<String> getCategoryIds() { return categoryIds; }
}
```

### 4.3 User and Cart

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Cart {
    // ConcurrentHashMap because a user's own devices could race (two tabs),
    // and merge() gives us atomic read-modify-write per key.
    private final Map<String, Integer> items = new ConcurrentHashMap<>();

    public void addItem(String productId, int qty) {
        if (qty <= 0) throw new IllegalArgumentException("Quantity must be positive");
        items.merge(productId, qty, Integer::sum);
    }

    public void updateQuantity(String productId, int qty) {
        if (qty < 0) throw new IllegalArgumentException("Quantity cannot be negative");
        if (qty == 0) items.remove(productId);
        else items.put(productId, qty);
    }

    public void removeItem(String productId) { items.remove(productId); }
    public void clear() { items.clear(); }
    public boolean isEmpty() { return items.isEmpty(); }

    /** Defensive snapshot — checkout works on a frozen view of the cart. */
    public Map<String, Integer> snapshot() { return Map.copyOf(items); }
}

public class User {
    private final String id, name, email;
    private final List<Address> addresses = new ArrayList<>();   // composition
    private final Cart cart = new Cart();                        // composition, 1:1

    public User(String id, String name, String email) {
        this.id = id; this.name = name; this.email = email;
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public Cart getCart() { return cart; }
    public void addAddress(Address a) { addresses.add(a); }
    public List<Address> getAddresses() { return List.copyOf(addresses); }
}
```

### 4.4 Order and OrderItem (the price snapshot)

```java
import java.math.BigDecimal;
import java.util.*;

/** Immutable line item: freezes name and price at the moment of purchase. */
public final class OrderItem {
    private final String productId, productName;
    private final int quantity;
    private final BigDecimal priceAtPurchase;

    public OrderItem(String productId, String productName, int quantity, BigDecimal priceAtPurchase) {
        this.productId = productId; this.productName = productName;
        this.quantity = quantity; this.priceAtPurchase = priceAtPurchase;
    }
    public String getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public BigDecimal lineTotal() {
        return priceAtPurchase.multiply(BigDecimal.valueOf(quantity));
    }
    @Override public String toString() {
        return productName + " x" + quantity + " @ " + priceAtPurchase;
    }
}

public class Order {
    private final String id, userId;
    private final List<OrderItem> items;        // composition — frozen at creation
    private final BigDecimal total;
    private final Address shippingAddress;
    private volatile String paymentId;
    private OrderStatus status = OrderStatus.PENDING_PAYMENT; // guarded by this

    public Order(String id, String userId, List<OrderItem> items, Address shippingAddress) {
        this.id = id; this.userId = userId;
        this.items = List.copyOf(items);
        this.shippingAddress = shippingAddress;
        this.total = items.stream()
                          .map(OrderItem::lineTotal)
                          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The ONLY way status changes. synchronized makes check-and-set atomic:
     * without it, two threads could both see PLACED and one ships while
     * the other cancels — an illegal double transition.
     */
    public synchronized void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                "Illegal transition " + status + " -> " + next + " for order " + id);
        }
        this.status = next;
    }

    public synchronized OrderStatus getStatus() { return status; }
    public String getId() { return id; }
    public String getUserId() { return userId; }
    public List<OrderItem> getItems() { return items; }
    public BigDecimal getTotal() { return total; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public String getPaymentId() { return paymentId; }
}
```

### 4.5 InventoryService — the concurrency core

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns all stock state. Catalog (Product) knows nothing about stock —
 * the item-type / hot-mutable-state split.
 *
 * Multi-product reservation strategy: acquire ALL per-product locks in
 * sorted productId order, then check-all, then commit-all.
 *  - Sorted acquisition => no deadlock (a cycle needs two threads locking
 *    in opposite orders; a global total order makes cycles impossible).
 *  - Holding all locks across check+commit => the reservation is atomic:
 *    no other thread can sneak a decrement between our check and our write.
 */
public class InventoryService {
    private final Map<String, Integer> stock = new HashMap<>();           // guarded by per-product locks
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(String productId) {
        // computeIfAbsent is atomic: two threads asking for the same
        // product's lock always receive the SAME lock instance.
        return locks.computeIfAbsent(productId, id -> new ReentrantLock());
    }

    public void addStock(String productId, int qty) {
        ReentrantLock lock = lockFor(productId);
        lock.lock();
        try { stock.merge(productId, qty, Integer::sum); }
        finally { lock.unlock(); }
    }

    public int getAvailable(String productId) {
        ReentrantLock lock = lockFor(productId);
        lock.lock();
        try { return stock.getOrDefault(productId, 0); }
        finally { lock.unlock(); }
    }

    /** Atomic all-or-nothing reservation across multiple products. */
    public boolean reserve(Map<String, Integer> requested) {
        List<String> ids = new ArrayList<>(requested.keySet());
        Collections.sort(ids);                       // consistent global lock order
        Deque<ReentrantLock> acquired = new ArrayDeque<>();
        try {
            for (String id : ids) {
                ReentrantLock l = lockFor(id);
                l.lock();
                acquired.push(l);
            }
            // Phase 1: check everything while holding all locks
            for (String id : ids) {
                if (stock.getOrDefault(id, 0) < requested.get(id)) {
                    return false;                    // nothing written yet — clean abort
                }
            }
            // Phase 2: commit everything
            for (String id : ids) {
                stock.merge(id, -requested.get(id), Integer::sum);
            }
            return true;
        } finally {
            while (!acquired.isEmpty()) acquired.pop().unlock();  // release in reverse
        }
    }

    /** Compensation: give reserved stock back (payment failed / order cancelled). */
    public void release(Map<String, Integer> reserved) {
        List<String> ids = new ArrayList<>(reserved.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            ReentrantLock l = lockFor(id);
            l.lock();
            try { stock.merge(id, reserved.get(id), Integer::sum); }
            finally { l.unlock(); }
        }
    }
}
```

> **Design note — check-then-commit vs. claim-and-compensate.** Concert Ticket used *claim each seat, compensate on partial failure*. Here we instead hold all locks and check before writing, so we never need intra-reservation compensation. Both are valid; this version is cleaner when lock hold time is microseconds. The claim-and-compensate variant wins when you *cannot* hold multiple locks at once (e.g., the "locks" are rows in different database shards). Know both and say why you chose yours.

### 4.6 Payment strategies

```java
import java.math.BigDecimal;
import java.util.UUID;

public class Payment {
    private final String id, orderId;
    private final BigDecimal amount;
    private final String method;
    private volatile PaymentStatus status;

    public Payment(String orderId, BigDecimal amount, String method, PaymentStatus status) {
        this.id = UUID.randomUUID().toString();
        this.orderId = orderId; this.amount = amount;
        this.method = method; this.status = status;
    }
    public String getId() { return id; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus s) { this.status = s; }
}

/** Strategy: isolates the one varying step of checkout. */
public interface PaymentStrategy {
    Payment pay(String orderId, BigDecimal amount);
    void refund(Payment payment);
}

public class CardPaymentStrategy implements PaymentStrategy {
    @Override public Payment pay(String orderId, BigDecimal amount) {
        // Real impl: external gateway call — slow, fallible. NEVER under a lock.
        boolean gatewayApproved = true; // simulate
        return new Payment(orderId, amount, "CARD",
                gatewayApproved ? PaymentStatus.SUCCESS : PaymentStatus.FAILED);
    }
    @Override public void refund(Payment p) { p.setStatus(PaymentStatus.REFUNDED); }
}

public class UpiPaymentStrategy implements PaymentStrategy {
    @Override public Payment pay(String orderId, BigDecimal amount) {
        return new Payment(orderId, amount, "UPI", PaymentStatus.SUCCESS);
    }
    @Override public void refund(Payment p) { p.setStatus(PaymentStatus.REFUNDED); }
}

/** Useful for demos/tests: always declines. */
public class AlwaysFailingPaymentStrategy implements PaymentStrategy {
    @Override public Payment pay(String orderId, BigDecimal amount) {
        return new Payment(orderId, amount, "TEST", PaymentStatus.FAILED);
    }
    @Override public void refund(Payment p) { p.setStatus(PaymentStatus.REFUNDED); }
}
```

### 4.7 Catalog with composable search

```java
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ProductCatalog {
    private final Map<String, Product> products = new ConcurrentHashMap<>();

    public void addProduct(Product p) { products.put(p.getId(), p); }
    public Optional<Product> getProduct(String id) { return Optional.ofNullable(products.get(id)); }

    public List<Product> search(Predicate<Product> criteria) {
        return products.values().stream().filter(criteria).collect(Collectors.toList());
    }

    /* ---- Reusable, composable filters (Strategy via Predicate) ---- */
    public static Predicate<Product> nameOrDescriptionContains(String kw) {
        String k = kw.toLowerCase();
        return p -> p.getName().toLowerCase().contains(k)
                 || p.getDescription().toLowerCase().contains(k);
    }
    public static Predicate<Product> inCategory(String categoryId) {
        return p -> p.getCategoryIds().contains(categoryId);
    }
    public static Predicate<Product> priceBetween(BigDecimal min, BigDecimal max) {
        return p -> p.getPrice().compareTo(min) >= 0 && p.getPrice().compareTo(max) <= 0;
    }
}
```

### 4.8 The Facade — checkout orchestration

```java
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class OnlineShoppingService {

    /* ---- Singleton via initialization-on-demand holder ---- */
    private OnlineShoppingService() {}
    private static class Holder { static final OnlineShoppingService INSTANCE = new OnlineShoppingService(); }
    public static OnlineShoppingService getInstance() { return Holder.INSTANCE; }

    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
    private final ProductCatalog catalog = new ProductCatalog();
    private final InventoryService inventory = new InventoryService();
    private final AtomicLong orderSeq = new AtomicLong();

    public ProductCatalog catalog() { return catalog; }
    public InventoryService inventory() { return inventory; }

    public User registerUser(String name, String email) {
        User u = new User(UUID.randomUUID().toString(), name, email);
        users.put(u.getId(), u);
        return u;
    }

    public void addToCart(String userId, String productId, int qty) {
        User user = requireUser(userId);
        Product p = catalog.getProduct(productId)
                .orElseThrow(() -> new NoSuchElementException("Unknown product: " + productId));
        // Soft availability check — a courtesy, NOT a guarantee. The only
        // authoritative check is inside reserve() at checkout.
        if (inventory.getAvailable(p.getId()) < qty) {
            throw new IllegalStateException("Insufficient stock for " + p.getName());
        }
        user.getCart().addItem(productId, qty);
    }

    /**
     * Two-phase checkout:
     *   Phase A (locked, microseconds): atomically reserve stock for ALL items.
     *   Phase B (NO locks held):        call payment gateway.
     *   Phase C: confirm (PLACED) or compensate (release stock, CANCELLED).
     */
    public Order checkout(String userId, Address shipTo, PaymentStrategy paymentStrategy) {
        User user = requireUser(userId);
        Map<String, Integer> cartSnapshot = user.getCart().snapshot();
        if (cartSnapshot.isEmpty()) throw new IllegalStateException("Cart is empty");

        // Build frozen line items: price snapshotted HERE.
        List<OrderItem> items = cartSnapshot.entrySet().stream()
                .map(e -> {
                    Product p = catalog.getProduct(e.getKey())
                            .orElseThrow(() -> new NoSuchElementException("Product vanished: " + e.getKey()));
                    return new OrderItem(p.getId(), p.getName(), e.getValue(), p.getPrice());
                })
                .collect(Collectors.toList());

        Order order = new Order("ORD-" + orderSeq.incrementAndGet(), userId, items, shipTo);
        orders.put(order.getId(), order);

        // Phase A — atomic multi-product reservation
        if (!inventory.reserve(cartSnapshot)) {
            order.transitionTo(OrderStatus.CANCELLED);
            throw new IllegalStateException("Out of stock — order " + order.getId() + " cancelled");
        }

        // Phase B — external I/O with zero locks held
        Payment payment = paymentStrategy.pay(order.getId(), order.getTotal());
        order.setPaymentId(payment.getId());

        // Phase C — confirm or compensate
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            order.transitionTo(OrderStatus.PLACED);
            user.getCart().clear();
        } else {
            inventory.release(cartSnapshot);          // compensation: restock
            order.transitionTo(OrderStatus.CANCELLED);
            throw new IllegalStateException("Payment failed — order " + order.getId() + " cancelled, stock released");
        }
        return order;
    }

    public void cancelOrder(String orderId, PaymentStrategy refundVia) {
        Order order = orders.get(orderId);
        if (order == null) throw new NoSuchElementException("Unknown order: " + orderId);

        // transitionTo is synchronized — a concurrent ship() and cancel()
        // cannot both succeed; the loser gets IllegalStateException.
        order.transitionTo(OrderStatus.CANCELLED);

        Map<String, Integer> qtys = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
        inventory.release(qtys);                       // restock
        // refund (simplified: real code would look up the Payment record)
    }

    public void shipOrder(String orderId)    { orders.get(orderId).transitionTo(OrderStatus.SHIPPED); }
    public void deliverOrder(String orderId) { orders.get(orderId).transitionTo(OrderStatus.DELIVERED); }

    public List<Order> getOrderHistory(String userId) {
        return orders.values().stream()
                .filter(o -> o.getUserId().equals(userId))
                .collect(Collectors.toList());
    }

    public List<Product> search(Predicate<Product> criteria) { return catalog.search(criteria); }

    private User requireUser(String userId) {
        User u = users.get(userId);
        if (u == null) throw new NoSuchElementException("Unknown user: " + userId);
        return u;
    }
}
```

### 4.9 Demo

```java
import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class ShoppingDemo {
    public static void main(String[] args) throws InterruptedException {
        OnlineShoppingService svc = OnlineShoppingService.getInstance();

        // --- Catalog setup ---
        Category electronics = new Category("CAT-1", "Electronics");
        Product laptop = new Product("P-1", "ThinkPad X1", "14-inch ultrabook",
                new BigDecimal("1200.00"), Set.of(electronics.getId()));
        Product mouse  = new Product("P-2", "MX Master 3", "Wireless mouse",
                new BigDecimal("99.00"), Set.of(electronics.getId()));
        svc.catalog().addProduct(laptop);
        svc.catalog().addProduct(mouse);
        svc.inventory().addStock("P-1", 1);   // only ONE laptop — oversell test
        svc.inventory().addStock("P-2", 10);

        // --- Search demo: composed predicates ---
        var results = svc.search(
                ProductCatalog.nameOrDescriptionContains("mouse")
                        .and(ProductCatalog.priceBetween(new BigDecimal("50"), new BigDecimal("150"))));
        System.out.println("Search results: " + results.size());   // 1

        // --- Happy path ---
        User alice = svc.registerUser("Alice", "alice@example.com");
        Address aliceAddr = new Address("1 Main St", "Pondicherry", "PY", "605001");
        svc.addToCart(alice.getId(), "P-2", 2);
        Order o1 = svc.checkout(alice.getId(), aliceAddr, new UpiPaymentStrategy());
        System.out.println(o1.getId() + " -> " + o1.getStatus() + " total " + o1.getTotal()); // PLACED 198.00

        // --- Payment failure => compensation ---
        User bob = svc.registerUser("Bob", "bob@example.com");
        svc.addToCart(bob.getId(), "P-2", 1);
        try {
            svc.checkout(bob.getId(), aliceAddr, new AlwaysFailingPaymentStrategy());
        } catch (IllegalStateException e) {
            System.out.println("Expected: " + e.getMessage());
        }
        System.out.println("Mouse stock after failed payment: "
                + svc.inventory().getAvailable("P-2"));            // 9 — restocked

        // --- Concurrent oversell test: 2 buyers, 1 laptop ---
        User u1 = svc.registerUser("U1", "u1@x.com");
        User u2 = svc.registerUser("U2", "u2@x.com");
        svc.addToCart(u1.getId(), "P-1", 1);
        svc.addToCart(u2.getId(), "P-1", 1);

        CountDownLatch start = new CountDownLatch(1);
        Runnable buyer = () -> {
            try {
                start.await();
                Order o = svc.checkout(Thread.currentThread().getName().equals("t1")
                                ? u1.getId() : u2.getId(),
                        aliceAddr, new CardPaymentStrategy());
                System.out.println(Thread.currentThread().getName() + " got " + o.getId());
            } catch (Exception e) {
                System.out.println(Thread.currentThread().getName() + " failed: " + e.getMessage());
            }
        };
        Thread t1 = new Thread(buyer, "t1"), t2 = new Thread(buyer, "t2");
        t1.start(); t2.start();
        start.countDown();                 // release both simultaneously
        t1.join(); t2.join();
        System.out.println("Laptop stock: " + svc.inventory().getAvailable("P-1")); // 0, exactly one winner
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### 5.1 Exception strategy

| Failure | Exception | Handling |
|---|---|---|
| Unknown user / product / order | `NoSuchElementException` | Fail fast at the facade boundary |
| Non-positive cart quantity | `IllegalArgumentException` | Validated in `Cart` before any state mutates |
| Checkout with empty cart | `IllegalStateException` | Guard at start of `checkout()` |
| Out of stock at reservation | `IllegalStateException` | Order transitions to `CANCELLED`; nothing was decremented (check-before-commit) |
| Payment failure | `IllegalStateException` | **Compensation**: `inventory.release()` then `CANCELLED` |
| Illegal status transition | `IllegalStateException` from `transitionTo` | Single guarded mutation point — invalid lifecycles are unrepresentable |

Interview talking point: in production these become a small custom hierarchy (`OutOfStockException`, `PaymentFailedException extends OrderException`) so callers can catch selectively; unchecked, because callers can't meaningfully recover mid-method.

### 5.2 Edge cases

1. **Price changes between add-to-cart and checkout** — handled by design: cart stores only quantities; `OrderItem` snapshots price at checkout. (Real Amazon shows a "price changed" notice; that's a UI concern over the same model.)
2. **Product deleted while in someone's cart** — `checkout` re-resolves every productId; a vanished product fails the order before any reservation.
3. **Add-to-cart availability check is advisory** — stock can vanish between `addToCart` and `checkout`. The design accepts this (TOCTOU is unavoidable for a courtesy check) because the *authoritative* check lives inside `reserve()` under locks.
4. **Concurrent cancel vs. ship on the same order** — `transitionTo` is `synchronized` check-and-set; exactly one wins, the other throws. Without synchronization both could read `PLACED` and both "succeed."
5. **Double cancellation** — second call finds status `CANCELLED`, transition table rejects it, no double restock / double refund.
6. **Duplicate checkout clicks** — two threads snapshot the same cart and could both reserve. Mitigation: idempotency key per checkout attempt, or a per-cart "checkout in progress" CAS flag (`AtomicBoolean.compareAndSet`). Worth raising proactively in an interview.
7. **Cart cleared only on success** — a failed payment leaves the cart intact so the user can retry with another method.

### 5.3 Concurrency analysis

**Shared mutable state inventory:**

| State | Primitive | Why |
|---|---|---|
| Stock counts | `HashMap` guarded by per-product `ReentrantLock`s | The map itself is never accessed without holding the relevant product's lock; per-product granularity = disjoint products never contend |
| Lock registry | `ConcurrentHashMap.computeIfAbsent` | Atomic lock creation — both racers get the *same* lock object |
| `Order.status` | `synchronized` transitions | Check-and-set must be atomic; contention on a single order is near-zero, so a heavier primitive buys nothing |
| `Product.price` | `volatile` | Single reference, write-rarely/read-often; visibility is the only requirement |
| Users / orders registries | `ConcurrentHashMap` | Independent puts/gets, no compound invariants across keys |
| Cart items | `ConcurrentHashMap` + `merge` | Atomic per-key read-modify-write covers the two-tabs race |
| Order id | `AtomicLong` | Lock-free unique sequence |

**Deadlock-freedom argument.** Deadlock requires a cycle in the waits-for graph. `reserve()` acquires per-product locks in **sorted productId order** — a global total order on lock acquisition makes cycles impossible, because a thread can only ever wait for a lock "greater" than all it holds. All other code paths (`addStock`, `release`, `getAvailable`) hold at most one inventory lock at a time, and no code path holds an inventory lock while taking an order monitor or vice versa — there is no lock nesting across subsystems.

**Race-freedom for overselling.** The only writes to stock happen inside `reserve`/`release`/`addStock` under the product's lock. `reserve` holds *all* relevant locks across its check-then-commit, so the invariant `stock ≥ 0` cannot be violated by interleaving: any competing reservation for an overlapping product blocks until ours completes and then re-checks against the updated count. The demo's two-buyers-one-laptop test demonstrates exactly one winner.

**No locks across I/O.** The payment call happens strictly after `reserve()` returns (all locks released). Worst case under a slow gateway: stock is *reserved* longer than ideal — a liveness/UX cost, never a safety cost. The production extension is a **reservation TTL** (your Airline pattern): a `ScheduledExecutorService` sweeps orders stuck in `PENDING_PAYMENT` past a deadline and compensates them.

**Livelock/starvation.** `ReentrantLock` default is unfair, which is fine here: hold times are microseconds and there is no retry loop that could livelock. If a flash-sale product creates a hot lock, `new ReentrantLock(true)` (fair) trades throughput for FIFO fairness — mention it, don't default to it.

---

## Interviewer Follow-ups (with model answers)

**Q1. "Make this a marketplace — multiple sellers per product."**
Introduce `Seller` and a `Listing` (sellerId, productId, price, stock) — the item-type/instance split again: `Product` stays catalog metadata, `Listing` is the purchasable unit. Inventory locks move to per-listing. Cart lines reference listingId. Buy-box selection is a `Strategy` over listings (lowest price, best rating).

**Q2. "10x scale — in-memory maps won't cut it. What changes?"**
Registries become repositories over a database. Per-product `ReentrantLock` maps to either pessimistic row locks (`SELECT ... FOR UPDATE` in sorted id order — same deadlock argument) or optimistic locking (version column; retry on conflict — better for low contention). Checkout becomes a saga: reserve → pay → confirm, with the compensation step we already have. Search moves to Elasticsearch behind the unchanged `catalog.search` interface; the Predicate API was the seam.

**Q3. "User clicks Buy twice — how do you prevent a double order?"**
Idempotency key: client generates a UUID per checkout intent; service keeps a `ConcurrentHashMap<idempotencyKey, Order>` and `putIfAbsent` — the second click returns the first order instead of re-executing. Cheaper local fix: `AtomicBoolean checkoutInProgress` on the cart with `compareAndSet(false, true)`.

**Q4. "Add coupons / discounts without touching checkout."**
`PricingStrategy` (or a chain of `DiscountRule`s — Decorator over the order total): `BigDecimal apply(Order draft)`. Checkout calls the strategy where it currently sums line totals. New promotion = new rule class. OCP, same shape as payment.

**Q5. "Payment gateway times out — you don't know if it charged. Now what?"**
This is the two-generals problem; you cannot resolve it synchronously. Mark payment `PENDING_VERIFICATION`, keep stock reserved with a TTL, and reconcile asynchronously (gateway status-query API or webhook). The key design property already in place: the order's state machine has an explicit pending state and compensation path, so uncertainty is representable rather than catastrophic.

---

## Transferable Lessons

1. **Snapshot at composition boundaries.** `OrderItem` copying `priceAtPurchase` is the same idea as freezing a bid amount in Auction: historical records must not alias live mutable state. Watch for it in every "X history" requirement.
2. **Sorted multi-lock acquisition** is the second tool (alongside claim-and-compensate from Concert Ticket) for multi-resource atomicity. Choose holding-all-locks when hold time is tiny and locks are local; choose claim-and-compensate when locks can't coexist (distributed).
3. **Courtesy checks vs. authoritative checks.** The add-to-cart stock check is advisory; only the locked check inside `reserve()` is truth. Naming this TOCTOU distinction out loud scores points.
4. **The cart/order split** generalizes: *intent* objects are mutable and cheap; *commitment* objects are immutable and guarded. (Cart/Order, BidIntent/Bid, SearchDraft/Booking.)
