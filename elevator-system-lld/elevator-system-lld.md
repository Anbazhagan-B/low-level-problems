# Elevator System — Low Level Design (Java)

> Problem source: awesome-low-level-design • Difficulty: Medium/Hard (Concurrency)
> Patterns exercised: **Strategy**, **State (via enum + transitions)**, **Singleton (controller)**, **Producer–Consumer**
> Core algorithm: **SCAN ("elevator algorithm")** per elevator + **nearest-car dispatching** across elevators

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| F1 | The system manages **N elevators** serving floors **1..M** in a single building. |
| F2 | **External (hall) request:** a user on floor F presses UP or DOWN; the *system* picks the best elevator. |
| F3 | **Internal (cabin) request:** a user inside elevator E presses a destination floor. |
| F4 | Each elevator has a **capacity limit** (max passengers); boarding beyond capacity is rejected and a full elevator skips new pickups. |
| F5 | **Dispatching** assigns a hall request to the optimal elevator: nearest car already moving in a compatible direction, else nearest idle car. |
| F6 | Each elevator **batches stops** using SCAN: serve all stops in the current direction, then reverse. No FCFS. |
| F7 | Elevator lifecycle: move floor-by-floor, open/close doors at stops, transition through states (IDLE, MOVING_UP, MOVING_DOWN, MAINTENANCE). |

### Non-Functional Requirements

| # | Requirement |
|---|-------------|
| N1 | **Concurrency:** requests arrive from many threads (every hall panel + cabin panel is a producer); each elevator runs on its own thread (consumer). |
| N2 | **Thread safety:** shared mutable state (pending stops, current floor, direction, passenger count) must be protected; no race between dispatcher reads and elevator-thread writes. |
| N3 | **No starvation:** SCAN guarantees every accepted stop is eventually served (bounded by one full sweep). |
| N4 | **Extensibility:** the dispatch policy is swappable (Strategy) without touching elevator logic. |
| N5 | **Modest scale:** in-process design, one building, 2–16 elevators. No persistence, no distribution. |

### Assumptions

- Floors `1..M`, single elevator bank, all elevators serve all floors.
- Hall request = `(floor, Direction)`; cabin request = `(destinationFloor)` against a specific elevator.
- Movement between adjacent floors takes a fixed simulated time (`Thread.sleep`).
- Capacity is a passenger count enforced via `board()` / `exit()`.
- No failure recovery or networked controllers (follow-up territory).

---

## Step 2 — Entities & Relationships

### Core Entities

| Entity | Responsibility |
|--------|----------------|
| `ElevatorSystem` (controller, Singleton) | Entry point. Owns elevators, receives hall requests, delegates selection to the strategy, starts/stops elevator threads. |
| `Elevator` (implements `Runnable`) | One physical car. Owns its current floor, direction, state, passenger count, and its **pending stop sets**. Runs the SCAN loop on its own thread. |
| `ElevatorSelectionStrategy` (interface) | Picks the best elevator for a hall request. |
| `NearestElevatorStrategy` | Concrete strategy: scores cars by distance + direction compatibility. |
| `HallRequest` | Value object: `(floor, Direction)`. |
| `Direction` (enum) | `UP`, `DOWN`, `IDLE`. |
| `ElevatorState` (enum) | `IDLE`, `MOVING_UP`, `MOVING_DOWN`, `MAINTENANCE`. |

### Relationships (with type and justification)

| Relationship | Type | Why |
|--------------|------|-----|
| `ElevatorSystem` → `Elevator` (1..N) | **Composition** | Elevators have no meaning outside the system; the system creates and owns their lifecycle (and their threads). |
| `ElevatorSystem` → `ElevatorSelectionStrategy` | **Aggregation** | The strategy is injected and replaceable at runtime; the system uses it but does not own its lifecycle. (Spring analogue: constructor-injected bean.) |
| `Elevator` → `Direction`, `ElevatorState` | **Association** | Elevator holds current values of these enums. |
| `ElevatorSystem` → `HallRequest` | **Dependency** | Requests are short-lived parameters, consumed and discarded — not stored as structure. |
| `Elevator` → stop sets (`TreeSet<Integer>` up/down) | **Composition** | The pending-stops data structure is internal state, never exposed. |

