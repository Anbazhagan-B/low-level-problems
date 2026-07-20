package ruleengine.condition;

import java.util.List;

import ruleengine.model.Facts;

/**
 * The Specification abstraction and the Composite component type.
 * Both leaves (SimpleCondition) and branches (And/Or/Not) implement this,
 * so the engine treats any condition tree uniformly via evaluate().
 * The default combinators give a fluent builder: a.and(b).or(c).
 */
public interface Condition {
    boolean evaluate(Facts facts);

    default Condition and(Condition other) {
        return new AndCondition(List.of(this, other));
    }

    default Condition or(Condition other) {
        return new OrCondition(List.of(this, other));
    }

    default Condition not() {
        return new NotCondition(this);
    }
}
