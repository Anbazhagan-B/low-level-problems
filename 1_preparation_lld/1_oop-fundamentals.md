# OOP Fundamentals for LLD Interviews

A concept-by-concept reference for the object-oriented building blocks every Low-Level Design
interview assumes you already own. Each section gives the definition, the *why it matters in LLD*,
a Java example, and the traps interviewers probe.

Examples are in Java (the LLD lingua franca), but every idea maps directly to C++, C#, Python, etc.

---

## Table of Contents
1. [Classes and Objects](#1-classes-and-objects)
2. [Enums](#2-enums)
3. [Interfaces](#3-interfaces)
4. [Encapsulation](#4-encapsulation)
5. [Abstraction](#5-abstraction)
6. [Inheritance](#6-inheritance)
7. [Polymorphism](#7-polymorphism)
8. [How They Fit Together](#8-how-they-fit-together-in-an-lld)
9. [Interview Cheat Sheet](#9-interview-cheat-sheet)

---

## 1. Classes and Objects

**Class** = a blueprint/template that bundles **state** (fields) and **behavior** (methods).
**Object** = a concrete instance of a class, living in memory with its own copy of the state.

> A class is the cookie cutter; objects are the cookies. One class → many independent objects.

```java
public class BankAccount {
    // ---- State (instance fields) ----
    private final String accountId;   // unique per object
    private double balance;

    // ---- Constructor: how an object is born ----
    public BankAccount(String accountId, double openingBalance) {
        this.accountId = accountId;
        this.balance   = openingBalance;
    }

    // ---- Behavior (methods operate on this object's state) ----
    public void deposit(double amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");
        this.balance += amount;
    }

    public double getBalance() { return balance; }
}

// Objects — two independent instances, separate state:
BankAccount a = new BankAccount("ACC-1", 100.0);
BankAccount b = new BankAccount("ACC-2", 500.0);
a.deposit(50);   // a.balance = 150, b is untouched
```

**Key ideas the interviewer listens for:**
- **Fields vs. methods** — state and behavior travel together (the core OOP idea).
- **Instance vs. static** — instance members belong to each object; `static` members belong to the
  class itself (shared across all objects, e.g. a counter or a factory method).
- **Constructor** — establishes a valid object; validate invariants here so no object exists in a
  bad state.
- **`this`** — the reference to the current object; disambiguates field vs. parameter.
- **Identity vs. equality** — `==` compares references (same object?); `equals()`/`hashCode()`
  compare logical value. Override both together for value objects used in maps/sets.

**LLD relevance:** identifying the right classes (nouns in the requirements) and giving each a
*single responsibility* is 60% of the design. A class that does too much is the #1 smell.

---

## 2. Enums

An **enum** is a type with a **fixed, closed set of named constants**. Use it whenever a value can
only be one of a known, exhaustive list.

```java
public enum OrderStatus {
    CREATED, PAID, SHIPPED, DELIVERED, CANCELLED
}

OrderStatus s = OrderStatus.PAID;
```

Enums in Java are full-blown classes — they can carry **fields, constructors, and methods**, which
makes them perfect for attaching data/behavior to each constant:

```java
public enum Coin {
    PENNY(1), NICKEL(5), DIME(10), QUARTER(25);

    private final int cents;
    Coin(int cents) { this.cents = cents; }   // constructor runs per constant
    public int getCents() { return cents; }
}

int total = Coin.QUARTER.getCents() + Coin.DIME.getCents();  // 35
```

They can even give **each constant its own behavior** (constant-specific method bodies) — a clean,
allocation-free Strategy:

```java
public enum Operation {
    ADD      { public int apply(int a, int b) { return a + b; } },
    SUBTRACT { public int apply(int a, int b) { return a - b; } };
    public abstract int apply(int a, int b);
}
```

**Why interviewers like enums:**
- **Type safety** — `OrderStatus.PAID` can't be a typo'd string or a stray `int`.
- **Exhaustiveness** — a `switch` over an enum flags missing cases; models a real state set exactly.
- **Self-documenting** — the valid values *are* the type.
- **Singleton trick** — a single-constant enum is the simplest thread-safe singleton in Java.

**Trap:** don't use enums for open/extensible sets (e.g. "payment providers we might add later").
If new values arrive without code changes, prefer polymorphism (interfaces + classes) instead.

---

## 3. Interfaces

An **interface** is a pure **contract**: a set of method signatures a class promises to implement,
with **no implementation state**. It answers *"what can this thing do?"* not *"what is it?"*.

```java
public interface PaymentGateway {
    PaymentResult charge(Money amount, Card card);   // abstract by default
    void refund(String txnId);
}

public class StripeGateway implements PaymentGateway {
    public PaymentResult charge(Money amount, Card card) { /* Stripe API */ }
    public void refund(String txnId) { /* ... */ }
}

public class RazorpayGateway implements PaymentGateway {
    public PaymentResult charge(Money amount, Card card) { /* Razorpay API */ }
    public void refund(String txnId) { /* ... */ }
}
```

Code depends on the **interface**, not the concrete class — so you can swap implementations freely:

```java
class CheckoutService {
    private final PaymentGateway gateway;              // depends on the abstraction
    CheckoutService(PaymentGateway gateway) { this.gateway = gateway; }  // injected
}
new CheckoutService(new StripeGateway());   // or new RazorpayGateway() — no change to CheckoutService
```

**Modern Java interfaces can also have:**
- `default` methods — a body, so you can add methods without breaking implementers.
- `static` methods — utilities/factories tied to the interface.
- `private` methods — shared helpers for the above.
- `public static final` constants only (no instance state).

**Interface vs. abstract class — the classic question:**

| | Interface | Abstract class |
|---|---|---|
| Models | a *capability* ("can-do": `Comparable`, `Serializable`) | an *is-a* type with shared code |
| State (instance fields) | ❌ no | ✅ yes |
| Multiple inheritance | ✅ a class can implement many | ❌ only one superclass |
| Constructors | ❌ | ✅ |
| Use when | you need a contract many unrelated types share | related types share state + partial implementation |

**LLD relevance:** interfaces are how you satisfy the **Dependency Inversion** and **Open/Closed**
principles — depend on abstractions, add new behavior by adding a class, not editing existing ones.
They are the backbone of Strategy, Observer, Factory, Repository, and most design patterns.

---

## 4. Encapsulation

**Encapsulation** = bundling data with the methods that operate on it, and **hiding internal state**
behind a controlled public interface. The object protects its own **invariants**; outsiders can't
put it in an invalid state.

Mechanism: make fields `private`, expose intent-revealing methods (not raw getters/setters).

```java
public class Wallet {
    private long balancePaise;              // hidden — nobody can set it directly

    public void credit(long paise) {
        if (paise <= 0) throw new IllegalArgumentException("must be positive");
        balancePaise += paise;
    }

    public void debit(long paise) {
        if (paise <= 0) throw new IllegalArgumentException("must be positive");
        if (paise > balancePaise) throw new InsufficientFundsException();  // invariant enforced
        balancePaise -= paise;
    }

    public long getBalancePaise() { return balancePaise; }   // read-only view
}
```

Because `balancePaise` is private with **no setter**, the balance can *only* change through `credit`
/`debit`, which enforce the rules. The invariant "balance never goes negative" is guaranteed.

**Encapsulation is more than getters/setters.** A class littered with `getX/setX` for every field is
"broken encapsulation" — it exposes the internals with extra steps. Prefer methods that express
**operations** (`transferTo`, `applyDiscount`) over ones that leak fields.

**Also encapsulate collections:**
```java
private final List<Item> items = new ArrayList<>();
public List<Item> getItems() { return Collections.unmodifiableList(items); }  // no external mutation
```

**Benefits interviewers want to hear:** protects invariants, localizes change (swap internal
representation without breaking callers), reduces coupling, and makes the class easier to reason
about and test.

> **Encapsulation ≠ Abstraction.** Encapsulation *hides how the data is stored/mutated*
> (implementation hiding). Abstraction *hides complexity behind a simple concept* (essential vs.
> incidental detail). They're complementary — see next section.

---

## 5. Abstraction

**Abstraction** = exposing only the **essential** features of something and hiding the incidental
complexity. You program to a simplified model of *what* an object does, ignoring *how*.

You use abstraction every time you call `list.add(x)` without caring whether it's an array or a
linked list, or `gateway.charge(...)` without knowing the HTTP/retry details.

Tools for abstraction in Java: **interfaces** and **abstract classes**.

```java
// The abstraction: callers think in terms of "a notification I can send"
public abstract class Notification {
    protected final String recipient;
    protected Notification(String recipient) { this.recipient = recipient; }

    // Essential concept, implementation deferred:
    public abstract void send(String message);

    // Shared, concrete helper:
    protected void audit(String message) { /* log to audit trail */ }
}

public class EmailNotification extends Notification {
    public EmailNotification(String to) { super(to); }
    public void send(String message) { /* SMTP details hidden here */ audit(message); }
}

public class SmsNotification extends Notification {
    public SmsNotification(String to) { super(to); }
    public void send(String message) { /* SMS gateway details hidden here */ audit(message); }
}

// Caller works purely at the abstract level:
Notification n = pickChannel(user);   // returns Email or Sms
n.send("Your order shipped");         // doesn't know or care which
```

**Abstract class vs. interface for abstraction:**
- **Interface** → pure abstraction, a contract with (usually) no state. Multiple can be mixed in.
- **Abstract class** → partial abstraction: some methods concrete (shared code + state), some
  `abstract` (deferred to subclasses). Cannot be instantiated on its own.

**Levels of abstraction also matter in design:** a good LLD keeps each layer talking at one level —
a `CheckoutService` orchestrates `Cart`, `PaymentGateway`, `Inventory` at a high level and doesn't
reach into SQL or byte buffers. Mixing levels ("abstraction leak") is a common critique.

**One-liner to remember:** *Abstraction is about the design (what to expose); encapsulation is about
the implementation (how to hide it).*

---

## 6. Inheritance

**Inheritance** lets a class (**subclass/child**) acquire the fields and methods of another
(**superclass/parent**), modeling an **is-a** relationship and reusing code.

```java
public class Employee {
    protected final String name;
    protected double baseSalary;
    public Employee(String name, double baseSalary) { this.name = name; this.baseSalary = baseSalary; }
    public double monthlyPay() { return baseSalary / 12; }
}

public class Manager extends Employee {          // Manager IS-A Employee
    private final double bonus;
    public Manager(String name, double baseSalary, double bonus) {
        super(name, baseSalary);                 // must initialize the parent part first
        this.bonus = bonus;
    }
    @Override
    public double monthlyPay() {                 // specialize behavior
        return super.monthlyPay() + bonus / 12;  // reuse + extend
    }
}
```

**Key mechanics:**
- `extends` (single class in Java) vs. `implements` (many interfaces).
- `super(...)` calls the parent constructor; `super.method()` calls the parent's version.
- `@Override` documents and compiler-checks that you're replacing a parent method.
- `protected` members are visible to subclasses; `final` classes/methods forbid extension/override.

**The big warning — favor composition over inheritance.** Inheritance is the *tightest* coupling in
OOP: the subclass depends on the parent's internals and breaks when the parent changes ("fragile base
class"). Reach for it only for a true, stable **is-a** with substitutability. For "has-a" or "uses-a"
relationships, **compose**:

```java
// ❌ Overusing inheritance: Stack extends Vector inherits insert-in-the-middle — nonsense for a stack
// ✅ Composition: Stack HAS-A list, exposes only push/pop
public class Stack<T> {
    private final Deque<T> items = new ArrayDeque<>();  // delegate to a field
    public void push(T t) { items.push(t); }
    public T pop() { return items.pop(); }
}
```

**Liskov Substitution Principle (LSP):** a subclass must be usable anywhere its parent is expected,
without surprising behavior. The classic violation: `Square extends Rectangle` — setting width also
mutates height, breaking code that assumed they're independent. If a subclass has to weaken or
contradict the parent's contract, inheritance is the wrong tool.

---

## 7. Polymorphism

**Polymorphism** ("many forms") = the same call site behaves differently depending on the actual
object type. It's what lets you write code against an abstraction and have the *right* concrete
behavior run at execution time.

### a) Runtime polymorphism (dynamic dispatch / method overriding)
The JVM picks the method based on the object's **actual runtime type**, not the reference type.

```java
List<Notification> channels = List.of(
    new EmailNotification("a@x.com"),
    new SmsNotification("+91..."));

for (Notification n : channels) {
    n.send("Deploy done");   // Email's send() or Sms's send() — resolved at runtime
}
```

This is *the* engine of extensibility in LLD: add a `PushNotification implements`/`extends` and the
loop above works unchanged (**Open/Closed Principle**). Strategy, State, Observer, Command,
Template Method — all rely on runtime polymorphism.

### b) Compile-time polymorphism (method overloading)
Same method name, **different parameter lists**; the compiler picks based on the static argument
types. (Convenience, not extensibility.)

```java
class Printer {
    void print(int i)    { }
    void print(String s) { }
    void print(int i, int copies) { }
}
```

### Related concepts
- **Upcasting** — treat a `Manager` as an `Employee` (always safe; enables polymorphism).
- **Downcasting** — treat an `Employee` as a `Manager` (needs a check: `instanceof` / pattern
  matching); a smell if overused — you're often missing a polymorphic method.
- **Overriding vs. overloading** — overriding = *runtime*, same signature, inheritance (the important
  one); overloading = *compile-time*, different signatures. Interviewers love this distinction.

**Why it matters:** polymorphism removes `if/else`/`switch`-on-type ladders. Instead of

```java
if (shape.type == CIRCLE) areaCircle(...) else if (shape.type == SQUARE) ...   // ❌ edit every time
```

you call `shape.area()` and each subclass knows its own formula — new shapes need **no change** to
callers. Replacing conditionals with polymorphism is a top design refactor to name in interviews.

---

## 8. How They Fit Together in an LLD

These aren't isolated facts — they compose into a good design:

- **Classes/Objects** model the domain nouns; each gets a single responsibility.
- **Encapsulation** keeps each object's invariants safe behind a clean API.
- **Abstraction** (interfaces/abstract classes) defines *what* collaborators expect, hiding *how*.
- **Inheritance** reuses/specializes behavior for true is-a hierarchies (used sparingly).
- **Polymorphism** lets one algorithm work across many concrete types → **extensible** designs.
- **Enums** capture the fixed value sets (statuses, types) precisely and safely.

**Worked micro-example — a payment flow tying it all together:**
```java
enum Currency { USD, INR, EUR }                       // Enum: closed value set

interface PaymentStrategy {                            // Abstraction / interface: the contract
    boolean pay(long amountMinor, Currency currency);
}

class UpiPayment implements PaymentStrategy { /* ... */ }   // Polymorphic implementations
class CardPayment implements PaymentStrategy { /* ... */ }

class Checkout {                                       // Class with encapsulated state
    private final PaymentStrategy strategy;            // depends on abstraction (DIP)
    Checkout(PaymentStrategy s) { this.strategy = s; } // strategy injected → swappable
    boolean settle(Order o) {
        return strategy.pay(o.totalMinor(), o.currency());  // runtime polymorphism
    }
}
```
Add crypto payments later → write `CryptoPayment implements PaymentStrategy`. **Nothing in
`Checkout` changes.** That's the payoff of using these fundamentals well.

---

## 9. Interview Cheat Sheet

| Concept | One-line definition | Keyword to drop |
|---|---|---|
| Class / Object | Blueprint vs. instance; state + behavior together | single responsibility, `this`, `equals/hashCode` |
| Enum | Fixed, type-safe set of named constants (can hold data/behavior) | exhaustiveness, type safety |
| Interface | Pure capability contract, no state; enables swapping | Dependency Inversion, program to an interface |
| Encapsulation | Hide internal state; protect invariants via a controlled API | private fields, invariants, low coupling |
| Abstraction | Expose essentials, hide complexity (interface / abstract class) | levels of abstraction, hide the *how* |
| Inheritance | Acquire parent's members; models **is-a** | favor composition, LSP, fragile base class |
| Polymorphism | Same call, many behaviors, resolved by actual type | dynamic dispatch, override vs. overload, kills `switch`-on-type |

**Distinctions you must be able to state instantly:**
- **Abstraction vs. Encapsulation** — *what to expose* (design) vs. *how to hide it* (implementation).
- **Interface vs. Abstract class** — capability/no-state/multi vs. is-a/shared-state/single.
- **Overriding vs. Overloading** — runtime, same signature, inheritance vs. compile-time, different signature.
- **Inheritance vs. Composition** — is-a (tight, rigid) vs. has-a (loose, preferred).
- **`==` vs. `equals()`** — reference identity vs. logical equality.

**Golden rules for LLD:** favor composition over inheritance · depend on abstractions, not concretions ·
replace type-checking conditionals with polymorphism · keep each class to one reason to change.
