# ATM System — Low Level Design

> Problem #6 in our sequence (after Logging Framework, Parking Lot, Coffee Vending Machine, Vending Machine, Traffic Signal). The ATM is the **pattern-synthesis problem**: State (session lifecycle, from Vending Machine) + atomic check-then-act inventory (from Coffee Machine) + Chain of Responsibility (note dispensing, new) + the **debit/dispense consistency protocol against a remote backend** (new, and the part interviewers probe hardest).
>
> All code below compiles on Java 11+ and was executed; every scenario in §5, including the reversal path and the timeout race, was verified at runtime.

---

## Step 1 — Requirements

### Functional Requirements

1. **Authentication**: insert card → enter PIN → validate the card+PIN pair against the **bank backend** (never locally). 3 attempts, then the card is blocked at the bank and retained.
2. **Balance inquiry**: fetched live from the bank on every request — never cached, because any other channel (mobile app, another ATM) can change it at any moment.
3. **Cash withdrawal**: amount must be (a) a positive multiple of ₹100 within the per-transaction limit, (b) composable from the ATM's physical per-denomination inventory, (c) covered by the account balance (bank's call). Debit the account **and** dispense the cash — and survive the failure of either half.
4. **Cash deposit (cash-counting)**: inserted notes are counted, the account is credited, and the notes **join the dispensable inventory** — deposits and withdrawals therefore mutate the *same* shared state.
5. **Bank backend interaction**: the ATM is a *client* of a remote `BankService` (authenticate, balance, debit, credit, reverse). The ATM owns only its local cash.
6. **Session lifecycle with timeout**: card in → authenticate → N transactions → card out. A session ends explicitly (eject), on failure (blocked card), or **implicitly via inactivity timeout** enforced by a timer thread.

### Non-Functional Requirements

- **Consistency over availability.** The hard invariant: *never dispense cash without a confirmed debit; never leave a debit standing without dispensed cash.* Cash is physically irrecoverable once dispensed; a debit is reversible. This asymmetry dictates the entire withdrawal protocol.
- **Concurrency.** A physical ATM serves one user at a time, so concurrency comes from exactly two places: (a) the **timeout timer thread** racing the user-driven thread over session state, and (b) the **same account** being hit from other channels — which is the bank's problem to serialize, but our protocol must tolerate it.
- **Extensibility.** New transaction types (PIN change, mini-statement, transfer) must slot into the authenticated session without touching auth/session machinery.
- **Hardware abstraction.** Card reader, dispenser, keypad, screen sit behind interfaces so core logic is testable without hardware. (Elided from the code below to keep focus on the interesting parts; the seam is noted where it belongs.)
- **Money is integral.** `long` rupees, never `double`. Floating-point money is an automatic interview wound.

### Assumptions Locked In

- Single ATM, one active session; bank abstracted as a `BankService` interface — we design the ATM side and the *protocol*, not the bank's ledger.
- Denominations ₹500/₹200/₹100; greedy largest-first dispensing (limitation documented in §5); withdrawals are multiples of ₹100; per-transaction max ₹20,000.
- Bank enforces account/daily limits (the ATM cannot know what you withdrew elsewhere); ATM enforces per-transaction max and dispensability.
- PIN validation is **remote, always** — the ATM forwards the PIN and never stores or compares it.
- Session timeout in scope; cash-counting deposit in scope (both per our checkpoint decision).

---

## Step 2 — Entities & Relationships

| Entity | Kind | Responsibility |
|---|---|---|
| `Denomination` | enum | ₹500/₹200/₹100, with face value. Declaration order = dispense order. |
| `Card` | value object | A dumb token carrying only the card number. No PIN, no balance. |
| `BankService` | interface | The remote bank: authenticate, balance, **atomic debit returning a txn id**, credit, **reverse**. |
| `MockBankService` | class | In-memory bank with per-account locking and idempotent reversal. |
| `CashInventory` | class | The ATM's physical cash: per-denomination counts + atomic `tryDispense` / `deposit`. |
| `DenominationHandler` | class | One Chain-of-Responsibility link per denomination; plans notes greedily. |
| `AtmState` | interface | State pattern contract; every operation defaults to "not allowed here". |
| `IdleState`, `CardInsertedState`, `AuthenticatedState` | classes | The three legal session phases. |
| `Atm` | class | Facade + State context + session/timeout owner. The single synchronized entry point. |
| `AtmException` + leaves | exceptions | `InvalidAmount`, `InsufficientFunds`, `CashNotDispensable`, `AuthenticationFailed`, `CardBlocked`. |

### Relationships (with type and why)

