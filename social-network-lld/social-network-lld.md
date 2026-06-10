# Low Level Design — Social Network (Facebook-like)

**Language:** Java 11+ (framework-agnostic; Spring Boot notes where they illuminate the design)
**Difficulty:** Medium · **Key patterns:** Observer, Strategy, Singleton (Facade), Repository

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement | Detail |
|---|-------------|--------|
| F1 | Account lifecycle | Register with name, email, password; login; logout. Email is unique. |
| F2 | Profile management | Profile picture, bio, interests; owner can update them. |
| F3 | Friendship | Send friend request → recipient accepts or declines. Friendship is **bidirectional** once accepted. Users can view their friend list and unfriend. |
| F4 | Posts | Create a post with text plus optional image/video attachments; timestamped; author can delete. |
| F5 | Newsfeed | Posts from self + friends, **reverse chronological**, privacy-filtered. |
| F6 | Engagement | Like a post (at most once per user, idempotent), unlike, comment; view likes and comments. |
| F7 | Privacy | Per-post visibility: `PUBLIC` / `FRIENDS_ONLY` / `PRIVATE`. Profile visibility control. Every read path enforces it. |
| F8 | Notifications | Generated on friend request, request accepted, like, comment, mention. Delivered "real-time" (in-process Observer dispatch at LLD scope). |

### Non-Functional Requirements

| # | Requirement | Detail |
|---|-------------|--------|
| N1 | Thread safety | Concurrent likes on the same post, simultaneous friend-request accept/decline, feed reads racing post writes — all must be safe. |
| N2 | Extensibility (OCP) | New post media types, new notification channels (email/push), new privacy levels — without modifying core classes. |
| N3 | Performance | Feed cost proportional to the user's friend count, not total system posts. Pull-based feed; mention push (fan-out-on-write) as the scale answer. |
| N4 | Security | Passwords hashed behind a `PasswordHasher` abstraction; authorization enforced centrally. |
| N5 | Persistence-agnostic | In-memory repositories behind interfaces so a DB can be swapped in (DIP). |

### Assumptions (locked)

- Single-process, in-memory, thread-safe design (standard LLD convention).
- **Pull-based** newsfeed (computed on read).
- Flat comments (design hook left for nesting).
- A **declined** request is kept as a record with status `DECLINED`; the sender may re-send (a new request object).
- Mention *parsing* is out of scope; the `MENTION` notification type is modeled so it is pluggable.
- `SocialNetworkService` is the facade / single API surface. In Spring Boot this would simply be a `@Service` (container-managed singleton) instead of a hand-rolled Singleton.

---

## Step 2 — Entities & Relationships

### Core Entities and Enums

| Entity | Responsibility |
|--------|----------------|
| `User` | Identity (id, email, password hash) + owns a `Profile`, a friend set, and authored post ids. |
| `Profile` | Display data: picture URL, bio, interests, profile-level `PrivacyLevel`. |
| `FriendRequest` | A request from sender → receiver with a state machine: `PENDING → ACCEPTED \| DECLINED`. |
| `Post` | Author, text, attachments, timestamp, `PrivacyLevel`, likes (user-id set), comments. |
| `MediaAttachment` | Url + `MediaType` (IMAGE / VIDEO). Kept as a value object so new types don't change `Post`. |
| `Comment` | Author, post id, text, timestamp. |
| `Notification` | Recipient, `NotificationType`, message, timestamp, read flag. |
| `SocialNetworkService` | Facade: orchestrates use cases, enforces authorization, publishes events. |

| Enum | Values |
|------|--------|
| `FriendRequestStatus` | `PENDING`, `ACCEPTED`, `DECLINED` |
| `PrivacyLevel` | `PUBLIC`, `FRIENDS_ONLY`, `PRIVATE` |
| `NotificationType` | `FRIEND_REQUEST`, `FRIEND_REQUEST_ACCEPTED`, `LIKE`, `COMMENT`, `MENTION` |
| `MediaType` | `IMAGE`, `VIDEO` |

### Relationships (with type and rationale)

| Relationship | Type | Why |
|--------------|------|-----|
| `User` — `Profile` | **Composition (1:1)** | A profile cannot exist without its user; same lifecycle. Created inside the `User` constructor, never shared. |
| `User` — `User` (friends) | **Association (M:N, reflexive)** | Friends exist independently of each other; stored as a set of user **ids**, not object references, to avoid a tangled object graph and to keep serialization/persistence simple. |
| `User` — `Post` | **Aggregation (1:N)** | A post is authored by a user, but lives in the `PostRepository`; deleting the in-memory `User` object should not be what destroys posts (the service does that explicitly). The user holds post *ids*. |
| `Post` — `Comment` | **Composition (1:N)** | A comment is meaningless without its post; deleting a post deletes its comments. |
| `Post` — `MediaAttachment` | **Composition (1:N)** | Attachments are value objects owned by the post. |
| `Post` — likes | **Association (M:N via user ids)** | A like is just membership of a user id in the post's like-set; modeling a `Like` class is over-engineering unless we need like-timestamps. |
| `FriendRequest` — `User` | **Association (2 users)** | Holds sender/receiver ids; independent lifecycle (kept after decline). |
| `SocialNetworkService` — repositories | **Dependency / Aggregation** | Service depends on repository **interfaces** (DIP); concrete `InMemory*Repository` injected. |
| `NotificationService` — `NotificationObserver` | **Association (Observer)** | Publishers don't know concrete subscribers; channels are pluggable. |

