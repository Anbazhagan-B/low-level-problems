# Chess Game — Low-Level Design

**Tier:** Hard (rule-modeling complexity, not concurrency complexity)
**Scope locked in:** All six piece types, turn enforcement, check / checkmate / stalemate, pawn promotion, resignation, move history. Castling and en passant are *acknowledged in the design* (hooks shown) but not implemented. No clocks, no repetition/fifty-move draws.

> **Why this problem matters for interviews:** Chess is the canonical test of *polymorphism and responsibility placement*. The trap is a god-class `Board` (or `Game`) with a 500-line `validateMove()` switch statement. The win is layered validation: each piece knows its own geometry, the board knows occupancy and paths, and the game knows turns and check rules. Interviewers use this problem to see whether you can decompose rules, not whether you know chess.

---

## Step 1 — Requirements

### Functional Requirements

1. **Standard rules, two players.** White and Black, 16 pieces each (1 K, 1 Q, 2 R, 2 B, 2 N, 8 P), on an 8×8 board.
2. **Turn management.** White moves first; turns alternate strictly; a player may only move pieces of their own color.
3. **Move validation.** A move is rejected if:
   - source square is empty, or holds an opponent's piece
   - destination holds a piece of the mover's own color
   - the move violates that piece's movement geometry
   - the path is blocked (for sliding pieces: rook, bishop, queen)
   - the move would leave the mover's own king in check (**the rule most candidates forget**)
4. **Check detection.** After every move, determine whether the opponent's king is under attack.
5. **Checkmate.** Opponent is in check AND has no legal move → game over, mover wins.
6. **Stalemate.** Opponent is NOT in check but has no legal move → draw.
7. **Pawn promotion.** A pawn reaching the last rank is replaced by a piece of the player's choice (default: queen).
8. **Resignation.** Either player may resign; the opponent wins.
9. **Game lifecycle.** Status: `ACTIVE → WHITE_WIN | BLACK_WIN | STALEMATE`. No moves accepted after the game ends.
10. **Thin interface.** A programmatic API (`game.makeMove(player, from, to)`) decoupled from any rendering. A console renderer is a trivial add-on, not part of the core design.

### Non-Functional Requirements

- **Extensibility (OCP showcase).** Adding a new piece type (fairy chess: Archbishop = bishop + knight) must require *zero changes* to `Board` or `Game` — only a new `Piece` subclass. This is the single most probed NFR in this problem.
- **Correctness over performance.** The board has 64 squares. O(64) or even O(64²) scans per move are microseconds. Bitboards, move caches, and Zobrist hashing are chess-*engine* concerns, not LLD concerns — *say this out loud in the interview* to show judgment.
- **Concurrency.** Minimal by nature: one game is a strictly alternating sequence — there is no legal concurrent mutation *within* a game. The interesting concurrency story is a **game server hosting many games** (Section 5).
- **Testability.** Move legality must be a pure function of board state — checkable without running a game loop. This falls out naturally from layered validation.

---

## Step 2 — Entities & Relationships

> **Socratic note (do this from memory before reading on):** try listing the entities yourself. The two classic mistakes here are (a) a `PieceType` enum + giant switch instead of a `Piece` class hierarchy — this kills polymorphism; and (b) modeling `Square`/`Cell` as a mere `int[]` pair instead of an object that *holds* a piece — which scatters occupancy logic everywhere.

| Entity | Kind | Responsibility |
|---|---|---|
| `Color` | enum | WHITE / BLACK, with `opposite()` |
| `GameStatus` | enum | ACTIVE, WHITE_WIN, BLACK_WIN, STALEMATE — enum-guarded lifecycle, same pattern as `BookingStatus`/`AuctionStatus` |
| `Piece` | abstract class | color, `hasMoved` flag, **abstract `canMove(board, from, to)`** — geometry + path only, *never* check-awareness |
| `King, Queen, Rook, Bishop, Knight, Pawn` | concrete classes | one geometry rule each |
| `Cell` | class | (row, col) + the `Piece` currently on it (nullable) |
| `Board` | class | 8×8 grid of cells; occupancy queries: `isPathClear`, `findKing`, `isSquareAttacked`; initial setup |
| `Move` | immutable value object | from, to, moved piece, captured piece, promotion flag — the audit-trail record |
| `Player` | class | name + color (kept deliberately thin) |
| `Game` | class | orchestrator: board + players + turn + status + history; owns *check-aware* validation and endgame detection |
| `PieceFactory` | class | creates the standard back rank + pawns; isolates setup from `Board` |
| `ChessException` hierarchy | exceptions | `InvalidMoveException`, `WrongTurnException`, `GameOverException` |

### Relationships (with type and why)

