package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.util.CucumberQueryUtil;
import io.cucumber.messages.types.Background;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.RuleChild;
import io.cucumber.messages.types.Step;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background steps are identified by Gherkin AST ids. Cached pickles are not mutated.
 * An empty id set means the caller must leave the pickle steps unchanged.
 * A lookup failure is raised instead of being reported as an empty id set.
 */
final class CalledFeatureBackground {
    private static final ConcurrentHashMap<String, Set<String>> IDS_BY_URI = new ConcurrentHashMap<>();

    private CalledFeatureBackground() {
    }

    static void omitBackgroundSteps(Pickle pickle, List<PickleStepTestStep> steps) {
        Set<String> backgroundIds = backgroundStepIds(pickle);
        if (backgroundIds.isEmpty() || steps == null || steps.isEmpty()) {
            return;
        }
        steps.removeIf(step -> isBackgroundStep(step, backgroundIds));
    }

    private static boolean isBackgroundStep(PickleStepTestStep step, Set<String> backgroundStepIds) {
        if (step == null || step.getPickleStep() == null || step.getPickleStep().getAstNodeIds() == null) {
            return false;
        }
        for (String id : step.getPickleStep().getAstNodeIds()) {
            if (backgroundStepIds.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> backgroundStepIds(Pickle pickle) {
        if (pickle == null || pickle.getUri() == null) {
            return Set.of();
        }
        String uri = pickle.getUri().toString();
        return IDS_BY_URI.computeIfAbsent(uri, key -> load(pickle));
    }

    private static Set<String> load(Pickle pickle) {
        try {
            Optional<Feature> feature = CucumberQueryUtil.featureOf(pickle);
            if (feature.isEmpty() || feature.get().getChildren() == null) {
                return Set.of();
            }
            Set<String> ids = new HashSet<>();
            for (FeatureChild child : feature.get().getChildren()) {
                child.getBackground().ifPresent(background -> addSteps(ids, background));
                child.getRule().ifPresent(rule -> {
                    if (rule.getChildren() == null) {
                        return;
                    }
                    for (RuleChild ruleChild : rule.getChildren()) {
                        ruleChild.getBackground().ifPresent(background -> addSteps(ids, background));
                    }
                });
            }
            return Set.copyOf(ids);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Could not determine background steps for '"
                            + (pickle.getUri() == null ? pickle : pickle.getUri())
                            + "': "
                            + (exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage()),
                    exception
            );
        }
    }

    private static void addSteps(Set<String> ids, Background background) {
        if (background == null || background.getSteps() == null) {
            return;
        }
        for (Step step : background.getSteps()) {
            if (step != null && step.getId() != null) {
                ids.add(step.getId());
            }
        }
    }
}
