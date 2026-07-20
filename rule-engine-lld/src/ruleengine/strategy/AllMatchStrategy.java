package ruleengine.strategy;

import java.util.List;

import ruleengine.model.ExecutionContext;
import ruleengine.model.Facts;
import ruleengine.model.Rule;

/** Fires every matching rule, in priority order. */
public final class AllMatchStrategy implements EvaluationStrategy {
    @Override
    public void evaluate(List<Rule> rules, Facts f, ExecutionContext ctx) {
        for (Rule r : rules) {
            if (r.matches(f)) {
                r.fire(f, ctx);
            }
        }
    }
}
