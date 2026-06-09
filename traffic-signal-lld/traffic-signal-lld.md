# Traffic Signal Control System — Low Level Design

> Problem 3 in our sequence (after Parking Lot, Vending Machine). The new muscle this problem builds: a **time-driven state machine** (a scheduler fires transitions, not user actions) with **event-driven preemption** (emergencies interrupt the cycle). The vending machine taught State for user events; this teaches State under a clock, plus the concurrency that comes with it.

---

## Step 1 — Requirements

### Functional Requirements

1. **Intersection model**: one intersection with multiple roads (4 by default: N/E/S/W), each having its own traffic light.
2. **Signal states**: each light cycles RED → GREEN → YELLOW → RED. Green follows red; yellow is the warning on the way back to red.
3. **Configurable durations**: GREEN and YELLOW durations are per-light configurable and adjustable **at runtime** (rush-hour tuning, sensor-driven adaptation). RED has no configured duration — a road is red whenever it isn't its turn, so red time is *derived* from everyone else's green + yellow. (Modeling red as configurable is a common over-modeling mistake.)
4. **Safe, coordinated transitions**: at no instant may two conflicting roads be non-red. Lights do not transition independently; a controller coordinates them.
5. **Emergency preemption**: an approaching emergency vehicle interrupts the normal cycle — its road goes green (via a safe wind-down of the current green), holds until an explicit "cleared" event, then normal cycling resumes. Multiple simultaneous emergencies are queued FIFO.
6. **Extensibility**: new cycle policies (traffic-density-weighted), new roads, new listeners (displays, telemetry), and eventually pedestrian/turn signals should slot in without rewriting the core.

### Non-Functional Requirements

- **Safety over throughput.** A design that can ever show two conflicting greens is wrong, full stop — the safety invariant is enforced in code, not by convention.
- **Concurrency**: timed transitions fire on a scheduler thread while emergency events and duration changes arrive from arbitrary other threads. Shared state must be protected; cancellation races must be handled.
- **Scale**: one controller per intersection; "scalable" means many independent intersections (a network coordinator can sit above this design), not high QPS on one object.
- **Testability**: transitions are driven by an injectable `ScheduledExecutorService`-based clock; durations are milliseconds, so tests run in tens of ms.

### Assumptions (stated to the interviewer)

- Single intersection, one road green at a time, round-robin order. (Real intersections group opposite roads into *phases* — N+S green together; the extension point for that is called out in Step 3.)
- Emergencies are served FIFO; "vehicle has cleared" arrives as an explicit callback (`emergencyCleared()`), with a watchdog timeout noted as a production hardening.
- Pedestrian signals out of scope, but the Observer seam means a `PedestrianSignal` could subscribe to light changes without controller changes.

---

## Step 2 — Entities & Relationships

| Entity | Kind | Responsibility | Relationship to others |
|---|---|---|---|
| `SignalState` | enum (State pattern) | RED/GREEN/YELLOW; each knows its successor via `next()` | Used by `TrafficLight` (dependency) |
| `Direction` | enum | N/E/S/W approach roads | Identity key for lights |
| `TrafficLight` | class | One physical light: holds current state + configurable durations; notifies observers. Deliberately "dumb" — it never decides *when* to change | **Composition** with `IntersectionController` (lights have no life outside their intersection); **association** with observers |
| `IntersectionController` | class (Facade/Mediator) | The brain: drives the timed cycle, enforces the safety invariant, handles emergency preemption | **Composes** lights; **aggregates** a `CycleStrategy` (injected, swappable); **owns** the scheduler and emergency queue |
| `CycleStrategy` / `RoundRobinCycleStrategy` | interface + impl (Strategy) | "Which road gets green next" — the rule most likely to change | Injected into controller (aggregation/dependency) |
| `TrafficLightObserver` | interface (Observer) | Displays, audit logs, telemetry react to state changes | Subscribed to `TrafficLight` |
| `EmergencyRequest` | immutable value object | Direction + vehicle type + timestamp; safe to pass across threads | Queued inside controller |
| `UnsafeSignalStateException` | unchecked exception | Loud failure if the safety invariant would be violated | Thrown by controller |

**Common modeling mistakes this avoids:**
- Putting transition timers *inside* `TrafficLight` → each light becomes an independent actor and the safety invariant ("only one non-red") has no single owner. Coordination problems need a coordinator (Mediator).
- A `Road`/`Vehicle`/`Lane` class hierarchy → YAGNI; nothing in the requirements needs them. `Direction` is sufficient identity.
- Configurable RED duration → red is derived, not configured.

---

