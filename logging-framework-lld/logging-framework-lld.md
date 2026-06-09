# Low-Level Design: Logging Framework (Java)

---

## Step 1 — Requirements

### Functional Requirements

1. **Ordered log levels**: `DEBUG < INFO < WARNING < ERROR < FATAL`. A message is emitted only if its level is **≥** the configured minimum level. The ordering (not just the labels) is the core insight — levels form a severity hierarchy used for filtering.
2. **Structured log record**: every entry carries a timestamp, level, message, and the emitting thread's name.
3. **Multiple simultaneous destinations (appenders)**: console, file, database — with fan-out (one log call can go to several destinations at once).
4. **Runtime configuration**: minimum level and the active appender set are changeable at runtime without restart, via a config object.
5. **Simple client API**: `logger.info("...")`, `logger.error("...")` etc. The client never builds a `LogRecord` or touches appenders directly.

### Non-Functional Requirements

1. **Thread safety**: concurrent logging from many threads must not corrupt/interleave output; a config swap mid-flight must never crash an in-progress log call.
2. **Extensibility (Open/Closed)**: a new destination (e.g., Kafka) or a new level is *added*, never patched into existing classes via `if/else`.
3. **Low hot-path overhead**: a `debug()` call below the threshold should be near-free (one volatile read + one enum comparison).
4. **Graceful degradation**: a failing appender (disk full, DB down) must never propagate an exception into business code.

### Assumptions (stated to the interviewer)

- Single application-wide `Logger` (Singleton); named per-class loggers are an extension, not v1.
- Synchronous logging in v1; an async appender is provided as a **Decorator** to show the upgrade path.
- Fixed line format `timestamp [LEVEL] [thread] message`; pattern layouts out of scope.
- Appender failures are caught and reported to `stderr`.

---

## Step 2 — Entities & Relationships

| Entity | Kind | Responsibility |
|---|---|---|
| `LogLevel` | enum | Severity ordering + threshold comparison |
| `LogRecord` | immutable value class | One log event (timestamp, level, thread, message) |
| `LogAppender` | interface | Strategy contract: "write this record somewhere" |
| `ConsoleAppender` / `FileAppender` / `DatabaseAppender` | concrete strategies | Destination-specific writing |
| `AsyncAppender` | decorator | Wraps any appender; makes it non-blocking via a queue + worker thread |
| `LoggerConfig` | immutable value class | min level + list of active appenders |
| `Logger` | singleton facade | Client API; filters by level, builds records, fans out to appenders |

### Relationships (with type and why)

