package ruleengine.engine;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import ruleengine.model.Rule;

/**
 * Thread-safe store of rules. The only shared mutable state in the system.
 * ConcurrentHashMap gives atomic put/remove and non-blocking reads;
 * priorityOrdered() snapshots into a fresh list so live edits during an
 * evaluation can't cause a ConcurrentModificationException.
 *
 * Sort: priority descending, id ascending as a stable tiebreaker for determinism.
 */
public final class RuleRepository {
    private final ConcurrentHashMap<String, Rule> rules = new ConcurrentHashMap<>();

    public void addOrUpdate(Rule r) {
        rules.put(r.id(), r);
    }

    public void remove(String id) {
        rules.remove(id);
    }

    public List<Rule> priorityOrdered() {
        return rules.values().stream()
                .sorted(Comparator.comparingInt(Rule::priority).reversed()
                        .thenComparing(Rule::id))
                .collect(Collectors.toList());
    }
}