## Step 3 — UML Class Design

```text
                                  «interface»
                              TrafficLightObserver
                              + onStateChange(Direction, SignalState)
                                       ▲
                                       ┆ implements
                              ConsoleDisplay (demo)

 «enum» SignalState                «enum» Direction
 RED / GREEN / YELLOW              NORTH / EAST / SOUTH / WEST
 + next(): SignalState

 ┌────────────────────────────────┐         ┌─────────────────────────────────────────┐
 │ TrafficLight                   │         │ IntersectionController                  │
 ├────────────────────────────────┤   1   * ├─────────────────────────────────────────┤
 │ - direction: Direction         │◆────────│ - lights: Map<Direction,TrafficLight>   │
 │ - state: volatile SignalState  │ compos. │ - cycleStrategy: CycleStrategy          │
 │ - durations: Map<State,Long>   │         │ - scheduler: ScheduledExecutorService   │
 │ - observers: List<Observer>    │         │ - lock: ReentrantLock                   │
 ├────────────────────────────────┤         │ - mode: NORMAL|CLEARING|EMERGENCY_HOLD  │
 │ + setDuration(state, ms)       │         │ - emergencies: Deque<EmergencyRequest>  │
 │ + addObserver(o)               │         │ - generation: long  (fencing token)     │
 │ ~ setState(s)  «pkg-private»   │         ├─────────────────────────────────────────┤
 └────────────────────────────────┘         │ + start() / shutdown()                  │
                                            │ + reportEmergency(req)   «any thread»   │
 «interface» CycleStrategy                  │ + emergencyCleared()     «any thread»   │
 + nextGreen(current, order)                │ + adjustDuration(d, state, ms)          │
        ▲                                   │ - turnGreen / toYellow / toRedAndAdvance│
        ┆ implements              ◇─────────│ - schedule(transition, delay)           │
 RoundRobinCycleStrategy   aggregation      └─────────────────────────────────────────┘
                                                         │ uses
                                            EmergencyRequest (immutable VO)
                                            UnsafeSignalStateException
```

Multiplicity: one controller composes 2..* lights (one per direction); each light has 0..* observers.

### Pattern → why it fits *here*

| Pattern | Where | Why it fits (the part interviewers want) |
|---|---|---|
| **State** | `SignalState` enum with `next()` | The same tick means different things depending on current state (green→yellow vs yellow→red). Encoding the successor *in* the state kills the `switch` that would otherwise grow in the controller. Enum-based State is the lightweight variant — full State classes (like the vending machine) would be overkill because traffic states carry no per-state data or distinct method sets. Knowing when to use the cheap variant is itself a senior signal. |
| **Mediator** (with a **Facade** face) | `IntersectionController` | The invariant "at most one non-red" spans *all* lights, so no single light can own it. Lights never talk to each other; they are coordinated through one mediator that is the *single writer* of state. The public API (`start`, `reportEmergency`, ...) is the facade. |
| **Strategy** | `CycleStrategy` | "Which road next" is the rule most likely to change (round-robin → density-weighted → green-wave coordinated). The requirement "adjust based on traffic conditions" is precisely a strategy seam. |
| **Observer** | `TrafficLightObserver` | Displays, logging, pedestrian units, and telemetry all react to the same event with no business influence on it. Hardcoding them into `setState` would couple the device to every consumer. |
| **Command (implicit)** | `schedule(Runnable transition, delay)` | Each future transition is reified as an object that can be **cancelled** — which is exactly what preemption needs. |

**SOLID mapping:** SRP — light holds state, strategy picks order, controller coordinates time and safety. OCP — new cycle policy or observer = new class, zero controller edits. LSP — any `CycleStrategy` is substitutable. ISP — observers see one tiny method. DIP — controller depends on the `CycleStrategy` abstraction (constructor-injected; in Spring this would be a `@Component` strategy bean injected into the controller bean rather than `new RoundRobinCycleStrategy()`).

### The two decisions an interviewer will probe

1. **Why does the controller own all transitions instead of each light timing itself?** Because the safety invariant spans lights. A per-light timer design has N writers and no owner of "only one green"; the mediator design has exactly one writer, so the invariant is checkable in one place (`assertAllOthersRed`) before every green. This is the difference between *hoping* the system is safe and *enforcing* it.
2. **How does preemption avoid corrupting the cycle?** Three mechanisms working together: a single-threaded scheduler (transitions never interleave), one `ReentrantLock` shared by scheduler and external threads (no torn state), and a **generation fencing token** that turns any "cancelled too late" task into a no-op. Section 5 argues this is race- and deadlock-free.

