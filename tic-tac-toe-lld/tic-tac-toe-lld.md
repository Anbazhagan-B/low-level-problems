# Low Level Design — Tic Tac Toe

*Target: Senior Java interview, plain Java 11+, framework-agnostic. Designed for N×N (default 3×3) to demonstrate extensibility at near-zero cost.*

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| F1 | Game is played on an N×N grid (default 3×3); each cell is empty or holds one symbol. |
| F2 | Exactly two players, each with a unique symbol (X, O). X moves first by convention. |
| F3 | Players alternate turns; the game enforces turn order. |
| F4 | A move is valid only if: it is that player's turn, coordinates are in bounds, the target cell is empty, and the game is still in progress. |
| F5 | After each move, detect a win: N matching symbols in a row, column, or either diagonal. |
| F6 | If the board is full with no winner, the game is a draw. |
| F7 | Once finished, the game announces the result and rejects further moves. |
| F8 | A presentation layer (console here) renders the board and collects moves — kept strictly separate from game logic. |

### Non-Functional Requirements

| # | Requirement |
|---|-------------|
| NF1 | **Extensibility**: N×N boards, pluggable win rules ("k in a row"), and swappable player types (human → bot) without modifying core classes (OCP). |
| NF2 | **Separation of concerns**: Core engine has zero UI dependency → unit-testable. |
| NF3 | **Performance**: Win check is O(N) per move (inspect only the affected row/column/diagonals), not an O(N²) board scan. |
| NF4 | **Concurrency**: Not required for a local 2-player game; we state this assumption explicitly and show in Step 5 how the design hardens for a multiplayer-server variant. |
| NF5 | **Replayability**: `Game` instances are cheap and self-contained — start a fresh game without process restart. |

### Assumptions (stated to the interviewer)

1. Two human players; `Player` is modeled so a bot strategy can be swapped in later.
2. Invalid moves throw a descriptive unchecked exception; the UI layer decides how to surface it.
3. Console rendering is sufficient; logic is UI-agnostic.
4. Undo / move history / scorekeeping are out of scope (the design leaves a clean seam for them — see follow-ups).

---

## Step 2 — Entities & Relationships

| Entity | Kind | Responsibility | Relationship & Why |
|--------|------|----------------|--------------------|
| `Symbol` | enum | `X`, `O`, `EMPTY`. Type-safe cell content — no magic chars. | Used by `Board` and `Player` (**dependency/association**). |
| `GameStatus` | enum | `IN_PROGRESS`, `WIN`, `DRAW`. Explicit lifecycle — avoids boolean-flag soup. | Owned by `Game` (**association**). |
| `Player` | class | Identity (name) + assigned `Symbol`. | `Game` **aggregates** players: players are created outside the game and could conceptually outlive it (e.g., a tournament reuses them). |
| `Board` | class | Owns the N×N grid; enforces cell-level validity (bounds, emptiness); knows nothing about turns or rules. | `Game` **composes** `Board`: the board has no meaning or lifetime outside its game. Created inside `Game`, dies with it. |
| `WinningStrategy` | interface | `checkWinner(board, lastMove)` — is the last move a winning move? | `Game` holds a strategy (**aggregation**); concrete strategies **depend** on `Board` (read-only). Strategy pattern seam. |
| `RowColumnDiagonalStrategy` | class | Default O(N) implementation. | Implements `WinningStrategy` (**realization**). |
| `Move` | class (value object) | Immutable (row, col, symbol) — what just happened. | `Game` and strategies **depend** on it transiently. |
| `Game` | class | The orchestrator: turn order, status transitions, delegating validity to `Board` and win detection to the strategy. | Composition root of the model. |
| `GameController` / console UI | class | Reads input, renders board, reports errors. | **Depends** on `Game`; `Game` never depends on it (Dependency Inversion at the architecture level). |

