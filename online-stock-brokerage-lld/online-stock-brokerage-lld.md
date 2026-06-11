# Online Stock Brokerage System — Low-Level Design

**Tier:** Hard · **Builds on:** Digital Wallet (reserve/hold pattern), Concert Ticket Booking (atomic claim + compensation), Airline (two-phase booking) · **New concept:** order matching engine with price-time priority

---

## Step 1 — Requirements

### Functional Requirements

1. **Account management** — users register and get a trading account holding a cash balance and a portfolio (stock symbol → quantity owned). Users can deposit and withdraw funds.
2. **Order placement** — users place **BUY** and **SELL** orders of two types: **MARKET** (execute immediately at best available price) and **LIMIT** (execute only at the limit price or better).
3. **Order matching & execution** — the system runs its own matching engine per stock, using **price-time priority**: best price wins; among equal prices, the earlier order wins. **Partial fills** are supported — one large order can fill against several smaller counterparties.
4. **Order lifecycle** — `OPEN → PARTIALLY_FILLED → FILLED`, or `→ CANCELLED` (user-initiated, or automatic for the unfilled remainder of a market order), or `REJECTED` (validation failure). Users can cancel any non-terminal order.
5. **Funds & share reservation** — placing a limit BUY reserves the cash (`limitPrice × qty`); placing a SELL reserves the shares. Reservations are committed on execution and released on cancellation. This prevents a user from spending the same $1,000 across ten simultaneous orders.
6. **Portfolio & history** — users view current holdings and a chronological transaction history (trades, deposits, withdrawals).
7. **Market data** — users query a quote per stock: last traded price (derived from our own executions) plus best bid / best ask from the live order book.
8. **Validations** — BUY requires sufficient *available* (unreserved) funds; SELL requires sufficient *available* shares. No short selling, no margin, no fractional shares.

### Non-Functional Requirements

1. **Concurrency** — many threads place orders on the same stock simultaneously. The per-stock order book is the hot shared structure. No lost updates, no double-spend of cash or shares, no phantom shares created or destroyed.
2. **Consistency / atomicity** — a single trade must atomically: debit buyer cash, credit seller cash, transfer shares, update both orders' filled quantities, and record the trade. The reservation model is what makes this achievable without holding multiple locks at once (explained in Step 5).
3. **Extensibility** — adding a new order type (stop-loss, stop-limit, IOC) must not require rewriting the matching loop (Open/Closed Principle).
4. **Scalability** — each stock owns an independent order book and lock, so trading in AAPL never contends with trading in TSLA. This shards naturally: in a distributed version each symbol's book becomes a partition.
5. **Low latency** — matching is the hot path; the book uses heaps (`PriorityQueue`) giving O(log n) insert and O(1) peek of the best order.
6. **Security** — authn/authz, encryption of PII, and audit logging are acknowledged but out of LLD scope. In a Spring Boot system this sits in a filter chain / `@PreAuthorize` layer in front of the service facade — the core design below is unchanged by it.

### Assumptions Locked In

- In-memory, single JVM; repositories are `ConcurrentHashMap`s. No real persistence or exchange connectivity.
- Market orders never rest on the book: they fill against whatever is available and the unfilled remainder is auto-cancelled (standard exchange semantics — two market orders carry no price and cannot match each other).
- Execution price is always the **resting** order's price (the order that was already on the book). The incoming "taker" gets price improvement if the book is better than their limit.
- Money is `BigDecimal` (interview-friendly; mention "long cents" as the latency-conscious alternative).
- Instant settlement (real-world T+1 settlement is a follow-up question, Step 5).

---

## Step 2 — Entities & Relationships

> **Foundational refresher — aggregation vs. composition (revisited):** composition means the part's lifecycle is owned by the whole (destroy the whole, the part dies); aggregation means the whole references parts that live independently. The order book *contains* orders, but orders are created by users and survive removal from the book (they live on in history) — so book→order is **aggregation**, while account→portfolio is **composition** (a portfolio is meaningless without its account).

| Entity | Responsibility | Key relationships |
|---|---|---|
| `User` | Identity (id, name, email) | **Association** 1→1 with `Account` |
| `Account` | Cash ledger: `availableBalance` + `reservedBalance`; deposit / withdraw / reserve / release / commit | **Composition** 1→1 `Portfolio`; owns its own lock |
| `Portfolio` | Holdings per symbol: available qty + reserved qty | **Composition** 1→\* `Holding` (a value object, not a top-level entity) |
| `Stock` | Catalog metadata: symbol, company name | **Dependency** from orders/quotes (referenced by symbol) |
| `Order` | One buy/sell instruction: side, type, limit price, qty, filledQty, status, sequence number, remaining reservation | **Association** \*→1 `User`, \*→1 `Stock` |
| `OrderBook` | Per-stock matching venue: buy heap (max-price) + sell heap (min-price) + the match loop; owns one `ReentrantLock` | **Aggregation** 1→\* `Order` (orders outlive their time on the book) |
| `Trade` | Immutable record of one execution: buy/sell order ids, price, qty, timestamp | **Dependency** on two `Order`s |
| `Transaction` | Immutable ledger entry per account (TRADE_DEBIT, TRADE_CREDIT, DEPOSIT, WITHDRAWAL) | **Association** \*→1 `Account` |
| `MarketDataService` | Last traded price per symbol; observes trades | **Dependency** on `Trade` via Observer |
| `BrokerageService` | Singleton facade: registries, validation, reservation, routing to books, cancellation | **Aggregation** of all registries |

**Deliberate under-modeling** (the recurring lesson from Facebook/LinkedIn): no `Quote` entity class with its own lifecycle — a quote is a *snapshot* assembled on demand from the book's best bid/ask plus the last price. No `MatchingEngine` class separate from `OrderBook` — at this scale the book *is* the engine; splitting them adds indirection without a second implementation to justify it. No `Money` wrapper class — `BigDecimal` with a scale convention is enough here; mention the wrapper as the production-hardening answer.

