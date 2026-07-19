# Design Patterns for LLD Interviews

The 23 classic **Gang of Four (GoF)** patterns, grouped into **Creational, Structural, and
Behavioral**. Each pattern here has: **Intent** (the one-line purpose), the **problem it solves**, a
compact **Java sample**, **when to use it**, and the **real LLD scenario** where interviewers expect
it. Patterns are proven solutions to recurring design problems — naming the right one at the right
moment is a strong senior signal (and forcing one where it isn't needed is a classic red flag).

| Creational (object creation) | Structural (object composition) | Behavioral (object interaction) |
|---|---|---|
| Singleton | Adapter | Iterator |
| Factory Method | Bridge | Observer |
| Abstract Factory | Composite | Strategy |
| Builder | Decorator | Command |
| Prototype | Facade | State |
| | Flyweight | Template Method |
| | Proxy | Visitor |
| | | Mediator |
| | | Memento |
| | | Chain of Responsibility |

---

## Table of Contents
- [Creational Patterns](#creational-patterns)
  - [Singleton](#singleton) · [Factory Method](#factory-method) · [Abstract Factory](#abstract-factory) · [Builder](#builder) · [Prototype](#prototype)
- [Structural Patterns](#structural-patterns)
  - [Adapter](#adapter) · [Bridge](#bridge) · [Composite](#composite) · [Decorator](#decorator) · [Facade](#facade) · [Flyweight](#flyweight) · [Proxy](#proxy)
- [Behavioral Patterns](#behavioral-patterns)
  - [Iterator](#iterator) · [Observer](#observer) · [Strategy](#strategy) · [Command](#command) · [State](#state) · [Template Method](#template-method) · [Visitor](#visitor) · [Mediator](#mediator) · [Memento](#memento) · [Chain of Responsibility](#chain-of-responsibility)
- [Quick Decision Guide](#quick-decision-guide)

---

# Creational Patterns

*Concerned with **how objects are created** — decoupling client code from the concrete classes it
instantiates, so construction is flexible, controlled, and swappable.*

---

## Singleton

**Intent:** ensure a class has **exactly one instance** and provide a global access point to it.

**Problem:** some resources must be unique and shared — a config registry, a connection pool, a
logger. Multiple instances would be wrong or wasteful.

```java
public final class Logger {
    private static volatile Logger instance;   // volatile → correct under concurrency
    private Logger() {}                          // private ctor blocks `new`

    public static Logger getInstance() {         // double-checked locking
        if (instance == null) {
            synchronized (Logger.class) {
                if (instance == null) instance = new Logger();
            }
        }
        return instance;
    }
    public void log(String msg) { /* ... */ }
}
```

*Cleaner alternative (thread-safe, lazy, serialization-safe):* a single-constant **enum**:
```java
public enum Logger { INSTANCE; public void log(String m) { /*...*/ } }
```

**Use when:** exactly one instance must coordinate access to a shared resource.
**Watch out:** singletons are effectively global state → hurt testability and hide dependencies.
Prefer **injecting** a single instance (DIP) over calling `getInstance()` everywhere. Must be
**thread-safe**.

---

## Factory Method

**Intent:** define an interface for creating an object, but let **subclasses decide which class to
instantiate**. Defers instantiation to subclasses.

**Problem:** a class needs to create objects but shouldn't hard-code their concrete types.

```java
abstract class Dialog {
    abstract Button createButton();          // the "factory method"
    void render() {
        Button b = createButton();           // uses the product without knowing its concrete type
        b.paint();
    }
}
class WindowsDialog extends Dialog { Button createButton() { return new WindowsButton(); } }
class WebDialog     extends Dialog { Button createButton() { return new HtmlButton(); } }
```

*Simple/Static Factory (not strictly GoF, but ubiquitous in interviews):*
```java
class ShapeFactory {
    static Shape create(String type) {
        return switch (type) { case "circle" -> new Circle(); case "square" -> new Square(); default -> throw ...; };
    }
}
```

**Use when:** the exact type to create isn't known until runtime, or you want to centralize/localize
object creation. **LLD example:** a `NotificationFactory` returning the right channel;
`PieceFactory` in chess creating the correct `Piece`.

---

## Abstract Factory

**Intent:** provide an interface for creating **families of related objects** without specifying their
concrete classes.

**Problem:** you need to create *sets* of objects that must be used together and stay consistent (e.g.
a whole UI toolkit in one theme).

```java
interface GUIFactory { Button createButton(); Checkbox createCheckbox(); }

class MacFactory     implements GUIFactory {
    public Button createButton()     { return new MacButton(); }
    public Checkbox createCheckbox() { return new MacCheckbox(); }
}
class WindowsFactory implements GUIFactory {
    public Button createButton()     { return new WinButton(); }
    public Checkbox createCheckbox() { return new WinCheckbox(); }
}
// Client gets one factory and produces a consistent family:
GUIFactory f = onMac ? new MacFactory() : new WindowsFactory();
Button b = f.createButton();   // guaranteed to match f.createCheckbox()'s style
```

**Factory Method vs. Abstract Factory:** Factory Method creates **one** product via inheritance;
Abstract Factory creates **a family** of products via composition. **Use when:** the system must be
independent of how its products are created and products come in interchangeable families.

---

## Builder

**Intent:** construct a **complex object step by step**, separating construction from representation;
lets you produce different representations with the same process.

**Problem:** a constructor with many parameters (especially optional ones) becomes an unreadable
"telescoping constructor." Builder gives a fluent, readable, validated construction.

```java
public class HttpRequest {
    private final String url; private final String method; private final Map<String,String> headers;
    private HttpRequest(Builder b) { this.url = b.url; this.method = b.method; this.headers = b.headers; }

    public static class Builder {
        private final String url;                       // required
        private String method = "GET";                   // optional w/ default
        private Map<String,String> headers = new HashMap<>();
        public Builder(String url) { this.url = url; }
        public Builder method(String m)             { this.method = m; return this; }   // fluent
        public Builder header(String k, String v)   { headers.put(k, v); return this; }
        public HttpRequest build() { /* validate invariants here */ return new HttpRequest(this); }
    }
}
// Readable, order-independent, immutable result:
HttpRequest r = new HttpRequest.Builder("/api").method("POST").header("Auth", "xyz").build();
```

**Use when:** an object has many optional parameters, needs step-wise construction, or should be
immutable. **LLD example:** building a `Pizza`, an `SqlQuery`, a `Meal` combo, or any config object.

---

## Prototype

**Intent:** create new objects by **cloning an existing instance** (a prototype) rather than
constructing from scratch.

**Problem:** object creation is expensive (heavy initialization, DB reads) or you want a copy of an
object whose exact class you may not know.

```java
interface Prototype { Prototype clone(); }

class Document implements Prototype {
    private String content; private List<String> tags;
    public Document clone() {
        Document copy = new Document();
        copy.content = this.content;
        copy.tags = new ArrayList<>(this.tags);   // deep-copy mutable fields!
        return copy;
    }
}
Document template = loadExpensiveTemplate();
Document draft = template.clone();               // cheap; tweak the copy
```

**Use when:** cloning is cheaper than constructing, or you need copies of runtime-configured objects.
**Watch out:** **shallow vs. deep copy** — the classic bug is copying a reference to a shared mutable
list. **LLD example:** duplicating a configured game board, a design-editor shape, or cached template
objects.

---

# Structural Patterns

*Concerned with **how classes and objects are composed** into larger structures while keeping them
flexible and efficient.*

---

## Adapter

**Intent:** convert the interface of a class into another interface clients expect. Lets incompatible
interfaces **work together**.

**Problem:** you have an existing/third-party class whose interface doesn't match what your code
needs.

```java
interface PaymentProcessor { void pay(int amount); }              // what our app expects

class StripeApi { void makePayment(double dollars) { /* 3rd-party */ } }  // incompatible interface

class StripeAdapter implements PaymentProcessor {                 // the adapter
    private final StripeApi stripe = new StripeApi();
    public void pay(int amount) { stripe.makePayment(amount / 100.0); }   // translate the call
}
```

**Use when:** integrating a legacy/third-party API, or reusing a class whose interface is "wrong" for
your context. **Real analogy:** a travel power-plug adapter. **LLD example:** wrapping a vendor SMS
gateway to your `NotificationChannel` interface.

---

## Bridge

**Intent:** **decouple an abstraction from its implementation** so the two can vary independently.
Prevents a combinatorial explosion of subclasses.

**Problem:** two dimensions of variation (e.g. shape × rendering API) would need `M × N` subclasses if
combined by inheritance.

```java
interface Renderer { void renderCircle(float r); }               // implementation side
class VectorRenderer implements Renderer { public void renderCircle(float r) { /*...*/ } }
class RasterRenderer implements Renderer { public void renderCircle(float r) { /*...*/ } }

abstract class Shape {                                            // abstraction side
    protected final Renderer renderer;                            // the "bridge"
    Shape(Renderer r) { this.renderer = r; }
    abstract void draw();
}
class Circle extends Shape {
    private float r; Circle(Renderer rn, float r){ super(rn); this.r = r; }
    void draw() { renderer.renderCircle(r); }
}
// Mix & match freely: new Circle(new VectorRenderer()) or new Circle(new RasterRenderer())
```

**Use when:** you have two (or more) orthogonal dimensions that each need to vary. **Bridge vs.
Adapter:** Adapter makes *unrelated* interfaces work together (after the fact); Bridge is *designed
up front* to separate abstraction from implementation.

---

## Composite

**Intent:** compose objects into **tree structures** and let clients treat **individual objects and
groups uniformly**.

**Problem:** you have a part–whole hierarchy and want the same operations to work on a leaf and on a
whole subtree.

```java
interface FileSystemNode { int size(); }                         // common interface

class File implements FileSystemNode {                           // leaf
    private int bytes; public int size() { return bytes; }
}
class Folder implements FileSystemNode {                         // composite
    private List<FileSystemNode> children = new ArrayList<>();
    public void add(FileSystemNode n) { children.add(n); }
    public int size() {                                          // recurse over children
        return children.stream().mapToInt(FileSystemNode::size).sum();
    }
}
// A Folder holding Files and other Folders — client calls size() without caring which is which.
```

**Use when:** representing hierarchies (file systems, org charts, UI component trees, menus,
arithmetic expressions). **Key benefit:** uniform treatment of leaves and containers via one
interface.

---

## Decorator

**Intent:** **attach additional responsibilities to an object dynamically** by wrapping it. A flexible
alternative to subclassing for extending behavior.

**Problem:** you want to add features to individual objects (not the whole class) in combinations,
without a subclass for every combination.

```java
interface Coffee { double cost(); String desc(); }
class SimpleCoffee implements Coffee { public double cost(){return 2;} public String desc(){return "coffee";} }

abstract class CoffeeDecorator implements Coffee {               // wraps a Coffee
    protected final Coffee inner;
    CoffeeDecorator(Coffee c) { this.inner = c; }
}
class Milk extends CoffeeDecorator {
    Milk(Coffee c){ super(c);}
    public double cost(){ return inner.cost() + 0.5; }           // add behavior around the wrapped call
    public String desc(){ return inner.desc() + " + milk"; }
}
class Sugar extends CoffeeDecorator {
    Sugar(Coffee c){ super(c);}
    public double cost(){ return inner.cost() + 0.2; }
    public String desc(){ return inner.desc() + " + sugar"; }
}
// Stack decorators in any order:
Coffee c = new Sugar(new Milk(new SimpleCoffee()));   // cost 2.7, "coffee + milk + sugar"
```

**Use when:** you need to add/remove responsibilities at runtime in varied combinations (Java's
`InputStream`/`BufferedReader` chain is the canonical example). **Decorator vs. Inheritance:** same
interface, but composition at runtime instead of a fixed compile-time hierarchy.

---

## Facade

**Intent:** provide a **single, simplified interface** to a complex subsystem.

**Problem:** clients must juggle many subsystem classes with intricate wiring; you want to hide that
behind one clean entry point.

```java
class VideoConverter {                                           // the facade
    private final AudioCodec audio = new AudioCodec();
    private final VideoCodec video = new VideoCodec();
    private final Muxer muxer = new Muxer();

    public File convert(File src, String format) {               // one simple call...
        var a = audio.extract(src);                              // ...hides all the subsystem steps
        var v = video.transcode(src, format);
        return muxer.mux(a, v);
    }
}
// Client: new VideoConverter().convert(file, "mp4");  — no knowledge of codecs/muxers needed.
```

**Use when:** you want to reduce coupling to a complex library/subsystem and give clients a
convenient high-level API. **Note:** a facade *simplifies*; it doesn't hide the subsystem entirely
(advanced clients can still go direct). Most LLD "service" classes are facades.

---

## Flyweight

**Intent:** minimize memory by **sharing** as much data as possible between many similar objects.
Separate **intrinsic** (shared) state from **extrinsic** (context-specific) state.

**Problem:** you need a huge number of objects, and duplicating their common data would blow memory.

```java
class TreeType {                                                 // intrinsic, shared flyweight
    final String name; final String texture; final Color color;  // heavy, immutable, reused
    TreeType(String n, String t, Color c){ name=n; texture=t; color=c; }
    void draw(int x, int y) { /* render using shared data + extrinsic position */ }
}
class TreeFactory {
    private static final Map<String, TreeType> cache = new HashMap<>();
    static TreeType get(String name, String texture, Color color) {
        return cache.computeIfAbsent(name + texture + color, k -> new TreeType(name, texture, color));
    }
}
class Tree { int x, y; TreeType type; }   // extrinsic x,y per tree; type is shared across thousands
```

**Use when:** you have millions of objects with lots of repeated data (game particles/tiles, text
editor glyphs, map markers). Java's `Integer.valueOf` cache is a flyweight. **Key idea:** pull the
shared/immutable part into one reused object.

---

## Proxy

**Intent:** provide a **surrogate/placeholder** for another object to **control access** to it.

**Problem:** you need to add a layer of control before/around the real object — lazy loading,
access checks, caching, logging, remoting — without changing it.

```java
interface Image { void display(); }
class RealImage implements Image {                               // expensive real object
    RealImage(String file) { loadFromDisk(file); }              // heavy on construction
    public void display() { /* render */ }
    private void loadFromDisk(String f) { /* slow */ }
}
class LazyImageProxy implements Image {                          // same interface as RealImage
    private final String file; private RealImage real;
    LazyImageProxy(String file) { this.file = file; }
    public void display() {
        if (real == null) real = new RealImage(file);           // defer expensive load until needed
        real.display();
    }
}
```

**Common proxy flavors:** *virtual* (lazy load), *protection* (access control), *remote* (stands in
for an object in another process), *caching*, *logging*. **Proxy vs. Decorator:** both wrap and share
the interface — Decorator *adds behavior*; Proxy *controls access* (often same behavior, gated).

---

# Behavioral Patterns

*Concerned with **how objects communicate** — the assignment of responsibilities and the flow of
control between them.*

---

## Iterator

**Intent:** provide a way to **access elements of a collection sequentially** without exposing its
underlying representation.

**Problem:** you want to traverse different collections uniformly, and hide whether it's an array,
tree, or list underneath.

```java
interface Iterator<T> { boolean hasNext(); T next(); }
interface Container<T> { Iterator<T> iterator(); }

class NameRepository implements Container<String> {
    private String[] names = {"Ann", "Bob", "Cara"};
    public Iterator<String> iterator() {
        return new Iterator<>() {
            int i = 0;
            public boolean hasNext() { return i < names.length; }
            public String next()     { return names[i++]; }
        };
    }
}
// for-each in Java is built on this pattern (java.util.Iterator / Iterable).
```

**Use when:** you need multiple/complex traversals or want to decouple traversal from the collection.
Mostly you'll *use* the language's built-in iterators; interviewers ask you to *implement* one to show
you understand the abstraction.

---

## Observer

**Intent:** define a **one-to-many** dependency so that when one object (**subject**) changes state,
all its dependents (**observers**) are **notified automatically**.

**Problem:** many objects need to react to another object's changes, but you don't want the subject
tightly coupled to them.

```java
interface Observer { void update(String event); }

class Subject {
    private final List<Observer> observers = new ArrayList<>();
    public void subscribe(Observer o)   { observers.add(o); }
    public void unsubscribe(Observer o) { observers.remove(o); }
    protected void notifyAll(String event) {
        for (Observer o : observers) o.update(event);           // push to all subscribers
    }
}
class NewsAgency extends Subject { void publish(String news) { notifyAll(news); } }
class EmailSubscriber implements Observer { public void update(String e){ /* send email */ } }
```

**Use when:** event/notification systems, pub-sub, MVC (view observes model), reactive UIs. **LLD
example:** stock-price tickers, a `Cricinfo` scorecard pushing to subscribers, auction bidders
notified of new bids. **Watch:** avoid notification loops and manage unsubscription (leaks).

---

## Strategy

**Intent:** define a **family of interchangeable algorithms**, encapsulate each, and make them
swappable at runtime. Behavior varies independently of the client using it.

**Problem:** you have multiple ways to do one thing (sort, price, route) and want to pick/swap at
runtime without `if/else` ladders.

```java
interface PricingStrategy { double price(double base); }
class RegularPricing  implements PricingStrategy { public double price(double b){ return b; } }
class SalePricing     implements PricingStrategy { public double price(double b){ return b * 0.8; } }
class MemberPricing   implements PricingStrategy { public double price(double b){ return b * 0.9; } }

class Checkout {
    private PricingStrategy strategy;                            // injected/swappable
    void setStrategy(PricingStrategy s) { this.strategy = s; }
    double total(double base) { return strategy.price(base); }  // delegates the "how"
}
```

**Use when:** multiple variants of an algorithm, chosen at runtime — the workhorse of extensible LLD
(payment methods, fee models, sorting, routing). **Strategy vs. State:** structurally similar;
Strategy = client picks the algorithm, State = the object transitions itself between behaviors.

---

## Command

**Intent:** encapsulate a **request as an object**, letting you parameterize, queue, log, and **undo**
operations.

**Problem:** you want to decouple the object that *invokes* an operation from the one that *performs*
it — and treat operations as first-class things (queueable, undoable).

```java
interface Command { void execute(); void undo(); }

class Light { void on(){} void off(){} }
class LightOnCommand implements Command {
    private final Light light; LightOnCommand(Light l){ light = l; }
    public void execute() { light.on(); }
    public void undo()    { light.off(); }
}
class RemoteControl {                                            // invoker — knows nothing about Light
    private final Deque<Command> history = new ArrayDeque<>();
    public void press(Command c) { c.execute(); history.push(c); }
    public void undoLast()       { if(!history.isEmpty()) history.pop().undo(); }
}
```

**Use when:** you need undo/redo, macros, transaction logs, task queues, or GUI actions decoupled from
handlers. **LLD example:** text-editor undo stack, a job scheduler, remote-control buttons.

---

## State

**Intent:** allow an object to **alter its behavior when its internal state changes** — it appears to
change class. Encapsulates state-specific behavior in separate classes.

**Problem:** an object's behavior depends on its state, and a big `switch(state)` in every method is
unmaintainable.

```java
interface State { void insertCoin(VendingMachine m); void dispense(VendingMachine m); }

class NoCoinState implements State {
    public void insertCoin(VendingMachine m){ m.setState(new HasCoinState()); }
    public void dispense(VendingMachine m){ System.out.println("Pay first"); }
}
class HasCoinState implements State {
    public void insertCoin(VendingMachine m){ System.out.println("Already paid"); }
    public void dispense(VendingMachine m){ /* vend */ m.setState(new NoCoinState()); }
}
class VendingMachine {
    private State state = new NoCoinState();
    void setState(State s){ this.state = s; }
    void insertCoin(){ state.insertCoin(this); }                // delegates to current state
    void dispense(){ state.dispense(this); }
}
```

**Use when:** an object has clear states with distinct behavior and defined transitions (vending
machines, order/ticket lifecycle, TCP connection, game modes, traffic signals). Replaces sprawling
state conditionals with polymorphism.

---

## Template Method

**Intent:** define the **skeleton of an algorithm** in a base method, deferring specific steps to
subclasses. Subclasses redefine steps **without changing the overall structure**.

**Problem:** several algorithms share the same steps in the same order but differ in a few steps.

```java
abstract class DataProcessor {
    public final void process() {          // template method — fixed skeleton, `final` so order is locked
        readData();                        // fixed step
        var data = parse();                // ← varies
        transform(data);                   // ← varies
        save();                            // fixed step
    }
    private void readData() { /* common */ }
    protected abstract Object parse();     // subclass fills in
    protected abstract void transform(Object d);
    private void save() { /* common */ }
}
class CsvProcessor  extends DataProcessor { protected Object parse(){/*CSV*/return null;} protected void transform(Object d){} }
class JsonProcessor extends DataProcessor { protected Object parse(){/*JSON*/return null;} protected void transform(Object d){} }
```

**Use when:** multiple variants share an invariant sequence but differ in specific steps.
**Template Method vs. Strategy:** Template Method uses **inheritance** (compile-time, one algorithm
with pluggable steps); Strategy uses **composition** (runtime, whole algorithm swapped).

---

## Visitor

**Intent:** represent an **operation to be performed on the elements of an object structure**, letting
you add new operations **without modifying the elements' classes**.

**Problem:** you have a stable set of element classes and keep needing to add *new operations* over
them; putting each operation in every class violates SRP/OCP.

```java
interface Visitor { void visit(Circle c); void visit(Square s); }
interface Shape   { void accept(Visitor v); }

class Circle implements Shape { float r; public void accept(Visitor v){ v.visit(this); } }  // double-dispatch
class Square implements Shape { float side; public void accept(Visitor v){ v.visit(this); } }

class AreaVisitor implements Visitor {                          // a new operation, no Shape edits
    public void visit(Circle c){ /* π r² */ }
    public void visit(Square s){ /* side² */ }
}
class SvgExportVisitor implements Visitor { /* another new operation */ public void visit(Circle c){} public void visit(Square s){} }
```

**Use when:** the object structure is stable but operations change often (AST traversals, compilers,
report generation over a fixed model). **Trade-off:** easy to add operations, **hard to add new
element types** (every visitor must change) — the inverse trade-off of most patterns. Relies on
**double dispatch**.

---

## Mediator

**Intent:** define an object that **encapsulates how a set of objects interact**, promoting loose
coupling by keeping objects from referring to each other directly.

**Problem:** many objects communicate in a tangle of direct references (many-to-many); changes ripple
everywhere.

```java
interface ChatMediator { void send(String msg, User from); }
class ChatRoom implements ChatMediator {                        // the hub
    private final List<User> users = new ArrayList<>();
    public void register(User u){ users.add(u); }
    public void send(String msg, User from) {
        for (User u : users) if (u != from) u.receive(msg);     // members talk *through* the mediator
    }
}
abstract class User {
    protected final ChatMediator room; User(ChatMediator r){ room = r; }
    abstract void receive(String msg);
    void send(String msg){ room.send(msg, this); }              // never talks to peers directly
}
```

**Use when:** complex many-to-many interactions you want to centralize (chat rooms, air-traffic
control, UI dialogs where widgets affect each other). **Mediator vs. Observer:** Mediator centralizes
*bidirectional* coordination among peers; Observer is *one-way* broadcast subject→observers.

---

## Memento

**Intent:** capture and externalize an object's **internal state** so it can be **restored later** —
without violating encapsulation.

**Problem:** you need undo/checkpoint/rollback, but exposing the object's internals to save them would
break encapsulation.

```java
class EditorMemento {                          // opaque snapshot — only Editor understands it
    private final String content;
    EditorMemento(String c) { this.content = c; }
    String getContent() { return content; }
}
class Editor {
    private String content = "";
    void type(String t) { content += t; }
    EditorMemento save()               { return new EditorMemento(content); }   // create snapshot
    void restore(EditorMemento m)      { content = m.getContent(); }            // roll back
}
class History { private final Deque<EditorMemento> stack = new ArrayDeque<>();
    void push(EditorMemento m){ stack.push(m);} EditorMemento pop(){ return stack.pop(); } }
```

**Use when:** undo/redo, snapshots, transactional rollback, save-points (game saves, editor history).
**Roles:** *Originator* (the object), *Memento* (the snapshot), *Caretaker* (stores mementos, never
inspects them). Often pairs with **Command** for undo.

---

## Chain of Responsibility

**Intent:** pass a request along a **chain of handlers**; each handler decides to **process it or pass
it on** to the next. Decouples sender from receiver.

**Problem:** multiple objects might handle a request and you don't want the sender coupled to a
specific one — or you want a configurable pipeline.

```java
abstract class Handler {
    protected Handler next;
    public Handler setNext(Handler n) { this.next = n; return n; }
    public void handle(Request r) {
        if (canHandle(r)) process(r);
        else if (next != null) next.handle(r);                  // pass along the chain
    }
    abstract boolean canHandle(Request r);
    abstract void process(Request r);
}
class InfoLogger  extends Handler { boolean canHandle(Request r){ return r.level == INFO;  } void process(Request r){} }
class ErrorLogger extends Handler { boolean canHandle(Request r){ return r.level == ERROR; } void process(Request r){} }
// Wire the chain: info.setNext(error);  then info.handle(request);
```

**Use when:** request processing pipelines (logging levels, approval workflows, middleware, event
handling, servlet filters). **Benefit:** add/remove/reorder handlers without touching the sender.
**LLD example:** an ATM dispensing notes (₹500 handler → ₹100 handler → …), leave-approval hierarchy.

---

## Quick Decision Guide

**"Which pattern?" — match the intent, not the label:**

| You need to… | Pattern |
|---|---|
| Guarantee one shared instance | **Singleton** |
| Decide the concrete type at runtime | **Factory Method** |
| Create a consistent family of objects | **Abstract Factory** |
| Build a complex object with many optional parts | **Builder** |
| Copy an existing configured object | **Prototype** |
| Make an incompatible interface fit | **Adapter** |
| Vary two dimensions independently | **Bridge** |
| Treat a tree of objects uniformly | **Composite** |
| Add responsibilities at runtime by wrapping | **Decorator** |
| Simplify a complex subsystem behind one API | **Facade** |
| Share heavy data across millions of objects | **Flyweight** |
| Control access to an object (lazy/guard/cache) | **Proxy** |
| Traverse a collection without exposing it | **Iterator** |
| Notify many objects of a change | **Observer** |
| Swap interchangeable algorithms at runtime | **Strategy** |
| Encapsulate an action (queue/undo/log) | **Command** |
| Change behavior as internal state changes | **State** |
| Fix an algorithm's skeleton, vary steps | **Template Method** |
| Add operations over a fixed class hierarchy | **Visitor** |
| Centralize many-to-many communication | **Mediator** |
| Snapshot & restore state (undo) | **Memento** |
| Pass a request through a handler pipeline | **Chain of Responsibility** |

**Pairs interviewers love to contrast:**
Strategy vs. State · Factory Method vs. Abstract Factory · Adapter vs. Bridge vs. Decorator vs. Proxy
(all wrap!) · Template Method vs. Strategy (inheritance vs. composition) · Observer vs. Mediator.

**Golden rule:** patterns are a *vocabulary*, not a *checklist*. Reach for one only when the problem
exhibits the forces it resolves — forcing a pattern where a plain method suffices violates KISS/YAGNI
and reads as over-engineering.
