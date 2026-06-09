# Low Level Design — Task Management System (Java)

---

## Step 1 — Requirements

### Functional Requirements

1. **Task CRUD** — users can create, update, and delete tasks.
2. **Task attributes** — every task has: title, description, due date, priority (`LOW / MEDIUM / HIGH / URGENT`), and status (`PENDING / IN_PROGRESS / COMPLETED`).
3. **Assignment** — a task can be assigned to a user; creator and assignee may differ. Single assignee per task (nullable until assigned).
4. **Reminders** — a reminder (time + message) can be attached to a task. Passive data in the core design; an active scheduler is treated as an extension.
5. **Search & filter** — tasks can be searched/filtered by keyword, priority, status, due-date range, and assigned user. Filters must be **composable** (e.g., "HIGH priority AND due this week AND assigned to me").
6. **Completion & history** — a user can mark a task completed and view the list of tasks they have completed (task history).

### Non-Functional Requirements

| Concern | Decision |
|---|---|
| **Concurrency** | Multiple threads may read/update the *same* task concurrently. Storage must be thread-safe, and mutations on an individual task must be atomic and consistent. |
| **Consistency** | No lost updates; status transitions must remain valid under contention (e.g., two threads racing to complete/reassign the same task). |
| **Extensibility** | Adding a new filter criterion, status, or task feature (tags, comments) must not require modifying existing classes — Open/Closed Principle is the headline. |
| **Scale** | In-memory LLD exercise: thousands of tasks, not millions. Linear-scan search with predicates is acceptable; we note where indexes would go at scale. |

### Assumptions

- Constrained status lifecycle: `PENDING → IN_PROGRESS → COMPLETED`, reopen allowed (`COMPLETED → IN_PROGRESS`). Invalid transitions throw.
- "Task history" = tasks a user has completed (audit log discussed as an extension).
- Any user may modify any task (no authorization layer).
- Hard delete; in-memory storage; no persistence.

---

## Step 2 — Entities & Relationships

### Core Entities

| Entity | Responsibility |
|---|---|
| `User` | Identity of a person in the system (id, name, email). Immutable value-like entity. |
| `Task` | The central aggregate: attributes, lifecycle state, assignee, reminders. Owns its own thread-safety. |
| `TaskStatus` (enum) | Lifecycle states **plus the legal transitions between them** — a lightweight State pattern. |
| `TaskPriority` (enum) | LOW / MEDIUM / HIGH / URGENT, ordered so it can be used for sorting. |
| `Reminder` | A point-in-time alert attached to a task (immutable). |
| `TaskFilter` (interface) | A composable search criterion — Strategy/Specification pattern. |
| `TaskManager` | Singleton service: registry of tasks, CRUD, search, history. The only entry point clients use. |

### Relationships

| Relationship | Type | Why |
|---|---|---|
| `TaskManager` → `Task` | **Aggregation** | The manager holds tasks in its registry, but a `Task` is conceptually independent of the manager — it isn't destroyed because the registry is. |
| `Task` → `User` (createdBy, assignedTo) | **Association** | A task references users; neither owns the other's lifecycle. Users exist independently of tasks. |
| `Task` → `Reminder` | **Composition** | A reminder has no meaning outside its task. Delete the task and its reminders die with it. |
| `Task` → `TaskStatus`, `TaskPriority` | **Association (attribute)** | Enum-typed fields. |
| `TaskManager` → `TaskFilter` | **Dependency** | The manager *uses* a filter passed into `search(...)` but doesn't hold one. |

### Common Modeling Mistakes (what NOT to do)

- **Putting CRUD inside `User`** (`user.createTask(...)` storing tasks in the user) — splits the registry across users and makes global search painful. Users are data; `TaskManager` is the service. (SRP)
- **Making status a `String`** — loses transition control and compile-time safety.
- **A `SearchService` with one method per criterion** (`searchByPriority`, `searchByStatusAndUser`, ...) — combinatorial explosion; violates OCP. Composable filters fix this.
- **Over-modeling**: `TaskList`, `Project`, `Team`, `Notification` hierarchies — not in the requirements; mention them as extensions instead.

