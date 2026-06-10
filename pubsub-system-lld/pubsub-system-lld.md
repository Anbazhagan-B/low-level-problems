# Low Level Design — Pub-Sub System (Java)

> Scope locked in: **in-process, in-memory broker · push model · asynchronous delivery via per-subscriber queue + worker thread · per-topic FIFO ordering per subscriber · at-most-once delivery · no retention · bounded buffers**.

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| F1 | Topics can be created explicitly, or lazily on first publish/subscribe. |
| F2 | A publisher can publish a `Message` to a named topic. |
| F3 | A subscriber can subscribe to a topic and later unsubscribe. |
| F4 | Every message published to a topic is delivered to **all subscribers registered at publish time** (fan-out). |
| F5 | Multiple publishers and multiple subscribers may operate on the same topic concurrently. |
| F6 | The broker can be shut down gracefully, draining in-flight messages. |

### Non-Functional Requirements

| # | Requirement | Design consequence |
|---|-------------|--------------------|
| N1 | **Thread safety** — concurrent publish/subscribe/unsubscribe must not corrupt state or throw `ConcurrentModificationException`. | Concurrent collections; no manual locking on hot paths. |
| N2 | **Non-blocking publishers** — a slow subscriber must never stall a publisher or sibling subscribers. | Decouple publish from delivery: each subscriber owns a `BlockingQueue` + dedicated worker thread. |
| N3 | **Low latency ("real-time")** — push-based async delivery, not polling. | Broker hands message to subscriber queues immediately; workers drain continuously. |
| N4 | **Per-topic FIFO ordering** per subscriber. | Single worker thread per subscriber (one consumer per queue ⇒ order preserved). |
| N5 | **Bounded memory** — a stuck subscriber must not OOM the JVM. | Bounded queues + an explicit, pluggable overflow policy (drop / block). |
| N6 | **Extensibility** — filtering, retention, priorities should be addable without rewriting the core. | Program to interfaces (`Subscriber`), isolate policies behind a Strategy. |

### Key assumptions (stated to the interviewer)

