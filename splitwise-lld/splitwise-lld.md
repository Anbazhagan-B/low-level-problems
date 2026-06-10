# Splitwise — Low-Level Design

> **Tier:** Medium · **Core new ideas:** pairwise balance ledger, money arithmetic with `BigDecimal`, lock-free per-pair atomicity via `ConcurrentHashMap.compute()`
> **Patterns exercised:** Strategy, Factory, Singleton (holder idiom), Facade · **Transfers from:** Parking Lot (Strategy), Digital Wallet (per-resource atomic updates), Concert Ticket (validation-before-mutation)

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| FR1 | Create users with profile info (name, email). |
| FR2 | Create groups; add members to groups. |
| FR3 | Add an expense: amount, description, single payer, participants, split type, optional group. |
| FR4 | Split methods: **EQUAL**, **EXACT**, **PERCENTAGE** — each validated (exact amounts must sum to total; percentages must sum to 100). |
| FR5 | View net pairwise balances: for any user, who owes them and whom they owe, fully netted (A owes B 100, B owes A 30 ⇒ shown as A owes B 70). |
| FR6 | Settle up: record a payment from one user to another that offsets the pairwise balance. |
| FR7 | View transaction history per user and expense list per group. |
| FR8 | Delete an expense (reverses its balance effects). |

### Non-Functional Requirements

| # | Requirement | Design consequence |
|---|-------------|--------------------|
| NFR1 | **Consistency under concurrency** — simultaneous expenses/settlements touching the same user pair must never lose an update. | Atomic per-pair balance updates; no global lock. |
| NFR2 | **Monetary precision** — no floating-point money. | `BigDecimal` (scale 2) everywhere; deterministic remainder distribution for equal/percentage splits. |
| NFR3 | **Extensibility** — new split methods without touching existing ones (OCP). | `SplitStrategy` interface + registry-based factory. |
| NFR4 | **In-memory, single JVM** — standard LLD convention. | `ConcurrentHashMap` registries; service layer is framework-agnostic (in Spring, `SplitwiseService` would be a singleton-scoped `@Service` and the strategies injected as a `Map<SplitType, SplitStrategy>` — DI replaces both the Singleton and the Factory). |

### Assumptions (stated to the interviewer)