> **Common over-modeling traps here:** a `Like` entity class (a `Set<String>` of user ids is enough), a `Friendship` entity (the adjacency set on each user suffices at LLD scope — a `Friendship` table is a *persistence* concern), and a `Newsfeed` entity (feed is *computed*, not stored, in the pull model).

---

## Step 3 — Class Design (UML, text form)

```text
<<enum>> FriendRequestStatus { PENDING, ACCEPTED, DECLINED }
<<enum>> PrivacyLevel        { PUBLIC, FRIENDS_ONLY, PRIVATE }
<<enum>> NotificationType    { FRIEND_REQUEST, FRIEND_REQUEST_ACCEPTED, LIKE, COMMENT, MENTION }
<<enum>> MediaType           { IMAGE, VIDEO }

+----------------------------+        1     1 +---------------------------+
| User                       |◆---------------| Profile                   |
+----------------------------+   composition  +---------------------------+
| - id: String               |                | - pictureUrl: String      |
| - name: String             |                | - bio: String             |
| - email: String            |                | - interests: List<String> |
| - passwordHash: String     |                | - visibility: PrivacyLevel|
| - profile: Profile         |                +---------------------------+
| - friendIds: Set<String>   |  (concurrent set)
| - postIds: Set<String>     |
+----------------------------+
| + isFriendsWith(id): bool  |
+----------------------------+
        ▲ ids only (association M:N reflexive — friends)

+----------------------------+ 1            * +---------------------------+
| Post                       |◆---------------| Comment                   |
+----------------------------+   composition  +---------------------------+
| - id: String               |                | - id, postId, authorId    |
| - authorId: String         |                | - text: String            |
| - text: String             |                | - createdAt: Instant      |
| - attachments:             |                +---------------------------+
|     List<MediaAttachment>  |◆── composition ── MediaAttachment(url, MediaType)
| - createdAt: Instant       |
| - privacy: PrivacyLevel    |
| - likedBy: Set<String>     |  (concurrent set; M:N association to users)
| - comments: List<Comment>  |  (concurrent list)
+----------------------------+
| + like(userId): boolean    |   // returns false if already liked (idempotent)
| + unlike(userId): boolean  |
+----------------------------+

+-----------------------------+
| FriendRequest               |
+-----------------------------+
| - id, senderId, receiverId  |
| - status: AtomicReference   |
|       <FriendRequestStatus> |
+-----------------------------+
| + accept(): boolean         |   // CAS PENDING→ACCEPTED
| + decline(): boolean        |   // CAS PENDING→DECLINED
+-----------------------------+

<<interface>> Repository<T, ID>      { save, findById, delete, findAll }
   ▲ implements
InMemoryUserRepository / InMemoryPostRepository / InMemoryFriendRequestRepository
   (each backed by ConcurrentHashMap)

<<interface>> PrivacyPolicy                      <<Strategy>>
   + canView(viewer: User, owner: User): boolean
   ▲
PublicPolicy | FriendsOnlyPolicy | PrivatePolicy
   (selected from PrivacyLevel via PrivacyPolicyFactory)

<<interface>> NotificationObserver               <<Observer>>
   + onNotify(n: Notification)
   ▲
InAppNotificationObserver (stores per-user inbox) | (future: EmailObserver, PushObserver)

+------------------------------------------+
| NotificationService  (publisher)         |
+------------------------------------------+
| - observers: CopyOnWriteArrayList        |
| + register(NotificationObserver)         |
| + publish(Notification)                  |
+------------------------------------------+

+----------------------------------------------------------+
| SocialNetworkService            <<Singleton / Facade>>    |
+----------------------------------------------------------+
| - users: Repository<User>                                 |
| - posts: Repository<Post>                                 |
| - requests: Repository<FriendRequest>                     |
| - sessions: ConcurrentHashMap<token, userId>              |
| - notifier: NotificationService                           |
| - hasher: PasswordHasher                                  |
+----------------------------------------------------------+
| + register(name,email,pwd): User                          |
| + login(email,pwd): String token   + logout(token)        |
| + updateProfile(token, changes)                           |
| + sendFriendRequest(token, toUserId): FriendRequest       |
| + respondToFriendRequest(token, reqId, accept: boolean)   |
| + createPost(token, text, attachments, privacy): Post     |
| + deletePost(token, postId)                               |
| + getNewsFeed(token, limit): List<Post>                   |
| + likePost(token, postId)   + commentOnPost(...)          |
| + getNotifications(token): List<Notification>             |
+----------------------------------------------------------+
```