### What people commonly get wrong here

- **Collapsing hall and cabin requests into one class** — they carry different information (direction vs. destination) and flow through different paths (dispatcher vs. direct-to-elevator).
- **Putting the scheduling queue in the controller** — each elevator must own its own stop sets; the controller only *assigns*, it doesn't *sequence*.
- **Modeling a `Button`/`Door`/`Display` class hierarchy** — over-modeling. Interviewers want the scheduling and concurrency, not hardware simulation.

---

## Step 3 — UML Class Design

```text
┌─────────────────────────────────────┐
│        <<singleton>>                │
│        ElevatorSystem               │
├─────────────────────────────────────┤
│ - elevators : List<Elevator>        │
│ - strategy  : ElevatorSelectionStrategy
│ - executor  : ExecutorService       │
├─────────────────────────────────────┤
│ + requestElevator(floor, dir)       │  ← hall call (external)
│ + selectFloor(elevatorId, floor)    │  ← cabin call (internal)
│ + start() / shutdown()              │
└──────────┬───────────────┬──────────┘
           │ composition 1..N         │ aggregation (injected)
           ▼                          ▼
┌─────────────────────────┐   ┌─────────────────────────────────┐
│ Elevator                │   │ <<interface>>                   │
│ (implements Runnable)   │   │ ElevatorSelectionStrategy       │
├─────────────────────────┤   ├─────────────────────────────────┤
│ - id : int              │   │ + select(List<Elevator>,        │
│ - currentFloor : int    │   │          HallRequest): Elevator │
│ - direction : Direction │   └───────────────┬─────────────────┘
│ - state : ElevatorState │                   │ implements
│ - capacity : int        │   ┌───────────────▼─────────────────┐
│ - passengers : int      │   │ NearestElevatorStrategy         │
│ - upStops   : TreeSet   │   │ + select(...)                   │
│ - downStops : TreeSet   │   └─────────────────────────────────┘
│ - lock : ReentrantLock  │
│ - hasWork : Condition   │   ┌──────────────┐  ┌──────────────────┐
├─────────────────────────┤   │ <<enum>>     │  │ <<enum>>         │
│ + addStop(floor)        │   │ Direction    │  │ ElevatorState    │
│ + board() / exit()      │   │ UP DOWN IDLE │  │ IDLE MOVING_UP   │
│ + run()        (SCAN)   │   └──────────────┘  │ MOVING_DOWN      │
│ + costFor(request): int │                     │ MAINTENANCE      │
└─────────────────────────┘                     └──────────────────┘

HallRequest { floor : int, direction : Direction }   ← immutable value object
```

### Design ↔ Concept Mapping

| Concept | Where & why |
|---------|-------------|
| **Strategy pattern** | `ElevatorSelectionStrategy`. Dispatch policy is the most volatile part of the design (nearest-car → zoning → destination dispatch). Strategy isolates that volatility behind one interface so changing policy never touches `Elevator`. |
| **State (lightweight)** | `ElevatorState` enum + guarded transitions inside `Elevator`. A full GoF State pattern (one class per state) is justified only if per-state *behavior* grows (e.g., MAINTENANCE rejecting stops with custom logic). Saying this trade-off out loud scores points. |
| **Singleton** | `ElevatorSystem` — there is exactly one controller per building. In Spring you'd simply make it a `@Component` (singleton scope by default) instead of hand-rolling `getInstance()`; mention that DI is the modern Singleton. |
| **Producer–Consumer** | Hall/cabin panels (producers) add stops; each elevator thread (consumer) drains its own stop sets, parking on a `Condition` when empty. |
| **SRP (S in SOLID)** | Controller = assignment; Elevator = movement/sequencing; Strategy = selection. Three responsibilities, three homes. |
| **OCP (O)** | New dispatch policies are added by *implementing* the strategy interface, not modifying existing code. |
| **DIP (D)** | `ElevatorSystem` depends on the `ElevatorSelectionStrategy` abstraction, never a concrete policy. |

### Decisions an interviewer will probe