**Common over-modeling traps (worth saying in an interview):**
- A `Cell` class is unnecessary here — a cell has no behavior or identity beyond its `Symbol`. A `Symbol[][]` is simpler. (If cells later gain state — e.g., power-ups — promote to a class then.)
- A `PlayerFactory` or `GameFactory` is premature for two constructor calls.
- Separate `RowStrategy` / `ColumnStrategy` / `DiagonalStrategy` classes are a defensible alternative (compose a list of strategies), but for Tic Tac Toe one efficient last-move checker reads better; mention the split as the path to Gomoku-style variants.

---

## Step 3 — UML Class Design

```
+----------------+        +---------------------+
|   <<enum>>     |        |     <<enum>>        |
|    Symbol      |        |     GameStatus      |
| X, O, EMPTY    |        | IN_PROGRESS,        |
+----------------+        | WIN, DRAW           |
                          +---------------------+

+----------------------+       +------------------------------+
|       Player         |       |            Move              |
+----------------------+       |  (immutable value object)    |
| - name: String       |       +------------------------------+
| - symbol: Symbol     |       | - row: int                   |
+----------------------+       | - col: int                   |
| + getSymbol(): Symbol|       | - symbol: Symbol             |
+----------------------+       +------------------------------+

+------------------------------+
|            Board             |
+------------------------------+
| - size: int                  |
| - grid: Symbol[][]           |
| - filledCells: int           |
+------------------------------+
| + placeSymbol(r,c,s): void   |   throws InvalidMoveException
| + getSymbol(r,c): Symbol     |
| + isFull(): boolean          |
| + getSize(): int             |
| + print(): String            |
+------------------------------+

+--------------------------------------+
|        <<interface>>                 |
|        WinningStrategy               |
+--------------------------------------+
| + isWinningMove(Board, Move): boolean|
+--------------------------------------+
                 ^
                 | implements
+--------------------------------------+
|   RowColumnDiagonalStrategy          |
|   (O(N) check around last move)      |
+--------------------------------------+

+----------------------------------------------+
|                    Game                      |
+----------------------------------------------+
| - board: Board                  (composition)|
| - players: List<Player>         (aggregation)|
| - strategy: WinningStrategy     (aggregation)|
| - currentPlayerIdx: int                      |
| - status: GameStatus                         |
| - winner: Player                             |
+----------------------------------------------+
| + makeMove(Player, row, col): GameStatus     |
| + getCurrentPlayer(): Player                 |
| + getStatus(): GameStatus                    |
| + getWinner(): Optional<Player>              |
+----------------------------------------------+

Multiplicity: Game "1" --- "2" Player
              Game "1" --- "1" Board
              Game "1" --- "1" WinningStrategy

ConsoleGameRunner --> Game   (UI depends on model; never the reverse)
```

### Design ↔ Concept Mapping

| Concept | Where & Why |
|---------|-------------|
| **SRP** | `Board` = grid state & cell validity. `Game` = turn/lifecycle orchestration. `WinningStrategy` = rules. UI = I/O. Each class has one reason to change. |
| **OCP** | New win rules (k-in-a-row, custom shapes) = new `WinningStrategy` implementation; `Game` is closed for modification. Same for bot players later. |
| **LSP** | Any `WinningStrategy` implementation is substitutable — `Game` calls only the interface contract. |
| **DIP** | `Game` depends on the `WinningStrategy` abstraction, injected via constructor — pure constructor injection, exactly what Spring's DI formalizes. In Spring Boot, `Game` would be a **prototype-scoped** bean (one per match), never a singleton, because it holds per-match mutable state. |
| **Strategy pattern** | Win detection varies independently of game orchestration. Fits because the *algorithm* is the variation point, chosen at construction time. |
| **State (mentioned, not used)** | `GameStatus` enum + guard clauses is sufficient here; a full State pattern (objects per state) is over-engineering for 3 states with trivial transitions — say this out loud, it scores points. |
| **Encapsulation** | `grid` is private; the only mutation path is `placeSymbol`, which validates. No setter exposes internals. |

