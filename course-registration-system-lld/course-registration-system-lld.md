# University Course Registration System — Low-Level Design

> **Problem tier:** Medium (concurrency-centric) · **Source:** ashishps1/awesome-low-level-design
> **Scope locked in:** register + drop, search by code/name, capacity enforcement, FCFS, single-node in-memory. Waitlist & prerequisites deferred but designed-for as extensions.
>
> **Transfer note:** This is Concert Ticket Booking's atomic check-and-set with the payment leg removed — no two-phase reserve/confirm is needed because there is no external I/O inside the critical section. That single difference drives the whole concurrency design.

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| FR1 | Maintain a catalog of courses; each course has a unique **course code**, name, instructor, and **max enrollment capacity**. |
| FR2 | Students can **search** courses by exact course code or by (case-insensitive, substring) name. |
| FR3 | Students can **register** for a course if seats remain and they are not already registered. |
| FR4 | Students can **drop** a course they are registered in; the seat is reclaimed immediately. |
| FR5 | Students can **view** the list of courses they are currently registered in. |
| FR6 | The system must **never oversubscribe** a course — enrollment ≤ capacity is an invariant, not a best effort. |

### Non-Functional Requirements

| # | Requirement | Design consequence |
|---|-------------|--------------------|
| NFR1 | **Concurrency** — many students race for the last seats of the *same* course (registration-day stampede). | Atomic check-and-set per course; the capacity check and the enrollment must happen inside one critical section. |
| NFR2 | **Consistency** — the course roster and the student's registered-course list must never disagree. | Both sides of the relationship are mutated inside the same critical section, in a fixed order. |
| NFR3 | **Throughput** — registrations on *different* courses must not serialize against each other. | Per-course `ReentrantLock`, never a global lock (same argument as Parking Lot / Concert Ticket). |
| NFR4 | **Extensibility** — waitlist, prerequisites, schedule-conflict checks should bolt on without rewriting the core. | Service layer + repository interfaces; registration validation isolated so it can grow into a Strategy/Chain. |

### Assumptions (stated, as in an interview)

- Single JVM, in-memory storage (standard LLD convention) → `java.util.concurrent` primitives are the right tool; in a distributed deployment the same invariant would move to a DB constraint or optimistic version column.
- FCFS — no priority registration windows.
- Re-registering for a course you already hold is an **error**, not an idempotent success.
- No per-student course limit in v1 (called out as a one-line extension in Step 5).

---

## Step 2 — Entities & Relationships

### Core entities

| Entity | Responsibility | Why it exists |
|--------|---------------|---------------|
| `Course` | Catalog data (code, name, instructor, capacity) **plus** its own roster and its own lock. | The course is the *contended resource*; co-locating roster + lock with the course makes the critical section obvious and self-contained. |
| `Student` | Identity + the set of courses they hold. | Needed for FR5 (view my courses) without scanning every course roster. |
| `CourseCatalog` (repository) | Thread-safe registry of courses; search by code/name. | Separates *finding* a course from *mutating* one (SRP). |
| `StudentRepository` | Registry of students. | Symmetric to the catalog; keeps the service free of storage detail. |
| `RegistrationService` | Orchestrates register/drop; owns the transaction-like sequence. | The only place business rules live — controllers/demo code never touch locks. |

### Deliberate under-modeling (interview gold)

- **No `Registration` entity.** A registration is just the pair (student, course) — representable as set membership on both sides. You'd promote it to a class only when it carries its own data (timestamp, grade, status, waitlist position). Same judgment call as "no `Friendship` entity" in the social-network problems: model relationships as relationships until they earn fields of their own.
- **No `Enrollment` counter separate from the roster.** `roster.size()` *is* the enrollment count — a separate `int enrolled` field is a second source of truth that can drift. One source of truth is a consistency argument you get for free.
- **No `SearchService`.** Two query methods on the catalog don't justify a class. If search grew (filters, ranking), *then* extract a Strategy.

### Relationships

| Relationship | Type | Why |
|---|---|---|
| `CourseCatalog` → `Course` | **Aggregation** | Catalog holds courses but a course's lifetime isn't conceptually owned by the map (it could be shared/moved). |
| `Course` → `Student` (roster) | **Association** (many-to-many with `Student` → `Course`) | Neither owns the other; membership is the relationship itself. |
| `Course` → `ReentrantLock` | **Composition** | The lock has no meaning outside its course; created with it, dies with it. |
| `RegistrationService` → repositories | **Dependency injection (association)** | Service depends on abstractions, enabling test doubles — the plain-Java analogue of Spring constructor injection on `@Service`. |

