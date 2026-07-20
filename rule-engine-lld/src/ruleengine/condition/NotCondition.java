package ruleengine.condition;

import ruleengine.model.Facts;

/** Composite branch: negates its single child. */
public final class NotCondition implements Condition {
    private final Condition child;

    public NotCondition(Condition child) {
        this.child = child;
    }

    @Override
    public boolean evaluate(Facts f) {
        return !child.evaluate(f);
    }

    @Override
    public String toString() {
        return "NOT " + child;
    }
}
