package ruleengine.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-call result accumulator: which rule ids fired (explainability) and what
 * outcomes the actions produced. Created fresh for every RuleEngine.fire() call,
 * so concurrent evaluations never share mutable state through it.
 */
public final class ExecutionContext {
    private final List<String> fired = new ArrayList<>();
    private final Map<String, Object> outcomes = new HashMap<>();

    public void recordFired(String id) {
        fired.add(id);
    }

    public void put(String key, Object value) {
        outcomes.put(key, value);
    }

    public List<String> firedRuleIds() {
        return List.copyOf(fired);
    }

    public Map<String, Object> outcomes() {
        return Map.copyOf(outcomes);
    }
}