---

## Step 4 — Implementation

Compiles under `javac --release 11`; demo run output is included at the end of this section.


### `SignalState.java`

```java
/**
 * The lifecycle of a single traffic light, modeled as an enum-based State pattern.
 * Each constant knows its own successor, so transition logic lives WITH the state,
 * not in a switch statement scattered around the controller (Open/Closed).
 *
 * Cycle: RED -> GREEN -> YELLOW -> RED
 * (Green follows red; yellow is the warning on the way back to red.)
 */
public enum SignalState {
    RED {
        @Override public SignalState next() { return GREEN; }
    },
    GREEN {
        @Override public SignalState next() { return YELLOW; }
    },
    YELLOW {
        @Override public SignalState next() { return RED; }
    };

    /** The state that follows this one in the normal cycle. */
    public abstract SignalState next();

    /** RED is the only state in which crossing traffic may safely move. */
    public boolean allowsConflictingTraffic() { return this == RED; }
}
```

### `Direction.java`

```java
/** The approach roads of the intersection. Adding a road = adding a constant. */
public enum Direction {
    NORTH, EAST, SOUTH, WEST
}
```

### `TrafficLightObserver.java`

```java
/**
 * Observer: anything that must react when a light changes (display boards,
 * audit logs, vehicle-count telemetry) implements this. The controller and
 * lights know nothing about WHO is listening (Dependency Inversion).
 */
public interface TrafficLightObserver {
    void onStateChange(Direction direction, SignalState newState);
}
```

### `TrafficLight.java`

```java
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One physical light at the intersection. It is a "dumb" device:
 * it holds state and durations, and notifies observers — it does NOT
 * decide when to change. All transitions are commanded by the
 * IntersectionController (single-writer principle), which is why
 * setState is package-private.
 */
public class TrafficLight {

    private final Direction direction;

    /** volatile: written only by the controller thread, read by any thread (displays, monitors). */
    private volatile SignalState state = SignalState.RED;

    /**
     * Configurable, runtime-adjustable durations (requirement #3).
     * ConcurrentHashMap so an operator/sensor thread can adjust while the
     * scheduler thread reads — the change simply applies from the next cycle.
     */
    private final Map<SignalState, Long> durationsMillis = new ConcurrentHashMap<>();

    /** CopyOnWriteArrayList: iteration during notification is lock-free and safe vs. concurrent subscribe. */
    private final List<TrafficLightObserver> observers = new CopyOnWriteArrayList<>();

    public TrafficLight(Direction direction, long greenMillis, long yellowMillis) {
        this.direction = Objects.requireNonNull(direction);
        setDuration(SignalState.GREEN, greenMillis);
        setDuration(SignalState.YELLOW, yellowMillis);
        // Note: RED has no configured duration. A light is red whenever it is
        // not its turn — red time is DERIVED from everyone else's green+yellow.
    }

    public Direction getDirection() { return direction; }

    public SignalState getState() { return state; }

    public long getDurationMillis(SignalState s) {
        return durationsMillis.getOrDefault(s, 0L);
    }

    /** Runtime tuning hook: lengthen green at rush hour, etc. Takes effect next cycle. */
    public void setDuration(SignalState s, long millis) {
        if (s == SignalState.RED) {
            throw new IllegalArgumentException("RED duration is derived, not configured");
        }
        if (millis <= 0) {
            throw new IllegalArgumentException("Duration must be positive: " + millis);
        }
        durationsMillis.put(s, millis);
    }

    public void addObserver(TrafficLightObserver o) { observers.add(o); }

    /** Package-private: ONLY IntersectionController may mutate state. */
    void setState(SignalState newState) {
        this.state = newState;
        for (TrafficLightObserver o : observers) {
            try {
                o.onStateChange(direction, newState);
            } catch (RuntimeException e) {
                // A broken display must never stall the signal cycle.
                System.err.println("Observer failed: " + e.getMessage());
            }
        }
    }
}
```

### `CycleStrategy.java`

```java
import java.util.List;

/**
 * Strategy: "which road gets green next" is the rule most likely to change
 * (round-robin today; traffic-density-weighted tomorrow). Isolating it lets
 * us swap policies without touching the controller (Open/Closed).
 */
public interface CycleStrategy {
    /**
     * @param current the road that just finished its green/yellow phase (null at startup)
     * @param order   the roads of this intersection, in installation order
     * @return the road to turn green next
     */
    Direction nextGreen(Direction current, List<Direction> order);
}
```

### `RoundRobinCycleStrategy.java`

