# LRU Cache — Low Level Design (Java)

> Problem source: awesome-low-level-design (Easy/Medium tier). Design explained and implemented from scratch — fresh, interview-ready Java.

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| F1 | `put(key, value)`: insert or update a key-value pair. On update, the entry becomes most-recently-used (MRU). If inserting a **new** key while at capacity, evict the least-recently-used (LRU) entry first. |
| F2 | `get(key)`: return the value if present and promote the entry to MRU. Return `null` on a miss (generic design — `-1` is a LeetCode-ism that breaks down when `-1` is a legal value). |
| F3 | Fixed capacity, provided at construction time, immutable afterwards. |
| F4 | `size()` for observability (optional but cheap and useful in tests). |

### Non-Functional Requirements

| # | Requirement | Design consequence |
|---|-------------|--------------------|
| N1 | **O(1)** `get` and `put` | Forces the HashMap + Doubly Linked List composition. A map alone has no order; a list alone has O(n) search. Composed, the map locates the node in O(1) and the list relinks it in O(1). |
| N2 | **Thread-safe** | Note carefully: in an LRU cache, even `get` is a **write** (it reorders the list). A naive `ReadWriteLock` is therefore wrong. We use one exclusive lock (v1), and discuss finer-grained options. |
| N3 | **Extensible eviction** | Eviction policy is a pluggable seam (Strategy pattern) so "now make it LFU" is a small change, not a rewrite. |
| N4 | Fail fast on bad input | Reject `null` key/value and capacity < 1 with exceptions at the boundary. |

### Assumptions (stated to the interviewer)

- Generic `LRUCache<K, V>`; miss returns `null` (alternative: `Optional<V>`).
- `put` on an existing key refreshes recency.
- No TTL/expiry, no eviction listeners in v1 — both are discussed as follow-ups.
- Correctness-level thread safety; throughput tuning (striping/lock-free) discussed in Step 5.

---

## Step 2 — Entities & Relationships

| Entity | Role | Relationship | Why this relationship type |
|--------|------|--------------|----------------------------|
| `Cache<K,V>` (interface) | Public contract: `get`, `put`, `remove`, `size` | — | Lets callers depend on an abstraction (DIP). A Spring app would inject this interface, not the concrete class. |
| `LRUCache<K,V>` | Concrete implementation | *realizes* `Cache` | Implementation of the contract. |
| `Node<K,V>` | Doubly-linked-list node holding `key`, `value`, `prev`, `next` | **Composition** with `LRUCache` | Nodes have no meaning or lifetime outside their owning cache; they are created and destroyed by it. Private static nested class — never leaks out. |
| `DoublyLinkedList<K,V>` | Maintains recency order: head = MRU, tail = LRU | **Composition** with `LRUCache` | Same lifetime argument. Keeping it as a separate (package-private) class keeps `LRUCache` readable and unit-testable, but inlining it is also acceptable in an interview. |
| `HashMap<K, Node<K,V>>` | O(1) index: key → node | **Aggregation/dependency** (uses JDK class) | The cache *uses* a map; the map type is an implementation detail hidden behind the interface. |
| `EvictionPolicy<K,V>` (optional, v2) | Strategy seam: `onAccess(node)`, `onInsert(node)`, `evict()` | **Association** (LRUCache → policy) | The policy is swappable and could outlive/precede any one cache instance — classic Strategy. |

**Why the node stores the key, not just the value:** when we evict from the tail of the list we must also remove the entry from the map — and the map is keyed by `K`. Without the key in the node, eviction becomes O(n) (scan the map for the node). This is the single most common bug in LRU implementations.

---

## Step 3 — UML Class Design

