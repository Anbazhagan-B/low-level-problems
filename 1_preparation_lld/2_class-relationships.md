# Class Relationships for LLD Interviews

The four ways objects connect to each other — **Association, Aggregation, Composition, and
Dependency**. Getting these right is what separates a class *list* from a class *design*: they decide
who owns what, who lives and dies with whom, and how change ripples through the system. Interviewers
read your UML relationships as a proxy for how well you understand coupling.

Examples are in Java, matching the LLD docs in this repo.

---

## Table of Contents
1. [The Big Picture](#1-the-big-picture)
2. [Association](#2-association)
3. [Aggregation](#3-aggregation)
4. [Composition](#4-composition)
5. [Dependency](#5-dependency)
6. [Side-by-Side Comparison](#6-side-by-side-comparison)
7. [How to Decide (Interview Heuristics)](#7-how-to-decide-interview-heuristics)
8. [UML Notation Reference](#8-uml-notation-reference)

---

## 1. The Big Picture

All four describe **how one class relates to another**, ordered here from *weakest* to *strongest*
coupling:

```
Dependency  <  Association  <  Aggregation  <  Composition
   (uses)       (knows-a)       (has-a,          (owns-a,
                                 shared)          exclusive + lifecycle)
```

- **Dependency** — "*A uses B* transiently" (a method parameter, a local variable, a return type).
- **Association** — "*A knows B*" — a persistent link, usually a field. A **has-a** relationship.
- **Aggregation** — a *special* association: **whole–part**, but the part can **exist independently**
  and may be **shared**. Weak ownership.
- **Composition** — a *stronger* whole–part: the part is **owned exclusively** and **dies with the
  whole**. Strong ownership + lifecycle control.

> Aggregation and Composition are both **"has-a"** relationships. The single question that separates
> them is **lifecycle**: *if the whole is destroyed, does the part die too?* Yes → composition.
> No → aggregation.

---

## 2. Association

A **structural link** where one object **knows about / refers to** another over time. It's a plain
**"has-a" / "knows-a"** with **no ownership** implied — both objects have independent lifecycles.

Typically implemented as a **field** holding a reference (or a collection of references).

```java
class Teacher {
    private List<Student> students;   // a teacher is associated with many students
}

class Student {
    private List<Teacher> teachers;   // and vice-versa
}
```

**Characteristics**
- Can be **uni-directional** (`Order` knows its `Customer`) or **bi-directional** (both know each other).
- Has a **multiplicity**: one-to-one, one-to-many, many-to-many.
- Neither object owns the other; deleting a `Teacher` does **not** delete the `Student`s.

**Real LLD example:** in the parking-lot design, `ParkingSpot` holds a reference to the `Vehicle`
currently parked in it. The vehicle exists before it parks and after it leaves — the spot merely
*references* it. That's association (not composition).

> Aggregation and Composition are *specialized* associations. So when unsure and the relationship is
> just "these two are linked," **"association" is always a defensible answer.**

---

## 3. Aggregation

A **whole–part** relationship where the whole **has** parts, but the parts can **live independently**
and can be **shared** across multiple wholes. Often called **"weak has-a."**

The part is usually **passed in** from outside (the whole doesn't create it):

```java
class Team {
    private List<Player> players;          // Team HAS players...

    public Team(List<Player> players) {    // ...but they're handed in, not created here
        this.players = players;
    }
}

Player kohli = new Player("Kohli");        // player exists on its own
Team india  = new Team(List.of(kohli));    // added to a team
// If `india` is garbage-collected, `kohli` still exists — and could join another Team.
```

**Signals of aggregation**
- The part is **injected** via constructor/setter, not `new`-ed inside the whole.
- The part **outlives** the whole; destroying the whole leaves the parts intact.
- The same part instance can belong to **multiple** wholes (sharing).

**Real LLD examples**
- A `Playlist` aggregates `Song`s — the same song appears in many playlists and survives a playlist's
  deletion.
- A `ParkingLot` is *configured with* a `FeeStrategy` it doesn't own — swappable, shared, injected.
- A university `Department` and its `Professor`s — a professor can move departments and exists
  independently.

---

## 4. Composition

The **strongest** whole–part relationship: the whole **exclusively owns** its parts and **controls
their lifecycle**. Parts are created by the whole and **cannot meaningfully exist without it** —
often called **"strong has-a"** or a **contains-a / part-of** relationship.

The whole typically **creates** the parts internally:

```java
class House {
    private final List<Room> rooms = new ArrayList<>();

    public House() {
        rooms.add(new Room("Kitchen"));   // House CREATES and OWNS its rooms
        rooms.add(new Room("Bedroom"));
    }
    // When the House object is destroyed, its Rooms are destroyed too.
    // A Room has no independent existence outside its House.
}
```

**Signals of composition**
- The part is **created inside** the whole (`new` in the constructor) and not exposed for external
  ownership.
- The part's **lifecycle is bound** to the whole — destroy the whole and the parts go with it.
- The part is **not shared** — it belongs to exactly one whole.

**Real LLD examples**
- `Order` composes its `OrderLine` items — a line item is meaningless outside its order and dies with it.
- `ParkingLevel` composes its `ParkingSpot`s — spots are created and owned by the level.
- A `Ticket` composes its `Payment` — the payment is created for and belongs to that one ticket.

> **Aggregation vs. Composition, the one-liner:** a `Team` **aggregates** `Player`s (players survive
> the team); a `House` **composes** `Room`s (rooms die with the house).

---

## 5. Dependency

The **weakest, most transient** relationship: class A **uses** class B temporarily, without keeping
a lasting reference. Also called **"uses-a."** If B's signature/behavior changes, A might need to
change — that's the "dependency."

It shows up as a **method parameter, local variable, return type, or a static call** — *not* a field.

```java
class ReportService {
    // Depends on PdfExporter only for the duration of this call — no field is stored.
    public File generate(ReportData data, PdfExporter exporter) {
        return exporter.export(data);
    }
}
```

**Association vs. Dependency — the crisp distinction:**
- **Association** = a **structural, lasting** link (a **field**). "A *has* a B."
- **Dependency** = a **transient, use-site** link (a **parameter/local/return**). "A *uses* a B briefly."

If the reference is stored as a member variable, it's (at least) an association. If it appears only
inside a method's scope, it's a dependency.

**Real LLD example:** a `Checkout` that receives a `PaymentGateway` *as a method argument* to process
one payment depends on it. If instead `Checkout` stores the gateway as a field, that link is promoted
to an association/aggregation.

---

## 6. Side-by-Side Comparison

| Aspect | Dependency | Association | Aggregation | Composition |
|---|---|---|---|---|
| Meaning | "uses-a" | "knows-a" / "has-a" | "has-a" (weak, whole–part) | "owns-a" (strong, whole–part) |
| Coupling strength | Weakest | Weak | Medium | Strongest |
| Implemented as | Param / local / return | Field (reference) | Field, **injected** | Field, **created inside** |
| Part's lifecycle | Independent | Independent | Independent (outlives whole) | **Bound to whole** (dies with it) |
| Can the part be shared? | n/a | Yes | Yes | **No** (exclusive) |
| Who creates the part? | Caller | Either | Outside (passed in) | The whole itself |
| UML arrow | dashed arrow ┄▷ | solid line ── | hollow diamond ◇── | filled diamond ◆── |
| Example | `service.generate(data, exporter)` | `Order ── Customer` | `Team ◇── Player` | `House ◆── Room` |

---

## 7. How to Decide (Interview Heuristics)

Walk this ladder for any two classes A and B:

1. **Does A only *use* B inside a method (param/local/return), no stored reference?**
   → **Dependency.**
2. **Does A hold a reference to B as a field, but neither owns the other's lifecycle?**
   → **Association.**
3. **Is it a whole–part where B can exist without A and may be shared / is passed in?**
   → **Aggregation.**
4. **Is it a whole–part where A creates B, B isn't shared, and B dies when A dies?**
   → **Composition.**

**The two questions that resolve 90% of cases:**
- *"Is the reference stored as a field, or only used transiently?"* → separates **dependency** from
  the rest.
- *"If I delete the whole, does the part die too?"* → separates **composition** (yes) from
  **aggregation** (no).

**Why it matters in LLD:** these relationships *are* your coupling. Composition gives you strong
encapsulation and clear ownership (good for invariants), while aggregation/dependency give you
flexibility and swappability (good for Dependency Inversion). Naming them correctly in your class
diagram signals design maturity — and "**favor composition over inheritance**" is one of the
relationships on this very ladder.

---

## 8. UML Notation Reference

```
Dependency:    ClassA  ┄┄┄┄▷  ClassB      (dashed line, open arrowhead)   "uses"
Association:   ClassA  ─────   ClassB      (solid line, optional arrow)    "has / knows"
Aggregation:   Whole   ◇─────  Part        (hollow/open diamond at whole)  "has, shared"
Composition:   Whole   ◆─────  Part        (filled/solid diamond at whole) "owns, exclusive"
```

- The **diamond sits on the "whole"** side (the container/owner).
- **Hollow diamond = aggregation** (weak ownership), **filled diamond = composition** (strong ownership).
- Add **multiplicities** at each end: `1`, `0..1`, `1..*`, `*` — e.g. `ParkingLevel "1" ◆── "1..*" ParkingSpot`.

> Mnemonic: **F**illed diamond = **F**ull ownership (composition). Hollow = "part is on loan"
> (aggregation).