```java
import java.util.List;

/** Default policy: serve roads in fixed rotation. Stateless, hence trivially thread-safe. */
public class RoundRobinCycleStrategy implements CycleStrategy {
    @Override
    public Direction nextGreen(Direction current, List<Direction> order) {
        if (order.isEmpty()) throw new IllegalStateException("Intersection has no roads");
        if (current == null) return order.get(0);
        int idx = order.indexOf(current);
        return order.get((idx + 1) % order.size());
    }
}
```

### `EmergencyRequest.java`

```java
import java.util.Objects;

/**
 * Immutable value object describing an emergency preemption request.
 * Immutability means it can cross threads (sensor thread -> controller) freely.
 */
public final class EmergencyRequest {

    public enum VehicleType { AMBULANCE, FIRE_TRUCK, POLICE }

    private final Direction direction;
    private final VehicleType vehicleType;
    private final long reportedAtMillis;

    public EmergencyRequest(Direction direction, VehicleType vehicleType) {
        this.direction = Objects.requireNonNull(direction);
        this.vehicleType = Objects.requireNonNull(vehicleType);
        this.reportedAtMillis = System.currentTimeMillis();
    }

    public Direction getDirection() { return direction; }
    public VehicleType getVehicleType() { return vehicleType; }
    public long getReportedAtMillis() { return reportedAtMillis; }

    @Override public String toString() {
        return vehicleType + " from " + direction;
    }
}
```

### `UnsafeSignalStateException.java`

```java
/**
 * Thrown when a commanded transition would violate the safety invariant
 * (two conflicting roads simultaneously non-red). Unchecked and deliberately
 * loud: this is a bug, not a recoverable condition — fail fast, all-red.
 */
public class UnsafeSignalStateException extends RuntimeException {
    public UnsafeSignalStateException(String message) {
        super(message);
    }
}
```

### `IntersectionController.java`