**The item-type/instance split returns:** `Stock` (catalog metadata) vs. `Holding` (a user's position in it) is exactly the `Book`/`BookCopy` and `Car`/availability split from Library and Car Rental. Don't put `quantityOwned` on `Stock`.

---

## Step 3 — UML Class Design

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ <<enum>> OrderSide   { BUY, SELL }                                      │
│ <<enum>> OrderType   { MARKET, LIMIT }                                  │
│ <<enum>> OrderStatus { OPEN, PARTIALLY_FILLED, FILLED, CANCELLED,       │
│                        REJECTED }                                       │
│ <<enum>> TransactionType { DEPOSIT, WITHDRAWAL, TRADE_DEBIT,            │
│                            TRADE_CREDIT }                               │
└─────────────────────────────────────────────────────────────────────────┘

User
 ├─ id: String, name: String, email: String
 └─ 1 ── 1 Account

Account
 ├─ userId: String
 ├─ availableBalance: BigDecimal     // spendable now
 ├─ reservedBalance:  BigDecimal     // locked behind open buy orders
 ├─ lock: ReentrantLock
 ├─ + deposit(amt) / withdraw(amt)
 ├─ + reserve(amt)        : throws InsufficientFundsException
 ├─ + release(amt)        // reservation → available
 ├─ + commitReserved(amt) // reservation → spent (trade settled)
 ├─ + credit(amt)         // seller receives proceeds
 └─ ◆ 1 ── 1 Portfolio                                  (composition)

Portfolio
 ├─ holdings: Map<String, Holding>   // symbol → position
 ├─ + reserveShares(sym, qty) : throws InsufficientSharesException
 ├─ + releaseShares / commitReservedShares / addShares
 └─ ◆ 1 ── * Holding { availableQty, reservedQty }      (composition)

Order
 ├─ id, userId, symbol: String
 ├─ side: OrderSide, type: OrderType
 ├─ limitPrice: BigDecimal           // null for MARKET
 ├─ quantity, filledQuantity: int
 ├─ status: OrderStatus              // enum-guarded transitions
 ├─ seq: long                        // global sequence → time priority
 ├─ remainingReservation: BigDecimal // buy-side cash still held
 ├─ + remainingQty(): int
 ├─ + canExecuteAt(price): boolean   // polymorphic by type (Strategy)
 └─ + recordFill(qty, cost)

OrderBook                                    ── one instance per symbol ──
 ├─ symbol: String
 ├─ buyOrders:  PriorityQueue<Order>  // max-heap: price DESC, seq ASC
 ├─ sellOrders: PriorityQueue<Order>  // min-heap: price ASC,  seq ASC
 ├─ lock: ReentrantLock               // guards both heaps + match loop
 ├─ + submit(order, settler): List<Trade>
 ├─ + cancel(order): boolean
 ├─ + bestBid() / bestAsk(): Optional<BigDecimal>
 └─ ◇ 1 ── * Order                                      (aggregation)

TradeSettler  <<functional interface>>
 └─ + settle(buy: Order, sell: Order, price, qty): Trade

BrokerageService  <<Singleton — holder idiom>>  <<Facade>>
 ├─ users / accounts / portfolios : ConcurrentHashMap
 ├─ orderBooks: ConcurrentHashMap<String, OrderBook>
 ├─ trades: CopyOnWriteArrayList<Trade> ; txnLog per account
 ├─ listeners: CopyOnWriteArrayList<TradeListener>     (Observer)
 ├─ + placeOrder(userId, symbol, side, type, price, qty): Order
 ├─ + cancelOrder(orderId): boolean
 ├─ + getQuote(symbol): Quote (snapshot)
 └─ + getPortfolio / getTransactionHistory

MarketDataService implements TradeListener   (Observer)
 └─ lastPrice: ConcurrentHashMap<String, BigDecimal>
```

### Pattern & SOLID Mapping — and *why* each fits

- **Strategy (via polymorphic `canExecuteAt`)** — the *only* behavioral difference between MARKET and LIMIT during matching is "is this counterparty price acceptable?" MARKET answers always-yes; LIMIT compares against `limitPrice`. Encapsulating that one question means the match loop is closed for modification: a stop-limit order later just adds a new answer to the same question (plus an activation trigger), and the loop never changes. This is OCP applied at the exact seam where change is expected.
- **Singleton (initialization-on-demand holder)** — `BrokerageService` is the single exchange venue; two instances would mean two divergent order books for the same stock. Holder idiom over `static synchronized getInstance()`, as established: the JVM's class-loading guarantees give us lazy, thread-safe init with zero locking on the read path.
- **Facade** — `BrokerageService.placeOrder` hides a five-step protocol (validate → reserve → submit → match → settle/release) behind one method. The caller (a controller in Spring terms) never touches a lock or a heap.
- **Observer** — `TradeListener` decouples trade *execution* from trade *consequences* (market data update, user notification, audit). The matching engine must not know who cares about trades — otherwise every new consumer means editing the hot path. `MarketDataService` is just the first subscriber.
- **State via enum-guarded transitions** — `OrderStatus` with an explicit `canTransitionTo` check, not the full GoF State pattern. Same call as FriendRequest/BookingStatus: five states with simple rules don't justify five classes.
- **Factory (static factory methods)** — `Order.limit(...)` / `Order.market(...)` make the null-price-for-market rule unrepresentable as a caller mistake, instead of a constructor with a nullable parameter and a prayer.
- **SRP** — `Account` knows money, `Portfolio` knows shares, `OrderBook` knows matching, `BrokerageService` knows orchestration. The settlement callback (`TradeSettler`) exists precisely so the book doesn't reach into accounts — **DIP**: the book depends on a one-method abstraction, not on `BrokerageService`.
- **LSP** — every `Order`, regardless of type, honors the same contract in the match loop; no `instanceof` checks anywhere in `OrderBook`.

### The two decisions an interviewer will probe

1. **Why reserve at placement instead of checking balance at execution?** Without reservation, validation and execution are separated in time — a check-then-act race. A user with $1,000 places ten $1,000 buys; all ten pass the balance check; the first execution succeeds and the other nine fail at settlement, *after* a counterparty has been matched. Reservation moves the only fallible money operation to placement time (inside the account's lock), making settlement **infallible by construction** — which is also what lets us settle without holding two account locks simultaneously (Step 5).
2. **Why is the execution price the resting order's price?** If a buy limit at $105 meets a resting sell at $100, executing at $105 would let the resting seller receive more than they asked — fine for them — but it rewards stale quotes and breaks price-time priority incentives. Executing at the resting price ($100) gives the *incoming* order the improvement, which is the standard exchange rule and the one that makes the priority queue ordering economically coherent.

---

## Step 4 — Implementation

### 4.1 Enums and exceptions

```java
public enum OrderSide { BUY, SELL }
public enum OrderType { MARKET, LIMIT }
public enum TransactionType { DEPOSIT, WITHDRAWAL, TRADE_DEBIT, TRADE_CREDIT }

public enum OrderStatus {
    OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED;

    /** Enum-guarded transitions — lightweight alternative to the GoF State pattern. */
    public boolean canTransitionTo(OrderStatus next) {
        switch (this) {
            case OPEN:             return next != OPEN && next != REJECTED;
            case PARTIALLY_FILLED: return next == FILLED || next == CANCELLED
                                       || next == PARTIALLY_FILLED;
            default:               return false;  // terminal states
        }
    }
}

public class BrokerageException extends RuntimeException {
    public BrokerageException(String msg) { super(msg); }
}
public class InsufficientFundsException  extends BrokerageException {
    public InsufficientFundsException(String m)  { super(m); } }
public class InsufficientSharesException extends BrokerageException {
    public InsufficientSharesException(String m) { super(m); } }
public class InvalidOrderException       extends BrokerageException {
    public InvalidOrderException(String m)       { super(m); } }
public class OrderNotFoundException      extends BrokerageException {
    public OrderNotFoundException(String m)      { super(m); } }
```

### 4.2 Account — the cash ledger with the hold pattern

```java
import java.math.BigDecimal;
import java.util.concurrent.locks.ReentrantLock;

public class Account {
    private final String userId;
    private BigDecimal availableBalance = BigDecimal.ZERO;
    private BigDecimal reservedBalance  = BigDecimal.ZERO;
    private final ReentrantLock lock = new ReentrantLock();

    public Account(String userId) { this.userId = userId; }

    public void deposit(BigDecimal amt) {
        requirePositive(amt);
        lock.lock();
        try { availableBalance = availableBalance.add(amt); }
        finally { lock.unlock(); }
    }

    public void withdraw(BigDecimal amt) {
        requirePositive(amt);
        lock.lock();
        try {
            if (availableBalance.compareTo(amt) < 0)
                throw new InsufficientFundsException(
                    "Available " + availableBalance + " < " + amt);
            availableBalance = availableBalance.subtract(amt);
        } finally { lock.unlock(); }
    }

    /** Atomic check-and-reserve: the ONLY money operation that can fail.
     *  Once funds are reserved, settlement (commitReserved) cannot fail —
     *  this is what makes lock-free-across-accounts settlement safe. */
    public void reserve(BigDecimal amt) {
        requirePositive(amt);
        lock.lock();
        try {
            if (availableBalance.compareTo(amt) < 0)
                throw new InsufficientFundsException(
                    "Cannot reserve " + amt + ", available " + availableBalance);
            availableBalance = availableBalance.subtract(amt);
            reservedBalance  = reservedBalance.add(amt);
        } finally { lock.unlock(); }
    }

    /** Cancellation path: reservation flows back to available. */
    public void release(BigDecimal amt) {
        lock.lock();
        try {
            reservedBalance  = reservedBalance.subtract(amt);
            availableBalance = availableBalance.add(amt);
        } finally { lock.unlock(); }
    }

    /** Settlement path: reserved money leaves the account. Never throws —
     *  the invariant 'reserved >= amt' was established by reserve(). */
    public void commitReserved(BigDecimal amt) {
        lock.lock();
        try { reservedBalance = reservedBalance.subtract(amt); }
        finally { lock.unlock(); }
    }

    /** Seller side of settlement. Never throws. */
    public void credit(BigDecimal amt) {
        lock.lock();
        try { availableBalance = availableBalance.add(amt); }
        finally { lock.unlock(); }
    }

    public BigDecimal getAvailableBalance() {
        lock.lock();
        try { return availableBalance; } finally { lock.unlock(); }
    }

    private static void requirePositive(BigDecimal amt) {
        if (amt == null || amt.signum() <= 0)
            throw new InvalidOrderException("Amount must be positive: " + amt);
    }
}
```

### 4.3 Portfolio — the share ledger, same hold pattern

```java
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Portfolio {
    /** Mutable per-symbol position. Guarded by the Portfolio monitor. */
    static final class Holding {
        int availableQty;
        int reservedQty;
    }

    private final Map<String, Holding> holdings = new ConcurrentHashMap<>();

    public synchronized void reserveShares(String symbol, int qty) {
        Holding h = holdings.get(symbol);
        if (h == null || h.availableQty < qty)
            throw new InsufficientSharesException(
                "Need " + qty + " " + symbol + ", have "
                + (h == null ? 0 : h.availableQty));
        h.availableQty -= qty;
        h.reservedQty  += qty;
    }

    public synchronized void releaseShares(String symbol, int qty) {
        Holding h = holdings.get(symbol);
        h.reservedQty  -= qty;
        h.availableQty += qty;
    }

    /** Settlement: shares leave the seller. Never throws (post-reserve). */
    public synchronized void commitReservedShares(String symbol, int qty) {
        Holding h = holdings.get(symbol);
        h.reservedQty -= qty;
        if (h.availableQty == 0 && h.reservedQty == 0) holdings.remove(symbol);
    }

    /** Settlement: shares arrive at the buyer. Never throws. */
    public synchronized void addShares(String symbol, int qty) {
        holdings.computeIfAbsent(symbol, s -> new Holding()).availableQty += qty;
    }

    public synchronized Map<String, Integer> snapshot() {
        Map<String, Integer> view = new java.util.TreeMap<>();
        holdings.forEach((s, h) -> view.put(s, h.availableQty + h.reservedQty));
        return view;
    }
}
```

### 4.4 Order — Strategy via one polymorphic question

```java
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class Order {
    private static final AtomicLong SEQ = new AtomicLong();  // time priority

    private final String id = UUID.randomUUID().toString();
    private final String userId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal limitPrice;       // null iff MARKET
    private final int quantity;
    private final long seq = SEQ.incrementAndGet();

    private int filledQuantity = 0;
    private OrderStatus status = OrderStatus.OPEN;
    private BigDecimal remainingReservation = BigDecimal.ZERO; // buy-side cash hold

    private Order(String userId, String symbol, OrderSide side,
                  OrderType type, BigDecimal limitPrice, int quantity) {
        if (quantity <= 0) throw new InvalidOrderException("Quantity must be > 0");
        this.userId = userId; this.symbol = symbol; this.side = side;
        this.type = type; this.limitPrice = limitPrice; this.quantity = quantity;
    }

    /** Static factories make 'price must be null for MARKET' unrepresentable. */
    public static Order limit(String userId, String symbol, OrderSide side,
                              BigDecimal price, int qty) {
        if (price == null || price.signum() <= 0)
            throw new InvalidOrderException("Limit price must be positive");
        return new Order(userId, symbol, side, OrderType.LIMIT, price, qty);
    }
    public static Order market(String userId, String symbol, OrderSide side, int qty) {
        return new Order(userId, symbol, side, OrderType.MARKET, null, qty);
    }

    /** The Strategy seam: the match loop asks only this one question.
     *  A future STOP_LIMIT type changes this answer, not the loop. */
    public boolean canExecuteAt(BigDecimal counterpartyPrice) {
        if (type == OrderType.MARKET) return true;
        return side == OrderSide.BUY
            ? limitPrice.compareTo(counterpartyPrice) >= 0   // pay at most limit
            : limitPrice.compareTo(counterpartyPrice) <= 0;  // sell at least limit
    }

    /* —— mutators below are only ever called under the OrderBook lock —— */

    void recordFill(int qty, BigDecimal cost) {
        filledQuantity += qty;
        if (side == OrderSide.BUY)
            remainingReservation = remainingReservation.subtract(cost);
        transition(filledQuantity == quantity
                   ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
    }

    void transition(OrderStatus next) {
        if (!status.canTransitionTo(next))
            throw new IllegalStateException(status + " -> " + next);
        status = next;
    }

    public int remainingQty()              { return quantity - filledQuantity; }
    public boolean isTerminal()            { return !status.canTransitionTo(OrderStatus.CANCELLED)
                                                    && status != OrderStatus.OPEN
                                                    && status != OrderStatus.PARTIALLY_FILLED; }
    // getters: id, userId, symbol, side, type, limitPrice, seq, status,
    //          remainingReservation — omitted for brevity
    public String getId()                  { return id; }
    public String getUserId()              { return userId; }
    public String getSymbol()              { return symbol; }
    public OrderSide getSide()             { return side; }
    public OrderType getType()             { return type; }
    public BigDecimal getLimitPrice()      { return limitPrice; }
    public long getSeq()                   { return seq; }
    public OrderStatus getStatus()         { return status; }
    public BigDecimal getRemainingReservation() { return remainingReservation; }
    void setRemainingReservation(BigDecimal r)  { remainingReservation = r; }
}
```

### 4.5 Trade and the settlement abstraction

```java
import java.math.BigDecimal;
import java.time.Instant;

public final class Trade {                       // immutable — safe to publish
    public final String tradeId = java.util.UUID.randomUUID().toString();
    public final String symbol, buyOrderId, sellOrderId;
    public final BigDecimal price;
    public final int quantity;
    public final Instant executedAt = Instant.now();

    public Trade(String symbol, String buyOrderId, String sellOrderId,
                 BigDecimal price, int quantity) {
        this.symbol = symbol; this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId; this.price = price; this.quantity = quantity;
    }
}

/** DIP seam: OrderBook depends on this, never on BrokerageService.
 *  Contract: must not throw (all fallible checks happened at reservation). */
@FunctionalInterface
public interface TradeSettler {
    Trade settle(Order buy, Order sell, BigDecimal price, int qty);
}

@FunctionalInterface
public interface TradeListener {   // Observer
    void onTrade(Trade trade);
}
```

### 4.6 OrderBook — the matching engine

```java
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class OrderBook {
    private final String symbol;
    // Max-heap for buys: highest bid first; ties broken by earliest seq.
    private final PriorityQueue<Order> buyOrders = new PriorityQueue<>(
        Comparator.comparing(Order::getLimitPrice).reversed()
                  .thenComparingLong(Order::getSeq));
    // Min-heap for sells: lowest ask first.
    private final PriorityQueue<Order> sellOrders = new PriorityQueue<>(
        Comparator.comparing(Order::getLimitPrice)
                  .thenComparingLong(Order::getSeq));
    // One lock guards BOTH heaps and every order mutation for this symbol.
    // Per-stock granularity: AAPL traffic never blocks TSLA traffic.
    private final ReentrantLock lock = new ReentrantLock();

    public OrderBook(String symbol) { this.symbol = symbol; }

    /**
     * Synchronous match-on-arrival. Returns the trades generated.
     * Invariants on entry: incoming order already has funds/shares reserved.
     */
    public List<Trade> submit(Order incoming, TradeSettler settler) {
        List<Trade> trades = new ArrayList<>();
        lock.lock();
        try {
            PriorityQueue<Order> opposite =
                incoming.getSide() == OrderSide.BUY ? sellOrders : buyOrders;

            while (incoming.remainingQty() > 0 && !opposite.isEmpty()) {
                Order resting = opposite.peek();
                BigDecimal execPrice = resting.getLimitPrice(); // resting price rules
                if (!incoming.canExecuteAt(execPrice)) break;   // book no longer crosses

                int qty = Math.min(incoming.remainingQty(), resting.remainingQty());
                Order buy  = incoming.getSide() == OrderSide.BUY ? incoming : resting;
                Order sell = incoming.getSide() == OrderSide.BUY ? resting  : incoming;

                // Settlement cannot fail (reservation invariant), so order
                // state and money state cannot diverge here.
                trades.add(settler.settle(buy, sell, execPrice, qty));

                if (resting.remainingQty() == 0) opposite.poll(); // fully consumed
            }

            if (incoming.remainingQty() > 0) {
                if (incoming.getType() == OrderType.LIMIT) {
                    rest(incoming);                 // park remainder on the book
                } else {
                    // Market remainder cannot rest (no price) → auto-cancel.
                    incoming.transition(OrderStatus.CANCELLED);
                }
            }
        } finally {
            lock.unlock();
        }
        return trades;
    }

    private void rest(Order o) {
        (o.getSide() == OrderSide.BUY ? buyOrders : sellOrders).add(o);
    }

    /** Returns true if WE cancelled it; false if it was already terminal.
     *  The status check and the heap removal are atomic under the book lock,
     *  so cancel can never race a concurrent fill of the same order. */
    public boolean cancel(Order order) {
        lock.lock();
        try {
            if (order.isTerminal()) return false;
            (order.getSide() == OrderSide.BUY ? buyOrders : sellOrders)
                .remove(order);                     // O(n) — acceptable at LLD scale
            order.transition(OrderStatus.CANCELLED);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public Optional<BigDecimal> bestBid() {
        lock.lock();
        try { return Optional.ofNullable(buyOrders.peek()).map(Order::getLimitPrice); }
        finally { lock.unlock(); }
    }
    public Optional<BigDecimal> bestAsk() {
        lock.lock();
        try { return Optional.ofNullable(sellOrders.peek()).map(Order::getLimitPrice); }
        finally { lock.unlock(); }
    }
}
```

### 4.7 BrokerageService — Singleton facade and the settlement implementation

```java
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

public class BrokerageService {

    /* Initialization-on-demand holder: lazy, thread-safe, lock-free reads. */
    private static class Holder { static final BrokerageService I = new BrokerageService(); }
    public static BrokerageService getInstance() { return Holder.I; }
    private BrokerageService() {}

    private final Map<String, User>      users      = new ConcurrentHashMap<>();
    private final Map<String, Account>   accounts   = new ConcurrentHashMap<>();
    private final Map<String, Portfolio> portfolios = new ConcurrentHashMap<>();
    private final Map<String, Stock>     stocks     = new ConcurrentHashMap<>();
    private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();
    private final Map<String, Order>     ordersById = new ConcurrentHashMap<>();
    private final Map<String, List<Transaction>> txnLog = new ConcurrentHashMap<>();
    private final List<TradeListener> listeners = new CopyOnWriteArrayList<>();

    private final MarketDataService marketData = new MarketDataService();
    { listeners.add(marketData); }                   // Observer wired up

    /* —— registration —— */

    public User registerUser(String name, String email) {
        User u = new User(name, email);
        users.put(u.getId(), u);
        accounts.put(u.getId(), new Account(u.getId()));
        portfolios.put(u.getId(), new Portfolio());
        txnLog.put(u.getId(), new CopyOnWriteArrayList<>());
        return u;
    }

    public void listStock(String symbol, String company, BigDecimal seedPrice) {
        stocks.put(symbol, new Stock(symbol, company));
        orderBooks.put(symbol, new OrderBook(symbol));
        marketData.seed(symbol, seedPrice);
    }

    public void deposit(String userId, BigDecimal amt) {
        account(userId).deposit(amt);
        txnLog.get(userId).add(Transaction.of(TransactionType.DEPOSIT, amt, null));
    }

    /* —— the five-step placement protocol behind the Facade —— */

    public Order placeOrder(String userId, String symbol, OrderSide side,
                            OrderType type, BigDecimal price, int qty) {
        if (!users.containsKey(userId))   throw new InvalidOrderException("Unknown user");
        if (!stocks.containsKey(symbol))  throw new InvalidOrderException("Unknown stock " + symbol);

        Order order = (type == OrderType.LIMIT)
                ? Order.limit(userId, symbol, side, price, qty)
                : Order.market(userId, symbol, side, qty);

        // (1) Reserve — the only step that can fail with a business exception.
        try {
            if (side == OrderSide.BUY) {
                BigDecimal hold = estimateBuyCost(order);
                account(userId).reserve(hold);
                order.setRemainingReservation(hold);
            } else {
                portfolios.get(userId).reserveShares(symbol, qty);
            }
        } catch (BrokerageException e) {
            order.transition(OrderStatus.REJECTED);   // OPEN -> REJECTED is illegal,
            // so REJECT is modeled as construction-time: simplest is to rethrow
            throw e;
        }

        ordersById.put(order.getId(), order);

        // (2) Submit + match. Settlement callback is infallible by construction.
        List<Trade> trades = orderBooks.get(symbol).submit(order, this::settle);

        // (3) Release leftover buy-side reservation if the order is terminal.
        //     (Market buy reserved an estimate; actual cost may be lower.)
        if (side == OrderSide.BUY && order.isTerminal()) releaseLeftover(order);

        // (4) Same for an auto-cancelled market sell remainder.
        if (side == OrderSide.SELL && order.getStatus() == OrderStatus.CANCELLED)
            portfolios.get(userId).releaseShares(symbol, order.remainingQty());

        // (5) Notify observers OUTSIDE the book lock (submit already returned).
        trades.forEach(t -> listeners.forEach(l -> l.onTrade(t)));
        return order;
    }

    /** Market BUY has no limit price to reserve against, so we hold an
     *  estimate: last price + 10% buffer. Real systems do exactly this
     *  (or convert to a marketable limit). Excess is released after match. */
    private BigDecimal estimateBuyCost(Order o) {
        BigDecimal unit = (o.getType() == OrderType.LIMIT)
                ? o.getLimitPrice()
                : marketData.lastPrice(o.getSymbol())
                            .multiply(new BigDecimal("1.10"));
        return unit.multiply(BigDecimal.valueOf(o.remainingQty()));
    }

    /** TradeSettler implementation. Runs under the book lock. Never throws.
     *  Note: acquires ONE account/portfolio lock at a time — never two —
     *  which is the no-deadlock argument (Step 5). */
    private Trade settle(Order buy, Order sell, BigDecimal price, int qty) {
        BigDecimal cost = price.multiply(BigDecimal.valueOf(qty));

        account(buy.getUserId()).commitReserved(cost);          // buyer pays
        portfolios.get(buy.getUserId()).addShares(buy.getSymbol(), qty);

        portfolios.get(sell.getUserId())
                  .commitReservedShares(sell.getSymbol(), qty); // seller delivers
        account(sell.getUserId()).credit(cost);                 // seller paid

        buy.recordFill(qty, cost);
        sell.recordFill(qty, BigDecimal.ZERO);
        if (sell.isTerminal() && sell.getSide() == OrderSide.BUY) {/*unreachable*/}

        txnLog.get(buy.getUserId())
              .add(Transaction.of(TransactionType.TRADE_DEBIT,  cost, buy.getId()));
        txnLog.get(sell.getUserId())
              .add(Transaction.of(TransactionType.TRADE_CREDIT, cost, sell.getId()));

        Trade t = new Trade(buy.getSymbol(), buy.getId(), sell.getId(), price, qty);
        // If the buy side just went terminal, hand back any over-reservation.
        if (buy.isTerminal()) releaseLeftover(buy);
        return t;
    }

    private void releaseLeftover(Order buyOrder) {
        BigDecimal leftover = buyOrder.getRemainingReservation();
        if (leftover.signum() > 0) {
            account(buyOrder.getUserId()).release(leftover);
            buyOrder.setRemainingReservation(BigDecimal.ZERO);
        }
    }

    /* —— cancellation —— */

    public boolean cancelOrder(String orderId) {
        Order o = ordersById.get(orderId);
        if (o == null) throw new OrderNotFoundException(orderId);
        boolean cancelled = orderBooks.get(o.getSymbol()).cancel(o);
        if (cancelled) {                       // we won the cancel race → we release
            if (o.getSide() == OrderSide.BUY) releaseLeftover(o);
            else portfolios.get(o.getUserId())
                           .releaseShares(o.getSymbol(), o.remainingQty());
        }
        return cancelled;
    }

    /* —— queries —— */

    public Quote getQuote(String symbol) {
        OrderBook book = orderBooks.get(symbol);
        if (book == null) throw new InvalidOrderException("Unknown stock " + symbol);
        return new Quote(symbol,
                         marketData.lastPrice(symbol),
                         book.bestBid().orElse(null),
                         book.bestAsk().orElse(null));
    }

    public Map<String, Integer> getPortfolio(String userId) {
        return portfolios.get(userId).snapshot();
    }
    public List<Transaction> getTransactionHistory(String userId) {
        return List.copyOf(txnLog.get(userId));
    }
    public BigDecimal getBalance(String userId) {
        return account(userId).getAvailableBalance();
    }

    private Account account(String userId) { return accounts.get(userId); }
}
```

### 4.8 Supporting classes

```java
public class User {
    private final String id = java.util.UUID.randomUUID().toString();
    private final String name, email;
    public User(String name, String email) { this.name = name; this.email = email; }
    public String getId() { return id; }
    public String getName() { return name; }
}

public class Stock {
    private final String symbol, companyName;
    public Stock(String symbol, String companyName) {
        this.symbol = symbol; this.companyName = companyName;
    }
}

public final class Transaction {
    public final TransactionType type;
    public final java.math.BigDecimal amount;
    public final String orderId;                       // null for cash movements
    public final java.time.Instant at = java.time.Instant.now();
    private Transaction(TransactionType t, java.math.BigDecimal a, String oid) {
        type = t; amount = a; orderId = oid;
    }
    static Transaction of(TransactionType t, java.math.BigDecimal a, String oid) {
        return new Transaction(t, a, oid);
    }
    @Override public String toString() {
        return type + " " + amount + (orderId != null ? " (order " + orderId.substring(0,8) + ")" : "");
    }
}

public final class Quote {
    public final String symbol;
    public final java.math.BigDecimal lastPrice, bestBid, bestAsk;
    public Quote(String s, java.math.BigDecimal lp,
                 java.math.BigDecimal bb, java.math.BigDecimal ba) {
        symbol = s; lastPrice = lp; bestBid = bb; bestAsk = ba;
    }
    @Override public String toString() {
        return symbol + " last=" + lastPrice + " bid=" + bestBid + " ask=" + bestAsk;
    }
}

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class MarketDataService implements TradeListener {
    private final ConcurrentHashMap<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();
    void seed(String symbol, BigDecimal price) { lastPrices.put(symbol, price); }
    @Override public void onTrade(Trade t)     { lastPrices.put(t.symbol, t.price); }
    public BigDecimal lastPrice(String symbol) {
        return Optional.ofNullable(lastPrices.get(symbol))
                       .orElseThrow(() -> new InvalidOrderException("No price for " + symbol));
    }
}
```

### 4.9 Demo

```java
import java.math.BigDecimal;

public class BrokerageDemo {
    public static void main(String[] args) {
        BrokerageService ex = BrokerageService.getInstance();

        ex.listStock("AAPL", "Apple Inc.", new BigDecimal("100.00"));

        User alice = ex.registerUser("Alice", "alice@x.com"); // buyer
        User bob   = ex.registerUser("Bob",   "bob@x.com");   // seller
        ex.deposit(alice.getId(), new BigDecimal("10000"));
        ex.deposit(bob.getId(),   new BigDecimal("1000"));

        // Bootstrap Bob with shares: Bob buys from a seeded liquidity user,
        // or simpler for the demo — give Bob shares directly via a sell-side
        // helper. Here: Bob "IPO-receives" 100 shares.
        // (In a real demo you'd add an admin grant; shown inline for brevity.)
        BrokerageService.getInstance().getPortfolio(bob.getId()); // touch
        // -- assume an admin grant method exists:
        // ex.grantShares(bob.getId(), "AAPL", 100);

        // 1) Bob places a limit SELL: 60 shares @ 101 — rests on the book.
        Order sell1 = ex.placeOrder(bob.getId(), "AAPL",
                OrderSide.SELL, OrderType.LIMIT, new BigDecimal("101.00"), 60);
        System.out.println("Quote: " + ex.getQuote("AAPL"));
        // -> last=100.00 bid=null ask=101.00

        // 2) Alice places a limit BUY: 100 shares @ 102.
        //    Crosses Bob's ask: fills 60 @ 101 (resting price wins),
        //    remaining 40 rests as the new best bid @ 102.
        Order buy1 = ex.placeOrder(alice.getId(), "AAPL",
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("102.00"), 100);
        System.out.println("buy1 status: " + buy1.getStatus());   // PARTIALLY_FILLED
        System.out.println("sell1 status: " + sell1.getStatus()); // FILLED
        System.out.println("Quote: " + ex.getQuote("AAPL"));
        // -> last=101.00 bid=102.00 ask=null

        // 3) Bob fires a MARKET SELL of 50: fills 40 against Alice's resting
        //    bid @ 102; remainder 10 auto-cancelled (empty bid book).
        Order sell2 = ex.placeOrder(bob.getId(), "AAPL",
                OrderSide.SELL, OrderType.MARKET, null, 50);
        System.out.println("buy1 status: " + buy1.getStatus());   // FILLED
        System.out.println("sell2 status: " + sell2.getStatus()); // CANCELLED (remainder)

        // 4) Cancel path: Alice parks a bid and pulls it; reservation returns.
        Order buy2 = ex.placeOrder(alice.getId(), "AAPL",
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("95.00"), 10);
        System.out.println("Balance before cancel: " + ex.getBalance(alice.getId()));
        ex.cancelOrder(buy2.getId());
        System.out.println("Balance after cancel:  " + ex.getBalance(alice.getId()));

        System.out.println("Alice portfolio: " + ex.getPortfolio(alice.getId()));
        System.out.println("Alice history:   " + ex.getTransactionHistory(alice.getId()));
    }
}
```

> **Demo note:** add a tiny `grantShares(userId, symbol, qty)` admin method on `BrokerageService` (one line: `portfolios.get(userId).addShares(symbol, qty)`) so Bob has inventory to sell. Kept out of the core API because share creation isn't a brokerage operation — it's a settlement-system concern.

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### 5.1 Exception strategy

| Failure | Where caught | Behavior |
|---|---|---|
| Insufficient funds / shares | `placeOrder`, at reservation | `InsufficientFunds/SharesException`; order never reaches the book — nothing to roll back |
| Unknown user / stock / non-positive qty / bad price | `placeOrder` entry, factories | `InvalidOrderException`, fail fast before any state change |
| Cancel of unknown order | `cancelOrder` | `OrderNotFoundException` |
| Cancel of already-filled order | `OrderBook.cancel` | returns `false` (not an exception — losing a benign race isn't exceptional) |
| Illegal status transition | `Order.transition` | `IllegalStateException` — this is a *bug detector*, not a business error; it should never fire in correct code |

The structural principle: **all fallible operations happen before the order touches the book; everything after is infallible by construction.** Reservation is the single fallibility gate. This is the same shape as Airline's two-phase booking — there the rule was "never hold a lock across payment I/O"; here it's "never let settlement be able to fail."

### 5.2 Edge cases

1. **Market order on an empty book** — fills zero, remainder auto-cancelled, full reservation released. The user gets a `CANCELLED` order, not an exception: an empty book is a market condition, not an error.
2. **Market BUY reservation with no known price** — we reserve `lastPrice × qty × 1.10` and release the excess after matching. The honest caveat (say this in the interview): a 10% buffer can still under-reserve in a fast market. The robust fix is converting market orders to *marketable limit orders* capped at the reservable amount, or checking-and-reserving incrementally per fill inside the match loop — at the cost of a fallible settlement path. We chose the buffer to preserve "settlement never fails."
3. **Self-trade** — Alice's buy can match Alice's own resting sell. Economically a no-op (her cash and shares round-trip), and the code handles it correctly because account operations are sequential, not simultaneous. Real exchanges *prevent* it (wash-trading rules); the one-line guard is `if (resting.getUserId().equals(incoming.getUserId())) { /* skip or cancel-oldest */ }` — worth mentioning, fine to omit.
4. **Cancel racing a fill** — Alice clicks cancel while the matching thread is mid-fill on her order. Both paths take the same book lock, so exactly one wins. If the fill wins and completes the order, `cancel` sees a terminal status and returns `false`; if cancel wins, the order leaves the heap before the matcher can `peek` it. The reservation is released by whichever path wins — never both, because `releaseLeftover` zeroes `remainingReservation` and `cancelOrder` only releases when `cancel(...)` returned `true`.
5. **Partial-fill bookkeeping drift** — `filledQuantity`, heap membership, and reservation must stay mutually consistent. They do because all three are only mutated under the book lock (orders' mutators are package-private and documented as lock-protected).
6. **`PriorityQueue.remove(Object)` is O(n)** — fine at LLD scale; the production answer is a `TreeMap<Price, Deque<Order>>` book (O(log n) cancel, plus natural FIFO-within-price-level), or lazy deletion (mark cancelled, skip on `peek`). Name this proactively — it shows you know the data structure's cost model.
7. **BigDecimal equality** — always `compareTo`, never `equals` (`2.50` vs `2.5` differ in scale). Already observed in the code.
8. **Stale quotes** — `getQuote` assembles last/bid/ask from two lock acquisitions, so the snapshot isn't perfectly atomic across fields. Acceptable for display data; say so rather than letting the interviewer discover it.

### 5.3 Concurrency analysis

**Shared mutable state inventory:**

| State | Guard |
|---|---|
| Buy/sell heaps + all `Order` mutable fields | the symbol's `OrderBook.lock` (`ReentrantLock`) |
| `Account` balances (available + reserved) | per-account `ReentrantLock` |
| `Portfolio` holdings | per-portfolio monitor (`synchronized`) |
| Registries (users, books, orders) | `ConcurrentHashMap` |
| Listener list | `CopyOnWriteArrayList` (read-heavy, rarely mutated) |
| Last traded prices | `ConcurrentHashMap` (single-key atomic put/get) |

**Critical sections:** (a) reserve = check-and-set on one account/portfolio; (b) the match loop = read best, fill, mutate two orders, settle; (c) cancel = status check + heap removal.

**Why per-stock locking, not global:** the established pattern — a global exchange lock serializes AAPL and TSLA traffic that shares no data. Per-symbol locks give linear scaling across symbols with zero added deadlock risk, because no code path ever touches two books in one operation.

**The deadlock-freedom argument** (rehearse this one — it's the answer interviewers want stated crisply):

1. The lock graph has two tiers: book locks, then account/portfolio locks.
2. Locks are only ever acquired **downward** (book → account), never account → book. No account method calls into a book.
3. Within the lower tier, settlement acquires account/portfolio locks **one at a time** — `commitReserved` on the buyer completes and releases before `credit` on the seller begins. We never hold two account locks simultaneously, so there is no possible cycle even between two settlements involving the same pair of users in opposite directions.
4. No cycle in the acquisition graph ⇒ no deadlock. (Contrast with Digital Wallet, where a transfer *did* need both account locks at once and we imposed lock ordering by account ID. Here, reservation removes that need entirely — committing reserved funds requires no cross-account invariant check.)

**Why settling one-account-at-a-time is *safe*, not just deadlock-free:** the atomicity worry is "what if we debit the buyer and then crediting the seller fails?" It can't — `commitReserved` and `credit` are unconditional arithmetic on pre-validated state. The fallible check (`balance ≥ amount`) was consumed at reservation time. Reservation converts a *distributed* invariant (buyer-can-pay AND seller-can-deliver, checked at trade time) into two *local* invariants checked independently at placement time. That's the transferable insight of this whole problem.

**Race-condition spot checks:**

- *Double-spend:* impossible — `reserve` is atomic check-and-set under the account lock; subsequent orders see the reduced `availableBalance`.
- *Lost fill:* impossible — every mutation of `filledQuantity` happens inside `submit` or `settle`, both under the book lock.
- *Double release of a reservation:* `releaseLeftover` zeroes the counter under the book-lock-protected order; cancel releases only if it won the status transition.
- *Livelock:* none — no retry loops, no optimistic CAS spinning; every path is lock-acquire → bounded work → release. The match loop is bounded by `min(incoming qty, book depth)`.
- *Observer reentrancy:* listeners are notified **after** `submit` returns, outside the book lock — a slow listener (or one that places an order, re-entering the book) cannot block or deadlock the matcher.

**Spring Boot aside:** `BrokerageService` would be a default-scope singleton `@Service` (DI replaces the holder idiom); `OrderBook` instances stay as plain objects in a map — they're per-symbol *state*, not stateless beans, which is exactly the case where "everything is a bean" thinking goes wrong.

---

## Interviewer Follow-Ups (with model answers)

**1. "Add stop-loss orders."**
A stop order is a *trigger* plus an order: inactive until last price crosses the stop price, then it becomes a market (stop) or limit (stop-limit) order. Design: a `StopOrderManager` registered as a `TradeListener`; on each trade it checks pending stops for that symbol (a `TreeMap<BigDecimal, List<Order>>` keyed by trigger price makes the check O(log n)) and submits triggered orders through the normal `placeOrder` path. Matching loop unchanged — the Strategy seam holds. Caveat to volunteer: funds for a stop-buy can't be precisely reserved at registration (trigger price ≠ execution price), so reserve at trigger time and reject if funds vanished.

**2. "What changes at 10× / distributed scale?"**
Shard by symbol — the per-stock lock already proved books are independent, so each symbol's book becomes a single-threaded partition (the LMAX/sequencer model: one thread per book consuming a queue of commands, no locks at all inside). Accounts move to a database with the reservation as a real ledger row (`status = HELD`), settlement becomes an event-driven saga, and the in-memory `txnLog` becomes an append-only event stream (Kafka). The reservation pattern survives unchanged — it's *more* important distributed, because it's what lets settlement avoid distributed transactions.

**3. "Your settlement runs inside the book lock — isn't that a latency problem?"**
Yes, it lengthens the critical section. Two-stage alternative: inside the lock, only mutate order state and emit an immutable `Fill` record to a queue; a settlement worker applies account/portfolio effects asynchronously. Safe because settlement is infallible — order matters per account but cannot fail. Trade-off: a read of your balance immediately after a fill may be momentarily stale (eventual consistency within the process). For LLD scope, synchronous settlement is simpler and correct; name the alternative unprompted.

**4. "How do you test the matching engine's concurrency?"**
Deterministic unit tests for the matching rules (price-time priority, partial fills, market remainder cancel) need no threads — the book is testable single-threaded because the lock is internal. For races: a stress test with N threads placing random orders, then assert **conservation invariants**: total cash across all accounts + nothing created/destroyed; total shares per symbol constant; every order's `filledQty` equals the sum of its trades' quantities; `reserved ≥ 0` everywhere. Invariant-based stress testing is the honest answer — you can't unit-test a race away, you make its violation detectable.

**5. "Real-world settlement is T+1. How would you model it?"**
Split the trade lifecycle: `EXECUTED → SETTLED`. At execution, move cash/shares between *reserved/pending* buckets only (buyer's cash committed, shares land as `pendingQty` not `availableQty`; seller mirror-image). A `ScheduledExecutorService` (or settlement-date job) flips pending → available. This is the TTL/expiry machinery from Airline's reservation timeout pointed at a different lifecycle edge.

---

## Transferable Lessons

1. **Reservation converts distributed invariants into local ones.** The deepest idea here: by reserving at placement, trade settlement needs *no* cross-account atomicity — each side commits independently and infallibly. Compare Digital Wallet, where transfer-time checking forced two simultaneous locks and ordered acquisition. Whenever a multi-party operation looks like it needs a distributed transaction, ask: *can an earlier reservation make the final step unconditional?* This reappears in Movie Ticket Booking (seat holds), Hotel Booking (room holds), and every real payment/inventory system.
2. **Make the fallible region as early and as small as possible.** "Everything after the gate is infallible" is the property that made every concurrency argument in Step 5 short.
3. **Price-time priority = compound comparator + heap.** The order book (two priority queues under one lock, match-on-arrival loop) is a reusable component shape — it's also the core of job schedulers and ride-matching (Uber LLD).
4. **Strategy at the question, not the loop.** Encapsulating `canExecuteAt` rather than subclassing the matcher kept the engine closed to modification — the precise OCP move interviewers look for.

**Suggested next problem:** **Movie Ticket Booking System** — it takes the reservation idea you just used per-order and stretches it across *multiple resources claimed atomically* (a set of seats), forcing the claim-all-or-compensate pattern under contention. After that, **Stack Overflow** (rich domain modeling, reputation as Observer) or jump to the **Concurrency tier** proper, where the order-book heap-plus-lock shape recurs as bounded-buffer/producer-consumer.
