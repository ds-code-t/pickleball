package tools.dscode.control.override;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scenario-lane Step Override registry.
 *
 * <p>Runtime bridge commands execute on the selected scenario thread, so a thread-local
 * registry scopes experimental rules to that live lane without mutating Cucumber glue.
 * {@link #clear()} removes all lane-owned state; a fresh worker JVM is the full cleanup path.
 */
public final class StepOverrideRegistry {
    private static final ThreadLocal<Map<String, StepOverrideRule>> CURRENT = new ThreadLocal<>();

    private StepOverrideRegistry() {
    }

    /** Registers or replaces the rule with the same id. */
    public static StepOverrideRule register(StepOverrideRule rule) {
        Map<String, StepOverrideRule> rules = CURRENT.get();
        if (rules == null) {
            rules = new LinkedHashMap<>();
            CURRENT.set(rules);
        }
        return rules.put(rule.id(), rule);
    }

    public static boolean remove(String id) {
        Map<String, StepOverrideRule> rules = CURRENT.get();
        if (rules == null) {
            return false;
        }
        boolean removed = rules.remove(id) != null;
        if (rules.isEmpty()) {
            CURRENT.remove();
        }
        return removed;
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static List<StepOverrideRule> rules() {
        Map<String, StepOverrideRule> rules = CURRENT.get();
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        return rules.values().stream()
                .sorted(Comparator.comparing(StepOverrideRule::id))
                .toList();
    }

    /**
     * Matches REPLACE rules against the resolved step text.
     *
     * <p>Zero matches means normal Cucumber glue must be used. Multiple matches fail
     * deterministically instead of depending on registration order.
     */
    public static Optional<Match> match(String resolvedStepText) {
        Map<String, StepOverrideRule> rules = CURRENT.get();
        if (rules == null || rules.isEmpty()) {
            return Optional.empty();
        }

        List<Match> matches = new ArrayList<>();
        for (StepOverrideRule rule : rules.values()) {
            List<String> captures = rule.matchCaptures(resolvedStepText);
            if (captures != null) {
                matches.add(new Match(rule, captures));
            }
        }

        matches.sort(Comparator.comparing(match -> match.rule().id()));
        if (matches.size() > 1) {
            throw new AmbiguousStepOverrideException(
                    resolvedStepText,
                    matches.stream().map(match -> match.rule().id()).toList()
            );
        }
        return matches.stream().findFirst();
    }

    public record Match(StepOverrideRule rule, List<String> captures) {
        public Match {
            captures = List.copyOf(captures);
        }
    }

    public static final class AmbiguousStepOverrideException extends IllegalStateException {
        private final String stepText;
        private final List<String> ruleIds;

        private AmbiguousStepOverrideException(String stepText, List<String> ruleIds) {
            super("Ambiguous Step Override for step '" + stepText + "': " + String.join(", ", ruleIds));
            this.stepText = stepText;
            this.ruleIds = List.copyOf(ruleIds);
        }

        public String stepText() {
            return stepText;
        }

        public List<String> ruleIds() {
            return ruleIds;
        }
    }
}
