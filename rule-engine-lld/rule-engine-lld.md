# Rule Engine — LLD Interview Prep (Java)

Difficulty: Medium. Core patterns: **Composite + Strategy + Specification** (also Interpreter, Factory, optional Observer).

---

## 1. Requirements

**Functional**

- Rule = _condition(s) → action(s)_. Evaluate a `Facts` context against the rule base; return which rules fired and the resulting outcomes.
- Composite conditions: AND / OR / NOT, arbitrarily nested.
- Pluggable operators: ==, !=, >, <, >=, <=, contains, in, matches.
- Rules have priority + enabled flag.
- Two modes: first-match (highest-priority match wins) and all-match.
- Runtime add/update/remove of rules.
- Explainability: report which rules fired.

**Non-functional**

- Extensibility (OCP): new operators/conditions/actions without editing the engine.
- Performance path (indexing / Rete at scale).
- Thread-safety under concurrent evaluation + live edits.
- Rules loadable from JSON/DB/DSL.

**Clarifying Qs → assumptions**: generic domain; runtime-editable; support first-match AND all-match; actions record outcomes into a context (side-effects pluggable); one Facts context per call, engine reusable.

---

## 2. Entities & relationships

| Entity                        | Type            | Responsibility                                            |
| ----------------------------- | --------------- | --------------------------------------------------------- |
| Facts                         | class           | immutable key→value context (input)                       |
| Condition                     | interface       | `boolean evaluate(Facts)` — the Specification abstraction |
| SimpleCondition               | class           | leaf: field + Operator + expected                         |
| And/Or/NotCondition           | classes         | composite nodes holding child Conditions                  |
| Operator                      | enum (Strategy) | each comparison algorithm                                 |
| Action                        | interface       | `execute(Facts, ExecutionContext)`                        |
| Rule                          | class           | id, priority, enabled, one Condition, List<Action>        |
| RuleEngine                    | class           | sorts, evaluates, delegates to strategy                   |
| EvaluationStrategy            | interface       | FirstMatch vs AllMatch                                    |
| ExecutionContext / RuleResult | class           | fired rule ids + outcomes (explainability)                |
| RuleRepository                | class           | thread-safe store (ConcurrentHashMap)                     |

**Relationships**

- Rule **composition** Condition (owns the condition tree).
- And/Or **composition** of child Conditions (Composite tree).
- SimpleCondition **dependency** on Operator (shared enum).
- Rule **aggregation** of Actions (reusable independently).
- RuleEngine **aggregation** RuleRepository → Rules.
- RuleEngine **dependency** on EvaluationStrategy (injected).

Key insight: `Condition` IS the **Specification pattern** — a reusable, and/or/not-combinable predicate.

---

## 3. Patterns & SOLID

- **Composite** — uniform treatment of leaf + nested condition tree via `evaluate`.
- **Interpreter** — the same tree is an expression grammar evaluated against a context.
- **Strategy** — (1) Operator comparison, (2) EvaluationStrategy first/all-match.
- **Specification** — the and/or/not combinators on Condition.
- **Factory/Builder** — RuleFactory builds Condition trees from JSON/DSL.
- **Observer** (optional) — "rule fired" events for audit/metrics.

SOLID: **OCP** is the headline — new operator = new enum constant; new combinator = new Condition impl; new effect = new Action; engine never changes. SRP (condition decides / action acts / engine orchestrates), LSP (all conditions substitutable), ISP (single-method interfaces), DIP (engine depends on abstractions).

Two decisions interviewers probe: (1) adding operators/conditions without editing engine (OCP); (2) thread-safety under concurrent reads + live edits (immutable rules + concurrent repo).

---

## 4. Implementation (Java 11+)

