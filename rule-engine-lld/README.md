# Rule Engine — Reference Implementation

Runnable Java scaffold for the design in [rule-engine-lld.md](rule-engine-lld.md).
No build tool or external dependencies — plain `javac` on JDK 11+.

## Run

```bash
# from this folder
javac -d out $(find src -name '*.java')
java -cp out ruleengine.demo.Main
```

Or use the helper: `./run.sh` (bash) / `run.ps1` (PowerShell).

## Layout (`src/ruleengine/`)

Files are grouped into subpackages by responsibility:

| Package | Files | Role | Pattern |
| --- | --- | --- | --- |
| `model` | `Facts` | immutable input context | — |
| `model` | `ExecutionContext` | per-call fired ids + outcomes | — |
| `model` | `Rule` | condition → actions, priority, enabled | — |
| `condition` | `Operator` | comparison algorithms (==, >, IN, …) | Strategy |
| `condition` | `Condition` | tree component + fluent `and/or/not` | Composite + Specification |
| `condition` | `SimpleCondition` | leaf: field + operator + value | Composite (leaf) |
| `condition` | `AndCondition` / `OrCondition` / `NotCondition` | branches | Composite |
| `action` | `Action` | side-effect on fire | Strategy |
| `strategy` | `EvaluationStrategy` + `FirstMatch` / `AllMatch` | firing policy | Strategy |
| `engine` | `RuleRepository` | thread-safe store (`ConcurrentHashMap`) | — |
| `engine` | `RuleEngine` | orchestrator | — |
| `factory` | `RuleFactory` | fluent builders (JSON/DSL loading point) | Factory |
| `demo` | `Main` | end-to-end demo | — |

## What the demo shows

1. **first-match** — the loan-approval example from the doc.
2. **all-match** — two rules firing in priority order over an OR/NOT tree.
3. **fail-closed** — a missing fact yields no match instead of a crash.
4. **runtime edit** — add/remove rules; the next `fire()` reflects it.