### The two decisions an interviewer will probe

1. **Why no `Cell` class / why `Symbol[][]`?** → No behavior to encapsulate; YAGNI. Be ready to defend or evolve it.
2. **Why Strategy for winning, and why check only the last move?** → Variation point + O(N) instead of O(N²); only the last move can create a new win.

---

## Step 4 — Implementation (Java 11+)

```java
// ===== Symbol.java =====
public enum Symbol {
    X("X"), O("O"), EMPTY(" ");

    private final String display;
    Symbol(String display) { this.display = display; }
    public String display() { return display; }
}

// ===== GameStatus.java =====
public enum GameStatus { IN_PROGRESS, WIN, DRAW }

// ===== Player.java =====
public class Player {
    private final String name;
    private final Symbol symbol;

    public Player(String name, Symbol symbol) {
        if (symbol == Symbol.EMPTY)
            throw new IllegalArgumentException("Player cannot use EMPTY symbol");
        this.name = name;
        this.symbol = symbol;
    }
    public String getName()   { return name; }
    public Symbol getSymbol() { return symbol; }
}

// ===== Move.java =====
// Immutable value object: a fact about what happened, safe to share.
public final class Move {
    private final int row;
    private final int col;
    private final Symbol symbol;

    public Move(int row, int col, Symbol symbol) {
        this.row = row; this.col = col; this.symbol = symbol;
    }
    public int getRow()       { return row; }
    public int getCol()       { return col; }
    public Symbol getSymbol() { return symbol; }
}

// ===== InvalidMoveException.java =====
// Unchecked: an invalid move is a caller/user error, not a recoverable
// system condition. The UI catches it and re-prompts.
public class InvalidMoveException extends RuntimeException {
    public InvalidMoveException(String message) { super(message); }
}

// ===== Board.java =====
// Owns grid state. Validates CELL-level legality only (bounds, emptiness).
// Knows nothing about turns, players, or win rules (SRP).
public class Board {
    private final int size;
    private final Symbol[][] grid;
    private int filledCells; // O(1) draw detection instead of scanning

    public Board(int size) {
        if (size < 3) throw new IllegalArgumentException("Board size must be >= 3");
        this.size = size;
        this.grid = new Symbol[size][size];
        for (Symbol[] row : grid) java.util.Arrays.fill(row, Symbol.EMPTY);
    }

    public void placeSymbol(int row, int col, Symbol symbol) {
        if (!inBounds(row, col))
            throw new InvalidMoveException(
                "Position (" + row + "," + col + ") is off the board");
        if (grid[row][col] != Symbol.EMPTY)
            throw new InvalidMoveException(
                "Cell (" + row + "," + col + ") is already occupied");
        grid[row][col] = symbol;
        filledCells++;
    }

    public Symbol getSymbol(int row, int col) {
        if (!inBounds(row, col))
            throw new IllegalArgumentException("Out of bounds");
        return grid[row][col];
    }

    public boolean isFull()  { return filledCells == size * size; }
    public int getSize()     { return size; }
    private boolean inBounds(int r, int c) {
        return r >= 0 && r < size && c >= 0 && c < size;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                sb.append(' ').append(grid[r][c].display()).append(' ');
                if (c < size - 1) sb.append('|');
            }
            sb.append('\n');
            if (r < size - 1) sb.append("---+".repeat(size - 1)).append("---\n");
        }
        return sb.toString();
    }
}

// ===== WinningStrategy.java =====
// Strategy pattern: the win rule is the variation point (OCP seam).
public interface WinningStrategy {
    boolean isWinningMove(Board board, Move lastMove);
}

// ===== RowColumnDiagonalStrategy.java =====
// O(N) per move: only the row/column/diagonals through the LAST move
// can possibly contain a brand-new win.
public class RowColumnDiagonalStrategy implements WinningStrategy {

    @Override
    public boolean isWinningMove(Board board, Move m) {
        int n = board.getSize();
        Symbol s = m.getSymbol();

        boolean rowWin = true, colWin = true;
        for (int i = 0; i < n; i++) {
            if (board.getSymbol(m.getRow(), i) != s) rowWin = false;
            if (board.getSymbol(i, m.getCol()) != s) colWin = false;
        }
        if (rowWin || colWin) return true;

        // Main diagonal: only relevant if the move sits on it.
        if (m.getRow() == m.getCol()) {
            boolean diagWin = true;
            for (int i = 0; i < n; i++)
                if (board.getSymbol(i, i) != s) { diagWin = false; break; }
            if (diagWin) return true;
        }
        // Anti-diagonal.
        if (m.getRow() + m.getCol() == n - 1) {
            boolean antiWin = true;
            for (int i = 0; i < n; i++)
                if (board.getSymbol(i, n - 1 - i) != s) { antiWin = false; break; }
            if (antiWin) return true;
        }
        return false;
    }
}

// ===== Game.java =====
// Orchestrator: turn order + lifecycle. Delegates cell validity to Board
// and rule evaluation to WinningStrategy (DIP via constructor injection).
public class Game {
    private final Board board;
    private final java.util.List<Player> players;
    private final WinningStrategy strategy;
    private int currentPlayerIdx = 0;
    private GameStatus status = GameStatus.IN_PROGRESS;
    private Player winner;

    public Game(int boardSize, Player p1, Player p2, WinningStrategy strategy) {
        if (p1.getSymbol() == p2.getSymbol())
            throw new IllegalArgumentException("Players must have distinct symbols");
        this.board = new Board(boardSize);          // composition
        this.players = java.util.List.of(p1, p2);   // aggregation, immutable list
        this.strategy = java.util.Objects.requireNonNull(strategy);
    }

    /** Convenience factory for the classic 3x3 game. */
    public static Game classic(Player p1, Player p2) {
        return new Game(3, p1, p2, new RowColumnDiagonalStrategy());
    }

    public GameStatus makeMove(Player player, int row, int col) {
        // Guard clauses make the legal-state machine explicit.
        if (status != GameStatus.IN_PROGRESS)
            throw new InvalidMoveException("Game is over: " + status);
        if (player != getCurrentPlayer())
            throw new InvalidMoveException(
                "Not " + player.getName() + "'s turn");

        board.placeSymbol(row, col, player.getSymbol()); // may throw

        Move move = new Move(row, col, player.getSymbol());
        if (strategy.isWinningMove(board, move)) {
            status = GameStatus.WIN;
            winner = player;
        } else if (board.isFull()) {
            status = GameStatus.DRAW;
        } else {
            currentPlayerIdx = (currentPlayerIdx + 1) % players.size();
        }
        return status;
    }

    public Player getCurrentPlayer() { return players.get(currentPlayerIdx); }
    public GameStatus getStatus()    { return status; }
    public Board getBoard()          { return board; }
    public java.util.Optional<Player> getWinner() {
        return java.util.Optional.ofNullable(winner);
    }
}

// ===== ConsoleGameRunner.java =====
// The ONLY class that knows about System.in/out. Model stays testable.
public class ConsoleGameRunner {
    public static void main(String[] args) {
        Player alice = new Player("Alice", Symbol.X);
        Player bob   = new Player("Bob",   Symbol.O);
        Game game = Game.classic(alice, bob);

        try (java.util.Scanner in = new java.util.Scanner(System.in)) {
            while (game.getStatus() == GameStatus.IN_PROGRESS) {
                System.out.println(game.getBoard().render());
                Player current = game.getCurrentPlayer();
                System.out.printf("%s (%s) — enter row col: ",
                        current.getName(), current.getSymbol().display());
                try {
                    int row = in.nextInt();
                    int col = in.nextInt();
                    game.makeMove(current, row, col);
                } catch (InvalidMoveException e) {
                    System.out.println("Invalid move: " + e.getMessage());
                } catch (java.util.InputMismatchException e) {
                    System.out.println("Please enter two integers.");
                    in.nextLine(); // discard bad token
                }
            }
            System.out.println(game.getBoard().render());
            System.out.println(game.getWinner()
                    .map(w -> w.getName() + " wins!")
                    .orElse("It's a draw!"));
        }
    }
}
```

