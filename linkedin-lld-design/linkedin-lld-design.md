# Low Level Design — Professional Networking Platform (LinkedIn)

**Difficulty:** Hard | **Source problem set:** awesome-low-level-design | **Language:** Java 11+

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement | Notes |
|---|-------------|-------|
| FR1 | **Registration & Auth** — create account (name, email, password); secure login/logout | Passwords stored as salted hashes, never plaintext |
| FR2 | **Profiles** — picture, headline, summary, experience[], education[], skills[]; user can update | Profile is owned by exactly one User |
| FR3 | **Connections** — send request; recipient accepts/declines; view connection list | Connection is mutual (undirected) once accepted |
| FR4 | **Messaging** — send messages to *connections only*; view inbox and sent | Messaging gated on connection status |
| FR5 | **Job Postings** — employers post jobs (title, description, requirements, location); users view & apply | A user must not apply twice to the same job |
| FR6 | **Search** — search users / companies / jobs by criteria; ranked results | Ranking strategy must be swappable |
| FR7 | **Notifications** — real-time notifications for connection requests, messages, new jobs | Event-driven, decoupled from the action that caused it |

### Non-Functional Requirements

- **Concurrency safety:** many users mutate shared state simultaneously (two users accepting/declining the same request, two applicants racing on a job). No lost updates, no duplicate connections.
- **Scalability:** in-memory design must use data structures and locking granularity that translate to a horizontally scaled system (per-entity locking, not one global lock).
- **Extensibility:** new notification channels (email, push), new search ranking algorithms, new searchable entity types — all without modifying existing classes (OCP).
- **Low latency:** notification delivery is fire-and-forget/async; it must never block the user's primary action.

### Clarifying questions a strong candidate asks (and our assumptions)

1. *Is a Connection directed or mutual?* → **Mutual.** Request is directed; accepted connection is symmetric. (A "follow" feature would be directed — out of scope.)
2. *Can you message non-connections?* → **No.** Connections only. (InMail-style premium messaging is an extension.)
3. *Is an Employer a different actor or a role?* → **A role/capability.** Any user affiliated with a Company can post jobs. Avoids a parallel class hierarchy.
4. *Is search exact-match or fuzzy, and is ranking personalized?* → In-memory keyword match; ranking pluggable via Strategy so an interviewer's "make it personalized" follow-up is a one-class change.
5. *"Real-time" notifications — push or pull?* → In-process Observer push. In production this becomes a message queue + WebSocket; the object design is identical.
6. *Persistence?* → In-memory repositories behind interfaces, so a DB swap is a new implementation, not a redesign.

---

## Step 2 — Entities & Relationships

**Core entities:** `User`, `Profile`, `Experience`, `Education`, `Connection` (and `ConnectionRequest` as its pending form), `Message`, `Company`, `JobPosting`, `JobApplication`, `Notification`.

**Enums:** `RequestStatus {PENDING, ACCEPTED, DECLINED}`, `NotificationType {CONNECTION_REQUEST, MESSAGE, JOB_POSTED, APPLICATION_UPDATE}`, `ApplicationStatus {APPLIED, IN_REVIEW, REJECTED, HIRED}`.

| Relationship | Type | Why |
|---|---|---|
| User → Profile | **Composition** | Profile has no meaning/lifetime without its User; created with the user, dies with the user. |
| Profile → Experience / Education / Skill | **Composition** | Value-style children owned entirely by the profile. |
| User ↔ User via ConnectionRequest | **Association (directed)** | Sender and receiver both exist independently; the request merely links them. |
| User ↔ User via Connection | **Association (undirected)** | Mutual link between two independent objects. |
| Message → sender/receiver User | **Association** | Message references users; deleting a message doesn't touch the users. |
| Company → JobPosting | **Aggregation** | A posting belongs to a company but can be closed/archived independently; the company outlives its postings. |
| JobApplication → User, JobPosting | **Association** | Pure link entity ("many-to-many with attributes" — status, timestamp). |
| NotificationService → User (listeners) | **Dependency / Observer** | Service depends on the `NotificationListener` abstraction, not on concrete users. |