```text
┌──────────────────────────────┐
│  «interface» Cache<K,V>      │
├──────────────────────────────┤
│ + get(key: K): V             │
│ + put(key: K, value: V)      │
│ + remove(key: K): V          │
│ + size(): int                │
└──────────────△───────────────┘
               │ realizes
┌──────────────┴────────────────────────────┐
│            LRUCache<K,V>                  │
├───────────────────────────────────────────┤
│ - capacity: int  «final»                  │
│ - map: HashMap<K, Node<K,V>>              │
│ - list: DoublyLinkedList<K,V>             │
│ - lock: ReentrantLock                     │
├───────────────────────────────────────────┤
│ + get(key): V                             │
│ + put(key, value): void                   │
│ + remove(key): V                          │
│ + size(): int                             │
│ - evictIfNeeded(): void                   │
└───────────────┬───────────────────────────┘
                │ composition (1 ──── 1)
┌───────────────▽───────────────┐      ┌─────────────────────────┐
│   DoublyLinkedList<K,V>       │ 1  * │   Node<K,V>             │
├───────────────────────────────┤◆────▷├─────────────────────────┤
│ - head: Node  (sentinel)      │      │ ~ key: K   «final»      │
│ - tail: Node  (sentinel)      │      │ ~ value: V              │
├───────────────────────────────┤      │ ~ prev, next: Node      │
│ ~ addFirst(node)              │      └─────────────────────────┘
│ ~ remove(node)                │
│ ~ moveToFront(node)           │
│ ~ removeLast(): Node          │
└───────────────────────────────┘
```

### SOLID mapping

- **S (Single Responsibility):** `DoublyLinkedList` only manages order; the map only indexes; `LRUCache` only orchestrates policy + capacity. Each piece is testable alone.
- **O (Open/Closed):** with the `EvictionPolicy` seam, adding LFU/FIFO means a new policy class, not edits to `LRUCache`.
- **L (Liskov):** any `Cache` implementation (LRU, LFU, no-op test double) is substitutable for callers of the interface.
- **I (Interface Segregation):** `Cache` exposes only what clients need — no `moveToFront` or node types leak into the contract.
- **D (Dependency Inversion):** callers (and Spring beans) depend on `Cache<K,V>`, not `LRUCache`. In Spring you'd register it as a singleton-scoped `@Bean Cache<K,V>` — note that *bean*-singleton via DI is preferable to the Singleton *pattern* (no global state, mockable in tests).

### Design patterns

| Pattern | Where | Why it fits (the part interviewers want) |
|---------|-------|------------------------------------------|
| **Strategy** | `EvictionPolicy` | Eviction is a family of interchangeable algorithms (LRU/LFU/FIFO) behind one interface; the cache delegates *when* to evict, the policy decides *whom*. |
| **Composition over inheritance** | map + list inside the cache | We compose two structures to get a property neither has alone. Extending `HashMap` (as some naive solutions do) couples you to its internals and violates LSP. |
| **Iterator/Sentinel idiom** | dummy head/tail nodes | Sentinels eliminate every null check in link/unlink — fewer branches, fewer bugs. Not a GoF pattern, but a signal of fluency. |
| **Singleton — deliberately rejected** | — | A cache as a JVM-global singleton makes testing and multi-tenancy painful. Say this out loud: rejecting a pattern with a reason scores as well as using one. |

### The two decisions an interviewer will probe

1. **"Why does the node store the key?"** → eviction must delete from the map in O(1) (see Step 2).
2. **"Can you use a ReadWriteLock to speed up reads?"** → No — `get` mutates the recency list, so it needs the write lock anyway; an RW lock buys nothing and adds overhead. This trips up a lot of senior candidates.

---

## Step 4 — Implementation

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Public contract. Callers depend on this, not on LRUCache (DIP). */
public interface Cache<K, V> {
    V get(K key);          // null on miss
    void put(K key, V value);
    V remove(K key);       // null if absent
    int size();
}

/**
 * Thread-safe LRU cache: HashMap for O(1) lookup + doubly linked list
 * for O(1) recency reordering. Head = most recently used, tail = least.
 */
public class LRUCache<K, V> implements Cache<K, V> {

    /** Composition: nodes never escape this class. */
    private static final class Node<K, V> {
        final K key;           // needed so eviction can remove from the map in O(1)
        V value;
        Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    // Sentinels: head.next is the MRU node, tail.prev is the LRU node.
    // Sentinels mean link/unlink never null-checks prev/next.
    private final Node<K, V> head = new Node<>(null, null);
    private final Node<K, V> tail = new Node<>(null, null);

