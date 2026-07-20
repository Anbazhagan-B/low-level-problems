package ruleengine.factory;

import java.util.List;

import ruleengine.condition.AndCondition;
import ruleengine.condition.Condition;
import ruleengine.condition.NotCondition;
import ruleengine.condition.Operator;
import ruleengine.condition.OrCondition;
import ruleengine.condition.SimpleCondition;

/**
 * Factory helpers for building condition trees fluently in code.
 *
 * In a real system this is where JSON/DSL deserialization would live
 * (e.g. Jackson polymorphic types) — the point of the Factory is that rules
 * become data loadable from a file/DB with zero engine changes. Kept
 * dependency-free here so the scaffold compiles with plain javac.
 */
public final class RuleFactory {
    private RuleFactory() {}

    public static Condition cond(String field, Operator op, Object expected) {
        return new SimpleCondition(field, op, expected);
    }

    public static Condition all(Condition... children) {
        return new AndCondition(List.of(children));
    }

    public static Condition any(Condition... children) {
        return new OrCondition(List.of(children));
    }

    public static Condition none(Condition child) {
        return new NotCondition(child);
    }
}
