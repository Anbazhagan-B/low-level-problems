# Library Management System — Low Level Design (Java)

Problem 1 of the Easy track (awesome-low-level-design sequence).
Assumptions locked in: **multiple physical copies per book**, max **5 concurrent loans** per member, **14-day** loan period (both configurable), no fines/reservations in v1, in-memory storage, single JVM, thread-safe.

---

## Step 1 — Requirements

### Functional Requirements

1. **Catalog management:** librarians can add, update, and remove books (and physical copies) from the catalog.
2. **Book metadata:** every book has a title, author, ISBN, publication year; every physical copy has an availability status.
3. **Member management:** members have a member ID, name, contact info, and a borrowing history.
4. **Borrow:** a member checks out an *available copy*; the system records the loan and a due date.
5. **Return:** a member returns a copy; availability is restored and the loan is closed in history.
6. **Borrowing rules enforced at checkout:**
   - Maximum number of simultaneous loans per member (default 5).
   - Fixed loan duration (default 14 days).
7. **Search:** look up books by ISBN (primary key) and by title/author (secondary).

### Non-Functional Requirements

1. **Concurrency:** concurrent borrows/returns must be safe. Two members must never successfully borrow the *same physical copy*. A member at their loan limit must not slip past it via two simultaneous requests.
2. **Atomicity:** a borrow either fully succeeds (copy marked BORROWED **and** loan recorded on the member) or fully fails. No half-states.
3. **Extensibility:** lending rules are pluggable (Strategy), the entry point is a thin facade, and reservations/fines/notifications can be added without rewriting the core.
4. **Scale scope:** in-memory, single process. This is an OOD exercise, not a distributed-systems one — state that explicitly in the interview.

---

## Step 2 — Entities & Relationships

### The key modeling insight: Book vs. BookCopy

`Book` is the *abstract work* — "Clean Code, ISBN 978-0132350884". `BookCopy` is the *physical item* on the shelf with its own barcode. You borrow a **copy**, not a book. Conflating these is the most common mistake in this problem: it makes "3 copies of the same title" impossible to represent and forces availability to live on the wrong object.

### Entity list with relationships