---

## Step 3 — UML Class Design

```
+----------------------------+        +----------------------------+
| <<class>> Course           |        | <<class>> Student          |
+----------------------------+        +----------------------------+
| - code: String  {id}       |        | - id: String {id}          |
| - name: String             |        | - name: String             |
| - instructor: String       |        | - registeredCourses:       |
| - capacity: int            |        |     Set<Course>  (CHM-set) |
| - roster: Set<Student>     |        +----------------------------+
| - lock: ReentrantLock  ◆   |        | + getRegisteredCourses()   |
+----------------------------+        +----------------------------+
| + tryEnroll(Student): void |                 ▲  *
| + drop(Student): void      |                 |  roster (many-to-many)
| + getEnrolledCount(): int  |                 |  *
| + hasSeat(): boolean       |◄----------------+
+----------------------------+

+-------------------------------+       +-------------------------------+
| <<interface>> CourseCatalog   |       | <<interface>> StudentRepository|
+-------------------------------+       +-------------------------------+
| + add(Course)                 |       | + add(Student)                 |
| + findByCode(String): Optional|       | + findById(String): Optional   |
| + searchByName(String): List  |       +-------------------------------+
+-------------------------------+                    ▲
            ▲                                        |
            | implements                             | implements
+-------------------------------+       +-------------------------------+
| InMemoryCourseCatalog         |       | InMemoryStudentRepository      |
| - courses: ConcurrentHashMap  |       | - students: ConcurrentHashMap  |
+-------------------------------+       +-------------------------------+

+---------------------------------------------------------------+
| <<class>> RegistrationService                                  |
+---------------------------------------------------------------+
| - catalog: CourseCatalog            (injected)                 |
| - students: StudentRepository       (injected)                 |
+---------------------------------------------------------------+
| + register(studentId, courseCode): void                        |
| + drop(studentId, courseCode): void                            |
| + viewRegisteredCourses(studentId): List<Course>               |
+---------------------------------------------------------------+
```

Multiplicity: `Course "*" — "*" Student` (a student holds many courses, a course enrolls many students), maintained **bidirectionally** under the course's lock.

### Pattern & principle mapping

| Concept | Where | Why it fits (not just the name) |
|---|---|---|
| **Repository pattern** | `CourseCatalog`, `StudentRepository` interfaces | The service codes against an abstraction; swapping in a JPA-backed implementation later changes zero business logic. This is DIP in action. |
| **Dependency Inversion (D)** | `RegistrationService(CourseCatalog, StudentRepository)` constructor | High-level policy (registration rules) depends on interfaces, not `ConcurrentHashMap`. Spring note: this is exactly what constructor injection on a `@Service` gives you; here we wire it by hand in the demo `main`. |
| **Single Responsibility (S)** | `Course` guards its own seat invariant; `RegistrationService` sequences the workflow; catalogs only store/find. | Each class has one reason to change: capacity rules vs. orchestration vs. storage. |
| **Open/Closed (O)** | Validation seam in `register()` | Adding a prerequisite check or per-student course cap is a new validator, not a rewritten method (Step 5 shows the seam). |
| **Encapsulation of the critical section** | `Course.tryEnroll()` / `Course.drop()` | The lock is *private* and acquired inside the methods that need it. Callers cannot forget to lock — the invariant is impossible to bypass. This is the single most probe-worthy decision in the design. |
| **Monitor-object idiom (per-resource locking)** | `ReentrantLock` per `Course` | Contention on CS101 doesn't block MATH200. A global `synchronized` registry would serialize the whole university — the same scaling argument you made in Parking Lot and Concert Ticket. |
| **(Deliberately absent) Singleton** | — | The service is plain and injected. If asked, the initialization-on-demand holder idiom is the answer, but injection is the more modern default and easier to test. |

### The two decisions an interviewer will probe

1. **Why does `Course` own the lock and the enroll logic, instead of the service locking around it?**
   Because an invariant should be enforced where the data lives. If the service did `if (course.hasSeat()) course.addStudent(s)`, every future caller must remember the lock — one forgetful caller breaks the invariant. With `tryEnroll()`, the check and the mutation are inseparable. (This is the same "no check-then-act across a lock boundary" lesson as LRU Cache.)