---

## Step 3 — UML Class Design

```text
+----------------------+          +---------------------------+
|        User          |          |        <<enum>>           |
+----------------------+          |        TaskStatus         |
| - id: String         |          +---------------------------+
| - name: String       |          | PENDING                   |
| - email: String      |          | IN_PROGRESS               |
+----------------------+          | COMPLETED                 |
          ^                       +---------------------------+
          | createdBy / assignedTo| + canTransitionTo(s): bool|
          | (association)         +---------------------------+
          |
+--------------------------------------+        +---------------------+
|                Task                  |<>------|      Reminder       |
+--------------------------------------+ 1    * +---------------------+
| - id: String                         | (composition)| - id: String  |
| - title: String                      |        | - remindAt: LocalDateTime |
| - description: String                |        | - message: String   |
| - dueDate: LocalDate                 |        +---------------------+
| - priority: TaskPriority             |
| - status: TaskStatus                 |        +---------------------------+
| - createdBy: User                    |        |        <<enum>>           |
| - assignedTo: User                   |        |       TaskPriority        |
| - reminders: List<Reminder>          |        +---------------------------+
| - lock: ReentrantLock                |        | LOW, MEDIUM, HIGH, URGENT |
+--------------------------------------+        +---------------------------+
| + update(title, desc, due, priority) |
| + assignTo(user)                     |        +---------------------------+
| + startProgress() / complete()       |        |      <<interface>>        |
| + reopen()                           |        |        TaskFilter         |
| + addReminder(reminder)              |        +---------------------------+
| + snapshot(): TaskView               |        | + matches(task): boolean  |
+--------------------------------------+        | + and(other): TaskFilter  |
          ^ aggregation (0..*)                  | + or(other): TaskFilter   |
          |                                     +---------------------------+
+--------------------------------------+              ^ implements
|       TaskManager  <<singleton>>     |   +----------+-----------+--------------+
+--------------------------------------+   |          |           |              |
| - tasks: ConcurrentHashMap<String,Task>  Keyword  Priority   Assignee   DueDateRange
| - INSTANCE: TaskManager              |   Filter   Filter     Filter     Filter
+--------------------------------------+
| + createTask(...): Task              |
| + updateTask(id, ...): void          |
| + deleteTask(id): void               |
| + assignTask(id, user): void         |
| + markCompleted(id): void            |
| + search(filter): List<Task>         |
| + getTaskHistory(user): List<Task>   |
+--------------------------------------+
```

### Design Patterns Used — and WHY

| Pattern | Where | Why it fits *here* |
|---|---|---|
| **Singleton** | `TaskManager` | One authoritative in-memory registry; two instances would mean two divergent sources of truth. (In Spring Boot you would never hand-roll this — a `@Service` bean is a container-managed singleton by default scope; same intent, better testability via DI.) |
| **Strategy / Specification** | `TaskFilter` + implementations | Each search criterion is an interchangeable algorithm. `and()/or()` default methods make criteria *composable*, so a new criterion (e.g., `TagFilter`) is a new class — zero changes to `TaskManager.search()`. This is the OCP showcase of the problem. |
| **State (lightweight)** | `TaskStatus.canTransitionTo()` | Full GoF State (one class per state) is over-engineering for 3 states; encoding the transition table inside the enum gives the same guarantee — illegal transitions are impossible — with a tenth of the code. Know how to argue this trade-off. |
| **Builder** | `Task.Builder` | Task has 2 required + 4 optional construction parameters; a telescoping constructor would be unreadable and error-prone. Builder also lets us validate once, at `build()`. |
| **Facade** | `TaskManager` | Clients touch one API; internal coordination (locking, registry, history) is hidden. |
| **Observer** *(extension)* | Reminder firing | If reminders become *active*, a `ReminderScheduler` observes due reminders and notifies listeners — kept out of core scope deliberately. |

### SOLID Mapping