| Entity | Role | Key relationships |
|---|---|---|
| `Book` | Catalog metadata (ISBN, title, author, year). Immutable value-ish object. | — |
| `BookCopy` | Physical copy: copy ID + status. | **Association** with `Book` (a copy *refers to* a book; the Book exists independently and is shared by all its copies — so not composition). |
| `Member` | Member ID, name, contact, active loans, history. | **Aggregation** of `Loan` records (member "has" loans, but loans are also meaningful to the library independently — they're shared records, not owned exclusively). |
| `Loan` | One borrowing event: copy + member + borrow date + due date + return date. The *association class* that reifies the member↔copy relationship. | **Association** with both `Member` and `BookCopy`. |
| `LendingPolicy` (interface) | Encapsulates max-loans and duration rules. | **Dependency** from `LibraryService` (used, not stored per-entity). |
| `Catalog` | Owns the book/copy registries and search. | **Composition** of its internal maps (the catalog exclusively owns its index structures — they die with it). |
| `LibraryService` | Facade: borrow/return/add/remove orchestration. | **Composition** of `Catalog` and the member registry; **dependency** on `LendingPolicy`. |
| `BookStatus` (enum) | AVAILABLE, BORROWED, LOST. | — |

**Why `Loan` is its own class and not just a `dueDate` field on `BookCopy`:** the loan is a fact with its own lifecycle (open → returned, possibly → overdue). Putting due-date/borrower fields on the copy works until the first follow-up ("add fines", "show history", "allow renewals") — then you have nowhere to hang the data. Reifying the relationship as a class is the transferable habit.

---

## Step 3 — UML Class Design

```
+----------------------+        +---------------------------+
| <<enum>> BookStatus  |        | <<interface>>             |
|----------------------|        | LendingPolicy             |
| AVAILABLE            |        |---------------------------|
| BORROWED             |        | +maxConcurrentLoans():int |
| LOST                 |        | +loanPeriod():Duration    |
+----------------------+        +------------▲--------------+
                                             |
                                 +-----------+------------+
                                 | DefaultLendingPolicy   |
                                 | (5 books / 14 days)    |
                                 +------------------------+

+---------------------+ 1     * +----------------------+
| Book                |◄--------| BookCopy             |
|---------------------|         |----------------------|
| -isbn: String       |         | -copyId: String      |
| -title: String      |         | -status: BookStatus  |
| -author: String     |         |----------------------|
| -publicationYear:int|         | +tryBorrow(): bool   |  (synchronized)
+---------------------+         | +markReturned()      |  (synchronized)
                                +----------▲-----------+
                                           | 1
                                           |
+---------------------+ 1     * +----------+-----------+
| Member              |---------| Loan                 |
|---------------------|         |----------------------|
| -memberId: String   |         | -loanId: String      |
| -name: String       |         | -copy: BookCopy      |
| -contact: String    |         | -member: Member      |
| -loans: List<Loan>  |         | -borrowedAt: LocalDate|
|---------------------|         | -dueDate: LocalDate  |
| +activeLoanCount()  |         | -returnedAt: LocalDate (nullable)
| +addLoan(Loan)      |         | +isActive(): bool    |
| +closeLoan(...)     |         | +close()             |
+---------------------+         +----------------------+

+---------------------------------------------------------+
| LibraryService  (Facade)                                 |
|---------------------------------------------------------|
| -catalog: Catalog                       (composition)    |
| -members: ConcurrentHashMap<String,Member>               |
| -policy: LendingPolicy                  (injected)       |
|---------------------------------------------------------|
| +addBook(Book, int copies)                               |
| +removeCopy(copyId)                                      |
| +registerMember(Member)                                  |
| +borrowBook(memberId, isbn): Loan                        |
| +returnBook(memberId, copyId)                            |
| +searchByTitle(q) / findByIsbn(isbn)                     |
+---------------------------------------------------------+
                 | 1 (composition)
                 ▼
+---------------------------------------------------------+
| Catalog                                                  |
|---------------------------------------------------------|
| -booksByIsbn: ConcurrentHashMap<String, Book>            |
| -copiesByIsbn: ConcurrentHashMap<String,                 |
|                   List<BookCopy>>  (CopyOnWriteArrayList)|
| -copiesById: ConcurrentHashMap<String, BookCopy>         |
|---------------------------------------------------------|
| +addBook / addCopy / removeCopy                          |
| +findAvailableCopy(isbn): Optional<BookCopy>             |
| +search(...)                                             |
+---------------------------------------------------------+
```

Multiplicities: `Book 1 — * BookCopy`, `Member 1 — * Loan`, `BookCopy 1 — * Loan` (over time; at most one *active* loan at any instant — an invariant the code must enforce, since UML can't).

### SOLID mapping

- **S — Single Responsibility:** `Catalog` indexes inventory; `Member` owns its loan bookkeeping; `LendingPolicy` owns the rules; `LibraryService` only orchestrates. No god class.
- **O — Open/Closed:** new lending rules (premium members get 10 books, students get 30 days) = new `LendingPolicy` implementation; zero changes to the borrow flow.
- **L — Liskov:** any `LendingPolicy` implementation is substitutable; the service depends only on the contract.
- **I — Interface Segregation:** `LendingPolicy` is deliberately tiny. We did *not* create a fat `ILibraryOperations` mega-interface.
- **D — Dependency Inversion:** `LibraryService` receives `LendingPolicy` via constructor injection — the high-level borrow flow depends on an abstraction, not on `DefaultLendingPolicy`. *(Spring Boot parallel: this constructor is exactly what `@Autowired` constructor injection does; `LendingPolicy` would be a `@Component`-scanned bean you could swap with a `@Profile` or `@ConditionalOnProperty`.)*

### Design patterns and exactly why they fit

- **Facade (`LibraryService`):** clients (a CLI, a REST controller, a test) need one coherent entry point for a multi-step operation — borrow touches policy + catalog + copy + member. The facade hides that choreography and is the natural place to enforce atomicity.
- **Strategy (`LendingPolicy`):** the *rule* varies independently of the *flow*. Borrow-flow steps never change; the numbers and conditions do. That "stable algorithm, variable policy" shape is the textbook Strategy trigger.
- **Factory (lightweight, `Loan.open(...)` static factory):** loan creation has invariants (due date = now + policy period, ID generation). A static factory centralizes them instead of scattering `new Loan(...)` call sites.
- **Singleton — deliberately avoided:** the repo's canonical solution makes `LibraryManager` a singleton. In modern Java that's a liability: it hides dependencies, makes tests share state, and Spring gives you singleton *scope* without the *pattern*. Say this in the interview — rejecting a pattern with a reason scores higher than applying one by reflex.
- **State — considered and rejected:** `BookStatus` has 3 values and ~2 transitions; an enum with guarded transitions is enough. The State pattern earns its weight when behavior differs per state across many operations (we'll see that in Vending Machine / Elevator).
- **Observer — extension point:** "notify member when a reserved book is returned" plugs in as a listener on the return flow. Not built in v1, but the seam is obvious.

### The two decisions an interviewer will probe

1. **Where does availability live and how is borrow made atomic?** (Answer: status on `BookCopy`, guarded by a per-copy synchronized compare-and-set; see Step 5.)
2. **Why Book/BookCopy split?** Be ready to defend it with the "3 copies of Clean Code" scenario.

---

## Step 4 — Implementation

Core classes, trimmed boilerplate, the interesting parts intact.

```java
// ---------- Enums & value objects ----------

public enum BookStatus { AVAILABLE, BORROWED, LOST }

public final class Book {                       // immutable: catalog metadata never
    private final String isbn;                  // mutates after creation -> inherently
    private final String title;                 // thread-safe, freely shareable
    private final String author;
    private final int publicationYear;

    public Book(String isbn, String title, String author, int publicationYear) {
        this.isbn = Objects.requireNonNull(isbn);
        this.title = Objects.requireNonNull(title);
        this.author = Objects.requireNonNull(author);
        this.publicationYear = publicationYear;
    }
    public String getIsbn()  { return isbn; }
    public String getTitle() { return title; }
    public String getAuthor(){ return author; }
}
```

```java
// ---------- BookCopy: owns its own thread-safety ----------

public class BookCopy {
    private final String copyId;
    private final Book book;
    private BookStatus status = BookStatus.AVAILABLE;   // guarded by 'this'

    public BookCopy(String copyId, Book book) {
        this.copyId = copyId;
        this.book = book;
    }

    /**
     * Atomic test-and-set. This is THE critical section of the whole system:
     * two threads racing on the same copy serialize here, and exactly one
     * sees AVAILABLE and flips it. Monitor is per-copy, so contention on
     * copy A never blocks copy B.
     */
    public synchronized boolean tryBorrow() {
        if (status != BookStatus.AVAILABLE) return false;
        status = BookStatus.BORROWED;
        return true;
    }

    public synchronized void markReturned() {
        if (status != BookStatus.BORROWED) {
            throw new IllegalStateException("Copy " + copyId + " is not on loan");
        }
        status = BookStatus.AVAILABLE;
    }

    public synchronized boolean isAvailable() { return status == BookStatus.AVAILABLE; }
    public String getCopyId() { return copyId; }
    public Book getBook()     { return book; }
}
```

```java
// ---------- Loan: reified borrow event, with static factory ----------

public class Loan {
    private final String loanId;
    private final BookCopy copy;
    private final Member member;
    private final LocalDate borrowedAt;
    private final LocalDate dueDate;
    private volatile LocalDate returnedAt;   // null while active

    private Loan(String loanId, BookCopy copy, Member member,
                 LocalDate borrowedAt, LocalDate dueDate) {
        this.loanId = loanId; this.copy = copy; this.member = member;
        this.borrowedAt = borrowedAt; this.dueDate = dueDate;
    }

    /** Factory centralizes the due-date invariant instead of trusting call sites. */
    public static Loan open(BookCopy copy, Member member, LendingPolicy policy) {
        LocalDate now = LocalDate.now();
        return new Loan(UUID.randomUUID().toString(), copy, member,
                        now, now.plus(policy.loanPeriod()));
    }

    public boolean isActive()  { return returnedAt == null; }
    public boolean isOverdue() { return isActive() && LocalDate.now().isAfter(dueDate); }
    void close()               { this.returnedAt = LocalDate.now(); }
    public BookCopy getCopy()  { return copy; }
    public LocalDate getDueDate() { return dueDate; }
}
```

```java
// ---------- Member: synchronizes its own loan bookkeeping ----------

public class Member {
    private final String memberId;
    private final String name;
    private final String contact;
    private final List<Loan> loans = new ArrayList<>();   // guarded by 'this'

    public Member(String memberId, String name, String contact) {
        this.memberId = memberId; this.name = name; this.contact = contact;
    }

    /**
     * Check-limit-and-record must be one atomic step. If we exposed
     * activeLoanCount() and let the service check-then-add, two threads
     * could both pass the check (check-then-act race) and push the member
     * over the limit. So the invariant lives WITH the data it protects.
     */
    public synchronized boolean tryAddLoan(Loan loan, int maxLoans) {
        long active = loans.stream().filter(Loan::isActive).count();
        if (active >= maxLoans) return false;
        loans.add(loan);
        return true;
    }

    public synchronized Optional<Loan> findActiveLoanForCopy(String copyId) {
        return loans.stream()
                .filter(Loan::isActive)
                .filter(l -> l.getCopy().getCopyId().equals(copyId))
                .findFirst();
    }

    public synchronized List<Loan> borrowingHistory() {
        return List.copyOf(loans);                 // defensive copy: never leak
    }                                              // the guarded mutable list
    public String getMemberId() { return memberId; }
}
```

```java
// ---------- Strategy ----------

public interface LendingPolicy {
    int maxConcurrentLoans();
    Period loanPeriod();
}

public class DefaultLendingPolicy implements LendingPolicy {
    @Override public int maxConcurrentLoans() { return 5; }
    @Override public Period loanPeriod()      { return Period.ofDays(14); }
}
```

```java
// ---------- Catalog: concurrent indexes, no global lock ----------

public class Catalog {
    private final ConcurrentHashMap<String, Book> booksByIsbn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BookCopy> copiesById = new ConcurrentHashMap<>();
    // CopyOnWriteArrayList: copy lists are read-heavy (every borrow scans one),
    // written rarely (only when a librarian adds/removes copies).
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<BookCopy>> copiesByIsbn =
            new ConcurrentHashMap<>();

    public void addBook(Book book, int numberOfCopies) {
        booksByIsbn.putIfAbsent(book.getIsbn(), book);
        for (int i = 0; i < numberOfCopies; i++) addCopy(book.getIsbn());
    }

    public BookCopy addCopy(String isbn) {
        Book book = booksByIsbn.get(isbn);
        if (book == null) throw new BookNotFoundException(isbn);
        BookCopy copy = new BookCopy(isbn + "-" + UUID.randomUUID(), book);
        copiesById.put(copy.getCopyId(), copy);
        copiesByIsbn.computeIfAbsent(isbn, k -> new CopyOnWriteArrayList<>()).add(copy);
        return copy;
    }

    /** Snapshot scan — a returned copy may already be borrowed again by the
     *  time the caller acts, which is why the caller must use tryBorrow(). */
    public List<BookCopy> copiesOf(String isbn) {
        return copiesByIsbn.getOrDefault(isbn, new CopyOnWriteArrayList<>());
    }

    public BookCopy getCopy(String copyId) {
        BookCopy c = copiesById.get(copyId);
        if (c == null) throw new BookNotFoundException(copyId);
        return c;
    }

    public void removeCopy(String copyId) {
        BookCopy copy = getCopy(copyId);
        synchronized (copy) {                          // can't remove a borrowed copy
            if (!copy.isAvailable())
                throw new IllegalStateException("Copy on loan; cannot remove: " + copyId);
            copiesById.remove(copyId);
            copiesByIsbn.get(copy.getBook().getIsbn()).remove(copy);
        }
    }

    public List<Book> searchByTitle(String q) {
        String needle = q.toLowerCase();
        return booksByIsbn.values().stream()
                .filter(b -> b.getTitle().toLowerCase().contains(needle))
                .collect(Collectors.toList());
    }
}
```

```java
// ---------- Facade: orchestration + atomicity ----------

public class LibraryService {
    private final Catalog catalog = new Catalog();
    private final ConcurrentHashMap<String, Member> members = new ConcurrentHashMap<>();
    private final LendingPolicy policy;

    public LibraryService(LendingPolicy policy) {   // DI: depend on the abstraction
        this.policy = policy;
    }

    public void addBook(Book book, int copies)  { catalog.addBook(book, copies); }
    public void registerMember(Member m)        { members.putIfAbsent(m.getMemberId(), m); }

    /**
     * Borrow flow. Note the ORDER and the COMPENSATION:
     *  1. claim the copy   (copy-level atomic CAS)
     *  2. record the loan  (member-level atomic check-and-add)
     *  3. if 2 fails, UNDO 1 -> no half-state
     * We never hold the copy's monitor while taking the member's monitor,
     * so there is no lock-ordering cycle -> no deadlock possible.
     */
    public Loan borrowBook(String memberId, String isbn) {
        Member member = requireMember(memberId);

        for (BookCopy copy : catalog.copiesOf(isbn)) {
            if (!copy.tryBorrow()) continue;          // someone beat us to this copy

            Loan loan = Loan.open(copy, member, policy);
            if (member.tryAddLoan(loan, policy.maxConcurrentLoans())) {
                return loan;                          // success: both sides committed
            }
            copy.markReturned();                      // compensate: release the claim
            throw new BorrowLimitExceededException(memberId, policy.maxConcurrentLoans());
        }
        throw new BookUnavailableException(isbn);     // catalog had no claimable copy
    }

    public void returnBook(String memberId, String copyId) {
        Member member = requireMember(memberId);
        Loan loan = member.findActiveLoanForCopy(copyId)
                .orElseThrow(() -> new InvalidReturnException(memberId, copyId));
        loan.close();                                 // close record first,
        loan.getCopy().markReturned();                // then free the copy
        if (loan.isOverdue()) {
            // v1: just surface it; fines = future FinePolicy strategy hooks in here
        }
    }

    private Member requireMember(String memberId) {
        Member m = members.get(memberId);
        if (m == null) throw new MemberNotFoundException(memberId);
        return m;
    }
}
```

```java
// ---------- Exceptions: a small, specific hierarchy ----------

public class LibraryException extends RuntimeException {
    public LibraryException(String msg) { super(msg); }
}
public class BookNotFoundException extends LibraryException {
    public BookNotFoundException(String id) { super("No book/copy: " + id); }
}
public class BookUnavailableException extends LibraryException {
    public BookUnavailableException(String isbn) { super("No available copy of " + isbn); }
}
public class BorrowLimitExceededException extends LibraryException {
    public BorrowLimitExceededException(String memberId, int max) {
        super("Member " + memberId + " already has " + max + " active loans");
    }
}
public class MemberNotFoundException extends LibraryException {
    public MemberNotFoundException(String id) { super("No member: " + id); }
}
public class InvalidReturnException extends LibraryException {
    public InvalidReturnException(String memberId, String copyId) {
        super("Member " + memberId + " has no active loan for copy " + copyId);
    }
}
```

```java
// ---------- Demo ----------

public class LibraryDemo {
    public static void main(String[] args) {
        LibraryService library = new LibraryService(new DefaultLendingPolicy());

        library.addBook(new Book("978-0132350884", "Clean Code",
                                 "Robert C. Martin", 2008), 2);
        library.registerMember(new Member("M1", "Asha", "asha@mail.com"));
        library.registerMember(new Member("M2", "Ravi", "ravi@mail.com"));

        Loan l1 = library.borrowBook("M1", "978-0132350884");
        Loan l2 = library.borrowBook("M2", "978-0132350884");
        System.out.println("Both copies out; due " + l1.getDueDate());

        try {
            library.borrowBook("M1", "978-0132350884");   // 0 copies left
        } catch (BookUnavailableException e) {
            System.out.println("Expected: " + e.getMessage());
        }

        library.returnBook("M2", l2.getCopy().getCopyId());
        System.out.println("Copy back; borrowable again: "
                + (library.borrowBook("M1", "978-0132350884") != null));
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input & boundary conditions

| Case | Behavior |
|---|---|
| Borrow unknown ISBN / unknown member | `BookNotFoundException` / `MemberNotFoundException` — fail fast with a specific type, not a generic `RuntimeException`, so callers can react differently per failure. |
| All copies of a title on loan | `BookUnavailableException` — distinct from "no such book": the client can offer a reservation for one but not the other. |
| Member at loan limit | `BorrowLimitExceededException`, **and** the just-claimed copy is released (compensation) so it isn't leaked into limbo. |
| Return a copy you never borrowed / double-return | `InvalidReturnException` — the loan lookup is the guard; the copy's own `markReturned()` is a second line of defense (`IllegalStateException`). |
| Remove a copy that's currently borrowed | Rejected. Alternative design: soft-delete (mark `RETIRED`) so the copy disappears on return — mention this as the production-grade choice. |
| Add a book with an ISBN that already exists | `putIfAbsent` — idempotent; new copies attach to the existing `Book`. |
| Same member borrows two copies of the same title | Allowed in v1 (legal in real libraries); if disallowed, it's one extra predicate inside `tryAddLoan` — note that the check composes cleanly because the invariant lives in one place. |

### Concurrency analysis (the part interviewers grade hardest)

**Shared mutable state — enumerate it explicitly:**
1. `BookCopy.status` — guarded by the copy's own monitor.
2. `Member.loans` — guarded by the member's own monitor.
3. The catalog/member registries — `ConcurrentHashMap` + `CopyOnWriteArrayList`, thread-safe by construction.
4. `Loan.returnedAt` — `volatile` (single writer at close-time; readers just need visibility).

**Critical sections and primitives chosen:**
- **Copy claim** = `tryBorrow()`: a synchronized test-and-set. This is intentionally a *try* (returns `false`) rather than block-and-wait — borrow contention should fail over to the next copy, not queue. Per-copy monitors mean fine-grained locking: heavy traffic on one bestseller never blocks the rest of the catalog. A global `synchronized borrowBook(...)` on the service would also be *correct* — but it serializes the entire library through one lock. Correct-but-coarse vs. correct-and-fine-grained is exactly the trade-off to narrate.
- **Limit enforcement** = `tryAddLoan()`: the classic **check-then-act** hazard. Counting active loans and appending must happen under one monitor, or two parallel borrows both observe `count = 4 < 5` and the member ends at 6. The fix is structural, not just a keyword: the invariant ("never exceed max") lives in the same class as the data it constrains.

**Why no deadlock:** deadlock needs a cycle in lock acquisition. Our borrow flow acquires the copy monitor, *releases it* (method returns), then acquires the member monitor. No thread ever holds both simultaneously, so no cycle can form — that's a stronger argument than "we order our locks," because there's nothing to order.

**Why no race on the limit / no double-borrow:** both invariants are enforced by single atomic operations on the owning object's monitor (`tryBorrow`, `tryAddLoan`). The window between them is safe because the copy is already exclusively claimed — the worst case is the compensation path, which simply releases it.

**Why no livelock/starvation in practice:** `synchronized` blocks are tiny (a field check and a write); the JVM's monitor implementation doesn't guarantee fairness, but with critical sections this short, starvation is not a realistic failure mode. If it were, `ReentrantLock(true)` (fair mode) is the drop-in upgrade — at a throughput cost.

**Known soft spot to volunteer before they find it:** between `loan.close()` and `copy.markReturned()` in `returnBook`, another thread could observe an inactive loan whose copy still reads BORROWED for a few nanoseconds. It's benign (the copy is merely unavailable a moment longer; no invariant breaks). If true atomicity across both objects were required, you'd introduce a small per-loan lock or do both mutations under the copy's monitor — and you'd say *why* you didn't by default: wider locks couple the two objects' lifecycles for no observable benefit.

**Spring Boot aside:** in a real service, `LibraryService` would be a singleton-scoped `@Service`, the maps would become repositories, and the atomicity story would move to the database (`@Transactional` + optimistic locking with `@Version` on the copy row — which is the DB-flavored cousin of our `tryBorrow` CAS). The in-memory reasoning above is what proves you understand what the framework is doing for you.

---

## After the Problem — Interview Follow-Ups & Takeaways

**Likely follow-up questions, with model answers in one breath each:**

1. **"Add reservations/holds for unavailable books."** — New `Reservation` entity + per-ISBN `ConcurrentLinkedQueue<Reservation>`; on `returnBook`, poll the queue and notify the head (Observer: a `ReturnListener` hook on the facade). Borrow flow gains one check: a reserved copy is claimable only by the reservation holder for a grace window.
2. **"Add fines for overdue returns."** — `FinePolicy` strategy (`Money calculate(Loan)`), invoked in `returnBook` where the `isOverdue()` hook already sits; member gains a `Ledger`. Zero changes to borrow flow — that's Open/Closed paying out.
3. **"Different member tiers with different limits."** — Either `LendingPolicy` becomes per-member (`policyFor(Member)` via a factory), or `Member` carries a `MembershipTier` enum the policy reads. Strategy + a parameter, not subclassing `Member`.
4. **"What changes at 10x/100x scale or multiple library branches?"** — In-memory monitors stop working across processes: state moves to a DB, `tryBorrow` becomes an optimistic-lock UPDATE (`... SET status='BORROWED' WHERE id=? AND status='AVAILABLE'`, check rows-affected == 1), and `Branch` becomes an entity with copies associated per branch.
5. **"Prove your design is thread-safe."** — Enumerate shared mutable state (4 items above), show each has exactly one guard, show no thread holds two monitors at once (no deadlock), and point at the compensation path as the answer to partial failure.

**Transferable lessons:**
- **Reify relationships:** `Loan` (member↔copy) is the same move as `Booking` in Hotel/Movie systems and `Transaction` in Parking Lot. When a relationship has its own data or lifecycle, it's a class.
- **Item vs. item-type split:** `Book`/`BookCopy` reappears as `Movie`/`Show`/`Seat`, `VehicleType`/`ParkingSpot`, `Product`/`InventoryItem`.
- **Invariants live with their data:** `tryAddLoan` owning the limit check is the antidote to check-then-act races everywhere.
- **Try-claim + compensate** is the in-memory miniature of the saga pattern you already use across microservices.

**Next problem in sequence:** **Parking Lot** — it reuses the item-type/instance split and the per-resource claim, and adds your first real Strategy showcase (pricing/spot-assignment) plus a richer enum model. After that: Vending Machine, where the State pattern finally earns its keep.