```java
public final class Facts {
    private final Map<String, Object> data;
    public Facts(Map<String, Object> d){ this.data = Collections.unmodifiableMap(new HashMap<>(d)); }
    public Object get(String k){ return data.get(k); }
    public boolean has(String k){ return data.containsKey(k); }
}

public enum Operator {
    EQUALS      { public boolean apply(Object a, Object b){ return Objects.equals(a,b);} },
    NOT_EQUALS  { public boolean apply(Object a, Object b){ return !Objects.equals(a,b);} },
    GREATER_THAN{ public boolean apply(Object a, Object b){ return compare(a,b) > 0;} },
    LESS_THAN   { public boolean apply(Object a, Object b){ return compare(a,b) < 0;} },
    GTE         { public boolean apply(Object a, Object b){ return compare(a,b) >= 0;} },
    LTE         { public boolean apply(Object a, Object b){ return compare(a,b) <= 0;} },
    CONTAINS    { public boolean apply(Object a, Object b){ return String.valueOf(a).contains(String.valueOf(b));} },
    IN          { public boolean apply(Object a, Object b){ return (b instanceof Collection) && ((Collection<?>)b).contains(a);} };
    public abstract boolean apply(Object actual, Object expected);
    @SuppressWarnings({"unchecked","rawtypes"})
    protected static int compare(Object a, Object b){
        if (a instanceof Number && b instanceof Number)
            return Double.compare(((Number)a).doubleValue(), ((Number)b).doubleValue());
        if (a instanceof Comparable && b instanceof Comparable) return ((Comparable)a).compareTo(b);
        throw new IllegalArgumentException("Not comparable: " + a + ", " + b);
    }
}

public interface Condition {
    boolean evaluate(Facts facts);
    default Condition and(Condition o){ return new AndCondition(List.of(this,o)); }
    default Condition or(Condition o){ return new OrCondition(List.of(this,o)); }
    default Condition not(){ return new NotCondition(this); }
}

public final class SimpleCondition implements Condition {
    private final String field; private final Operator operator; private final Object expected;
    public SimpleCondition(String f, Operator op, Object e){ field=f; operator=op; expected=e; }
    public boolean evaluate(Facts facts){
        if (!facts.has(field)) return false;                 // missing fact -> no match
        try { return operator.apply(facts.get(field), expected); }
        catch (RuntimeException ex){ return false; }         // type mismatch -> no match
    }
}
public final class AndCondition implements Condition {
    private final List<Condition> c; public AndCondition(List<Condition> ch){ c = List.copyOf(ch); }
    public boolean evaluate(Facts f){ return c.stream().allMatch(x -> x.evaluate(f)); }
}
public final class OrCondition implements Condition {
    private final List<Condition> c; public OrCondition(List<Condition> ch){ c = List.copyOf(ch); }
    public boolean evaluate(Facts f){ return c.stream().anyMatch(x -> x.evaluate(f)); }
}
public final class NotCondition implements Condition {
    private final Condition child; public NotCondition(Condition c){ child = c; }
    public boolean evaluate(Facts f){ return !child.evaluate(f); }
}

public interface Action { void execute(Facts facts, ExecutionContext ctx); }

public final class ExecutionContext {
    private final List<String> fired = new ArrayList<>();
    private final Map<String,Object> outcomes = new HashMap<>();
    public void recordFired(String id){ fired.add(id); }
    public void put(String k, Object v){ outcomes.put(k,v); }
    public List<String> firedRuleIds(){ return List.copyOf(fired); }
    public Map<String,Object> outcomes(){ return Map.copyOf(outcomes); }
}

public final class Rule {
    private final String id; private final int priority; private final boolean enabled;
    private final Condition condition; private final List<Action> actions;
    public Rule(String id,int p,boolean en,Condition c,List<Action> a){
        this.id=id; priority=p; enabled=en; condition=c; actions=List.copyOf(a);
    }
    public boolean matches(Facts f){ return enabled && condition.evaluate(f); }
    public void fire(Facts f, ExecutionContext ctx){ ctx.recordFired(id); actions.forEach(a -> a.execute(f,ctx)); }
    public String id(){ return id; } public int priority(){ return priority; }
}

public interface EvaluationStrategy { void evaluate(List<Rule> ordered, Facts f, ExecutionContext ctx); }
public final class AllMatchStrategy implements EvaluationStrategy {
    public void evaluate(List<Rule> rules, Facts f, ExecutionContext ctx){
        for (Rule r : rules) if (r.matches(f)) r.fire(f,ctx);
    }
}
public final class FirstMatchStrategy implements EvaluationStrategy {
    public void evaluate(List<Rule> rules, Facts f, ExecutionContext ctx){
        for (Rule r : rules) if (r.matches(f)){ r.fire(f,ctx); return; }
    }
}

public final class RuleRepository {
    private final Map<String,Rule> rules = new ConcurrentHashMap<>();
    public void addOrUpdate(Rule r){ rules.put(r.id(), r); }
    public void remove(String id){ rules.remove(id); }
    public List<Rule> priorityOrdered(){
        return rules.values().stream()
            .sorted(Comparator.comparingInt(Rule::priority).reversed())
            .collect(Collectors.toList());
    }
}

public final class RuleEngine {
    private final RuleRepository repo; private final EvaluationStrategy strategy;
    public RuleEngine(RuleRepository r, EvaluationStrategy s){ repo=r; strategy=s; }
    public ExecutionContext fire(Facts facts){
        ExecutionContext ctx = new ExecutionContext();   // per-call, no shared mutable state
        strategy.evaluate(repo.priorityOrdered(), facts, ctx);
        return ctx;
    }
}
```