- **S** — `Task` holds state + its own invariants; `TaskManager` orchestrates; filters only match. No god class.
- **O** — new filters/priorities/statuses are added without editing `search()` or `Task`.
- **L** — every `TaskFilter` is substitutable anywhere a filter is expected (pure predicate, no side effects).
- **I** — `TaskFilter` exposes exactly one abstract method; clients aren't forced to depend on unused methods.
- **D** — `TaskManager.search()` depends on the `TaskFilter` abstraction, never on concrete filter classes.

### The Two Decisions an Interviewer Will Probe

1. **Where does thread-safety live?** Answer: storage-level safety in `ConcurrentHashMap`, **per-task** mutation safety inside `Task` itself (its own lock). Locking the whole manager would serialize unrelated tasks; locking nothing risks lost updates on the same task.
2. **Why composable filters instead of query methods?** Answer: OCP + combinatorial explosion. `searchByPriorityAndUserAndDateRange(...)` style APIs grow factorially; predicates compose linearly.

---

## Step 4 — Implementation

### Enums: `TaskPriority` and `TaskStatus`

```java
public enum TaskPriority {
    LOW, MEDIUM, HIGH, URGENT;   // declaration order = natural order, so sorting works for free
}
```

```java
import java.util.EnumSet;
import java.util.Set;

/**
 * Lightweight State pattern: each enum constant knows its legal next states.
 * Illegal transitions are rejected in one place, by construction.
 */
public enum TaskStatus {
    PENDING {
        @Override Set<TaskStatus> next() { return EnumSet.of(IN_PROGRESS, COMPLETED); }
    },
    IN_PROGRESS {
        @Override Set<TaskStatus> next() { return EnumSet.of(COMPLETED, PENDING); }
    },
    COMPLETED {
        @Override Set<TaskStatus> next() { return EnumSet.of(IN_PROGRESS); } // reopen
    };

    abstract Set<TaskStatus> next();

    public boolean canTransitionTo(TaskStatus target) {
        return next().contains(target);
    }
}
```

### `User` and `Reminder` (immutable)

```java
import java.util.Objects;
import java.util.UUID;

public final class User {
    private final String id;
    private final String name;
    private final String email;

    public User(String name, String email) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name, "name");
        this.email = Objects.requireNonNull(email, "email");
    }

    public String getId()    { return id; }
    public String getName()  { return name; }
    public String getEmail() { return email; }

    // Identity-based equality: two User objects are the same user iff same id.
    @Override public boolean equals(Object o) {
        return this == o || (o instanceof User && id.equals(((User) o).id));
    }
    @Override public int hashCode() { return id.hashCode(); }
}
```

```java
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public final class Reminder {
    private final String id;
    private final LocalDateTime remindAt;
    private final String message;

    public Reminder(LocalDateTime remindAt, String message) {
        if (remindAt.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Reminder time must be in the future");
        }
        this.id = UUID.randomUUID().toString();
        this.remindAt = remindAt;
        this.message = Objects.requireNonNull(message, "message");
    }

    public String getId()              { return id; }
    public LocalDateTime getRemindAt() { return remindAt; }
    public String getMessage()         { return message; }
}
```

### `Task` — Builder construction, lock-guarded mutation