- **`Logger` → `LoggerConfig`: aggregation.** The Logger *holds* a config, but configs are created externally and swappable at runtime — their lifecycles are independent. (Composition would imply the config dies with the Logger and can't be replaced wholesale.)
- **`LoggerConfig` → `LogAppender`: aggregation.** The config references appenders; appenders (e.g., a shared `FileAppender`) may outlive any one config — two successive configs can share the same appender instance.
- **`ConsoleAppender` / `FileAppender` / `DatabaseAppender` → `LogAppender`: realization (implements).** Classic Strategy family.
- **`AsyncAppender` → `LogAppender`: realization **and** composition.** It *is* an appender and it *wraps/owns* its delegate's queue and worker thread (the worker dies when the AsyncAppender closes) — the signature shape of a Decorator.
- **`Logger` → `LogRecord`: dependency.** The Logger creates records transiently inside `log()`; it never stores them. (A common over-modeling mistake is giving the Logger a list of past records — that's a memory leak, not a feature.)
- **`LogAppender` → `LogRecord`: dependency.** Appenders consume records as method parameters only.

**Common mistakes here:** modeling `LogLevel` as a class hierarchy (it's a closed, ordered set → enum); making each appender a singleton (you legitimately want two `FileAppender`s pointing at different files); storing log history inside the Logger.

---

## Step 3 — Class Design

### Text UML

```
                 «enum»
               LogLevel
   DEBUG, INFO, WARNING, ERROR, FATAL
   + isAtLeast(threshold: LogLevel): boolean

 «immutable»                       «interface»
 LogRecord                         LogAppender
 - timestamp: Instant              + append(r: LogRecord)
 - level: LogLevel                 + close()
 - threadName: String                    △
 - message: String                       │ implements
 + format(): String        ┌────────────┼──────────────┬───────────────┐
                           │            │              │               │
                   ConsoleAppender  FileAppender  DatabaseAppender  AsyncAppender
                                    - writer (sync'd) - dataSource   - delegate: LogAppender  ◄─ wraps (Decorator)
                                                                     - queue: BlockingQueue<LogRecord>
                                                                     - worker: Thread

 «immutable»                          «singleton»
 LoggerConfig                         Logger
 - minLevel: LogLevel                 - volatile config: LoggerConfig
 - appenders: List<LogAppender> (unmodifiable)
 + minLevel(), appenders()            + getInstance(): Logger
                                      + setConfig(c: LoggerConfig)
 Logger ◇──── 1 LoggerConfig          + debug/info/warning/error/fatal(msg)
 LoggerConfig ◇──── * LogAppender     - log(level, msg)   // filter → build record → fan out
```

Multiplicity: one `Logger` ◇— one `LoggerConfig` ◇— many (`*`) `LogAppender`s.

### Design Patterns and *why each fits*

1. **Strategy (`LogAppender`)** — the *algorithm for writing* varies by destination while the calling code (`Logger.log`) is identical. New destination = new class implementing the interface; the Logger is closed for modification. This is the load-bearing pattern of the whole design.
2. **Singleton (`Logger`)** — logging needs one globally consistent configuration point; two independent Loggers with different thresholds writing to the same file is incoherent. Implemented via holder idiom (lazy + thread-safe without locking). *Spring note:* in a Spring app you'd never hand-roll this — a `@Component` with default singleton scope gives you the same guarantee with testability; mention that trade-off if asked.
3. **Decorator (`AsyncAppender`)** — adds the *behavior* "non-blocking, queued" to **any** existing appender without subclassing each one (no `AsyncFileAppender`, `AsyncConsoleAppender` class explosion). It implements the same interface it wraps — the defining Decorator property.
4. **Facade (the `Logger` API)** — `info(msg)` hides record construction, filtering, and fan-out behind one method.
5. **Chain of Responsibility — considered and rejected.** The canonical repo solution chains `DebugHandler → InfoHandler → ...`, each deciding to handle/pass. With levels that are a *totally ordered enum*, a single `level.isAtLeast(min)` comparison does the same job with zero classes. CoR earns its complexity only when handlers are heterogeneous (e.g., route ERROR to PagerDuty, INFO to file). Saying *why you rejected a pattern* scores higher than using it.

### SOLID mapping

- **S**: `LogRecord` = data, appenders = transport, `Logger` = orchestration/filtering, `LoggerConfig` = settings. Each changes for exactly one reason.
- **O**: new destinations/decorators are added without touching `Logger`.
- **L**: any `LogAppender` is substitutable anywhere one is expected — `AsyncAppender` proves it by wrapping arbitrary delegates.
- **I**: the appender interface is two methods; no implementor is forced to stub irrelevant methods.
- **D**: `Logger` depends on the `LogAppender` abstraction, never on `FileAppender` concretely.

### The two decisions an interviewer will probe

1. **How do you reconfigure safely under concurrency?** → Immutable `LoggerConfig` swapped through a single `volatile` reference. Each `log()` call reads the reference **once** into a local variable, so it sees one consistent (level, appenders) pair — never a torn mix of old level + new appenders.
2. **Sync vs. async logging?** → v1 is synchronous (ordered, simple, no lost logs on crash). The async path is the `AsyncAppender` decorator (producer–consumer over `BlockingQueue`); cost = possible loss of tail logs on hard crash + bounded-queue backpressure policy.

---

## Step 4 — Implementation

```java
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.io.*;

// ---------- LogLevel ----------
public enum LogLevel {
    DEBUG, INFO, WARNING, ERROR, FATAL;

    /** Severity comparison rides on enum ordinal — declaration order IS the contract. */
    public boolean isAtLeast(LogLevel threshold) {
        return this.ordinal() >= threshold.ordinal();
    }
}

// ---------- LogRecord (immutable => freely shareable across threads) ----------
public final class LogRecord {
    private final Instant timestamp;
    private final LogLevel level;
    private final String threadName;
    private final String message;

    public LogRecord(LogLevel level, String message) {
        this.timestamp = Instant.now();
        this.level = level;
        this.threadName = Thread.currentThread().getName();
        this.message = message;
    }

    public String format() {
        return String.format("%s [%s] [%s] %s", timestamp, level, threadName, message);
    }
    public LogLevel level() { return level; }
}

// ---------- Strategy contract ----------
public interface LogAppender {
    void append(LogRecord record);
    default void close() {}
}

// ---------- Concrete strategies ----------
public class ConsoleAppender implements LogAppender {
    @Override
    public void append(LogRecord record) {
        // println is internally synchronized on the stream => whole lines never interleave
        System.out.println(record.format());
    }
}

public class FileAppender implements LogAppender {
    private final BufferedWriter writer;
    private final Object lock = new Object(); // private lock: external code can't deadlock us

    public FileAppender(String filePath) throws IOException {
        this.writer = new BufferedWriter(new FileWriter(filePath, /*append=*/true));
    }

    @Override
    public void append(LogRecord record) {
        // Critical section: write + newline must be atomic per record,
        // or two threads' lines interleave mid-line in the file.
        synchronized (lock) {
            try {
                writer.write(record.format());
                writer.newLine();
                writer.flush(); // trade-off: durability per line vs. throughput
            } catch (IOException e) {
                throw new AppenderException("File append failed", e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try { writer.close(); } catch (IOException ignored) {}
        }
    }
}

public class DatabaseAppender implements LogAppender {
    // In a real system: a javax.sql.DataSource + INSERT. Kept as a sketch —
    // the design point is that it's just one more Strategy.
    @Override
    public void append(LogRecord record) {
        // INSERT INTO logs(ts, level, thread, message) VALUES (...)
    }
}

// ---------- Decorator: makes ANY appender asynchronous ----------
public class AsyncAppender implements LogAppender {
    private final LogAppender delegate;
    private final BlockingQueue<LogRecord> queue;
    private final Thread worker;
    private volatile boolean running = true;

    public AsyncAppender(LogAppender delegate, int capacity) {
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(capacity); // BOUNDED: unbounded queue = OOM under burst
        this.worker = new Thread(this::drain, "log-async-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void append(LogRecord record) {
        // Backpressure policy: drop on full queue. Alternative: queue.put() blocks the
        // producer (preserves every log, but logging can now stall business threads).
        if (!queue.offer(record)) {
            System.err.println("AsyncAppender queue full — dropped: " + record.format());
        }
    }

    private void drain() {
        try {
            while (running || !queue.isEmpty()) {
                LogRecord r = queue.poll(100, TimeUnit.MILLISECONDS);
                if (r != null) delegate.append(r);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        try { worker.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        delegate.close();
    }
}

// ---------- Immutable configuration ----------
public final class LoggerConfig {
    private final LogLevel minLevel;
    private final List<LogAppender> appenders;

    public LoggerConfig(LogLevel minLevel, List<LogAppender> appenders) {
        if (minLevel == null || appenders == null || appenders.isEmpty())
            throw new IllegalArgumentException("minLevel and at least one appender required");
        this.minLevel = minLevel;
        this.appenders = List.copyOf(appenders); // defensive copy + unmodifiable
    }
    public LogLevel minLevel() { return minLevel; }
    public List<LogAppender> appenders() { return appenders; }
}

// ---------- Singleton facade ----------
public final class Logger {

    // Holder idiom: lazy init, thread-safe, no synchronization on the hot path.
    private static class Holder { static final Logger INSTANCE = new Logger(); }
    public static Logger getInstance() { return Holder.INSTANCE; }

    // volatile => a config swap is published atomically to all logging threads.
    private volatile LoggerConfig config =
            new LoggerConfig(LogLevel.INFO, List.of(new ConsoleAppender()));

    private Logger() {}

    public void setConfig(LoggerConfig newConfig) { this.config = newConfig; }

    public void debug(String msg)   { log(LogLevel.DEBUG, msg); }
    public void info(String msg)    { log(LogLevel.INFO, msg); }
    public void warning(String msg) { log(LogLevel.WARNING, msg); }
    public void error(String msg)   { log(LogLevel.ERROR, msg); }
    public void fatal(String msg)   { log(LogLevel.FATAL, msg); }

    private void log(LogLevel level, String msg) {
        // Read the volatile ONCE: this call now operates on one consistent snapshot
        // (level + appenders), even if setConfig() runs concurrently.
        LoggerConfig snapshot = this.config;

        if (!level.isAtLeast(snapshot.minLevel())) return; // hot-path early exit

        LogRecord record = new LogRecord(level, msg == null ? "null" : msg);

        for (LogAppender appender : snapshot.appenders()) {
            try {
                appender.append(record);          // fan-out
            } catch (RuntimeException e) {
                // Logging must NEVER take down business code. One bad appender
                // must not block the others either — hence per-appender try/catch.
                System.err.println("Appender failed: " + e.getMessage());
            }
        }
    }
}

// ---------- Framework exception ----------
public class AppenderException extends RuntimeException {
    public AppenderException(String message, Throwable cause) { super(message, cause); }
}

// ---------- Demo ----------
class Demo {
    public static void main(String[] args) throws Exception {
        Logger log = Logger.getInstance();
        log.debug("invisible — below INFO threshold");
        log.info("application started");

        LogAppender file = new AsyncAppender(new FileAppender("app.log"), 10_000);
        log.setConfig(new LoggerConfig(LogLevel.DEBUG, List.of(new ConsoleAppender(), file)));

        Runnable task = () -> { for (int i = 0; i < 5; i++) log.info("work item " + i); };
        Thread t1 = new Thread(task, "worker-1");
        Thread t2 = new Thread(task, "worker-2");
        t1.start(); t2.start(); t1.join(); t2.join();

        log.error("something failed");
        file.close();
    }
}
```

**Spring Boot aside (one line each):** the hand-rolled Singleton becomes a default-scoped `@Component`; appenders become beans injected as `List<LogAppender>` (Spring auto-collects all implementations — DI doing the Strategy wiring for you); `LoggerConfig` maps to `@ConfigurationProperties`.

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input & boundary conditions

| Case | Handling |
|---|---|
| `null` message | Coerced to `"null"` — logging a null must never throw. |
| Empty appender list / null level in config | `LoggerConfig` constructor rejects it (fail fast at config time, not log time). |
| Appender throws mid-fan-out | Per-appender `try/catch`: remaining appenders still receive the record. |
| Disk full / DB down | `AppenderException` is caught in `Logger.log`, reported to stderr; app continues. |
| Async queue full (burst) | Bounded queue + explicit policy (drop & report). The *wrong* answer is an unbounded queue — that converts a logging burst into an OutOfMemoryError. |
| Shutdown with queued records | `AsyncAppender.close()` flips the flag, drains the queue, joins the worker with a timeout. |

### Concurrency analysis (the part to be able to argue out loud)

**Shared mutable state — there are exactly three pieces:**
1. `Logger.config` reference
2. `FileAppender.writer`
3. `AsyncAppender.queue` (+ `running` flag)

**Synchronization choice per piece, and why:**

1. **Config: immutability + `volatile`, no locks.** `LoggerConfig` is deeply immutable, so threads can read it freely. Replacing it is a single volatile reference write — atomic by the JMM, and `volatile` gives the happens-before edge so readers see a fully constructed config, never a half-built one. Reading it *once per `log()` call* into a local eliminates the torn-read hazard (old level paired with new appenders). This "immutable snapshot behind a volatile" idiom is the cheapest correct answer to read-mostly shared config, and it reappears in many LLD problems. (Alternative: `CopyOnWriteArrayList` of appenders — same philosophy, but it allows incremental mutation; the whole-object swap keeps level+appenders consistent *together*.)
2. **File writes: `synchronized` on a private lock.** The critical section is `write + newLine + flush` — it must be atomic per record or lines interleave mid-line. A *private* lock object (not `this`) means no external code can synchronize on our appender and create a lock-ordering hazard.
3. **Async hand-off: `ArrayBlockingQueue`.** Producer–consumer is exactly what `BlockingQueue` is for; it owns all of its own synchronization, and bounding it gives a deliberate backpressure point.

**Freedom-from arguments:**
- **Race conditions:** every shared field is either immutable, volatile-published, lock-guarded, or a concurrent collection. No unguarded read-modify-write exists.
- **Deadlock:** no code path ever holds two locks at once (the file lock is leaf-level; the queue's internal lock never nests with it), and locks are private — circular wait is structurally impossible.
- **Livelock/starvation:** no retry loops; `synchronized` and `ArrayBlockingQueue` queue waiters fairly enough for this workload; the drain loop uses a timed `poll`, so shutdown can't hang forever.
- **Known residual hazard (say it before they do):** with multiple *synchronous* appenders, per-appender locking means global cross-appender ordering isn't strictly guaranteed under contention, and an async appender reorders relative to sync ones by design. If strict total order across destinations is required, serialize the whole fan-out through one queue/lock — trading throughput for ordering.

---

## Interviewer Follow-ups (with short model answers)

1. **"Add named, hierarchical loggers like Log4j (`com.app.service` inherits from `com.app`)."**
   Introduce a `LogManager` (registry: `ConcurrentHashMap<String, Logger>`, `computeIfAbsent` for thread-safe get-or-create). Each Logger gets a name and an optional parent; effective level = own level if set, else walk up parents. The dot-separated hierarchy is a textbook Chain of Responsibility — *here* the pattern finally earns its keep.

2. **"Logging is now your latency bottleneck — 10x the throughput."**
   Wrap appenders in `AsyncAppender` so business threads only pay an `offer()`. Then: batch DB inserts in the drain loop, enlarge file-flush intervals, and consider per-thread buffers feeding a single writer (the Log4j2 async/disruptor idea). State the trade-off: lost tail logs on hard crash.

3. **"How do you guarantee no log lines are lost on JVM shutdown?"**
   Register a shutdown hook that calls `close()` on every appender; `AsyncAppender.close()` drains the queue before returning. Be honest: `kill -9`/power loss defeats any in-process guarantee — durability beyond that needs synchronous flushing or an external agent.

4. **"Support a custom format like `%d [%t] %level - %msg`."**
   Add a `LogFormatter` interface (another Strategy) owned by each appender or by the config; `LogRecord` stays raw data, formatting moves out of `record.format()`. Demonstrates you keep data and presentation separated.

5. **"Why didn't you use Chain of Responsibility like the well-known solution?"**
   Because levels are a totally ordered enum: one comparison replaces five handler classes. CoR pays off when handlers are heterogeneous (routing by level to different sinks) or when the chain is user-extensible. Pattern choice should follow variability, not familiarity.

## Transferable Lessons

- **Strategy + interface fan-out** is the default answer whenever a behavior varies by "kind of X" (payment methods, notification channels, parking-spot pricing). It reappears in nearly every problem ahead.
- **Decorator vs. subclassing**: when a feature (async, retry, buffering) should compose with *any* implementation, wrap — don't multiply subclasses.
- **Immutable snapshot behind a `volatile`** is the cheapest correct pattern for read-mostly shared configuration; you'll reuse it in rate limiters and caches.
- **Bounded queues + an explicit overflow policy**: every producer–consumer design must answer "what happens when the consumer falls behind?"

**Next problem (Easy track):** *Design a Stack Overflow / or Parking Lot* — Parking Lot is the better next step: it layers Strategy (pricing), Factory (spot/vehicle creation), and your first real inventory-concurrency discussion on top of what you just learned here.
