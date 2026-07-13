package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.util.CucumberQueryUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public aspect PickleRunMetadataAspect {

    private static final Pattern DESCRIPTION_ENTRY =
            Pattern.compile("^\\s*-\\s*([A-Z][A-Z0-9_-]*)\\s*:(.*)$");

    private static final Map<String, Object> EMPTY_DESCRIPTION_MAP = Map.of();

    /** null = not yet resolved; "" is cached when the scenario has no description. */
    private String GherkinMessagesPickle.descriptionText;

    /** null = not yet resolved; empty map is cached for pickles with no metadata. */
    private Map<String, Object> GherkinMessagesPickle.descriptionMap;

    public abstract Map<String, Object> Pickle.getDescriptionMap();

    public String GherkinMessagesPickle.getDescriptionText() {
        String cached = this.descriptionText;
        if (cached != null) {
            return cached;
        }
        String description = CucumberQueryUtil.scenarioOf(this).getDescription();
        cached = (description == null) ? "" : description;
        this.descriptionText = cached;
        return cached;
    }

    public Map<String, Object> GherkinMessagesPickle.getDescriptionMap() {
        Map<String, Object> cached = this.descriptionMap;
        if (cached != null) {
            return cached;
        }
        String description = this.getDescriptionText();
        cached = description.isBlank() ? EMPTY_DESCRIPTION_MAP : parseDescriptionMap(description);
        this.descriptionMap = cached;
        return cached;
    }

    private static Map<String, Object> parseDescriptionMap(String description) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (String rawLine : description.split("\\R")) {
            Matcher matcher = DESCRIPTION_ENTRY.matcher(rawLine);
            if (matcher.matches()) {
                entries.put(matcher.group(1).strip(), matcher.group(2).strip());
            }
        }
        return entries.isEmpty() ? EMPTY_DESCRIPTION_MAP : entries;
    }
}