**Common over-modeling traps** (what an interviewer penalizes):
- Separate `Employer extends User` class → wrong; posting jobs is a *capability via company affiliation*, not a subtype. Subclassing for roles violates LSP the moment one person is both job-seeker and recruiter.
- Storing the connection list inside `User` as a mutable public field → breaks encapsulation and makes thread-safety impossible to reason about. Keep relationship state in services/repositories keyed by user ID.
- Modeling `Inbox` as an entity → it's just a query over messages (`receiverId == me`), not a stored object.

---

## Step 3 — UML Class Design

```text
+----------------------+         1        1 +---------------------+
|        User          |◆------------------▶|       Profile       |
+----------------------+   (composition)    +---------------------+
| - id: String         |                    | - headline: String  |
| - name: String       |                    | - summary: String   |
| - email: String      |                    | - pictureUrl: String|
| - passwordHash: Str  |                    | - experiences:List◆ |
| - profile: Profile   |                    | - educations: List◆ |
+----------------------+                    | - skills: Set<Str>  |
| +verifyPassword(raw) |                    +---------------------+
+----------------------+

User "1" ---- "*" ConnectionRequest ---- "1" User      (association, directed)
User "1" ---- "*" Message            ---- "1" User      (association)
User "1" ---- "*" JobApplication     ---- "1" JobPosting(association)
Company "1" ◇--- "*" JobPosting                         (aggregation)

+-------------------------+        +--------------------------+
|   ConnectionRequest     |        |        JobPosting        |
+-------------------------+        +--------------------------+
| - id, senderId,         |        | - id, companyId, title,  |
|   receiverId            |        |   description, location  |
| - status: RequestStatus |        | - requirements: List<Str>|
| +accept() / +decline()  |        | - active: boolean        |
+-------------------------+        +--------------------------+

<<interface>> NotificationListener          <<interface>> SearchRankingStrategy
  +onNotification(Notification)               +score(query, SearchResult): double
        ▲ implements                                ▲ implements
  InAppNotificationListener,                  KeywordRelevanceStrategy,
  EmailNotificationListener (ext.)            RecencyBoostStrategy (ext.)

<<interface>> Searchable                     LinkedInService (Facade, Singleton)
  +getSearchText(): String                     - userService, connectionService,
        ▲ implements                             messageService, jobService,
  User, Company, JobPosting                      searchService, notificationService
```

### SOLID mapping

- **SRP** — each service owns one concern: `ConnectionService` (graph mutations), `MessageService`, `JobService`, `SearchService`, `NotificationService`. `User` holds identity/credentials only; profile data lives in `Profile`.
- **OCP** — new ranking algorithm = new `SearchRankingStrategy`; new notification channel = new `NotificationListener`; new searchable type = implement `Searchable`. Zero edits to existing services.
- **LSP** — no role-based inheritance (`Employer`/`JobSeeker`); any `NotificationListener` is substitutable for another.
- **ISP** — listeners only see `onNotification(...)`; searchables only expose `getSearchText()`. No fat "IUser" interface.
- **DIP** — `NotificationService` depends on the `NotificationListener` abstraction; `SearchService` depends on `SearchRankingStrategy`. In Spring Boot these would be constructor-injected `@Component`s — here we wire them manually in the facade, which is exactly what a DI container automates.

### Design patterns and *why each fits*