- Single payer per expense (multi-payer is a follow-up, not core).
- The payer need not be a participant (can pay purely on others' behalf).
- Balances are **global per user pair**, not per group — groups organize expenses; the ledger nets across everything (matches real Splitwise).
- Settlement is a ledger entry, not a real payment (no gateway).
- Equal-split rounding: divide in integer **cents**; the first `remainder` participants absorb one extra cent each. Shares always sum exactly to the total.

---

## Step 2 — Entities & Relationships

### The two classic over-modeling traps (and why we avoid them)

1. **A `Debt` entity per expense-participant.** Tempting, but debts are *derived data*: the ledger only needs the running net per user pair. Storing every micro-debt forces O(history) reads and creates a consistency problem between the debt list and the net balance. We keep `Expense` (immutable history) and `BalanceLedger` (mutable nets) as the two sources, where the ledger is always the *fold* of history.
2. **A `Balance` entity per group.** Group balances are a *view* computed from that group's expenses. Modeling them as stored state means every expense must update two ledgers atomically — complexity with no benefit. One global ledger; group views are derived.

This is the same **deliberate under-modeling** discipline from Facebook/LinkedIn (no `Like`, no `Friendship` entity): model state you must mutate, derive views you must display.

### Entity list

| Entity | Kind | Relationship notes |
|--------|------|--------------------|
| `User` | Immutable entity | Referenced by id everywhere else (no object cycles). |
| `Group` | Entity | **Aggregation** of users (holds member *ids*; users exist independently of groups). |
| `Expense` | **Immutable** entity | **Composition** of `Split`s (splits have no identity or life outside their expense). **Association** to payer/group by id. **Dependency** on `SplitStrategy` at creation time only — the strategy computes splits, the expense stores the result. |
| `Split` | Value object | `(userId, amount)`. No id, equality by value. |
| `UserPair` | Value object | Canonically ordered pair — the ledger key. The non-obvious entity that makes the whole design work. |
| `BalanceLedger` | Entity (the interesting one) | Owns `Map<UserPair, BigDecimal>`. **Composition** inside the service. |
| `Settlement` | Immutable entity | History record of a settle-up; affects the ledger exactly like a one-line expense. |
| `SplitStrategy` (+3 impls) | Behavior, not data | Strategy pattern; stateless, shareable singletons. |
| `SplitType` | Enum | Factory key. |
| `SplitwiseService` | Facade | Singleton; owns all registries and the ledger. |

### Key relationship: why `UserPair` and a signed balance

A naive ledger is `Map<User, Map<User, BigDecimal>>` (the reference design does this). It stores every balance **twice** (A→B and B→A) and every update must mutate two entries — which is exactly where concurrent updates corrupt data. Instead:

- Canonicalize the pair: `UserPair.of(a, b)` always orders ids lexicographically.
- Store one **signed** `BigDecimal`: *positive ⇒ `first` owes `second`; negative ⇒ the reverse.*
- Every balance change is now a **single-key atomic update**. This is the entire concurrency story.

---

## Step 3 — UML Class Design

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        SplitwiseService  «Facade, Singleton»             │
│  - users:       ConcurrentHashMap<String, User>                          │
│  - groups:      ConcurrentHashMap<String, Group>                         │
│  - expenses:    ConcurrentHashMap<String, Expense>                       │
│  - settlements: ConcurrentLinkedQueue<Settlement>                        │
│  - ledger:      BalanceLedger                                            │
│  + createUser(name, email): User                                         │
│  + createGroup(name, memberIds): Group                                   │
│  + addExpense(desc, amount, payerId, participantIds,                     │
│               type, splitData, groupId): Expense                         │
│  + settle(fromId, toId, amount): Settlement                              │
│  + deleteExpense(expenseId): void                                        │
│  + getBalancesFor(userId): Map<String, BigDecimal>                       │
│  + getGroupExpenses(groupId): List<Expense>                              │
└────────────┬──────────────────────────────┬──────────────────────────────┘
             │ owns (composition)           │ uses (dependency)
             ▼                              ▼
┌───────────────────────────────┐   ┌─────────────────────────────────────┐
│  BalanceLedger                │   │  SplitStrategyFactory               │
│  - balances:                  │   │  - registry: EnumMap<SplitType,     │
│    ConcurrentHashMap<         │   │               SplitStrategy>        │
│      UserPair, BigDecimal>    │   │  + get(SplitType): SplitStrategy    │
│  + applyDelta(debtor,         │   └──────────────────┬──────────────────┘
│      creditor, amount)        │                      │ creates/returns
│  + balanceBetween(a,b)        │                      ▼
│  + balancesFor(userId)        │   ┌─────────────────────────────────────┐
└───────────────────────────────┘   │  «interface» SplitStrategy          │
                                    │  + computeSplits(total,             │
┌───────────────────────────────┐   │      participantIds,                │
│  UserPair  «value object»     │   │      splitData): List<Split>        │
│  - first, second: String      │   └───────┬──────────┬─────────┬────────┘
│  (first < second always)      │           △          △         △
│  + of(a, b): UserPair         │   ┌───────┴──┐ ┌─────┴────┐ ┌──┴───────┐
│  equals/hashCode by value     │   │ Equal    │ │ Exact    │ │Percentage│
└───────────────────────────────┘   │ Split    │ │ Split    │ │ Split    │
                                    │ Strategy │ │ Strategy │ │ Strategy │
┌───────────────────────────────┐   └──────────┘ └──────────┘ └──────────┘
│  Expense  «immutable»         │
│  - expenseId, description     │   ┌──────────────┐  ┌──────────────────┐
│  - amount: BigDecimal         │   │ User         │  │ Group            │
│  - paidByUserId: String       │   │ - userId     │  │ - groupId, name  │
│  - groupId: String (nullable) │   │ - name,email │  │ - memberIds:     │
│  - splits: List<Split> (◆)    │   └──────────────┘  │   Set<String>    │
│  - createdAt: Instant         │   ┌──────────────┐  │   (concurrent)   │
└───────────────────────────────┘   │ Split  «VO»  │  └──────────────────┘
                                    │ - userId     │  ┌──────────────────┐
                                    │ - amount     │  │ enum SplitType   │
                                    └──────────────┘  │ EQUAL, EXACT,    │
                                                      │ PERCENTAGE       │
                                                      └──────────────────┘
```

### SOLID mapping

- **SRP** — `SplitStrategy` computes shares; `BalanceLedger` maintains nets; `Expense` records history; the service only orchestrates. In the reference design, `BalanceSheet` both stores balances *and* interprets splits (`updateBalance(paidBy, splits)`) — we keep split interpretation in the service so the ledger stays a dumb, easily-testable map.
- **OCP** — adding `SHARES` split type = one new strategy class + one enum constant + one registry line. Nothing existing changes.
- **LSP** — every strategy honors the same contract: returned splits sum *exactly* to the total (enforced by each impl's remainder handling). A strategy that could return splits summing to total±0.01 would silently corrupt the ledger — this contract *is* the LSP obligation here.
- **ISP** — `SplitStrategy` is a single-method interface; no implementation is forced to carry unused obligations.
- **DIP** — service depends on the `SplitStrategy` abstraction; concrete strategies are wired via the factory registry (or Spring DI).

### Patterns and exactly why they fit

- **Strategy** — split computation is a family of interchangeable algorithms chosen at runtime by `SplitType`. Conditionals (`switch` on type inside `addExpense`) would violate OCP and scatter validation logic.
- **Factory (registry-based)** — strategies are stateless, so the factory returns shared singletons from an `EnumMap` rather than constructing per call. An `EnumMap` registry beats a `switch` factory: adding a type can't be forgotten in one branch.
- **Singleton (initialization-on-demand holder)** — one service instance, lazily and thread-safely initialized by the JVM classloader, no `synchronized` on every `getInstance()` call (the reference design's `static synchronized getInstance()` pays that cost forever).
- **Facade** — `SplitwiseService` gives callers one coherent API over registries + ledger + strategies.

### Where this deliberately diverges from the reference diagram

| Reference (algomaster) | This design | Why |
|---|---|---|
| `double` for money | `BigDecimal`, scale 2 | `0.1 + 0.2 != 0.3`; equal splits drift; an interviewer **will** flag `double`. |
| `Map<User, Map<User, Double>>` balance sheet | `ConcurrentHashMap<UserPair, BigDecimal>` | Single-key atomic updates; no double bookkeeping; no nested-map race. |
| `static synchronized getInstance()` | Holder idiom | No contention on every access. |
| `Split` holds a `User` reference; `Expense` holds `Group`/`User` objects | Ids only | Avoids object graphs/cycles; matches how a persistence layer would store it. |
| No delete-reversal story | `deleteExpense` applies inverse deltas atomically | FR8 + the interviewer's favorite follow-up. |

### The two decisions an interviewer will probe

1. **"How do two concurrent `addExpense` calls not corrupt balances?"** → Each split produces one `(payer, participant)` delta; each delta is a single `compute()` on one `UserPair` key. Addition is commutative and associative, so interleaved per-pair updates from different expenses commute — no multi-key transaction is needed *for balance correctness*. (Contrast with Concert Ticket, where multi-seat claims are *not* commutative and need claim-or-compensate.)
2. **"Why is rounding done in cents with deterministic remainder assignment?"** → `100 / 3` at scale 2 gives `33.33 × 3 = 99.99` — one cent vanishes from the ledger per expense, and the ledger stops being the fold of history. Integer-cent division with the first `r` participants taking one extra cent guarantees `Σ splits = total` *exactly*, and determinism makes delete-reversal byte-identical to the original deltas.

---

## Step 4 — Implementation

### Value objects and entities

```java
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class User {
    private final String userId;
    private final String name;
    private final String email;

    public User(String userId, String name, String email) {
        this.userId = userId; this.name = name; this.email = email;
    }
    public String getUserId() { return userId; }
    public String getName()   { return name; }
    public String getEmail()  { return email; }
}

public final class Group {
    private final String groupId;
    private final String name;
    // Concurrent set: members can be added while expenses are being created.
    private final Set<String> memberIds = ConcurrentHashMap.newKeySet();

    public Group(String groupId, String name, Collection<String> initialMembers) {
        this.groupId = groupId; this.name = name;
        memberIds.addAll(initialMembers);
    }
    public void addMember(String userId) { memberIds.add(userId); }
    public boolean hasMember(String userId) { return memberIds.contains(userId); }
    public String getGroupId() { return groupId; }
    public Set<String> getMemberIds() { return Collections.unmodifiableSet(memberIds); }
}

/** Value object: a participant's share of one expense. */
public final class Split {
    private final String userId;
    private final BigDecimal amount; // always scale 2, non-negative

    public Split(String userId, BigDecimal amount) {
        this.userId = userId;
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }
    public String getUserId()     { return userId; }
    public BigDecimal getAmount() { return amount; }
}

/** Immutable history record. The ledger is always the fold of these. */
public final class Expense {
    private final String expenseId;
    private final String description;
    private final BigDecimal amount;
    private final String paidByUserId;
    private final String groupId;          // nullable: non-group expense
    private final List<Split> splits;      // composition: built once, never mutated
    private final Instant createdAt;
    // Guards delete-reversal: flips true exactly once (see deleteExpense).
    private final AtomicBoolean deleted = new AtomicBoolean(false);

    public Expense(String expenseId, String description, BigDecimal amount,
                   String paidByUserId, String groupId, List<Split> splits) {
        this.expenseId = expenseId;
        this.description = description;
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        this.paidByUserId = paidByUserId;
        this.groupId = groupId;
        this.splits = List.copyOf(splits);
        this.createdAt = Instant.now();
    }
    public String getExpenseId()    { return expenseId; }
    public String getPaidByUserId() { return paidByUserId; }
    public String getGroupId()      { return groupId; }
    public List<Split> getSplits()  { return splits; }
    public BigDecimal getAmount()   { return amount; }
    public Instant getCreatedAt()   { return createdAt; }
    /** @return true exactly once — the caller that wins may reverse the deltas. */
    boolean markDeleted() { return deleted.compareAndSet(false, true); }
}

/** Immutable settle-up record. */
public final class Settlement {
    private final String settlementId;
    private final String fromUserId;   // payer of the settlement
    private final String toUserId;     // receiver
    private final BigDecimal amount;
    private final Instant createdAt = Instant.now();

    public Settlement(String settlementId, String fromUserId, String toUserId, BigDecimal amount) {
        this.settlementId = settlementId;
        this.fromUserId = fromUserId; this.toUserId = toUserId;
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }
    public String getFromUserId() { return fromUserId; }
    public String getToUserId()   { return toUserId; }
    public BigDecimal getAmount() { return amount; }
}
```

### The ledger key: canonical `UserPair`

```java
/**
 * Canonically ordered pair of user ids — the single key under which the
 * net balance between two users lives. Ordering convention:
 *   first < second (lexicographic).
 * Sign convention for the stored balance:
 *   positive  => first owes second
 *   negative  => second owes first
 * One key per pair means every balance change is a single-key atomic update.
 */
public final class UserPair {
    private final String first;
    private final String second;

    private UserPair(String first, String second) {
        this.first = first; this.second = second;
    }

    public static UserPair of(String a, String b) {
        if (a.equals(b)) throw new IllegalArgumentException("Self-pair not allowed");
        return a.compareTo(b) < 0 ? new UserPair(a, b) : new UserPair(b, a);
    }

    public String getFirst()  { return first; }
    public String getSecond() { return second; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserPair)) return false;
        UserPair p = (UserPair) o;
        return first.equals(p.first) && second.equals(p.second);
    }
    @Override public int hashCode() { return Objects.hash(first, second); }
}
```

### The interesting class: `BalanceLedger`

```java
/**
 * Net pairwise balances. All mutation funnels through applyDelta(), which is
 * a single ConcurrentHashMap.compute() — atomic per key, lock-striped by the
 * map itself. No explicit locks; no global lock.
 */