```java
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public class Task {
    private final String id;
    private final User createdBy;

    // Mutable state — every read/write goes through `lock`.
    private String title;
    private String description;
    private LocalDate dueDate;
    private TaskPriority priority;
    private TaskStatus status;
    private User assignedTo;                       // nullable until assigned
    private final List<Reminder> reminders = new ArrayList<>();

    // Per-task lock: contention is scoped to ONE task, not the whole system.
    private final ReentrantLock lock = new ReentrantLock();

    private Task(Builder b) {
        this.id = UUID.randomUUID().toString();
        this.title = b.title;
        this.description = b.description;
        this.dueDate = b.dueDate;
        this.priority = b.priority;
        this.createdBy = b.createdBy;
        this.assignedTo = b.assignedTo;
        this.status = TaskStatus.PENDING;          // every task is born PENDING
    }

    /* ---------- guarded mutators ---------- */

    public void update(String title, String description, LocalDate dueDate, TaskPriority priority) {
        lock.lock();
        try {
            if (title != null)       this.title = title;        // partial update: null = "leave as is"
            if (description != null) this.description = description;
            if (dueDate != null)     this.dueDate = dueDate;
            if (priority != null)    this.priority = priority;
        } finally {
            lock.unlock();
        }
    }

    public void assignTo(User user) {
        Objects.requireNonNull(user, "assignee");
        lock.lock();
        try {
            if (status == TaskStatus.COMPLETED) {
                throw new InvalidTaskStateException("Cannot reassign a completed task");
            }
            this.assignedTo = user;
        } finally {
            lock.unlock();
        }
    }

    public void startProgress() { transitionTo(TaskStatus.IN_PROGRESS); }
    public void complete()      { transitionTo(TaskStatus.COMPLETED); }
    public void reopen()        { transitionTo(TaskStatus.IN_PROGRESS); }

    // Check-then-act on `status` MUST be atomic — hence inside the lock.
    private void transitionTo(TaskStatus target) {
        lock.lock();
        try {
            if (!status.canTransitionTo(target)) {
                throw new InvalidTaskStateException(
                    "Illegal transition " + status + " -> " + target + " for task " + id);
            }
            this.status = target;
        } finally {
            lock.unlock();
        }
    }

    public void addReminder(Reminder reminder) {
        lock.lock();
        try {
            reminders.add(Objects.requireNonNull(reminder));
        } finally {
            lock.unlock();
        }
    }

    /* ---------- guarded readers ---------- */
    // Reads also take the lock: guarantees visibility (happens-before) without
    // sprinkling `volatile` on every field.

    public String getId()        { return id; }       // final -> safe unlocked
    public User getCreatedBy()   { return createdBy; }// final -> safe unlocked

    public String getTitle()        { lock.lock(); try { return title; }       finally { lock.unlock(); } }
    public String getDescription()  { lock.lock(); try { return description; } finally { lock.unlock(); } }
    public LocalDate getDueDate()   { lock.lock(); try { return dueDate; }     finally { lock.unlock(); } }
    public TaskPriority getPriority(){ lock.lock(); try { return priority; }   finally { lock.unlock(); } }
    public TaskStatus getStatus()   { lock.lock(); try { return status; }      finally { lock.unlock(); } }
    public User getAssignedTo()     { lock.lock(); try { return assignedTo; }  finally { lock.unlock(); } }

    public List<Reminder> getReminders() {
        lock.lock();
        try {
            // Defensive copy: never leak the internal mutable list.
            return Collections.unmodifiableList(new ArrayList<>(reminders));
        } finally {
            lock.unlock();
        }
    }

    /* ---------- Builder ---------- */

    public static class Builder {
        private final String title;        // required
        private final User createdBy;      // required
        private String description = "";
        private LocalDate dueDate;
        private TaskPriority priority = TaskPriority.MEDIUM;
        private User assignedTo;

        public Builder(String title, User createdBy) {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Task title is required");
            }
            this.title = title;
            this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        }

        public Builder description(String d)      { this.description = d; return this; }
        public Builder dueDate(LocalDate d)       { this.dueDate = d;     return this; }
        public Builder priority(TaskPriority p)   { this.priority = p;    return this; }
        public Builder assignedTo(User u)         { this.assignedTo = u;  return this; }

        public Task build() {
            if (dueDate != null && dueDate.isBefore(LocalDate.now())) {
                throw new IllegalArgumentException("Due date cannot be in the past");
            }
            return new Task(this);
        }
    }
}
```

### Filters — Strategy/Specification with composition

```java
/** One composable search criterion. New criteria = new classes; search code never changes (OCP). */
@FunctionalInterface
public interface TaskFilter {
    boolean matches(Task task);

    default TaskFilter and(TaskFilter other) { return t -> this.matches(t) && other.matches(t); }
    default TaskFilter or(TaskFilter other)  { return t -> this.matches(t) || other.matches(t); }
    default TaskFilter negate()              { return t -> !this.matches(t); }
}
```

