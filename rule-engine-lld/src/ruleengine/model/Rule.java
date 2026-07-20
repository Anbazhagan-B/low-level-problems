package ruleengine.model;

import java.util.List;

import ruleengine.action.Action;
import ruleengine.condition.Condition;

/**
 * A rule = one Condition (tree) -> a list of Actions, plus id/priority/enabled.
 * Immutable: safe to publish and read from any thread without synchronization.
 */
public final class Rule {
    private final String id;
    private final int priority;
    private final boolean enabled;
    private final Condition condition;
    private final List<Action> actions;

    public Rule(String id, int priority, boolean enabled, Condition condition, List<Action> actions) {
        this.id = id;
        this.priority = priority;
        this.enabled = enabled;
        this.condition = condition;
        this.actions = List.copyOf(actions);
    }

    public boolean matches(Facts f) {
        return enabled && condition.evaluate(f);
    }

    public void fire(Facts f, ExecutionContext ctx) {
        ctx.recordFired(id);
        actions.forEach(a -> a.execute(f, ctx));
    }

    public String id() {
        return id;
    }

    public int priority() {
        return priority;
    }

    @Override
    public String toString() {
        return "Rule[" + id + ", p=" + priority + ", enabled=" + enabled + ", when " + condition + "]";
    }
}