public final class BalanceLedger {

    private final ConcurrentHashMap<UserPair, BigDecimal> balances = new ConcurrentHashMap<>();

    /**
     * Record that `debtor` now owes `creditor` an additional `amount`.
     * (A settlement or an expense reversal calls this with roles such that
     * the net moves the other way — same method, no special cases.)
     */
    public void applyDelta(String debtorId, String creditorId, BigDecimal amount) {
        if (amount.signum() == 0) return;                  // zero-share participant
        UserPair pair = UserPair.of(debtorId, creditorId);
        // Map sign convention: positive => pair.first owes pair.second.
        BigDecimal signed = pair.getFirst().equals(debtorId) ? amount : amount.negate();

        // compute() is atomic for this key: read-modify-write cannot interleave
        // with another thread's update to the same pair. Returning null removes
        // the entry, so fully-settled pairs don't accumulate in the map.
        balances.compute(pair, (k, current) -> {
            BigDecimal updated = (current == null ? BigDecimal.ZERO : current).add(signed);
            return updated.signum() == 0 ? null : updated;
        });
    }

    /** Positive => a owes b; negative => b owes a; zero => settled. */
    public BigDecimal balanceBetween(String a, String b) {
        UserPair pair = UserPair.of(a, b);
        BigDecimal stored = balances.getOrDefault(pair, BigDecimal.ZERO);
        return pair.getFirst().equals(a) ? stored : stored.negate();
    }