| Pattern | Where | Why it fits (the part interviewers want) |
|---|---|---|
| **Observer** | `NotificationService` + `NotificationListener` | FR7 says "events → real-time notifications." The action (accepting a request) must not know *who* is listening or *how* they're notified. Publisher/subscriber decoupling is the textbook Observer use-case; adding email/push later is additive. |
| **Strategy** | `SearchRankingStrategy` | Ranking is an algorithm that varies independently of the search mechanics (filtering). Encapsulating it lets us swap keyword relevance ↔ recency ↔ personalized ML score at runtime, per-request. |
| **Facade (+ Singleton)** | `LinkedInService` | Client code (a controller, a CLI demo) needs one entry point, not six services. Singleton because it owns the shared in-memory state; in Spring this is simply the default singleton bean scope — `@Service` gives you this for free, which is why you never hand-roll `getInstance()` in real Spring code. |
| **State (lightweight)** | `RequestStatus` + guarded transitions in `ConnectionRequest` | Only PENDING → ACCEPTED/DECLINED is legal. With 3 states a full State-pattern class hierarchy is over-engineering; an enum + guard clauses inside a synchronized transition method is the right-sized choice. *Saying why you didn't use the full pattern scores points.* |
| **Repository** | `InMemory*Repository` behind interfaces | Isolates storage; swapping to JPA/Mongo touches no business logic (DIP in action). |

### The two decisions an interviewer will probe

1. **Where does connection state live and how is "accept" made atomic?** Answer: in `ConnectionService`, with the status transition synchronized *on the request object* (per-entity lock), so two concurrent `accept()`/`decline()` calls can't both succeed, and connections are stored in a canonical order to prevent (A,B)/(B,A) duplicates.
2. **Why Observer instead of calling `messageService.notify(...)` inline?** Inline calls couple every feature to every channel and serialize latency into the user's request. Observer + an executor makes delivery async and additive.

---
## Step 4 — Implementation (Java 11+)

Core classes only; trivial getters/setters elided. Everything compiles as plain Java — no framework required.

### Enums & domain objects

```java
public enum RequestStatus { PENDING, ACCEPTED, DECLINED }
public enum ApplicationStatus { APPLIED, IN_REVIEW, REJECTED, HIRED }
public enum NotificationType { CONNECTION_REQUEST, MESSAGE, JOB_POSTED, APPLICATION_UPDATE }
```

```java
import java.util.*;

public class User implements Searchable {
    private final String id;
    private final String name;
    private final String email;
    private final String passwordHash;     // salted hash — NEVER the raw password
    private final Profile profile;         // composition: created with the user

    public User(String name, String email, String passwordHash) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.profile = new Profile();      // lifetime bound to User
    }

    public boolean verifyPassword(String rawPassword, PasswordHasher hasher) {
        return hasher.matches(rawPassword, passwordHash);
    }

    @Override
    public String getSearchText() {
        return (name + " " + profile.getHeadline() + " "
                + String.join(" ", profile.getSkills())).toLowerCase();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Profile getProfile() { return profile; }
}
```

```java
public class Profile {
    private volatile String headline = "";
    private volatile String summary = "";
    private volatile String pictureUrl = "";
    // CopyOnWriteArrayList: profile edits are rare, reads (search, render) are
    // constant — the classic read-heavy case where COW beats synchronizedList.
    private final List<Experience> experiences = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Education> educations = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<String> skills = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void updateHeadline(String h) { this.headline = h; }
    public void addExperience(Experience e) { experiences.add(e); }
    public void addSkill(String s) { skills.add(s.toLowerCase()); }

    public String getHeadline() { return headline; }
    public Set<String> getSkills() { return Set.copyOf(skills); } // defensive copy
}
```

```java
public class ConnectionRequest {
    private final String id = java.util.UUID.randomUUID().toString();
    private final String senderId;
    private final String receiverId;
    private RequestStatus status = RequestStatus.PENDING; // guarded by 'this'

    public ConnectionRequest(String senderId, String receiverId) {
        this.senderId = senderId;
        this.receiverId = receiverId;
    }

    /**
     * Lightweight State pattern: the only legal transition is PENDING -> terminal.
     * synchronized on the request itself = per-entity lock. Two threads racing
     * accept()/decline() — exactly one wins; the loser gets 'false'.
     */
    public synchronized boolean transitionTo(RequestStatus target) {
        if (status != RequestStatus.PENDING) return false;          // idempotency guard
        if (target == RequestStatus.PENDING) throw new IllegalArgumentException();
        status = target;
        return true;
    }

    public synchronized RequestStatus getStatus() { return status; }
    public String getSenderId() { return senderId; }
    public String getReceiverId() { return receiverId; }
    public String getId() { return id; }
}
```