```java
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The brain of one intersection (Facade + Mediator over the lights).
 *
 * CONCURRENCY MODEL — the part to defend in an interview:
 *  - A SINGLE-threaded ScheduledExecutorService drives all timed transitions,
 *    so transitions can never interleave with each other.
 *  - External events (reportEmergency, emergencyCleared, shutdown) arrive on
 *    arbitrary threads. Every state-touching method acquires `lock`, so the
 *    scheduler thread and external threads are mutually excluded.
 *  - `generation` is a fencing token: every preemption/cancel bumps it, and a
 *    scheduled task aborts if the generation it captured is stale. This closes
 *    the classic race where cancel() arrives after a task has already started.
 *
 * SAFETY INVARIANT: at most one light is non-RED at any instant. Enforced in
 * turnGreen() by checking every other light is RED before commanding green.
 */
public class IntersectionController {

    private enum Mode {
        NORMAL,                  // ordinary round-robin cycling
        CLEARING_FOR_EMERGENCY,  // yellow-then-red on current green, emergency pending
        EMERGENCY_HOLD           // emergency road is green, held until cleared
    }

    private final Map<Direction, TrafficLight> lights = new EnumMap<>(Direction.class);
    private final List<Direction> roadOrder;
    private final CycleStrategy cycleStrategy;

    /** Single thread => all timed transitions are naturally serialized. */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "intersection-controller");
                t.setDaemon(false);
                return t;
            });

    private final ReentrantLock lock = new ReentrantLock();

    // ----- state guarded by `lock` -----
    private Mode mode = Mode.NORMAL;
    private Direction currentActive;            // the road that is GREEN or YELLOW (null = all red)
    private ScheduledFuture<?> pendingTransition;
    private long generation = 0;                // fencing token for stale scheduled tasks
    private final Deque<EmergencyRequest> emergencies = new ArrayDeque<>(); // FIFO
    private boolean running = false;

    public IntersectionController(List<TrafficLight> trafficLights, CycleStrategy cycleStrategy) {
        if (trafficLights == null || trafficLights.size() < 2) {
            throw new IllegalArgumentException("An intersection needs at least 2 roads");
        }
        for (TrafficLight l : trafficLights) {
            if (lights.put(l.getDirection(), l) != null) {
                throw new IllegalArgumentException("Duplicate light for " + l.getDirection());
            }
        }
        this.roadOrder = List.copyOf(lights.keySet());
        this.cycleStrategy = cycleStrategy;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void start() {
        lock.lock();
        try {
            if (running) throw new IllegalStateException("Controller already started");
            running = true;
            lights.values().forEach(l -> l.setState(SignalState.RED)); // known-safe baseline
            Direction first = cycleStrategy.nextGreen(null, roadOrder);
            turnGreen(first);
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            running = false;
            cancelPending();
            // Fail safe: a dead controller leaves every road red (or flashing-red in the real world).
            lights.values().forEach(l -> l.setState(SignalState.RED));
        } finally {
            lock.unlock();
        }
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Normal timed cycle: GREEN --(greenMs)--> YELLOW --(yellowMs)--> RED -> next road
    // ------------------------------------------------------------------

    /** Must be called with lock held. */
    private void turnGreen(Direction d) {
        assertAllOthersRed(d); // SAFETY INVARIANT — fail loudly, never show two greens
        TrafficLight light = lights.get(d);
        currentActive = d;
        light.setState(SignalState.GREEN);
        schedule(() -> toYellow(d), light.getDurationMillis(SignalState.GREEN));
    }

    private void toYellow(Direction d) {
        TrafficLight light = lights.get(d);
        light.setState(SignalState.YELLOW);
        schedule(() -> toRedAndAdvance(d), light.getDurationMillis(SignalState.YELLOW));
    }

    /** Yellow has elapsed: go red, then route based on mode. */
    private void toRedAndAdvance(Direction d) {
        lights.get(d).setState(SignalState.RED);
        currentActive = null; // brief all-red interval is intentional: it's the safety margin

        if (mode == Mode.CLEARING_FOR_EMERGENCY && !emergencies.isEmpty()) {
            beginEmergencyHold();
        } else {
            mode = Mode.NORMAL;
            turnGreen(cycleStrategy.nextGreen(d, roadOrder));
        }
    }

    // ------------------------------------------------------------------
    // Emergency preemption (requirement #5)
    // ------------------------------------------------------------------

    /** Called from ANY thread (sensor, operator console). */
    public void reportEmergency(EmergencyRequest request) {
        lock.lock();
        try {
            if (!running) throw new IllegalStateException("Controller is not running");
            emergencies.addLast(request);
            if (mode != Mode.NORMAL) {
                return; // already clearing/holding; request waits its turn in FIFO order
            }
            mode = Mode.CLEARING_FOR_EMERGENCY;

            if (currentActive == null) {
                // We're inside the brief all-red gap; serve immediately.
                beginEmergencyHold();
                return;
            }
            TrafficLight active = lights.get(currentActive);
            if (currentActive == request.getDirection()
                    && active.getState() == SignalState.GREEN) {
                // Lucky case: the emergency road is ALREADY green. Don't cycle it
                // through yellow/red — just cancel the timer and hold the green.
                cancelPending();
                beginEmergencyHold();
                return;
            }
            if (active.getState() == SignalState.GREEN) {
                // Cut green short — but NEVER skip yellow: drivers need the warning.
                cancelPending();
                toYellow(currentActive);
            }
            // If already YELLOW: let it finish; toRedAndAdvance() will route to the emergency.
        } finally {
            lock.unlock();
        }
    }

    /** Must be called with lock held. Turns the emergency road green with NO timer. */
    private void beginEmergencyHold() {
        EmergencyRequest req = emergencies.peekFirst();
        mode = Mode.EMERGENCY_HOLD;
        System.out.println(">>> EMERGENCY PREEMPTION: holding green for " + req);
        if (currentActive != null && currentActive == req.getDirection()) {
            // Emergency road is somehow already active — just hold it.
            lights.get(req.getDirection()).setState(SignalState.GREEN);
        } else {
            turnGreenNoTimer(req.getDirection());
        }
    }

    private void turnGreenNoTimer(Direction d) {
        assertAllOthersRed(d);
        currentActive = d;
        lights.get(d).setState(SignalState.GREEN);
        // No scheduled end: we hold until emergencyCleared(). A production system
        // would also schedule a max-hold watchdog here (see Edge Cases).
    }

    /** Called from ANY thread once the emergency vehicle has passed through. */
    public void emergencyCleared() {
        lock.lock();
        try {
            if (mode != Mode.EMERGENCY_HOLD) {
                return; // idempotent: spurious/duplicate clears are ignored
            }
            EmergencyRequest done = emergencies.pollFirst();
            System.out.println(">>> EMERGENCY CLEARED: " + done);
            // Wind down via the same safe path either way: yellow -> red -> route.
            // toRedAndAdvance() then serves the next queued emergency if one exists,
            // or falls through to NORMAL cycling if the queue is empty.
            mode = Mode.CLEARING_FOR_EMERGENCY;
            toYellow(currentActive);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Runtime tuning (requirement #3)
    // ------------------------------------------------------------------

    public void adjustDuration(Direction d, SignalState state, long millis) {
        TrafficLight light = lights.get(d);
        if (light == null) throw new IllegalArgumentException("No light for " + d);
        light.setDuration(state, millis); // validated inside; applies from next cycle
    }

    public Map<Direction, SignalState> snapshot() {
        Map<Direction, SignalState> snap = new EnumMap<>(Direction.class);
        lights.forEach((d, l) -> snap.put(d, l.getState()));
        return Collections.unmodifiableMap(snap);
    }

    // ------------------------------------------------------------------
    // Scheduling plumbing
    // ------------------------------------------------------------------

    /**
     * Wraps every timed transition with (a) the lock and (b) a generation check,
     * so a task that was cancelled "too late" becomes a harmless no-op.
     * Must be called with lock held.
     */
    private void schedule(Runnable transition, long delayMillis) {
        final long myGeneration = ++generation;
        pendingTransition = scheduler.schedule(() -> {
            lock.lock();
            try {
                if (!running || myGeneration != generation) {
                    return; // stale task: a preemption/shutdown superseded us
                }
                transition.run();
            } finally {
                lock.unlock();
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    /** Must be called with lock held. */
    private void cancelPending() {
        generation++; // fences out the task even if cancel() loses the race
        if (pendingTransition != null) {
            pendingTransition.cancel(false);
            pendingTransition = null;
        }
    }

    /** Must be called with lock held. The non-negotiable safety check. */
    private void assertAllOthersRed(Direction aboutToGoGreen) {
        for (TrafficLight l : lights.values()) {
            if (l.getDirection() != aboutToGoGreen && l.getState() != SignalState.RED) {
                throw new UnsafeSignalStateException(
                        "Refusing to turn " + aboutToGoGreen + " green while "
                                + l.getDirection() + " is " + l.getState());
            }
        }
    }
}
```