    /**
     * Snapshot view for one user: counterpartyId -> amount this user owes them
     * (negative => they owe this user). Weakly consistent by design: it reflects
     * a moment-in-time iteration, which is the right trade-off for a read view.
     */
    public Map<String, BigDecimal> balancesFor(String userId) {
        Map<String, BigDecimal> view = new HashMap<>();
        balances.forEach((pair, amt) -> {
            if (pair.getFirst().equals(userId))       view.put(pair.getSecond(), amt);
            else if (pair.getSecond().equals(userId)) view.put(pair.getFirst(), amt.negate());
        });
        return view;
    }
}
```

### Strategies

```java
public enum SplitType { EQUAL, EXACT, PERCENTAGE }

public interface SplitStrategy {
    /**
     * Contract (the LSP obligation): returned splits cover exactly the given
     * participants and sum EXACTLY to totalAmount, at scale 2.
     * @param splitData per-user inputs: exact amounts or percentages; ignored by EQUAL.
     * @throws InvalidSplitException if inputs violate the split type's rules.
     */
    List<Split> computeSplits(BigDecimal totalAmount,
                              List<String> participantIds,
                              Map<String, BigDecimal> splitData);
}

public final class EqualSplitStrategy implements SplitStrategy {
    @Override
    public List<Split> computeSplits(BigDecimal total, List<String> participants,
                                     Map<String, BigDecimal> ignored) {
        int n = participants.size();
        // Work in integer cents so division can't lose money to rounding.
        long totalCents = total.movePointRight(2).longValueExact();
        long base = totalCents / n;
        long remainder = totalCents % n;     // first `remainder` users pay 1 extra cent

        List<Split> splits = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long cents = base + (i < remainder ? 1 : 0);
            splits.add(new Split(participants.get(i),
                    BigDecimal.valueOf(cents).movePointLeft(2)));
        }
        return splits; // sums to total by construction — no validation needed
    }
}