```java
import java.time.LocalDate;

public class PriorityFilter implements TaskFilter {
    private final TaskPriority priority;
    public PriorityFilter(TaskPriority priority) { this.priority = priority; }
    @Override public boolean matches(Task t) { return t.getPriority() == priority; }
}

public class StatusFilter implements TaskFilter {
    private final TaskStatus status;
    public StatusFilter(TaskStatus status) { this.status = status; }
    @Override public boolean matches(Task t) { return t.getStatus() == status; }
}

public class AssigneeFilter implements TaskFilter {
    private final User user;
    public AssigneeFilter(User user) { this.user = user; }
    @Override public boolean matches(Task t) { return user.equals(t.getAssignedTo()); }
}

public class KeywordFilter implements TaskFilter {
    private final String keyword;
    public KeywordFilter(String keyword) { this.keyword = keyword.toLowerCase(); }
    @Override public boolean matches(Task t) {
        return t.getTitle().toLowerCase().contains(keyword)
            || t.getDescription().toLowerCase().contains(keyword);
    }
}

public class DueDateRangeFilter implements TaskFilter {
    private final LocalDate from, to;   // inclusive
    public DueDateRangeFilter(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw new IllegalArgumentException("from > to");
        this.from = from; this.to = to;
    }
    @Override public boolean matches(Task t) {
        LocalDate d = t.getDueDate();
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }
}
```

### Custom exceptions

```java
public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(String taskId) {
        super("Task not found: " + taskId);
    }
}

public class InvalidTaskStateException extends RuntimeException {
    public InvalidTaskStateException(String message) { super(message); }
}
```

### `TaskManager` — Singleton facade

```java
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TaskManager {

    // Eager, JVM-guaranteed thread-safe singleton initialization.
    private static final TaskManager INSTANCE = new TaskManager();
    public static TaskManager getInstance() { return INSTANCE; }
    private TaskManager() {}

    // Thread-safe registry: lock-free reads, fine-grained (bin-level) write locking.
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    /* ---------- CRUD ---------- */

    public Task createTask(String title, String description, LocalDate dueDate,
                           TaskPriority priority, User createdBy) {
        Task task = new Task.Builder(title, createdBy)
                .description(description)
                .dueDate(dueDate)
                .priority(priority)
                .build();
        tasks.put(task.getId(), task);
        return task;
    }

    public void updateTask(String taskId, String title, String description,
                           LocalDate dueDate, TaskPriority priority) {
        getOrThrow(taskId).update(title, description, dueDate, priority);
    }

    public void deleteTask(String taskId) {
        if (tasks.remove(taskId) == null) {        // atomic check-and-remove
            throw new TaskNotFoundException(taskId);
        }
    }

    public Task getTask(String taskId) { return getOrThrow(taskId); }

    /* ---------- domain operations ---------- */

    public void assignTask(String taskId, User assignee) {
        getOrThrow(taskId).assignTo(assignee);
    }

    public void markCompleted(String taskId) {
        getOrThrow(taskId).complete();
    }

    public void addReminder(String taskId, Reminder reminder) {
        getOrThrow(taskId).addReminder(reminder);
    }

    /* ---------- search & history ---------- */

    public List<Task> search(TaskFilter filter) {
        // Weakly consistent iteration over ConcurrentHashMap: never throws
        // ConcurrentModificationException; reflects some-point-in-time state.
        return tasks.values().stream()
                .filter(filter::matches)
                .sorted(Comparator.comparing(Task::getPriority).reversed()) // URGENT first
                .collect(Collectors.toList());
    }

    public List<Task> getTaskHistory(User user) {
        // History = tasks this user is assigned to that are COMPLETED.
        return search(new AssigneeFilter(user).and(new StatusFilter(TaskStatus.COMPLETED)));
    }

    private Task getOrThrow(String taskId) {
        Task t = tasks.get(taskId);
        if (t == null) throw new TaskNotFoundException(taskId);
        return t;
    }
}
```

### Demo