2. **Why update the student's course set *inside* the course's critical section?**
   So the bidirectional relationship can never be observed half-written by a `drop` racing a `register` on the same (student, course) pair. The student's set is itself a concurrent set, so cross-course parallelism is unaffected — we're buying ordering on one pair, not serializing the student.

---

## Step 4 — Implementation (with demo)

### Exceptions

```java
// Base type lets callers catch "any registration failure" in one clause (LSP-friendly hierarchy).
public class RegistrationException extends RuntimeException {
    public RegistrationException(String message) { super(message); }
}

public class CourseNotFoundException extends RegistrationException {
    public CourseNotFoundException(String code) { super("No course with code: " + code); }
}

public class StudentNotFoundException extends RegistrationException {
    public StudentNotFoundException(String id) { super("No student with id: " + id); }
}

public class CourseFullException extends RegistrationException {
    public CourseFullException(String code) { super("Course is full: " + code); }
}

public class DuplicateRegistrationException extends RegistrationException {
    public DuplicateRegistrationException(String sid, String code) {
        super("Student " + sid + " already registered for " + code);
    }
}

public class NotRegisteredException extends RegistrationException {
    public NotRegisteredException(String sid, String code) {
        super("Student " + sid + " is not registered for " + code);
    }
}
```

### `Student`

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Student {
    private final String id;
    private final String name;

    // Concurrent set: a student may be (un)registered for DIFFERENT courses in parallel,
    // each under a different course's lock. The set itself must therefore be thread-safe.
    private final Set<Course> registeredCourses = ConcurrentHashMap.newKeySet();

    public Student(String id, String name) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
    }

    public String getId() { return id; }
    public String getName() { return name; }

    // Package-private: only Course (inside its lock) maintains the back-reference.
    void addCourse(Course c)    { registeredCourses.add(c); }
    void removeCourse(Course c) { registeredCourses.remove(c); }

    public List<Course> getRegisteredCourses() {
        return List.copyOf(registeredCourses);   // defensive snapshot — callers can't mutate our state
    }
}
```

### `Course` — the heart of the design

```java
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class Course {
    private final String code;
    private final String name;
    private final String instructor;
    private final int capacity;

    // Roster is the SINGLE source of truth for enrollment. No separate counter to drift.
    private final Set<Student> roster = new HashSet<>();

    // Composition: one lock per course = per-resource locking.
    // Contention on this course never blocks any other course.
    private final ReentrantLock lock = new ReentrantLock();

    public Course(String code, String name, String instructor, int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.code = Objects.requireNonNull(code);
        this.name = Objects.requireNonNull(name);
        this.instructor = Objects.requireNonNull(instructor);
        this.capacity = capacity;
    }

    /**
     * Atomic check-and-set: duplicate check + capacity check + both-side mutation
     * happen under ONE lock acquisition. This is the entire race-condition defense.
     */
    public void tryEnroll(Student student) {
        lock.lock();
        try {
            if (roster.contains(student))
                throw new DuplicateRegistrationException(student.getId(), code);
            if (roster.size() >= capacity)
                throw new CourseFullException(code);

            roster.add(student);
            student.addCourse(this);   // back-reference set inside the same critical section
        } finally {
            lock.unlock();             // finally-block unlock: exception paths can't leak the lock
        }
    }

    public void drop(Student student) {
        lock.lock();
        try {
            if (!roster.remove(student))
                throw new NotRegisteredException(student.getId(), code);
            student.removeCourse(this);   // seat reclaimed + back-reference cleared atomically
        } finally {
            lock.unlock();
        }
    }

    // Reads of mutable state also take the lock: HashSet is not safe for concurrent
    // read-during-write. (Cheap here; see Step 5 for the read-mostly alternative.)
    public int getEnrolledCount() {
        lock.lock();
        try { return roster.size(); } finally { lock.unlock(); }
    }

    public boolean hasSeat() {
        lock.lock();
        try { return roster.size() < capacity; } finally { lock.unlock(); }
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getInstructor() { return instructor; }
    public int getCapacity() { return capacity; }

    // Identity = course code (the natural key). Required for correct Set membership.
    @Override public boolean equals(Object o) {
        return this == o || (o instanceof Course && code.equals(((Course) o).code));
    }
    @Override public int hashCode() { return code.hashCode(); }
}
```

> **Note on `hasSeat()`:** it exists for *display* ("show open courses"), never for control flow. `if (course.hasSeat()) course.tryEnroll(s)` would be a check-then-act race — the seat can vanish between the two calls. `tryEnroll` re-checks under the lock, which is why it's safe to call directly and handle the exception.

### Repositories

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public interface CourseCatalog {
    void add(Course course);
    Optional<Course> findByCode(String code);
    List<Course> searchByName(String query);
}

public class InMemoryCourseCatalog implements CourseCatalog {
    // CHM gives lock-free reads and per-bin write locking — registry workhorse.
    private final Map<String, Course> courses = new ConcurrentHashMap<>();

    @Override public void add(Course course) {
        // putIfAbsent = atomic existence check + insert (no check-then-act on the map either)
        if (courses.putIfAbsent(course.getCode(), course) != null)
            throw new IllegalArgumentException("Course code already exists: " + course.getCode());
    }

    @Override public Optional<Course> findByCode(String code) {
        return Optional.ofNullable(courses.get(code));
    }

    @Override public List<Course> searchByName(String query) {
        String q = query.toLowerCase();
        return courses.values().stream()                       // CHM iteration is weakly consistent: safe, no locking
                .filter(c -> c.getName().toLowerCase().contains(q))
                .sorted(Comparator.comparing(Course::getCode))
                .collect(Collectors.toList());
    }
}

public interface StudentRepository {
    void add(Student student);
    Optional<Student> findById(String id);
}

public class InMemoryStudentRepository implements StudentRepository {
    private final Map<String, Student> students = new ConcurrentHashMap<>();

    @Override public void add(Student student) {
        if (students.putIfAbsent(student.getId(), student) != null)
            throw new IllegalArgumentException("Student id already exists: " + student.getId());
    }

    @Override public Optional<Student> findById(String id) {
        return Optional.ofNullable(students.get(id));
    }
}
```

### `RegistrationService`

```java
import java.util.List;

public class RegistrationService {
    private final CourseCatalog catalog;
    private final StudentRepository students;

    // Constructor injection — Spring's @Service + constructor wiring, done by hand.
    public RegistrationService(CourseCatalog catalog, StudentRepository students) {
        this.catalog = catalog;
        this.students = students;
    }

    public void register(String studentId, String courseCode) {
        Student student = students.findById(studentId)
                .orElseThrow(() -> new StudentNotFoundException(studentId));
        Course course = catalog.findByCode(courseCode)
                .orElseThrow(() -> new CourseNotFoundException(courseCode));

        // <-- EXTENSION SEAM: pre-enrollment validators (prereqs, course cap, schedule clash)
        //     would run here, each a RegistrationValidator strategy. See Step 5.

        course.tryEnroll(student);   // all locking is encapsulated in Course
    }

    public void drop(String studentId, String courseCode) {
        Student student = students.findById(studentId)
                .orElseThrow(() -> new StudentNotFoundException(studentId));
        Course course = catalog.findByCode(courseCode)
                .orElseThrow(() -> new CourseNotFoundException(courseCode));
        course.drop(student);
    }

    public List<Course> viewRegisteredCourses(String studentId) {
        return students.findById(studentId)
                .orElseThrow(() -> new StudentNotFoundException(studentId))
                .getRegisteredCourses();
    }

    public List<Course> searchCourses(String nameQuery) {
        return catalog.searchByName(nameQuery);
    }
}
```

### Demo — including a stampede test that *proves* the invariant

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CourseRegistrationDemo {
    public static void main(String[] args) throws InterruptedException {
        CourseCatalog catalog = new InMemoryCourseCatalog();
        StudentRepository studentRepo = new InMemoryStudentRepository();
        RegistrationService service = new RegistrationService(catalog, studentRepo);

        // --- Seed data ---
        catalog.add(new Course("CS101", "Intro to Computer Science", "Dr. Rao", 3));
        catalog.add(new Course("MATH200", "Linear Algebra", "Dr. Iyer", 50));
        for (int i = 1; i <= 100; i++)
            studentRepo.add(new Student("S" + i, "Student " + i));

        // --- Functional walkthrough ---
        System.out.println("Search 'algebra': " + service.searchCourses("algebra")
                .stream().map(Course::getCode).toList());                  // [MATH200]

        service.register("S1", "CS101");
        service.register("S1", "MATH200");
        System.out.println("S1's courses: " + service.viewRegisteredCourses("S1")
                .stream().map(Course::getCode).sorted().toList());          // [CS101, MATH200]

        try { service.register("S1", "CS101"); }
        catch (DuplicateRegistrationException e) { System.out.println("Rejected: " + e.getMessage()); }

        service.drop("S1", "CS101");
        System.out.println("After drop, S1's courses: " + service.viewRegisteredCourses("S1")
                .stream().map(Course::getCode).toList());                   // [MATH200]

        // --- Concurrency stampede: 100 threads race for 3 seats in CS101 ---
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGun = new CountDownLatch(1);   // maximize the race: all threads released at once
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();

        for (int i = 1; i <= threads; i++) {
            final String sid = "S" + i;
            pool.submit(() -> {
                try {
                    startGun.await();
                    service.register(sid, "CS101");
                    wins.incrementAndGet();
                } catch (CourseFullException | DuplicateRegistrationException e) {
                    full.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGun.countDown();
        done.await();
        pool.shutdown();

        Course cs101 = catalog.findByCode("CS101").orElseThrow();
        System.out.printf("Stampede result: %d succeeded, %d rejected, roster=%d, capacity=%d%n",
                wins.get(), full.get(), cs101.getEnrolledCount(), cs101.getCapacity());
        // ALWAYS prints: 3 succeeded, 97 rejected, roster=3 — the invariant held under contention.
        assert cs101.getEnrolledCount() == cs101.getCapacity();
    }
}
```

> Interview tip: writing this stampede harness unprompted is a strong signal. You're not *claiming* thread safety — you're demonstrating the experiment that would falsify it.

---

## Step 5 — Exception Handling, Edge Cases & Concurrency

### Invalid input & boundary conditions

| Case | Handling |
|---|---|
| Unknown student / course id | `StudentNotFoundException` / `CourseNotFoundException` from the service — fail fast before any lock is taken. |
| Capacity ≤ 0 at construction | `IllegalArgumentException` in the `Course` constructor — invalid objects can never exist. |
| Duplicate course code / student id | `putIfAbsent` in the repositories rejects atomically. |
| Register twice for the same course | `DuplicateRegistrationException`, checked under the lock (the check is part of the critical section, so two simultaneous duplicates can't both pass). |
| Drop a course never registered | `NotRegisteredException` — `roster.remove()` returning `false` is the atomic detection. |
| Capacity exactly reached | `roster.size() >= capacity` uses `>=`, so the boundary seat is the last one granted; the next request fails, never a silent off-by-one over. |
| Empty search query | Matches everything (substring of all) — acceptable; a real system would page results. |

### Concurrency analysis (the interview core)

**Shared mutable state:**
1. `Course.roster` (plain `HashSet`) — guarded exclusively by the course's `ReentrantLock`.
2. `Student.registeredCourses` — a `ConcurrentHashMap.newKeySet()`, because *different course locks* may mutate the same student's set in parallel (S1 registering for CS101 and MATH200 simultaneously from two devices).
3. The two repository maps — `ConcurrentHashMap`, mutated only via atomic `putIfAbsent`.

**Critical sections:** `tryEnroll` and `drop` — each is {validate, mutate roster, mutate back-reference} under one lock acquisition. There is deliberately **no** public path that checks capacity in one call and enrolls in another.

**Primitive choice — `ReentrantLock` per course, and why not the alternatives:**
- *Global lock / synchronized service:* correct but serializes the entire university; CS101's stampede would block MATH200. Per-resource locking is strictly better here (NFR3).
- *`AtomicInteger` seat counter (CAS):* can enforce the count, but the roster and the student back-reference must change *with* the count — CAS can't make a multi-structure update atomic. You'd reintroduce a lock anyway. (Counter-as-truth also recreates the two-sources-of-truth drift problem.)
- *`Semaphore(capacity)`:* tempting — `tryAcquire()` is the seat claim. But it suffers the same multi-structure problem, and drop/duplicate-detection logic ends up bolted around it. Mention it as a viable alternative; defend the lock as the cleaner unit of reasoning.
- *`ReadWriteLock`:* reads here (`getEnrolledCount`) are rare and tiny; the upgrade complexity isn't paid for. And remember the LRU lesson — only use it when reads truly don't mutate. Here they don't, so it *would* be legal, just not worth it. If "show open courses" became a hot read path, switching `Course`'s lock to `ReentrantReadWriteLock` is a local, contained change — a benefit of having encapsulated the lock.

**Deadlock-freedom argument:** every operation acquires **at most one lock** (one course's). No thread ever holds lock A while requesting lock B, so a circular wait is impossible by construction — the strongest deadlock argument available, better than lock ordering because there's nothing to order. (Contrast: course-*swap* — drop X, add Y atomically — would need two locks and then the consistent-ordering discipline from your multi-seat booking work: acquire in courseCode order.)

**Livelock/starvation:** `ReentrantLock` in default (non-fair) mode permits barging; under a true stampede you could pass `new ReentrantLock(true)` for FIFO fairness at a throughput cost. Worth *saying* in an interview; default non-fair is the right engineering call because critical sections are microseconds long.

**No I/O under locks:** the critical section is pure in-memory mutation. This is exactly why we don't need the Airline-style two-phase reserve-with-TTL → confirm pattern — that pattern exists to avoid holding locks across a payment gateway call. No external call ⇒ single-phase atomic enroll is sufficient and simpler. Knowing *when not to* use a pattern is the senior signal.

### Graceful degradation & extension seams

| Future feature | Where it slots in |
|---|---|
| **Waitlist** | A `Deque<Student>` inside `Course`, guarded by the same lock; `drop()` promotes the head instead of just freeing the seat. Promotion fires an Observer notification (`RegistrationListener`) — the Auction/Airline notification pattern lands here unchanged. |
| **Prerequisites / per-student course cap / schedule clash** | `List<RegistrationValidator>` injected into the service, each validator a Strategy run at the marked seam before `tryEnroll`. New rule = new class — Open/Closed honored. |
| **Course swap (atomic drop+add)** | New service method acquiring both course locks in courseCode order — your established multi-resource ordering discipline. |
| **Persistence / distribution** | Repositories become DB-backed; the in-memory lock invariant becomes `UPDATE ... WHERE enrolled < capacity` (atomic conditional update) or an optimistic `@Version` column with retry. The interfaces make this a swap, not a rewrite. |

---

## Likely Interviewer Follow-ups (with model answers)

**Q1. "Add a waitlist — what changes?"**
Add a bounded `ArrayDeque<Student>` to `Course`, guarded by the existing lock. `tryEnroll` on a full course offers waitlist placement instead of throwing; `drop` polls the deque and promotes atomically within the same critical section, then notifies via an Observer (`RegistrationListener.onPromoted(...)`) registered on the service. Notification dispatch happens *after* the lock is released (copy the listener list out) so listener code never runs inside the critical section.

**Q2. "10× scale — multiple servers. Your `ReentrantLock` is now useless."**
Correct — JVM locks don't span processes. The invariant moves to the shared store: either a pessimistic `SELECT ... FOR UPDATE` on the course row, an atomic conditional `UPDATE course SET enrolled = enrolled + 1 WHERE code = ? AND enrolled < capacity` (check rows-affected), or optimistic locking with a version column and bounded retry. The service layer and interfaces survive untouched; only repository implementations change — which is the payoff of the Repository abstraction.

**Q3. "Why not `synchronized` methods on `Course`?"**
Functionally nearly equivalent here, and a fine answer. `ReentrantLock` buys optionality: `tryLock(timeout)` for back-pressure during stampedes, fairness mode, and `Condition` objects if a waitlist later needs await/signal. Also, a private lock object can't be abused by external `synchronized(course)` blocks, whereas synchronized methods lock the publicly reachable `this`.

**Q4. "A student registers for two courses at the exact same instant — is their course list safe?"**
Yes — that's precisely why `registeredCourses` is a concurrent set rather than a `HashSet`. The two operations run under *different* course locks, so the student's set sees genuinely concurrent writers; `ConcurrentHashMap.newKeySet()` handles that without the student becoming a serialization point.

**Q5. "Prove there's no race on the last seat."**
The capacity check and the roster insertion execute under one uninterrupted hold of the course lock. For two threads to both pass the check, both would need to hold the lock simultaneously — impossible by mutual exclusion. The demo's 100-thread stampede with `CountDownLatch` start-gun empirically confirms it: exactly `capacity` successes, every run.

---

## Transferable Lesson

**Encapsulate the invariant with the lock that protects it.** `Course.tryEnroll()` makes the seat invariant unbreakable-by-construction because no caller can separate the check from the act. This is the same principle behind LRU Cache's single-lock `get`, Concert Ticket's atomic seat claim, and Digital Wallet's balance check-and-debit — and the "at most one lock per operation" structure is the cleanest deadlock-freedom argument you can offer. Equally transferable: recognizing that **no external I/O in the critical section** means the two-phase reserve/confirm machinery is unnecessary — pattern selection includes pattern *omission*.