public final class ExactSplitStrategy implements SplitStrategy {
    @Override
    public List<Split> computeSplits(BigDecimal total, List<String> participants,
                                     Map<String, BigDecimal> exactAmounts) {
        BigDecimal sum = BigDecimal.ZERO;
        List<Split> splits = new ArrayList<>(participants.size());
        for (String userId : participants) {
            BigDecimal share = exactAmounts.get(userId);
            if (share == null || share.signum() < 0)
                throw new InvalidSplitException("Missing/negative exact amount for " + userId);
            share = share.setScale(2, RoundingMode.UNNECESSARY); // reject sub-cent input
            sum = sum.add(share);
            splits.add(new Split(userId, share));
        }
        // compareTo, never equals(): equals() distinguishes 50.0 from 50.00.
        if (sum.compareTo(total) != 0)
            throw new InvalidSplitException(
                "Exact amounts sum to " + sum + ", expected " + total);
        return splits;
    }
}

public final class PercentageSplitStrategy implements SplitStrategy {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public List<Split> computeSplits(BigDecimal total, List<String> participants,
                                     Map<String, BigDecimal> percentages) {
        BigDecimal pctSum = BigDecimal.ZERO;
        for (String userId : participants) {
            BigDecimal pct = percentages.get(userId);
            if (pct == null || pct.signum() < 0)
                throw new InvalidSplitException("Missing/negative percentage for " + userId);
            pctSum = pctSum.add(pct);
        }
        if (pctSum.compareTo(HUNDRED) != 0)
            throw new InvalidSplitException("Percentages sum to " + pctSum + ", expected 100");

        // Floor each share in cents, then hand out the leftover cents one by one.
        long totalCents = total.movePointRight(2).longValueExact();
        long allocated = 0;
        List<Split> splits = new ArrayList<>(participants.size());
        long[] cents = new long[participants.size()];
        for (int i = 0; i < participants.size(); i++) {
            BigDecimal pct = percentages.get(participants.get(i));
            cents[i] = BigDecimal.valueOf(totalCents).multiply(pct)
                         .divide(HUNDRED, 0, RoundingMode.FLOOR).longValueExact();
            allocated += cents[i];
        }
        for (long left = totalCents - allocated, i = 0; left > 0; left--, i++)
            cents[(int) i]++;                       // deterministic remainder spread
        for (int i = 0; i < participants.size(); i++)
            splits.add(new Split(participants.get(i),
                    BigDecimal.valueOf(cents[i]).movePointLeft(2)));
        return splits;
    }
}

/** Registry factory: strategies are stateless, so share one instance each. */
public final class SplitStrategyFactory {
    private static final Map<SplitType, SplitStrategy> REGISTRY = new EnumMap<>(SplitType.class);
    static {
        REGISTRY.put(SplitType.EQUAL, new EqualSplitStrategy());
        REGISTRY.put(SplitType.EXACT, new ExactSplitStrategy());
        REGISTRY.put(SplitType.PERCENTAGE, new PercentageSplitStrategy());
    }
    private SplitStrategyFactory() {}

    public static SplitStrategy get(SplitType type) {
        SplitStrategy s = REGISTRY.get(type);
        if (s == null) throw new IllegalArgumentException("Unsupported split type: " + type);
        return s;
    }
}
```

### Exceptions

```java
public class SplitwiseException extends RuntimeException {
    public SplitwiseException(String msg) { super(msg); }
}
public class UserNotFoundException extends SplitwiseException {
    public UserNotFoundException(String id) { super("User not found: " + id); }
}
public class GroupNotFoundException extends SplitwiseException {
    public GroupNotFoundException(String id) { super("Group not found: " + id); }
}
public class ExpenseNotFoundException extends SplitwiseException {
    public ExpenseNotFoundException(String id) { super("Expense not found: " + id); }
}
public class InvalidSplitException extends SplitwiseException {
    public InvalidSplitException(String msg) { super(msg); }
}
```

### The facade: `SplitwiseService`

```java
public final class SplitwiseService {

    // ---- Singleton via initialization-on-demand holder: lazy, thread-safe,
    // ---- zero synchronization cost after class init (JLS guarantees it).
    private SplitwiseService() {}
    private static final class Holder { static final SplitwiseService INSTANCE = new SplitwiseService(); }
    public static SplitwiseService getInstance() { return Holder.INSTANCE; }

    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Group> groups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Expense> expenses = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Settlement> settlements = new ConcurrentLinkedQueue<>();
    private final BalanceLedger ledger = new BalanceLedger();
    private final AtomicLong idSeq = new AtomicLong();

    private String nextId(String prefix) { return prefix + "-" + idSeq.incrementAndGet(); }

    // ---------------- Users & Groups ----------------

    public User createUser(String name, String email) {
        User user = new User(nextId("U"), name, email);
        users.put(user.getUserId(), user);
        return user;
    }

    public Group createGroup(String name, List<String> memberIds) {
        memberIds.forEach(this::requireUser);
        Group group = new Group(nextId("G"), name, memberIds);
        groups.put(group.getGroupId(), group);
        return group;
    }

    public void addMemberToGroup(String groupId, String userId) {
        requireUser(userId);
        requireGroup(groupId).addMember(userId);
    }