### Design-concept mapping

**SOLID**

- **S — Single Responsibility:** `Post` holds post state and its own invariants (idempotent like); `NotificationService` only dispatches; repositories only store; the facade only orchestrates + authorizes. Password hashing is isolated in `PasswordHasher`.
- **O — Open/Closed:** new notification channels = new `NotificationObserver` implementations; new privacy levels = new `PrivacyPolicy` strategy; new media types = enum value + attachment, no `Post` change.
- **L — Liskov:** every `PrivacyPolicy` and `NotificationObserver` is substitutable; callers depend only on the interface contracts.
- **I — Interface Segregation:** observers implement one tiny `onNotify`; we don't force a fat "SocialNetworkListener" with friend/post/like methods on every subscriber.
- **D — Dependency Inversion:** `SocialNetworkService` depends on `Repository`, `PrivacyPolicy`, `PasswordHasher`, `NotificationObserver` **interfaces**. In Spring Boot these are constructor-injected beans; here we inject manually in the constructor — same principle, no framework.

**Patterns and exactly why they fit**

| Pattern | Where | Why it fits *here* |
|---------|-------|--------------------|
| **Observer** | `NotificationService` → `NotificationObserver` | Many event sources (like, comment, friend request) must fan out to an open-ended set of channels in "real time" without the post/friend logic knowing who listens. Decouples publishers from subscribers — the textbook Observer motivation. |
| **Strategy** | `PrivacyPolicy` per `PrivacyLevel` | Visibility is a family of interchangeable algorithms chosen at runtime per post. A `switch` inside the feed code would violate OCP every time a level (`CUSTOM_LIST`, `FRIENDS_OF_FRIENDS`) is added. |
| **Singleton (Facade)** | `SocialNetworkService` | One coherent API surface holding the shared state; clients never touch repositories directly, so authorization cannot be bypassed. *Spring note:* prefer container-managed singleton scope over `getInstance()` in real systems — easier to test and no hidden global state. |
| **Factory (simple)** | `PrivacyPolicyFactory`, `NotificationFactory` | Centralizes mapping enum → strategy / event → message so creation logic isn't scattered. |
| **State (lightweight)** | `FriendRequest` status transitions | Only two transitions from PENDING, so a full State-pattern class hierarchy is over-engineering; an `AtomicReference` + CAS gives the same legality guarantee with less ceremony. Knowing *when not to* apply a pattern is itself interview signal. |
| **Repository** | `Repository<T, ID>` | Keeps the design persistence-agnostic (N5) and testable. |

**The two decisions an interviewer will probe**

1. **Pull vs push newsfeed.** We pull: merge friends' recent posts at read time — O(friends × recent posts), no storage blow-up, always consistent. Push (fan-out-on-write) precomputes per-user feeds for O(1) reads but pays write amplification (a celebrity with 5M followers triggers 5M inserts per post). Real systems hybridize: push for normal users, pull for celebrities. Know both and say *why* pull is right at this scope.
2. **Like-set vs Like entity, and friendship as adjacency sets vs a Friendship entity.** Both are deliberate under-modeling: we keep the smallest representation that satisfies the requirements and note the upgrade path (a `Like{userId, timestamp}` record if "liked at" is ever required; a `Friendship` table when persistence arrives).

---
## Step 4 — Implementation (Java 11+)

> Focus: the patterns, the state transitions, and the concurrency. Trivial getters and boilerplate are elided.

### Enums

```java
public enum FriendRequestStatus { PENDING, ACCEPTED, DECLINED }
public enum PrivacyLevel        { PUBLIC, FRIENDS_ONLY, PRIVATE }
public enum NotificationType    { FRIEND_REQUEST, FRIEND_REQUEST_ACCEPTED, LIKE, COMMENT, MENTION }
public enum MediaType           { IMAGE, VIDEO }
```

### Exceptions (domain-specific, unchecked)

```java
public class SocialNetworkException extends RuntimeException {
    public SocialNetworkException(String msg) { super(msg); }
}
public class AuthenticationException extends SocialNetworkException {
    public AuthenticationException(String msg) { super(msg); }
}
public class AuthorizationException  extends SocialNetworkException {
    public AuthorizationException(String msg) { super(msg); }
}
public class EntityNotFoundException extends SocialNetworkException {
    public EntityNotFoundException(String msg) { super(msg); }
}
public class DuplicateEmailException extends SocialNetworkException {
    public DuplicateEmailException(String msg) { super(msg); }
}
```