```java
public class Message {
    private final String id = java.util.UUID.randomUUID().toString();
    private final String senderId;
    private final String receiverId;
    private final String content;
    private final java.time.Instant sentAt = java.time.Instant.now();

    public Message(String senderId, String receiverId, String content) {
        this.senderId = senderId; this.receiverId = receiverId; this.content = content;
    }
    public String getSenderId() { return senderId; }
    public String getReceiverId() { return receiverId; }
    public java.time.Instant getSentAt() { return sentAt; }
}
```

```java
public class JobPosting implements Searchable {
    private final String id = java.util.UUID.randomUUID().toString();
    private final String companyId;
    private final String title, description, location;
    private final List<String> requirements;
    private volatile boolean active = true;

    public JobPosting(String companyId, String title, String description,
                      String location, List<String> requirements) {
        this.companyId = companyId; this.title = title;
        this.description = description; this.location = location;
        this.requirements = List.copyOf(requirements);   // immutable snapshot
    }

    public void close() { active = false; }
    public boolean isActive() { return active; }
    public String getId() { return id; }

    @Override
    public String getSearchText() {
        return (title + " " + description + " " + location).toLowerCase();
    }
}
```

### Observer — notifications (FR7)

```java
public interface NotificationListener {           // ISP: one tiny method
    void onNotification(Notification notification);
}

public class Notification {
    private final String id = java.util.UUID.randomUUID().toString();
    private final String targetUserId;
    private final NotificationType type;
    private final String payload;
    public Notification(String targetUserId, NotificationType type, String payload) {
        this.targetUserId = targetUserId; this.type = type; this.payload = payload;
    }
    public String getTargetUserId() { return targetUserId; }
    public NotificationType getType() { return type; }
    public String getPayload() { return payload; }
}
```

```java
import java.util.List;
import java.util.concurrent.*;

public class NotificationService {
    // userId -> that user's listeners (in-app session, email channel, ...)
    private final ConcurrentHashMap<String, List<NotificationListener>> listeners =
            new ConcurrentHashMap<>();
    // Async delivery: the publisher (e.g. ConnectionService) never blocks on I/O.
    private final ExecutorService deliveryPool = Executors.newFixedThreadPool(4);

    public void subscribe(String userId, NotificationListener l) {
        // computeIfAbsent is atomic on ConcurrentHashMap — no check-then-act race.
        listeners.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(l);
    }

    public void publish(Notification n) {
        List<NotificationListener> ls = listeners.get(n.getTargetUserId());
        if (ls == null) return;
        for (NotificationListener l : ls) {
            deliveryPool.submit(() -> {
                try { l.onNotification(n); }
                catch (Exception e) { /* log; one bad listener must not kill others */ }
            });
        }
    }

    public void shutdown() { deliveryPool.shutdown(); }
}
```

### ConnectionService — the concurrency-critical core (FR3)

