# LLD: Design Stack Overflow (Java)

Scope locked per Step 1: in-memory, single-JVM, thread-safe. Vote deltas: question upvote +5, answer upvote +10, downvote received −2, accepted answer +15. One vote per (user, post), no self-votes, reputation floored at 0.

---

## Step 1. Clarify Requirements

Functional Requirements

Post content — Users can post questions; users can post answers to questions; users can comment on both questions and answers.
Vote — Users can upvote/downvote questions and answers (not comments, per classic SO behavior).
Tags — Each question carries one or more tags.
Search — Find questions by keyword (in title/body), by tag, or by author.
Reputation — User reputation changes based on activity: e.g., your question gets upvoted (+5), your answer gets upvoted (+10), downvote received (−2), etc. Exact numbers are configurable — the mechanism is what matters.
Accept answer (implied, worth confirming) — The question author can mark one answer as accepted.

Non-Functional Requirements

Concurrency & consistency — Multiple users may vote on the same post or update reputation simultaneously; counts must not be lost or double-applied.
Extensibility — Adding a new votable/commentable content type (e.g., a "wiki post") or a new reputation rule should not require rewriting existing classes. This screams interface-based design.
Scale (LLD framing) — This is an in-memory, single-JVM design for the interview. We model the object structure correctly; we do not design sharded databases or search indexes (that's HLD). Search is linear scan or simple in-memory index.

## Step 2 — Entities, Enums, Relationships

### Core entities

| Entity                     | Role                                                                           |
| -------------------------- | ------------------------------------------------------------------------------ |
| `User`                     | Actor; owns reputation                                                         |
| `Post` (abstract)          | Shared behavior of Question & Answer: body, author, votes, comments            |
| `Question`                 | A Post with title, tags, answers, accepted answer                              |
| `Answer`                   | A Post belonging to one Question; can be accepted                              |
| `Comment`                  | Lightweight note on a Post; not votable                                        |
| `Vote`                     | Record of (voter, type) — the _fact_ of a vote, needed to enforce one-per-user |
| `Tag`                      | Value object; shared label across questions                                    |
| `StackOverflowService`     | Facade + in-memory repositories                                                |
| `SearchStrategy` (+ impls) | Pluggable search behavior                                                      |

### Enums & constants

- `VoteType { UPVOTE(+1), DOWNVOTE(-1) }` — carries its score delta.
- `ReputationPolicy` — constants for the rep deltas. Isolating these in one place means "change the upvote bonus" is a one-line change (a mini application of OCP).

### Relationships (and why each type)

| Relationship                        | Type                      | Why                                                                                                                         |
| ----------------------------------- | ------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Question → Answer                   | **Composition**           | An Answer cannot exist without its Question; delete the question, the answers are meaningless. Question owns the lifecycle. |
| Post → Comment                      | **Composition**           | Same lifecycle argument.                                                                                                    |
| Post → Vote                         | **Composition**           | A vote only exists _on_ a post.                                                                                             |
| Question → Tag                      | **Aggregation**           | Tags exist independently and are shared across many questions. Deleting a question does not delete `java` the tag.          |
| User → Question/Answer/Comment/Vote | **Association**           | The user _authored_ it, but doesn't own its lifecycle in our model (real SO keeps posts after account deletion).            |
| Service → SearchStrategy            | **Dependency / Strategy** | Service uses a strategy passed at call time; no ownership.                                                                  |
| Question/Answer → Post              | **Inheritance**           | True is-a; both are votable, commentable, authored content.                                                                 |

**The mistake most candidates make here:** modeling `Vote` as just an `int counter` on Post. That loses the information needed to enforce "one vote per user" and to support vote-changing later. The counter is _derived state_; the `Map<userId, Vote>` is the _source of truth_.

A second common over-model: giving `Comment` votes and reputation. We deliberately keep Comment dumb — it shows the interviewer you can resist symmetric-but-unnecessary design.

---

## Step 3 — Class Design

### Text UML

```
<<interface>> Votable                 <<interface>> Commentable
  + vote(User, VoteType)               + addComment(Comment)
  + getVoteScore(): int                + getComments(): List<Comment>

         ▲ implements both
         |
  Post (abstract) ----------------------------------------------
    - id: String                       - author: User  [association]
    - body: String                     - createdAt: Instant
    - votesByUser: ConcurrentHashMap<String, Vote>   [composition]
    - voteScore: AtomicInteger        (derived, cached)
    - comments: CopyOnWriteArrayList<Comment>        [composition]
    + vote(User, VoteType)            // template: shared dup/self checks
    # reputationDelta(VoteType): int  // hook: subclass-specific rep math
         ▲                       ▲
         |                       |
  Question                    Answer
    - title: String             - accepted: AtomicBoolean
    - tags: Set<Tag>  [aggregation]
    - answers: CopyOnWriteArrayList<Answer> [composition]
    - acceptedAnswer: AtomicReference<Answer>
    + addAnswer(Answer)
    + acceptAnswer(User actor, Answer)

  User                          Vote (immutable)        Tag (immutable value)
    - id, username                - voter: User           - name: String
    - reputation: AtomicInteger   - type: VoteType
    + updateReputation(int)

  <<interface>> SearchStrategy
    + search(Collection<Question>): List<Question>
       ▲ implements
  KeywordSearchStrategy | TagSearchStrategy | UserSearchStrategy

  StackOverflowService (Singleton / Facade)
    - users, questions, answers : ConcurrentHashMap<String, ...>
    + registerUser, postQuestion, postAnswer, addComment,
      vote, acceptAnswer, search(SearchStrategy)
```

### Concept mapping

**SOLID**

- **S — Single Responsibility:** `Post` manages voting/commenting mechanics; `User` manages reputation arithmetic; `SearchStrategy` impls each own exactly one matching rule; the Service only orchestrates and stores. Reputation _amounts_ live in `ReputationPolicy`, not scattered through entities.
- **O — Open/Closed:** new search dimension = new `SearchStrategy` class, zero edits to the service. New rep rule = edit one constants class. A new votable content type = extend `Post`.
- **L — Liskov:** anywhere a `Post` is expected (voting, commenting), a `Question` or `Answer` substitutes cleanly; subclasses only specialize the protected `reputationDelta` hook, never weaken the base contract.
- **I — Interface Segregation:** `Votable` and `Commentable` are separate. If we later add a `TagWiki` that's commentable but not votable, it implements only one interface instead of inheriting dead methods.
- **D — Dependency Inversion:** the service's `search()` depends on the `SearchStrategy` abstraction, not concrete searches.

**Design patterns and exactly why they fit**

1. **Strategy (search):** the _algorithm varies_ (keyword vs tag vs author) while the _context is fixed_ (filter a collection of questions). Encoding each as a class lets callers compose/choose at runtime and lets us add ranking strategies later without touching the service. The alternative — an enum + switch inside `search()` — violates OCP and grows into a god-method.
2. **Template Method (voting):** `Post.vote()` owns the invariant steps every vote must perform (reject self-vote, reject duplicate atomically, adjust score, award reputation) and delegates only the variable step — _how much reputation_ — to a protected hook. This guarantees subclasses can't accidentally skip the duplicate check.
3. **Singleton (service):** there must be exactly one in-memory store per JVM, accessed from many threads. _Spring aside:_ in a real app you'd never hand-roll this — a `@Service` bean is a container-managed singleton by default scope, which also makes it testable via injection. Say that sentence in an interview; it shows you know why hand-rolled Singletons are disliked.
4. **Observer (mentioned, not implemented):** reputation could be event-driven — posts publish `VoteEvent`s, a `ReputationManager` subscribes. That fully decouples scoring from content. We chose the direct hook for brevity, but you should _name_ this trade-off: direct = simpler and synchronous; Observer = decoupled, and the natural seam for adding badges/notifications later.

### The two decisions an interviewer will probe

1. **How do you enforce one-vote-per-user under concurrency?** Answer: the vote map is the source of truth and `putIfAbsent` makes check-and-insert a single atomic operation. (Detailed in Step 5.)
2. **Why an abstract `Post` instead of separate Question/Answer classes?** Answer: voting and commenting are genuinely identical behavior with shared invariants; duplicating them invites divergence (e.g., the self-vote check fixed in one class but not the other). Inheritance is justified by shared _behavior + invariants_, not just shared fields.

---

## Step 4 — Implementation (Java 11+)

> Foundational refresher — **encapsulation as invariant protection:** every field below is private and mutated only through methods that enforce the rules (no setter for `voteScore`, no public mutable collection escapes). Encapsulation isn't about getters/setters; it's about making illegal states unrepresentable from outside the class. That's why `getComments()` returns an unmodifiable view.

### Enums and policy

```java
public enum VoteType {
    UPVOTE(1), DOWNVOTE(-1);

    private final int delta;
    VoteType(int delta) { this.delta = delta; }
    public int delta() { return delta; }
}

/** Single place where "how much is an upvote worth" lives. */
public final class ReputationPolicy {
    public static final int QUESTION_UPVOTED = 5;
    public static final int ANSWER_UPVOTED   = 10;
    public static final int POST_DOWNVOTED   = -2;
    public static final int ANSWER_ACCEPTED  = 15;
    private ReputationPolicy() {}
}
```

### User

```java
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class User {
    private final String id;
    private final String username;
    private final AtomicInteger reputation = new AtomicInteger(0);

    public User(String username) {
        this.id = UUID.randomUUID().toString();
        this.username = username;
    }

    /**
     * Lock-free, race-safe, and enforces the floor-at-zero rule atomically.
     * A plain "rep += delta" would lose updates under concurrent votes.
     */
    public void updateReputation(int delta) {
        reputation.updateAndGet(current -> Math.max(0, current + delta));
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public int getReputation() { return reputation.get(); }
}
```

### Vote, Tag, Comment (immutable where possible)

```java
public final class Vote {
    private final User voter;
    private final VoteType type;

    public Vote(User voter, VoteType type) {
        this.voter = voter;
        this.type = type;
    }
    public User getVoter() { return voter; }
    public VoteType getType() { return type; }
}

import java.util.Objects;

public final class Tag {
    private final String name;

    public Tag(String name) { this.name = name.toLowerCase().trim(); }
    public String getName() { return name; }

    // Value semantics: two Tag("java") are the same tag.
    @Override public boolean equals(Object o) {
        return o instanceof Tag && ((Tag) o).name.equals(name);
    }
    @Override public int hashCode() { return Objects.hash(name); }
}

import java.time.Instant;
import java.util.UUID;

public final class Comment {
    private final String id = UUID.randomUUID().toString();
    private final User author;
    private final String text;
    private final Instant createdAt = Instant.now();

    public Comment(User author, String text) {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Comment text must not be empty");
        this.author = author;
        this.text = text;
    }
    public User getAuthor() { return author; }
    public String getText() { return text; }
}
```

### Interfaces and abstract Post (Template Method lives here)

```java
public interface Votable {
    void vote(User voter, VoteType type);
    int getVoteScore();
}

public interface Commentable {
    void addComment(Comment comment);
    java.util.List<Comment> getComments();
}
```

```java
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class Post implements Votable, Commentable {
    private final String id = UUID.randomUUID().toString();
    private final User author;
    private final String body;
    private final Instant createdAt = Instant.now();

    /** Source of truth for votes: who voted, and how. */
    private final Map<String, Vote> votesByUser = new ConcurrentHashMap<>();
    /** Derived, cached score so reads are O(1). */
    private final AtomicInteger voteScore = new AtomicInteger(0);

    private final List<Comment> comments = new CopyOnWriteArrayList<>();

    protected Post(User author, String body) {
        if (body == null || body.isBlank())
            throw new IllegalArgumentException("Post body must not be empty");
        this.author = Objects.requireNonNull(author);
        this.body = body;
    }

    /**
     * TEMPLATE METHOD: invariant steps are fixed here; only the
     * reputation amount varies by subclass (the protected hook).
     * Marked final so a subclass can't bypass the duplicate check.
     */
    @Override
    public final void vote(User voter, VoteType type) {
        if (voter.getId().equals(author.getId())) {
            throw new IllegalArgumentException("You cannot vote on your own post");
        }
        // ATOMIC check-and-insert: this single call is the entire defense
        // against double-voting under concurrency. A separate
        // containsKey() + put() would be a check-then-act race.
        Vote previous = votesByUser.putIfAbsent(voter.getId(), new Vote(voter, type));
        if (previous != null) {
            throw new IllegalStateException("User has already voted on this post");
        }
        voteScore.addAndGet(type.delta());
        author.updateReputation(reputationDelta(type));
    }

    /** Hook: how much reputation does this vote grant the author? */
    protected abstract int reputationDelta(VoteType type);

    @Override
    public void addComment(Comment comment) {
        comments.add(Objects.requireNonNull(comment));
    }

    @Override
    public List<Comment> getComments() {
        return Collections.unmodifiableList(comments);
    }

    @Override public int getVoteScore() { return voteScore.get(); }
    public String getId() { return id; }
    public User getAuthor() { return author; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
```

### Answer and Question

```java
import java.util.concurrent.atomic.AtomicBoolean;

public class Answer extends Post {
    private final Question question;            // back-reference
    private final AtomicBoolean accepted = new AtomicBoolean(false);

    public Answer(User author, String body, Question question) {
        super(author, body);
        this.question = question;
    }

    @Override
    protected int reputationDelta(VoteType type) {
        return type == VoteType.UPVOTE
                ? ReputationPolicy.ANSWER_UPVOTED
                : ReputationPolicy.POST_DOWNVOTED;
    }

    /** Package-private: only Question may flip this, via acceptAnswer(). */
    void markAccepted() { accepted.set(true); }

    public boolean isAccepted() { return accepted.get(); }
    public Question getQuestion() { return question; }
}
```

```java
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public class Question extends Post {
    private final String title;
    private final Set<Tag> tags;
    private final List<Answer> answers = new CopyOnWriteArrayList<>();
    private final AtomicReference<Answer> acceptedAnswer = new AtomicReference<>();

    public Question(User author, String title, String body, Set<Tag> tags) {
        super(author, body);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("Title must not be empty");
        this.title = title;
        this.tags = Set.copyOf(tags == null ? Set.of() : tags); // defensive, immutable
    }

    @Override
    protected int reputationDelta(VoteType type) {
        return type == VoteType.UPVOTE
                ? ReputationPolicy.QUESTION_UPVOTED
                : ReputationPolicy.POST_DOWNVOTED;
    }

    public void addAnswer(Answer answer) {
        if (!answer.getQuestion().getId().equals(this.getId()))
            throw new IllegalArgumentException("Answer belongs to a different question");
        answers.add(answer);
    }

    /**
     * Only the question author may accept; only one answer may ever be
     * accepted. compareAndSet(null, answer) makes "is there already an
     * accepted answer?" and "set it" one atomic step — two concurrent
     * accepts cannot both win.
     */
    public void acceptAnswer(User actor, Answer answer) {
        if (!actor.getId().equals(getAuthor().getId()))
            throw new IllegalStateException("Only the question author can accept an answer");
        if (!answers.contains(answer))
            throw new IllegalArgumentException("That answer does not belong to this question");
        if (answer.getAuthor().getId().equals(getAuthor().getId())) {
            // self-answers are allowed on real SO; accepting your own answer
            // grants no reputation there. We keep it simple: accept allowed, no rep.
            if (!acceptedAnswer.compareAndSet(null, answer))
                throw new IllegalStateException("An answer has already been accepted");
            answer.markAccepted();
            return;
        }
        if (!acceptedAnswer.compareAndSet(null, answer))
            throw new IllegalStateException("An answer has already been accepted");
        answer.markAccepted();
        answer.getAuthor().updateReputation(ReputationPolicy.ANSWER_ACCEPTED);
    }

    public String getTitle() { return title; }
    public Set<Tag> getTags() { return tags; }
    public List<Answer> getAnswers() { return Collections.unmodifiableList(answers); }
    public Optional<Answer> getAcceptedAnswer() { return Optional.ofNullable(acceptedAnswer.get()); }
}
```

### Search — Strategy pattern

```java
import java.util.Collection;
import java.util.List;

public interface SearchStrategy {
    List<Question> search(Collection<Question> questions);
}
```

```java
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class KeywordSearchStrategy implements SearchStrategy {
    private final String keyword;
    public KeywordSearchStrategy(String keyword) { this.keyword = keyword.toLowerCase(); }

    @Override
    public List<Question> search(Collection<Question> questions) {
        return questions.stream()
                .filter(q -> q.getTitle().toLowerCase().contains(keyword)
                          || q.getBody().toLowerCase().contains(keyword))
                .collect(Collectors.toList());
    }
}

public class TagSearchStrategy implements SearchStrategy {
    private final Tag tag;
    public TagSearchStrategy(String tagName) { this.tag = new Tag(tagName); }

    @Override
    public List<Question> search(Collection<Question> questions) {
        return questions.stream()
                .filter(q -> q.getTags().contains(tag))   // value equality on Tag
                .collect(Collectors.toList());
    }
}

public class UserSearchStrategy implements SearchStrategy {
    private final String userId;
    public UserSearchStrategy(User user) { this.userId = user.getId(); }

    @Override
    public List<Question> search(Collection<Question> questions) {
        return questions.stream()
                .filter(q -> q.getAuthor().getId().equals(userId))
                .collect(Collectors.toList());
    }
}
```

Strategies compose for free:

```java
// "questions tagged java that mention 'deadlock'"
List<Question> step1 = new TagSearchStrategy("java").search(all);
List<Question> result = new KeywordSearchStrategy("deadlock").search(step1);
```

### Service — Singleton facade

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class StackOverflowService {
    private static final StackOverflowService INSTANCE = new StackOverflowService();
    public static StackOverflowService getInstance() { return INSTANCE; }
    private StackOverflowService() {}
    // Spring note: in a real app this is a @Service bean — the container
    // gives you singleton scope + injectability, so never hand-roll there.

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Question> questions = new ConcurrentHashMap<>();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();

    public User registerUser(String username) {
        User user = new User(username);
        users.put(user.getId(), user);
        return user;
    }

    public Question postQuestion(User author, String title, String body, Set<String> tagNames) {
        Set<Tag> tags = tagNames.stream().map(Tag::new).collect(Collectors.toSet());
        Question q = new Question(requireRegistered(author), title, body, tags);
        questions.put(q.getId(), q);
        return q;
    }

    public Answer postAnswer(User author, String questionId, String body) {
        Question q = requireQuestion(questionId);
        Answer a = new Answer(requireRegistered(author), body, q);
        q.addAnswer(a);
        answers.put(a.getId(), a);
        return a;
    }

    public void voteQuestion(User voter, String questionId, VoteType type) {
        requireQuestion(questionId).vote(requireRegistered(voter), type);
    }

    public void voteAnswer(User voter, String answerId, VoteType type) {
        requireAnswer(answerId).vote(requireRegistered(voter), type);
    }

    public void commentOnQuestion(User author, String questionId, String text) {
        requireQuestion(questionId).addComment(new Comment(requireRegistered(author), text));
    }

    public void commentOnAnswer(User author, String answerId, String text) {
        requireAnswer(answerId).addComment(new Comment(requireRegistered(author), text));
    }

    public void acceptAnswer(User actor, String questionId, String answerId) {
        requireQuestion(questionId).acceptAnswer(requireRegistered(actor), requireAnswer(answerId));
    }

    /** Dependency Inversion: the service knows only the abstraction. */
    public List<Question> search(SearchStrategy strategy) {
        return strategy.search(questions.values());
    }

    // ---- guards ----
    private User requireRegistered(User u) {
        if (u == null || !users.containsKey(u.getId()))
            throw new IllegalArgumentException("Unknown user");
        return u;
    }
    private Question requireQuestion(String id) {
        Question q = questions.get(id);
        if (q == null) throw new NoSuchElementException("Question not found: " + id);
        return q;
    }
    private Answer requireAnswer(String id) {
        Answer a = answers.get(id);
        if (a == null) throw new NoSuchElementException("Answer not found: " + id);
        return a;
    }
}
```

### Demo

```java
public class StackOverflowDemo {
    public static void main(String[] args) {
        StackOverflowService so = StackOverflowService.getInstance();

        User alice = so.registerUser("alice");
        User bob   = so.registerUser("bob");
        User carol = so.registerUser("carol");

        Question q = so.postQuestion(alice,
                "How does ConcurrentHashMap putIfAbsent work?",
                "I need an atomic check-and-insert for a voting system...",
                java.util.Set.of("java", "concurrency"));

        Answer a = so.postAnswer(bob, q.getId(),
                "putIfAbsent is atomic: it inserts only if the key is absent and returns the prior value.");

        so.voteQuestion(bob,   q.getId(), VoteType.UPVOTE);   // alice +5
        so.voteAnswer(alice,   a.getId(), VoteType.UPVOTE);   // bob +10
        so.voteAnswer(carol,   a.getId(), VoteType.UPVOTE);   // bob +10
        so.acceptAnswer(alice, q.getId(), a.getId());         // bob +15

        so.commentOnAnswer(carol, a.getId(), "Great explanation!");

        System.out.println("alice rep = " + alice.getReputation()); // 5
        System.out.println("bob rep   = " + bob.getReputation());   // 35
        System.out.println("answer score = " + a.getVoteScore());   // 2
        System.out.println("tag search: " +
                so.search(new TagSearchStrategy("concurrency")).size()); // 1

        try { so.voteQuestion(alice, q.getId(), VoteType.UPVOTE); }
        catch (IllegalArgumentException e) { System.out.println("Blocked: " + e.getMessage()); }

        try { so.voteAnswer(carol, a.getId(), VoteType.DOWNVOTE); }
        catch (IllegalStateException e) { System.out.println("Blocked: " + e.getMessage()); }
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input & boundary conditions

| Case                                                                | Handling                                                                              |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| Blank title/body/comment                                            | `IllegalArgumentException` in constructors — invalid objects can never exist          |
| Vote on own post                                                    | `IllegalArgumentException` in `Post.vote()`                                           |
| Duplicate vote                                                      | `IllegalStateException` — caller distinguishes "bad request" from "conflicting state" |
| Accept by non-author / answer from another question / second accept | `IllegalStateException` / `IllegalArgumentException` in `acceptAnswer`                |
| Unknown user/question/answer IDs                                    | guard methods in service throw early with clear messages                              |
| Reputation below 0                                                  | `Math.max(0, ...)` inside the atomic update — the floor can't be raced past           |
| Search no matches                                                   | empty list, never null                                                                |

Design choice worth voicing: we use **unchecked exceptions** for rule violations. These are programming/state errors, not recoverable I/O conditions; checked exceptions here would pollute every signature. In a Spring API layer you'd translate them to 400/409 in a `@ControllerAdvice`.

### Concurrency analysis (the part interviewers grade hardest)

**Shared mutable state inventory:**

1. `Post.votesByUser` + `Post.voteScore`
2. `User.reputation`
3. `Question.answers`, `Post.comments`
4. `Question.acceptedAnswer` + `Answer.accepted`
5. Service repositories (`users`, `questions`, `answers`)

**Primitive chosen for each, and why:**

1. **Voting — `ConcurrentHashMap.putIfAbsent` (CAS-based check-and-act).** The hazard is the classic _check-then-act race_: thread A checks "bob hasn't voted", thread B checks the same, both insert → double vote. `putIfAbsent` collapses check+insert into one atomic map operation; exactly one thread gets `null` back and proceeds to mutate score/reputation. The losing thread throws. No lock, no contention bottleneck on hot posts.
2. **Score & reputation — `AtomicInteger`.** `addAndGet`/`updateAndGet` are lock-free CAS loops. A plain `int` with `score++` is a read-modify-write race (two +1s can yield +1). We don't need `synchronized` because each counter is a _single independent variable_ — atomics are sufficient and faster.
3. **Answer/comment lists — `CopyOnWriteArrayList`.** Read-heavy, append-only, iteration must never throw `ConcurrentModificationException`. COW gives lock-free reads at the cost of copying on write — the right trade when reads ≫ writes. (If answers were edited/removed frequently, I'd switch to a `synchronized` list or a `ConcurrentLinkedQueue` and say why.)
4. **Accepted answer — `AtomicReference.compareAndSet(null, answer)`.** Two threads racing to accept: CAS guarantees exactly one wins; the other sees `false` and throws. This is the same one-shot-claim idiom as the vote map, on a single reference instead of a map entry.
5. **Repositories — `ConcurrentHashMap`.** Safe concurrent get/put with no global lock.

**Is there a deadlock?** No thread ever holds one lock while acquiring another — in fact, no explicit locks exist; everything is a single atomic operation (CAS or one concurrent-collection call). Deadlock requires hold-and-wait on ≥2 locks; we have zero. Livelock: CAS retry loops in `AtomicInteger` are bounded in practice and each retry means _someone else_ made progress — the system as a whole always advances (lock-freedom).

**Known consistency gap to admit proactively (this earns points):** `vote()` performs three steps — insert vote, bump score, bump reputation. Each step is atomic, but the _triple_ is not one transaction. A reader between steps could see the vote recorded but the score not yet bumped. For an in-memory LLD this eventual-within-microseconds consistency is acceptable; if strict atomicity were required, I'd wrap the triple in a per-post `ReentrantLock` (coarser, simpler to reason about) or, in a real system, a DB transaction. Naming this trade-off unprompted is exactly what separates senior candidates.

---

## Interviewer Follow-ups (with model answers)

**1. "Let users change or retract their vote."**
The `Map<userId, Vote>` source-of-truth makes this tractable: `changeVote` does `votesByUser.replace(userId, oldVote, newVote)` (atomic CAS replace), then applies the score/rep _delta between old and new_ (e.g., down→up on an answer: score +2, rep +12). Retraction uses `remove(userId, oldVote)` and reverses the deltas. If we'd stored only a counter, this feature would be impossible — say that; it justifies the original modeling decision.

**2. "Add badges (e.g., 'first upvoted answer')."**
This is where the Observer pattern pays off: introduce a `PostEvent` (VOTED, ACCEPTED, COMMENTED) and an `EventPublisher` the posts notify. `ReputationManager` and `BadgeManager` become independent subscribers. Posts stop knowing about reputation at all — better SRP. In Spring: `ApplicationEventPublisher` + `@EventListener`.

**3. "What changes at 10x/1000x scale — multiple servers?"**
Atomics and `ConcurrentHashMap` only protect one JVM. Cross-server you move the invariants into the datastore: unique constraint on `(post_id, user_id)` in a votes table enforces one-vote-per-user; score becomes a DB atomic increment or a periodically reconciled counter; keyword search moves to an inverted index (Elasticsearch); reputation updates become async events on a queue so vote latency doesn't include rep math. The _class design barely changes_ — the synchronization strategy is what's swapped. That separation is why we kept policy/strategy isolated.

**4. "Why Strategy for search instead of one method with a filter parameter?"**
A `search(String keyword, String tag, User author)` method with nullable params grows combinatorially and every new dimension edits the method (OCP violation). Strategies are independently testable, composable (chain them), and adding `RecencyRankingStrategy` later touches nothing existing. Counter-trade-off to admit: for only 2–3 fixed criteria, Strategy is arguably over-engineering — Java predicates (`Predicate<Question>` composition) are a lighter modern alternative. Knowing both answers is the senior move.

**5. "Make `vote()` strictly atomic across vote+score+reputation."**
Per-post `ReentrantLock`: lock, do all three, unlock in `finally`. Locks are per-post so there's no global contention, and we never take two locks (the user reputation update is itself atomic and we never lock User), so no lock-ordering deadlock. Cost: voters on the _same_ hot post serialize. Mention `tryLock` with timeout as the defensive variant.

---

## Transferable Lessons

1. **"Store the fact, derive the count."** The Vote-map-plus-cached-counter shape reappears in Parking Lot (spot assignments), Movie Booking (seat locks), Splitwise (transactions vs balances). Whenever a requirement says "a user can do X _once_," your data model needs to remember _who_ did X, and `putIfAbsent`/`compareAndSet` is the thread-safe enforcement.
2. **One-shot claim via CAS** (`compareAndSet(null, winner)`) is the universal idiom for "exactly one of N concurrent actors succeeds" — accepted answers here, seat booking, locker assignment, leader claim later in the concurrency set.
3. **Template Method for invariants + hook for variance** keeps subclasses from forgetting safety checks — you'll reuse it for state-transition validation in Vending Machine / Elevator.
4. **Strategy whenever the verb is fixed but the algorithm varies** — search here, pricing in Parking Lot, splitting in Splitwise, matching in Ride Sharing.

## Next Problem

**Logging Framework** or **Vending Machine** is the natural next step in the repo's Medium track — Vending Machine especially, because it introduces the **State pattern**, which Stack Overflow didn't exercise and which interviewers love. Recommendation: **Vending Machine** next.