```java
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class TaskManagementDemo {
    public static void main(String[] args) {
        TaskManager mgr = TaskManager.getInstance();
        User alice = new User("Alice", "alice@example.com");
        User bob   = new User("Bob", "bob@example.com");

        Task t1 = mgr.createTask("Fix payment bug", "NPE in checkout flow",
                LocalDate.now().plusDays(2), TaskPriority.URGENT, alice);
        Task t2 = mgr.createTask("Write API docs", "Document v2 endpoints",
                LocalDate.now().plusDays(7), TaskPriority.LOW, alice);

        mgr.assignTask(t1.getId(), bob);
        mgr.assignTask(t2.getId(), bob);
        mgr.addReminder(t1.getId(),
                new Reminder(LocalDateTime.now().plusDays(1), "Payment bug due tomorrow"));

        t1.startProgress();
        mgr.markCompleted(t1.getId());

        // Composed filter: Bob's completed tasks
        List<Task> history = mgr.getTaskHistory(bob);
        history.forEach(t -> System.out.println("Completed: " + t.getTitle()));

        // Composed filter: urgent OR due within 3 days
        TaskFilter urgentOrSoon = new PriorityFilter(TaskPriority.URGENT)
                .or(new DueDateRangeFilter(LocalDate.now(), LocalDate.now().plusDays(3)));
        System.out.println("Hot tasks: " + mgr.search(urgentOrSoon).size());
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Exception Strategy

| Failure | Handling |
|---|---|
| Unknown task id (get/update/delete/assign) | `TaskNotFoundException` (unchecked — caller bug or stale id, not a recoverable condition) |
| Illegal status transition (`PENDING → ???`, double-complete) | `InvalidTaskStateException`, thrown atomically inside the task's lock |
| Blank title, past due date, past reminder time | `IllegalArgumentException` at construction — invalid objects can never exist (validate in `Builder.build()` / constructors) |
| Reassigning a completed task | `InvalidTaskStateException` |
| `null` user/reminder arguments | `Objects.requireNonNull` fails fast at the boundary |

Design rule: **validate at construction, guard at transition**. Domain exceptions are unchecked and carry the task id so callers can log/correlate.

### Edge Cases

- **Unassigned tasks** — `assignedTo` is nullable; `AssigneeFilter` uses `user.equals(t.getAssignedTo())` (constant on the left) so null assignee is safely non-matching, never an NPE.
- **Delete-while-updating race** — thread A deletes task X while thread B updates it. B's update mutates an orphaned object (harmless, unreachable afterwards) or B fails earlier with `TaskNotFoundException`. Both outcomes are consistent; no corruption. If "update must fail after delete" were required, add a `deleted` flag checked inside the task's lock.
- **Double-complete race** — two threads call `complete()` simultaneously. The lock serializes them; the second sees `COMPLETED → COMPLETED` is not in the transition table and gets `InvalidTaskStateException`. Exactly-once semantics, no lost signal.
- **Search during writes** — `ConcurrentHashMap` iteration is *weakly consistent*: no `ConcurrentModificationException`, results reflect a legitimate point-in-time view, possibly missing a task inserted mid-scan. Acceptable for search; state this trade-off explicitly in an interview.
- **Reopen flow** — `COMPLETED → IN_PROGRESS` is allowed; note that a reopened task drops out of history (history is derived from current status). If history must be permanent, switch to an append-only completion log — see follow-ups.
- **Time zones** — `LocalDate/LocalDateTime` assume one zone; a real system stores `Instant` and renders per-user zone.

### Concurrency Analysis (the part interviewers grade hardest)

**Shared mutable state:**
1. The `tasks` registry in `TaskManager`.
2. Each `Task`'s fields (`title`, `status`, `assignedTo`, `reminders`, ...).

**Critical sections & chosen primitives:**

| Shared state | Primitive | Why this one |
|---|---|---|
| Registry (put/get/remove) | `ConcurrentHashMap` | Lock-free reads + striped writes beat a `synchronized` `HashMap` wrapper; `remove` is an atomic check-and-act so delete needs no extra locking. |
| Per-task mutation (`update`, `transitionTo`, `assignTo`, `addReminder`) | `ReentrantLock` **per task** | The status transition is a *check-then-act* (`canTransitionTo` then write) — it must be atomic. A per-task lock scopes contention to one task: threads working on different tasks never block each other. A single global lock would needlessly serialize the whole system. |
| Per-task reads | Same lock | Taking the lock on reads gives the happens-before edge that guarantees readers see the latest committed write — simpler to argue than `volatile` on six fields. |
| Singleton init | Eager `static final` | JVM class-loading guarantees safe publication; no double-checked-locking ceremony needed. |

**Why this is deadlock-free:** every operation acquires **at most one lock at a time** (either a map bin internally, or one task's lock — never two task locks, never task-lock-then-map-lock while holding another). With no chains of held locks, the circular-wait condition for deadlock cannot form. The day an operation needs *two* tasks (e.g., "merge task A into B"), impose a global lock ordering — acquire by ascending task id — to keep circular wait impossible.

**Why no livelock/starvation in practice:** `ReentrantLock()` (non-fair) can theoretically starve a thread under pathological contention; critical sections here are a few field writes, so hold times are nanoseconds. If fairness mattered, `new ReentrantLock(true)` trades throughput for FIFO ordering.

**Race conditions eliminated:** lost update on concurrent `update()` (serialized by lock), invalid double transition (check-then-act inside lock), reminder list corruption (guarded + defensive copies), torn/stale reads (lock on readers).

**Scale-up note:** at 10x–100x load the linear-scan `search` becomes the bottleneck before locking does. Fixes in order: maintain secondary indexes (`Map<TaskStatus, Set<String>>`, `Map<UserId, Set<String>>`) updated inside task transitions; then a read-optimized snapshot model (copy-on-write `TaskView`s); then move storage to a database where filtering is a query.

---

## Interviewer Follow-ups (with model answers)

1. **"Make reminders actually fire."** Add a `ReminderScheduler` backed by `ScheduledExecutorService` (or a `DelayQueue` of reminders). When a reminder fires, publish to `NotificationListener` observers (email, push) — Observer pattern. Cancellation: keep the `ScheduledFuture` keyed by reminder id and cancel on task delete.
2. **"Support a full audit log of every change."** Don't bolt fields onto `Task`. Emit immutable `TaskEvent(taskId, actor, type, timestamp, payload)` records to an append-only, per-task event list (or queue). This is the first step toward event sourcing, and it makes "history" permanent and reconstructible.
3. **"Add sub-tasks."** Composite pattern: `TaskComponent` interface with `Task` (leaf) and `TaskGroup` (composite). Decide and defend the rollup rule: a parent is COMPLETED only when all children are.
4. **"Multiple assignees?"** `assignedTo` becomes `Set<User>` guarded by the same lock; `AssigneeFilter` checks `contains`. The interesting part is semantics: does *any* assignee completing it complete the task, or do you need per-assignee completion state?
5. **"Why not `synchronized` instead of `ReentrantLock`?"** For this design `synchronized(this)`-style monitors would work and be simpler. `ReentrantLock` buys `tryLock(timeout)` (useful if we later lock two tasks and want deadlock avoidance), fairness option, and interruptible acquisition. In an interview, *saying you know the trade-off matters more than the choice.*

## Transferable Lessons

- **Composable predicate filters (Specification)** reappear in: Hotel Booking search, Splitwise expense queries, Logging framework level/topic filters, any "filter by N criteria" requirement. Default-method `and/or` composition is the OCP move to remember.
- **Transition table inside an enum** is the budget State pattern — reuse it in Vending Machine, Elevator, Order lifecycle. Upgrade to full State classes only when each state carries distinct *behavior*, not just distinct *next-states*.
- **Lock granularity** — "lock the entity, not the system" — is the same argument you'll make in Movie Ticket Booking (per-seat/per-show locks) and Parking Lot (per-spot).

**Next problem suggestion:** *Vending Machine* — first problem where the full GoF State pattern (state classes, not just an enum table) genuinely earns its keep, so you can contrast it with what we did here.