```java
import java.util.*;
import java.util.concurrent.*;

public class ConnectionService {
    private final ConcurrentHashMap<String, ConnectionRequest> pendingRequests =
            new ConcurrentHashMap<>();
    // Adjacency: userId -> set of connected userIds. ConcurrentHashMap-backed
    // key set gives lock-free reads and safe concurrent adds.
    private final ConcurrentHashMap<String, Set<String>> connections =
            new ConcurrentHashMap<>();
    private final NotificationService notifier;

    public ConnectionService(NotificationService notifier) { this.notifier = notifier; }

    public ConnectionRequest sendRequest(String senderId, String receiverId) {
        if (senderId.equals(receiverId))
            throw new InvalidOperationException("Cannot connect to yourself");
        if (areConnected(senderId, receiverId))
            throw new InvalidOperationException("Already connected");
        // Canonical key prevents the A->B and B->A duplicate-request race.
        String key = canonicalKey(senderId, receiverId);
        ConnectionRequest fresh = new ConnectionRequest(senderId, receiverId);
        ConnectionRequest existing = pendingRequests.putIfAbsent(key, fresh); // atomic
        if (existing != null)
            throw new InvalidOperationException("Request already pending");
        notifier.publish(new Notification(receiverId,
                NotificationType.CONNECTION_REQUEST, "New request from " + senderId));
        return fresh;
    }

    public void respond(String requestId, String responderId, boolean accept) {
        ConnectionRequest req = findPendingById(requestId);
        if (req == null) throw new NotFoundException("Request not found");
        if (!req.getReceiverId().equals(responderId))
            throw new UnauthorizedException("Only the receiver can respond");

        RequestStatus target = accept ? RequestStatus.ACCEPTED : RequestStatus.DECLINED;
        if (!req.transitionTo(target))                       // atomic per-entity CAS-style guard
            throw new InvalidOperationException("Request already resolved");

        pendingRequests.remove(canonicalKey(req.getSenderId(), req.getReceiverId()));
        if (accept) {
            addEdge(req.getSenderId(), req.getReceiverId());
            addEdge(req.getReceiverId(), req.getSenderId());
            notifier.publish(new Notification(req.getSenderId(),
                    NotificationType.CONNECTION_REQUEST,
                    responderId + " accepted your request"));
        }
    }

    public boolean areConnected(String a, String b) {
        return connections.getOrDefault(a, Set.of()).contains(b);
    }

    public Set<String> getConnections(String userId) {
        return Set.copyOf(connections.getOrDefault(userId, Set.of()));
    }

    private void addEdge(String from, String to) {
        connections.computeIfAbsent(from, k -> ConcurrentHashMap.newKeySet()).add(to);
    }

    private static String canonicalKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private ConnectionRequest findPendingById(String id) {
        return pendingRequests.values().stream()
                .filter(r -> r.getId().equals(id)).findFirst().orElse(null);
    }
}
```

### MessageService (FR4) and JobService (FR5)

```java
public class MessageService {
    private final java.util.Queue<Message> messages =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final ConnectionService connectionService;
    private final NotificationService notifier;

    public MessageService(ConnectionService cs, NotificationService n) {
        this.connectionService = cs; this.notifier = n;
    }

    public Message send(String senderId, String receiverId, String content) {
        if (content == null || content.isBlank())
            throw new InvalidOperationException("Empty message");
        // Business rule from FR4: connections only.
        if (!connectionService.areConnected(senderId, receiverId))
            throw new UnauthorizedException("Can only message connections");
        Message m = new Message(senderId, receiverId, content);
        messages.add(m);
        notifier.publish(new Notification(receiverId, NotificationType.MESSAGE,
                "New message from " + senderId));
        return m;
    }

    // Inbox/sent are QUERIES over messages — not stored entities.
    public java.util.List<Message> inbox(String userId) {
        return messages.stream()
                .filter(m -> m.getReceiverId().equals(userId))
                .sorted(java.util.Comparator.comparing(Message::getSentAt).reversed())
                .collect(java.util.stream.Collectors.toList());
    }
    public java.util.List<Message> sent(String userId) {
        return messages.stream()
                .filter(m -> m.getSenderId().equals(userId))
                .collect(java.util.stream.Collectors.toList());
    }
}
```

