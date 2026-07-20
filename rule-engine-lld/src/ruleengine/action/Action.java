package ruleengine.action;

import ruleengine.model.ExecutionContext;
import ruleengine.model.Facts;

/**
 * A side-effect performed when a rule fires. Functional interface, so actions
 * can be lambdas. New effects (log, notify, mutate context) are new Actions —
 * the engine never changes (Open/Closed Principle).
 *
 * Thread-safety of the side-effect itself is the action author's responsibility.
 */
@FunctionalInterface
public interface Action {
    void execute(Facts facts, ExecutionContext ctx);
}