    // ---------------- Expenses ----------------

    public Expense addExpense(String description, BigDecimal amount, String payerId,
                              List<String> participantIds, SplitType type,
                              Map<String, BigDecimal> splitData, String groupId) {
        // 1) Validate everything BEFORE any mutation (claim-nothing-until-valid,
        //    same discipline as Concert Ticket's validate-then-claim).
        if (amount == null || amount.signum() <= 0)
            throw new InvalidSplitException("Amount must be positive");
        if (participantIds == null || participantIds.isEmpty())
            throw new InvalidSplitException("At least one participant required");
        if (new HashSet<>(participantIds).size() != participantIds.size())
            throw new InvalidSplitException("Duplicate participants");
        requireUser(payerId);
        participantIds.forEach(this::requireUser);
        if (groupId != null) {
            Group group = requireGroup(groupId);
            for (String pid : participantIds)
                if (!group.hasMember(pid))
                    throw new InvalidSplitException(pid + " is not a member of group " + groupId);
        }

        // 2) Strategy computes splits — may throw InvalidSplitException; if it
        //    does, nothing has been mutated yet.
        List<Split> splits = SplitStrategyFactory.get(type)
                .computeSplits(amount.setScale(2, RoundingMode.UNNECESSARY),
                               participantIds, splitData == null ? Map.of() : splitData);

        // 3) Record history, then apply ledger deltas. Each delta is an
        //    independent atomic single-key update; per-pair additions commute,
        //    so concurrent expenses cannot lose or corrupt balances.
        Expense expense = new Expense(nextId("E"), description, amount, payerId, groupId, splits);
        expenses.put(expense.getExpenseId(), expense);

        for (Split split : splits) {
            if (split.getUserId().equals(payerId)) continue; // own share: no debt
            ledger.applyDelta(split.getUserId(), payerId, split.getAmount());
        }
        return expense;
    }

    public void deleteExpense(String expenseId) {
        Expense expense = expenses.get(expenseId);
        if (expense == null) throw new ExpenseNotFoundException(expenseId);
        // CAS guard: exactly one caller wins; a second delete is a no-op error,
        // never a double reversal that would mint money out of thin air.
        if (!expense.markDeleted())
            throw new SplitwiseException("Expense already deleted: " + expenseId);
        for (Split split : expense.getSplits()) {
            if (split.getUserId().equals(expense.getPaidByUserId())) continue;
            // Inverse delta: the payer now "owes" the participant the share,
            // which exactly cancels the original entry (determinism matters!).
            ledger.applyDelta(expense.getPaidByUserId(), split.getUserId(), split.getAmount());
        }
        expenses.remove(expenseId);
    }

    // ---------------- Settlement ----------------

    /** Records that `fromUserId` paid `toUserId` `amount` outside the app. */
    public Settlement settle(String fromUserId, String toUserId, BigDecimal amount) {
        requireUser(fromUserId); requireUser(toUserId);
        if (amount == null || amount.signum() <= 0)
            throw new InvalidSplitException("Settlement amount must be positive");
        // Paying reduces what `from` owes `to`: apply the delta in reverse.
        // We intentionally ALLOW over-settlement (it flips the direction of the
        // balance) — same as real Splitwise; see Step 5 for the alternative.
        ledger.applyDelta(toUserId, fromUserId, amount.setScale(2, RoundingMode.UNNECESSARY));
        Settlement s = new Settlement(nextId("S"), fromUserId, toUserId, amount);
        settlements.add(s);
        return s;
    }

    // ---------------- Views ----------------

    public Map<String, BigDecimal> getBalancesFor(String userId) {
        requireUser(userId);
        return ledger.balancesFor(userId);
    }

    public BigDecimal getBalanceBetween(String a, String b) {
        requireUser(a); requireUser(b);
        return ledger.balanceBetween(a, b);
    }

    public List<Expense> getGroupExpenses(String groupId) {
        requireGroup(groupId);
        List<Expense> result = new ArrayList<>();
        for (Expense e : expenses.values())
            if (groupId.equals(e.getGroupId())) result.add(e);
        result.sort(Comparator.comparing(Expense::getCreatedAt));
        return result;
    }

    public List<Expense> getUserExpenses(String userId) {
        requireUser(userId);
        List<Expense> result = new ArrayList<>();
        for (Expense e : expenses.values()) {
            boolean involved = e.getPaidByUserId().equals(userId)
                    || e.getSplits().stream().anyMatch(s -> s.getUserId().equals(userId));
            if (involved) result.add(e);
        }
        result.sort(Comparator.comparing(Expense::getCreatedAt));
        return result;
    }

    // ---------------- Guards ----------------