1. **Why two `TreeSet`s instead of one `PriorityQueue`?** SCAN needs *direction-partitioned, sorted, deduplicated* stops: going up you serve `upStops` in ascending order (`ceiling/first`), going down `downStops` in descending. A single priority queue can't flip its comparator mid-flight, and duplicate floor presses must collapse to one stop — `TreeSet` gives all three properties for free.
2. **Why per-elevator locks instead of one global lock?** A global lock serializes the whole bank — elevator 3's movement would block a hall call assignment to elevator 1. Per-elevator `ReentrantLock` keeps cars independent; the dispatcher takes a brief snapshot of each car's state when scoring.

---

## Step 4 — Implementation

### Enums and the request value object

```java
public enum Direction { UP, DOWN, IDLE }

public enum ElevatorState { IDLE, MOVING_UP, MOVING_DOWN, MAINTENANCE }

/** Immutable hall call: floor + desired direction. Immutability => freely shareable across threads. */
public final class HallRequest {
    private final int floor;
    private final Direction direction;

    public HallRequest(int floor, Direction direction) {
        if (direction == Direction.IDLE)
            throw new IllegalArgumentException("Hall request must be UP or DOWN");
        this.floor = floor;
        this.direction = direction;
    }
    public int getFloor() { return floor; }
    public Direction getDirection() { return direction; }
}
```

### Elevator — owns its stops, runs SCAN on its own thread

```java
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Elevator implements Runnable {

    private final int id;
    private final int capacity;
    private final int minFloor;
    private final int maxFloor;

    // ---- shared mutable state: ALWAYS accessed under `lock` ----
    private int currentFloor = 1;
    private int passengers = 0;
    private Direction direction = Direction.IDLE;
    private ElevatorState state = ElevatorState.IDLE;
    private final NavigableSet<Integer> upStops = new TreeSet<>();
    private final NavigableSet<Integer> downStops = new TreeSet<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition hasWork = lock.newCondition();   // elevator parks here when no stops
    private volatile boolean running = true;

    public Elevator(int id, int capacity, int minFloor, int maxFloor) {
        this.id = id;
        this.capacity = capacity;
        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
    }

    /** Called by dispatcher (hall request) and by cabin panel (internal request). Thread-safe. */
    public void addStop(int floor) {
        if (floor < minFloor || floor > maxFloor)
            throw new InvalidFloorException("Floor " + floor + " out of range");
        lock.lock();
        try {
            if (state == ElevatorState.MAINTENANCE)
                throw new ElevatorUnavailableException("Elevator " + id + " in maintenance");
            if (floor == currentFloor) return;                 // already here; doors handle it
            if (floor > currentFloor) upStops.add(floor);      // TreeSet dedupes repeated presses
            else                      downStops.add(floor);
            if (direction == Direction.IDLE) {                 // wake a parked elevator
                direction = (floor > currentFloor) ? Direction.UP : Direction.DOWN;
            }
            hasWork.signal();
        } finally {
            lock.unlock();
        }
    }

    /** Boarding is a check-then-act on `passengers`; must be atomic, hence inside the lock. */
    public boolean board() {
        lock.lock();
        try {
            if (passengers >= capacity) return false;          // full: caller takes next car
            passengers++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void exit() {
        lock.lock();
        try {
            if (passengers > 0) passengers--;
        } finally {
            lock.unlock();
        }
    }

    /** SCAN loop: serve current direction fully, then reverse; park when no work. */
    @Override
    public void run() {
        while (running) {
            Integer nextStop = null;
            lock.lock();
            try {
                while (running && upStops.isEmpty() && downStops.isEmpty()) {
                    direction = Direction.IDLE;
                    state = ElevatorState.IDLE;
                    hasWork.await(1, TimeUnit.SECONDS);        // timed wait: also lets `running` be rechecked
                }
                if (!running) return;
                nextStop = chooseNextStopLocked();             // decides direction + target under the lock
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }
            if (nextStop != null) moveTo(nextStop);            // movement happens OUTSIDE the lock
        }
    }

    /** Must hold `lock`. Implements the SCAN direction decision. */
    private Integer chooseNextStopLocked() {
        if (direction == Direction.UP) {
            Integer next = upStops.ceiling(currentFloor);      // nearest stop above (or at) us
            if (next != null) return next;
            direction = Direction.DOWN;                        // exhausted upward sweep → reverse
            return downStops.isEmpty() ? null : downStops.last();
        } else { // DOWN (IDLE was resolved in addStop / await loop)
            Integer next = downStops.floor(currentFloor);
            if (next != null) return next;
            direction = Direction.UP;
            return upStops.isEmpty() ? null : upStops.first();
        }
    }

    /** Simulates floor-by-floor travel. Takes the lock only briefly per floor — never sleeps while holding it. */
    private void moveTo(int target) {
        while (true) {
            lock.lock();
            try {
                if (currentFloor == target) {
                    openDoorsLocked(target);
                    return;
                }
                state = (target > currentFloor) ? ElevatorState.MOVING_UP : ElevatorState.MOVING_DOWN;
                currentFloor += (target > currentFloor) ? 1 : -1;
                // A new stop may have been added between current floor and target on our path:
                NavigableSet<Integer> stops = (direction == Direction.UP) ? upStops : downStops;
                if (stops.contains(currentFloor)) openDoorsLocked(currentFloor); // opportunistic pickup (SCAN)
            } finally {
                lock.unlock();
            }
            sleepQuietly(1000);                                // travel time per floor, lock-free
        }
    }

    /** Must hold `lock`. */
    private void openDoorsLocked(int floor) {
        upStops.remove(floor);
        downStops.remove(floor);
        System.out.printf("Elevator %d: doors open at floor %d (%s)%n", id, floor, direction);
        // door dwell time would be a short lock-free sleep in a fuller simulation
    }

    /** Dispatch cost: lower is better. Snapshot read under the lock. */
    public int costFor(HallRequest r) {
        lock.lock();
        try {
            if (state == ElevatorState.MAINTENANCE || passengers >= capacity)
                return Integer.MAX_VALUE;                      // effectively excluded
            int distance = Math.abs(currentFloor - r.getFloor());
            boolean onTheWay =
                (direction == Direction.UP   && r.getDirection() == Direction.UP   && r.getFloor() >= currentFloor) ||
                (direction == Direction.DOWN && r.getDirection() == Direction.DOWN && r.getFloor() <= currentFloor);
            if (direction == Direction.IDLE) return distance;          // idle: pure proximity
            if (onTheWay)                    return distance;          // same direction, ahead of us
            return distance + 2 * (maxFloor - minFloor);               // opposite/behind: heavy penalty
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        running = false;
        lock.lock();
        try { hasWork.signalAll(); } finally { lock.unlock(); }
    }

    public int getId() { return id; }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

### Strategy interface + nearest-car implementation

```java
import java.util.Comparator;
import java.util.List;

