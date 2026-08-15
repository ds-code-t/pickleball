package tools.dscode.control.api;

import io.cucumber.core.feature.TestFeatureParser;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.gherkin.messages.NGherkinFactory;

import java.net.URI;
import java.util.List;

/** Convenience access to Pickleball's existing Cucumber Gherkin parser and native objects. */
public final class GherkinControl {
    private GherkinControl() {
    }

    public static ControlCallResult<Feature> parseFeature(String source) {
        return parseFeature(URI.create("memory:/control.feature"), source);
    }

    public static ControlCallResult<Feature> parseFeature(URI uri, String source) {
        if (source == null || source.isBlank()) {
            return ControlCallResult.unavailable("feature source must not be blank");
        }
        return attempt(() -> {
            Feature feature = TestFeatureParser.parse(uri, source);
            if (feature == null) {
                throw new IllegalArgumentException("No feature was parsed from the supplied Gherkin.");
            }
            return feature;
        });
    }

    public static List<Pickle> scenarios(Feature feature) {
        return feature == null ? List.of() : List.copyOf(feature.getPickles());
    }

    public static List<Step> steps(Pickle scenario) {
        return scenario == null ? List.of() : List.copyOf(scenario.getSteps());
    }

    public static String argumentText(Step step) {
        return step == null ? "" : NGherkinFactory.getGherkinArgumentText(step);
    }

    private static <T> ControlCallResult<T> attempt(java.util.function.Supplier<T> action) {
        try {
            return ControlCallResult.success(action.get());
        } catch (Throwable error) {
            return ControlCallResult.failed(error);
        }
    }
}