### User and Profile

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class User {
    private final String id;
    private final String name;
    private final String email;
    private volatile String passwordHash;          // volatile: password change visible to all threads
    private final Profile profile;                 // composition: created here, dies with the user

    // Concurrent sets: liked/feed/friend operations hit these from many threads.
    private final Set<String> friendIds = ConcurrentHashMap.newKeySet();
    private final Set<String> postIds   = ConcurrentHashMap.newKeySet();

    public User(String name, String email, String passwordHash) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.profile = new Profile();
    }

    public boolean isFriendsWith(String otherUserId) { return friendIds.contains(otherUserId); }

    public Set<String> getFriendIds() { return Collections.unmodifiableSet(friendIds); } // no rep exposure
    Set<String> friendIdsInternal()   { return friendIds; }   // package-private: only the service mutates
    Set<String> postIdsInternal()     { return postIds; }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Profile getProfile() { return profile; }
    public String getName() { return name; }
}

public class Profile {
    private volatile String pictureUrl = "";
    private volatile String bio = "";
    private volatile PrivacyLevel visibility = PrivacyLevel.PUBLIC;
    private final List<String> interests = new java.util.concurrent.CopyOnWriteArrayList<>();
    // setters omitted; each field volatile so a profile update is immediately visible to readers
}
```

### Post, Comment, MediaAttachment

```java
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class MediaAttachment {                 // immutable value object
    private final String url;
    private final MediaType type;
    public MediaAttachment(String url, MediaType type) { this.url = url; this.type = type; }
}

public class Comment {
    private final String id = UUID.randomUUID().toString();
    private final String postId, authorId, text;
    private final Instant createdAt = Instant.now();
    public Comment(String postId, String authorId, String text) {
        this.postId = postId; this.authorId = authorId; this.text = text;
    }
}

public class Post {
    private final String id = UUID.randomUUID().toString();
    private final String authorId;
    private final String text;
    private final List<MediaAttachment> attachments;       // immutable after construction
    private final Instant createdAt = Instant.now();
    private volatile PrivacyLevel privacy;

    // Like-set: membership IS the like. Idempotency falls out of Set semantics.
    private final Set<String> likedBy = ConcurrentHashMap.newKeySet();
    private final List<Comment> comments = new CopyOnWriteArrayList<>();

    public Post(String authorId, String text, List<MediaAttachment> attachments, PrivacyLevel privacy) {
        this.authorId = authorId;
        this.text = text;
        this.attachments = List.copyOf(attachments == null ? List.of() : attachments);
        this.privacy = privacy;
    }

    /** @return true only for the FIRST like by this user — drives "notify once". Atomic via Set.add. */
    public boolean like(String userId)   { return likedBy.add(userId); }
    public boolean unlike(String userId) { return likedBy.remove(userId); }

    public void addComment(Comment c)    { comments.add(c); }

    public String getId() { return id; }
    public String getAuthorId() { return authorId; }
    public Instant getCreatedAt() { return createdAt; }
    public PrivacyLevel getPrivacy() { return privacy; }
    public int getLikeCount() { return likedBy.size(); }
    public List<Comment> getComments() { return Collections.unmodifiableList(comments); }
}
```

### FriendRequest — CAS state transition

```java
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class FriendRequest {
    private final String id = UUID.randomUUID().toString();
    private final String senderId, receiverId;
    private final AtomicReference<FriendRequestStatus> status =
            new AtomicReference<>(FriendRequestStatus.PENDING);

    public FriendRequest(String senderId, String receiverId) {
        this.senderId = senderId; this.receiverId = receiverId;
    }

    /**
     * Compare-and-set guarantees exactly one transition out of PENDING even if
     * accept() and decline() race (e.g. user taps both buttons on two devices).
     * No lock needed: the whole critical section is one atomic word.
     */
    public boolean accept()  { return status.compareAndSet(FriendRequestStatus.PENDING, FriendRequestStatus.ACCEPTED); }
    public boolean decline() { return status.compareAndSet(FriendRequestStatus.PENDING, FriendRequestStatus.DECLINED); }

    public FriendRequestStatus getStatus() { return status.get(); }
    public String getId() { return id; }
    public String getSenderId() { return senderId; }
    public String getReceiverId() { return receiverId; }
}
```

### Strategy — PrivacyPolicy

```java
public interface PrivacyPolicy {                       // <<Strategy>>
    boolean canView(User viewer, User owner);
}

class PublicPolicy implements PrivacyPolicy {
    public boolean canView(User v, User o) { return true; }
}
class FriendsOnlyPolicy implements PrivacyPolicy {
    public boolean canView(User v, User o) {
        return v.getId().equals(o.getId()) || o.isFriendsWith(v.getId());
    }
}
class PrivatePolicy implements PrivacyPolicy {
    public boolean canView(User v, User o) { return v.getId().equals(o.getId()); }
}

