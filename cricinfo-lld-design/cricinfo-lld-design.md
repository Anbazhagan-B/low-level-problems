# Low-Level Design: Cricket Information System (CricInfo)

> **Tier:** Hard | **Language:** Java 11+ | **Scope:** In-memory, interview-style LLD
>
> **The big idea of this problem:** Every Hard problem so far (Auction, Airline, Concert Ticket) was a
> *write-contention* system — many users racing to claim scarce resources, solved with per-resource
> locks and atomic check-and-set. CricInfo is the opposite: a **read-heavy, single-writer** system.
> One scorer writes ball-by-ball; thousands of users read concurrently. The right tool flips from
> *locking* to **immutable snapshots published atomically** (copy-on-write). Recognizing which regime
> you're in is the single most transferable skill this problem teaches.

---

## Step 1 — Requirements

### Functional Requirements

1. **Catalog management** — register teams, players, and venues; create matches with format
   (T20 / ODI / TEST), venue, teams, and scheduled start time.
2. **Match lifecycle** — matches move through `SCHEDULED → LIVE → COMPLETED` (or `ABANDONED`),
   with transitions guarded so balls can only be recorded on a `LIVE` match.
3. **Live scoring** — an authoritative scorer records ball-by-ball events (`Ball`): runs off the bat,
   extras (wide / no-ball / bye / leg-bye), wickets, and commentary text. All scorecard data is
   **derived** from this ball stream.
4. **Match detail view** — for any match: current score, full scorecard (batting lines, bowling
   lines, fall of wickets), ball-by-ball commentary, and derived stats (run rate, strike rates,
   economy rates).
5. **Schedules & results** — list upcoming matches and completed results, filterable by team and format.
6. **Search** — find matches, teams, and players by name keyword.
7. **Live subscriptions** — users can subscribe to a match and be pushed every update (Observer).

### Non-Functional Requirements

1. **Read-dominant concurrency** — one writer per match, unbounded concurrent readers. Readers must
   never observe a *torn* update (e.g., runs incremented but the wicket not yet applied).
2. **Point-in-time consistency** — any scorecard read reflects the state after some whole ball,
   never mid-application.
3. **Scalability** — readers must not serialize behind the writer or each other; the hot read path
   should be lock-free.
4. **Extensibility** — new formats, new stat types, new notification channels without modifying
   core classes (OCP via Strategy and Observer).
5. **Low latency** — updates propagate to subscribers promptly; notification must not block scoring.

### Assumptions Locked In

- Exactly **one writer (scorer) per match**; this is the realistic model and shapes the concurrency design.
- `Ball` is the immutable atomic unit of truth; everything else is derived (event-sourcing flavor).
- In-memory registries (`ConcurrentHashMap`), no persistence.
- Limited-overs rules implemented concretely; TEST handled by the same Strategy seam.
- Ball corrections (umpire review) treated as an edge case via replay, not a primary flow.
- Per-match statistics in scope; cross-match career aggregates discussed as an extension.

---

## Step 2 — Entities & Relationships

### Core Entities

| Entity | Responsibility | Mutability |
|---|---|---|
| `Player` | Identity + role of a cricketer | Immutable |
| `Team` | Named squad of players | Effectively immutable per match |
| `Venue` | Ground name + city | Immutable |
| `Match` | Lifecycle, teams, innings list, observer registry | Mutable (guarded) |
| `Innings` | One team's batting innings; owns the published snapshot | Mutable holder of immutable snapshots |
| `Ball` | One delivery: runs, extras, wicket, commentary | **Immutable** (Builder-built) |
| `Wicket` | Dismissal details (type, batter out, fielder) | Immutable |
| `InningsSnapshot` | Point-in-time derived state: totals + batting/bowling lines + ball log | **Immutable** |
| `BattingLine` / `BowlingLine` | One player's aggregated figures inside a snapshot | Immutable |
| `MatchUpdate` | Event delivered to observers | Immutable |
| `CricInfoService` | Singleton facade: registries, search, schedules, subscriptions | Thread-safe |

### Enums

- `MatchFormat { T20, ODI, TEST }`
- `MatchStatus { SCHEDULED, LIVE, COMPLETED, ABANDONED }`
- `ExtraType { NONE, WIDE, NO_BALL, BYE, LEG_BYE }`
- `WicketType { BOWLED, CAUGHT, LBW, RUN_OUT, STUMPED, HIT_WICKET, RETIRED }`
- `PlayerRole { BATTER, BOWLER, ALL_ROUNDER, WICKET_KEEPER }`

### Relationships (with type and rationale)

| Relationship | Type | Why |
|---|---|---|
| `Team` → `Player` (1..*) | **Aggregation** | Players exist independently of any team; a team groups them but doesn't own their lifecycle. |
| `Match` → `Team` (exactly 2) | **Association** | A match references teams; teams outlive matches. |
| `Match` → `Venue` | **Association** | Shared, independent entity. |
| `Match` → `Innings` (1..4) | **Composition** | An innings has no meaning outside its match; created and destroyed with it. |
| `Innings` → `Ball` (0..*) | **Composition** | Balls belong to exactly one innings; the ball log *is* the innings. |
| `Ball` → `Player` (striker, non-striker, bowler) | **Association** | References to independent players. |
| `Ball` → `Wicket` (0..1) | **Composition** | A wicket record only exists as part of its ball. |
| `Match` → `MatchObserver` (0..*) | **Dependency / Association** | Match notifies observers via interface; knows nothing concrete (DIP). |
| `Match` → `MatchFormatRules` | **Association (Strategy)** | Format-specific rules injected; match delegates "is innings over?" decisions. |
| `CricInfoService` → repositories | **Composition** | Service owns its registries. |

