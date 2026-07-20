package ruleengine.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable key -> value input context evaluated against the rule base.
 * Defensively copies on construction and exposes an unmodifiable view,
 * so a Facts instance is safe to share across threads without synchronization.
 */
public final class Facts {
    private final Map<String, Object> data;

    public Facts(Map<String, Object> d) {
        this.data = Collections.unmodifiableMap(new HashMap<>(d));
    }

    public Object get(String key) {
        return data.get(key);
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    @Override
    public String toString() {
        return "Facts" + data;
    }
}