- **`Board` ◆— `Cell` (composition, 1 → 64).** Cells have no identity or lifetime outside their board. Destroy the board, the cells are meaningless. Strongest ownership.
- **`Game` ◆— `Board` (composition, 1 → 1).** Each game owns exactly one board; the board is not shared across games.
- **`Game` ◇— `Player` (aggregation, 1 → 2).** Players exist independently of any single game (the same player object could be in a tournament's player registry). Game references but does not own them.
- **`Cell` —→ `Piece` (association, 0..1).** A cell *holds* a piece temporarily; pieces move between cells and can be captured (removed) without the cell dying. Not composition — the lifetimes are independent.
- **`King…Pawn` —▷ `Piece` (inheritance).** Pure subtype polymorphism: every concrete piece *is-a* Piece and is substitutable wherever `Piece` is expected (LSP).
- **`Game` ◆— `Move` history (composition, 1 → *).** Moves are records created by and owned by the game. This list is the hook for undo, castling legality (`hasMoved`), en passant (last move was a double pawn push), and threefold repetition.
- **`Piece.canMove` ⇢ `Board` (dependency).** Pieces *consult* the board (path clearness, occupancy) but never store a reference to it — passed as a method parameter. Keeping pieces board-stateless makes them trivially reusable and testable.

### Deliberate under-modeling (say this explicitly in the interview)

- **No `Square` color field used in logic.** Light/dark squares are pure rendering; deriving `(row + col) % 2` at render time beats storing it.
- **No `Capture` entity.** A capture is just a `Move` with a non-null `capturedPiece`.
- **No `Team`/`PieceSet` entity.** A player's pieces are *derivable* by scanning the board for their color — same principle as Car Rental, where availability was derived from reservations rather than stored as a flag.

---

## Step 3 — UML Class Design

```
                        «enum» Color            «enum» GameStatus
                        WHITE, BLACK            ACTIVE, WHITE_WIN,
                        + opposite()            BLACK_WIN, STALEMATE

 ┌────────────────────────── «abstract» Piece ──────────────────────────┐
 │ # color : Color                                                      │
 │ # hasMoved : boolean        ← hook for castling & pawn double-step   │
 │ + canMove(board, from, to) : boolean   «abstract»  (geometry only)   │
 │ + symbol() : char                       «abstract»                   │
 └──────────────────────────────────────────────────────────────────────┘
        △            △           △           △           △          △
        │            │           │           │           │          │
      King        Queen        Rook       Bishop      Knight      Pawn
                 (delegates to rook∨bishop geometry — composition of rules)

 ┌──── Cell ────────────┐         ┌──── Board ─────────────────────────────┐
 │ - row, col : int     │ 64    1 │ - grid : Cell[8][8]                    │
 │ - piece : Piece 0..1 │ ◆───────│ + getCell(r,c) : Cell                  │
 │ + isEmpty()          │         │ + isPathClear(from, to) : boolean      │
 └──────────────────────┘         │ + findKing(color) : Cell               │
                                  │ + isSquareAttacked(cell, by) : boolean │
                                  │ + resetBoard(factory)                  │
                                  └────────────────────────────────────────┘
                                                    △ 1
                                                    ◆
 ┌──── Game ──────────────────────────────────────────────────────────────┐
 │ - board : Board                 (composition)                          │
 │ - players : Player[2]           (aggregation)                          │
 │ - currentTurn : Color                                                  │
 │ - status : GameStatus           (volatile — see §5)                    │
 │ - history : List<Move>          (composition)                          │
 │ + makeMove(player, fromR, fromC, toR, toC, promotion) : Move           │
 │ + resign(player)                                                       │
 │ - isInCheck(color) : boolean                                           │
 │ - hasAnyLegalMove(color) : boolean                                     │
 │ - leavesKingInCheck(from, to, mover) : boolean   (make–unmake)         │
 └────────────────────────────────────────────────────────────────────────┘

 Move (immutable): from, to, movedPiece, capturedPiece, promotedTo?
 PieceFactory: + createBackRank(color), + createPawn(color)
```

### Pattern mapping — and exactly *why* each fits

**1. Polymorphic movement — Strategy realized through inheritance.**
Each subclass overrides `canMove`; the engine calls `piece.canMove(...)` with zero knowledge of piece type. *Why it fits:* movement is the axis of variation; encapsulating each variant behind one interface means adding an Archbishop touches no existing class — textbook OCP.

> **Interview nuance — inheritance vs. composition for Strategy.** The "purer" GoF Strategy injects a `MovementStrategy` object into a single concrete `Piece` class (`new Piece(color, new RookMovement())`). Trade-off: composition lets Queen *literally reuse* `RookMovement ∨ BishopMovement` and allows runtime swapping (promotion becomes a strategy swap instead of object replacement!). Inheritance is simpler and matches how interviewers expect the answer. **Recommended answer:** implement with inheritance, *mention* the composition alternative and that promotion is its strongest argument. That one sentence is worth a lot.

**2. Factory — `PieceFactory` for board setup.**
*Why it fits:* initial-position construction is volatile, verbose knowledge (which piece on which file) that doesn't belong in `Board` (whose job is occupancy, not chess opening positions). Also the single seam for variants (Chess960 = a different factory).

**3. Enum-guarded State (lightweight State pattern).**
`GameStatus` + a single guard (`assertActive()`) — identical to your `BookingStatus`/`AuctionStatus` precedent. *Why not full GoF State:* the lifecycle is a one-way fan-out from ACTIVE with no per-state behavioral variation; six state classes would be ceremony without payoff. Say exactly that when probed.

**4. Command (designed-for, not implemented).**
`Move` is already an immutable command record holding everything needed to undo (`capturedPiece`, `promotedTo`). Implementing `undo()` is replaying the record backwards. *Why it fits:* undo/redo and move replay (PGN export) are pure Command use cases; storing history as command objects costs nothing now.

**5. Observer (mentioned, not implemented).**
A `GameObserver { onMove, onGameEnd }` lets UIs/loggers/clock-services subscribe without `Game` knowing them — same role Observer played in your Auction and LinkedIn designs. For a two-player console game it's optional; for a game server it's how spectators work.

### SOLID mapping

- **S:** Piece = geometry; Board = occupancy/topology; Game = turn order + check rules + lifecycle. Three reasons to change, three classes.
- **O:** new piece ⇒ new subclass only. New setup ⇒ new factory. Engine untouched.
- **L:** every subclass honors the `canMove` contract (pure function: no side effects, no check-awareness). A `Pawn` is substitutable anywhere a `Piece` is.
- **I:** pieces depend only on the two `Board` query methods they need — not on `Game`. (If we extracted a `BoardView` read-only interface, pieces would depend on that; worth mentioning.)
- **D:** `Game` depends on the `Piece` abstraction, never on concrete piece types. Promotion is the *one* place a concrete type (`Queen`) appears — isolated behind the factory.

### The two decisions an interviewer will probe hardest

**Probe 1 — "Where does validation live?"** Answer: it's *layered*, and naming the layers is the answer:
1. **Game layer:** game active? your turn? your piece? destination not your own piece?
2. **Piece layer:** geometry + (via board queries) path clearness — *check-unaware by design*.
3. **Game layer again:** simulate the move; does it leave your king in check? (Pieces can't know this — it requires whole-board knowledge, which would violate SRP and create a Piece→Game cycle.)

**Probe 2 — "How do you detect checkmate without exploding complexity?"** Answer: `isInCheck(color)` = "is the king's cell attacked by any enemy piece" (one O(64) scan reusing `canMove` — attack detection is *free* once movement is polymorphic). `hasAnyLegalMove(color)` = for each of the player's pieces × 64 targets: geometric-legal ∧ ¬leaves-king-in-check. Worst case ~16×64 simulations ≈ 1k cheap operations. Checkmate = inCheck ∧ ¬hasAnyLegalMove; stalemate = ¬inCheck ∧ ¬hasAnyLegalMove. **One helper serves both endings — that symmetry is the elegant part; lead with it.**

---

## Step 4 — Implementation

```java
// ───────────────────────── enums ─────────────────────────
public enum Color {
    WHITE, BLACK;
    public Color opposite() { return this == WHITE ? BLACK : WHITE; }
}

public enum GameStatus { ACTIVE, WHITE_WIN, BLACK_WIN, STALEMATE }
```

```java
// ───────────────────────── exceptions ─────────────────────────
public class ChessException extends RuntimeException {
    public ChessException(String msg) { super(msg); }
}
public class InvalidMoveException extends ChessException {
    public InvalidMoveException(String msg) { super(msg); }
}
public class WrongTurnException extends ChessException {
    public WrongTurnException(String msg) { super(msg); }
}
public class GameOverException extends ChessException {
    public GameOverException(String msg) { super(msg); }
}
```

```java
// ───────────────────────── Cell ─────────────────────────
public class Cell {
    private final int row;   // 0..7 (rank), 0 = White's back rank
    private final int col;   // 0..7 (file)
    private Piece piece;     // nullable — association, not composition

    public Cell(int row, int col) { this.row = row; this.col = col; }

    public int getRow() { return row; }
    public int getCol() { return col; }
    public Piece getPiece() { return piece; }
    public void setPiece(Piece piece) { this.piece = piece; }
    public boolean isEmpty() { return piece == null; }
}
```

```java
// ───────────────────────── Piece hierarchy ─────────────────────────
public abstract class Piece {
    protected final Color color;
    protected boolean hasMoved; // hook: castling legality, pawn double-step

    protected Piece(Color color) { this.color = color; }

    public Color getColor() { return color; }
    public boolean hasMoved() { return hasMoved; }
    public void markMoved() { this.hasMoved = true; }

    /**
     * Geometry + path legality ONLY. Deliberately check-unaware:
     * "does this move leave my king in check" needs whole-board knowledge
     * and belongs to Game (SRP). Pure function of (board, from, to) —
     * no side effects, which is what makes attack-scanning reusable.
     */
    public abstract boolean canMove(Board board, Cell from, Cell to);

    public abstract char symbol(); // uppercase; renderer lowercases for black
}

public class Rook extends Piece {
    public Rook(Color color) { super(color); }

    @Override
    public boolean canMove(Board board, Cell from, Cell to) {
        boolean straightLine = from.getRow() == to.getRow()
                            || from.getCol() == to.getCol();
        return straightLine && board.isPathClear(from, to);
    }

    @Override
    public char symbol() { return 'R'; }
}

public class Bishop extends Piece {
    public Bishop(Color color) { super(color); }

    @Override
    public boolean canMove(Board board, Cell from, Cell to) {
        boolean diagonal = Math.abs(from.getRow() - to.getRow())
                        == Math.abs(from.getCol() - to.getCol());
        return diagonal && board.isPathClear(from, to);
    }

    @Override
    public char symbol() { return 'B'; }
}

public class Queen extends Piece {
    public Queen(Color color) { super(color); }

    @Override
    public boolean canMove(Board board, Cell from, Cell to) {
        // Queen = rook-geometry OR bishop-geometry. Duplicating the two
        // predicates inline is acceptable; extracting shared static helpers
        // (or full Strategy composition) removes it. Mention, don't belabor.
        int dr = Math.abs(from.getRow() - to.getRow());
        int dc = Math.abs(from.getCol() - to.getCol());
        boolean straightLine = from.getRow() == to.getRow()
                            || from.getCol() == to.getCol();
        boolean diagonal = dr == dc;
        return (straightLine || diagonal) && board.isPathClear(from, to);
    }

    @Override
    public char symbol() { return 'Q'; }
}

public class Knight extends Piece {
    public Knight(Color color) { super(color); }

    @Override
    public boolean canMove(Board board, Cell from, Cell to) {
        int dr = Math.abs(from.getRow() - to.getRow());
        int dc = Math.abs(from.getCol() - to.getCol());
        return dr * dc == 2;   // (1,2) or (2,1) — jumps, so NO path check
    }

    @Override
    public char symbol() { return 'N'; }
}

public class King extends Piece {
    public King(Color color) { super(color); }

    @Override
    public boolean canMove(Board board, Cell from, Cell to) {
        int dr = Math.abs(from.getRow() - to.getRow());
        int dc = Math.abs(from.getCol() - to.getCol());
        boolean oneStep = Math.max(dr, dc) == 1;
        // Castling hook: dr==0 && dc==2 && !hasMoved && rook !hasMoved
        // && path clear && king does not pass THROUGH an attacked square.
        // Requires hasMoved (already modeled) + isSquareAttacked (exists).
        return oneStep;
    }

    @Override
    public char symbol() { return 'K'; }
}

public class Pawn extends Piece {
    public Pawn(Color color) { super(color); }

    @Override
    public boolean canMove(Board board, Cell from, Cell to) {
        int dir = (color == Color.WHITE) ? 1 : -1;  // White moves up the ranks
        int dr = to.getRow() - from.getRow();        // SIGNED — pawns can't retreat
        int dc = Math.abs(to.getCol() - from.getCol());

        // forward push: must land on an EMPTY square (pawns capture differently
        // than they move — the only piece for which this is true)
        if (dc == 0 && dr == dir && to.isEmpty()) return true;

        // double push from the starting rank: both squares must be empty
        if (dc == 0 && dr == 2 * dir && !hasMoved && to.isEmpty()
                && board.isPathClear(from, to)) return true;

        // diagonal capture: destination MUST hold an enemy piece.
        // (En passant hook: would also allow this when the last Move in
        // history was an adjacent enemy double-push — needs Game.history.)
        return dc == 1 && dr == dir && !to.isEmpty()
                && to.getPiece().getColor() != color;
    }

    @Override
    public char symbol() { return 'P'; }
}
```

```java
// ───────────────────────── PieceFactory ─────────────────────────
public class PieceFactory {
    /** Back rank in file order: R N B Q K B N R. Single seam for variants. */
    public Piece[] createBackRank(Color color) {
        return new Piece[] {
            new Rook(color), new Knight(color), new Bishop(color),
            new Queen(color), new King(color),
            new Bishop(color), new Knight(color), new Rook(color)
        };
    }

    public Piece createPawn(Color color) { return new Pawn(color); }

    /** Promotion: the one place concrete types meet the engine. */
    public Piece createPromoted(char symbol, Color color) {
        switch (Character.toUpperCase(symbol)) {
            case 'Q': return new Queen(color);
            case 'R': return new Rook(color);
            case 'B': return new Bishop(color);
            case 'N': return new Knight(color);
            default: throw new InvalidMoveException(
                "Cannot promote to '" + symbol + "' (Q/R/B/N only)");
        }
    }
}
```

```java
// ───────────────────────── Board ─────────────────────────
public class Board {
    public static final int SIZE = 8;
    private final Cell[][] grid = new Cell[SIZE][SIZE];

    public Board(PieceFactory factory) {
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                grid[r][c] = new Cell(r, c);

        Piece[] whiteBack = factory.createBackRank(Color.WHITE);
        Piece[] blackBack = factory.createBackRank(Color.BLACK);
        for (int c = 0; c < SIZE; c++) {
            grid[0][c].setPiece(whiteBack[c]);
            grid[1][c].setPiece(factory.createPawn(Color.WHITE));
            grid[6][c].setPiece(factory.createPawn(Color.BLACK));
            grid[7][c].setPiece(blackBack[c]);
        }
    }

    public Cell getCell(int row, int col) {
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE)
            throw new InvalidMoveException(
                "Coordinates off board: (" + row + "," + col + ")");
        return grid[row][col];
    }

    /** Squares strictly BETWEEN from and to are empty (rook/bishop/queen,
     *  pawn double-push). Endpoints are the caller's concern. */
    public boolean isPathClear(Cell from, Cell to) {
        int dr = Integer.signum(to.getRow() - from.getRow());
        int dc = Integer.signum(to.getCol() - from.getCol());
        int r = from.getRow() + dr, c = from.getCol() + dc;
        while (r != to.getRow() || c != to.getCol()) {
            if (!grid[r][c].isEmpty()) return false;
            r += dr; c += dc;
        }
        return true;
    }

    public Cell findKing(Color color) {
        for (Cell[] rank : grid)
            for (Cell cell : rank)
                if (cell.getPiece() instanceof King
                        && cell.getPiece().getColor() == color)
                    return cell;
        throw new IllegalStateException("No " + color + " king — corrupt board");
    }

    /**
     * Is `target` attacked by any piece of `byColor`?
     * KEY INSIGHT: attack detection reuses canMove — once movement is
     * polymorphic, check detection costs one O(64) scan and zero new logic.
     */
    public boolean isSquareAttacked(Cell target, Color byColor) {
        for (Cell[] rank : grid)
            for (Cell cell : rank) {
                Piece p = cell.getPiece();
                if (p != null && p.getColor() == byColor
                        && p.canMove(this, cell, target))
                    return true;
            }
        return false;
    }
}
```

```java
// ───────────────────────── Move (immutable Command record) ────────────────
public final class Move {
    private final Cell from, to;
    private final Piece moved;
    private final Piece captured;      // nullable
    private final Piece promotedTo;    // nullable

    public Move(Cell from, Cell to, Piece moved, Piece captured, Piece promotedTo) {
        this.from = from; this.to = to;
        this.moved = moved; this.captured = captured; this.promotedTo = promotedTo;
    }
    public Cell getFrom() { return from; }
    public Cell getTo() { return to; }
    public Piece getMoved() { return moved; }
    public Piece getCaptured() { return captured; }
    public boolean isCapture() { return captured != null; }
    // Everything needed for undo() lives here — Command pattern, designed-for.
}
```

```java
// ───────────────────────── Player ─────────────────────────
public class Player {
    private final String name;
    private final Color color;
    public Player(String name, Color color) { this.name = name; this.color = color; }
    public String getName() { return name; }
    public Color getColor() { return color; }
}
```

```java
// ───────────────────────── Game (orchestrator) ─────────────────────────
import java.util.ArrayList;
import java.util.List;

public class Game {
    private final Board board;
    private final PieceFactory factory;
    private final Player white, black;
    private final List<Move> history = new ArrayList<>();

    private Color currentTurn = Color.WHITE;
    private volatile GameStatus status = GameStatus.ACTIVE; // spectator-visible

    public Game(Player white, Player black) {
        this.factory = new PieceFactory();
        this.board = new Board(factory);
        this.white = white;
        this.black = black;
    }

    /**
     * Single entry point. synchronized = one coarse lock per GAME instance —
     * see §5 for why coarse is the RIGHT grain here, unlike Parking Lot.
     * promotionChoice: 'Q'/'R'/'B'/'N'; ignored unless this move promotes.
     */
    public synchronized Move makeMove(Player player, int fromRow, int fromCol,
                                      int toRow, int toCol, char promotionChoice) {
        // ── Layer 1: game-level guards ──
        assertActive();
        if (player.getColor() != currentTurn)
            throw new WrongTurnException("It is " + currentTurn + "'s turn");

        Cell from = board.getCell(fromRow, fromCol);   // throws if off-board
        Cell to   = board.getCell(toRow, toCol);
        Piece piece = from.getPiece();

        if (piece == null)
            throw new InvalidMoveException("No piece at source square");
        if (piece.getColor() != currentTurn)
            throw new InvalidMoveException("That piece belongs to your opponent");
        if (from == to)
            throw new InvalidMoveException("Source and destination are the same");
        if (!to.isEmpty() && to.getPiece().getColor() == currentTurn)
            throw new InvalidMoveException("Destination holds your own piece");

        // ── Layer 2: piece geometry (polymorphic dispatch) ──
        if (!piece.canMove(board, from, to))
            throw new InvalidMoveException(
                piece.getClass().getSimpleName() + " cannot move that way");

        // ── Layer 3: self-check (the rule everyone forgets) ──
        if (leavesKingInCheck(from, to, currentTurn))
            throw new InvalidMoveException("Move would leave your king in check");

        // ── Execute ──
        Piece captured = to.getPiece();        // may be null
        to.setPiece(piece);
        from.setPiece(null);
        piece.markMoved();

        Piece promoted = maybePromote(to, promotionChoice);
        Move move = new Move(from, to, piece, captured, promoted);
        history.add(move);

        // ── Endgame detection for the OPPONENT ──
        Color opponent = currentTurn.opposite();
        boolean inCheck = isInCheck(opponent);
        boolean hasMoves = hasAnyLegalMove(opponent);
        if (inCheck && !hasMoves) {
            status = (currentTurn == Color.WHITE)
                    ? GameStatus.WHITE_WIN : GameStatus.BLACK_WIN;   // checkmate
        } else if (!inCheck && !hasMoves) {
            status = GameStatus.STALEMATE;                            // draw
        } else {
            currentTurn = opponent;
        }
        return move;
    }

    public synchronized void resign(Player player) {
        assertActive();
        status = (player.getColor() == Color.WHITE)
                ? GameStatus.BLACK_WIN : GameStatus.WHITE_WIN;
    }

    // ───────── internals ─────────

    private void assertActive() {
        if (status != GameStatus.ACTIVE)
            throw new GameOverException("Game already finished: " + status);
    }

    private boolean isInCheck(Color color) {
        Cell king = board.findKing(color);
        return board.isSquareAttacked(king, color.opposite());
    }

    /**
     * MAKE–UNMAKE simulation: mutate, test, restore. Cheaper and simpler
     * than deep-copying the board, but it MUST restore on every path and
     * must NOT call markMoved() — only the real move flips hasMoved.
     * (Deep-copy is the safer answer in a concurrent engine; here the
     * surrounding lock makes make–unmake invisible to other threads.)
     */
    private boolean leavesKingInCheck(Cell from, Cell to, Color mover) {
        Piece moving = from.getPiece();
        Piece captured = to.getPiece();
        to.setPiece(moving);
        from.setPiece(null);
        try {
            return isInCheck(mover);
        } finally {
            from.setPiece(moving);   // ALWAYS restore — finally, not happy-path
            to.setPiece(captured);
        }
    }

    /** Serves BOTH checkmate and stalemate. ~16 pieces × 64 targets, trivial. */
    private boolean hasAnyLegalMove(Color color) {
        for (int r = 0; r < Board.SIZE; r++)
            for (int c = 0; c < Board.SIZE; c++) {
                Cell from = board.getCell(r, c);
                Piece p = from.getPiece();
                if (p == null || p.getColor() != color) continue;

                for (int tr = 0; tr < Board.SIZE; tr++)
                    for (int tc = 0; tc < Board.SIZE; tc++) {
                        Cell to = board.getCell(tr, tc);
                        if (from == to) continue;
                        if (!to.isEmpty() && to.getPiece().getColor() == color) continue;
                        if (p.canMove(board, from, to)
                                && !leavesKingInCheck(from, to, color))
                            return true;   // short-circuit: one is enough
                    }
            }
        return false;
    }

    private Piece maybePromote(Cell to, char choice) {
        Piece p = to.getPiece();
        boolean lastRank = (p.getColor() == Color.WHITE && to.getRow() == 7)
                        || (p.getColor() == Color.BLACK && to.getRow() == 0);
        if (p instanceof Pawn && lastRank) {
            Piece promoted = factory.createPromoted(
                choice == 0 ? 'Q' : choice, p.getColor());  // default: queen
            to.setPiece(promoted);
            return promoted;
        }
        return null;
    }

    public GameStatus getStatus() { return status; }
    public Color getCurrentTurn() { return currentTurn; }
    public List<Move> getHistory() { return List.copyOf(history); } // defensive
}
```

```java
// ───────────────────────── thin console driver ─────────────────────────
public class ChessDemo {
    public static void main(String[] args) {
        Game game = new Game(new Player("Anbu", Color.WHITE),
                             new Player("Magnus", Color.BLACK));

        game.makeMove(/* white */ gameWhite(game), 1, 4, 3, 4, 'Q'); // e2→e4
        // ... UI loop: read "e2e4", convert to coords, catch ChessException,
        //     re-prompt. Rendering = iterate board, print symbol()/lowercase.
    }
    private static Player gameWhite(Game g) { /* lookup omitted */ return null; }
}
```

**Spring Boot aside (one sentence each, framework-agnostic core intact):** `Game` must be a **prototype-scoped** bean (or better, created per-match by a `GameService`), never the default singleton — one bean instance would make every user play the same game. `PieceFactory` *is* a natural singleton bean (stateless). A `GameService` holding `ConcurrentHashMap<String, Game>` is your `@Service`.

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Exception strategy

| Failure | Exception | Why unchecked |
|---|---|---|
| Off-board coordinates | `InvalidMoveException` | caller bug / bad input — recover by re-prompting |
| Empty source / opponent's piece / own piece at destination | `InvalidMoveException` | same |
| Geometry or path violation | `InvalidMoveException` | same |
| Move leaves own king in check | `InvalidMoveException` | same — distinct message, same recovery |
| Out of turn | `WrongTurnException` | distinct type: a UI may want to handle it differently (e.g., queue it) |
| Move after game ended | `GameOverException` | terminal — UI should stop accepting input |
| No king on board | `IllegalStateException` | invariant violation = programming error, not user error |

All extend `ChessException extends RuntimeException` — consistent with your Wallet/Airline designs: user-recoverable rule violations are unchecked with precise messages; the UI loop catches `ChessException` and re-prompts. **Validate-then-execute**: every guard runs before any mutation, so a rejected move leaves the board untouched — no compensation needed (contrast with multi-seat booking, where partial claims forced a compensation pattern).

### Edge cases (the checklist interviewers fish for)

1. **Self-check via discovered attack** — moving a pinned piece exposes your king. Handled *uniformly* by the simulation in Layer 3; no special "pin detection" logic needed. Candidates who try to detect pins explicitly are over-engineering.
2. **Blocked path vs. knight** — knight is the only jumper; every slider must call `isPathClear`. Forgetting it for the pawn's *double push* is the classic miss.
3. **Pawn asymmetries** — moves ≠ captures (push needs empty square, capture needs enemy piece); direction is color-signed (`dr` must be *signed*, not `abs`); double push only when `!hasMoved`.
4. **Promotion** — must happen atomically within the same move, *before* check/checkmate evaluation (a promoted queen can deliver mate on arrival). Invalid promotion symbol → reject the whole move.
5. **Checkmate vs. stalemate** — same `hasAnyLegalMove` scan, opposite `isInCheck` flag. Getting stalemate wrong (calling it a win) is an instant red flag.
6. **Kings can never be adjacent** — falls out for free: moving next to the enemy king fails Layer 3, because the enemy king "attacks" that square per `King.canMove`.
7. **The king is never actually captured** — Layer 3 makes it impossible to *leave* a king en prise; "capture the king" is not a code path. If `findKing` ever fails, the board is corrupt → `IllegalStateException`.
8. **make–unmake must restore in `finally`** — and must not flip `hasMoved` during simulation, or you silently break the pawn double-push and (future) castling rights.
9. **Same-square "move"** (`from == to`) — explicitly rejected; several geometry predicates would wrongly return true for it (e.g., rook: same row ✓).

### Concurrency

**The honest headline: a single chess game is *intrinsically sequential*.** Turns strictly alternate, so there is never a legal concurrent mutation. Saying this plainly scores better than inventing fine-grained locks.

- **Shared mutable state:** the `Cell.piece` references (64 of them), `currentTurn`, `status`, `history`.
- **Critical section:** the *entire* `makeMove` — validate→simulate→execute→detect-mate must be atomic. The make–unmake simulation **temporarily corrupts the board**; if a spectator thread read mid-simulation it would see a phantom position. That is the decisive argument for a coarse per-game lock.
- **Primitive chosen:** `synchronized` on the `Game` instance (one lock per game). `volatile status` lets lock-free spectator reads of "is it over" stay current.
- **Why coarse-grained is *correct* here — the inversion of your usual pattern:** in Parking Lot / Concert Ticket / Wallet, per-resource locks won because many independent transactions contend on *different* resources. In chess, every move reads/writes the *same* one resource (the whole board: path checks and check detection are global scans), and at most two threads ever touch a game. Per-cell locks would mean acquiring up to 64 locks in order — pure deadlock surface, zero parallelism gained. **Lock granularity must match contention granularity** — this problem is the canonical example of when coarse wins.
- **Scaling to a game server:** `ConcurrentHashMap<GameId, Game>` in a `GameService`; thousands of games proceed in parallel because each has its own monitor — the per-resource pattern *reappears one level up*, where the "resource" is the game, not the cell. No lock ordering issue: no operation ever holds two games' locks.
- **Deadlock/livelock argument:** one lock per operation, never nested, never held across I/O (the lock guards pure computation; rendering/network happens outside) ⇒ deadlock impossible by the hold-and-wait test. No retry loops ⇒ no livelock. Races excluded because every read/write of board state happens under the single monitor, and `history` is exposed only as a defensive copy.

---

## Interviewer Follow-ups (with model answers)

**Q1 — "Add castling. What changes?"**
King's `canMove` gains the `dc == 2, dr == 0` branch guarded by: king `!hasMoved`, the relevant rook `!hasMoved`, path clear, king not currently in check, and the king does not pass *through* an attacked square (check each transit square with `isSquareAttacked`). Execution moves two pieces, so `Move` gains an optional secondary (rook) displacement and `Game` executes it atomically. Every required ingredient — `hasMoved`, `isSquareAttacked`, the `Move` record — already exists; that's the sign the design has the right joints.

**Q2 — "Add an AI opponent."**
`Player` becomes the seam: extract `MoveProvider { Move chooseMove(Board, Color) }` — a human provider reads the UI, an AI provider runs minimax over the *same* `hasAnyLegalMove`-style generation (refactor it to `getLegalMoves(color) : List<Move>` returning instead of short-circuiting). Strategy pattern, zero engine changes — the payoff of keeping `Game` ignorant of where moves come from.

**Q3 — "Support undo."**
`Move` already stores captured piece and promotion. `undo()` pops history, restores `from`/`to`, reinstates the captured piece, reverses promotion. The one subtle bug: `hasMoved` must be *restored*, not just unset — so `Move` should record the piece's prior `hasMoved` value. This is exactly why Command objects should snapshot *all* state they disturb.

**Q4 — "10,000 concurrent games — what breaks?"**
Nothing in the design; the per-game monitor already isolates games. The real scaling concerns are *operational*: game state must move out of heap into a store (serialize `history` — the move list IS the game, replayable from the start position: event sourcing in miniature), horizontal scale by sharding games across nodes (a game is a perfect shard unit — no cross-game transactions), and Observer-based spectator fan-out moves to pub/sub.

**Q5 — "Why not the State pattern for piece... or for game status?"**
Pieces don't change behavior over their lifetime (a rook moves the same on move 1 and move 40) — there's nothing for State to vary; promotion *replaces* the object, which is cleaner than mutating its type. `GameStatus` is a one-way fan-out with no behavioral variation per state — the enum-guard idiom (your Auction/Booking precedent) gives the safety without six ceremony classes. Knowing when *not* to apply a pattern is the senior signal.

---

## Transferable Lessons

1. **Layered validation kills god classes.** Geometry → occupancy → global rules, each at its natural owner. The same layering reappears in any rule-heavy domain (order validation in e-commerce, fraud checks in payments).
2. **Polymorphic capability checks compose for free.** Once `canMove` exists, "is this square attacked" is a scan, and "is there any legal move" is a scan over scans. Design one honest primitive; derive the hard features.
3. **Lock granularity must match contention granularity.** Per-resource locking (your Parking Lot → Airline thread) is not a universal law — chess is the counterexample where one coarse monitor is provably optimal. Expect interviewers to test whether you apply patterns or *reason* about them.
4. **Simulate-and-restore (make–unmake) vs. copy-and-test** is the in-memory miniature of dry-run validation; `finally`-guaranteed restoration is its compensation pattern.
5. **The move list IS the game** — replayable history as the source of truth is event sourcing in miniature, and the hook for undo, castling rights, en passant, and repetition draws.

**Next problem suggestion:** **Snake and Ladder** (quick consolidation: board game with dice Strategy and far simpler rules — good interview-mode candidate) or jump straight to **Movie Ticket Booking System** to return to the multi-resource atomicity track (multi-seat claim + compensation) you've been building since Concert Ticket and Airline.