- **`Atm` ─◆ `CashInventory` — composition.** The inventory has no identity or lifetime apart from its machine; it is created with the ATM and dies with it. Nobody else may hold a reference (that would bypass the locking discipline).
- **`Atm` ─◇ `BankService` — aggregation (shared dependency, injected).** The bank exists independently of any ATM and is shared by thousands of them. Injected via constructor — in Spring terms, `BankService` is exactly the kind of collaborator you'd `@Autowired` as a singleton-scoped bean, while each physical `Atm` would be its own component owning its own composed inventory.
- **`Atm` ─→ `AtmState` — association (replaceable part).** The ATM *has a* current state, but states are swapped at runtime; each concrete state object lives only as long as its phase of the session. `CardInsertedState` is deliberately instantiated fresh per card insert so its PIN-attempt counter resets *by construction*.
- **`AtmState` ─ ─→ `Atm` — dependency.** States receive the `Atm` as a method parameter and call back into it (`setState`, `bank()`, `inventory()`); they hold no reference and no shared mutable state of their own.
- **`CashInventory` ─◆ `DenominationHandler` chain — composition.** The chain is built inside the inventory's constructor and is meaningless outside it; it must only ever run under the inventory's lock.
- **`Atm` ─→ `Card` — association (temporal).** The current session's card; null when idle.
- **`MockBankService` ─◆ `Account` — composition.** Accounts are private internals of the bank; the ATM never sees them — that boundary *is* requirement 3.

What's deliberately **not** modeled: a `User`/`Customer` class (the ATM only ever knows a card), a `Transaction` class hierarchy (Command pattern — a worthy extension, discussed in follow-ups, but premature for three operations), and hardware classes (`Screen`, `Keypad`, `CardReader` — real, but boilerplate; their seam is the `Atm` facade's method boundary).

---

## Step 3 — UML Class Design

```
                          <<interface>>
                           BankService
   +isBlocked(card) +blockCard(card) +authenticate(card,pin)
   +getBalance(card) +debit(card,amt): txnId  +credit(card,amt)
   +reverse(txnId)
                                △
                                │ implements
                        MockBankService
                  -accounts: Map<String,Account>   ◆── Account (-balance, -blocked, -pin)
                  -debits:   Map<String,Txn>

                                ◇ injected (aggregation)
                                │
 ┌──────────────────────────────┴───────────────────────────────┐
 │                            Atm                                │
 │  -state: AtmState          -currentCard: Card                 │
 │  -inventory: CashInventory ◆ (composition)                    │
 │  -bank: BankService        -lastActivityAt: long              │
 │  -timeoutMonitor: ScheduledExecutorService                    │
 │  +insertCard(card) +enterPin(pin) +checkBalance()             │
 │  +withdraw(amt): Map<Denomination,Integer>                    │
 │  +deposit(notes) +ejectCard()        [all synchronized]       │
 │  ~setState(s) ~beginSession(c) ~endSession()                  │
 └──────────────┬────────────────────────────────────────────────┘
                │ current (association, swapped at runtime)
                ▼
         <<interface>> AtmState
   +insertCard +enterPin +checkBalance +withdraw +deposit +ejectCard
   (every method defaults to throwing IllegalStateException)
        △            △                 △
        │            │                 │
   IdleState   CardInsertedState   AuthenticatedState
               -attempts: int      (withdraw protocol,
                                    deposit, balance)

 CashInventory                          DenominationHandler
 -counts: EnumMap<Denomination,int>  ◆──-denom: Denomination
 -chain: DenominationHandler  1 ───▶ *  -next: DenominationHandler
 +canDispense(amt) +tryDispense(amt)    +plan(amt, available, plan): int
 +deposit(notes) +unloadAll()           (500 ▶ 200 ▶ 100)
 [every public method synchronized]

 Denomination (enum): FIVE_HUNDRED(500) ▶ TWO_HUNDRED(200) ▶ HUNDRED(100)
 Card: -cardNumber          AtmException ◁─ {InvalidAmount, InsufficientFunds,
                                             CashNotDispensable, AuthenticationFailed,
                                             CardBlocked}
```

### Design Patterns — and exactly why each fits

**State** (`AtmState` + three concrete states). The ATM's legality rules are *positional*: `withdraw` is meaningless before authentication, `enterPin` is meaningless before a card. Encoding each phase as a class makes illegal sequences impossible by construction — each state overrides only what it legally supports, everything else hits the interface's default `reject`. The payoff over an enum + `switch`: per-state *data* travels with the state object. `CardInsertedState` owns its `attempts` counter, and because a fresh state object is created per card insert, the counter resets without a single line of reset logic. This is the same pattern as Vending Machine, with one upgrade: state transitions are now triggered by **two** threads (user actions and the timeout timer), which is why all entry points are serialized at the `Atm` facade.

**Chain of Responsibility** (`DenominationHandler`: 500 ▶ 200 ▶ 100). Each handler plans as many of its own notes as it can and passes the remainder to its successor; whatever survives the whole chain is the "cannot compose" verdict. Why CoR and not a plain loop? Mechanically a loop over a sorted map is equivalent — and saying so out loud is worth points — but CoR earns its keep on *extensibility along the chain axis*: adding a ₹2000 cassette, removing a denomination at runtime (cassette empty/faulted), or inserting a policy link ("never dispense more than 20 notes of any one denomination") is a re-link, not a rewrite. The classic presentation uses one subclass per note; parameterizing a single handler by `Denomination` is the same pattern minus the duplication. One non-negotiable: the chain only ever runs **inside the inventory's lock** — a plan computed outside the critical section is fiction by the time you commit it.

