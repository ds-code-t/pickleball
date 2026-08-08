package tools.dscode.common.reporting.diagnostic;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.util.CucumberQueryUtil;
import io.cucumber.messages.types.TableCell;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record ScenarioIdentity(
        String featureUri,
        String scenarioName,
        long scenarioLine,
        Long exampleLine,
        List<String> tags,
        String exampleValuesHash,
        String exactSourceKey,
        String semanticKey,
        String nameKey,
        String tagKey,
        long sourceOrderHint
) {
    public static ScenarioIdentity from(CurrentScenarioState state) {
        return fromPickle(state == null ? null : state.pickle);
    }

    public static ScenarioIdentity from(ScenarioStep step) {
        return fromPickle(step == null ? null : step.getSourcePickle());
    }

    public static ScenarioIdentity fromPickle(Pickle pickle) {
        if (pickle == null) return unknown();
        try {
            CucumberQueryUtil.GherkinView view = CucumberQueryUtil.describe(pickle);
            String uri = uri(pickle);
            String name = view.scenario == null ? "" : view.scenario.getName();
            long scenarioLine = view.scenarioLocation == null ? 0 : view.scenarioLocation.getLine();
            Long exampleLine = view.exampleRow == null ? null : view.exampleRow.getLocation().getLine();
            List<String> tags = new ArrayList<>(view.pickleTags);
            tags.sort(Comparator.naturalOrder());
            String values = view.exampleRow == null ? "" : view.exampleRow.getCells().stream()
                    .map(TableCell::getValue)
                    .map(ScenarioIdentity::normalize)
                    .reduce((a, b) -> a + "\u001f" + b)
                    .orElse("");
            String valuesHash = values.isBlank() ? "" : shortHash(values);
            String normalizedName = normalize(name);
            String exact = shortHash(uri + "|" + scenarioLine + "|" + (exampleLine == null ? "" : exampleLine));
            String semantic = shortHash(uri + "|" + normalizedName + "|" + valuesHash);
            String nameKey = shortHash(normalizedName);
            String tagKey = tags.isEmpty() ? "" : shortHash(String.join("\u001f", tags));
            return new ScenarioIdentity(
                    uri, name, scenarioLine, exampleLine, List.copyOf(tags), valuesHash,
                    exact, semantic, nameKey, tagKey, scenarioLine
            );
        } catch (Throwable ignored) {
            String uri = uri(pickle);
            String name = pickle.getName() == null ? "" : pickle.getName();
            long line = pickle.getLocation() == null ? 0 : pickle.getLocation().getLine();
            return new ScenarioIdentity(
                    uri, name, line, null, List.of(), "",
                    shortHash(uri + "|" + line),
                    shortHash(uri + "|" + normalize(name)),
                    shortHash(normalize(name)),
                    "", line
            );
        }
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("featureUri", featureUri);
        map.put("scenarioName", scenarioName);
        map.put("scenarioLine", scenarioLine);
        map.put("exampleLine", exampleLine);
        map.put("tags", tags);
        map.put("exampleValuesHash", exampleValuesHash);
        map.put("exactSourceKey", exactSourceKey);
        map.put("semanticKey", semanticKey);
        map.put("nameKey", nameKey);
        map.put("tagKey", tagKey);
        map.put("sourceOrderHint", sourceOrderHint);
        return map;
    }

    public String infoText() {
        return "Scenario source: uri='" + featureUri + "', scenario='" + scenarioName
                + "', scenarioLine=" + scenarioLine
                + (exampleLine == null ? "" : ", exampleLine=" + exampleLine)
                + ", exactSourceKey=" + exactSourceKey
                + ", semanticKey=" + semanticKey;
    }

    private static ScenarioIdentity unknown() {
        return new ScenarioIdentity("", "", 0, null, List.of(), "",
                shortHash("unknown"), shortHash("unknown"), shortHash("unknown"), "", 0);
    }

    private static String uri(Pickle pickle) {
        try {
            return pickle == null || pickle.getUri() == null ? "" : pickle.getUri().toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String shortHash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(24);
            for (int i = 0; i < 12; i++) out.append(String.format("%02x", hash[i]));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
