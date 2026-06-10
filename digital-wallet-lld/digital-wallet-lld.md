# Digital Wallet Service — Low Level Design

Java 11+, framework-agnostic. Follows the reference structure (Singleton facade → Account → Transaction → PaymentMethod hierarchy → Currency enum) but replaces the reference's global `synchronized transferFunds` with **per-account locks + deterministic lock ordering** — the single most probed decision in this problem.

---

## Step 1 — Requirements

### Functional Requirements

1. **User management** — create users; a user can own multiple accounts.
2. **Account management** — create accounts; each account is denominated in exactly one currency and holds a `BigDecimal` balance.
3. **Payment methods** — add/remove payment methods (credit card, bank account) linked to a user; used for top-ups and external settlement.
4. **Fund transfer** — transfer between any two accounts (same user or different users). The amount is expressed in a stated currency; the system converts to the source account's currency for the debit and the destination account's currency for the credit.
5. **Transaction history** — every transfer is recorded as an immutable `Transaction` in both accounts; a statement (ordered list) can be retrieved per account.
6. **Multi-currency** — USD, EUR, GBP, JPY supported; conversion via a pluggable exchange-rate source.
7. **Validation** — reject negative/zero amounts, unknown accounts, self-transfers, and transfers exceeding the source balance.

### Non-Functional Requirements

| Concern | Decision |
|---|---|
| **Atomicity / consistency** | A transfer debits and credits as one atomic unit — both happen or neither. Money is conserved (sum of balances changes only by conversion arithmetic, never lost). |
| **Concurrency** | Per-account `ReentrantLock`, acquired in a **global deterministic order (by account id)** so concurrent A→B and B→A transfers can never deadlock. No system-wide lock — independent transfers proceed in parallel. |
| **Precision** | All money is `BigDecimal` with explicit scale and `RoundingMode`. `double` is never used for money. |
| **Auditability** | `Transaction` objects are deeply immutable; history lists are append-only and exposed as unmodifiable copies. |
| **Extensibility** | New payment methods = new subclass (OCP). New rate sources = new `ExchangeRateProvider` implementation (Strategy). Core transfer logic never changes. |
| **Security (scope for LLD)** | Encapsulation (no mutable internals leak), input validation, immutable audit trail. Encryption/auth are deployment concerns, acknowledged but not implemented. |
| **Scale** | In-memory `ConcurrentHashMap` repositories inside the facade; layered so storage can be swapped for a database without touching domain classes. |

**Assumptions:** synchronous settlement; exchange rates are read once per transfer (rate locked at transaction start); no fees in v1; no idempotency key in v1 (deliberately out of scope — see follow-ups).

---

## Step 2 — Entities & Relationships

| Entity | Role | Key relationships |
|---|---|---|
| `DigitalWallet` | Singleton facade/service. Owns the repositories and the transfer algorithm. | **Aggregation** of `User`, `Account`, `PaymentMethod` (it manages them; their lifecycle is conceptually independent of the facade object). **Dependency** on `ExchangeRateProvider`. |
| `User` | Identity holder. | **Aggregation** of `Account` (a user *has* accounts; an account also stands alone in the account registry, so it is not composition). |
| `Account` | The balance holder and the unit of locking. | **Association** back to `User` (belongs to). **Composition** of its `List<Transaction>` history entries *as a record* — an account's statement has no meaning detached from the account. **Dependency** on `Currency`. |
| `Transaction` | Immutable audit record of one transfer. | **Association** to source and destination `Account`; **dependency** on `Currency`. |
| `PaymentMethod` (abstract) | Polymorphic external funding source. | **Association** to owning `User`. Specialized by `CreditCard` and `BankAccount` (**inheritance**). |
| `CreditCard`, `BankAccount` | Concrete payment methods; each knows how to `processPayment`. | Inherit `PaymentMethod`. |
| `Currency` | Enum: `USD, EUR, GBP, JPY`. | Used by `Account`, `Transaction`, converter. |
| `ExchangeRateProvider` | Strategy interface for conversion rates. | Implemented by `StaticExchangeRateProvider` (and later: API-backed, cached, etc.). |
| Exceptions | `InsufficientFundsException`, `InvalidTransferException`, `AccountNotFoundException`. | Thrown by validation and the transfer critical section. |

