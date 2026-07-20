package ruleengine.condition;

import java.util.Collection;
import java.util.Objects;

/**
 * Strategy #1: each constant carries its own comparison algorithm.
 * Adding a new operator (e.g. BETWEEN, MATCHES) is a new enum constant only —
 * the engine, conditions and rules never change (Open/Closed Principle).
 */
public enum Operator {
    EQUALS {
        public boolean apply(Object a, Object b) { return Objects.equals(a, b); }
    },
    NOT_EQUALS {
        public boolean apply(Object a, Object b) { return !Objects.equals(a, b); }
    },
    GREATER_THAN {
        public boolean apply(Object a, Object b) { return compare(a, b) > 0; }
    },
    LESS_THAN {
        public boolean apply(Object a, Object b) { return compare(a, b) < 0; }
    },
    GTE {
        public boolean apply(Object a, Object b) { return compare(a, b) >= 0; }
    },
    LTE {
        public boolean apply(Object a, Object b) { return compare(a, b) <= 0; }
    },
    CONTAINS {
        public boolean apply(Object a, Object b) {
            return String.valueOf(a).contains(String.valueOf(b));
        }
    },
    IN {
        public boolean apply(Object a, Object b) {
            return (b instanceof Collection) && ((Collection<?>) b).contains(a);
        }
    };

    public abstract boolean apply(Object actual, Object expected);

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Comparable && b instanceof Comparable) {
            return ((Comparable) a).compareTo(b);
        }
        throw new IllegalArgumentException("Not comparable: " + a + ", " + b);
    }
}