Usage:

```java
Condition premium = new SimpleCondition("creditScore", Operator.GREATER_THAN, 700)
        .and(new SimpleCondition("income", Operator.GTE, 50_000));
Rule approve = new Rule("R1", 10, true, premium,
        List.of((f,ctx) -> ctx.put("decision","APPROVE")));
RuleRepository repo = new RuleRepository(); repo.addOrUpdate(approve);
RuleEngine engine = new RuleEngine(repo, new FirstMatchStrategy());
ExecutionContext res = engine.fire(new Facts(Map.of("creditScore",720,"income",60_000)));
// res.outcomes() -> {decision=APPROVE}; res.firedRuleIds() -> [R1]
```

---

## 5. Exceptions, edge cases, concurrency

- Missing fact -> false (fail-closed). Type mismatch -> caught -> no match (or fail-fast at load; state the trade-off).
- Empty rule base / no match -> empty context; caller supplies default.
- Priority ties -> stable sort; add id tiebreaker for determinism.
- Trees are acyclic by construction (immutable children); cap depth in factory for untrusted input.
- Null expected -> Objects.equals is null-safe.

**Concurrency**: only shared mutable state is the repository map (ConcurrentHashMap, atomic put/remove, non-blocking reads). Rule, Condition, Facts are immutable -> evaluation is pure, lock-free. `fire()` snapshots via priorityOrdered() so live edits can't cause CME. ExecutionContext is per-call. No locks -> no deadlock. Immutability = safe publication without synchronization; that's the clean "free of races" argument. Action side-effects are the action author's thread-safety responsibility.

---

## 6. Follow-ups

1. New operator (BETWEEN/regex) -> new enum constant; nothing else changes (OCP).
2. Scale 100k rules / 10k eps -> pre-sort + cache ordered list (invalidate on mutation); index rules by referenced fields so only candidates evaluate; mention **Rete algorithm** (Drools) sharing condition eval across rules.
3. Transactional updates -> swap whole repository reference atomically (volatile ref to immutable rule list).
4. Explainability -> firedRuleIds already; extend with matched sub-condition trace (Interpreter + accumulating visitor).
5. Load rules from JSON -> RuleFactory (Factory) + Jackson polymorphic deserialization; no engine change.

**Transferable lesson:** Composite + Strategy + Specification is the reusable core for any "evaluate a tree of combinable predicates against a context" problem — query builders, permission/policy engines, validators, feature-flag targeting, pricing/discount engines.

**Next problem:** Discount/Pricing engine (Strategy-heavy) or Elevator System (State pattern).