**Why these relationship choices matter in an interview**

- *User—Account is aggregation, not composition*: the system looks accounts up by id independently of the user (e.g., transfer takes account ids). If you modeled it as composition, every account access would route through a user — awkward and wrong.
- *Account—Transaction is composition (of the record)*: each account keeps its own append-only view of transactions that touched it. The `Transaction` object itself is shared between two accounts' lists — what's composed is the *membership in the statement*, which is why the lists are private and only copies escape.
- *PaymentMethod is an abstract class, not an interface*: subclasses share state (`id`, `user`) and shared constructor logic, plus one abstract behavior. Shared state ⇒ abstract class; pure capability ⇒ interface. Knowing which to pick and why is a frequent probe.

---

## Step 3 — UML Class Design

```
┌────────────────────────────────────────────────────────────┐
│ «singleton» DigitalWallet                                  │
├────────────────────────────────────────────────────────────┤
│ - users: Map<String, User>              (ConcurrentHashMap)│
│ - accounts: Map<String, Account>        (ConcurrentHashMap)│
│ - paymentMethods: Map<String, PaymentMethod>               │
│ - rateProvider: ExchangeRateProvider                       │
├────────────────────────────────────────────────────────────┤
│ + getInstance(): DigitalWallet                             │
│ + createUser(name, email): User                            │
│ + createAccount(userId, currency): Account                 │
│ + addPaymentMethod(pm: PaymentMethod): void                │
│ + removePaymentMethod(id): void                            │
│ + transferFunds(srcId, dstId, amount, currency): Transaction│
│ + getTransactionHistory(accountId): List<Transaction>      │
└────────────┬───────────────────────┬───────────────────────┘
             │ manages 0..*          │ manages 0..*
             ▼                       ▼
┌─────────────────────────┐   ┌─────────────────────────────┐
│ Account                 │   │ «abstract» PaymentMethod    │
├─────────────────────────┤   ├─────────────────────────────┤
│ - id: String  «final»   │   │ # id: String   «final»      │
│ - user: User  «final»   │   │ # user: User   «final»      │
│ - currency: Currency    │   ├─────────────────────────────┤
│ - balance: BigDecimal   │   │ + processPayment(amt, cur)  │
│ - transactions: List<Tx>│   │   : boolean  «abstract»     │
│ - lock: ReentrantLock   │   └────────△───────────△────────┘
├─────────────────────────┤            │implements │implements
│ ~ deposit(amt)          │   ┌────────┴─────┐ ┌───┴────────────┐
│ ~ withdraw(amt)         │   │ CreditCard   │ │ BankAccount    │
│ ~ record(tx)            │   │ - cardNumber │ │ - accountNumber│
│ + getBalance(): BigDec  │   │ - expiry,cvv │ │ - routingNumber│
│ + getTransactions()     │   └──────────────┘ └────────────────┘
└───────┬─────────────────┘
        │ 1 belongs to            ┌──────────────────────────────┐
        ▼                         │ «immutable» Transaction      │
┌──────────────────┐   records    ├──────────────────────────────┤
│ User             │◄──┐  0..*    │ - id, timestamp   «final»    │
├──────────────────┤   └──────────│ - source, destination: Acct  │
│ - id, name, email│              │ - amount: BigDecimal         │
│ - accounts: List │              │ - currency: Currency ──► «enum» Currency
└──────────────────┘              └──────────────────────────────┘   USD EUR GBP JPY

┌──────────────────────────────┐      ┌───────────────────────────────┐
│ «interface»                  │      │ StaticExchangeRateProvider    │
│ ExchangeRateProvider         │◄─────│ (rates pivoted through USD)   │
│ + getRate(from,to): BigDec   │      └───────────────────────────────┘
└──────────────────────────────┘
```

Multiplicities: `User 1 — 0..* Account`, `Account 1 — 0..* Transaction` (each Transaction referenced by exactly 2 accounts), `User 1 — 0..* PaymentMethod`.

### Design patterns and exactly why each fits