### Deliberate under-modeling (a pattern from your previous sessions)

- **No `Scorecard` entity class.** The scorecard is a *view* over `InningsSnapshot` — modeling it
  as a stored entity invites the classic bug of two sources of truth drifting apart. Derive, don't duplicate.
- **No `Commentary` entity.** Commentary text rides on each `Ball`; the commentary feed is just the
  ball log read in order.
- **No `Score` entity.** Runs/wickets/overs are fields of the snapshot, not an object with identity.
- **No `Over` entity.** Over boundaries are derivable from `legalBallCount / 6`; a class adds nothing
  but bookkeeping. (Mention in an interview that you *could* introduce it if per-over analytics became
  a first-class requirement — extensibility, not speculation.)

---

## Step 3 — UML Class Design

```
+----------------------+        +---------------------+
|   CricInfoService    |<>------| MatchRepository     |  (Singleton, holder idiom)
|----------------------|        | TeamRepository      |
| +createMatch(...)    |        | PlayerRepository    |  ConcurrentHashMap-backed
| +getMatch(id)        |        +---------------------+
| +searchMatches(q)    |
| +searchPlayers(q)    |
| +upcomingMatches()   |
| +results()           |
| +subscribe(id, obs)  |
+----------------------+
          |
          v 1..*
+----------------------------+          +--------------------------+
|          Match             |--------> | <<interface>>            |
|----------------------------|  rules   | MatchFormatRules         |
| -id: String                |          |--------------------------|
| -home, away: Team          |          | +inningsPerSide(): int   |
| -venue: Venue              |          | +maxOversPerInnings():int|
| -format: MatchFormat       |          | +isInningsComplete(s):bool|
| -status: volatile          |          +--------------------------+
|         MatchStatus        |                      ^
| -innings: List<Innings>    |          +-----------+-----------+
| -observers:                |          | LimitedOversRules     |
|   CopyOnWriteArrayList     |          | TestMatchRules        |
| -writerLock: Object        |          +-----------------------+
|----------------------------|
| +start() / +complete()     |          +--------------------------+
| +recordBall(Ball)          |--------> | <<interface>>            |
| +scoreboard():             | notifies | MatchObserver            |
|     List<InningsSnapshot>  |          |--------------------------|
+----------------------------+          | +onUpdate(MatchUpdate)   |
          | 1..4 (composition)          +--------------------------+
          v
+----------------------------+
|          Innings           |
|----------------------------|
| -battingTeam, bowlingTeam  |
| -snapshot: AtomicReference |   <<< the concurrency heart of the design
|        <InningsSnapshot>   |
|----------------------------|
| +apply(Ball): InningsSnapshot   (writer only)
| +current(): InningsSnapshot     (lock-free read)
+----------------------------+
          | publishes
          v
+----------------------------+       +-----------------+
|   InningsSnapshot (immut.) |<>-----| Ball (immutable)|----> Wicket (0..1, immutable)
|----------------------------|  log  |-----------------|
| -runs, -wickets            |       | -over, -ballNo  |
| -legalBalls                |       | -striker        |
| -batting: Map<Player,      |       | -bowler         |
|     BattingLine>           |       | -runsOffBat     |
| -bowling: Map<Player,      |       | -extraType,     |
|     BowlingLine>           |       |  extraRuns      |
| -balls: List<Ball>         |       | -commentary     |
|----------------------------|       | (Builder)       |
| +apply(Ball): new snapshot |       +-----------------+
| +runRate(): double         |
+----------------------------+
```

### Design Patterns and Exactly Why Each Fits

**1. Immutable Snapshot + Atomic Publish (copy-on-write)** — *the* decision of this problem.
The writer never mutates shared state in place. It takes the current `InningsSnapshot`, derives a
new one with the ball applied, and publishes it with a single `AtomicReference.set()`. Readers call
`get()` and receive a fully consistent, frozen view — no lock, no torn reads. This works *because*
there is one writer; the pattern is `CopyOnWriteArrayList`'s philosophy applied to domain state.

**2. Observer** — `MatchObserver` decouples score production from consumption. The match doesn't
know whether the subscriber is a UI session, a push-notification gateway, or a stats aggregator —
new channels are added without touching `Match` (OCP). Same role as notifications in your Auction
and Airline designs; the new wrinkle here is *where* we notify (outside the writer lock — see Step 5).

**3. Strategy** — `MatchFormatRules` isolates the volatile axis: formats. "Is this innings over?"
differs between T20 (20 overs or 10 wickets), ODI (50 overs), and TEST (declarations, follow-on).
`Match` delegates instead of branching on a format enum — adding The Hundred is a new class, not an
edited switch.

**4. Builder** — `Ball` has 9+ fields, most optional (extras, wicket, commentary). A telescoping
constructor would be unreadable and error-prone; Builder gives named, validated construction of an
immutable object.

**5. Singleton (initialization-on-demand holder)** — `CricInfoService` as the single facade, same
idiom you've used since Parking Lot: lazy, thread-safe via JVM class-initialization guarantees, no
`synchronized` on the hot path.

