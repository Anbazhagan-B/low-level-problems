package ruleengine.engine;

import ruleengine.model.ExecutionContext;
import ruleengine.model.Facts;
import ruleengine.strategy.EvaluationStrategy;

/**
 * Orchestrator. Holds the repository and an injected EvaluationStrategy.
 * fire() is pure aside from the actions' side-effects: it builds a per-call
 * ExecutionContext, snapshots the ordered rules and delegates to the strategy.
 * No locks, no shared mutable state -> safe for concurrent calls.
 */
public final class RuleEngine {
    private final RuleRepository repo;
    private final EvaluationStrategy strategy;

    public RuleEngine(RuleRepository repo, EvaluationStrategy strategy) {
        this.repo = repo;
        this.strategy = strategy;
    }

    public ExecutionContext fire(Facts facts) {
        ExecutionContext ctx = new ExecutionContext();
        strategy.evaluate(repo.priorityOrdered(), facts, ctx);
        return ctx;
    }
}