    private User requireUser(String id) {
        User u = users.get(id);
        if (u == null) throw new UserNotFoundException(id);
        return u;
    }
    private Group requireGroup(String id) {
        Group g = groups.get(id);
        if (g == null) throw new GroupNotFoundException(id);
        return g;
    }
}
```

### Demo

```java
public class SplitwiseDemo {
    public static void main(String[] args) {
        SplitwiseService svc = SplitwiseService.getInstance();

        User alice = svc.createUser("Alice", "alice@x.com");
        User bob   = svc.createUser("Bob",   "bob@x.com");
        User carol = svc.createUser("Carol", "carol@x.com");

        Group trip = svc.createGroup("Goa Trip",
                List.of(alice.getUserId(), bob.getUserId(), carol.getUserId()));

        // Alice pays 100, split equally 3 ways: 33.34 / 33.33 / 33.33
        svc.addExpense("Dinner", new BigDecimal("100.00"), alice.getUserId(),
                List.of(alice.getUserId(), bob.getUserId(), carol.getUserId()),
                SplitType.EQUAL, null, trip.getGroupId());

        // Bob pays 90: Alice owes 40% , Bob 30%, Carol 30%
        svc.addExpense("Cab", new BigDecimal("90.00"), bob.getUserId(),
                List.of(alice.getUserId(), bob.getUserId(), carol.getUserId()),
                SplitType.PERCENTAGE,
                Map.of(alice.getUserId(), new BigDecimal("40"),
                       bob.getUserId(),   new BigDecimal("30"),
                       carol.getUserId(), new BigDecimal("30")),
                trip.getGroupId());

        System.out.println("Alice: " + svc.getBalancesFor(alice.getUserId()));
        System.out.println("Bob:   " + svc.getBalancesFor(bob.getUserId()));
        System.out.println("Carol: " + svc.getBalancesFor(carol.getUserId()));

        // Carol settles what she owes Alice
        BigDecimal carolOwesAlice = svc.getBalanceBetween(carol.getUserId(), alice.getUserId());
        if (carolOwesAlice.signum() > 0)
            svc.settle(carol.getUserId(), alice.getUserId(), carolOwesAlice);

        System.out.println("After settle — Carol: " + svc.getBalancesFor(carol.getUserId()));
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input

| Case | Handling |
|------|----------|
| Negative/zero expense or settlement amount | `InvalidSplitException` before any mutation. |
| Exact amounts don't sum to total / percentages ≠ 100 | Strategy throws; service hasn't touched state yet — **validate-before-mutate** means failures are always clean, no compensation needed (the easy cousin of Airline's two-phase booking). |
| Sub-cent input (`33.333` exact share) | `setScale(2, RoundingMode.UNNECESSARY)` throws `ArithmeticException` → rejected at the door rather than silently rounded. |
| Unknown user/group/expense ids | Specific `*NotFoundException` from a shared base — callers can catch broadly or precisely. |
| Duplicate participants | Rejected; otherwise EQUAL would double-charge one user. |
| Participant not in the group (group expense) | Rejected. |
| `BigDecimal` equality | Always `compareTo() == 0`, never `equals()` — `equals()` says `50.0 ≠ 50.00`, a classic silent bug. |

### Boundary conditions

- **Single-participant expense where participant == payer** → all splits skipped (own share), zero ledger deltas, expense still recorded in history. Correct, not an error.
- **Payer not among participants** → payer fronts the full amount; every split becomes a debt. Supported by design (the `continue` only skips the payer's own share).
- **Fully settled pair** → `compute()` returns `null`, entry removed; the ledger doesn't grow monotonically with user-pair count.
- **Over-settlement** (Carol pays Alice more than she owes) → balance flips sign: now Alice owes Carol. This matches real Splitwise. The stricter alternative — reject if `amount > balanceBetween(from, to)` — requires a *check-then-act* on the pair and therefore a real lock per pair (a `compute()` that throws inside the remapping function works, but discuss it as a deliberate choice). Know both; say which you're choosing and why.
- **₹100 / 3** → 33.34 + 33.33 + 33.33. Sum is exact; first participant absorbs the extra cent; reversal reproduces identical deltas.

### Concurrency analysis (the part the interviewer grades hardest)

**Shared mutable state:**
1. `BalanceLedger.balances` — the hot spot.
2. `users` / `groups` / `expenses` registries — `ConcurrentHashMap`, single-key puts/gets, inherently safe.
3. `Group.memberIds` — `ConcurrentHashMap.newKeySet()`, safe for concurrent add/lookup.
4. `Expense.deleted` flag — `AtomicBoolean` CAS.

**Critical sections and chosen primitives:**

| Operation | Hazard | Primitive | Why it's sufficient |
|---|---|---|---|
| Two expenses updating the same pair | Lost update (read-modify-write race) | `ConcurrentHashMap.compute()` | Atomic per key; CHM lock-stripes internally, so distinct pairs update fully in parallel — the same per-resource-not-global philosophy as Digital Wallet, achieved without owning any locks. |
| One expense touching many pairs | "Partial visibility" — a reader sees some deltas applied, not yet others | Accepted (documented) | Per-pair **additions commute**, so the final state is always correct regardless of interleaving; only transient reads can see an expense half-applied. Contrast Concert Ticket: seat claims do NOT commute, hence claim-or-compensate there but not here. This question — *"why doesn't multi-pair need a transaction?"* — is the deepest probe in this problem. |
| Expense delete racing a second delete | Double reversal (money created) | `AtomicBoolean.compareAndSet` | Exactly one winner reverses; loser gets an exception. |
| Settlement racing an expense on the same pair | Lost update | Same `compute()` path | All mutation funnels through `applyDelta` — one funnel, one correctness argument. |
| Reading `balancesFor` during writes | Stale/teared view | CHM weakly-consistent iteration | Reads never block writes; a snapshot may be milliseconds stale, which is correct for a display view. If a strongly consistent global snapshot were required, you'd introduce a `ReadWriteLock` over the ledger — and pay for it on every write. |

**Deadlock/livelock argument:** the design holds **zero explicit locks** and never holds more than one CHM bin lock at a time (each `applyDelta` is one `compute()`); lock ordering problems cannot arise because there is no point where two locks are held. No retry loops → no livelock. CAS on the delete flag is single-shot, not spinning.

**Where this design would need real locks (know this!):** any *cross-pair invariant*. Example: "reject an expense if it would push someone's total debt above a credit limit" — that's a check across many pairs followed by multi-pair writes, i.e., a multi-resource transaction. Then you're back to ordered per-pair `ReentrantLock`s (lock pairs in canonical `UserPair` order to prevent deadlock — exactly the consistent-ordering rule from Concert Ticket/Airline). The current requirements have **no cross-pair invariant**, which is *why* the lock-free design is sufficient — being able to articulate that boundary is the senior-level answer.

---

## Interviewer Follow-Ups (with model answers)

**1. "Simplify debts: minimize the number of transactions to settle a group."**
Compute each member's *net* position (Σ owed to them − Σ they owe). Nets sum to zero. Greedy: repeatedly match the largest debtor with the largest creditor, transfer `min(|debt|, |credit|)`, repeat — at most `n−1` transactions using two heaps, O(n log n). Note honestly: the true minimum-transaction count is NP-hard (subset-sum to find zero-sum subgroups); the greedy `n−1` bound is the expected interview answer, and real Splitwise does roughly this.

**2. "Support multiple payers on one expense."**
`paidByUserId: String` → `payments: Map<String, BigDecimal>` validated to sum to the total. Splits unchanged. Ledger application becomes: each participant's share is distributed across payers proportionally to payment (or net each user's `paid − owed` against the group's net — cleaner). Strategy layer untouched — evidence the SRP boundary was right.

**3. "Make balances strongly consistent for a 'group summary' read."**
Options: (a) `ReadWriteLock` over the ledger — simple, serializes all writes against summary reads; (b) versioned snapshots (copy-on-write of the balance map) — readers get immutable snapshots, writers pay copy cost; (c) accept weak consistency and timestamp the view — usually the right product answer. Lead with (c), show you can build (a).

**4. "Expense editing, not just deletion."**
Edit = delete (reverse deltas) + add (apply new deltas), wrapped so history shows one logical edit. The `AtomicBoolean` guard generalizes to a version number (`AtomicInteger`) for optimistic concurrency: edit fails if the version changed since read.

**5. "Scale 10×, multiple JVMs?"**
The single-JVM atomicity story (CHM `compute`) evaporates. Pairwise balance becomes a database row with optimistic locking (`@Version`) or an atomic `UPDATE ... SET balance = balance + ?`; expense + deltas wrap in one DB transaction; or go event-sourced — expenses are the append-only log (they already are immutable here!), balances are a projection. Point out that this design's *shape* — immutable history + derived net ledger — is exactly the event-sourcing shape, so the migration is natural.

---

## Transferable Lessons

1. **Commutativity is a concurrency tool.** When updates commute (pure additions), per-key atomicity is enough and multi-resource transactions are unnecessary. When they don't (seat claims, inventory), you need claim-or-compensate. Asking *"do my updates commute?"* should now be a standard step in your concurrency analysis.
2. **Canonical keys turn two-sided state into one-sided state.** `UserPair` with a sign convention is the same trick as ordered lock acquisition — impose a total order to kill symmetry problems. Reappears in: chat (conversation between two users), trading (currency pairs), graph edges.
3. **Immutable history + mutable derived state, with one funnel for mutation.** `Expense` is append-only; `BalanceLedger` is its fold; `applyDelta` is the only door. One door = one correctness argument.
4. **Money is `long` cents or `BigDecimal`, and remainders must be assigned deterministically** — otherwise Σ(parts) ≠ whole and your ledger silently leaks.

## Next Problem

**Movie Ticket Booking System** (Hard) — it deliberately re-introduces what Splitwise let you avoid: seat selection is a *non-commutative* multi-resource claim, so you'll combine Concert Ticket's claim-or-compensate with Airline's reserve-with-TTL, and contrast it against today's lock-free ledger. That contrast — when CHM `compute` is enough vs. when you need ordered locks vs. when you need two-phase — is the exact spectrum a senior interview probes.