    // One exclusive lock guards BOTH structures so they can never disagree.
    // Note: even get() mutates (it reorders), so a ReadWriteLock would not help.
    private final ReentrantLock lock = new ReentrantLock();

    public LRUCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 4 / 3 + 1); // avoid rehashing at steady state
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public V get(K key) {
        requireNonNull(key, "key");
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;            // miss — see Step 5 for the Optional discussion
            }
            moveToFront(node);          // promote to MRU
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {          // update path: refresh value + recency
                node.value = value;
                moveToFront(node);
                return;
            }
            if (map.size() == capacity) { // evict BEFORE insert so size never exceeds capacity
                Node<K, V> lru = tail.prev;        // guaranteed real node: capacity >= 1
                unlink(lru);
                map.remove(lru.key);               // <-- why Node stores the key
            }
            Node<K, V> fresh = new Node<>(key, value);
            map.put(key, fresh);
            linkFirst(fresh);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public V remove(K key) {
        requireNonNull(key, "key");
        lock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node == null) return null;
            unlink(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    // ---- list primitives (callers must hold the lock) ----

    private void linkFirst(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = node.next = null;   // help GC, catch use-after-unlink bugs
    }

    private void moveToFront(Node<K, V> node) {
        if (head.next == node) return;  // already MRU — skip pointer churn
        unlink(node);
        linkFirst(node);
    }

    private static void requireNonNull(Object o, String name) {
        if (o == null) throw new IllegalArgumentException(name + " must not be null");
    }
}
```

### Quick demo / sanity test

```java
public class Demo {
    public static void main(String[] args) {
        Cache<Integer, String> cache = new LRUCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        cache.get(1);             // 1 is now MRU; order: [1, 2]
        cache.put(3, "three");    // evicts 2 (the LRU)
        assert cache.get(2) == null;
        assert "one".equals(cache.get(1));
        assert "three".equals(cache.get(3));
        System.out.println("ok, size=" + cache.size());
    }
}
```

### Spring Boot aside

In a Spring app you would expose this as `@Bean public Cache<K,V> sessionCache() { return new LRUCache<>(10_000); }` — singleton bean scope gives you "one shared cache" without the Singleton pattern's global-state downsides, and tests can inject a fake `Cache`. (In production you'd reach for Caffeine, which is this same design plus lock-amortization and frequency sketches — knowing that earns credit.)

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input — fail fast at the boundary

| Case | Handling |
|------|----------|
| `capacity < 1` | `IllegalArgumentException` in the constructor. The object is never created in an invalid state. |
| `null` key or value | `IllegalArgumentException` before taking the lock — cheap rejection, and allowing null values would make `get == null` ambiguous (miss vs. stored null). |
| Miss on `get`/`remove` | Return `null` — a miss is **expected control flow**, not an exceptional condition; throwing here would be wrong (exceptions are for violations, and would also be slow on hot paths). `Optional<V>` is the type-safe alternative; mention it, note it allocates on every call. |

### Boundary conditions

- **capacity == 1:** every new-key `put` evicts. Works because eviction happens before insert and sentinels guarantee `tail.prev` is the only real node.
- **put existing key at full capacity:** must NOT evict — the update path returns early before the capacity check. (Common bug: evicting on update and ending below capacity.)
- **get/moveToFront on the node that is already MRU:** short-circuit; correctness doesn't require it but it avoids pointless pointer writes.
- **Evicting the node you are about to re-insert:** can't happen here because we check the map first; in policies with async eviction it can, so the ordering "lookup → update-or-evict-then-insert" matters.

### Concurrency analysis (the part to narrate in an interview)

**Shared mutable state:** the `map`, the linked list pointers (`prev`/`next` of every node, including sentinels), and each node's `value`. Crucially, the map and the list must always agree — a key in the map whose node is unlinked (or vice versa) is corruption.

**Critical sections:** the entire body of `get`, `put`, `remove`. They cannot be split because each one performs a *compound* operation across both structures (e.g., `put` = map lookup + possible map remove + list unlink + map put + list link). Interleaving two `put`s between those steps can: double-link a node, lose a node, or leave map.size() != list length.

**Chosen primitive — `ReentrantLock` (coarse-grained), and why:**
- One lock guarding both structures makes every operation atomic and the invariant trivial to argue.
- `synchronized` would be equally correct; `ReentrantLock` is chosen for `tryLock`/fairness/condition extensibility and to show fluency. Say either is fine.
- **Why not `ReadWriteLock`:** `get` mutates the list (recency promotion), so every operation needs the write lock — an RW lock adds overhead for zero parallelism. This is the #1 trap in this problem.
- **Why not just `ConcurrentHashMap`:** it makes the *map* operations atomic, but the cross-structure compound operation is still racy. CHM alone cannot keep the list consistent.

**Deadlock / livelock / starvation argument:**
- *Deadlock:* impossible — there is exactly **one** lock, and we never call out to foreign code (no listeners, no `equals` on user objects *while holding the lock*... almost: `map.get` does call `key.hashCode/equals` under the lock. Flag it: if user keys have pathological `equals` that block, that's a hazard; the standard assumption is well-behaved keys).
- *Livelock:* no retry loops, so none.
- *Starvation:* possible under heavy contention with an unfair lock; `new ReentrantLock(true)` trades throughput for fairness if required.
- *Race conditions:* none, because every read/write of shared state happens inside the single critical section; the lock's acquire/release provides the happens-before edges, so all threads see a consistent map+list.

**Scaling beyond one lock (follow-up material):**
1. **Lock striping / segmented LRU:** N independent `LRUCache` shards, route by `hash(key) % N`. Eviction becomes per-shard (approximate global LRU) — the classic throughput-for-precision trade.
2. **Amortized recency (Caffeine's approach):** `ConcurrentHashMap` for data + per-thread ring buffers that record accesses; a single drain thread replays them onto the LRU structure. Reads become nearly lock-free; recency is *eventually* applied.
3. **`Collections.synchronizedMap(new LinkedHashMap(cap, 0.75f, true))` with `removeEldestEntry`:** the 5-line interview answer. Know it exists, know `accessOrder=true`, and know it's still one big lock — i.e., it matches v1's throughput, with less control.

---

## Likely interviewer follow-ups (with model answers)

1. **"Make it LFU instead."** Swap the recency list for frequency buckets: `freq -> DLL of nodes`, plus a `minFreq` pointer. The Strategy seam means `LRUCache` orchestration survives; only the policy structure changes. (LFU is itself a classic problem — LeetCode 460.)
2. **"Add TTL."** Store `expiresAt` in the node; treat an expired hit as a miss (lazy expiry) and optionally sweep with a background thread or a min-heap on expiry time. Lazy expiry keeps O(1); active sweeping bounds memory.
3. **"10× the traffic — now what?"** Coarse lock becomes the bottleneck → shard (striping) for a quick win, or move to Caffeine-style buffered recency. Quantify: one lock serializes everything; 16 shards ≈ 16× theoretical throughput with approximate LRU.
4. **"Eviction listener?"** Add `BiConsumer<K,V> onEvict` — but **invoke it outside the lock** (collect evicted entries, release lock, then call). Calling foreign code under a lock invites deadlock.
5. **"Why not extend LinkedHashMap?"** Inheritance couples you to its iteration/locking behavior and leaks 30+ Map methods you didn't design for; composition behind a small `Cache` interface keeps the contract minimal (ISP) and substitutable (LSP).

## Transferable lesson

**Compose structures to combine their O(1) superpowers, and put a Strategy seam where the algorithm varies.** The map-indexes-into-linked-structure trick reappears in LFU cache, `LinkedHashMap` internals, rate limiters with timer wheels, and MRU/ARC variants. The concurrency lesson — "a read that updates metadata is a write" — reappears in every cache, counter, and metrics problem.

## Next problem

Natural follow-on: **LFU Cache** (directly stresses the same composition, one level harder), or if you want to stay on the easy-tier sequence first: **Parking Lot** (the canonical entity-modeling + Strategy/Factory problem).