```java
public class JobService {
    private final java.util.concurrent.ConcurrentHashMap<String, JobPosting> jobs =
            new java.util.concurrent.ConcurrentHashMap<>();
    // (jobId|userId) -> application. putIfAbsent makes "no duplicate apply" atomic.
    private final java.util.concurrent.ConcurrentHashMap<String, JobApplication> applications =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final NotificationService notifier;

    public JobService(NotificationService n) { this.notifier = n; }

    public JobPosting post(JobPosting job) {
        jobs.put(job.getId(), job);
        return job;
    }

    public JobApplication apply(String userId, String jobId) {
        JobPosting job = jobs.get(jobId);
        if (job == null) throw new NotFoundException("Job not found");
        if (!job.isActive()) throw new InvalidOperationException("Job is closed");
        String key = jobId + "|" + userId;
        JobApplication app = new JobApplication(userId, jobId);
        if (applications.putIfAbsent(key, app) != null)        // atomic dedupe
            throw new InvalidOperationException("Already applied");
        return app;
    }
}

class JobApplication {
    private final String userId, jobId;
    private volatile ApplicationStatus status = ApplicationStatus.APPLIED;
    JobApplication(String userId, String jobId) { this.userId = userId; this.jobId = jobId; }
}
```

### Strategy — search & ranking (FR6)

```java
public interface Searchable { String getSearchText(); }

public interface SearchRankingStrategy {
    double score(String query, Searchable item);
}

/** Default: simple keyword-overlap relevance. */
public class KeywordRelevanceStrategy implements SearchRankingStrategy {
    @Override
    public double score(String query, Searchable item) {
        String text = item.getSearchText();
        double hits = 0;
        for (String token : query.toLowerCase().split("\\s+")) {
            if (text.contains(token)) hits++;
        }
        return hits;
    }
}
```

```java
import java.util.*;
import java.util.stream.Collectors;

public class SearchService {
    private final List<Searchable> index = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile SearchRankingStrategy ranking;   // swappable at runtime (Strategy)

    public SearchService(SearchRankingStrategy ranking) { this.ranking = ranking; }
    public void register(Searchable item) { index.add(item); }
    public void setRankingStrategy(SearchRankingStrategy s) { this.ranking = s; }

    public <T extends Searchable> List<T> search(String query, Class<T> type, int limit) {
        return index.stream()
                .filter(type::isInstance).map(type::cast)
                .map(item -> Map.entry(item, ranking.score(query, item)))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<T, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
```

### Facade — single entry point

```java
public class LinkedInService {
    private static volatile LinkedInService instance;          // Singleton (DCL)

    private final NotificationService notificationService = new NotificationService();
    private final ConnectionService connectionService = new ConnectionService(notificationService);
    private final MessageService messageService = new MessageService(connectionService, notificationService);
    private final JobService jobService = new JobService(notificationService);
    private final SearchService searchService = new SearchService(new KeywordRelevanceStrategy());
    private final java.util.concurrent.ConcurrentHashMap<String, User> usersByEmail =
            new java.util.concurrent.ConcurrentHashMap<>();

    private LinkedInService() {}

    public static LinkedInService getInstance() {
        if (instance == null) {
            synchronized (LinkedInService.class) {
                if (instance == null) instance = new LinkedInService(); // double-checked locking
            }
        }
        return instance;
    }
    // Spring Boot note: all of this is what @Service + constructor injection gives
    // you automatically — singleton-scoped beans wired by the container. You'd
    // never write getInstance() in Spring; the pattern lives in the container.

    public User register(String name, String email, String rawPassword, PasswordHasher hasher) {
        User u = new User(name, email, hasher.hash(rawPassword));
        if (usersByEmail.putIfAbsent(email.toLowerCase(), u) != null)   // atomic dedupe
            throw new InvalidOperationException("Email already registered");
        searchService.register(u);
        return u;
    }

    public ConnectionService connections() { return connectionService; }
    public MessageService messages() { return messageService; }
    public JobService jobs() { return jobService; }
    public SearchService search() { return searchService; }
    public NotificationService notifications() { return notificationService; }
}
```

