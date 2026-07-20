package ruleengine.strategy;

import java.util.List;

import ruleengine.model.ExecutionContext;
import ruleengine.model.Facts;
import ruleengine.model.Rule;

/**
 * Strategy #2: the policy for how many matching rules fire.
 * Injected into the engine, so the same rule base runs first-match or all-match.
 */
public interface EvaluationStrategy {
    void evaluate(List<Rule> ordered, Facts f, ExecutionContext ctx);
}
