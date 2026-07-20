package vendingmachine.inventory;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import vendingmachine.enums.Denomination;

/**
 * Tracks how many of each denomination the machine physically holds.
 * Iteration order is largest-value-first so greedy change-making is a plain loop.
 * Not internally synchronized — guarded by the machine's lock.
 */
public class CashInventory {
    private final NavigableMap<Denomination, Integer> counts =
            new TreeMap<>(Comparator.comparingInt(Denomination::getValue).reversed());

    public void add(Denomination d, int n) {
        counts.merge(d, n, Integer::sum);
    }

    public void addAll(Collection<Denomination> coins) {
        coins.forEach(c -> add(c, 1));
    }

    /** Rollback support: physically hand coins back out of the drawer. */
    public void removeAll(Collection<Denomination> coins) {
        for (Denomination c : coins) {
            counts.merge(c, -1, Integer::sum);
            if (counts.get(c) < 0) {
                throw new IllegalStateException("cash drawer corrupted");
            }
        }
    }

    /**
     * Greedy: largest denomination first. Correct for canonical currency systems
     * like {1,2,5,10,20,50}; for arbitrary sets you'd swap in a DP strategy.
     * Commits (decrements counts) ONLY if the full amount is reachable —
     * otherwise the inventory is untouched and empty is returned.
     */
    public Optional<Map<Denomination, Integer>> makeChange(int amount) {
        if (amount == 0) {
            return Optional.of(Map.of());
        }
        Map<Denomination, Integer> change = new LinkedHashMap<>();
        int remaining = amount;
        for (Map.Entry<Denomination, Integer> e : counts.entrySet()) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(remaining / e.getKey().getValue(), e.getValue());
            if (take > 0) {
                change.put(e.getKey(), take);
                remaining -= take * e.getKey().getValue();
            }
        }
        if (remaining != 0) {
            return Optional.empty();           // cannot make change
        }
        change.forEach((d, n) -> counts.merge(d, -n, Integer::sum));  // commit
        return Optional.of(change);
    }

    /** Admin: empty the drawer. Zero-count entries are omitted from the report. */
    public Map<Denomination, Integer> collectAll() {
        Map<Denomination, Integer> all = new LinkedHashMap<>();
        counts.forEach((d, n) -> {
            if (n > 0) {
                all.put(d, n);
            }
        });
        counts.clear();
        return all;
    }

    public int totalValue() {
        return counts.entrySet().stream()
                .mapToInt(e -> e.getKey().getValue() * e.getValue()).sum();
    }
}