public final class PrivacyPolicyFactory {              // <<Factory>> enum -> strategy, one place
    private static final java.util.Map<PrivacyLevel, PrivacyPolicy> POLICIES = java.util.Map.of(
        PrivacyLevel.PUBLIC,       new PublicPolicy(),
        PrivacyLevel.FRIENDS_ONLY, new FriendsOnlyPolicy(),
        PrivacyLevel.PRIVATE,      new PrivatePolicy());
    public static PrivacyPolicy forLevel(PrivacyLevel level) { return POLICIES.get(level); }
    private PrivacyPolicyFactory() {}
}
```

### Observer — Notifications

```java
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class Notification {
    private final String id = UUID.randomUUID().toString();
    private final String recipientId;
    private final NotificationType type;
    private final String message;
    private final Instant createdAt = Instant.now();
    private volatile boolean read = false;
    public Notification(String recipientId, NotificationType type, String message) {
        this.recipientId = recipientId; this.type = type; this.message = message;
    }
    public String getRecipientId() { return recipientId; }
    public String getMessage() { return message; }
}

public interface NotificationObserver {               // <<Observer>> — one tiny method (ISP)
    void onNotify(Notification notification);
}

/** Default channel: per-user in-app inbox. Email/Push = new classes, zero core changes (OCP). */
public class InAppNotificationObserver implements NotificationObserver {
    private final ConcurrentHashMap<String, Queue<Notification>> inboxes = new ConcurrentHashMap<>();

    @Override
    public void onNotify(Notification n) {
        inboxes.computeIfAbsent(n.getRecipientId(), k -> new ConcurrentLinkedQueue<>()).add(n);
    }
    public List<Notification> inboxOf(String userId) {
        return new ArrayList<>(inboxes.getOrDefault(userId, new ConcurrentLinkedQueue<>()));
    }
}

public class NotificationService {                    // publisher
    // CopyOnWriteArrayList: observer list is read on EVERY publish, mutated almost never — ideal fit.
    private final List<NotificationObserver> observers = new CopyOnWriteArrayList<>();

    public void register(NotificationObserver o) { observers.add(o); }

    public void publish(Notification n) {
        for (NotificationObserver o : observers) {
            try { o.onNotify(n); }
            catch (Exception e) { /* one bad channel must not break the others or the caller */ }
        }
        // Scale note: make this async (ExecutorService / message queue) so a slow
        // email provider never blocks the like() request path.
    }
}
```

### Repository (DIP) and PasswordHasher

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public interface Repository<T, ID> {
    T save(T entity);
    Optional<T> findById(ID id);
    void deleteById(ID id);
    Collection<T> findAll();
}

public class InMemoryRepository<T> implements Repository<T, String> {
    protected final ConcurrentHashMap<String, T> store = new ConcurrentHashMap<>();
    private final java.util.function.Function<T, String> idExtractor;
    public InMemoryRepository(java.util.function.Function<T, String> idExtractor) {
        this.idExtractor = idExtractor;
    }
    public T save(T e)                    { store.put(idExtractor.apply(e), e); return e; }
    public Optional<T> findById(String i) { return Optional.ofNullable(store.get(i)); }
    public void deleteById(String i)      { store.remove(i); }
    public Collection<T> findAll()        { return store.values(); }
}

public interface PasswordHasher {          // bcrypt/argon2 in production; design depends on the interface
    String hash(String raw);
    boolean matches(String raw, String hash);
}
```

