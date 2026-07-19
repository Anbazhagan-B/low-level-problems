# Design Principles for LLD Interviews

The principles that turn "working code" into "good design": **DRY, YAGNI, KISS**, and the five
**SOLID** principles. Interviewers rarely ask you to *recite* them — they watch whether your class
design *obeys* them, and they love to ask "which principle does this violate?" This doc gives each
principle a definition, a **bad → good** code refactor, the mental picture, and the smell that
signals a violation.

Examples are in Java, matching the LLD docs in this repo.

---

## Table of Contents
1. [DRY — Don't Repeat Yourself](#1-dry--dont-repeat-yourself)
2. [YAGNI — You Aren't Gonna Need It](#2-yagni--you-arent-gonna-need-it)
3. [KISS — Keep It Simple, Stupid](#3-kiss--keep-it-simple-stupid)
4. [SOLID — The Five Principles](#4-solid--the-five-principles)
   - [S — Single Responsibility](#s--single-responsibility-principle-srp)
   - [O — Open/Closed](#o--openclosed-principle-ocp)
   - [L — Liskov Substitution](#l--liskov-substitution-principle-lsp)
   - [I — Interface Segregation](#i--interface-segregation-principle-isp)
   - [D — Dependency Inversion](#d--dependency-inversion-principle-dip)
5. [How the Principles Interact](#5-how-the-principles-interact)
6. [Interview Cheat Sheet](#6-interview-cheat-sheet)

---

## 1. DRY — Don't Repeat Yourself

> **Every piece of knowledge should have a single, authoritative representation in the system.**

Duplication is a liability: when a rule lives in three places, a change means fixing three places —
and forgetting one is how bugs are born. DRY says *extract the shared knowledge into one place*
(a method, a class, a constant, a config).

**Picture:** one source of truth vs. three copies drifting out of sync.

```java
// ❌ Repetition — the tax rule is copy-pasted; change the rate and you must find every copy
double invoiceA = price * 1.18;
double invoiceB = qty * unit * 1.18;

// ✅ DRY — one authoritative definition
static final double GST_RATE = 0.18;
double withTax(double base) { return base * (1 + GST_RATE); }
```

**Nuance interviewers respect:** DRY is about *knowledge*, not *identical-looking lines*. Two code
blocks that happen to look the same but represent **different rules** should stay separate — merging
them creates false coupling (they'll need to change independently later). Don't over-DRY.

---

## 2. YAGNI — You Aren't Gonna Need It

> **Don't build functionality until it's actually required — not because you *might* need it later.**

Speculative generality (extra config knobs, unused abstraction layers, "flexible" frameworks for a
single use case) adds code to maintain, test, and understand for **zero present value**. Most
predicted futures never arrive.

**Picture:** building a 10-lane highway for a village that has three cars.

```java
// ❌ YAGNI violation — only email is needed today, but we "future-proofed" for 5 channels
interface NotificationChannel { void send(Msg m); }
class EmailChannel   implements NotificationChannel { /*...*/ }
class SmsChannel     implements NotificationChannel { /* TODO unused */ }
class PushChannel    implements NotificationChannel { /* TODO unused */ }
class SlackChannel   implements NotificationChannel { /* TODO unused */ }
class WhatsAppChannel implements NotificationChannel { /* TODO unused */ }

// ✅ YAGNI — build what's needed now; the interface seam makes adding channels cheap *when* required
class EmailService { void send(Msg m) { /*...*/ } }
```

**Balance with the interview reality:** LLD interviews *do* reward extensibility (OCP). The
resolution: design **seams/abstractions** where change is *genuinely likely* (payment providers,
pricing strategies), but don't *implement* variants nobody asked for. "Easy to extend" ≠ "already
extended." State your assumptions and defer the rest.

---

## 3. KISS — Keep It Simple, Stupid

> **Prefer the simplest solution that solves the problem. Complexity must earn its place.**

The best code is easy to read and reason about. Clever one-liners, deep inheritance trees, needless
patterns, and premature optimization all cost more (in bugs and onboarding) than they save.

**Picture:** a straight footpath vs. a maze that reaches the same door.

```java
// ❌ Needlessly clever
String grade = s >= 90 ? "A" : s >= 80 ? "B" : s >= 70 ? "C" : s >= 60 ? "D" : "F";
// (fine for 2 cases; a nested ternary ladder is where it stops being simple)

// ✅ Simple and obvious
String grade;
if      (s >= 90) grade = "A";
else if (s >= 80) grade = "B";
else if (s >= 70) grade = "C";
else if (s >= 60) grade = "D";
else              grade = "F";
```

**In interviews:** don't reach for a design pattern to look sophisticated. Introduce a Strategy or
Factory *when the problem shows real variability* — otherwise a plain method is the KISS answer.
Interviewers penalize over-engineering as much as under-engineering.

> **DRY, YAGNI, and KISS are the "keep it lean" trio.** DRY removes *duplication*, YAGNI removes
> *speculation*, KISS removes *unnecessary complexity*. SOLID then governs *how you structure* what
> remains.

---

## 4. SOLID — The Five Principles

Five principles (Robert C. Martin) for building software that's **easy to extend, hard to break, and
simple to understand**. They're the backbone of almost every LLD answer.

| Letter | Principle | One-liner |
|---|---|---|
| **S** | Single Responsibility | A class should have one reason to change |
| **O** | Open/Closed | Open for extension, closed for modification |
| **L** | Liskov Substitution | Subtypes must be usable as their base type |
| **I** | Interface Segregation | Many small interfaces beat one fat one |
| **D** | Dependency Inversion | Depend on abstractions, not concretions |

---

### S — Single Responsibility Principle (SRP)

> **A class should have exactly one reason to change** — it should do one thing and own one concern.

A class that mixes responsibilities (business logic + persistence + formatting) is fragile: a change
to any concern risks the others, and the class becomes hard to test and reuse.

**Picture:** a Swiss-army-knife class vs. focused single-purpose tools.

```java
// ❌ Three responsibilities → three reasons to change
class Invoice {
    void calculateTotal() { /* business rule */ }
    void saveToDatabase() { /* persistence — changes if DB changes */ }
    void printPdf()       { /* formatting — changes if layout changes */ }
}

// ✅ Split by reason-to-change
class Invoice           { double calculateTotal() { /*...*/ } }   // business
class InvoiceRepository { void save(Invoice i)   { /*...*/ } }     // persistence
class InvoicePrinter    { void printPdf(Invoice i){ /*...*/ } }    // presentation
```

**Smell:** the word "**and**" in a class description ("handles orders *and* sends emails"), or a class
touched by unrelated feature requests. **LLD payoff:** SRP is *the* driver of your class breakdown —
most "identify the classes" questions are SRP in disguise.

---

### O — Open/Closed Principle (OCP)

> **Software entities should be open for extension but closed for modification.** Add new behavior by
> adding new code, not by editing existing, tested code.

Achieved with **polymorphism**: define an abstraction, and add new implementations without touching
the code that uses it.

**Picture:** adding a new plug-in module vs. rewiring the whole appliance.

```java
// ❌ Every new shape edits this method (and risks breaking the others)
double area(Shape s) {
    if (s.type == CIRCLE) return Math.PI * s.r * s.r;
    else if (s.type == SQUARE) return s.side * s.side;
    // ...add triangle → edit here again
}

// ✅ Open for extension via a new class; area() never changes
interface Shape { double area(); }
class Circle implements Shape { public double area() { return Math.PI * r * r; } }
class Square implements Shape { public double area() { return side * side; } }
class Triangle implements Shape { public double area() { return 0.5 * b * h; } }  // just add this
```

**Smell:** a `switch`/`if-else` ladder on a type code that grows with every feature. **Payoff:** this
is why the parking-lot design injects a `FeeStrategy` — new pricing = new class, `ParkingLot`
untouched.

---

### L — Liskov Substitution Principle (LSP)

> **Objects of a subclass must be substitutable for their base class without breaking correctness.**
> A subtype must honor the *contract* of its supertype — no strengthened preconditions, no weakened
> postconditions, no surprising behavior.

If code written against the base type breaks when handed a subclass, inheritance was misused.

**Picture:** a "rubber duck" that quacks fits wherever a "duck" is expected; a "wooden duck" that
throws when you call `quack()` does not.

```java
// ❌ Classic violation — a Square isn't substitutable for a Rectangle
class Rectangle { void setWidth(int w){...} void setHeight(int h){...} int area(){...} }
class Square extends Rectangle {
    void setWidth(int w)  { this.w = this.h = w; }   // mutates height too — surprise!
    void setHeight(int h) { this.w = this.h = h; }
}
// Code that assumes width & height are independent now computes wrong areas for Square.

// ❌ Also a violation — subtype refuses a base capability
class Bird { void fly() {...} }
class Ostrich extends Bird { void fly() { throw new UnsupportedOperationException(); } }

// ✅ Fix: model the real hierarchy so subtypes never contradict the base
interface Shape { int area(); }
class Rectangle implements Shape { /*...*/ }
class Square    implements Shape { /*...*/ }   // no false "is-a Rectangle" claim
```

**Smell:** overridden methods that `throw UnsupportedOperationException`, do nothing, or need
`instanceof` checks in the caller. **Payoff:** LSP is the guardrail on *when inheritance is
legitimate* — if a subclass can't honor the contract, prefer composition.

---

### I — Interface Segregation Principle (ISP)

> **Clients should not be forced to depend on methods they don't use.** Prefer many small,
> role-specific interfaces over one large "fat" interface.

A bloated interface forces implementers to stub out irrelevant methods and couples clients to
behavior they don't care about.

**Picture:** a universal remote with 60 buttons vs. a few simple, purpose-built remotes.

```java
// ❌ Fat interface — a SimplePrinter is forced to implement fax/scan it can't do
interface Machine { void print(Doc d); void scan(Doc d); void fax(Doc d); }
class OldPrinter implements Machine {
    public void print(Doc d) { /*...*/ }
    public void scan(Doc d)  { throw new UnsupportedOperationException(); }  // smell
    public void fax(Doc d)   { throw new UnsupportedOperationException(); }
}

// ✅ Segregated roles — implement only what you support
interface Printer { void print(Doc d); }
interface Scanner { void scan(Doc d); }
interface Fax     { void fax(Doc d); }
class OldPrinter    implements Printer { public void print(Doc d) { /*...*/ } }
class AllInOne      implements Printer, Scanner, Fax { /* implements all three */ }
```

**Smell:** implementations full of empty or exception-throwing methods; interfaces with methods only
some clients use. **Payoff:** small interfaces compose cleanly (a class implements exactly the roles
it plays) and keep clients decoupled.

---

### D — Dependency Inversion Principle (DIP)

> **High-level modules should not depend on low-level modules; both should depend on abstractions.**
> And: **abstractions should not depend on details — details depend on abstractions.**

Instead of business logic hard-wiring a concrete class, it depends on an **interface**, and the
concrete implementation is **injected**. This inverts the traditional top-down dependency arrow.

**Picture:** a wall socket (standard interface) any appliance can plug into — the lamp doesn't
hard-wire itself to the power plant.

```java
// ❌ High-level OrderService is nailed to a concrete MySqlDatabase
class OrderService {
    private MySqlDatabase db = new MySqlDatabase();   // can't swap, can't unit-test
    void place(Order o) { db.save(o); }
}

// ✅ Depend on an abstraction; inject the detail
interface OrderRepository { void save(Order o); }
class MySqlOrderRepository  implements OrderRepository { /*...*/ }
class InMemoryOrderRepository implements OrderRepository { /* great for tests */ }

class OrderService {
    private final OrderRepository repo;
    OrderService(OrderRepository repo) { this.repo = repo; }   // injected
    void place(Order o) { repo.save(o); }
}
```

**Smell:** `new ConcreteClass()` for a collaborator inside business logic; inability to unit-test
without a real DB/network. **Payoff:** DIP is what makes designs **testable** (inject a fake) and
**flexible** (swap implementations) — it's the principle behind Dependency Injection and the
Repository/Strategy patterns.

> **Note:** DIP (a principle) ≠ Dependency Injection (a technique that implements it) ≠ IoC container
> (a tool that automates injection). Interviewers sometimes probe that you know the difference.

---

## 5. How the Principles Interact

They reinforce each other, and pull against each other just enough to require judgment:

- **SRP → OCP → DIP** form a chain: split by responsibility (SRP), let each vary behind an interface
  (OCP), inject the concrete choice (DIP). Most clean LLDs are exactly this pipeline.
- **OCP relies on LSP** — extension via subtypes only works if subtypes are truly substitutable.
- **ISP supports SRP** at the interface level — small roles = focused responsibilities.
- **YAGNI/KISS keep SOLID honest** — SOLID says "make it extensible," YAGNI/KISS say "only where
  change is real." Applying every SOLID abstraction everywhere is *over-engineering*. The skill is
  adding seams where variability is likely and staying simple everywhere else.

**The interview meta-point:** name the principle *and* the trade-off. "I'll put fee logic behind a
`FeeStrategy` (OCP/DIP) because pricing rules clearly change; but I'll keep spot assignment inline
(KISS/YAGNI) since there's one obvious algorithm." That balance is what senior signal sounds like.

---

## 6. Interview Cheat Sheet

| Principle | Says | Violation smell | Fix |
|---|---|---|---|
| **DRY** | One source of truth per rule | Copy-pasted logic | Extract to method/constant/class |
| **YAGNI** | Build only what's needed now | Unused abstractions/config | Delete speculation; keep the seam |
| **KISS** | Simplest thing that works | Cleverness, needless patterns | Straightforward code |
| **SRP** | One reason to change | Class described with "and" | Split by concern |
| **OCP** | Extend, don't modify | Growing `if/switch` on type | Polymorphism + new classes |
| **LSP** | Subtypes honor base contract | Overrides that throw / no-op | Fix the hierarchy or compose |
| **ISP** | Small, role-based interfaces | Empty/exception stub methods | Split the fat interface |
| **DIP** | Depend on abstractions | `new Concrete()` in logic; untestable | Interface + inject the detail |

**Say this if asked "which principles matter most in LLD?"**
> SRP drives my class breakdown, OCP + DIP make it extensible and testable, and I keep DRY/KISS/YAGNI
> as the brakes so I only add abstraction where change is genuinely likely.