- **Singleton (`DigitalWallet`)** — there must be exactly one registry of accounts and one transfer coordinator per process, otherwise two facades could each hold a "copy" of an account and the conservation invariant dies. Implemented with the **initialization-on-demand holder idiom**: the JVM's class-loading guarantees give thread-safe lazy init with zero locking — strictly better than the reference's `static synchronized getInstance()`, which takes a lock on every call. *(Spring note: in Spring Boot you'd never hand-roll this — a `@Service` bean is a container-managed singleton, and `ExchangeRateProvider` would be constructor-injected. Same pattern, container does the lifecycle.)*
- **Strategy (`ExchangeRateProvider`)** — conversion rates are a volatile external concern. The transfer algorithm depends on the interface; static rates, an HTTP-backed provider, or a cached decorator can be swapped without touching `transferFunds`. This is DIP applied to requirement 5.
- **Template-style polymorphism on `PaymentMethod`** — the facade treats every payment method uniformly through `processPayment(amount, currency)`; adding PayPal/UPI is a new subclass, zero changes elsewhere (OCP). Creation can be wrapped in a **Factory** (`PaymentMethodFactory.create(type, ...)`) once types multiply — noted but not essential at two types.
- **Facade (`DigitalWallet`)** — clients see one coarse API (`transferFunds`, `getTransactionHistory`) instead of orchestrating accounts, locks, conversion, and recording themselves. The locking protocol lives in exactly one place — which is precisely what makes it auditable.

### SOLID mapping

- **S** — `Account` guards a balance; `Transaction` records history; `ExchangeRateProvider` converts; the facade orchestrates. No class has two reasons to change.
- **O** — new payment methods and new rate sources are additions, not modifications.
- **L** — any `PaymentMethod` subtype is substitutable wherever the abstract type is used; `processPayment` honors the same contract (boolean success, no side effect on wallet balances).
- **I** — `ExchangeRateProvider` is a single-method interface; no client is forced to depend on methods it doesn't use.
- **D** — `transferFunds` depends on the `ExchangeRateProvider` abstraction, injected at construction, never on a concrete rate table.

### The two decisions an interviewer will probe

1. **Why per-account locks instead of one `synchronized transferFunds`?** The global lock (used in the reference solution) is *correct* but serializes all transfers system-wide: with 1M users, two strangers' transfers contend on the same monitor. Per-account locking lets disjoint transfers run fully in parallel; only transfers sharing an account serialize. The cost is that you now own the deadlock problem — solved with lock ordering (below).
2. **Why lock ordering by account id?** Thread 1 does A→B, thread 2 does B→A. If each locks its source first: T1 holds A waits for B, T2 holds B waits for A — classic deadlock. Rule: *always acquire the lock of the lexicographically smaller account id first*, regardless of transfer direction. A global total order on lock acquisition makes circular wait — one of the four Coffman conditions — impossible, so deadlock cannot occur.

---

## Step 4 — Implementation

> Foundational refresher — `ReentrantLock` vs `synchronized`: both give mutual exclusion and a memory-visibility barrier. `ReentrantLock` additionally offers `tryLock(timeout)` (deadlock escape hatch), interruptible acquisition, and fairness — and it can be acquired in one method and released in another, which `synchronized` cannot. We use it here because the transfer must hold **two** locks across a multi-step critical section, and we want the `tryLock` follow-up available.

```java
// ───────────────────────── Currency & money rules ─────────────────────────
public enum Currency {
    USD(2), EUR(2), GBP(2), JPY(0);   // JPY has no minor unit — scale matters!

    private final int scale;
    Currency(int scale) { this.scale = scale; }
    public int getScale() { return scale; }
}
```

```java
// ───────────────────────── Strategy: exchange rates ───────────────────────
public interface ExchangeRateProvider {
    /** Rate such that: amountInTo = amountInFrom * rate. Never null. */
    BigDecimal getRate(Currency from, Currency to);
}

/** All rates pivot through USD: n*n pairs maintained with n entries. */
public class StaticExchangeRateProvider implements ExchangeRateProvider {
    private final Map<Currency, BigDecimal> toUsd = Map.of(
        Currency.USD, BigDecimal.ONE,
        Currency.EUR, new BigDecimal("1.08"),
        Currency.GBP, new BigDecimal("1.26"),
        Currency.JPY, new BigDecimal("0.0064"));

    @Override
    public BigDecimal getRate(Currency from, Currency to) {
        if (from == to) return BigDecimal.ONE;
        // from→USD→to ; 10-digit intermediate precision, rounded at the edge
        return toUsd.get(from).divide(toUsd.get(to), 10, RoundingMode.HALF_EVEN);
    }
}
```

```java
// ───────────────────────── Exceptions ─────────────────────────────────────
public class WalletException extends RuntimeException {
    public WalletException(String msg) { super(msg); }
}
public class AccountNotFoundException extends WalletException {
    public AccountNotFoundException(String id) { super("No account: " + id); }
}
public class InvalidTransferException extends WalletException {
    public InvalidTransferException(String msg) { super(msg); }
}
public class InsufficientFundsException extends WalletException {
    public InsufficientFundsException(String accountId) {
        super("Insufficient funds in account " + accountId);
    }
}
```

```java
// ───────────────────────── User ───────────────────────────────────────────
public class User {
    private final String id;
    private final String name;
    private final String email;
    // Thread-safe: accounts may be added while another thread iterates.
    private final List<Account> accounts = new CopyOnWriteArrayList<>();

    public User(String id, String name, String email) {
        this.id = id; this.name = name; this.email = email;
    }
    void addAccount(Account a) { accounts.add(a); }
    public String getId() { return id; }
    public String getName() { return name; }
    public List<Account> getAccounts() { return List.copyOf(accounts); } // no leak
}
```

```java
// ───────────────────────── Account: the unit of locking ───────────────────
public class Account {
    private final String id;
    private final User user;
    private final Currency currency;
    private final ReentrantLock lock = new ReentrantLock();
    private BigDecimal balance;                       // guarded by lock
    private final List<Transaction> transactions = new ArrayList<>(); // guarded by lock

    public Account(String id, User user, Currency currency) {
        this.id = id; this.user = user; this.currency = currency;
        this.balance = BigDecimal.ZERO.setScale(currency.getScale());
    }

    /** Exposed so the facade can order multi-account acquisitions. */
    ReentrantLock getLock() { return lock; }

    // Package-private: only the facade mutates balances, and only under lock.
    void deposit(BigDecimal amount) {
        ensureLocked();
        balance = balance.add(amount).setScale(currency.getScale(), RoundingMode.HALF_EVEN);
    }

    void withdraw(BigDecimal amount) {
        ensureLocked();
        if (balance.compareTo(amount) < 0) throw new InsufficientFundsException(id);
        balance = balance.subtract(amount).setScale(currency.getScale(), RoundingMode.HALF_EVEN);
    }

    void record(Transaction tx) { ensureLocked(); transactions.add(tx); }

    /** Fail fast if anyone tries to mutate outside the locking protocol. */
    private void ensureLocked() {
        if (!lock.isHeldByCurrentThread())
            throw new IllegalStateException("Account " + id + " mutated without its lock");
    }

    public BigDecimal getBalance() {
        lock.lock();                       // read under lock → visibility guaranteed
        try { return balance; } finally { lock.unlock(); }
    }

    public List<Transaction> getTransactions() {
        lock.lock();
        try { return List.copyOf(transactions); }  // defensive copy, immutable
        finally { lock.unlock(); }
    }

    public String getId() { return id; }
    public User getUser() { return user; }
    public Currency getCurrency() { return currency; }
}
```

```java
// ───────────────────────── Transaction: immutable audit record ────────────
public final class Transaction {
    private final String id;
    private final String sourceAccountId;
    private final String destinationAccountId;
    private final BigDecimal amount;        // in `currency`, as requested
    private final Currency currency;
    private final LocalDateTime timestamp;

    public Transaction(String id, String src, String dst,
                       BigDecimal amount, Currency currency) {
        this.id = id; this.sourceAccountId = src; this.destinationAccountId = dst;
        this.amount = amount; this.currency = currency;
        this.timestamp = LocalDateTime.now();
    }
    // getters only — no setters, final class, final fields: deeply immutable
    public String getId() { return id; }
    public String getSourceAccountId() { return sourceAccountId; }
    public String getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
```

```java
// ───────────────────────── PaymentMethod hierarchy ────────────────────────
public abstract class PaymentMethod {
    protected final String id;
    protected final User user;

    protected PaymentMethod(String id, User user) { this.id = id; this.user = user; }
    public abstract boolean processPayment(BigDecimal amount, Currency currency);
    public String getId() { return id; }
    public User getUser() { return user; }
}

public class CreditCard extends PaymentMethod {
    private final String cardNumber;   // real system: store a token, never PAN
    private final String expiry;
    private final String cvv;

    public CreditCard(String id, User user, String cardNumber, String expiry, String cvv) {
        super(id, user);
        this.cardNumber = cardNumber; this.expiry = expiry; this.cvv = cvv;
    }
    @Override public boolean processPayment(BigDecimal amount, Currency currency) {
        return amount.signum() > 0;    // stub for the card-network call
    }
}

public class BankAccount extends PaymentMethod {
    private final String accountNumber;
    private final String routingNumber;

    public BankAccount(String id, User user, String accountNumber, String routingNumber) {
        super(id, user);
        this.accountNumber = accountNumber; this.routingNumber = routingNumber;
    }
    @Override public boolean processPayment(BigDecimal amount, Currency currency) {
        return amount.signum() > 0;    // stub for the ACH/wire call
    }
}
```

```java
// ───────────────────────── The facade ─────────────────────────────────────
public class DigitalWallet {

    /** Holder idiom: thread-safe lazy init, no lock on the hot getInstance() path. */
    private static class Holder { static final DigitalWallet INSTANCE = new DigitalWallet(); }
    public static DigitalWallet getInstance() { return Holder.INSTANCE; }

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, PaymentMethod> paymentMethods = new ConcurrentHashMap<>();
    private final ExchangeRateProvider rateProvider = new StaticExchangeRateProvider();

    private DigitalWallet() { }

    // ── registration ──
    public User createUser(String name, String email) {
        User u = new User(UUID.randomUUID().toString(), name, email);
        users.put(u.getId(), u);
        return u;
    }

    public Account createAccount(String userId, Currency currency) {
        User user = users.get(userId);
        if (user == null) throw new WalletException("No user: " + userId);
        Account a = new Account(UUID.randomUUID().toString(), user, currency);
        accounts.put(a.getId(), a);
        user.addAccount(a);
        return a;
    }

    public void addPaymentMethod(PaymentMethod pm) { paymentMethods.put(pm.getId(), pm); }
    public void removePaymentMethod(String id) { paymentMethods.remove(id); }

    // ── the interesting part ──
    public Transaction transferFunds(String sourceId, String destinationId,
                                     BigDecimal amount, Currency currency) {
        // 1. Validate BEFORE taking any lock — fail fast, hold locks briefly.
        if (amount == null || amount.signum() <= 0)
            throw new InvalidTransferException("Amount must be positive");
        if (sourceId.equals(destinationId))
            throw new InvalidTransferException("Source and destination are the same");
        Account src = requireAccount(sourceId);
        Account dst = requireAccount(destinationId);

        // 2. Fix conversion OUTSIDE the critical section: never do (potentially
        //    remote) rate lookups while holding account locks.
        BigDecimal debit  = convert(amount, currency, src.getCurrency());
        BigDecimal credit = convert(amount, currency, dst.getCurrency());

        // 3. DEADLOCK PREVENTION: total order on lock acquisition (by account id).
        //    A→B and B→A both lock min(A,B) first → circular wait is impossible.
        Account first  = src.getId().compareTo(dst.getId()) < 0 ? src : dst;
        Account second = first == src ? dst : src;

        first.getLock().lock();
        try {
            second.getLock().lock();
            try {
                // 4. Critical section: check-then-act is atomic because the
                //    balance check and the debit happen under the same lock.
                src.withdraw(debit);                       // throws → nothing credited
                dst.deposit(credit);
                Transaction tx = new Transaction(UUID.randomUUID().toString(),
                        src.getId(), dst.getId(), amount, currency);
                src.record(tx);
                dst.record(tx);
                return tx;
            } finally { second.getLock().unlock(); }
        } finally { first.getLock().unlock(); }
    }

    public List<Transaction> getTransactionHistory(String accountId) {
        return requireAccount(accountId).getTransactions();
    }

    private Account requireAccount(String id) {
        Account a = accounts.get(id);
        if (a == null) throw new AccountNotFoundException(id);
        return a;
    }

    private BigDecimal convert(BigDecimal amount, Currency from, Currency to) {
        return amount.multiply(rateProvider.getRate(from, to))
                     .setScale(to.getScale(), RoundingMode.HALF_EVEN);
    }
}
```

```java
// ───────────────────────── Demo ───────────────────────────────────────────
public class WalletDemo {
    public static void main(String[] args) {
        DigitalWallet w = DigitalWallet.getInstance();
        User alice = w.createUser("Alice", "alice@x.com");
        User bob   = w.createUser("Bob", "bob@x.com");

        Account aUsd = w.createAccount(alice.getId(), Currency.USD);
        Account bEur = w.createAccount(bob.getId(),   Currency.EUR);

        // seed Alice via her card (top-up = external processPayment + deposit)
        w.addPaymentMethod(new CreditCard("pm1", alice, "4111-1111", "12/27", "123"));
        aUsd.getLock().lock();
        try { aUsd.deposit(new BigDecimal("500.00")); } finally { aUsd.getLock().unlock(); }

        Transaction tx = w.transferFunds(aUsd.getId(), bEur.getId(),
                new BigDecimal("100.00"), Currency.USD);

        System.out.println("Alice USD: " + aUsd.getBalance()); // 400.00
        System.out.println("Bob   EUR: " + bEur.getBalance()); // ~92.59
        System.out.println("Tx: " + tx.getId() + " at " + tx.getTimestamp());
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Exception strategy

- Unchecked domain exceptions under a common `WalletException` root: callers can catch broadly or precisely; the transfer API stays clean (no checked-exception clutter), which mirrors how Spring services surface domain errors.
- **Validation before locking** — invalid amount, unknown account, self-transfer are rejected before any lock is taken, so bad requests never increase contention.
- **Atomic failure** — `withdraw` is called before `deposit` inside the critical section; if it throws `InsufficientFundsException`, no credit has occurred and both locks unwind via `finally`. Nothing to roll back because nothing was half-done.
- `ensureLocked()` turns a protocol violation (mutating an account without its lock) into an immediate `IllegalStateException` instead of a silent race — defensive design interviewers like to see.

### Edge cases

| Case | Handling |
|---|---|
| Zero / negative / null amount | `InvalidTransferException` pre-lock |
| Self-transfer (src == dst) | Rejected pre-lock. Also prevents the subtle bug of acquiring the *same* `ReentrantLock` twice (legal because reentrant, but a logic error here) |
| Unknown account id | `AccountNotFoundException` |
| Insufficient funds | Checked under the source lock — no TOCTOU gap between check and debit |
| JPY (0-decimal currency) | `Currency.getScale()` drives `setScale`; ¥100.5 can never exist |
| Rounding | `HALF_EVEN` (banker's rounding) everywhere — minimizes cumulative drift |
| Exact-balance transfer | `compareTo < 0` (not `<= 0`) — draining to exactly zero is allowed |
| `BigDecimal` equality | Always `compareTo`, never `equals` (`1.0 ≠ 1.00` under `equals`) |
| Removing a payment method mid-payment | Registry removal is independent of an in-flight `processPayment`; the in-flight call completes on its own reference |

### Concurrency analysis (the part to be able to argue out loud)

**Shared mutable state:** `Account.balance` and `Account.transactions` (guarded by the account's `ReentrantLock`); the three registries (`ConcurrentHashMap` — safe for concurrent put/get without external locking).

**Critical section:** in `transferFunds`, steps balance-check → debit → credit → record-twice, executed while holding **both** account locks.

**Why each hazard is absent:**

- **Race conditions / lost updates:** every read-modify-write of a balance happens under that account's lock; the insufficient-funds *check* and the *debit* sit in the same critical section, closing the check-then-act window. Two threads draining the same account cannot both pass the check.
- **Visibility:** lock acquire/release establish happens-before edges, so a balance written by one thread is visible to the next thread that locks the account. No `volatile` needed.
- **Deadlock:** impossible by construction. Locks are always acquired in ascending account-id order — a global total order kills the circular-wait condition (one of the four Coffman conditions; remove any one and deadlock cannot form). The concurrent A→B / B→A pair both lock `min(A,B)` first, so one simply waits.
- **Livelock / starvation:** plain blocking `lock()` (not a retry loop) → no livelock. Default unfair locks allow barging; under pathological contention on one hot account a fair lock (`new ReentrantLock(true)`) trades throughput for FIFO fairness — name the trade-off if asked.
- **Why not the reference's `synchronized transferFunds`:** correct, but it is one global lock — *every* transfer in the system serializes, even between unrelated accounts. Per-account locking shrinks the contention domain from "the whole wallet" to "these two accounts." That's the difference between a toy and a design that survives the "now scale it 10×" follow-up.
- **Granularity trade-off stated honestly:** per-account locking still serializes all transfers touching one celebrity/merchant account. The next escalation levels are `tryLock(timeout)` + retry with backoff, sharded/escrowed sub-balances, or optimistic versioned updates — see follow-ups.

---

## Likely Interviewer Follow-ups (with model answers)

1. **"Two identical transfer requests arrive (client retry). Now what?"** Add an idempotency key: client sends a unique request id; the facade keeps a `ConcurrentHashMap<String, Transaction>` and uses `computeIfAbsent`-style gating so a duplicate returns the original `Transaction` instead of double-debiting. We deliberately scoped this out of v1.
2. **"Make this survive a process crash / move to a database."** Replace in-memory maps with repositories; the two-lock critical section becomes a DB transaction — `SELECT ... FOR UPDATE` on both account rows **in id order** (same lock-ordering idea, now at the row level), or optimistic locking with a `version` column and retry. The conservation invariant moves into the transaction boundary.
3. **"One merchant account receives 10k transfers/sec — your per-account lock is now the bottleneck."** Options: (a) `tryLock` with timeout + bounded retry to shed load; (b) shard the hot account into N sub-balances and credit a random shard (reads sum the shards); (c) queue credits through a single-writer `BlockingQueue` consumer for that account — debits still need the real balance, credits don't.
4. **"Exchange rate changes between validation and execution?"** We read the rate once, before locking — the transfer executes at the quoted rate, which is the honest contract. For strictness, pass a client-quoted rate + max-slippage and reject inside the critical section if the current rate moved beyond tolerance.
5. **"Add a transfer fee without touching transferFunds."** Introduce a `FeePolicy` strategy (`BigDecimal feeFor(amount, currency, src, dst)`), injected like the rate provider; the debit becomes `amount + fee`, with the fee credited to a system account inside the same critical section (lock ordering now over three accounts — same ascending-id rule generalizes to any number of locks).

## Transferable Lesson

**Lock ordering as deadlock prevention** is *the* reusable takeaway: whenever an operation must atomically touch ≥2 lockable resources (two accounts here, two forks in Dining Philosophers, source/destination spots in a parking-lot valet transfer, two bank branches), impose a global total order on acquisition and the circular wait disappears. Pair it with the supporting habits — validate before locking, do I/O outside the critical section, keep critical sections tiny — and you have the template for every "transfer between two X" problem. Secondary lessons: **Strategy for volatile external policies** (rates, fees) and **immutable audit records** reappear in Splitwise, Stock Exchange, and Hotel Booking.

## Next Problem

Natural successor: **Splitwise / Expense Sharing** (Medium) — reuses immutable transactions and BigDecimal discipline, adds the Strategy pattern for split types (equal / exact / percentage) and a balance-simplification algorithm. If you'd rather go straight at concurrency, **ATM** or **Movie Ticket Booking** (seat locking) both reuse today's lock-ordering idea in a new costume.