**Facade** (`Atm`). The user-facing surface is six verbs. Behind it: state machinery, bank protocol, inventory locking, timeout monitoring. The facade is also the **concurrency boundary** — every public method is `synchronized`, which is what lets the states themselves stay lock-free.

**Strategy — present as a seam, deliberately not built.** `BankService` is a swappable backend (mock vs. real ISO-8583 gateway) — that's Strategy/dependency-inversion in spirit. A `DispensePolicy` strategy (greedy vs. bounded-DP vs. fewest-notes) is the natural refactor when the greedy limitation (§5) bites. Naming the seam without building it is the right interview move: *the absence of a pattern is a design decision.*

**Singleton — rejected.** One `Atm` per physical machine, but enforcing that with a `getInstance()` is the wrong tool: it kills testability (we spin up a second rig — `atm2` — in the demo to prove the reversal path) and the real cardinality constraint is "one per *hardware unit*", which the composition root (or Spring's singleton bean scope) expresses better than a private constructor.

### SOLID mapping

- **S**: `CashInventory` knows notes; `BankService` knows money-as-numbers; states know legality; `Atm` knows orchestration. The bug surface of "cash math" never touches the bug surface of "session legality".
- **O**: a new transaction type = one new method pair (state + facade) or a Command object; a new denomination = one enum constant + one chain link. Neither touches existing logic.
- **L**: every `AtmState` honors the same contract — callers (the `Atm`) never type-check which state they hold; unsupported operations fail uniformly via the default `reject`.
- **I**: `AtmState` could be split (`CardOperations` vs. `TransactionOperations`) if it grew; at six methods with safe defaults, splitting would be ceremony. Saying *why not* is the point.
- **D**: `Atm` depends on the `BankService` abstraction, never `MockBankService`. The whole demo, including the fault-injecting bank wrapper, exists because of this inversion.

### The two decisions an interviewer will probe

1. **Why debit *before* dispense?** Because the failure modes are asymmetric: a debit has an undo (`reverse(txnId)`); dispensed cash does not. Order the steps so the **irreversible action goes last**, and the only bad interleaving (debit succeeded, dispense failed) has a compensating transaction. The opposite order has an unfixable hole: dispense succeeds, network dies, debit never lands — the bank just gave away free money.
2. **Where exactly is the critical section in dispensing — and why is `canDispense` not it?** `canDispense` is an advisory dry run to avoid pointless network debits; the *authoritative* check is fused with the commit inside `tryDispense` under one lock (the same check-then-act fusion as Coffee Machine's `tryConsume`). Between the pre-check and the dispense, the bank round-trip happens with **no inventory lock held** — deliberately, because holding a local lock across a network call is how you turn a slow bank into a frozen machine. The price is a TOCTOU window, which the reversal protocol pays for.

---

## Step 4 — Implementation

Compilable Java (single file for the demo; in a repo each top-level class gets its own file). Getters/setters and hardware shims are trimmed; the patterns, the protocol, and the concurrency are complete.

### 4.1 Denominations, card, exceptions

```java
/** Declaration order = dispense order. EnumMap iterates in declaration
 *  order, so largest-first greedy falls out of the enum itself. */
enum Denomination {
    FIVE_HUNDRED(500), TWO_HUNDRED(200), HUNDRED(100);

    final int value;
    Denomination(int value) { this.value = value; }
}

/** The card is a dumb token: it identifies an account, nothing more.
 *  PINs are never stored on (or compared by) the ATM. */
final class Card {
    private final String cardNumber;
    Card(String cardNumber) { this.cardNumber = cardNumber; }
    String number() { return cardNumber; }
    @Override public String toString() { return "Card[" + cardNumber + "]"; }
}

class AtmException extends RuntimeException { AtmException(String m) { super(m); } }
class InvalidAmountException        extends AtmException { InvalidAmountException(String m)        { super(m); } }
class InsufficientFundsException    extends AtmException { InsufficientFundsException(String m)    { super(m); } }
class CashNotDispensableException   extends AtmException { CashNotDispensableException(String m)   { super(m); } }
class AuthenticationFailedException extends AtmException { AuthenticationFailedException(String m) { super(m); } }
class CardBlockedException          extends AtmException { CardBlockedException(String m)          { super(m); } }
```

### 4.2 The bank boundary

```java
interface BankService {
    boolean isBlocked(String cardNumber);
    void blockCard(String cardNumber);
    /** Remote PIN check. The ATM forwards the PIN; it never validates locally. */
    boolean authenticate(String cardNumber, String pin);
    long getBalance(String cardNumber);
    /** Atomic check-then-act debit on the bank side. Returns a transaction id
     *  usable for reversal - this id is the hook for our consistency protocol. */
    String debit(String cardNumber, long amount) throws InsufficientFundsException;
    void credit(String cardNumber, long amount);
    /** Compensating transaction: undo a debit whose cash was never dispensed. */
    void reverse(String transactionId);
}

/** In-memory stand-in. The important part is the CONTRACT:
 *  per-account atomic debit, and reversible, idempotent transactions. */
class MockBankService implements BankService {

    private static final class Account {
        long balance;                 // guarded by synchronized(account)
        volatile boolean blocked;
        final String pin;
        Account(long balance, String pin) { this.balance = balance; this.pin = pin; }
    }
    private static final class Txn {
        final String card; final long amount;
        Txn(String card, long amount) { this.card = card; this.amount = amount; }
    }

    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Txn> debits = new ConcurrentHashMap<>();

    void addAccount(String cardNumber, String pin, long openingBalance) {
        accounts.put(cardNumber, new Account(openingBalance, pin));
    }

    private Account account(String cardNumber) {
        Account a = accounts.get(cardNumber);
        if (a == null) throw new AuthenticationFailedException("Unknown card " + cardNumber);
        return a;
    }

    @Override public boolean isBlocked(String c) { return account(c).blocked; }
    @Override public void blockCard(String c)    { account(c).blocked = true; }

    @Override public boolean authenticate(String c, String pin) {
        Account a = account(c);
        return !a.blocked && a.pin.equals(pin);
    }

    @Override public long getBalance(String c) {
        Account a = account(c);
        synchronized (a) { return a.balance; }
    }

    /** check-then-act fused under the account's own lock - two ATMs hitting
     *  the same account cannot both pass the balance check. */
    @Override public String debit(String c, long amount) {
        Account a = account(c);
        synchronized (a) {
            if (a.balance < amount)
                throw new InsufficientFundsException(
                        "Balance " + a.balance + " < requested " + amount);
            a.balance -= amount;
        }
        String txnId = UUID.randomUUID().toString();
        debits.put(txnId, new Txn(c, amount));
        return txnId;
    }

    @Override public void credit(String c, long amount) {
        Account a = account(c);
        synchronized (a) { a.balance += amount; }
    }

    /** Idempotent: ConcurrentHashMap.remove wins exactly once, so reversing
     *  the same txnId twice credits only once. */
    @Override public void reverse(String txnId) {
        Txn t = debits.remove(txnId);
        if (t != null) credit(t.card, t.amount);
    }
}
```

### 4.3 Dispensing — Chain of Responsibility under one lock

```java
/** One link per denomination. Classic CoR shows a subclass per note type;
 *  parameterizing one handler by Denomination is the same pattern minus
 *  the duplication. */
class DenominationHandler {
    private final Denomination denom;
    private DenominationHandler next;

    DenominationHandler(Denomination denom) { this.denom = denom; }
    DenominationHandler linkNext(DenominationHandler n) { this.next = n; return n; }

    /** Greedily plans notes of this denomination, passes the remainder down
     *  the chain. Returns the amount the whole chain could NOT compose. */
    int plan(int amount, Map<Denomination, Integer> available, Map<Denomination, Integer> plan) {
        int usable = Math.min(amount / denom.value, available.getOrDefault(denom, 0));
        if (usable > 0) plan.put(denom, usable);
        int remaining = amount - usable * denom.value;
        return (next == null) ? remaining : next.plan(remaining, available, plan);
    }
}

/** The ATM's physical cash. SHARED MUTABLE STATE: withdrawals and
 *  cash-counting deposits both mutate the same per-denomination counts,
 *  so every public method is a critical section on one intrinsic lock. */
class CashInventory {
    private final EnumMap<Denomination, Integer> counts = new EnumMap<>(Denomination.class);
    private final DenominationHandler chain;

    CashInventory(Map<Denomination, Integer> initial) {
        for (Denomination d : Denomination.values())
            counts.put(d, initial.getOrDefault(d, 0));
        DenominationHandler h500 = new DenominationHandler(Denomination.FIVE_HUNDRED);
        h500.linkNext(new DenominationHandler(Denomination.TWO_HUNDRED))
            .linkNext(new DenominationHandler(Denomination.HUNDRED));
        this.chain = h500;
    }

    /** Cheap pre-check (dry run). Advisory only - the authoritative check
     *  is inside tryDispense, because the world can change in between. */
    synchronized boolean canDispense(long amount) { return planFor(amount) != null; }

    /** Atomic check-then-act: plan AND commit under the same lock, or
     *  change nothing at all. Returns null if the amount can't be composed. */
    synchronized Map<Denomination, Integer> tryDispense(long amount) {
        Map<Denomination, Integer> plan = planFor(amount);
        if (plan == null) return null;
        plan.forEach((d, n) -> counts.merge(d, -n, Integer::sum)); // commit
        return plan;
    }

    private Map<Denomination, Integer> planFor(long amount) {
        Map<Denomination, Integer> plan = new EnumMap<>(Denomination.class);
        int remainder = chain.plan((int) amount, counts, plan);
        return remainder == 0 ? plan : null;
    }

    /** Cash-counting deposit: accepted notes become dispensable inventory. */
    synchronized void deposit(Map<Denomination, Integer> notes) {
        notes.forEach((d, n) -> counts.merge(d, n, Integer::sum));
    }

    /** Technician operation (also used to simulate a cassette fault). */
    synchronized Map<Denomination, Integer> unloadAll() {
        Map<Denomination, Integer> removed = new EnumMap<>(counts);
        for (Denomination d : Denomination.values()) counts.put(d, 0);
        return removed;
    }

    synchronized long total() {
        return counts.entrySet().stream()
                .mapToLong(e -> (long) e.getKey().value * e.getValue()).sum();
    }

    synchronized Map<Denomination, Integer> snapshot() { return new EnumMap<>(counts); }
}
```

### 4.4 The State machine

```java
/** Every operation defaults to "not allowed here"; each concrete state
 *  overrides only what it legally supports. Illegal sequences become
 *  impossible by construction instead of by scattered if-checks. */
interface AtmState {
    String name();
    default void insertCard(Atm atm, Card card)                       { reject("insertCard"); }
    default void enterPin(Atm atm, String pin)                        { reject("enterPin"); }
    default long checkBalance(Atm atm)                                { reject("checkBalance"); return 0; }
    default Map<Denomination, Integer> withdraw(Atm atm, long amount) { reject("withdraw"); return null; }
    default void deposit(Atm atm, Map<Denomination, Integer> notes)   { reject("deposit"); }
    default void ejectCard(Atm atm)                                   { reject("ejectCard"); }
    default void reject(String op) {
        throw new IllegalStateException("'" + op + "' is not allowed in state " + name());
    }
}

class IdleState implements AtmState {
    @Override public String name() { return "IDLE"; }

    @Override public void insertCard(Atm atm, Card card) {
        if (atm.bank().isBlocked(card.number()))
            throw new CardBlockedException("Card " + card.number() + " is blocked");
        atm.beginSession(card);
        atm.setState(new CardInsertedState());   // fresh state object => fresh attempt counter
    }

    @Override public void ejectCard(Atm atm) { /* nothing to eject - harmless no-op */ }
}

class CardInsertedState implements AtmState {
    private int attempts = 0;   // per-session by construction: new state object per card insert

    @Override public String name() { return "CARD_INSERTED (awaiting PIN)"; }

    @Override public void enterPin(Atm atm, String pin) {
        if (atm.bank().authenticate(atm.card().number(), pin)) {
            atm.setState(new AuthenticatedState());
            return;
        }
        attempts++;
        if (attempts >= Atm.MAX_PIN_ATTEMPTS) {
            atm.bank().blockCard(atm.card().number());
            atm.endSession();   // card retained, account blocked; back to IDLE
            throw new CardBlockedException("3 wrong PINs - card blocked and retained");
        }
        throw new AuthenticationFailedException(
                "Wrong PIN (" + attempts + "/" + Atm.MAX_PIN_ATTEMPTS + ")");
    }

    @Override public void ejectCard(Atm atm) { atm.endSession(); }
}

class AuthenticatedState implements AtmState {
    @Override public String name() { return "AUTHENTICATED"; }

    @Override public long checkBalance(Atm atm) {
        // Always live from the bank - a cached balance can go stale the moment
        // another channel (mobile app, another ATM) touches the account.
        return atm.bank().getBalance(atm.card().number());
    }

    /** THE design decision of this problem: debit-then-dispense with a
     *  compensating reversal. Cash is irrecoverable once dispensed; a debit
     *  is reversible. So the irreversible step goes LAST. */
    @Override public Map<Denomination, Integer> withdraw(Atm atm, long amount) {
        if (amount <= 0 || amount % 100 != 0)
            throw new InvalidAmountException("Amount must be a positive multiple of 100");
        if (amount > Atm.PER_TXN_MAX)
            throw new InvalidAmountException("Per-transaction limit is " + Atm.PER_TXN_MAX);

        // 1. Cheap local pre-check: don't bother the bank for cash we clearly
        //    can't pay out. Advisory only (TOCTOU) - step 3 is the real check.
        if (!atm.inventory().canDispense(amount))
            throw new CashNotDispensableException(
                    "ATM cannot compose " + amount + " from available notes");

        // 2. Debit first (reversible).
        String txnId = atm.bank().debit(atm.card().number(), amount);

        // 3. Atomic dispense (irreversible). If it fails now, compensate.
        Map<Denomination, Integer> notes = atm.inventory().tryDispense(amount);
        if (notes == null) {
            atm.bank().reverse(txnId);   // compensating transaction
            throw new CashNotDispensableException(
                    "Cash became unavailable - debit was reversed");
        }
        return notes;
    }

    /** Cash-counting deposit: the hardware holds inserted notes in escrow
     *  while we seek the credit; only a confirmed credit moves them into
     *  dispensable inventory. If credit throws, the escrow returns them. */
    @Override public void deposit(Atm atm, Map<Denomination, Integer> notes) {
        long total = notes.entrySet().stream()
                .mapToLong(e -> (long) e.getKey().value * e.getValue()).sum();
        if (total <= 0) throw new InvalidAmountException("No notes inserted");

        atm.bank().credit(atm.card().number(), total);  // may throw -> escrow returns notes
        atm.inventory().deposit(notes);                  // local, cannot fail; safe after credit
    }

    @Override public void ejectCard(Atm atm) { atm.endSession(); }
}
```

### 4.5 The ATM facade — synchronization boundary and timeout owner

```java
class Atm {
    static final long PER_TXN_MAX = 20_000;
    static final int MAX_PIN_ATTEMPTS = 3;

    private final BankService bank;
    private final CashInventory inventory;
    private final long sessionTimeoutMillis;

    // Guarded by 'this': state, currentCard, lastActivityAt.
    private AtmState state = new IdleState();
    private Card currentCard;
    private long lastActivityAt;

    private final ScheduledExecutorService timeoutMonitor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-timeout-monitor");
                t.setDaemon(true);
                return t;
            });

    Atm(BankService bank, CashInventory inventory, long sessionTimeoutMillis) {
        this.bank = bank;
        this.inventory = inventory;
        this.sessionTimeoutMillis = sessionTimeoutMillis;
        long period = Math.max(100, sessionTimeoutMillis / 4);
        timeoutMonitor.scheduleAtFixedRate(this::expireIfIdle, period, period, TimeUnit.MILLISECONDS);
    }

    /* ---- Public API. 'synchronized' serializes the user thread against the
       timeout thread - the only two threads that touch session state. ---- */

    synchronized void insertCard(Card card)  { state.insertCard(this, card); touch(); }
    synchronized void enterPin(String pin)   { touch(); state.enterPin(this, pin); }
    synchronized long checkBalance()         { touch(); return state.checkBalance(this); }
    synchronized Map<Denomination, Integer> withdraw(long amount) {
        touch(); return state.withdraw(this, amount);
    }
    synchronized void deposit(Map<Denomination, Integer> notes) {
        touch(); state.deposit(this, notes);
    }
    synchronized void ejectCard()            { state.ejectCard(this); }
    synchronized String currentStateName()   { return state.name(); }

    /* ---- Timeout: check + eject are ONE critical section, so a user action
       and an expiry can never interleave halfway. ---- */
    private synchronized void expireIfIdle() {
        if (!(state instanceof IdleState)
                && System.currentTimeMillis() - lastActivityAt >= sessionTimeoutMillis) {
            endSession();   // eject the abandoned card
        }
    }

    /* ---- Helpers for states. Only ever called while holding this Atm's
       monitor, because every entry point into a state is a synchronized
       Atm method. The states themselves hold no shared mutable state. ---- */
    void setState(AtmState s)  { this.state = s; }
    void beginSession(Card c)  { this.currentCard = c; }
    void endSession()          { this.currentCard = null; this.state = new IdleState(); }
    private void touch()       { this.lastActivityAt = System.currentTimeMillis(); }

    Card card()               { return currentCard; }
    BankService bank()        { return bank; }
    CashInventory inventory() { return inventory; }

    void shutdown() { timeoutMonitor.shutdownNow(); }
}
```

> **Spring aside.** `BankService` is the textbook injected collaborator (one shared bean), `Atm` would be a component per physical machine, and the timeout monitor is what you'd replace with `@Scheduled` — but the locking discipline stays yours either way; the framework doesn't make `expireIfIdle` atomic for you.

### 4.6 Verified demo output (abridged)

```
--- State pattern guard: withdraw before any card ---
    [REJECTED] IllegalStateException: 'withdraw' is not allowed in state IDLE

--- Greedy limitation: 600 requested; greedy grabs the 500 and dead-ends,
    even though 3 x 200 = 600 exists ---
    Inventory: {FIVE_HUNDRED=1, TWO_HUNDRED=3, HUNDRED=0}
    [REJECTED] CashNotDispensableException: ATM cannot compose 600 ...

--- Cash-counting deposit refills the SAME inventory; then 900 succeeds ---
    [ATM] Deposited 900 - notes added to inventory
    Dispensed: {FIVE_HUNDRED=1, TWO_HUNDRED=2}

--- Reversal protocol: cassette fault AFTER debit -> debit reversed ---
    [HW] Cash cassette faulted after debit!
    [BANK] Reversed txn 88d7416a... (+1000 back to 4111)
    Balance before=9300 after=9300  (debit undone)

--- 3 wrong PINs blocks the card at the bank ---
    Wrong PIN (1/3)  Wrong PIN (2/3)
    [REJECTED] CardBlockedException: 3 wrong PINs - card blocked and retained

--- Session timeout: timer thread ejects an abandoned card ---
    State: CARD_INSERTED (awaiting PIN) ... user walks away
    [ATM] Session timeout - ejecting Card[4222]
    State after timeout: IDLE
    [REJECTED] IllegalStateException: 'enterPin' is not allowed in state IDLE
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### 5.1 Exception strategy

One unchecked root (`AtmException`) with specific leaves, because every failure here ends the *operation*, not the *program*, and the caller (the UI layer) handles them uniformly: show a message, stay in (or fall back to) a safe state. Each exception maps to a distinct user-visible outcome:

| Exception | Trigger | State afterwards |
|---|---|---|
| `IllegalStateException` | operation illegal in current state | unchanged — the State pattern *is* the validation |
| `InvalidAmountException` | non-positive, not multiple of 100, over per-txn max | `AUTHENTICATED` (retry allowed) |
| `CashNotDispensableException` | pre-check fails, or dispense fails post-debit (then reversed) | `AUTHENTICATED` |
| `InsufficientFundsException` | bank refuses the debit | `AUTHENTICATED` |
| `AuthenticationFailedException` | wrong PIN, attempts remaining | `CARD_INSERTED` |
| `CardBlockedException` | 3rd wrong PIN, or blocked card inserted | `IDLE` (session ended) |

Note what *doesn't* throw: ejecting in `IDLE` is a deliberate no-op (pressing eject with no card is not an error), and a failed `tryDispense` returns `null` rather than throwing — inside the protocol, "couldn't compose" is an expected branch with a compensation action, not an exceptional one.

### 5.2 Edge cases

- **Greedy is incomplete under bounded inventory** — verified live: with `{1×500, 3×200}`, a 600 request fails (greedy takes the 500, dead-ends at remainder 100) even though 3×200 = 600 exists. For canonical denominations with *unlimited* supply greedy is optimal; bounded cassettes break that guarantee. Documented as a known limitation; the fix is a bounded coin-change DP behind a `DispensePolicy` strategy (follow-up #1).
- **Withdrawal of exactly the last notes** — fine: `tryDispense` commits to zero counts atomically; the next request fails the pre-check cleanly.
- **Deposit then immediate withdrawal of those very notes** — works by design (verified): the deposit lands in the same locked inventory the withdrawal plans against. This is exactly why deposits and withdrawals must share one lock.
- **PIN attempts across sessions** — the counter lives in the `CardInsertedState` *object*, recreated per insert, so eject-and-reinsert legitimately resets attempts at the ATM. Real fraud control belongs at the bank (it sees attempts across all ATMs); ours blocks at the bank precisely so re-insertion anywhere fails.
- **Timeout fires while the user is mid-keystroke** — impossible to half-interleave: `expireIfIdle` and every user action contend for the same monitor, so the user action either completes before the eject (and `touch()` defers the timeout) or arrives after it (and gets a clean `IllegalStateException`). Verified in the demo.
- **Deposit credit succeeds but the process dies before `inventory.deposit`** — the account is credited and the notes are physically in the machine, just uncounted; a technician reconciliation (counted cash vs. recorded counts) catches it. Acceptable: the *customer* is never short-changed. The reverse ordering (inventory first, credit second) could lose the customer's money — that's why credit goes first.
- **`int` overflow in the chain** — amounts are bounded by `PER_TXN_MAX` (20,000) before the chain runs, so the `(int) amount` narrowing is safe; the guard is the validation, not luck.

### 5.3 Concurrency analysis

**Shared mutable state — the complete list:**

1. `Atm.state`, `Atm.currentCard`, `Atm.lastActivityAt` — touched by the user-driven thread and the timeout timer thread.
2. `CashInventory.counts` — touched by withdrawals and cash-counting deposits (and technician unloads).
3. `Account.balance` at the bank — touched by *every channel*, not just this ATM.

**Chosen primitives and why:**

- **`Atm`: intrinsic lock on `this`** (every public method `synchronized`). Exactly two threads contend, operations are short, and the invariant spans three fields (`state` + `currentCard` + `lastActivityAt` must change together) — one coarse monitor is the simplest thing that is correct. A `ReentrantLock` would buy nothing here; `volatile` on `state` alone would be insufficient because the timeout check (`state` read + `lastActivityAt` read + `endSession` write) is a multi-field compound action.
- **`CashInventory`: one intrinsic lock** fusing plan-and-commit. Per-denomination locks would be a correctness bug dressed as an optimization: a dispense plan spans denominations, so locking them individually reintroduces the race between planning a 500 and committing a 200. Same lesson as Coffee Machine's `tryConsume`.
- **Bank `Account`: per-account `synchronized(account)`** — fine-grained on purpose, because *here* contention is real (thousands of channels) and the invariant is per-account. The debit's balance check and decrement are fused; two simultaneous withdrawals of ₹300 against a ₹500 balance cannot both pass.
- **`Account.blocked`: `volatile`** — a single independent flag with no compound invariant; visibility is all that's needed.
- **Timeout thread: `ScheduledExecutorService`**, single daemon thread — not raw `Timer` (single shared thread, dies on uncaught exception) and not busy-waiting.

**Why no deadlock:** draw the lock-acquisition graph. The user thread acquires `Atm` ▶ then possibly `CashInventory` *or* `Account` (via the bank). The timeout thread acquires `Atm` only. `CashInventory` never calls the bank; the bank never calls the inventory; neither ever calls back into `Atm`. The graph is a DAG — locks are always acquired in one direction (`Atm` ▶ leaf), and no thread ever holds a leaf lock while wanting `Atm`. No cycle, no deadlock. No livelock either: nothing retries in a loop; every contended path either blocks briefly or fails definitively.

**Why no lost updates:** every read-modify-write on the three shared states is fused under its owning lock (`tryDispense`, `debit`, `expireIfIdle`). The one deliberate non-atomicity — `canDispense` ▶ `debit` ▶ `tryDispense` without a global lock — is not a race *bug* but a *protocol*: we accept the TOCTOU window (to avoid holding any local lock across a network call) and pay for it with an idempotent compensating reversal. The demo proves the books balance even when the window is hit.

**The honest trade-off to volunteer:** `Atm.withdraw` holds the ATM monitor across the bank round-trip, so a slow bank briefly blocks the timeout thread. For a single-user kiosk that's acceptable (the user is *in* a transaction; timing them out mid-debit would be worse). The production hardening is a timeout on the bank call itself, plus the store-and-forward reversal queue from follow-up #2.

---

## Interviewer Follow-ups (with model answers)

1. **"Greedy failed your own demo — fix it."** Swap the planner behind a `DispensePolicy` strategy: bounded coin-change DP over amount/100 (state = composable remainders given cassette counts, O(amount × denominations) time). The critical-section shape is untouched — plan-and-commit still fuse under the inventory lock; only the planning algorithm changes. Bonus point: fewest-notes and cassette-leveling (drain the fullest cassette) are alternative policies the same seam accommodates.
2. **"The reversal itself fails — network died. Now what?"** The reversal must be *durable and idempotent*: persist `txnId` to a local store-and-forward queue before declaring failure, retry with backoff, and reconcile via the bank's end-of-day settlement. The `txnId` makes retries safe (our `reverse` is already idempotent). This is the saga pattern in miniature: every step has a compensating action, and compensations are queued, not fire-and-forget.
3. **"Two ATMs, same account, same instant."** Nothing changes on the ATM side — that's the point of the design. The ATM holds no account state; serialization happens at the bank's per-account atomic debit. One ATM's debit wins, the other gets `InsufficientFundsException`. The transferable rule: *put the lock where the data lives.*
4. **"Add PIN change and mini-statement without bloating the state machine."** Two options, and the trade-off is the answer: (a) new methods on `AtmState` — simple, but the interface grows per feature (ISP pressure); (b) promote transactions to Command objects — `AuthenticatedState.execute(Transaction)` with `WithdrawTransaction`, `PinChangeTransaction`, etc. Commands win once you also need receipts, audit logs, or retry (the command object *is* the audit record). At three operations, (a); at six with cross-cutting concerns, (b).
5. **"Dispenser jams after pushing out 3 of 5 notes."** Partial physical failure means the simple reverse-all is wrong — you'd credit money the customer partially has. Real dispensers report a count of notes actually presented; the compensation becomes a *partial* reversal for the un-presented remainder, plus flagging the machine out of service and a reconciliation entry. Design hook: `tryDispense` returning the committed plan is what makes "what should have come out" comparable against "what hardware says came out."

## Transferable Lesson

The reusable kernel: **when one logical operation spans a reversible remote step and an irreversible local step, order the irreversible step last and pair the remote step with an idempotent compensating action.** That is the saga pattern at single-machine scale, and it reappears verbatim in payment capture vs. order fulfillment, seat-debit vs. ticket-issue (Movie Booking), and inventory-reserve vs. shipment. Second take-away: locks protect *invariants*, not fields — `Atm` got one coarse lock because its three fields share one invariant; the bank got per-account locks because its invariant is per-account. Choosing lock granularity by invariant, not by reflex, is what separates "knows `synchronized`" from "can argue correctness."

## Progress & Next

✅ Logging Framework → ✅ Parking Lot → ✅ Coffee Vending Machine → ✅ Vending Machine → ✅ Traffic Signal → ✅ **ATM** → next: **Elevator System** — scheduling (which request does the car serve next?) introduces Strategy over a live priority structure plus a worker-thread loop, our deepest concurrency problem yet before the dedicated Concurrency tier.