```java
// Domain exceptions — unchecked: callers can't reasonably recover inline,
// and they map cleanly to HTTP 4xx in a Spring controller advice.
public class NotFoundException extends RuntimeException {
    public NotFoundException(String m) { super(m); } }
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String m) { super(m); } }
public class InvalidOperationException extends RuntimeException {
    public InvalidOperationException(String m) { super(m); } }

public interface PasswordHasher {            // DIP: swap BCrypt/Argon2 freely
    String hash(String raw);
    boolean matches(String raw, String hashed);
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Input validation & domain errors

| Case | Handling |
|---|---|
| Duplicate email at registration | `putIfAbsent` on `usersByEmail` → `InvalidOperationException`. Atomic, no check-then-act gap. |
| Self-connection request | Rejected up front in `sendRequest`. |
| Duplicate / crossing requests (A→B while B→A in flight) | Canonical key `min(a,b)\|max(a,b)` + `putIfAbsent` collapses both directions into one pending slot. |
| Responder is not the receiver | `UnauthorizedException` — authorization enforced in the service, not trusted from the caller. |
| Message to a non-connection / blank message | `UnauthorizedException` / `InvalidOperationException` before any state change. |
| Apply to closed or missing job | Checked before insert; note the benign race below. |
| Notification listener throws | Caught per-listener inside the executor task — one failing channel can't poison delivery to others. |

### Concurrency analysis (the part to narrate in an interview)

**Shared mutable state:** `pendingRequests`, the `connections` adjacency map, `usersByEmail`, `applications`, the search index, each `ConnectionRequest.status`.

**Critical sections & chosen primitives:**

1. **Request status transition** — `synchronized transitionTo(...)` on the request instance. Per-entity locking: contention only exists between threads touching the *same* request, never across requests. The PENDING guard makes accept/decline idempotent — the second caller fails cleanly instead of corrupting state.
2. **Map insertions with uniqueness invariants** (email, pending request, job application) — `ConcurrentHashMap.putIfAbsent` / `computeIfAbsent`. These are atomic compound operations, eliminating the classic *check-then-act* race that `if (!map.contains(k)) map.put(k,v)` has even on a concurrent map.
3. **Adjacency updates** — two `addEdge` calls (A→B, B→A). Each is individually atomic. There is a microscopic window where A→B exists but B→A doesn't yet; we accept it because `areConnected` is advisory and eventually consistent within microseconds. If strict symmetry were required, take both per-user locks in canonical (sorted) order — sorting the lock order is what prevents deadlock (no circular wait).
4. **Notification fan-out** — `ExecutorService` decouples publish from delivery; the publisher thread never blocks on a slow listener (latency NFR).
5. **Read-heavy collections** — `CopyOnWriteArrayList` for profile sections and the search index (writes rare, reads constant); `ConcurrentLinkedQueue` for the append-only message log.

**Why it's free of deadlock / livelock / races:**
- *Deadlock:* no code path ever holds two locks simultaneously (and the documented strict-symmetry variant orders lock acquisition canonically — breaks the circular-wait condition).
- *Livelock:* no retry loops; losers of a race get an exception, not a spin.
- *Race conditions:* every invariant ("one pending request per pair", "one application per user per job", "one resolution per request", "unique email") is enforced by a single atomic operation, not by separate check and act steps.

**Benign race acknowledged:** a job can be closed between `isActive()` and `putIfAbsent` in `apply`. The application lands against a just-closed job — harmless, and fixable by re-checking after insert and compensating (remove + throw), or by a per-job `ReadWriteLock` if the business demands strictness. Knowing which races are *worth* paying a lock for is senior-level judgment.

### Graceful degradation & scale path

- Notification pool saturated → tasks queue; primary actions still succeed (notifications are best-effort by design).
- Linear-scan search degrades at scale → the `SearchService` interface stays put; the implementation swaps to an inverted index or Elasticsearch.
- Single-JVM maps → at 10x scale, repositories become DB tables (unique constraints replace `putIfAbsent`), the Observer becomes Kafka + WebSocket push, and per-entity `synchronized` becomes optimistic locking (`@Version`) or `SELECT ... FOR UPDATE`. **The object model does not change — only the implementations behind the interfaces.** That sentence is the whole point of LLD.
