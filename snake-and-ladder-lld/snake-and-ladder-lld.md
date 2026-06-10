# Snake and Ladder Game — Low-Level Design

**Tier:** Easy | **Language:** Java 11+ | **Source problem:** ashishps1/awesome-low-level-design

**Locked assumptions** (from requirements discussion):
- Exact landing required to win — overshooting roll means the player stays put.
- No chained jumps (a snake tail / ladder top never coincides with another jump's start).
- No special dice rules (no extra turn on 6) — kept as an extension point.
- Players may share a cell; pieces do not interact.
- "Concurrent sessions" = many independent `Game` instances managed by a thread-safe session registry; each game additionally guards its own move path with a per-game lock.

---

## Step 1 — Requirements

### Functional Requirements

1. The game is played on a board of N numbered cells (default 100), numbered 1..N.
2. The board has a predefined set of snakes (head → tail, head > tail) and ladders (base → top, base < top), fixed at game creation.
3. The game supports 2+ players, each with a name and a current position (starting off-board at position 0, conceptually "before cell 1").
4. Players take turns in fixed rotation, rolling a six-sided dice and moving forward by the rolled value.
5. Landing on a snake head slides the player down to the snake's tail.
6. Landing on a ladder base lifts the player to the ladder's top.
7. A player wins by landing **exactly** on cell N; an overshooting roll is forfeited (player doesn't move).
8. The game ends when a player wins; further moves are rejected.
9. Multiple independent game sessions run concurrently, each with its own board, players, and dice.

### Non-Functional Requirements

- **Extensibility:** dice behavior, board size, and snake/ladder layout must be pluggable without modifying the game loop (Open/Closed). Future rules (extra turn on 6, bounce-back overshoot) should attach without rewriting `Game`.
- **Correctness of configuration:** the board must reject invalid layouts at construction time — jumps on cell 1 or cell N, a snake that goes up, a ladder that goes down, two jumps starting on the same cell, or chained jumps.
- **Concurrency:**
  - The **session registry** (create/lookup/remove games) is accessed by many threads → must be thread-safe.
  - **Per-game state** (positions, turn index, status) is mutated by move calls; even though one game is usually driven sequentially, the design must be safe if two requests for the same game race (e.g., double-submitted HTTP request).
- **Testability:** `Dice` as an interface lets tests inject deterministic rolls.

---

## Step 2 — Entities & Relationships

### Core entities

| Entity | Responsibility | Why it exists |
|---|---|---|
| `Player` | Identity (id, name) + current position | Position lives on the player, not the board — the board is immutable shared topology; player state is per-participant. |
| `Jump` | A start→end cell mapping with a type (`SNAKE` / `LADDER`) | **Deliberate under-modeling:** a snake and a ladder have *identical behavior* (teleport from start to end); only the direction invariant differs. One class with static factories beats a `Snake`/`Ladder` inheritance hierarchy that would add code without adding behavior. |
| `Board` | Immutable: size + `Map<Integer, Integer>` of jump start → end; answers `getFinalPosition(cell)` | Pure topology. Knows nothing about players or turns — Single Responsibility. |
| `BoardBuilder` | Validates and assembles a `Board` | Validation logic (no overlaps, no chains, bounds) is construction-time policy; keeping it out of `Board` keeps `Board` trivially immutable and thread-safe. |
| `Dice` (interface) + `StandardDice` | `int roll()` | Strategy: randomness is a pluggable policy; tests and variants (crooked dice, two dice) swap implementations. |
| `Game` | Orchestrates one session: players, board, dice, turn rotation, status, winner; exposes `takeTurn()` | The aggregate root of a session. Owns the per-game `ReentrantLock`. |
| `GameSessionManager` | Singleton registry of active games (`ConcurrentHashMap<String, Game>`) | Satisfies requirement 8 (concurrent sessions). |

### Enums

- `GameStatus { NOT_STARTED, IN_PROGRESS, FINISHED }` — enum-guarded lifecycle, same approach as `BookingStatus` / `AuctionStatus` in earlier problems. No GoF State pattern needed: three states, one legal path.
- `JumpType { SNAKE, LADDER }` — carries the direction invariant for validation and display.

### Relationships

| Relationship | Type | Why |
|---|---|---|
| `Game` → `Board` | **Aggregation** | The board is conceptually shareable (the same immutable board layout could back many games); the game holds a reference but doesn't own its lifecycle in any deep sense. Because `Board` is immutable, sharing is actually safe. |
| `Game` → `Player` (1..*) | **Composition** | Players-in-this-game (with their positions) exist only within the session; when the game is discarded, those positional states are meaningless. |
| `Game` → `Dice` | **Aggregation / dependency on abstraction** | Injected interface (DIP). Game depends on `Dice`, never on `StandardDice`. |
| `Board` → `Jump` (0..*) | **Composition** | Jumps have no identity outside their board. Internally flattened to a `Map<Integer,Integer>` for O(1) lookup. |
| `GameSessionManager` → `Game` (0..*) | **Aggregation** | Registry holds references; games are created via the manager but their lifetime ends when the game finishes / is removed. |
| `BoardBuilder` → `Board` | **Dependency** (creates) | Builder produces the immutable product. |

**What we deliberately did NOT model:** no `Cell` class (a cell is just an `int` — a 100-element object array adds memory and indirection for zero behavior), no `Piece` class separate from `Player` (one piece per player, so it would be a pure wrapper), no `Turn` entity (a turn is a method call, not a thing with state).

---

## Step 3 — UML Class Design

```text
┌──────────────────────────┐
│ <<interface>> Dice       │
│ + roll(): int            │
└─────────▲────────────────┘
          │ implements
┌─────────┴────────────────┐
│ StandardDice             │
│ - faces: int             │
│ + roll(): int            │   // ThreadLocalRandom
└──────────────────────────┘

┌──────────────────────────┐        ┌──────────────────────────┐
│ Jump                     │        │ Board                    │
│ - start: int             │ 0..*   │ - size: int              │
│ - end: int               │◆───────│ - jumps: Map<Int,Int>    │
│ - type: JumpType         │        │ + getSize(): int         │
│ + snake(h,t): Jump       │        │ + getFinalPosition(int)  │
│ + ladder(b,t): Jump      │        └─────────▲────────────────┘
└──────────────────────────┘                  │ builds
                                   ┌──────────┴───────────────┐
                                   │ BoardBuilder             │
                                   │ + size(int)              │
                                   │ + addSnake(h,t)          │
                                   │ + addLadder(b,t)         │
                                   │ + build(): Board         │  // all validation here
                                   └──────────────────────────┘

┌──────────────────────────┐
│ Player                   │
│ - id: String             │
│ - name: String           │
│ - position: int          │
│ + advanceTo(int)         │
└─────────▲────────────────┘
          │ 2..* (composition)
┌─────────┴───────────────────────────────┐
│ Game                                    │
│ - id: String                            │
│ - board: Board            (aggregation) │
│ - dice: Dice              (injected)    │
│ - players: List<Player>                 │
│ - currentPlayerIndex: int               │
│ - status: GameStatus                    │
│ - winner: Player                        │
│ - moveLock: ReentrantLock               │
│ + start()                               │
│ + takeTurn(): MoveResult                │
│ + getStatus() / getWinner()             │
└─────────▲───────────────────────────────┘
          │ 0..* (registry)
┌─────────┴───────────────────────────────┐
│ GameSessionManager  <<singleton>>       │
│ - sessions: ConcurrentHashMap<Str,Game> │
│ + createGame(...): Game                 │
│ + getGame(id): Game                     │
│ + endGame(id)                           │
└─────────────────────────────────────────┘

enum GameStatus { NOT_STARTED, IN_PROGRESS, FINISHED }
enum JumpType   { SNAKE, LADDER }
```

### SOLID mapping

- **S — Single Responsibility:** `Board` = topology only; `Dice` = randomness only; `Game` = turn orchestration only; `BoardBuilder` = validation/assembly only. Each class has exactly one reason to change.
- **O — Open/Closed:** new dice behavior (crooked, weighted, two-dice) or new board layouts require zero edits to `Game`. The overshoot rule is isolated in one private method — swapping to "bounce-back" touches one place (and could itself be made a `MovementRule` strategy if the interviewer pushes).
- **L — Liskov:** any `Dice` implementation that returns an int in its declared range substitutes cleanly.
- **I — Interface Segregation:** `Dice` is a one-method interface; clients aren't forced to depend on anything they don't use.
- **D — Dependency Inversion:** `Game` depends on the `Dice` abstraction, injected via constructor — the plain-Java equivalent of Spring constructor injection. In Spring you'd make `StandardDice` a prototype-scoped `@Component`; here we just `new` it at the composition root (`GameSessionManager.createGame`).

### Design patterns and exactly why they fit

- **Strategy (`Dice`):** the *algorithm for producing a roll* varies independently of the game loop. This is the textbook Strategy trigger: "the behavior is a policy, the consumer shouldn't care which one." Same role `FeeStrategy`/`ParkingFeeStrategy` played earlier.
- **Builder (`BoardBuilder`):** the board has multi-step, invariant-heavy construction (size, many jumps, cross-jump validation like chain detection that can only run once all jumps are known). A telescoping constructor can't express "validate the whole set at the end"; Builder's `build()` is the natural validation choke point, and the product comes out immutable.
- **Singleton (`GameSessionManager`):** one process-wide registry, implemented with the **initialization-on-demand holder idiom** — lazy, thread-safe via JVM class-loading guarantees, no `synchronized` on every `getInstance()` call.
- **Patterns deliberately NOT used:** *State* (three statuses with a linear lifecycle don't justify one class per state — enum guard clauses are simpler and the interviewer will respect the restraint if you can articulate it); *Factory Method for Snake/Ladder subclasses* (no behavioral difference to dispatch on — the static factories `Jump.snake()`/`Jump.ladder()` give the readable API without the hierarchy); *Observer* (no requirement for move notifications yet — name it as the obvious extension for "broadcast moves to spectators").

### The two decisions an interviewer will probe

1. **One `Jump` class vs. `Snake`/`Ladder` subclasses.** Be ready to defend: subclassing encodes *behavioral* variation, and here there is none — both are `position → position`. The variation is in the *construction invariant* (down vs. up), which the static factories enforce. If a future rule makes snakes behave differently (e.g., "snake bite costs a turn"), *then* you refactor to polymorphism — and the `Map<Integer,Integer>` flattening would become `Map<Integer, Jump>`.
2. **Why does `Game.takeTurn()` need a lock at all if turns are sequential?** Because the design can't assume its caller is single-threaded: a double-clicked button or retried HTTP request produces two concurrent `takeTurn()` calls on the same game. The per-game `ReentrantLock` makes the whole turn (roll → compute → mutate position → rotate turn → check win) atomic. This is the per-resource-locking principle again: lock per game, never one global lock across all sessions.

---

## Step 4 — Implementation

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

// ---------- Enums ----------

enum GameStatus { NOT_STARTED, IN_PROGRESS, FINISHED }

enum JumpType { SNAKE, LADDER }

// ---------- Dice (Strategy) ----------

interface Dice {
    int roll();
}

class StandardDice implements Dice {
    private final int faces;

    StandardDice() { this(6); }
    StandardDice(int faces) {
        if (faces < 2) throw new IllegalArgumentException("Dice needs at least 2 faces");
        this.faces = faces;
    }

    @Override
    public int roll() {
        // ThreadLocalRandom: no contention across games, unlike a shared Random
        return ThreadLocalRandom.current().nextInt(1, faces + 1);
    }
}

// ---------- Jump ----------

/**
 * One class for both snakes and ladders: behavior is identical (teleport start -> end).
 * The direction invariant is enforced at the static factories, so an "upward snake"
 * is unrepresentable.
 */
final class Jump {
    private final int start;
    private final int end;
    private final JumpType type;

    private Jump(int start, int end, JumpType type) {
        this.start = start;
        this.end = end;
        this.type = type;
    }

    static Jump snake(int head, int tail) {
        if (tail >= head) throw new InvalidBoardException(
                "Snake must go down: head=" + head + " tail=" + tail);
        return new Jump(head, tail, JumpType.SNAKE);
    }

    static Jump ladder(int base, int top) {
        if (top <= base) throw new InvalidBoardException(
                "Ladder must go up: base=" + base + " top=" + top);
        return new Jump(base, top, JumpType.LADDER);
    }

    int getStart() { return start; }
    int getEnd()   { return end; }
    JumpType getType() { return type; }
}

// ---------- Board (immutable) + Builder ----------

final class Board {
    private final int size;
    private final Map<Integer, Integer> jumps; // start -> end, snakes and ladders flattened

    Board(int size, Map<Integer, Integer> jumps) {
        this.size = size;
        this.jumps = Collections.unmodifiableMap(new HashMap<>(jumps)); // defensive copy
    }

    int getSize() { return size; }

    /** Where do you actually end up if you land on `cell`? O(1), no chains by validation. */
    int getFinalPosition(int cell) {
        return jumps.getOrDefault(cell, cell);
    }

    boolean hasJumpAt(int cell) { return jumps.containsKey(cell); }
}

class BoardBuilder {
    private int size = 100;
    private final List<Jump> jumps = new ArrayList<>();

    BoardBuilder size(int size) {
        if (size < 10) throw new InvalidBoardException("Board too small: " + size);
        this.size = size;
        return this;
    }

    BoardBuilder addSnake(int head, int tail)  { jumps.add(Jump.snake(head, tail));  return this; }
    BoardBuilder addLadder(int base, int top)  { jumps.add(Jump.ladder(base, top));  return this; }

    /** All cross-jump validation lives here — it can only run once every jump is known. */
    Board build() {
        Map<Integer, Integer> map = new HashMap<>();
        for (Jump j : jumps) {
            if (j.getStart() <= 1 || j.getStart() >= size || j.getEnd() < 1 || j.getEnd() > size)
                throw new InvalidBoardException("Jump out of bounds: " + j.getStart() + "->" + j.getEnd());
            if (j.getStart() == size)
                throw new InvalidBoardException("No jump may start on the final cell");
            if (map.putIfAbsent(j.getStart(), j.getEnd()) != null)
                throw new InvalidBoardException("Two jumps start at cell " + j.getStart());
        }
        // forbid chains: no jump may END on a cell where another jump STARTS
        for (Jump j : jumps) {
            if (map.containsKey(j.getEnd()))
                throw new InvalidBoardException(
                        "Chained jump at cell " + j.getEnd() + " (end of one jump is start of another)");
        }
        return new Board(size, map);
    }
}

// ---------- Player ----------

class Player {
    private final String id;
    private final String name;
    private int position = 0; // 0 = off-board, before cell 1

    Player(String id, String name) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
    }

    String getId() { return id; }
    String getName() { return name; }
    int getPosition() { return position; }
    void advanceTo(int position) { this.position = position; }
}

// ---------- Move result (immutable report of one turn) ----------

final class MoveResult {
    final String playerId;
    final int roll;
    final int from;
    final int to;
    final boolean jumped;        // did a snake/ladder fire?
    final boolean overshoot;     // roll forfeited (exact-landing rule)
    final boolean won;

    MoveResult(String playerId, int roll, int from, int to,
               boolean jumped, boolean overshoot, boolean won) {
        this.playerId = playerId; this.roll = roll; this.from = from; this.to = to;
        this.jumped = jumped; this.overshoot = overshoot; this.won = won;
    }

    @Override
    public String toString() {
        if (overshoot) return playerId + " rolled " + roll + " — overshoot, stays at " + from;
        return playerId + " rolled " + roll + ": " + from + " -> " + to
                + (jumped ? " (jump!)" : "") + (won ? "  WINNER" : "");
    }
}

// ---------- Game ----------

class Game {
    private final String id;
    private final Board board;
    private final Dice dice;
    private final List<Player> players;
    private final ReentrantLock moveLock = new ReentrantLock();

    private int currentPlayerIndex = 0;
    private volatile GameStatus status = GameStatus.NOT_STARTED; // volatile: read without lock
    private volatile Player winner;

    Game(String id, Board board, Dice dice, List<Player> players) {
        if (players == null || players.size() < 2)
            throw new IllegalArgumentException("Need at least 2 players");
        long distinct = players.stream().map(Player::getId).distinct().count();
        if (distinct != players.size())
            throw new IllegalArgumentException("Duplicate player ids");
        this.id = id;
        this.board = board;
        this.dice = dice;
        this.players = new ArrayList<>(players); // defensive copy; turn order = list order
    }

    String getId() { return id; }
    GameStatus getStatus() { return status; }
    Optional<Player> getWinner() { return Optional.ofNullable(winner); }
    Player getCurrentPlayer() { return players.get(currentPlayerIndex); }

    void start() {
        moveLock.lock();
        try {
            if (status != GameStatus.NOT_STARTED)
                throw new IllegalGameStateException("Game " + id + " already started");
            status = GameStatus.IN_PROGRESS;
        } finally {
            moveLock.unlock();
        }
    }

    /**
     * Executes one full turn for the current player atomically:
     * roll -> compute landing -> apply jump -> mutate position -> win check -> rotate turn.
     * The lock makes a duplicate concurrent call (double-click / retried request)
     * harmless: the second call simply plays the NEXT turn, it can never interleave
     * inside this one.
     */
    MoveResult takeTurn() {
        moveLock.lock();
        try {
            if (status != GameStatus.IN_PROGRESS)
                throw new IllegalGameStateException("Game " + id + " is " + status);

            Player player = players.get(currentPlayerIndex);
            int roll = dice.roll();
            int from = player.getPosition();
            int tentative = from + roll;

            // Exact-landing rule: overshoot forfeits the move (position unchanged)
            if (tentative > board.getSize()) {
                rotateTurn();
                return new MoveResult(player.getId(), roll, from, from, false, true, false);
            }

            int finalPos = board.getFinalPosition(tentative); // snake/ladder, O(1)
            boolean jumped = finalPos != tentative;
            player.advanceTo(finalPos);

            boolean won = finalPos == board.getSize();
            if (won) {
                winner = player;
                status = GameStatus.FINISHED;
            } else {
                rotateTurn();
            }
            return new MoveResult(player.getId(), roll, from, finalPos, jumped, false, won);
        } finally {
            moveLock.unlock();
        }
    }

    private void rotateTurn() {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
    }
}

// ---------- Session manager (Singleton, holder idiom) ----------

class GameSessionManager {
    private GameSessionManager() {}

    private static class Holder {
        // JVM class-init guarantees: lazy AND thread-safe, no synchronization cost per call
        private static final GameSessionManager INSTANCE = new GameSessionManager();
    }

    static GameSessionManager getInstance() { return Holder.INSTANCE; }

    private final ConcurrentHashMap<String, Game> sessions = new ConcurrentHashMap<>();

    Game createGame(Board board, Dice dice, List<Player> players) {
        String id = UUID.randomUUID().toString();
        Game game = new Game(id, board, dice, players);
        sessions.put(id, game); // unique UUID: no race on key
        game.start();
        return game;
    }

    Game getGame(String gameId) {
        Game g = sessions.get(gameId);
        if (g == null) throw new GameNotFoundException("No game: " + gameId);
        return g;
    }

    void endGame(String gameId) { sessions.remove(gameId); }
}

// ---------- Exceptions ----------

class InvalidBoardException extends RuntimeException {
    InvalidBoardException(String msg) { super(msg); }
}
class IllegalGameStateException extends RuntimeException {
    IllegalGameStateException(String msg) { super(msg); }
}
class GameNotFoundException extends RuntimeException {
    GameNotFoundException(String msg) { super(msg); }
}

// ---------- Demo ----------

public class SnakeAndLadderDemo {
    public static void main(String[] args) {
        Board board = new BoardBuilder()
                .size(100)
                .addSnake(99, 7).addSnake(62, 19).addSnake(54, 34)
                .addLadder(4, 56).addLadder(12, 50).addLadder(63, 95)
                .build();

        Game game = GameSessionManager.getInstance().createGame(
                board,
                new StandardDice(),
                List.of(new Player("p1", "Anbu"), new Player("p2", "Ravi")));

        while (game.getStatus() == GameStatus.IN_PROGRESS) {
            System.out.println(game.takeTurn());
        }
        game.getWinner().ifPresent(w -> System.out.println("Winner: " + w.getName()));
        GameSessionManager.getInstance().endGame(game.getId());
    }
}
```

**Spring Boot aside:** `GameSessionManager` is exactly what a `@Service` with a `ConcurrentHashMap` field would be — Spring singletons get you the holder idiom for free via container-managed scope. `Dice` would be an injected bean, and per-game state would still need the explicit lock because Spring's singleton scope does nothing for *your* mutable state.

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input & construction-time failures

| Failure | Where caught | Behavior |
|---|---|---|
| Snake going up / ladder going down | `Jump.snake()` / `Jump.ladder()` factories | `InvalidBoardException` — invalid jumps are *unrepresentable*, not just unchecked |
| Jump on cell 1 / final cell, out of bounds | `BoardBuilder.build()` | `InvalidBoardException` |
| Two jumps starting on the same cell | `build()` via `putIfAbsent` | `InvalidBoardException` |
| Chained jumps (snake tail on ladder base) | second validation pass in `build()` | `InvalidBoardException` |
| < 2 players, duplicate player ids | `Game` constructor | `IllegalArgumentException` |
| Move on a finished / unstarted game | `takeTurn()` status guard | `IllegalGameStateException` |
| Unknown game id | `GameSessionManager.getGame` | `GameNotFoundException` |

Design principle: **fail at construction, not mid-game.** Every board invariant is checked in `build()`, so `Game.takeTurn()` never needs to re-validate topology — that's why `getFinalPosition` can be a bare map lookup.

### Edge cases

- **Overshoot at 98 rolling 5:** `tentative > size` → turn forfeited, position unchanged, turn rotates. The rule sits in one guard clause; switching to "bounce-back" (`to = size - (tentative - size)`) is a one-line change — or extract a `MovementRule` strategy if the interviewer asks for both to coexist.
- **Landing exactly on the final cell via a ladder:** works naturally — `getFinalPosition` resolves the ladder first, then the win check compares the *final* position. (A ladder topping out at 100 is legal; a jump *starting* at 100 is not.)
- **Player starts off-board (position 0):** first roll moves to cells 1–6; no special-casing needed since `0 + roll` is always in range.
- **Snake on the very first landing:** fine — jumps are resolved on every landing including the first.
- **Single-player game:** rejected at construction (needs ≥ 2). A solo "practice mode" would be a deliberate rule change, not an accident.
- **Game abandoned mid-way:** `endGame` removes it from the registry; an idle-TTL sweep with `ScheduledExecutorService` is the production extension (same eviction idea as expired reservations in Airline/Concert).

### Concurrency analysis

**Shared mutable state, enumerated:**
1. `GameSessionManager.sessions` — shared across all request threads.
2. Per-`Game`: `currentPlayerIndex`, each `Player.position`, `status`, `winner`.
3. `Board` — shared but **immutable** (`final` fields, unmodifiable defensive-copied map) → safe to share across threads and even across games with zero synchronization. Immutability is the cheapest concurrency tool you have.

**Critical sections & chosen primitives:**

| State | Primitive | Why this one |
|---|---|---|
| Session registry | `ConcurrentHashMap` | Lock-striped, no global lock; `put`/`get`/`remove` are the only ops needed and they're individually atomic. Keys are fresh UUIDs so no compound check-then-act is required. |
| One game's turn | per-game `ReentrantLock` around the whole of `takeTurn()` | The turn is a multi-step read-modify-write (roll → position → win check → rotate). It must be atomic as a unit; a lock per game gives that without serializing *other* games — per-resource locking, exactly as in Parking Lot / Concert Ticket. |
| `status`, `winner` | `volatile` | Read by observers (`getStatus()`, demo loop) without taking the lock; `volatile` guarantees visibility of the write made inside the lock. Writes only happen under the lock, so atomicity isn't at stake — only visibility. |
| Dice randomness | `ThreadLocalRandom` | A shared `java.util.Random` is thread-safe but contends on one CAS'd seed across every game in the process; `ThreadLocalRandom` removes that cross-game contention entirely. |

**Why there is no deadlock:** `takeTurn()` acquires exactly **one** lock and calls nothing that acquires another (board is lock-free immutable, dice is thread-local). Single-lock-at-a-time designs cannot deadlock — there is no cycle to form. This is the strongest deadlock argument available and worth stating verbatim in an interview.

**Why there is no race:** every mutation of game state (`position`, `currentPlayerIndex`, `status`, `winner`) happens only inside `moveLock`. A duplicated concurrent `takeTurn()` call serializes behind the lock and legitimately plays the *next* player's turn — consistent, never corrupted. If "the same client must not accidentally play two turns" is a requirement, the fix is an idempotency layer (pass an expected `playerId` or turn-sequence number into `takeTurn` and reject mismatches) — an API-design concern layered on top, not a locking concern.

**Why not `synchronized` instead of `ReentrantLock`?** Here `synchronized(this)` would honestly suffice — single lock, no timeouts, no fairness need. `ReentrantLock` is chosen for interview signaling and forward-compatibility (`tryLock` with timeout if turns later involve waiting, e.g., a timed-turn rule). Say this trade-off out loud; pretending `synchronized` is wrong is a red flag.

**Scale note (the "10x sessions" follow-up):** the design already scales linearly in sessions — games share nothing mutable. The registry's `ConcurrentHashMap` is the only shared structure and it's contention-striped. Beyond one JVM, `Game` state would move to a store (Redis hash per game) with optimistic versioning (CAS on a turn counter) replacing the in-process lock — the same evolution path as Digital Wallet's account versioning.

---

## Likely Interviewer Follow-ups

**1. "Add the rule: rolling a 6 grants an extra turn, three 6s in a row void the turn."**
Keep a small per-turn context: track `consecutiveSixes` for the current player inside `Game`. On roll == 6 and count < 3, skip `rotateTurn()`; on the third 6, reset position changes for that chain? (Standard rule: only the turn is voided — simply rotate and reset the counter.) If rules proliferate, extract a `TurnRule` strategy (`boolean shouldRotate(MoveResult, context)`), restoring Open/Closed.

**2. "Support a 'bounce-back' overshoot variant alongside exact-landing."**
Extract `MovementRule { int resolve(int from, int roll, int boardSize); }` with `ExactLanding` and `BounceBack` implementations, injected like `Dice`. Strategy again — the moment a rule has two live variants, it stops being a guard clause and becomes a policy object.

**3. "How do spectators watch a game live?"**
Observer: `Game` keeps a `CopyOnWriteArrayList<GameListener>` and publishes each `MoveResult` after the lock is released (never notify while holding the lock — listeners doing slow work would serialize the game; same never-hold-a-lock-across-I/O lesson as Airline's payment gateway).

**4. "What if a player disconnects mid-game?"**
Add `PlayerStatus { ACTIVE, FORFEITED }`; `rotateTurn()` skips forfeited players; if only one active player remains, they win by default. The enum-guard approach extends without a State hierarchy.

**5. "Make it work across multiple servers."**
The per-game lock only protects one JVM. Move game state to Redis (hash per game) and replace the lock with optimistic concurrency: store a `turnVersion`, do read → compute → conditional write (Lua script or `WATCH/MULTI`), retry on conflict. Sticky sessions (route a game's requests to one node) is the pragmatic alternative that preserves the in-process design.

---

## Transferable Lesson

This problem is the cleanest illustration of three recurring ideas:

1. **Immutability as a concurrency primitive.** `Board` needs no locks *because it can't change*. Before reaching for `ReentrantLock`, ask "can this just be immutable?" — it's the first tool, not the last.
2. **Make invalid states unrepresentable.** Direction invariants live in `Jump`'s factories; cross-jump invariants live in `build()`. Runtime code (`takeTurn`) carries zero topology validation. This validation-at-the-boundary pattern is the same one behind `BoardBuilder` here and request-validation layers in every larger system.
3. **Restraint is a design skill.** No `Cell`, no `Snake`/`Ladder` hierarchy, no State pattern, no Observer (yet). Each omission has a one-sentence justification — interviewers probe over-engineering as hard as under-engineering.

**Next problem suggestion:** **Stack Overflow** (Medium) — introduces reputation/voting invariants and a richer entity graph, or jump to **Movie Ticket Booking System** (Hard) to escalate the per-entity locking you used here into multi-seat atomic claim + compensation.