- Delivery is **at-most-once**: the JVM is the durability boundary; if the process dies, undelivered messages are lost. (At-least-once would require persistence + acks — that's Kafka/RabbitMQ territory, out of LLD scope.)
- Late subscribers receive **only messages published after** they subscribe (no replay/retention).
- "Real-time" means *asynchronous push with minimal queuing delay*, not a hard latency SLA.

---

## Step 2 — Entities & Relationships

### Core entities

| Entity | Role | Mutability |
|--------|------|-----------|
| `Message` | The payload + metadata (id, topic name, content, timestamp). | **Immutable** — shared across threads with zero synchronization. |
| `Topic` | A named channel; owns its set of subscriptions and performs fan-out. | Mutable, thread-safe internally. |
| `Subscriber` | **Interface** — the callback contract (`onMessage`). Implemented by users of the library. | N/A (contract) |
| `Subscription` | Binds one `Subscriber` to one `Topic`; owns the delivery queue + worker thread. The unit of async delivery. | Mutable, thread-confined consumption. |
| `Publisher` | Thin convenience client that publishes through the broker. | Stateless apart from a broker reference. |
| `PubSubBroker` | The façade/registry: topic lifecycle, subscribe/unsubscribe, publish routing, shutdown. | Mutable, thread-safe. |
| `OverflowPolicy` | **Enum/Strategy** — what to do when a subscriber's bounded queue is full (`DROP_NEW`, `BLOCK_PUBLISHER`). | Stateless |

### Why `Subscription` is a separate entity (the step most candidates miss)

If `Topic` holds raw `Subscriber` references and calls `subscriber.onMessage()` directly during publish, the publisher thread executes *user code* — one slow `onMessage` blocks every other subscriber and the publisher itself (violates N2). Introducing `Subscription` as the owner of a queue + worker thread is what converts the design from synchronous Observer to an asynchronous, isolated pipeline per subscriber.

### Relationships

| Relationship | Type | Why |
|--------------|------|-----|
| `PubSubBroker` → `Topic` | **Composition** (1 → \*) | Topics are created, registered, and destroyed by the broker; they have no life outside it. Broker shutdown shuts down all topics. |
| `Topic` → `Subscription` | **Composition** (1 → \*) | A subscription exists only within a topic; unsubscribing or deleting the topic destroys it (and stops its worker). |
| `Subscription` → `Subscriber` | **Aggregation** (1 → 1) | The subscription *references* a subscriber but doesn't own its lifecycle — the same subscriber object may subscribe to many topics and outlives any one subscription. |
| `Subscription` → `Message` | **Dependency** (transient) | Messages flow through the queue; they are not structurally part of the subscription. |
| `Publisher` → `PubSubBroker` | **Association** | Publisher holds a broker reference; both have independent lifecycles. |
| `Topic` / `Subscription` → `OverflowPolicy` | **Dependency** | Consulted at enqueue time; stateless strategy. |

---

## Step 3 — UML Class Design

```
+----------------------------------------------------------+
|                    <<final>> Message                      |
+----------------------------------------------------------+
| - id        : String        (UUID)                        |
| - topicName : String                                      |
| - payload   : String                                      |
| - timestamp : Instant                                     |
+----------------------------------------------------------+
| + getters only  (immutable)                               |
+----------------------------------------------------------+

+----------------------------+
|  <<interface>> Subscriber  |
+----------------------------+
| + getId() : String         |
| + onMessage(Message) :void |
+----------------------------+
            ^
            |  implements (user code, e.g. LoggingSubscriber)

+-----------------------------------------------------------+
|                       Subscription                         |
+-----------------------------------------------------------+
| - subscriber : Subscriber                                  |
| - queue      : BlockingQueue<Message>   (bounded)          |
| - worker     : Thread                   (single consumer)  |
| - active     : volatile boolean                            |
| - policy     : OverflowPolicy                              |
+-----------------------------------------------------------+
| + deliver(Message) : boolean   // enqueue, never user code |
| + start() / stop()                                         |
| - runLoop()                    // drain queue -> onMessage |
+-----------------------------------------------------------+

+-----------------------------------------------------------+
|                          Topic                              |
+-----------------------------------------------------------+
| - name          : String                                    |
| - subscriptions : ConcurrentHashMap<String, Subscription>   |
|                   (key = subscriberId)                       |
+-----------------------------------------------------------+
| + addSubscriber(Subscriber, OverflowPolicy)                 |
| + removeSubscriber(String subscriberId)                     |
| + publish(Message)        // fan-out: enqueue to each sub   |
| + shutdown()                                                |
+-----------------------------------------------------------+

+-----------------------------------------------------------+
|              PubSubBroker   <<Singleton, Facade>>           |
+-----------------------------------------------------------+
| - topics : ConcurrentHashMap<String, Topic>                 |
| - state  : volatile RUNNING|SHUTDOWN                        |
+-----------------------------------------------------------+
| + createTopic(name)                                         |
| + subscribe(topicName, Subscriber)                          |
| + unsubscribe(topicName, subscriberId)                      |
| + publish(topicName, payload)                               |
| + shutdown()                                                |
+-----------------------------------------------------------+

+---------------------------+        +------------------------------+
|         Publisher          |        |  <<enum>> OverflowPolicy     |
+---------------------------+        +------------------------------+
| - id     : String          |        |  DROP_NEW                    |
| - broker : PubSubBroker    |        |  BLOCK_PUBLISHER             |
+---------------------------+        +------------------------------+
| + publish(topic, payload)  |
+---------------------------+

Multiplicity:  Broker 1 --* Topic 1 --* Subscription 1 --1 Subscriber
```

### Pattern → justification mapping

| Pattern | Where | Why it fits *here* (not just the name) |
|---------|-------|----------------------------------------|
| **Observer** | `Subscriber` interface + `Topic.publish` fan-out | Publishers are fully decoupled from subscribers: they share no reference, only a topic name. New subscriber types plug in by implementing one interface — the broker never changes. This is the textbook Observer intent: one-to-many notification without coupling subject to concrete observers. |
| **Producer–Consumer** | `Subscription` (`BlockingQueue` + worker thread) | The mechanism that makes Observer *asynchronous*. Publisher threads are producers, the per-subscription worker is the single consumer. The queue is the hand-off point that isolates slow consumers (N2) and preserves FIFO (N4) because there's exactly one consumer per queue. |
| **Facade** | `PubSubBroker` | Clients touch one class; topic lifecycle, subscription wiring, and thread management are hidden. Keeps the public API stable while internals evolve. |
| **Singleton** | `PubSubBroker` (lazy holder idiom) | One shared registry of topics per JVM is the natural scope. **Spring note:** in a Spring Boot app you'd drop the hand-rolled singleton and declare the broker a `@Component` — Spring's default singleton bean scope gives the same guarantee with testability (you can inject a mock broker; a static singleton you cannot). Interviewers like hearing that distinction. |
| **Strategy** | `OverflowPolicy` | Drop-vs-block when a queue fills is a *policy*, not a mechanism. Encoding it as a pluggable strategy means adding `DROP_OLDEST` or `DEAD_LETTER` later touches no core class — Open/Closed in action. |
| *(Rejected)* Factory for messages/topics | — | Construction is trivial; a factory would be ceremony. Saying *why you rejected* a pattern scores points. |

### SOLID mapping

- **S** — `Topic` routes, `Subscription` delivers, `Broker` orchestrates, `Message` carries data. Each has one reason to change.
- **O** — new subscriber behaviors (filtering, batching) and new overflow policies extend via interface/enum; core untouched.
- **L** — any `Subscriber` implementation is substitutable; `Topic` depends only on the contract.
- **I** — `Subscriber` is a minimal two-method interface; implementors aren't forced to absorb broker concerns.
- **D** — `Topic` and `Subscription` depend on the `Subscriber` *abstraction*, never on concrete subscriber classes.

### The two decisions an interviewer will probe

1. **Per-subscriber queue + thread vs. shared thread pool.** One thread per subscription is simple and gives free FIFO, but doesn't scale to 100k subscribers. The senior answer: keep the per-subscription *queue* (isolation + ordering) but multiplex delivery over a fixed `ExecutorService`, ensuring at most one in-flight drain task per subscription (an "actor-style" or serial-executor pattern). We implement the simple version and discuss the upgrade in Step 5.
2. **What happens when the queue is full** — see `OverflowPolicy` and Step 5. There is no free lunch: drop (lose data), block (backpressure couples publisher to slow consumer), or unbounded (OOM). You must pick one *consciously*.

---

## Step 4 — Implementation

> Compilable Java 11+. Boilerplate (some getters, equals/hashCode) trimmed to keep the interesting 90% — patterns, hand-offs, state transitions — readable.

### Message.java — immutable payload

```java
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable. Immutability is the cheapest thread-safety there is:
 * a Message can be enqueued to N subscriber queues and read by N
 * worker threads with no synchronization and no defensive copies.
 */
public final class Message {
    private final String id;
    private final String topicName;
    private final String payload;
    private final Instant timestamp;

    public Message(String topicName, String payload) {
        this.id = UUID.randomUUID().toString();
        this.topicName = Objects.requireNonNull(topicName, "topicName");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.timestamp = Instant.now();
    }

    public String getId()        { return id; }
    public String getTopicName() { return topicName; }
    public String getPayload()   { return payload; }
    public Instant getTimestamp(){ return timestamp; }

    @Override public String toString() {
        return "Message{" + id.substring(0, 8) + ", topic=" + topicName
                + ", payload=" + payload + "}";
    }
}
```

### Subscriber.java — the Observer contract

```java
public interface Subscriber {
    /** Stable identity; used as the map key for unsubscribe. */
    String getId();

    /**
     * Invoked on the subscription's worker thread — NEVER on the
     * publisher's thread. Implementations may be slow without
     * affecting other subscribers, but a permanently blocked
     * onMessage will eventually fill this subscriber's own queue.
     */
    void onMessage(Message message);
}
```

### OverflowPolicy.java — Strategy for backpressure

```java
public enum OverflowPolicy {
    /** offer(): publisher never blocks; newest message is lost if full. */
    DROP_NEW,
    /** put(): publisher blocks until space frees — true backpressure. */
    BLOCK_PUBLISHER
}
```

### Subscription.java — the Producer–Consumer core

```java
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Binds one Subscriber to one Topic and owns the async delivery
 * pipeline: bounded queue + a single dedicated worker thread.
 *
 * Single consumer per queue ==> per-topic FIFO is preserved for this
 * subscriber with no extra locking.
 */
public class Subscription {
    private final Subscriber subscriber;
    private final BlockingQueue<Message> queue;
    private final OverflowPolicy policy;
    private final Thread worker;
    // volatile: written by the control thread (stop), read by the
    // worker loop — guarantees visibility without locking.
    private volatile boolean active = true;

    public Subscription(String topicName, Subscriber subscriber,
                        int capacity, OverflowPolicy policy) {
        this.subscriber = subscriber;
        this.policy = policy;
        this.queue = new LinkedBlockingQueue<>(capacity); // bounded: N5
        this.worker = new Thread(this::runLoop,
                "sub-" + topicName + "-" + subscriber.getId());
        this.worker.setDaemon(true);
    }

    public void start() { worker.start(); }

    /**
     * Called by publisher threads. Hot path: must do no user work.
     * @return false if the message was dropped (DROP_NEW + full queue).
     */
    public boolean deliver(Message message) {
        if (!active) return false;
        try {
            if (policy == OverflowPolicy.BLOCK_PUBLISHER) {
                queue.put(message);          // backpressure
                return true;
            }
            return queue.offer(message);     // lossy, non-blocking
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // never swallow interrupts
            return false;
        }
    }

    /** Worker loop: the ONLY thread that runs user code (onMessage). */
    private void runLoop() {
        try {
            // Drain even after stop() so shutdown is graceful: exit only
            // when deactivated AND the queue is empty.
            while (active || !queue.isEmpty()) {
                Message m = queue.poll(
                        100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (m == null) continue;     // timed poll => can re-check 'active'
                try {
                    subscriber.onMessage(m);
                } catch (RuntimeException ex) {
                    // Isolation: one subscriber's bug must not kill its
                    // worker thread or affect siblings. Log and continue.
                    System.err.println("Subscriber " + subscriber.getId()
                            + " failed on " + m + ": " + ex);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Graceful: worker drains remaining messages, then exits. */
    public void stop() { active = false; }

    public String getSubscriberId() { return subscriber.getId(); }
}
```

### Topic.java — fan-out registry

```java
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Topic {
    private static final int DEFAULT_CAPACITY = 1_000;

    private final String name;
    // ConcurrentHashMap: lock-free reads on the publish hot path;
    // fine-grained locking only on subscribe/unsubscribe.
    private final Map<String, Subscription> subscriptions =
            new ConcurrentHashMap<>();

    public Topic(String name) { this.name = name; }

    public void addSubscriber(Subscriber s, OverflowPolicy policy) {
        // computeIfAbsent makes "check + create + start" atomic per key:
        // two concurrent subscribes by the same subscriber cannot
        // create two worker threads.
        subscriptions.computeIfAbsent(s.getId(), id -> {
            Subscription sub = new Subscription(name, s, DEFAULT_CAPACITY, policy);
            sub.start();
            return sub;
        });
    }

    public void removeSubscriber(String subscriberId) {
        Subscription sub = subscriptions.remove(subscriberId);
        if (sub != null) sub.stop();   // worker drains, then dies
    }

    /**
     * Fan-out. Iteration over a ConcurrentHashMap is weakly consistent:
     * no ConcurrentModificationException even if subscribers churn
     * mid-publish. A subscriber added concurrently may or may not see
     * this message — acceptable under our at-most-once semantics.
     */
    public void publish(Message message) {
        for (Subscription sub : subscriptions.values()) {
            sub.deliver(message);      // enqueue only — O(1), no user code
        }
    }

    public void shutdown() {
        subscriptions.values().forEach(Subscription::stop);
        subscriptions.clear();
    }

    public String getName() { return name; }
}
```

### PubSubBroker.java — Facade + Singleton

```java
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PubSubBroker {

    /** Lazy holder idiom: JVM class-init guarantees give us a
     *  thread-safe singleton with no synchronization on access.
     *  (In Spring Boot: delete all this and mark it @Component —
     *  singleton bean scope + injectability for tests.) */
    private static class Holder {
        private static final PubSubBroker INSTANCE = new PubSubBroker();
    }
    public static PubSubBroker getInstance() { return Holder.INSTANCE; }
    private PubSubBroker() {}

    private final Map<String, Topic> topics = new ConcurrentHashMap<>();
    private volatile boolean shutdown = false;

    public Topic createTopic(String name) {
        ensureRunning();
        return topics.computeIfAbsent(name, Topic::new); // lazy, atomic
    }

    public void subscribe(String topicName, Subscriber subscriber) {
        subscribe(topicName, subscriber, OverflowPolicy.DROP_NEW);
    }

    public void subscribe(String topicName, Subscriber subscriber,
                          OverflowPolicy policy) {
        ensureRunning();
        createTopic(topicName).addSubscriber(subscriber, policy);
    }

    public void unsubscribe(String topicName, String subscriberId) {
        Topic t = topics.get(topicName);
        if (t != null) t.removeSubscriber(subscriberId);
    }

    public void publish(String topicName, String payload) {
        ensureRunning();
        Topic t = topics.get(topicName);
        if (t == null) {
            throw new TopicNotFoundException(topicName);
        }
        t.publish(new Message(topicName, payload));
    }

    /** Graceful: stops accepting work, lets workers drain queues. */
    public void shutdown() {
        shutdown = true;
        topics.values().forEach(Topic::shutdown);
        topics.clear();
    }

    private void ensureRunning() {
        if (shutdown) throw new IllegalStateException("Broker is shut down");
    }
}
```

### Exceptions + Publisher + Demo

```java
public class TopicNotFoundException extends RuntimeException {
    public TopicNotFoundException(String topic) {
        super("Topic not found: " + topic);
    }
}

/** Thin client — exists mainly to model the domain in the diagram. */
public class Publisher {
    private final String id;
    private final PubSubBroker broker;

    public Publisher(String id, PubSubBroker broker) {
        this.id = id;
        this.broker = broker;
    }
    public void publish(String topic, String payload) {
        broker.publish(topic, payload);
    }
}

/** Example user-side subscriber. */
public class PrintSubscriber implements Subscriber {
    private final String id;
    public PrintSubscriber(String id) { this.id = id; }
    @Override public String getId() { return id; }
    @Override public void onMessage(Message m) {
        System.out.println("[" + id + "] received " + m.getPayload()
                + " on " + m.getTopicName());
    }
}

public class PubSubDemo {
    public static void main(String[] args) throws InterruptedException {
        PubSubBroker broker = PubSubBroker.getInstance();
        broker.createTopic("orders");
        broker.createTopic("payments");

        broker.subscribe("orders",   new PrintSubscriber("inventory-svc"));
        broker.subscribe("orders",   new PrintSubscriber("email-svc"));
        broker.subscribe("payments", new PrintSubscriber("ledger-svc"));

        Publisher orderSvc   = new Publisher("order-svc",   broker);
        Publisher paymentSvc = new Publisher("payment-svc", broker);

        // Concurrent publishers — safe by design.
        Thread t1 = new Thread(() -> orderSvc.publish("orders", "ORD-1 created"));
        Thread t2 = new Thread(() -> orderSvc.publish("orders", "ORD-2 created"));
        Thread t3 = new Thread(() -> paymentSvc.publish("payments", "PAY-9 settled"));
        t1.start(); t2.start(); t3.start();
        t1.join();  t2.join();  t3.join();

        Thread.sleep(200);          // let async workers drain
        broker.shutdown();
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input & API misuse

| Case | Handling |
|------|----------|
| `null` topic name / payload | `Objects.requireNonNull` in `Message` ctor — fail fast at the boundary. |
| Publish to a non-existent topic | `TopicNotFoundException` (explicit) — alternatively auto-create; we chose fail-fast because silent topic creation hides typos. State the choice. |
| Duplicate subscribe (same subscriber, same topic) | `computeIfAbsent` makes it an idempotent no-op — no duplicate worker threads, no double delivery. |
| Unsubscribe of unknown subscriber / topic | Idempotent no-op (`remove` returns null, guarded). |
| Operations after `shutdown()` | `IllegalStateException` via `ensureRunning()` — broker state machine is RUNNING → SHUTDOWN, one-way. |
| Subscriber's `onMessage` throws | Caught **per message** inside the worker loop. Isolation guarantee: a buggy subscriber loses only its own message; its worker survives; siblings unaffected. Production upgrade: route the failed message to a dead-letter queue. |

### Concurrency analysis (the section that wins the interview)

**Shared mutable state inventory** — name it explicitly:

1. `PubSubBroker.topics` map — mutated by createTopic/shutdown, read by publish.
2. `Topic.subscriptions` map — mutated by (un)subscribe, iterated by publish.
3. Each `Subscription.queue` — written by N publisher threads, drained by 1 worker.
4. `Subscription.active` / `PubSubBroker.shutdown` flags — control-plane writes, hot-path reads.

**Primitive chosen per piece of state, and why:**

| State | Primitive | Argument |
|-------|-----------|----------|
| Both registries | `ConcurrentHashMap` | Hot path (publish) is read-only ⇒ lock-free reads. `computeIfAbsent` gives atomic check-then-act for topic/subscription creation, killing the classic *"if (!map.contains) map.put"* race. Weakly-consistent iteration means publish never throws CME during subscriber churn. |
| Delivery hand-off | `LinkedBlockingQueue` (bounded) | The canonical producer-consumer primitive: internally lock-based, but we never write wait/notify ourselves (hand-rolled wait/notify is where interview candidates introduce missed-signal bugs). Bounded ⇒ memory safety. |
| Lifecycle flags | `volatile boolean` | Single-writer, multi-reader, no compound check-then-act needed ⇒ visibility is the only requirement; volatile is sufficient and cheaper than locking. |
| FIFO ordering | *Structural*: one consumer thread per queue | Ordering needs no lock at all — it falls out of the single-consumer design. The best synchronization is the one you design away. |

**Why no `synchronized` blocks appear anywhere:** every compound operation is either (a) delegated to an atomic method of a j.u.c. collection (`computeIfAbsent`, `put`, `offer`, `poll`), or (b) confined to a single thread (message consumption). This is the modern senior-level answer: prefer **confinement + immutability + concurrent collections** over manual locking.

**Deadlock-freedom argument:** a deadlock requires a cycle of lock acquisition. Here, no thread ever holds one lock while acquiring another — publisher threads touch only the queue's internal lock; workers touch only their own queue. Lock graph has no cycles ⇒ no deadlock. Livelock/starvation: workers use timed `poll`, publishers use `offer`/`put` on independent queues; no retry loops compete for the same resource.

**Race conditions explicitly closed:**

- *Subscribe vs. publish*: weakly-consistent iteration — the new subscriber sees the message or it doesn't; both are correct under at-most-once. No corruption either way.
- *Unsubscribe vs. publish*: `deliver()` checks `active`; worst case a message lands in a queue being drained, and the draining worker still delivers it — graceful, not corrupt.
- *Double subscribe race*: closed by `computeIfAbsent` (single Subscription, single thread).
- *Shutdown vs. in-flight messages*: `stop()` flips `active` but the worker loop condition `while (active || !queue.isEmpty())` drains the backlog first — graceful degradation, not message truncation.

### Boundary conditions & degradation

- **Queue full + `DROP_NEW`**: newest messages silently dropped for that one subscriber only. Production: increment a dropped-message metric and alert.
- **Queue full + `BLOCK_PUBLISHER`**: real backpressure, but now a dead subscriber can stall publishers — only choose this when loss is unacceptable and you trust consumers. This drop-vs-block tension is *the* trade-off of the problem; there is no third option except unbounded memory (OOM) or persistence (Kafka).
- **Topic with zero subscribers**: publish is a no-op loop — messages vanish (consistent with no-retention).
- **Thundering topic (100k subscribers)**: thread-per-subscription stops scaling (~MBs of stack each). Upgrade path: keep per-subscription queues, replace dedicated threads with a fixed `ExecutorService` and a per-subscription `AtomicBoolean drainScheduled` so at most one drain task per subscription is in flight — preserves FIFO (serial-executor / actor pattern) with bounded threads. Java 21 virtual threads make thread-per-subscription viable again; worth mentioning.
- **Slow subscriber**: bounded by design — affects only its own queue; publisher latency unchanged under `DROP_NEW`.

### How this maps to the real world (one line each)

- Per-subscriber queue ≈ a consumer group's partition buffer in **Kafka**; our `OverflowPolicy` ≈ Kafka's retention/lag vs. RabbitMQ's flow-control.
- Our at-most-once + no retention is exactly why real systems add a **persistent log + consumer offsets** when durability is required — the LLD seam for that upgrade is the `Subscription` class.