public interface ElevatorSelectionStrategy {
    Elevator select(List<Elevator> elevators, HallRequest request);
}

/** Nearest compatible car wins; full / maintenance cars score MAX_VALUE and lose. */
public class NearestElevatorStrategy implements ElevatorSelectionStrategy {
    @Override
    public Elevator select(List<Elevator> elevators, HallRequest request) {
        return elevators.stream()
                .min(Comparator.comparingInt(e -> e.costFor(request)))
                .orElseThrow(() -> new ElevatorUnavailableException("No elevators registered"));
    }
}
```

### Controller (Singleton)

```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ElevatorSystem {

    private static volatile ElevatorSystem instance;           // double-checked locking singleton

    private final List<Elevator> elevators;
    private final ElevatorSelectionStrategy strategy;
    private final ExecutorService executor;

    private ElevatorSystem(int elevatorCount, int capacity, int minFloor, int maxFloor,
                           ElevatorSelectionStrategy strategy) {
        List<Elevator> list = new ArrayList<>();
        for (int i = 1; i <= elevatorCount; i++)
            list.add(new Elevator(i, capacity, minFloor, maxFloor));
        this.elevators = Collections.unmodifiableList(list);   // structure is immutable after construction
        this.strategy = strategy;
        this.executor = Executors.newFixedThreadPool(elevatorCount);
    }

    public static ElevatorSystem getInstance(int elevators, int capacity, int minFloor, int maxFloor) {
        if (instance == null) {
            synchronized (ElevatorSystem.class) {
                if (instance == null)
                    instance = new ElevatorSystem(elevators, capacity, minFloor, maxFloor,
                                                  new NearestElevatorStrategy());
            }
        }
        return instance;
        // Spring note: in a real app this whole dance disappears — declare it a @Component and
        // inject the strategy; the container guarantees a single instance.
    }

    public void start() {
        elevators.forEach(executor::submit);                   // one thread per elevator (consumer)
    }

    /** External hall call — any thread may invoke. */
    public Elevator requestElevator(int floor, Direction dir) {
        HallRequest request = new HallRequest(floor, dir);
        Elevator chosen = strategy.select(elevators, request);
        chosen.addStop(floor);
        System.out.printf("Hall call (floor %d, %s) -> elevator %d%n", floor, dir, chosen.getId());
        return chosen;
    }

    /** Internal cabin call — destination button inside elevator `elevatorId`. */
    public void selectFloor(int elevatorId, int floor) {
        elevators.stream()
                .filter(e -> e.getId() == elevatorId)
                .findFirst()
                .orElseThrow(() -> new ElevatorUnavailableException("No elevator " + elevatorId))
                .addStop(floor);
    }

    public void shutdown() {
        elevators.forEach(Elevator::shutdown);
        executor.shutdown();
    }
}
```

### Exceptions + demo

```java
public class InvalidFloorException extends RuntimeException {
    public InvalidFloorException(String msg) { super(msg); }
}