### `TrafficSignalDemo.java`

```java
import java.util.List;

/**
 * Demo: 4-road intersection, fast clock so the run finishes in seconds.
 * Shows: normal cycling -> runtime duration tuning -> emergency preemption
 * (reported from a different thread) -> resume.
 */
public class TrafficSignalDemo {

    /** Observer #1: the roadside display. */
    static class ConsoleDisplay implements TrafficLightObserver {
        @Override public void onStateChange(Direction d, SignalState s) {
            System.out.printf("[%-6s] -> %s%n", d, s);
        }
    }

    public static void main(String[] args) throws Exception {
        TrafficLightObserver display = new ConsoleDisplay();

        List<TrafficLight> lights = List.of(
                new TrafficLight(Direction.NORTH, 600, 200),
                new TrafficLight(Direction.EAST,  600, 200),
                new TrafficLight(Direction.SOUTH, 600, 200),
                new TrafficLight(Direction.WEST,  600, 200));
        lights.forEach(l -> l.addObserver(display));

        IntersectionController controller =
                new IntersectionController(lights, new RoundRobinCycleStrategy());

        System.out.println("=== start: normal round-robin cycling ===");
        controller.start();
        Thread.sleep(2000);

        System.out.println("=== runtime tuning: NORTH green doubled (rush hour) ===");
        controller.adjustDuration(Direction.NORTH, SignalState.GREEN, 1200);

        // Emergency reported from a DIFFERENT thread, mid-cycle.
        Thread sensor = new Thread(() -> {
            try {
                Thread.sleep(500);
                System.out.println("=== sensor thread: ambulance approaching from WEST ===");
                controller.reportEmergency(
                        new EmergencyRequest(Direction.WEST, EmergencyRequest.VehicleType.AMBULANCE));
                Thread.sleep(1500); // vehicle takes 1.5s to pass through
                controller.emergencyCleared();
            } catch (InterruptedException ignored) { }
        }, "roadside-sensor");
        sensor.start();
        sensor.join();

        Thread.sleep(2000); // watch normal cycling resume
        System.out.println("=== final snapshot: " + controller.snapshot() + " ===");
        controller.shutdown();
        System.out.println("=== shutdown: all red ===");
    }
}
```

### Demo run output (actual — compiled with `javac --release 11` and executed)

