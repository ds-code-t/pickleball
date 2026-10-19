package tools.dscode.control.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Gherkin keywords shipped with Cucumber's dialect table ({@code io.cucumber:gherkin:35.1.0}).
 * The protocol module stays JDK-only, so the table is a resource rather than a library dependency.
 */
final class GherkinKeywords {
    private static final Map<String, Set<String>> BY_LANGUAGE = load();
    private static final Set<String> ALL = all(BY_LANGUAGE);

    private GherkinKeywords() {
    }

    static boolean supported(String language) {
        return BY_LANGUAGE.containsKey(canonical(language));
    }

    /**
     * True when {@code head} is a real keyword in some dialect other than {@code language}.
     * A keyword of the file's own language is not foreign. Free text such as {@code Note} is not a keyword.
     */
    static boolean isForeignKeyword(String language, String head) {
        if (head == null || head.isEmpty() || !ALL.contains(head)) return false;
        Set<String> own = BY_LANGUAGE.getOrDefault(canonical(language), Set.of());
        return !own.contains(head);
    }

    private static String canonical(String language) {
        if (language == null || language.isBlank()) return "en";
        String trimmed = language.strip();
        if (BY_LANGUAGE.containsKey(trimmed)) return trimmed;
        for (String key : BY_LANGUAGE.keySet()) {
            if (key.equalsIgnoreCase(trimmed)) return key;
        }
        return trimmed;
    }

    private static Map<String, Set<String>> load() {
        InputStream in = GherkinKeywords.class.getResourceAsStream("gherkin-keywords.tsv");
        if (in == null) {
            throw new IllegalStateException("Missing gherkin-keywords.tsv");
        }
        Map<String, Set<String>> table = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) continue;
                String language = line.substring(0, tab).strip();
                String keyword = line.substring(tab + 1).strip();
                if (language.isEmpty() || keyword.isEmpty() || "*".equals(keyword)) continue;
                table.computeIfAbsent(language, ignored -> new HashSet<>()).add(keyword);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read gherkin-keywords.tsv", failure);
        }
        Map<String, Set<String>> frozen = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : table.entrySet()) {
            frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private static Set<String> all(Map<String, Set<String>> table) {
        Set<String> all = new HashSet<>();
        for (Set<String> keywords : table.values()) all.addAll(keywords);
        return Collections.unmodifiableSet(all);
    }
}