**Spring Boot aside (one breath, then drop it):** the `Game(…, WinningStrategy)` constructor is textbook constructor injection. In a web variant, `WinningStrategy` would be a stateless `@Component` (singleton-safe), while `Game` holds per-match state, so it must be created per match (prototype scope or, more realistically, a plain `new` managed by a `GameService` holding a `ConcurrentHashMap<gameId, Game>`).

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Invalid input

| Case | Handling | Where |
|------|----------|-------|
| Out-of-bounds coordinates | `InvalidMoveException` with coordinates in message | `Board.placeSymbol` |
| Occupied cell | `InvalidMoveException` | `Board.placeSymbol` |
| Move out of turn | `InvalidMoveException` | `Game.makeMove` guard |
| Move after game over | `InvalidMoveException("Game is over")` | `Game.makeMove` guard |
| Two players, same symbol | `IllegalArgumentException` — construction-time bug, fail fast | `Game` constructor |
| Player with `EMPTY` symbol | `IllegalArgumentException` | `Player` constructor |
| Board size < 3 | `IllegalArgumentException` | `Board` constructor |
| Non-numeric console input | Caught at UI; re-prompt | `ConsoleGameRunner` |

**Design point:** *cell-level* validity lives in `Board`; *game-level* validity (turn, lifecycle) lives in `Game`. Validation sits with the class that owns the invariant. All move errors are unchecked — user mistakes, not system failures — and the model never prints; only the UI does.