```text
=== start: normal round-robin cycling ===
[NORTH ] -> RED
[EAST  ] -> RED
[SOUTH ] -> RED
[WEST  ] -> RED
[NORTH ] -> GREEN
[NORTH ] -> YELLOW
[NORTH ] -> RED
[EAST  ] -> GREEN
[EAST  ] -> YELLOW
[EAST  ] -> RED
[SOUTH ] -> GREEN
=== runtime tuning: NORTH green doubled (rush hour) ===
[SOUTH ] -> YELLOW
[SOUTH ] -> RED
[WEST  ] -> GREEN
=== sensor thread: ambulance approaching from WEST ===
>>> EMERGENCY PREEMPTION: holding green for AMBULANCE from WEST
[WEST  ] -> GREEN
>>> EMERGENCY CLEARED: AMBULANCE from WEST
[WEST  ] -> YELLOW
[WEST  ] -> RED
[NORTH ] -> GREEN
[NORTH ] -> YELLOW
[NORTH ] -> RED
[EAST  ] -> GREEN
=== final snapshot: {NORTH=RED, EAST=GREEN, SOUTH=RED, WEST=RED} ===
[NORTH ] -> RED
[EAST  ] -> RED
[SOUTH ] -> RED
[WEST  ] -> RED
=== shutdown: all red ===
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input & defensive boundaries

| Case | Handling |
|---|---|
| Non-positive or RED duration in `setDuration` | `IllegalArgumentException` — RED is derived, never configured |
| Duplicate light for a direction / fewer than 2 roads | `IllegalArgumentException` at construction — fail at wiring time, not at 2 a.m. |
| `reportEmergency` before `start()` / after `shutdown()` | `IllegalStateException` — the controller refuses work when it cannot honor it |
| `adjustDuration` for an unknown direction | `IllegalArgumentException` |
| Throwing observer (broken display) | Caught and logged inside `setState` — a failed display must never stall the safety-critical cycle. Isolating observer failures is the standard Observer hardening. |
| Safety invariant about to break | `UnsafeSignalStateException`, unchecked and loud. This is a *bug*, not a recoverable condition; production behavior would be fail-safe to flashing-red and page an operator. |

### Edge cases

- **Emergency while the emergency road is already green** → don't cycle it through yellow/red; cancel the pending timer and hold the green directly (handled explicitly in `reportEmergency`).
- **Emergency during the all-red gap** (`currentActive == null`) → serve immediately; no wind-down needed.
- **Emergency during YELLOW** → let yellow finish (drivers are mid-warning); `toRedAndAdvance` routes to the emergency instead of the next round-robin road.
- **Second emergency during an emergency hold** → enqueued FIFO; on `emergencyCleared()` the current hold winds down via yellow and the next request is served. The wind-down path is *the same code* for "next emergency" and "resume normal" — `toRedAndAdvance` just consults the queue.
- **Duplicate/spurious `emergencyCleared()`** → idempotent: ignored unless mode is `EMERGENCY_HOLD`.
- **Duration changed mid-phase** → applies from the *next* time that state is entered. Retroactively shortening a live green would mean cancelling and rescheduling a timer for marginal benefit — a trade-off to state out loud, not hide.
- **Degenerate config** (very short durations) → still safe, just frantic; validation rejects only non-positive values. A production system would enforce minimum yellow times (jurisdictional law, typically 3–6 s).
- **Missing in this scope, named deliberately**: a max-hold **watchdog** on emergency green (a stuck sensor must not freeze the intersection forever — schedule a timeout that force-clears with an alert), and **all-red clearance intervals** of configurable length (we get a brief all-red between phases naturally; real intersections make it an explicit 1–2 s).

### Concurrency analysis (the section to be able to defend cold)

**Shared mutable state:** `mode`, `currentActive`, `pendingTransition`, `generation`, the `emergencies` deque (all in the controller), plus each light's `state` and `durationsMillis`.

**Threads touching it:** the single scheduler thread (timed transitions) and any number of external threads (`reportEmergency`, `emergencyCleared`, `adjustDuration`, `shutdown`).

**Critical sections & primitives chosen:**

1. **One `ReentrantLock` around every state-touching method.** Both the scheduled task bodies and the public API acquire it, so a timed transition and an emergency report are strictly serialized. Why a lock and not finer-grained atomics? The state is a small *cluster* of fields that must change together (`mode` + `currentActive` + queue); per-field atomics would reintroduce torn intermediate states. Coarse lock + tiny critical sections (microseconds; all waiting happens in the scheduler, never while holding the lock) is the right trade.
2. **Single-threaded `ScheduledExecutorService`.** Even if two timers misfire close together, their bodies run serially. The scheduler is also the *only* writer of light states during normal operation — the single-writer principle that made the parking-lot design defensible reappears here.
3. **Generation fencing token for cancellation races.** `cancel(false)` can lose the race with a task that has already been dequeued and is blocked on the lock. Every preemption bumps `generation`; every scheduled task captured its generation at schedule time and aborts if stale. So the worst case is a harmless no-op, never a double transition. (This is the in-memory miniature of fencing tokens in distributed locks — same idea ZooKeeper/etcd clients use.)
4. **`volatile` light state + `ConcurrentHashMap` durations.** Readers (displays, `snapshot()`) get fresh values without acquiring the controller lock; duration tuning from another thread publishes safely and takes effect next cycle.
5. **`CopyOnWriteArrayList` for observers.** Notification iterates lock-free; subscription is rare. Classic read-mostly fit.

**Why no deadlock:** there is exactly one lock in the system — deadlock needs at least two locks acquired in inconsistent order. Observer callbacks execute while holding it (acceptable here because observers are notify-only; a production variant would dispatch notifications onto a separate executor to drop even that risk — say this trade-off out loud).

**Why no livelock/starvation:** the scheduler queue is FIFO and single-threaded; emergencies are FIFO; `ReentrantLock` in default unfair mode with microsecond hold times makes starvation practically impossible (and `new ReentrantLock(true)` is the one-line answer if pressed).

**Why no race on the safety invariant:** lights only change inside the lock, on commands issued by one mediator, and `assertAllOthersRed` runs *inside the same critical section* as the green command — check and act are atomic with respect to each other.

---

## Likely Interviewer Follow-ups

**1. "Real intersections run North and South green together. Extend the design."**
Introduce a `Phase` (a set of non-conflicting directions) and make the controller cycle over phases instead of directions: `turnGreen(Phase)` commands every member light, and the invariant becomes "all lights outside the active phase are RED." `CycleStrategy` returns the next `Phase`. The controller's timing/preemption machinery is untouched — which is the payoff of separating *what turns green* (strategy) from *when and how safely* (mediator).

**2. "Make green duration adapt to traffic density automatically."**
Two seams already exist: a sensor-driven component calls `adjustDuration(...)` (closed-loop tuning), or you swap in an `AdaptiveCycleStrategy` that consults a `TrafficDensityProvider` to pick the next road and could also suggest durations. No controller changes — Strategy + the runtime-tuning API absorb the requirement.

**3. "What if two emergencies arrive from different roads at once?"**
Both land in the FIFO deque under the lock, so arrival order is well-defined. The first is served; the second is held; `emergencyCleared()` chains to it through the same yellow→red wind-down. If priorities matter (fire > ambulance), replace the `ArrayDeque` with a `PriorityQueue<EmergencyRequest>` ordered by vehicle type then timestamp — a one-field change, because the queue is encapsulated.

**4. "Scale to 100 intersections with coordinated green waves."**
Each intersection keeps its own controller (local safety must never depend on the network). Add a coordinator that *offsets* cycle start times along a corridor — i.e., it calls `adjustDuration` and a new `setPhaseOffset` API rather than commanding individual lights. Key argument: safety stays local and autonomous; coordination is an optimization layer on top, degradable to independent operation if the network partitions.

**5. "How would you unit-test time-based transitions without sleeping?"**
The design's testability hinge is that *all* timing flows through `schedule()`. Extract a `TransitionScheduler` interface, inject a deterministic fake that captures `(task, delay)` pairs and lets the test fire them manually: `fake.advance(5000)` → assert NORTH is YELLOW. Same technique as injecting a `Clock` — never test concurrency with `Thread.sleep` assertions.

---

## Transferable Lessons

- **Timer-driven State + cancellable transitions** is a reusable trio: single-threaded scheduler (serialize), one lock (mutual exclusion with external events), generation token (kill cancellation races). You will reuse it nearly verbatim in **Elevator** (moving between floors on a clock, preempted by new requests) and in the rate-limiter/scheduler problems in the Concurrency tier.
- **Invariants that span objects need a Mediator.** "Only one green" is the same shape as "an elevator door never opens between floors" or "a chess move must leave your king safe" — never let the parts self-coordinate.
- **Preempt via the safe path, not the fast path.** Emergency handling reuses the normal yellow→red wind-down instead of slamming lights around. Reusing the validated transition path for exceptional flows is a pattern that recurs in order-cancellation, payment-reversal, and rollback designs.
- **Fencing tokens** (the `generation` counter) are how grown-up systems neutralize "cancelled too late" — remember the name; it transfers directly to distributed-lock interview questions.

## Progress & Next Problem

✅ Foundations → ✅ Parking Lot → ✅ Vending Machine → ✅ **Traffic Signal** (this document)

**Next: Elevator System** — the natural escalation: same timer-driven State machine, but now with a *request-scheduling* problem layered on top (which floor to serve next → SCAN/LOOK strategies), richer state (direction + door), and genuinely harder preemption. It is also one of the highest-frequency interview asks in the entire set.