public class ElevatorUnavailableException extends RuntimeException {
    public ElevatorUnavailableException(String msg) { super(msg); }
}

public class ElevatorDemo {
    public static void main(String[] args) throws InterruptedException {
        ElevatorSystem system = ElevatorSystem.getInstance(3, 8, 1, 10);
        system.start();

        // Concurrent producers: hall panels on different floors
        new Thread(() -> system.requestElevator(5, Direction.UP)).start();
        new Thread(() -> system.requestElevator(9, Direction.DOWN)).start();
        new Thread(() -> system.requestElevator(2, Direction.UP)).start();

        Thread.sleep(3000);
        system.selectFloor(1, 8);     // rider inside elevator 1 presses 8
        Thread.sleep(15000);
        system.shutdown();
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input

| Case | Handling |
|------|----------|
| Floor outside `[minFloor, maxFloor]` | `InvalidFloorException` thrown at the boundary (`addStop`), before any state changes. Fail fast. |
| `HallRequest` with direction `IDLE` | Rejected in the constructor — an invalid request object can never exist (validate at construction, the immutability payoff). |
| Stop requested on a MAINTENANCE elevator | `ElevatorUnavailableException`; the dispatcher already avoids it via `costFor = MAX_VALUE`, so this guards only direct cabin calls. |
| Unknown elevator id in `selectFloor` | `ElevatorUnavailableException`. |

### Boundary conditions

- **Request for the current floor:** `addStop` returns without queuing — doors would simply (re)open; no phantom stop pollutes the sets.
- **Duplicate button presses:** `TreeSet` semantics collapse them to one stop — no special code needed (a data-structure choice eliminating an edge case is worth saying out loud in an interview).
- **All elevators full or in maintenance:** every `costFor` returns `MAX_VALUE`; the strategy still picks one (least-bad) — an alternative is to queue the hall request and retry, a fair follow-up discussion.
- **Top/bottom floor reversal:** `chooseNextStopLocked` reverses direction naturally when a sweep's set is exhausted; the elevator never tries to pass `maxFloor`/`minFloor` because no out-of-range stop can be added.
- **Empty system parking:** with no stops anywhere, every elevator sits in `hasWork.await(...)` consuming no CPU — no busy-waiting.

### Concurrency analysis (the part the interviewer cares most about)

**Shared mutable state:** per elevator — `currentFloor`, `direction`, `state`, `passengers`, `upStops`, `downStops`. The controller's `elevators` list is immutable after construction (`unmodifiableList` + final), so iterating it concurrently is safe.

**Critical sections:**
1. `addStop` — mutates a stop set, possibly flips direction, signals.
2. `chooseNextStopLocked` — reads both sets and writes `direction`.
3. The per-floor step inside `moveTo` — writes `currentFloor`, checks for opportunistic stops.
4. `board`/`exit` — check-then-act on `passengers` (the classic race if unguarded).
5. `costFor` — consistent snapshot of floor/direction/state for scoring.

**Primitive chosen: one `ReentrantLock` + `Condition` per elevator.**
- *Why not `synchronized`/`wait`?* Functionally equivalent here, but `Condition.await(timeout)` gives a clean timed park (so `running` is rechecked even if a signal is lost at shutdown), and `ReentrantLock` reads better when you need `lockInterruptibly` or fairness later.
- *Why not a `BlockingQueue` per elevator?* A queue imposes FIFO; SCAN needs *sorted, direction-partitioned, deduplicated* stops with mid-flight insertion (a new stop on the current path gets served this sweep). `TreeSet` under a lock models the domain; a queue would fight it.
- *Why not lock-free / atomics?* The invariant spans multiple fields (sets + direction + floor must change consistently); CAS on a single variable can't protect a multi-field invariant. Per-elevator locking is coarse enough to be simple and fine-grained enough to keep cars independent.

**Race conditions prevented:**
- *Lost wakeup:* `addStop` signals **while holding the lock**, and the consumer awaits **inside a `while` loop re-checking the predicate** (`upStops.isEmpty() && downStops.isEmpty()`), the canonical guarded-wait idiom. Spurious wakeups are harmless.
- *Stale dispatch:* the dispatcher's `costFor` snapshot can be slightly stale by assignment time (the car moved one floor). That's acceptable — it degrades optimality, never correctness; the stop is still served. Calling this out shows you know the difference between a race that breaks invariants and benign staleness.
- *Capacity race:* two riders boarding simultaneously can't exceed capacity because `board()` is check-then-act under the lock.

**Deadlock-freedom argument:** every code path acquires **at most one lock at a time** (its own elevator's). There is no lock ordering problem because there is no nesting — `costFor` is called per elevator sequentially by the strategy, releasing each lock before the next. No thread sleeps or blocks while holding a lock (`moveTo` releases before `Thread.sleep`). One-lock-at-a-time + no-hold-and-wait ⇒ deadlock impossible by construction.

**Livelock/starvation:** SCAN serves every queued stop within one sweep bound — a floor-1 request cannot be starved by top-floor traffic (this is exactly why pure shortest-seek-first is rejected). The timed `await` guarantees parked elevators eventually observe shutdown.

**Graceful shutdown:** `running` is `volatile` (visibility across threads), `shutdown()` signals all conditions, the run loop exits cleanly, and the executor is shut down — no thread leaks.

---

## Interviewer Follow-ups (with model answers)

1. **"Add destination dispatch (rider enters destination at the hall panel)."** The hall request becomes `(from, to)`; direction is derived. Only the Strategy and `HallRequest` change — `Elevator` already accepts arbitrary stops. This is the OCP payoff: cite it.
2. **"What if the building has 100 floors and 16 elevators?"** Zone the bank (low-rise/high-rise groups) — a new `ZonedSelectionStrategy` filtering elevators by served-floor range. Per-elevator logic untouched.
3. **"Make MAINTENANCE richer (reject stops, finish current riders, then park)."** This is the trigger to upgrade from the state *enum* to the full GoF **State pattern**: `ElevatorBehavior` interface with `IdleState/MovingState/MaintenanceState` classes, each deciding how `addStop` behaves.
4. **"How would you test the concurrency?"** Deterministic unit tests for `chooseNextStopLocked` (pure logic), plus stress tests: hammer `addStop`/`board` from many threads, assert invariants (passengers ≤ capacity, every added stop eventually served). Mention `jcstress` for real race hunting.
5. **"What changes if elevators are in different buildings / processes?"** The controller becomes a service; per-elevator stop sets become per-elevator actors/queues with message passing; `costFor` becomes a heartbeat-reported state. The *object decomposition survives* — only the transport changes.

## Transferable Lesson

- **Strategy for the volatile axis** (here: dispatch policy) reappears in Parking Lot (fee strategy), Splitwise (split strategy), Vending Machine (payment).
- **One lock per independent unit + condition-based parking** is the template for every producer–consumer LLD (Task Scheduler, Logger, Rate Limiter).
- **Pick the data structure that erases edge cases** (`TreeSet` killing duplicates and giving sorted sweeps) — a recurring senior-level signal.

**Next problem suggestion:** *Vending Machine* (canonical full State pattern) or, to stay on the concurrency track, *Thread-Safe LRU Cache*.
