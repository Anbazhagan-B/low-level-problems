# Vending Machine — Low Level Design (Java)

**Scope locked in Step 1:** select-first flow, per-denomination cash tracking, single physical machine whose object must be thread-safe, cash only, money as `int` smallest-units, cancel = full refund of the exact coins inserted.

> Note: we skipped the Socratic entity-attempt step at your request. After reading, try to reproduce the entity list from memory — that's the part interviews actually test.

---

## Step 1 Functional Requirements

Product catalog — multiple products, each with a price and a quantity; products live in addressable slots/codes (e.g., "A1").
Payment — accept coins and notes of fixed denominations; accumulate inserted money for the current transaction.
Dispense + change — on successful purchase, dispense exactly one unit of the selected product and return correct change.
Inventory tracking — decrement quantity on dispense; report out-of-stock.
Cancel/refund — (implied by #7) user can cancel mid-transaction and get their money back.
Admin operations — restock products, collect accumulated cash.
Failure handling — insufficient funds, out-of-stock, and the one candidates forget: machine can't make exact change.

Non-Functional Requirements

Concurrency / consistency — requirement #5. Worth pausing here: a physical vending machine has one keypad and one coin slot, so "concurrent transactions" really means the machine object must be safe if multiple threads (or a kiosk network / simulation harness) drive it. We must not double-dispense the last item or corrupt the cash balance.
Extensibility — adding new denominations, new payment methods (card/UPI later), new products without touching core logic. This is where Open/Closed gets probed.
Atomicity of a transaction — money accepted ↔ product dispensed ↔ change returned must succeed or roll back as a unit. No "took your money, gave nothing" states.

## Step 2 — Entities & Relationships

| Entity                              | Kind              | Responsibility                                                                                                                          |
| ----------------------------------- | ----------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `Denomination`                      | enum              | The fixed set of accepted coins/notes, each with an integer value                                                                       |
| `Product`                           | class (immutable) | Code, name, price. A value-like domain object                                                                                           |
| `ProductInventory`                  | class             | Which products exist + how many of each remain                                                                                          |
| `CashInventory`                     | class             | How many of **each denomination** the machine holds; makes change (greedy)                                                              |
| `VendingMachineState`               | interface         | The contract every state implements: `selectProduct`, `insertMoney`, `cancel`                                                           |
| `IdleState`, `ProductSelectedState` | classes           | Concrete behaviors per state                                                                                                            |
| `VendingMachine`                    | class             | The context/facade: owns inventories, current state, current-transaction data, and the lock                                             |
| Exceptions                          | hierarchy         | `VendingMachineException` → `InvalidSelectionException`, `SoldOutException`, `InsufficientChangeException`, `InvalidOperationException` |

### Relationships (and why each type)

- `VendingMachine` **composes** `ProductInventory` and `CashInventory` — _composition_, because the inventories have no meaning or lifecycle outside the machine; they are created by it and die with it.
- `VendingMachine` **composes** its state objects — same lifecycle argument. The states are stateless flyweights created once and reused.
- `ProductInventory` **aggregates** `Product` — _aggregation_, because a `Product` is a catalog concept that can conceptually exist independently of any one machine's inventory (the same `Product` could be shared across machines).
- States hold a back-reference to `VendingMachine` — _association_ (they use it, don't own it).
- `VendingMachine` **depends on** `Denomination` and the exceptions — _dependency_ (parameter/throw types only).

> **Foundations refresher — aggregation vs composition.** Both are "has-a", but composition means the part's lifecycle is bound to the whole (Machine → its CashInventory: destroy the machine and its cash drawer is gone). Aggregation means the part can outlive the whole or be shared (Inventory → Product: the product definition isn't owned by one machine). In UML, composition is a filled diamond, aggregation a hollow one. Interviewers rarely ask the symbols, but they do ask "who owns this object's lifecycle?" — that's the real question behind it.

### What's deliberately NOT modeled

- No `Coin` class with physical attributes (weight, diameter) — the enum value is all the domain needs. Over-modeling is a real interview failure mode.
- No `User`/`Transaction` persistence — the current transaction is transient fields on the machine, which mirrors reality: a vending machine has no memory of past customers.
- No `Slot` class separate from product code — a `Map<String, …>` keyed by code is enough at this scale; mention you'd introduce `Slot` if slots had their own behavior (capacity, motor status).

---

## Step 3 — Class Design

### Text UML

```
                        <<interface>>
                     VendingMachineState
                 + selectProduct(code): void
                 + insertMoney(d): void
                 + cancel(): void
                          ▲
              ┌───────────┴────────────┐
         IdleState              ProductSelectedState
         - machine ─────────────── - machine          (association)
              └───────────┬────────────┘
                          │ back-reference
                          ▼
   ┌───────────────────────────────────────────────┐
   │                VendingMachine                  │
   │ - lock: ReentrantLock                          │
   │ - state: VendingMachineState                   │
   │ - productInventory: ProductInventory   ◆──────┼──> Product (aggregation, 0..*)
   │ - cashInventory: CashInventory          ◆      │
   │ - selectedProduct: Product                     │
   │ - insertedCoins: List<Denomination>            │
   │ - insertedAmount: int                          │
   │ + selectProduct(code)                          │
   │ + insertMoney(d)                               │
   │ + cancel()                                     │
   │ + restock(p, qty)        (admin)               │
   │ + loadChange(d, count)   (admin)               │
   │ + collectCash(): Map     (admin)               │
   └───────────────────────────────────────────────┘

   CashInventory                         ProductInventory
   - counts: NavigableMap<Denom,Int>     - products: Map<String,Product>
   + add / addAll / removeAll            - stock: Map<String,Integer>
   + makeChange(amount): Optional<Map>   + addProduct / getProduct
   + collectAll(): Map                   + isAvailable / reduceStock
```

State transitions (select-first):

```
            selectProduct(valid, in stock)
   IDLE ───────────────────────────────────► PRODUCT_SELECTED
    ▲                                              │
    │   cancel  → refund exact inserted coins      │ insertMoney(d)
    ├──────────────────────────────────────────────┤   (loop while inserted < price)
    │   purchase complete → dispense + change      │
    ├──────────────────────────────────────────────┤
    │   can't make change → refund + abort         │
    └──────────────────────────────────────────────┘
```

> **Foundations refresher — the State pattern.** When an object's valid behavior for the _same_ method call differs by which mode it's in, the naive solution is `if (state == IDLE) … else if (state == SELECTED) …` inside every method — and every new state means editing every method. The State pattern inverts this: each mode becomes a class implementing a common interface, and the context delegates to whichever state object is current. Adding a state means adding a class, not editing conditionals (Open/Closed). The litmus test for using it: "does `insertMoney` mean something different depending on what happened before?" Here, yes — in IDLE it's an error, in SELECTED it accumulates credit.

### SOLID mapping

- **S — Single Responsibility:** change-making lives in `CashInventory`, stock-keeping in `ProductInventory`, transition rules in the state classes. `VendingMachine` only orchestrates. If the greedy algorithm changes, exactly one class changes.
- **O — Open/Closed:** new state (e.g., `MaintenanceState`) or new denomination = add code, don't modify the dispatch logic. The state interface is the extension point.
- **L — Liskov:** every state is fully substitutable behind `VendingMachineState`; no state throws "unsupported operation" surprises — invalid ops in a state are _domain_ errors with meaningful messages, which is the contract.
- **I — Interface Segregation:** customer API (`selectProduct/insertMoney/cancel`) vs admin API (`restock/loadChange/collectCash`) are distinct method groups; in a larger system you'd split them into two interfaces so a customer-facing kiosk can't even see `collectCash`.
- **D — Dependency Inversion:** `VendingMachine` depends on the `VendingMachineState` abstraction. (If we later add payment methods, a `PaymentProcessor` interface is the same move.)

### Design patterns used — and why they fit

1. **State** — core of the problem; justified above. This is THE pattern interviewers expect here.
2. **Facade** — `VendingMachine` exposes three customer methods and hides inventories, states, locking. The caller never touches a state object.
3. **Strategy (latent)** — greedy change-making is hardcoded in `CashInventory.makeChange`. If asked "what if denominations were {1, 3, 4} where greedy fails?", the answer is: extract a `ChangeStrategy` interface (greedy vs DP-based). Naming this _without_ prematurely building it shows judgment.
4. **Singleton (discussed, not enforced)** — a physical machine is one object, but hard Singleton (`private` constructor + static instance) hurts testability. In Spring you'd just make it a singleton-scoped `@Component` and let the container enforce uniqueness — same guarantee, injectable, mockable. Say this in interviews; it lands well.

### The two decisions an interviewer will probe

1. **"What if the machine can't make change?"** — Our answer: inserted coins are banked _tentatively_, change is attempted from the full cash inventory (including the just-inserted coins — they're legal change!), and on failure we roll back and refund the exact coins inserted. Atomic outcome, customer never loses money.
2. **"How is this thread-safe, and why one big lock?"** — One `ReentrantLock` guards every public entry point. Coarse-grained is _correct here by domain_: the machine is a serial state machine (one keypad), so contention is inherently low and fine-grained locking would add deadlock risk for zero throughput gain. Full argument in Step 5.

---

## Step 4 — Implementation (package `vendingmachine`)

### Denomination.java

```java
public enum Denomination {
    COIN_1(1), COIN_2(2), COIN_5(5), COIN_10(10), NOTE_20(20), NOTE_50(50);

    private final int value;   // smallest currency unit; never double for money

    Denomination(int value) { this.value = value; }

    public int getValue() { return value; }
}
```

### Product.java

```java
import java.util.Objects;

public final class Product {                 // immutable: safe to share across threads
    private final String code;               // slot code, e.g. "A1"
    private final String name;
    private final int price;                 // smallest unit

    public Product(String code, String name, int price) {
        if (price <= 0) throw new IllegalArgumentException("price must be positive");
        this.code = Objects.requireNonNull(code);
        this.name = Objects.requireNonNull(name);
        this.price = price;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public int getPrice()   { return price; }
}
```

### Exceptions.java

```java
public class VendingMachineException extends RuntimeException {
    public VendingMachineException(String message) { super(message); }
}

public class InvalidSelectionException extends VendingMachineException {
    public InvalidSelectionException(String code) { super("No product at code: " + code); }
}

public class SoldOutException extends VendingMachineException {
    public SoldOutException(String code) { super("Product sold out: " + code); }
}

public class InsufficientChangeException extends VendingMachineException {
    public InsufficientChangeException(int amount) {
        super("Cannot dispense exact change of " + amount + "; transaction refunded");
    }
}

public class InvalidOperationException extends VendingMachineException {
    public InvalidOperationException(String message) { super(message); }
}
```

_Unchecked, not checked: these are domain outcomes the caller can't "fix" by catching at every call site; a single boundary handler (the UI loop / controller advice in Spring) maps them to user messages._

### ProductInventory.java

```java
import java.util.HashMap;
import java.util.Map;

/** Not internally synchronized — all access happens under the machine's lock. */
public class ProductInventory {
    private final Map<String, Product> products = new HashMap<>();
    private final Map<String, Integer> stock    = new HashMap<>();

    public void addProduct(Product product, int quantity) {
        if (quantity < 0) throw new IllegalArgumentException("quantity must be >= 0");
        products.put(product.getCode(), product);
        stock.merge(product.getCode(), quantity, Integer::sum);
    }

    public Product getProduct(String code) {
        Product p = products.get(code);
        if (p == null) throw new InvalidSelectionException(code);
        return p;
    }

    public boolean isAvailable(String code) {
        return stock.getOrDefault(code, 0) > 0;
    }

    public void reduceStock(String code) {
        int current = stock.getOrDefault(code, 0);
        if (current <= 0) throw new SoldOutException(code);   // defense in depth
        stock.put(code, current - 1);
    }
}
```

### CashInventory.java

```java
import java.util.*;

/**
 * Tracks how many of each denomination the machine physically holds.
 * Iteration order is largest-value-first so greedy change-making is a plain loop.
 * Not internally synchronized — guarded by the machine's lock.
 */
public class CashInventory {
    private final NavigableMap<Denomination, Integer> counts =
            new TreeMap<>(Comparator.comparingInt(Denomination::getValue).reversed());

    public void add(Denomination d, int n) { counts.merge(d, n, Integer::sum); }

    public void addAll(Collection<Denomination> coins) { coins.forEach(c -> add(c, 1)); }

    /** Rollback support: physically hand coins back out of the drawer. */
    public void removeAll(Collection<Denomination> coins) {
        for (Denomination c : coins) {
            counts.merge(c, -1, Integer::sum);
            if (counts.get(c) < 0) throw new IllegalStateException("cash drawer corrupted");
        }
    }

    /**
     * Greedy: largest denomination first. Correct for canonical currency systems
     * like {1,2,5,10,20,50}; for arbitrary sets you'd swap in a DP strategy.
     * Commits (decrements counts) ONLY if the full amount is reachable —
     * otherwise the inventory is untouched and empty is returned.
     */
    public Optional<Map<Denomination, Integer>> makeChange(int amount) {
        if (amount == 0) return Optional.of(Map.of());
        Map<Denomination, Integer> change = new LinkedHashMap<>();
        int remaining = amount;
        for (Map.Entry<Denomination, Integer> e : counts.entrySet()) {
            if (remaining == 0) break;
            int take = Math.min(remaining / e.getKey().getValue(), e.getValue());
            if (take > 0) {
                change.put(e.getKey(), take);
                remaining -= take * e.getKey().getValue();
            }
        }
        if (remaining != 0) return Optional.empty();           // cannot make change
        change.forEach((d, n) -> counts.merge(d, -n, Integer::sum));  // commit
        return Optional.of(change);
    }

    /** Admin: empty the drawer. Zero-count entries are omitted from the report. */
    public Map<Denomination, Integer> collectAll() {
        Map<Denomination, Integer> all = new LinkedHashMap<>();
        counts.forEach((d, n) -> { if (n > 0) all.put(d, n); });
        counts.clear();
        return all;
    }

    public int totalValue() {
        return counts.entrySet().stream()
                .mapToInt(e -> e.getKey().getValue() * e.getValue()).sum();
    }
}
```

### VendingMachineState.java + the two states

```java
public interface VendingMachineState {
    void selectProduct(String code);
    void insertMoney(Denomination denomination);
    void cancel();
}
```

```java
public class IdleState implements VendingMachineState {
    private final VendingMachine machine;

    public IdleState(VendingMachine machine) { this.machine = machine; }

    @Override
    public void selectProduct(String code) {
        Product product = machine.getProductInventory().getProduct(code); // throws if unknown
        if (!machine.getProductInventory().isAvailable(code)) {
            throw new SoldOutException(code);
        }
        machine.beginTransaction(product);
        System.out.printf("Selected %s (%s) — price %d. Insert money.%n",
                product.getName(), code, product.getPrice());
    }

    @Override
    public void insertMoney(Denomination d) {
        // Physical machine would eject the coin; we model it as a rejected operation.
        throw new InvalidOperationException("Select a product before inserting money");
    }

    @Override
    public void cancel() {
        System.out.println("Nothing to cancel.");
    }
}
```

```java
public class ProductSelectedState implements VendingMachineState {
    private final VendingMachine machine;

    public ProductSelectedState(VendingMachine machine) { this.machine = machine; }

    @Override
    public void selectProduct(String code) {
        // Design choice: no mid-transaction switching. Cancel first.
        throw new InvalidOperationException(
                "Transaction in progress for " + machine.getSelectedProduct().getCode()
                + "; cancel to choose a different product");
    }

    @Override
    public void insertMoney(Denomination d) {
        machine.recordInsertion(d);
        int price = machine.getSelectedProduct().getPrice();
        System.out.printf("Inserted %d. Credit: %d / %d%n",
                d.getValue(), machine.getInsertedAmount(), price);
        if (machine.getInsertedAmount() >= price) {
            machine.completePurchase();      // dispense + change happen atomically here
        }
    }

    @Override
    public void cancel() {
        machine.refundAndReset();
    }
}
```

### VendingMachine.java — the context/facade

```java
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class VendingMachine {
    private final ReentrantLock lock = new ReentrantLock();

    private final ProductInventory productInventory = new ProductInventory();
    private final CashInventory cashInventory = new CashInventory();

    // States are stateless w.r.t. the transaction, so one instance each is enough.
    private final VendingMachineState idleState = new IdleState(this);
    private final VendingMachineState selectedState = new ProductSelectedState(this);
    private VendingMachineState state = idleState;

    // ---- current-transaction context (the shared mutable state we protect) ----
    private Product selectedProduct;
    private final List<Denomination> insertedCoins = new ArrayList<>();
    private int insertedAmount;

    // ===================== customer API — every entry takes the lock =========
    public void selectProduct(String code) { withLock(() -> state.selectProduct(code)); }
    public void insertMoney(Denomination d) { withLock(() -> state.insertMoney(d)); }
    public void cancel()                    { withLock(() -> state.cancel()); }

    // ===================== admin API ==========================================
    public void restock(Product product, int quantity) {
        withLock(() -> productInventory.addProduct(product, quantity));
    }
    public void loadChange(Denomination d, int count) {
        withLock(() -> cashInventory.add(d, count));
    }
    public Map<Denomination, Integer> collectCash() {
        lock.lock();
        try {
            if (state != idleState)
                throw new InvalidOperationException("Cannot collect cash mid-transaction");
            return cashInventory.collectAll();
        } finally { lock.unlock(); }
    }

    // ===================== package-private hooks for states ==================
    void beginTransaction(Product product) {
        this.selectedProduct = product;
        this.state = selectedState;
    }

    void recordInsertion(Denomination d) {
        insertedCoins.add(d);
        insertedAmount += d.getValue();
    }

    /**
     * The transactional core. Either: (product dispensed AND correct change returned
     * AND inserted cash banked) — or nothing changes and the customer is refunded.
     */
    void completePurchase() {
        int changeDue = insertedAmount - selectedProduct.getPrice();

        cashInventory.addAll(insertedCoins);              // 1) tentatively bank coins
        Optional<Map<Denomination, Integer>> change =     // 2) try to make change
                cashInventory.makeChange(changeDue);      //    (inserted coins count!)

        if (change.isEmpty()) {                           // 3a) rollback path
            cashInventory.removeAll(insertedCoins);
            List<Denomination> refund = List.copyOf(insertedCoins);
            resetTransaction();
            System.out.println("Refunding inserted coins: " + describe(refund));
            throw new InsufficientChangeException(changeDue);
        }

        productInventory.reduceStock(selectedProduct.getCode());  // 3b) commit path
        System.out.printf("Dispensing %s. Change %d returned as %s%n",
                selectedProduct.getName(), changeDue, change.get());
        resetTransaction();
    }

    void refundAndReset() {
        // Cancel refunds the EXACT coins inserted — no change-making needed,
        // because they never entered the cash drawer.
        System.out.println("Cancelled. Refunding: " + describe(insertedCoins));
        resetTransaction();
    }

    private void resetTransaction() {
        selectedProduct = null;
        insertedCoins.clear();
        insertedAmount = 0;
        state = idleState;
    }

    private void withLock(Runnable action) {
        lock.lock();
        try { action.run(); } finally { lock.unlock(); }
    }

    private static String describe(List<Denomination> coins) {
        return coins.isEmpty() ? "(nothing)" : coins.toString();
    }

    // accessors used by states
    ProductInventory getProductInventory() { return productInventory; }
    Product getSelectedProduct()           { return selectedProduct; }
    int getInsertedAmount()                { return insertedAmount; }
}
```

### Demo.java

```java
import java.util.Map;

public class Demo {
    public static void main(String[] args) {
        VendingMachine vm = new VendingMachine();
        vm.restock(new Product("A1", "Cola", 25), 2);
        vm.restock(new Product("A2", "Chips", 35), 1);
        vm.loadChange(Denomination.COIN_5, 1);     // deliberately scarce change

        // Happy path: 25 paid as 20+10, change 5 — uses the loaded COIN_5
        vm.selectProduct("A1");
        vm.insertMoney(Denomination.NOTE_20);
        vm.insertMoney(Denomination.COIN_10);

        // Insufficient change: 25 paid as 50, change 25 — only a 20 and a 10 in
        // the drawer now (just banked), can't compose 25 → rollback + refund
        vm.selectProduct("A1");
        try {
            vm.insertMoney(Denomination.NOTE_50);
        } catch (InsufficientChangeException e) {
            System.out.println("Machine says: " + e.getMessage());
        }

        // Cancel path
        vm.selectProduct("A2");
        vm.insertMoney(Denomination.COIN_10);
        vm.cancel();                                // refunds exactly [COIN_10]

        // Admin collects
        Map<Denomination, Integer> cash = vm.collectCash();
        System.out.println("Collected: " + cash);
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Edge cases handled

| Scenario                                  | Behavior                                                                                                                                   |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Unknown product code                      | `InvalidSelectionException` in IDLE; state unchanged                                                                                       |
| Out of stock at selection                 | `SoldOutException`; machine stays IDLE                                                                                                     |
| Insert money while IDLE                   | `InvalidOperationException` (physical analogue: coin ejected)                                                                              |
| Exact payment                             | `changeDue == 0` short-circuits to `Optional.of(Map.of())` — no drawer math                                                                |
| Overpay, change impossible                | Tentative bank → rollback → refund exact coins → `InsufficientChangeException`. Customer never loses money; drawer never goes inconsistent |
| Cancel mid-payment                        | Refund the literal coins inserted (they were never banked, so no change-making)                                                            |
| Select during a transaction               | Rejected; cancel first. (Allowing pre-payment switching is a 5-line extension — say so if asked)                                           |
| Admin collect mid-transaction             | Rejected — collecting while coins are tentatively banked could corrupt accounting                                                          |
| Invalid construction (negative price/qty) | `IllegalArgumentException` at the source — fail fast                                                                                       |

### Why the rollback ordering matters

`completePurchase` banks coins **before** attempting change because the customer's own coins are legal change material (you pay 50 for a 45 item; your 50 plus a drawer holding one 5 must succeed). If we made change _first_ from the pre-existing drawer only, we'd refuse transactions a real machine could complete. The price of this ordering is needing a rollback (`removeAll`) — which is why `makeChange` is written to be atomic itself: it mutates counts only when the full amount is reachable.

### Concurrency analysis (the part to rehearse out loud)

- **Shared mutable state:** `state`, `selectedProduct`, `insertedCoins`, `insertedAmount`, both inventories' maps.
- **Critical sections:** every public method, in full. The invariant isn't per-field — it's _cross-field_ ("state == SELECTED ⟺ selectedProduct != null", "drawer total == banked money"), so each operation must be atomic as a whole.
- **Primitive chosen:** one `ReentrantLock` at the facade boundary. `synchronized` methods would be equally correct here; `ReentrantLock` is chosen for the interview talking points — `tryLock(timeout)` (a second kiosk thread can give up gracefully instead of blocking forever) and fairness mode if needed.
- **Why not finer-grained** (e.g., `ConcurrentHashMap` for stock, `AtomicInteger` for credit)? Because the operations span multiple structures: dispensing reads credit, mutates the cash drawer, _and_ decrements stock — atomically. Fine-grained primitives make each piece atomic but not the _composition_; you'd reintroduce check-then-act races (two threads both see `stock == 1`, both dispense). Lock-free helps when contention is high; a one-keypad machine has near-zero contention, so coarse locking is the simpler _and_ faster-to-verify answer.
- **Deadlock argument:** a single lock means no lock-ordering cycles — deadlock is impossible by construction. No callbacks or foreign code run while holding the lock (the `System.out` calls are benign), so no lock is ever held across an unbounded wait. Livelock/starvation: non-issue at this contention level; flip the lock to fair mode if it ever mattered.
- **Race-condition argument:** every read and write of transaction state happens inside the lock, so the lock's happens-before edges make all mutations visible to the next thread — no `volatile` needed on `state`.

**Spring aside:** in a Spring Boot service this object would be a singleton `@Component`; singleton beans are shared across request threads, so this exact locking discipline is what makes a stateful bean safe. (The more common Spring answer is "don't hold mutable state in beans at all" — push it to a DB row with optimistic locking — worth mentioning as the 10x-scale evolution.)

---

## Interviewer Follow-ups (with model answers)

1. **"Add card/UPI payments."** Introduce a `PaymentMethod`/`PaymentProcessor` abstraction; cash becomes one implementation (the only one needing `CashInventory` and change). `ProductSelectedState` delegates to the processor. That's Strategy + Dependency Inversion; no state-machine surgery needed — though card likely adds a `PAYMENT_PENDING` state for the async authorization, which the State pattern absorbs as one new class.
2. **"Denominations are {1, 3, 4} and greedy breaks (change 6 → greedy gives 4+1+1 using 3 coins; optimal is 3+3)."** Greedy is only optimal for canonical coin systems. Extract `ChangeStrategy` and implement a bounded-count DP (classic coin-change with limited supply). The `Optional<Map<…>>` contract of `makeChange` doesn't change — that's the payoff of having isolated it.
3. **"Make it a fleet of 1,000 networked machines."** Each machine keeps its local state machine (it's physical reality), but inventory/cash become events published to a backend (Observer pattern locally → message queue in practice). Restock decisions move server-side; consistency per machine is still the local lock — distribution doesn't change the LLD, it wraps it.
4. **"Two threads call `insertMoney` simultaneously — walk me through what happens."** Both hit `withLock`; one acquires, runs the full state-dispatched operation, releases; the second then sees the _updated_ state (possibly IDLE again if the first completed a purchase) and behaves accordingly — e.g., gets `InvalidOperationException`. No interleaving inside an operation is possible.
5. **"Where would you add an Observer?"** Stock-level and cash-level listeners: `reduceStock` fires `onLowStock(code)` to notify a restocking service; `collectCash`-worthy thresholds similarly. Observer fits because multiple independent reactions (telemetry, restock, alerting) should not be hardcoded into inventory logic.

## Transferable Lesson

**State pattern = "behavior of the same call depends on history."** You will reuse it nearly verbatim in: Elevator (Idle/Moving/DoorOpen), ATM (CardInserted/PinEntered/...), Order lifecycle, Traffic Signal, Stack Overflow question lifecycle. And the _tentative-commit-with-rollback_ shape of `completePurchase` is the in-memory miniature of database transactions — recognize it whenever an operation must mutate several structures atomically.

## Next Problem

**Parking Lot** (the other canonical Medium) — it shifts the emphasis from State to **Strategy (pricing, spot-allocation) + Factory (vehicle/spot types)**, and its concurrency story (many entry gates competing for spots) is genuinely multi-threaded, unlike the vending machine's serial keypad. Good contrast.
