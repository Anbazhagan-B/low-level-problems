package ruleengine.condition;

import java.util.List;
import java.util.stream.Collectors;

import ruleengine.model.Facts;

/** Composite branch: true only if every child is true (short-circuits via allMatch). */
public final class AndCondition implements Condition {
    private final List<Condition> children;

    public AndCondition(List<Condition> children) {
        this.children = List.copyOf(children);
    }

    @Override
    public boolean evaluate(Facts f) {
        return children.stream().allMatch(x -> x.evaluate(f));
    }

    @Override
    public String toString() {
        return "(" + children.stream().map(Object::toString).collect(Collectors.joining(" AND ")) + ")";
    }
}
