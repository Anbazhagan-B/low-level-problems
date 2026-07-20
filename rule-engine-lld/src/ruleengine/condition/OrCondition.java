package ruleengine.condition;

import java.util.List;
import java.util.stream.Collectors;

import ruleengine.model.Facts;

/** Composite branch: true if any child is true (short-circuits via anyMatch). */
public final class OrCondition implements Condition {
    private final List<Condition> children;

    public OrCondition(List<Condition> children) {
        this.children = List.copyOf(children);
    }

    @Override
    public boolean evaluate(Facts f) {
        return children.stream().anyMatch(x -> x.evaluate(f));
    }

    @Override
    public String toString() {
        return "(" + children.stream().map(Object::toString).collect(Collectors.joining(" OR ")) + ")";
    }
}
