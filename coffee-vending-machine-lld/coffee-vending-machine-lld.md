# Coffee Vending Machine — Low Level Design

**Decisions locked in:** out-of-stock coffees are *shown as unavailable* (not hidden), and payment is a *single inserted amount* (no incremental coin insertion, so no State pattern needed — that's the key simplification vs. the full Vending Machine problem).

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|---|
| F1 | Machine offers a fixed catalog of coffee types (Espresso, Cappuccino, Latte), each with a **price** and a **recipe** (ingredient → quantity). |
| F2 | Display a **menu** of all coffees with prices; coffees that cannot currently be made are shown but marked **UNAVAILABLE**. |
| F3 | User selects exactly one coffee per transaction. |
| F4 | User pays a **single inserted amount**; machine validates it covers the price and returns **change** for overpayment. |
| F5 | On success: deduct recipe ingredients from inventory atomically and dispense the coffee. |
| F6 | Track ingredient inventory; **notify** registered listeners when any ingredient drops below a low-stock threshold. |
| F7 | An operator can **refill** ingredients. |

### Non-Functional Requirements

| # | Requirement |
|---|---|
| N1 | **Thread safety**: concurrent purchases must never drive inventory negative. The check-ingredients + deduct-ingredients pair must be one atomic operation. |
| N2 | **Extensibility (OCP)**: adding a new coffee or ingredient must not modify payment/dispense logic. |
| N3 | **Transactional consistency**: never take money without dispensing; any failure after payment validation must result in a full refund. |
| N4 | **Scope**: one physical machine → in-memory state is acceptable; no persistence or distribution. |

### Assumptions (stated explicitly, interview-style)

- Cash-like payment: one amount in, change out. Machine can always make change (no coin-float modeling — noted as a follow-up).
- Fixed recipes; no customization (Decorator is the extension path if asked).
- One coffee per transaction.
- Low-stock notification goes to an abstract observer (console now; SMS/dashboard later).

---

## Step 2 — Entities & Relationships

| Entity | Kind | Responsibility |
|---|---|---|
| `Ingredient` | enum | Closed set of raw materials: `COFFEE_BEANS, WATER, MILK, SUGAR, COCOA_POWDER`. |
| `CoffeeType` | enum | Closed set of menu keys: `ESPRESSO, CAPPUCCINO, LATTE`. |
| `Recipe` | class (immutable) | `Map<Ingredient, Integer>` — what one cup consumes. |
| `Coffee` | class (immutable) | A menu item: type + price + recipe. |
| `Inventory` | class | Owns ingredient stock; the **only** place stock is read/written; enforces atomic check-and-deduct; fires low-stock events. |
| `InventoryObserver` | interface | Callback contract for low-stock events. |
| `ConsoleNotifier` | class | Concrete observer (prints alert). |
| `MenuItem` | class (DTO) | Snapshot for display: name, price, available flag. |
| `CoffeeVendingMachine` | class (Singleton) | Facade: menu, selection, payment validation, orchestration of dispense. |
| `VendingException` + subclasses | exceptions | `InvalidSelectionException`, `InsufficientPaymentException`, `OutOfIngredientsException`. |

### Relationships (with type and why)

| Relationship | Type | Why |
|---|---|---|
| `Coffee` → `Recipe` | **Composition** | A recipe has no identity or lifecycle outside its coffee; it is created with and dies with the `Coffee`. |
| `Recipe` → `Ingredient` | **Dependency** (on enum values) | Recipe references ingredient constants; it doesn't own them. |
| `CoffeeVendingMachine` → `Inventory` | **Composition** | The inventory exists only inside this machine; machine creates and owns it. |
| `CoffeeVendingMachine` → `Coffee` (menu) | **Aggregation** | Coffees are value-like catalog entries; conceptually they could be shared/configured externally (e.g., injected as config), so the machine *has* them but doesn't intrinsically *own* their definition. |
| `Inventory` → `InventoryObserver` | **Association** (Observer pattern) | Inventory holds references to observers it did not create and does not own. |
| `CoffeeVendingMachine` → `MenuItem` | **Dependency** | Created transiently as a return value of `getMenu()`. |

**Common over-modeling traps avoided:** no `User` class (the user is external — only their *inputs* matter), no `Payment` class hierarchy yet (a single `int amountInserted` suffices; introduce `PaymentStrategy` only when card/UPI is actually asked for), no per-coffee subclasses (`Espresso extends Coffee` adds nothing — coffees differ by *data*, not *behavior*; data-driven beats inheritance here).

---

## Step 3 — UML Class Design

```
                         «enum»                      «enum»
                       CoffeeType                  Ingredient
                  ESPRESSO, CAPPUCCINO,       COFFEE_BEANS, WATER,
                        LATTE                 MILK, SUGAR, COCOA_POWDER

 ┌────────────────────────────┐        ┌─────────────────────────────────┐
 │          Coffee            │◆──────▶│            Recipe               │
 ├────────────────────────────┤  1   1 ├─────────────────────────────────┤
 │ - type: CoffeeType         │        │ - ingredients:                  │
 │ - priceCents: int          │        │     Map<Ingredient, Integer>    │
 │ - recipe: Recipe           │        ├─────────────────────────────────┤
 ├────────────────────────────┤        │ + getIngredients(): Map (ro)    │
 │ + getType(), + getPrice()  │        └─────────────────────────────────┘
 │ + getRecipe()              │
 └────────────────────────────┘
              ▲ menu: Map<CoffeeType, Coffee>  (aggregation ◇)
              │ 1..*
 ┌────────────┴─────────────────────────────┐      ┌──────────────────────────┐
 │       CoffeeVendingMachine  «Singleton»  │◆────▶│        Inventory         │
 ├──────────────────────────────────────────┤ 1  1 ├──────────────────────────┤
 │ - INSTANCE: CoffeeVendingMachine {static}│      │ - stock: Map<Ingredient, │
 │ - menu: Map<CoffeeType, Coffee>          │      │          Integer>        │
 │ - inventory: Inventory                   │      │ - lowStockThreshold: int │
 ├──────────────────────────────────────────┤      │ - observers: List<…>     │
 │ + getInstance(): CoffeeVendingMachine    │      ├──────────────────────────┤
 │ + getMenu(): List<MenuItem>              │      │ + tryConsume(r): boolean │
 │ + buyCoffee(type, amountCents):          │      │ + canMake(r): boolean    │
 │       DispenseResult                     │      │ + refill(ing, qty)       │
 │ + refill(ing, qty)                       │      │ + addObserver(o)         │
 └──────────────────────────────────────────┘      └─────────────┬────────────┘
                                                                 │ notifies 0..*
                                                                 ▼
                                                   ┌──────────────────────────┐
                                                   │ «interface»              │
                                                   │ InventoryObserver        │
                                                   ├──────────────────────────┤
                                                   │ + onLowStock(ing, qty)   │
                                                   └────────────△─────────────┘
                                                                │ implements
                                                   ┌────────────┴─────────────┐
                                                   │     ConsoleNotifier      │
                                                   └──────────────────────────┘

 DTO: MenuItem { name, priceCents, available }
 Exceptions: VendingException ◁── InvalidSelectionException
                              ◁── InsufficientPaymentException
                              ◁── OutOfIngredientsException
```

### Design patterns and exactly why they fit

| Pattern | Where | Why it fits *here* |
|---|---|---|
| **Singleton** | `CoffeeVendingMachine` | There is exactly one physical machine, and it guards shared state (inventory). Two instances would mean two inventories diverging from one physical reality. Eager static-final initialization gives thread-safe construction for free (JVM class-loading guarantee). *Spring note:* in Spring Boot you'd drop the static Singleton and declare it a `@Component` — singleton bean scope gives the same one-instance guarantee with testability (you can inject a mock `Inventory`). |
| **Observer** | `Inventory` → `InventoryObserver` | Low-stock alerting must be decoupled from inventory bookkeeping (SRP). Inventory shouldn't know whether alerts go to console, SMS, or a dashboard. New notification channels are added by implementing the interface — zero changes to `Inventory` (OCP). |
| **Facade** | `CoffeeVendingMachine.buyCoffee()` | One method hides the orchestration: validate selection → validate payment → atomic consume → compute change. Callers (UI/driver) never touch `Inventory` directly. |
| **Factory (lightweight)** | static `Coffee` catalog construction in `MenuConfig` | Centralizes recipe knowledge; adding Mocha = adding one catalog entry. Not a full Abstract Factory — say so in the interview; over-patterning is a smell. |
| *(deliberately absent)* **State** | — | Because payment is a single amount, the machine has no meaningful states (IDLE → HAS_MONEY → DISPENSING). Mention this trade-off: incremental coin insertion is exactly what would justify State. |
| *(hook)* **Strategy** | future `PaymentStrategy` | If card/UPI is added, `buyCoffee` takes a strategy instead of an int. Don't build it speculatively (YAGNI). |

### SOLID mapping

- **S**RP — `Inventory` does stock math; `CoffeeVendingMachine` orchestrates; notifiers notify. No class has two reasons to change.
- **O**CP — new coffee = new catalog entry (data); new alert channel = new observer (code addition, not modification).
- **L**SP — any `InventoryObserver` is substitutable; we avoided a `Coffee` inheritance tree precisely so LSP can't be violated.
- **I**SP — `InventoryObserver` is a single-method interface; nobody implements methods they don't need.
- **D**IP — `Inventory` depends on the `InventoryObserver` abstraction, never on `ConsoleNotifier`.

### The two decisions an interviewer will probe

1. **Why is `tryConsume` one synchronized check-and-deduct instead of `canMake()` then `consume()`?** Because separated, two threads can both pass the check (TOCTOU race) and over-deduct. The invariant "stock never negative" must be enforced inside one critical section.
2. **Why `int` cents instead of `double` for money?** `double` cannot represent 0.10 exactly; change computation accumulates error. Cents-as-int (or `BigDecimal`) is the professional answer.

---

## Step 4 — Implementation

> Compilable Java 11+. Boilerplate getters trimmed where uninteresting; the patterns, the atomicity, and the transaction flow are the focus.

### Enums

```java
public enum Ingredient {
    COFFEE_BEANS, WATER, MILK, SUGAR, COCOA_POWDER
}

public enum CoffeeType {
    ESPRESSO, CAPPUCCINO, LATTE
}
```

### Recipe and Coffee — immutable value objects

```java
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class Recipe {
    private final Map<Ingredient, Integer> ingredients;

    public Recipe(Map<Ingredient, Integer> ingredients) {
        // Defensive copy + unmodifiable view: immutability makes Recipe
        // inherently thread-safe — it can be read concurrently with no locking.
        this.ingredients = Collections.unmodifiableMap(new EnumMap<>(ingredients));
    }

    public Map<Ingredient, Integer> getIngredients() {
        return ingredients;
    }
}

public final class Coffee {
    private final CoffeeType type;
    private final int priceCents;   // money as int cents — never double (see Step 3 probe #2)
    private final Recipe recipe;

    public Coffee(CoffeeType type, int priceCents, Recipe recipe) {
        if (priceCents <= 0) throw new IllegalArgumentException("price must be positive");
        this.type = type;
        this.priceCents = priceCents;
        this.recipe = recipe;
    }

    public CoffeeType getType()  { return type; }
    public int getPriceCents()   { return priceCents; }
    public Recipe getRecipe()    { return recipe; }
}
```

### Observer contract + a concrete notifier

```java
public interface InventoryObserver {
    void onLowStock(Ingredient ingredient, int remainingQuantity);
}

public class ConsoleNotifier implements InventoryObserver {
    @Override
    public void onLowStock(Ingredient ingredient, int remainingQuantity) {
        System.out.printf("[ALERT] Low stock: %s remaining=%d%n", ingredient, remainingQuantity);
    }
}
```

### Inventory — the concurrency heart of the design

```java
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class Inventory {
    private final Map<Ingredient, Integer> stock = new EnumMap<>(Ingredient.class);
    private final int lowStockThreshold;
    // CopyOnWriteArrayList: observers are added rarely, iterated often,
    // and we iterate OUTSIDE the stock lock — COW makes that iteration safe.
    private final List<InventoryObserver> observers = new CopyOnWriteArrayList<>();

    public Inventory(int lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
        for (Ingredient i : Ingredient.values()) stock.put(i, 0);
    }

    public void addObserver(InventoryObserver o) { observers.add(o); }

    /**
     * Atomic check-and-deduct. This is THE critical section of the system.
     * Either all ingredients of the recipe are deducted, or none are.
     *
     * Why synchronized over ConcurrentHashMap: the operation is a COMPOUND
     * action across MULTIPLE keys (check milk AND beans AND water, then
     * deduct all). CHM only gives per-key atomicity — it cannot make a
     * multi-key transaction atomic. One intrinsic lock over the whole map can.
     */
    public boolean tryConsume(Recipe recipe) {
        List<LowStockEvent> events = new ArrayList<>();
        synchronized (this) {
            // Phase 1: verify everything (no mutation yet → trivially all-or-nothing)
            for (Map.Entry<Ingredient, Integer> e : recipe.getIngredients().entrySet()) {
                if (stock.getOrDefault(e.getKey(), 0) < e.getValue()) {
                    return false;
                }
            }
            // Phase 2: deduct everything
            for (Map.Entry<Ingredient, Integer> e : recipe.getIngredients().entrySet()) {
                int remaining = stock.get(e.getKey()) - e.getValue();
                stock.put(e.getKey(), remaining);
                if (remaining < lowStockThreshold) {
                    events.add(new LowStockEvent(e.getKey(), remaining));
                }
            }
        }
        // Notify OUTSIDE the lock: observer code is "alien" — if it were slow
        // or itself acquired locks, holding our lock here could cause
        // contention or deadlock. Collect inside, fire outside.
        for (LowStockEvent ev : events) {
            for (InventoryObserver o : observers) {
                o.onLowStock(ev.ingredient, ev.remaining);
            }
        }
        return true;
    }

    /** Advisory only — used for menu display. Real guarantee lives in tryConsume. */
    public synchronized boolean canMake(Recipe recipe) {
        for (Map.Entry<Ingredient, Integer> e : recipe.getIngredients().entrySet()) {
            if (stock.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    public synchronized void refill(Ingredient ingredient, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("refill quantity must be positive");
        stock.merge(ingredient, quantity, Integer::sum);
    }

    private static final class LowStockEvent {
        final Ingredient ingredient;
        final int remaining;
        LowStockEvent(Ingredient i, int r) { this.ingredient = i; this.remaining = r; }
    }
}
```

### Exceptions

```java
public class VendingException extends RuntimeException {
    public VendingException(String message) { super(message); }
}

public class InvalidSelectionException extends VendingException {
    public InvalidSelectionException(String msg) { super(msg); }
}

public class InsufficientPaymentException extends VendingException {
    public InsufficientPaymentException(String msg) { super(msg); }
}

public class OutOfIngredientsException extends VendingException {
    public OutOfIngredientsException(String msg) { super(msg); }
}
```

*(Unchecked, because the caller's recovery is the same in every case — show the message and refund. In a library API you might argue for checked; in an interview, state the choice either way.)*

### DTOs

```java
public final class MenuItem {
    public final String name;
    public final int priceCents;
    public final boolean available;   // F2: shown but marked UNAVAILABLE
    MenuItem(String name, int priceCents, boolean available) {
        this.name = name; this.priceCents = priceCents; this.available = available;
    }
    @Override public String toString() {
        return String.format("%-12s $%.2f %s", name, priceCents / 100.0,
                             available ? "" : "(UNAVAILABLE)");
    }
}

public final class DispenseResult {
    public final CoffeeType coffee;
    public final int changeCents;
    DispenseResult(CoffeeType coffee, int changeCents) {
        this.coffee = coffee; this.changeCents = changeCents;
    }
}
```

### The machine — Singleton facade

```java
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class CoffeeVendingMachine {

    // Eager initialization: the JVM class-loading guarantee makes this
    // thread-safe with zero synchronization code. Prefer this (or enum
    // singleton) over double-checked locking unless construction is costly.
    private static final CoffeeVendingMachine INSTANCE = new CoffeeVendingMachine();

    private final Map<CoffeeType, Coffee> menu = new EnumMap<>(CoffeeType.class);
    private final Inventory inventory = new Inventory(/* lowStockThreshold */ 3);

    private CoffeeVendingMachine() {
        // Lightweight factory: all recipe knowledge lives here.
        // Adding MOCHA = one enum constant + one entry. Nothing else changes (OCP).
        menu.put(CoffeeType.ESPRESSO,
            new Coffee(CoffeeType.ESPRESSO, 250, new Recipe(Map.of(
                Ingredient.COFFEE_BEANS, 7, Ingredient.WATER, 30))));
        menu.put(CoffeeType.CAPPUCCINO,
            new Coffee(CoffeeType.CAPPUCCINO, 350, new Recipe(Map.of(
                Ingredient.COFFEE_BEANS, 7, Ingredient.WATER, 30, Ingredient.MILK, 60))));
        menu.put(CoffeeType.LATTE,
            new Coffee(CoffeeType.LATTE, 400, new Recipe(Map.of(
                Ingredient.COFFEE_BEANS, 7, Ingredient.WATER, 20, Ingredient.MILK, 120))));
    }

    public static CoffeeVendingMachine getInstance() { return INSTANCE; }

    public void addInventoryObserver(InventoryObserver o) { inventory.addObserver(o); }
    public void refill(Ingredient ing, int qty)           { inventory.refill(ing, qty); }

    /** F2: every coffee is listed; unavailable ones are flagged, not hidden. */
    public List<MenuItem> getMenu() {
        List<MenuItem> items = new ArrayList<>();
        for (Coffee c : menu.values()) {
            items.add(new MenuItem(c.getType().name(), c.getPriceCents(),
                                   inventory.canMake(c.getRecipe())));
        }
        return items;
    }

    /**
     * Transaction flow — ORDER MATTERS for consistency (N3):
     *   1. Validate selection      (no side effect)
     *   2. Validate payment        (no side effect)
     *   3. tryConsume              (THE side effect — atomic)
     *   4. Compute change, dispense
     * Because steps 1–2 mutate nothing, any failure before step 3 means the
     * machine never "kept" money. If step 3 fails, we throw before charging:
     * full refund is implicit. Money is only retained on the success path.
     */
    public DispenseResult buyCoffee(CoffeeType type, int amountInsertedCents) {
        Coffee coffee = menu.get(type);
        if (coffee == null) {
            throw new InvalidSelectionException("Unknown selection: " + type);
        }
        if (amountInsertedCents < coffee.getPriceCents()) {
            throw new InsufficientPaymentException(String.format(
                "Inserted %d¢ but %s costs %d¢ — amount refunded.",
                amountInsertedCents, type, coffee.getPriceCents()));
        }
        if (!inventory.tryConsume(coffee.getRecipe())) {
            throw new OutOfIngredientsException(
                type + " is unavailable (ingredients exhausted) — amount refunded.");
        }
        int change = amountInsertedCents - coffee.getPriceCents();
        // (Physical dispensing hardware call would go here.)
        return new DispenseResult(type, change);
    }
}
```

### Driver — including a concurrency smoke test

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Demo {
    public static void main(String[] args) throws InterruptedException {
        CoffeeVendingMachine machine = CoffeeVendingMachine.getInstance();
        machine.addInventoryObserver(new ConsoleNotifier());

        machine.refill(Ingredient.COFFEE_BEANS, 21);  // enough beans for exactly 3 cups
        machine.refill(Ingredient.WATER, 500);
        machine.refill(Ingredient.MILK, 500);

        machine.getMenu().forEach(System.out::println);

        // 5 threads race for 3 cups' worth of beans → exactly 3 must succeed.
        ExecutorService pool = Executors.newFixedThreadPool(5);
        for (int i = 0; i < 5; i++) {
            final int user = i;
            pool.submit(() -> {
                try {
                    DispenseResult r = machine.buyCoffee(CoffeeType.ESPRESSO, 300);
                    System.out.printf("User %d got %s, change=%d¢%n",
                                      user, r.coffee, r.changeCents);
                } catch (VendingException e) {
                    System.out.printf("User %d failed: %s%n", user, e.getMessage());
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        machine.getMenu().forEach(System.out::println); // ESPRESSO now UNAVAILABLE
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input

| Case | Handling |
|---|---|
| Unknown/null coffee type | `InvalidSelectionException` before any side effect. |
| Payment < price (incl. 0 or negative) | `InsufficientPaymentException`; nothing deducted, full implicit refund. |
| Negative/zero refill quantity | `IllegalArgumentException` — programmer/operator error, fail fast. |
| Recipe referencing ingredient with no stock entry | `getOrDefault(…, 0)` treats it as zero — fails safe (rejects) rather than NPE. |

### Boundary conditions

- **Exact payment** → change = 0 (verified by simple arithmetic on the success path; no special case needed).
- **Stock hits exactly 0** → next `canMake` flags item UNAVAILABLE on the menu; next `tryConsume` returns false.
- **Stock crosses the threshold exactly** → `remaining < threshold` fires once per deduction below the line. (Improvement: fire only on the *crossing*, not on every subsequent sale, by remembering the previous level — mention this if probed about alert spam.)
- **Menu says AVAILABLE but purchase fails** → legal and expected: `canMake` is an advisory snapshot (TOCTOU by design); the *authoritative* check is inside `tryConsume`. The user gets a clean `OutOfIngredientsException` + refund, never a half-made coffee.

### Graceful degradation

- One coffee being out never blocks others (recipes checked independently).
- Observer failures: in production, wrap each `onLowStock` call in try/catch so one bad notifier can't break dispensing or starve other observers.
- Money is only retained on the single success path; every exception path implies full refund (N3).

### Concurrency analysis (the part the interviewer cares about)

**Shared mutable state — exhaustive list:**
1. `Inventory.stock` (the map) — the dangerous one.
2. `Inventory.observers` — handled by `CopyOnWriteArrayList`.
3. `CoffeeVendingMachine.menu` — populated once in the constructor, never mutated, and safely published via the `static final` instance → effectively immutable, no locking needed.
4. `Recipe` / `Coffee` — immutable, inherently thread-safe.

**Critical section:** the check-all-then-deduct-all sequence in `tryConsume`. Splitting it (public `canMake()` then `consume()`) creates the classic **check-then-act race**: threads A and B both see 7 beans, both deduct 7, stock = −7. Fusing them under one lock makes "stock ≥ 0" an invariant.

**Chosen primitive: `synchronized` (intrinsic lock) — and why the alternatives lose here:**

| Alternative | Why not |
|---|---|
| `ConcurrentHashMap` | Gives per-key atomicity (`compute`, `merge`), but a recipe is a **multi-key transaction**. CHM cannot atomically check-and-deduct across milk *and* beans *and* water. |
| `AtomicInteger` per ingredient | Same multi-key problem; you'd need lock-free multi-word CAS, which the JVM doesn't give you. Rolling back partial deductions on conflict is error-prone complexity for zero benefit at this scale. |
| `ReentrantLock` | Functionally equivalent here. Choose it only if you need `tryLock` with timeout, fairness, or multiple condition queues. We need none — `synchronized` is simpler and JIT-optimized. Say this trade-off out loud. |
| One lock **per ingredient** | Higher theoretical throughput, but now `tryConsume` acquires multiple locks → you must impose a global lock ordering (e.g., enum ordinal) to prevent deadlock. Real contention on a coffee machine is trivial; this is premature optimization with real deadlock risk. |

**Deadlock-freedom argument:** the design holds **at most one lock at a time** (the single `Inventory` monitor), and observer callbacks — the only alien code — run *after* the lock is released. No nested lock acquisition anywhere ⇒ no circular wait ⇒ deadlock impossible by construction.

**Livelock/starvation:** no spinning or retry loops exist; intrinsic locks are unfair in theory, but critical sections are a few map operations (~microseconds), so practical starvation is a non-issue. If fairness were a hard requirement, switch to `new ReentrantLock(true)`.

**Race-freedom argument:** every read and write of `stock` happens inside the same monitor ⇒ all accesses are totally ordered with happens-before edges ⇒ no data races; the all-or-nothing structure of `tryConsume` ⇒ no logical races (over-selling).

---

## Interview Follow-Ups (with model answers)

1. **"Add a Mocha."** One `CoffeeType` enum value + one catalog entry with its recipe. No logic changes — that's OCP working. If coffees came from a DB/config file instead of the enum, even the enum edit disappears (fully data-driven menu).
2. **"Support card and UPI payments."** Introduce `interface PaymentStrategy { PaymentResult pay(int amountCents); }` with `CashPayment`, `CardPayment` implementations; `buyCoffee` accepts a strategy. That's the Strategy pattern — the payment *algorithm* varies, the transaction flow doesn't.
3. **"Support customization (extra shot, oat milk)."** Decorator over the recipe/price: `ExtraShot(coffee)` wraps a `Coffee`, adding to both the recipe map and the price. Avoids the subclass explosion (`LatteWithExtraShotAndOatMilk`).
4. **"What if dispensing hardware fails after ingredients are deducted?"** Now you need a compensating transaction: re-credit the ingredients (or a reservation model — reserve, dispense, commit/rollback). This is the saga pattern in miniature; in the current design the hardware call sits inside the success path precisely so we can wrap it in try/catch + compensation later.
5. **"Make it a fleet of 1,000 machines reporting to a cloud service."** The Singleton stays *per machine*; inventory events publish to a message broker (the Observer pattern's distributed cousin, pub/sub); central service aggregates for restocking routes. In-memory state per machine remains correct because each machine's inventory is still physically local.

## Transferable Lesson

The reusable kernel of this problem is **atomic check-then-act over multi-key state under one lock, with observer callbacks fired outside the lock**. The same shape reappears in: Parking Lot (spot availability), Movie Ticket Booking (seat locking), Inventory/Order systems, and ATM (cash dispensing). Also bank the meta-lesson: *the absence of a pattern is a design decision* — we consciously rejected State (single-amount payment) and per-ingredient locks (premature optimization), and saying *why* scores more interview points than name-dropping patterns.

## Suggested Next Problem

**Vending Machine** (the full version) — same domain, but incremental coin insertion forces the **State pattern** (IDLE → HAS_MONEY → DISPENSING), which is the single most-asked pattern in LLD interviews. It builds directly on what you just locked in.