**6. Repository** — `ConcurrentHashMap`-backed registries for matches/teams/players, consistent
with every prior problem.

*(Lightly: event sourcing.* The ball log is an append-only event stream and all read models are
derived from it. You don't need the buzzword in an interview, but "I can rebuild any innings by
replaying its balls" is exactly the property that makes corrections trivial — Step 5.)*

### SOLID Mapping

- **S** — `Innings` applies balls; `InningsSnapshot` holds derived state; `Match` owns lifecycle;
  `CricInfoService` orchestrates. No god class doing scoring + searching + notifying.
- **O** — new formats via `MatchFormatRules`, new consumers via `MatchObserver`, new stats by
  extending snapshot derivation — none require editing existing classes.
- **L** — any `MatchFormatRules` implementation is substitutable in `Match`.
- **I** — `MatchObserver` is a single-method interface; subscribers aren't forced to implement
  scoring-side concerns.
- **D** — `Match` depends on the `MatchFormatRules` and `MatchObserver` abstractions, never on
  concrete formats or notification channels.

### The Two Decisions an Interviewer Will Probe

1. **Why `AtomicReference<InningsSnapshot>` instead of a `ReadWriteLock`?** Both are *correct* here.
   But with RWLock, every reader pays lock acquisition and readers stall during writes; with atomic
   snapshot publish, reads are a volatile read — zero contention, and a reader holding an old
   snapshot still sees something consistent. Note the contrast with your LRU Cache lesson: RWLock
   was *wrong* there because `get` mutated recency order. Here reads are genuinely pure, so RWLock
   would be *valid but inferior*. Being able to say "valid but inferior, and here's why" is senior-signal.
2. **Why is the scorecard derived rather than stored?** Single source of truth. If `BattingLine`
   were updated independently of the ball log, a missed update desynchronizes them forever. Deriving
   each snapshot from (previous snapshot + ball) keeps one authority and makes corrections a replay.

---

## Step 4 — Implementation

### Enums

```java
public enum MatchFormat { T20, ODI, TEST }
public enum MatchStatus { SCHEDULED, LIVE, COMPLETED, ABANDONED }
public enum ExtraType  { NONE, WIDE, NO_BALL, BYE, LEG_BYE }
public enum WicketType { BOWLED, CAUGHT, LBW, RUN_OUT, STUMPED, HIT_WICKET, RETIRED }
public enum PlayerRole { BATTER, BOWLER, ALL_ROUNDER, WICKET_KEEPER }
```

### Identity entities (boilerplate trimmed)

```java
public final class Player {
    private final String id;
    private final String name;
    private final PlayerRole role;

    public Player(String id, String name, PlayerRole role) {
        this.id = id; this.name = name; this.role = role;
    }
    public String getId()   { return id; }
    public String getName() { return name; }
    // equals/hashCode on id — Players are map keys in snapshots
    @Override public boolean equals(Object o) {
        return o instanceof Player && ((Player) o).id.equals(id);
    }
    @Override public int hashCode() { return id.hashCode(); }
}

public final class Team {
    private final String id;
    private final String name;
    private final List<Player> squad;

    public Team(String id, String name, List<Player> squad) {
        this.id = id; this.name = name;
        this.squad = List.copyOf(squad);          // defensive immutable copy
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public List<Player> getSquad() { return squad; }
}

public final class Venue {
    private final String name, city;
    public Venue(String name, String city) { this.name = name; this.city = city; }
    public String getName() { return name; }
}
```

### `Ball` — immutable, Builder-constructed

```java
public final class Ball {
    private final int over;            // 0-based
    private final int ballInOver;      // 1..6 (legal-ball numbering)
    private final Player striker, nonStriker, bowler;
    private final int runsOffBat;
    private final ExtraType extraType;
    private final int extraRuns;
    private final Wicket wicket;       // nullable
    private final String commentary;

    private Ball(Builder b) {
        this.over = b.over; this.ballInOver = b.ballInOver;
        this.striker = b.striker; this.nonStriker = b.nonStriker; this.bowler = b.bowler;
        this.runsOffBat = b.runsOffBat;
        this.extraType = b.extraType; this.extraRuns = b.extraRuns;
        this.wicket = b.wicket; this.commentary = b.commentary;
    }

    /** Wides and no-balls don't count toward the over. */
    public boolean isLegalDelivery() {
        return extraType != ExtraType.WIDE && extraType != ExtraType.NO_BALL;
    }
    public int totalRuns()      { return runsOffBat + extraRuns; }
    public boolean hasWicket()  { return wicket != null; }

    public Player getStriker()  { return striker; }
    public Player getBowler()   { return bowler; }
    public int getRunsOffBat()  { return runsOffBat; }
    public ExtraType getExtraType() { return extraType; }
    public int getExtraRuns()   { return extraRuns; }
    public Wicket getWicket()   { return wicket; }
    public String getCommentary() { return commentary; }

    public static class Builder {
        private int over, ballInOver;
        private Player striker, nonStriker, bowler;
        private int runsOffBat;
        private ExtraType extraType = ExtraType.NONE;
        private int extraRuns;
        private Wicket wicket;
        private String commentary = "";

        public Builder delivery(int over, int ballInOver) {
            this.over = over; this.ballInOver = ballInOver; return this;
        }
        public Builder players(Player striker, Player nonStriker, Player bowler) {
            this.striker = striker; this.nonStriker = nonStriker; this.bowler = bowler; return this;
        }
        public Builder runsOffBat(int r)            { this.runsOffBat = r; return this; }
        public Builder extra(ExtraType t, int r)    { this.extraType = t; this.extraRuns = r; return this; }
        public Builder wicket(Wicket w)             { this.wicket = w; return this; }
        public Builder commentary(String c)         { this.commentary = c; return this; }

        public Ball build() {
            if (striker == null || bowler == null)
                throw new InvalidBallException("Ball must reference striker and bowler");
            if (runsOffBat < 0 || runsOffBat > 6 || extraRuns < 0)
                throw new InvalidBallException("Invalid run values");
            if (extraType == ExtraType.NONE && extraRuns > 0)
                throw new InvalidBallException("Extra runs without an extra type");
            return new Ball(this);
        }
    }
}

public final class Wicket {
    private final WicketType type;
    private final Player batterOut;
    private final Player fielder;     // nullable (bowled, lbw)

    public Wicket(WicketType type, Player batterOut, Player fielder) {
        this.type = type; this.batterOut = batterOut; this.fielder = fielder;
    }
    public Player getBatterOut() { return batterOut; }
    public WicketType getType()  { return type; }
}
```

### Per-player lines inside a snapshot

```java
/** Immutable; 'with' methods return updated copies. */
public final class BattingLine {
    private final Player player;
    private final int runs, balls, fours, sixes;
    private final boolean out;

    public BattingLine(Player p) { this(p, 0, 0, 0, 0, false); }
    private BattingLine(Player p, int runs, int balls, int fours, int sixes, boolean out) {
        this.player = p; this.runs = runs; this.balls = balls;
        this.fours = fours; this.sixes = sixes; this.out = out;
    }
    BattingLine withBall(int r, boolean legal) {
        return new BattingLine(player, runs + r, balls + (legal ? 1 : 0),
                fours + (r == 4 ? 1 : 0), sixes + (r == 6 ? 1 : 0), out);
    }
    BattingLine dismissed() {
        return new BattingLine(player, runs, balls, fours, sixes, true);
    }
    public double strikeRate() { return balls == 0 ? 0 : runs * 100.0 / balls; }
    public int getRuns() { return runs; }
}

public final class BowlingLine {
    private final Player player;
    private final int legalBalls, runsConceded, wickets;

    public BowlingLine(Player p) { this(p, 0, 0, 0); }
    private BowlingLine(Player p, int legalBalls, int runsConceded, int wickets) {
        this.player = p; this.legalBalls = legalBalls;
        this.runsConceded = runsConceded; this.wickets = wickets;
    }
    BowlingLine withBall(Ball b) {
        // run-outs are not credited to the bowler
        boolean credited = b.hasWicket() && b.getWicket().getType() != WicketType.RUN_OUT;
        return new BowlingLine(player,
                legalBalls + (b.isLegalDelivery() ? 1 : 0),
                runsConceded + b.getRunsOffBat() + extrasAgainstBowler(b),
                wickets + (credited ? 1 : 0));
    }
    private static int extrasAgainstBowler(Ball b) {
        // wides & no-balls count against the bowler; byes/leg-byes do not
        switch (b.getExtraType()) {
            case WIDE: case NO_BALL: return b.getExtraRuns();
            default: return 0;
        }
    }
    public double economy() {
        return legalBalls == 0 ? 0 : runsConceded * 6.0 / legalBalls;
    }
}
```

### `InningsSnapshot` — the immutable heart

```java
/**
 * A fully consistent, frozen view of an innings after some whole ball.
 * apply() derives the NEXT snapshot; nothing here ever mutates.
 */
public final class InningsSnapshot {
    private final int runs, wickets, legalBalls;
    private final Map<Player, BattingLine> batting;
    private final Map<Player, BowlingLine> bowling;
    private final List<Ball> balls;                 // the event log / commentary feed

    public static InningsSnapshot empty() {
        return new InningsSnapshot(0, 0, 0, Map.of(), Map.of(), List.of());
    }

    private InningsSnapshot(int runs, int wickets, int legalBalls,
                            Map<Player, BattingLine> batting,
                            Map<Player, BowlingLine> bowling,
                            List<Ball> balls) {
        this.runs = runs; this.wickets = wickets; this.legalBalls = legalBalls;
        this.batting = batting; this.bowling = bowling; this.balls = balls;
    }

    /** Writer-side derivation: copy small maps, apply one ball, freeze. */
    public InningsSnapshot apply(Ball ball) {
        Map<Player, BattingLine> nextBatting = new HashMap<>(batting);
        nextBatting.merge(ball.getStriker(),
                new BattingLine(ball.getStriker()).withBall(ball.getRunsOffBat(), ball.isLegalDelivery()),
                (existing, ignored) -> existing.withBall(ball.getRunsOffBat(), ball.isLegalDelivery()));

        Map<Player, BowlingLine> nextBowling = new HashMap<>(bowling);
        nextBowling.merge(ball.getBowler(),
                new BowlingLine(ball.getBowler()).withBall(ball),
                (existing, ignored) -> existing.withBall(ball));

        int nextWickets = wickets;
        if (ball.hasWicket()) {
            Player out = ball.getWicket().getBatterOut();
            nextBatting.merge(out, new BattingLine(out).dismissed(),
                    (existing, ignored) -> existing.dismissed());
            nextWickets++;
        }

        List<Ball> nextBalls = new ArrayList<>(balls.size() + 1);
        nextBalls.addAll(balls);
        nextBalls.add(ball);

        return new InningsSnapshot(
                runs + ball.totalRuns(),
                nextWickets,
                legalBalls + (ball.isLegalDelivery() ? 1 : 0),
                Collections.unmodifiableMap(nextBatting),
                Collections.unmodifiableMap(nextBowling),
                Collections.unmodifiableList(nextBalls));
    }

    public int getRuns()    { return runs; }
    public int getWickets() { return wickets; }
    public int oversCompleted()   { return legalBalls / 6; }
    public int ballsIntoOver()    { return legalBalls % 6; }
    public double runRate() {
        return legalBalls == 0 ? 0 : runs * 6.0 / legalBalls;
    }
    public Map<Player, BattingLine> getBatting() { return batting; }
    public Map<Player, BowlingLine> getBowling() { return bowling; }
    public List<Ball> getCommentaryFeed()        { return balls; }
    public int getLegalBalls()                   { return legalBalls; }
}
```

### `Innings` — single-writer holder, lock-free reads

```java
public class Innings {
    private final Team battingTeam, bowlingTeam;
    private final AtomicReference<InningsSnapshot> snapshot =
            new AtomicReference<>(InningsSnapshot.empty());

    public Innings(Team battingTeam, Team bowlingTeam) {
        this.battingTeam = battingTeam; this.bowlingTeam = bowlingTeam;
    }

    /**
     * WRITER ONLY (Match serializes callers). Plain set(), not a CAS loop:
     * with a single writer there is no competing write to lose against.
     * The volatile write inside set() establishes happens-before for readers.
     */
    InningsSnapshot apply(Ball ball) {
        InningsSnapshot next = snapshot.get().apply(ball);
        snapshot.set(next);
        return next;
    }

    /** Lock-free, wait-free read. The snapshot is immutable; share freely. */
    public InningsSnapshot current() { return snapshot.get(); }

    public Team getBattingTeam() { return battingTeam; }
}
```

### Strategy — format rules

```java
public interface MatchFormatRules {
    int inningsPerSide();
    int maxOversPerInnings();          // Integer.MAX_VALUE for TEST
    boolean isInningsComplete(InningsSnapshot s, int playersPerSide);
}

public class LimitedOversRules implements MatchFormatRules {
    private final int maxOvers;        // 20 for T20, 50 for ODI
    public LimitedOversRules(int maxOvers) { this.maxOvers = maxOvers; }

    @Override public int inningsPerSide()      { return 1; }
    @Override public int maxOversPerInnings()  { return maxOvers; }
    @Override public boolean isInningsComplete(InningsSnapshot s, int playersPerSide) {
        return s.getWickets() >= playersPerSide - 1
            || s.getLegalBalls() >= maxOvers * 6;
    }

    public static MatchFormatRules forFormat(MatchFormat f) {
        switch (f) {
            case T20: return new LimitedOversRules(20);
            case ODI: return new LimitedOversRules(50);
            default:  throw new UnsupportedOperationException("TEST rules: separate strategy");
        }
    }
}
```

### Observer

```java
public interface MatchObserver {
    void onUpdate(MatchUpdate update);
}

public final class MatchUpdate {
    private final String matchId;
    private final Ball ball;                 // the event
    private final InningsSnapshot snapshot;  // consistent state AFTER the event
    public MatchUpdate(String matchId, Ball ball, InningsSnapshot snapshot) {
        this.matchId = matchId; this.ball = ball; this.snapshot = snapshot;
    }
    public InningsSnapshot getSnapshot() { return snapshot; }
    public Ball getBall() { return ball; }
}
```

### `Match` — lifecycle + writer serialization

```java
public class Match {
    private final String id;
    private final Team home, away;
    private final Venue venue;
    private final MatchFormat format;
    private final MatchFormatRules rules;
    private final LocalDateTime scheduledStart;

    private volatile MatchStatus status = MatchStatus.SCHEDULED;
    private final List<Innings> innings = new CopyOnWriteArrayList<>();
    private final List<MatchObserver> observers = new CopyOnWriteArrayList<>();

    /** Serializes the (single) writer path defensively; never held during reads or notification. */
    private final Object writerLock = new Object();

    public Match(String id, Team home, Team away, Venue venue,
                 MatchFormat format, MatchFormatRules rules, LocalDateTime start) {
        this.id = id; this.home = home; this.away = away; this.venue = venue;
        this.format = format; this.rules = rules; this.scheduledStart = start;
    }

    // ---- lifecycle (enum-guarded transitions, your standard idiom) ----

    public void start(Team battingFirst) {
        synchronized (writerLock) {
            requireStatus(MatchStatus.SCHEDULED, "start");
            Team bowling = battingFirst.equals(home) ? away : home;
            innings.add(new Innings(battingFirst, bowling));
            status = MatchStatus.LIVE;
        }
    }

    public void complete() {
        synchronized (writerLock) {
            requireStatus(MatchStatus.LIVE, "complete");
            status = MatchStatus.COMPLETED;
        }
    }

    public void abandon() {
        synchronized (writerLock) {
            if (status == MatchStatus.COMPLETED)
                throw new InvalidMatchStateException("Cannot abandon a completed match");
            status = MatchStatus.ABANDONED;
        }
    }

    // ---- the write path ----

    public void recordBall(Ball ball) {
        MatchUpdate update;
        synchronized (writerLock) {                     // validate + apply atomically
            requireStatus(MatchStatus.LIVE, "record a ball in");
            Innings current = currentInnings();
            InningsSnapshot after = current.apply(ball);
            update = new MatchUpdate(id, ball, after);

            if (rules.isInningsComplete(after, current.getBattingTeam().getSquad().size())) {
                advanceOrFinish();
            }
        }
        notifyObservers(update);   // OUTSIDE the lock — see Step 5
    }

    private void advanceOrFinish() {
        if (innings.size() < rules.inningsPerSide() * 2) {
            Innings done = currentInnings();
            innings.add(new Innings(
                    /* batting */ otherTeam(done.getBattingTeam()),
                    /* bowling */ done.getBattingTeam()));
        } else {
            status = MatchStatus.COMPLETED;
        }
    }

    // ---- the read path: no locks anywhere ----

    public MatchStatus getStatus() { return status; }   // volatile read

    /** Consistent per-innings snapshots; each frozen after a whole ball. */
    public List<InningsSnapshot> scoreboard() {
        List<InningsSnapshot> view = new ArrayList<>();
        for (Innings i : innings) view.add(i.current());
        return view;
    }

    public List<Ball> liveCommentary() {
        return innings.isEmpty() ? List.of() : currentInnings().current().getCommentaryFeed();
    }

    // ---- observers ----

    public void subscribe(MatchObserver o)   { observers.add(o); }
    public void unsubscribe(MatchObserver o) { observers.remove(o); }

    private void notifyObservers(MatchUpdate u) {
        for (MatchObserver o : observers) {
            try { o.onUpdate(u); }                      // one bad subscriber can't poison the rest
            catch (RuntimeException ex) { /* log and continue */ }
        }
    }

    // ---- helpers ----

    private Innings currentInnings() {
        if (innings.isEmpty()) throw new InvalidMatchStateException("Match has not started");
        return innings.get(innings.size() - 1);
    }
    private Team otherTeam(Team t) { return t.equals(home) ? away : home; }
    private void requireStatus(MatchStatus expected, String action) {
        if (status != expected)
            throw new InvalidMatchStateException(
                "Cannot " + action + " match " + id + " in status " + status);
    }

    public String getId() { return id; }
    public Team getHome() { return home; }
    public Team getAway() { return away; }
    public MatchFormat getFormat() { return format; }
    public LocalDateTime getScheduledStart() { return scheduledStart; }
}
```

### `CricInfoService` — Singleton facade with search & schedules

```java
public class CricInfoService {

    private CricInfoService() {}
    private static class Holder { static final CricInfoService INSTANCE = new CricInfoService(); }
    public static CricInfoService getInstance() { return Holder.INSTANCE; }

    private final Map<String, Match>  matches = new ConcurrentHashMap<>();
    private final Map<String, Team>   teams   = new ConcurrentHashMap<>();
    private final Map<String, Player> players = new ConcurrentHashMap<>();

    // ---- registration ----
    public void registerTeam(Team t)     { teams.put(t.getId(), t); }
    public void registerPlayer(Player p) { players.put(p.getId(), p); }

    public Match createMatch(String id, Team home, Team away, Venue venue,
                             MatchFormat format, LocalDateTime start) {
        MatchFormatRules rules = LimitedOversRules.forFormat(format);
        Match m = new Match(id, home, away, venue, format, rules, start);
        if (matches.putIfAbsent(id, m) != null)         // atomic duplicate check
            throw new IllegalArgumentException("Match id already exists: " + id);
        return m;
    }

    public Match getMatch(String id) {
        Match m = matches.get(id);
        if (m == null) throw new MatchNotFoundException(id);
        return m;
    }

    // ---- schedules & results ----
    public List<Match> upcomingMatches() {
        return matches.values().stream()
                .filter(m -> m.getStatus() == MatchStatus.SCHEDULED)
                .sorted(Comparator.comparing(Match::getScheduledStart))
                .collect(Collectors.toList());
    }

    public List<Match> liveMatches() {
        return byStatus(MatchStatus.LIVE);
    }

    public List<Match> results() {
        return byStatus(MatchStatus.COMPLETED);
    }

    private List<Match> byStatus(MatchStatus s) {
        return matches.values().stream()
                .filter(m -> m.getStatus() == s)
                .collect(Collectors.toList());
    }

    // ---- search (linear keyword scan; see Step 5 for the indexing discussion) ----
    public List<Match> searchMatches(String q) {
        String needle = q.toLowerCase();
        return matches.values().stream()
                .filter(m -> m.getHome().getName().toLowerCase().contains(needle)
                          || m.getAway().getName().toLowerCase().contains(needle))
                .collect(Collectors.toList());
    }

    public List<Player> searchPlayers(String q) {
        String needle = q.toLowerCase();
        return players.values().stream()
                .filter(p -> p.getName().toLowerCase().contains(needle))
                .collect(Collectors.toList());
    }

    // ---- subscriptions ----
    public void subscribe(String matchId, MatchObserver o)   { getMatch(matchId).subscribe(o); }
    public void unsubscribe(String matchId, MatchObserver o) { getMatch(matchId).unsubscribe(o); }
}
```

### Exceptions

```java
public class MatchNotFoundException extends RuntimeException {
    public MatchNotFoundException(String id) { super("Match not found: " + id); }
}
public class InvalidMatchStateException extends RuntimeException {
    public InvalidMatchStateException(String msg) { super(msg); }
}
public class InvalidBallException extends RuntimeException {
    public InvalidBallException(String msg) { super(msg); }
}
```

### Demo

```java
public class CricInfoDemo {
    public static void main(String[] args) {
        CricInfoService svc = CricInfoService.getInstance();

        Player kohli = new Player("p1", "Virat Kohli", PlayerRole.BATTER);
        Player rohit = new Player("p2", "Rohit Sharma", PlayerRole.BATTER);
        Player starc = new Player("p3", "Mitchell Starc", PlayerRole.BOWLER);

        Team india = new Team("t1", "India", List.of(kohli, rohit /* +9 */));
        Team aus   = new Team("t2", "Australia", List.of(starc /* +10 */));
        svc.registerTeam(india); svc.registerTeam(aus);

        Match m = svc.createMatch("m1", india, aus,
                new Venue("Chinnaswamy", "Bengaluru"), MatchFormat.T20,
                LocalDateTime.now());

        svc.subscribe("m1", u -> System.out.printf("LIVE: %d/%d — %s%n",
                u.getSnapshot().getRuns(), u.getSnapshot().getWickets(),
                u.getBall().getCommentary()));

        m.start(india);
        m.recordBall(new Ball.Builder()
                .delivery(0, 1).players(rohit, kohli, starc)
                .runsOffBat(4).commentary("Crunched through covers!").build());
        m.recordBall(new Ball.Builder()
                .delivery(0, 2).players(rohit, kohli, starc)
                .wicket(new Wicket(WicketType.BOWLED, rohit, null))
                .commentary("Timber! Starc strikes.").build());

        InningsSnapshot s = m.scoreboard().get(0);
        System.out.printf("Score: %d/%d in %d.%d overs, RR %.2f%n",
                s.getRuns(), s.getWickets(), s.oversCompleted(), s.ballsIntoOver(), s.runRate());
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid Input

| Case | Handling |
|---|---|
| Ball with negative/impossible runs, extras without extra type, missing striker/bowler | `Ball.Builder.build()` validates and throws `InvalidBallException` — invalid objects are unrepresentable. |
| Recording a ball on a non-LIVE match | `requireStatus` → `InvalidMatchStateException`; the enum-guarded transition idiom from your Auction/Facebook designs. |
| Duplicate match id | `putIfAbsent` makes the check-and-insert atomic (no check-then-act race). |
| Unknown match id on lookup/subscribe | `MatchNotFoundException` — fail fast with a precise message. |
| Striker not in the batting squad / bowler bowling consecutive overs | Validation hook inside `recordBall` under `writerLock`; omitted for brevity but name it in an interview as "domain-rule validation lives at the single write chokepoint." |

### Edge Cases

- **Innings-ending ball:** the last wicket / last legal ball both applies the ball *and* advances
  the innings (or completes the match) inside one `writerLock` section — readers never observe
  "11th wicket pending" states.
- **Wide on the last ball of the over:** `isLegalDelivery()` keeps wides/no-balls out of the
  `legalBalls` count, so over arithmetic (`legalBalls / 6`) stays correct by construction.
- **Run-out:** wicket recorded, but `BowlingLine.withBall` doesn't credit the bowler — a real
  scoring rule that's easy to miss and pleasant to mention.
- **Ball correction (umpire review reverses a decision):** because state is *derived* from the
  ball log, a correction is: take the log, replace/remove the offending `Ball`, replay through
  `InningsSnapshot.empty().apply(...)`, publish the rebuilt snapshot. One writer-side operation;
  readers just see the next consistent snapshot. This is the payoff of event-sourced derivation —
  with mutable in-place scorecards, corrections are a nightmare of compensating decrements.
- **Subscriber joins mid-match:** their first `onUpdate` carries a complete snapshot, so there's no
  "catch-up" protocol needed — the update is self-describing, not a delta.
- **Match abandoned mid-innings:** status flips under `writerLock`; subsequent `recordBall` fails
  fast; existing snapshots remain readable as the final state.

### Concurrency — the core argument

**Shared mutable state inventory:**

1. `Innings.snapshot` (`AtomicReference`) — the live score.
2. `Match.status` (`volatile`) + `Match.innings` list — lifecycle.
3. `observers` (`CopyOnWriteArrayList`) — subscription registry.
4. Service registries (`ConcurrentHashMap`).

**The design's central claim: readers are lock-free and can never see a torn state.**

- The writer derives a complete new `InningsSnapshot` *off to the side*, then publishes it with one
  `AtomicReference.set()`. A reader's `get()` returns either the old snapshot or the new one —
  both internally consistent. There is no instant at which runs are updated but wickets aren't,
  because those fields only ever change *together*, inside the construction of an object no reader
  has seen yet.
- **Visibility:** `AtomicReference.set()` is a volatile write; `get()` is a volatile read. The
  happens-before edge guarantees a reader who sees the new reference also sees every field of the
  snapshot fully initialized (safe publication of an effectively immutable object — final fields
  seal this).
- **Why plain `set()` and not a CAS loop:** CAS loops defend against *competing writers* losing
  updates. We have exactly one writer per match (and `writerLock` enforces it defensively even if
  the feed misbehaves), so there's nothing to compete with. Saying "I don't need CAS here, and
  here's why" demonstrates you understand the primitive rather than cargo-culting it.
- **Why not `ReentrantReadWriteLock`:** it would be *correct* — reads here are pure, unlike LRU
  Cache where `get` mutated recency order and RWLock was outright wrong. But RWLock makes every
  reader pay acquisition cost and stalls all readers during each write (every single ball). The
  snapshot approach gives readers wait-free access and costs the writer one small object graph per
  ball. For a read:write ratio of thousands:1, that trade is decisively right.
- **Cost honesty:** copy-on-write means O(squad size) map copies plus an O(balls) list copy per
  ball. For one match that's trivially cheap (≤ ~300 balls, 11-entry maps). If the log copy ever
  mattered, switch the ball log to a persistent (structurally-shared) list or an append-only
  `CopyOnWriteArrayList` referenced by index — name the optimization, don't pre-build it.

**Critical sections and lock discipline:**

- The *only* lock is `writerLock`, one per match, guarding: status checks, ball application, and
  innings advancement — so "validate state + apply ball + maybe finish innings" is atomic.
- **Observer notification happens outside the lock.** Holding a lock while invoking foreign code
  (`onUpdate`) is the classic recipe for surprise deadlock (a subscriber calling back into the
  match) and unbounded lock hold times behind a slow consumer. We build the `MatchUpdate` inside
  the lock (capturing the consistent snapshot) and deliver it after release. This is the same
  principle as Airline's "never hold a lock across the payment gateway call" — never hold a lock
  across code you don't control.

**Deadlock / livelock / starvation argument:**

- Deadlock requires a cycle of lock acquisitions. There is exactly one lock per match, no code path
  acquires two matches' locks, and no foreign code runs under the lock → no cycle is constructible.
- No CAS retry loops exist → no livelock.
- Readers never block → no reader starvation. The single writer contends only with lifecycle calls
  (start/complete), which are rare.

**Slow / failing subscribers:** `notifyObservers` catches per-observer exceptions so one broken
subscriber can't suppress the rest. For genuinely slow consumers, hand each observer its own
delivery via an `ExecutorService` (or a per-subscriber `BlockingQueue` drained by a worker) so the
scoring thread never waits on I/O — mention it; implement it only if the interviewer pushes.

**Search at scale:** linear `contains` scans are O(n) per query. The in-memory upgrade is an
inverted index — `ConcurrentHashMap<String /*token*/, Set<String /*ids*/>>` built at registration
time — turning search into a map lookup. Beyond a single node, this is where you'd say
"Elasticsearch" and stop, because that's HLD territory.

---

## Likely Interviewer Follow-ups (with model answers)

**1. "10x the readers — what breaks first and what do you do?"**
Nothing in the concurrency model breaks — readers are wait-free. The pressure point is fan-out:
notifying 10x subscribers per ball from one thread. Move delivery onto an executor, and note that
immutable snapshots are *perfect cache entries* — the same snapshot object can be handed to a CDN /
response cache keyed by (matchId, ballSeq) with zero invalidation logic, because it never changes.

**2. "Two feed sources for the same match — how do you handle conflicting updates?"**
The single-writer assumption breaks, so reintroduce coordination: sequence numbers on balls, with
`writerLock` already serializing application; reject or reconcile out-of-order/duplicate sequence
numbers (idempotency by ball sequence id). If both feeds are equally authoritative you need a
conflict policy (first-wins, or designated-primary with failover) — say explicitly that this is a
*policy* decision, not a locking trick.

**3. "Add career statistics across matches."**
Don't compute synchronously in `recordBall` — that couples live scoring latency to aggregate
bookkeeping. Make a `CareerStatsAggregator` *subscribe as a `MatchObserver`* and fold updates into
per-player aggregates asynchronously. Accept eventual consistency for career numbers; live match
data stays strongly consistent. The Observer seam you already have pays for the feature.

**4. "Implement TEST matches."**
New `TestMatchRules` strategy: `inningsPerSide() == 2`, no over cap, plus rules the interface must
grow to express — declarations and follow-on need an explicit "innings closed by captain" event,
and match completion needs result logic (win/draw/tie). The honest answer: the Strategy seam
absorbs most of it, but `isInningsComplete(snapshot)` alone is too narrow — you'd evolve the
interface, which is fine; OCP is about not editing *unrelated* code.

**5. "Why is `Ball` immutable? What would mutability cost you?"**
Three things break: (a) snapshots share `Ball` instances across versions — mutation would
retroactively corrupt published history; (b) safe publication without locks relies on immutability;
(c) the correction-by-replay story requires the log to be trustworthy. Immutability isn't a style
preference here — it's the load-bearing wall of the whole concurrency design.

---

## Transferable Lesson

**Match the concurrency strategy to the read/write ratio.** Write-contention systems (Parking Lot,
Concert Ticket, Airline) → per-resource locks, atomic check-and-set, compensation. Read-heavy
systems (CricInfo, config services, caches of derived data) → immutable snapshots + atomic publish:
writers pay a copy, readers pay nothing. Corollary: **derive, don't duplicate** — when state is a
function of an immutable event log, consistency and corrections come free. You'll reuse this in
Stack Overflow (vote counts, read-heavy Q&A) and in any "dashboard over a stream" problem.

**Next problem:** *Movie Ticket Booking System* — returns you to multi-resource atomicity
(multi-seat holds across shows) and pairs naturally with what Airline started; alternatively
*Stack Overflow* if you want to immediately re-apply today's read-heavy snapshot thinking in a
different domain. Recommended order: Movie Ticket Booking first, Stack Overflow after.