### Facade — SocialNetworkService

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SocialNetworkService {

    private static volatile SocialNetworkService instance;   // Singleton (interview convention)

    private final Repository<User, String> users;
    private final Repository<Post, String> posts;
    private final Repository<FriendRequest, String> requests;
    private final ConcurrentHashMap<String, String> emailIndex = new ConcurrentHashMap<>(); // email -> userId
    private final ConcurrentHashMap<String, String> sessions   = new ConcurrentHashMap<>(); // token -> userId
    private final NotificationService notifier;
    private final PasswordHasher hasher;

    private SocialNetworkService(NotificationService notifier, PasswordHasher hasher) {
        this.users    = new InMemoryRepository<>(User::getId);
        this.posts    = new InMemoryRepository<>(Post::getId);
        this.requests = new InMemoryRepository<>(FriendRequest::getId);
        this.notifier = notifier;
        this.hasher   = hasher;
    }

    /** Double-checked locking; `instance` must be volatile to forbid reordering of the publish. */
    public static SocialNetworkService getInstance(NotificationService n, PasswordHasher h) {
        if (instance == null) {
            synchronized (SocialNetworkService.class) {
                if (instance == null) instance = new SocialNetworkService(n, h);
            }
        }
        return instance;
    }

    // ---------- F1: accounts ----------

    public User register(String name, String email, String rawPassword) {
        validateNotBlank(name, "name"); validateNotBlank(email, "email");
        if (rawPassword == null || rawPassword.length() < 8)
            throw new SocialNetworkException("Password must be at least 8 characters");

        User user = new User(name, email, hasher.hash(rawPassword));
        // putIfAbsent = atomic uniqueness check + claim. A check-then-put would race:
        // two registrations with the same email could both pass the check.
        if (emailIndex.putIfAbsent(email.toLowerCase(), user.getId()) != null)
            throw new DuplicateEmailException("Email already registered: " + email);
        return users.save(user);
    }

    public String login(String email, String rawPassword) {
        String userId = emailIndex.get(email == null ? "" : email.toLowerCase());
        User user = (userId == null) ? null : users.findById(userId).orElse(null);
        // Same generic error for "no such user" and "wrong password" — don't leak which emails exist.
        if (user == null || !hasher.matches(rawPassword, user.getPasswordHash()))
            throw new AuthenticationException("Invalid credentials");
        String token = UUID.randomUUID().toString();
        sessions.put(token, user.getId());
        return token;
    }

    public void logout(String token) { sessions.remove(token); }

    private User requireSession(String token) {
        String userId = sessions.get(token);
        if (userId == null) throw new AuthenticationException("Invalid or expired session");
        return users.findById(userId)
                    .orElseThrow(() -> new AuthenticationException("Account no longer exists"));
    }

    // ---------- F3: friendship ----------

    public FriendRequest sendFriendRequest(String token, String toUserId) {
        User sender = requireSession(token);
        User receiver = users.findById(toUserId)
                .orElseThrow(() -> new EntityNotFoundException("No such user: " + toUserId));

        if (sender.getId().equals(toUserId))
            throw new SocialNetworkException("Cannot friend yourself");
        if (sender.isFriendsWith(toUserId))
            throw new SocialNetworkException("Already friends");
        if (hasPendingBetween(sender.getId(), toUserId))
            throw new SocialNetworkException("A pending request already exists");

        FriendRequest req = requests.save(new FriendRequest(sender.getId(), toUserId));
        notifier.publish(new Notification(toUserId, NotificationType.FRIEND_REQUEST,
                sender.getName() + " sent you a friend request"));
        return req;
    }

    public void respondToFriendRequest(String token, String requestId, boolean accept) {
        User responder = requireSession(token);
        FriendRequest req = requests.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("No such request"));

        // Authorization BEFORE state change: only the receiver may respond.
        if (!req.getReceiverId().equals(responder.getId()))
            throw new AuthorizationException("Only the recipient can respond to this request");

        if (accept) {
            // CAS first: at most one thread wins, so the friendship edges are added exactly once.
            if (!req.accept())
                throw new SocialNetworkException("Request is not pending");
            User sender = users.findById(req.getSenderId())
                    .orElseThrow(() -> new EntityNotFoundException("Sender no longer exists"));
            // Two set-adds, not one atomic op — see Step 5 for why this is acceptable
            // (idempotent adds, no invariant a reader can exploit between them).
            sender.friendIdsInternal().add(responder.getId());
            responder.friendIdsInternal().add(sender.getId());
            notifier.publish(new Notification(sender.getId(), NotificationType.FRIEND_REQUEST_ACCEPTED,
                    responder.getName() + " accepted your friend request"));
        } else {
            if (!req.decline())
                throw new SocialNetworkException("Request is not pending");
        }
    }

    private boolean hasPendingBetween(String a, String b) {
        return requests.findAll().stream().anyMatch(r ->
                r.getStatus() == FriendRequestStatus.PENDING &&
                ((r.getSenderId().equals(a) && r.getReceiverId().equals(b)) ||
                 (r.getSenderId().equals(b) && r.getReceiverId().equals(a))));
        // O(n) scan — fine in-memory; with a DB this is an indexed lookup.
    }

    // ---------- F4/F5: posts and feed ----------

    public Post createPost(String token, String text, List<MediaAttachment> attachments,
                           PrivacyLevel privacy) {
        User author = requireSession(token);
        boolean hasMedia = attachments != null && !attachments.isEmpty();
        if ((text == null || text.isBlank()) && !hasMedia)
            throw new SocialNetworkException("Post must contain text or media");

        Post post = new Post(author.getId(), text == null ? "" : text, attachments,
                             privacy == null ? PrivacyLevel.FRIENDS_ONLY : privacy); // safe default
        posts.save(post);
        author.postIdsInternal().add(post.getId());
        return post;
    }

    public void deletePost(String token, String postId) {
        User caller = requireSession(token);
        Post post = posts.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("No such post"));
        if (!post.getAuthorId().equals(caller.getId()))
            throw new AuthorizationException("Only the author can delete a post");
        posts.deleteById(postId);                       // comments die with it (composition)
        caller.postIdsInternal().remove(postId);
    }

    /** Pull model: merge own + friends' posts, privacy-filter, sort desc, cap. */
    public List<Post> getNewsFeed(String token, int limit) {
        User viewer = requireSession(token);

        Set<String> authorIds = new HashSet<>(viewer.getFriendIds());
        authorIds.add(viewer.getId());

        return posts.findAll().stream()
                .filter(p -> authorIds.contains(p.getAuthorId()))
                .filter(p -> canView(viewer, p))                       // Strategy applied per post
                .sorted(Comparator.comparing(Post::getCreatedAt).reversed())
                .limit(Math.max(0, limit))
                .collect(Collectors.toList());
        // Scale note: replace findAll() with per-author indexed lookups; replace
        // full sort with a k-way merge of per-friend sorted lists (each already
        // reverse-chronological) — O(k log f) instead of O(n log n).
    }

    private boolean canView(User viewer, Post post) {
        User owner = users.findById(post.getAuthorId()).orElse(null);
        if (owner == null) return false;
        return PrivacyPolicyFactory.forLevel(post.getPrivacy()).canView(viewer, owner);
    }

    // ---------- F6: engagement ----------

    public void likePost(String token, String postId) {
        User liker = requireSession(token);
        Post post = visiblePostOrThrow(liker, postId);   // can't like what you can't see

        // Set.add is atomic AND tells us if this was the first like:
        // notification fires exactly once even under a like/unlike/like storm.
        if (post.like(liker.getId()) && !post.getAuthorId().equals(liker.getId())) {
            notifier.publish(new Notification(post.getAuthorId(), NotificationType.LIKE,
                    liker.getName() + " liked your post"));
        }
    }

    public Comment commentOnPost(String token, String postId, String text) {
        User commenter = requireSession(token);
        validateNotBlank(text, "comment text");
        Post post = visiblePostOrThrow(commenter, postId);

        Comment comment = new Comment(postId, commenter.getId(), text);
        post.addComment(comment);
        if (!post.getAuthorId().equals(commenter.getId())) {
            notifier.publish(new Notification(post.getAuthorId(), NotificationType.COMMENT,
                    commenter.getName() + " commented on your post"));
        }
        return comment;
    }

    private Post visiblePostOrThrow(User viewer, String postId) {
        Post post = posts.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("No such post"));
        if (!canView(viewer, post))
            // Deliberately the SAME exception as not-found in a real API (404, not 403),
            // so private post ids are not discoverable by probing.
            throw new AuthorizationException("Post not visible to you");
        return post;
    }

    private static void validateNotBlank(String s, String field) {
        if (s == null || s.isBlank())
            throw new SocialNetworkException(field + " must not be blank");
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Exception strategy

- One **unchecked** domain root (`SocialNetworkException`) with precise subclasses: `AuthenticationException`, `AuthorizationException`, `EntityNotFoundException`, `DuplicateEmailException`. Unchecked because callers can't meaningfully recover mid-call; a web layer maps them to 401/403/404/409. (*Spring note:* this maps 1:1 to `@ControllerAdvice` + `@ExceptionHandler`.)
- **Fail fast, validate at the facade boundary** — entities can then assume valid input and stay simple.
- **Don't leak information through errors**: login returns the same "Invalid credentials" for unknown email vs wrong password; an invisible post should look identical to a nonexistent one.

### Edge cases covered

| Edge case | Handling |
|-----------|----------|
| Duplicate registration (same email, concurrent) | `emailIndex.putIfAbsent` — atomic check-and-claim, no TOCTOU race. |
| Self friend request / already friends / duplicate pending | Explicit guards in `sendFriendRequest`. |
| Accept and decline racing (two devices) | CAS on `AtomicReference` — exactly one transition wins; the loser gets "not pending". |
| Wrong user responding to a request | Authorization check before any state change. |
| Double-like / like-unlike-like storm | `Set.add` returns false on duplicates → idempotent, and notification fires only on the first true add. |
| Liking/commenting on an invisible post | `visiblePostOrThrow` — privacy enforced on the write path too, not just feed reads. |
| Empty post (no text, no media) | Rejected at creation. |
| Post deleted while someone comments | Comment goes to an unreachable object and is GC'd; acceptable last-write-wins at this scope. The fix, if required: a `deleted` flag checked inside a per-post lock. |
| Sender deleted before accept | `orElseThrow` on lookup → clean `EntityNotFoundException`, no NPE. |
| Negative / zero feed limit | Clamped with `Math.max(0, limit)`. |

### Concurrency analysis (the part interviewers grade hardest)

**Shared mutable state inventory**

| State | Primitive chosen | Why |
|-------|------------------|-----|
| User/post/request stores, email index, sessions | `ConcurrentHashMap` | Lock-striped, atomic `putIfAbsent`/`computeIfAbsent`; reads never block. |
| `Post.likedBy`, `User.friendIds` | `ConcurrentHashMap.newKeySet()` | Atomic add/remove/contains; `add`'s boolean doubles as the idempotency signal. |
| `Post.comments` | `CopyOnWriteArrayList` | Read-heavy (every feed render), append-rarely — COW's exact sweet spot. Would be wrong for a write-heavy list. |
| `FriendRequest.status` | `AtomicReference` + CAS | Single-word state machine; lock-free, no deadlock possible. |
| Observer list | `CopyOnWriteArrayList` | Iterated on every publish, mutated ~never; iteration is snapshot-safe. |
| `Profile` fields, `Post.privacy` | `volatile` | Single-field visibility; no compound invariant → no lock needed. |
| Singleton `instance` | `volatile` + double-checked locking | Without volatile, another thread can observe a half-constructed instance due to reordering. |

**Critical sections and how each hazard is argued away**

1. **Race: concurrent registration with the same email.** Eliminated by making check-and-insert a single atomic `putIfAbsent`. Any "if absent then put" written as two steps is the classic TOCTOU bug.
2. **Race: accept vs decline vs duplicate accept.** The *entire* legality decision is one CAS. No lock ⇒ no deadlock; CAS either succeeds or fails immediately ⇒ no livelock; one winner by hardware guarantee ⇒ no lost update.
3. **The honest weak spot — adding the two friendship edges.** `sender.add(receiver)` and `receiver.add(sender)` are two separate atomic ops, not one transaction. A reader between them sees a one-directional friendship for a few nanoseconds. Why this is acceptable: (a) the CAS gate guarantees the block runs at most once, so the end state is always correct; (b) both ops are idempotent set-adds that cannot fail midway in-memory; (c) the transient asymmetry only makes a `FRIENDS_ONLY` check momentarily conservative (deny), never permissive — it fails *safe*. If the interviewer insists on atomicity: lock both users in a **global order** (e.g., ascending userId) — `synchronized(min) { synchronized(max) { ... } }` — ordered acquisition is the standard deadlock-prevention argument; or, with a DB, wrap both inserts in a transaction.
4. **Feed read racing post writes.** `ConcurrentHashMap.values()` is weakly consistent: the stream sees a moment-in-time-ish snapshot, never throws `ConcurrentModificationException`, may miss a post created mid-iteration. For a newsfeed, eventual visibility within milliseconds is the right trade — strict snapshot isolation would mean a global read lock serializing every feed against every post.
5. **Notification fan-out.** Publish iterates a COW snapshot and swallows per-observer failures, so a misbehaving channel can neither corrupt the list nor abort sibling channels. Next step at scale: hand each `onNotify` to an `ExecutorService` so the user's like() call never waits on an email server.

**Deadlock/livelock summary:** the design holds **at most one lock at a time** (in fact, the hot paths are lock-free: CAS + concurrent collections). Deadlock requires hold-and-wait on multiple locks — structurally impossible here. The only multi-lock variant discussed (ordered two-user locking) imposes a global lock order, which breaks the circular-wait condition.

---

## Likely Interviewer Follow-ups (with model answers)

1. **"Scale the newsfeed 10x — what changes?"**
   Move from pull to a hybrid: fan-out-on-write into per-user feed caches (e.g., a Redis list per user) for normal accounts, but pull lazily for high-follower "celebrity" accounts to avoid 5M writes per celebrity post. Add per-author post indexes so feed assembly is a k-way merge of small sorted lists, not a scan.

2. **"Add a CUSTOM friend-list visibility level."**
   New `PrivacyLevel.CUSTOM`, new `CustomListPolicy implements PrivacyPolicy` holding an allowed-id set, register it in the factory. Zero changes to feed or post code — this is the Strategy payoff, say it explicitly.

3. **"Add email and push notifications."**
   Two new `NotificationObserver` implementations registered with `NotificationService`. Make `publish` async via an executor; at system scale, replace the in-process observer with a message broker (Kafka/SNS) — same Observer concept, network-distributed.

4. **"How do you prevent two threads from making the same pair friends twice?"**
   Three layers: pending-duplicate guard at send time; CAS on accept guarantees the edge-adding block executes once per request; and the edges themselves are set-adds, idempotent by construction — even a re-execution would be harmless.

5. **"Make comments nested (replies)."**
   Add `parentCommentId` (nullable) to `Comment` — a self-referencing association. Render as a tree by grouping on parent. Mention the Composite pattern if replies need uniform treatment of leaf/branch.

---

## Transferable Lessons

- **Observer for any "X happened, tell interested parties" requirement** — reappears in Stack Overflow (badges), Logging Framework, Pub-Sub, Stock Exchange, Elevator (floor events).
- **Strategy + Factory for enum-driven behavior families** — privacy here; pricing in Parking Lot; matching in Ride Sharing; splitting in Splitwise.
- **`Set.add`'s return value as a free atomic "first time?" check** — the cheapest idempotency trick in Java; reuse it anywhere you need act-once semantics (vote once, claim once).
- **CAS for single-word state machines, ordered locking for multi-object updates** — the two answers that resolve 90% of LLD concurrency probes.
- **Knowing when NOT to use a pattern** (no State hierarchy for a 2-transition request, no `Like` entity) is as much signal as using one.

## Suggested Next Problem

**Stack Overflow** — it reuses this exact skeleton (users, posts/answers, votes-as-sets, Observer for reputation/badges) and adds a reputation-rule engine, which is a clean Strategy/Chain-of-Responsibility exercise. Doing it immediately after this one cements the transfer.
