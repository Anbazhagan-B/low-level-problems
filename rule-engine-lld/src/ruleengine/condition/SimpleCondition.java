package ruleengine.condition;

import ruleengine.model.Facts;

/**
 * Composite leaf: field + Operator + expected value.
 * Fail-closed: a missing fact or a type mismatch evaluates to false (no match)
 * rather than throwing, so one bad rule can't break a whole evaluation.
 * (Trade-off: fail-fast validation at load time is the alternative — see the LLD doc.)
 */
public final class SimpleCondition implements Condition {
    private final String field;
    private final Operator operator;
    private final Object expected;

    public SimpleCondition(String field, Operator operator, Object expected) {
        this.field = field;
        this.operator = operator;
        this.expected = expected;
    }

    @Override
    public boolean evaluate(Facts facts) {
        if (!facts.has(field)) {
            return false;                       // missing fact -> no match
        }
        try {
            return operator.apply(facts.get(field), expected);
        } catch (RuntimeException ex) {
            return false;                       // type mismatch -> no match
        }
    }

    @Override
    public String toString() {
        return field + " " + operator + " " + expected;
    }
}
