package ruleengine.demo;

import java.util.List;
import java.util.Map;

import ruleengine.condition.Condition;
import ruleengine.condition.Operator;
import ruleengine.condition.SimpleCondition;
import ruleengine.engine.RuleEngine;
import ruleengine.engine.RuleRepository;
import ruleengine.model.ExecutionContext;
import ruleengine.model.Facts;
import ruleengine.model.Rule;
import ruleengine.strategy.AllMatchStrategy;
import ruleengine.strategy.FirstMatchStrategy;

import static ruleengine.factory.RuleFactory.any;
import static ruleengine.factory.RuleFactory.cond;

/**
 * Runnable demo exercising the engine end-to-end:
 *   1. the loan-approval example from the LLD doc (first-match),
 *   2. all-match mode with priorities and an OR/NOT tree,
 *   3. fail-closed behaviour on a missing fact,
 *   4. a runtime edit (disable + add a rule) taking effect immediately.
 */
public final class Main {
    public static void main(String[] args) {
        demoFirstMatch();
        // demoAllMatch();
        // demoMissingFact();
        // demoRuntimeEdit();
    }

    /** Example from the LLD doc: creditScore > 700 AND income >= 50000 -> APPROVE. */
    private static void demoFirstMatch() {
        Condition premium = new SimpleCondition("creditScore", Operator.GREATER_THAN, 700)
                .and(new SimpleCondition("income", Operator.GTE, 50_000));
        Rule approve = new Rule("R1", 10, true, premium,
                List.of((f, ctx) -> ctx.put("decision", "APPROVE")));

        RuleRepository repo = new RuleRepository();
        repo.addOrUpdate(approve);
        RuleEngine engine = new RuleEngine(repo, new FirstMatchStrategy());

        ExecutionContext res = engine.fire(new Facts(Map.of("creditScore", 720, "income", 60_000)));
        System.out.println("== first-match ==");
        System.out.println("outcomes     = " + res.outcomes());      // {decision=APPROVE}
        System.out.println("firedRuleIds = " + res.firedRuleIds());  // [R1]

        ExecutionContext rejected = engine.fire(new Facts(Map.of("creditScore", 650, "income", 60_000)));
        System.out.println("no match     -> outcomes=" + rejected.outcomes()
                + " fired=" + rejected.firedRuleIds());
    }

    /** Two rules can both fire; higher priority is evaluated first. */
    private static void demoAllMatch() {
        Rule vip = new Rule("VIP", 20, true,
                any(cond("tier", Operator.EQUALS, "GOLD"),
                    cond("lifetimeSpend", Operator.GTE, 10_000)),
                List.of((f, ctx) -> ctx.put("perk", "FREE_SHIPPING")));

        Rule discount = new Rule("DISC", 10, true,
                cond("cartTotal", Operator.GTE, 100),
                List.of((f, ctx) -> ctx.put("discountPct", 15)));

        RuleRepository repo = new RuleRepository();
        repo.addOrUpdate(vip);
        repo.addOrUpdate(discount);
        RuleEngine engine = new RuleEngine(repo, new AllMatchStrategy());

        ExecutionContext res = engine.fire(new Facts(
                Map.of("tier", "SILVER", "lifetimeSpend", 12_000, "cartTotal", 250)));
        System.out.println("\n== all-match ==");
        System.out.println("outcomes     = " + res.outcomes());      // {perk=FREE_SHIPPING, discountPct=15}
        System.out.println("firedRuleIds = " + res.firedRuleIds());  // [VIP, DISC]
    }

    /** Missing fact -> condition is false (fail-closed), no crash. */
    private static void demoMissingFact() {
        Rule needsAge = new Rule("AGE", 5, true,
                cond("age", Operator.GTE, 18),
                List.of((f, ctx) -> ctx.put("adult", true)));

        RuleRepository repo = new RuleRepository();
        repo.addOrUpdate(needsAge);
        RuleEngine engine = new RuleEngine(repo, new AllMatchStrategy());

        ExecutionContext res = engine.fire(new Facts(Map.of("name", "Sam"))); // no "age"
        System.out.println("\n== missing fact (fail-closed) ==");
        System.out.println("outcomes     = " + res.outcomes());      // {}
        System.out.println("firedRuleIds = " + res.firedRuleIds());  // []
    }

    /** Rules are editable at runtime; the next fire() sees the change. */
    private static void demoRuntimeEdit() {
        RuleRepository repo = new RuleRepository();
        repo.addOrUpdate(new Rule("BLOCK", 100, true,
                cond("country", Operator.IN, List.of("XX", "YY")),
                List.of((f, ctx) -> ctx.put("blocked", true))));
        RuleEngine engine = new RuleEngine(repo, new FirstMatchStrategy());

        Facts f = new Facts(Map.of("country", "ZZ"));
        System.out.println("\n== runtime edit ==");
        System.out.println("before: " + engine.fire(f).outcomes());  // {}

        repo.addOrUpdate(new Rule("ALLOW", 1, true,
                cond("country", Operator.NOT_EQUALS, "XX"),
                List.of((ff, ctx) -> ctx.put("access", "GRANTED"))));
        System.out.println("after add ALLOW: " + engine.fire(f).outcomes()); // {access=GRANTED}

        repo.remove("ALLOW");
        System.out.println("after remove ALLOW: " + engine.fire(f).outcomes()); // {}
    }
}