### Edge cases

- **Winning move that also fills the board**: win is checked *before* draw — `WIN` correctly takes precedence (order of the if/else in `makeMove` matters; classic interview gotcha).
- **First move / corner moves on diagonals**: the diagonal checks fire only when the move lies on a diagonal — no false positives.
- **No further state change after terminal status**: guard clause makes `Game` effectively immutable once finished.
- **Win on the very last (9th) cell vs. draw**: covered by the precedence above — worth a unit test.
- **Graceful degradation**: a rejected move never mutates state — `placeSymbol` validates before writing, so the board can never be half-updated.

### Concurrency

**Local 2-player game:** single-threaded; no shared mutable state across threads → no synchronization. Adding locks here would be speculative complexity. *Say this explicitly — knowing when NOT to synchronize is a senior signal.*

**If this becomes a multiplayer server (two clients, two request threads):**

- **Shared mutable state:** `Board.grid`, `Board.filledCells`, `Game.currentPlayerIdx`, `Game.status`, `Game.winner`.
- **Critical section:** the entire `makeMove` — validate-turn → place → evaluate → transition must be atomic. A check-then-act race exists otherwise: both players' threads could pass the turn check before either places.
- **Primitive of choice:** `synchronized makeMove(...)` (or one `ReentrantLock` per `Game`). Contention is two threads on one object — a coarse lock is correct and trivially arguable:
  - **Race-free:** every read/write of game state happens inside the single monitor.
  - **Deadlock-free:** exactly one lock per game; no nested lock acquisition; lock ordering trivially holds.
  - **Livelock/starvation:** turn alternation means threads naturally take turns; `ReentrantLock(true)` (fair) if you must guarantee FIFO.
- **What NOT to do:** per-cell locks or `AtomicReference<Symbol>[][]` — finer granularity buys nothing (a move touches turn state AND grid AND status together; atomicity must span all of them) and reintroduces compound-action races.
- **Many concurrent games:** `ConcurrentHashMap<GameId, Game>` in a `GameService`; each game still uses its own coarse lock. Independent games never contend — this scales linearly.

