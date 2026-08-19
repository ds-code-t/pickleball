package tools.dscode.control.override;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scenario-scoped Step Override registry.
 *
 * <p>Rules may be authored by the loopback bridge off the scenario thread, while
 * matching and handler execution remain on the selected scenario thread.</p>
 */
public final class StepOverrideRegistry {
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, StepOverrideRule>>
            RULES_BY_SCENARIO = new ConcurrentHashMap<>();

    private StepOverrideRegistry() {
    }

    public static StepOverrideRule register(StepOverrideRule rule) {
        return register(requireCurrentScenarioId(), rule);
    }

    /** Registers or replaces one rule for an explicit active scenario id. */
    public static StepOverrideRule register(String scenarioId, StepOverrideRule rule) {
        String key = requireScenarioId(scenarioId);
        StepOverrideRule previous = RULES_BY_SCENARIO
                .computeIfAbsent(key, ignored -> new ConcurrentHashMap<>())
                .put(rule.id(), rule);
        if (previous != null && previous != rule) {
            previous.close();
        }
        return previous;
    }

    public static boolean remove(String id) {
        return remove(requireCurrentScenarioId(), id);
    }

    public static boolean remove(String scenarioId, String id) {
        ConcurrentHashMap<String, StepOverrideRule> rules =
                RULES_BY_SCENARIO.get(requireScenarioId(scenarioId));
        if (rules == null) return false;

        StepOverrideRule removed = rules.remove(requireText(id, "id"));
        if (removed != null) removed.close();
        if (rules.isEmpty()) RULES_BY_SCENARIO.remove(scenarioId, rules);
        return removed != null;
    }

    public static void clear() {
        clear(requireCurrentScenarioId());
    }

    public static int clear(String scenarioId) {
        Map<String, StepOverrideRule> rules =
                RULES_BY_SCENARIO.remove(requireScenarioId(scenarioId));
        if (rules == null) return 0;
        rules.values().forEach(StepOverrideRule::close);
        return rules.size();
    }

    public static List<StepOverrideRule> rules() {
        return rules(requireCurrentScenarioId());
    }

    public static List<StepOverrideRule> rules(String scenarioId) {
        Map<String, StepOverrideRule> rules =
                RULES_BY_SCENARIO.get(requireScenarioId(scenarioId));
        if (rules == null || rules.isEmpty()) return List.of();
        return rules.values().stream()
                .sorted(Comparator.comparing(StepOverrideRule::id))
                .toList();
    }

    public static Optional<Match> match(String resolvedStepText) {
        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        if (state == null) return Optional.empty();

        Map<String, StepOverrideRule> rules =
                RULES_BY_SCENARIO.get(state.id.toString());
        if (rules == null || rules.isEmpty()) return Optional.empty();

        List<Match> matches = new ArrayList<>();
        for (StepOverrideRule rule : rules.values()) {
            List<String> captures = rule.matchCaptures(resolvedStepText);
            if (captures != null) matches.add(new Match(rule, captures));
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

    private static String requireCurrentScenarioId() {
        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        if (state == null) {
            throw new IllegalStateException("Step Override operation requires an active Pickleball scenario.");
        }
        return state.id.toString();
    }

    private static String requireScenarioId(String scenarioId) {
        return requireText(scenarioId, "scenarioId");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        return value.trim();
    }
}
