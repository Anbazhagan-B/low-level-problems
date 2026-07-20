package ruleengine.strategy;

import java.util.List;

import ruleengine.model.ExecutionContext;
import ruleengine.model.Facts;
import ruleengine.model.Rule;

/** Fires only the highest-priority matching rule, then stops. */
public final class FirstMatchStrategy implements EvaluationStrategy {
    @Override
    public void evaluate(List<Rule> rules, Facts f, ExecutionContext ctx) {
        for (Rule r : rules) {
            if (r.matches(f)) {
                r.fire(f, ctx);
                return;
            }
        }
    }
}
