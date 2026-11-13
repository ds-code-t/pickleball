package dev.domengine.affinity;

import dev.domengine.cdp.CdpException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Component weights. {@link #defaults()} is the named set (they sum to 0.90).
 * {@link #normalized()} re-normalises them so they sum to 1.
 */
public record AffinityWeights(double proximity, double alignment, double role, double text, double section) {
    public static final String ENV = "DOMENGINE_AFFINITY_WEIGHTS";

    public static AffinityWeights defaults() {
        return new AffinityWeights(0.30, 0.20, 0.15, 0.10, 0.15);
    }

    public AffinityWeights normalized() {
        double sum = proximity + alignment + role + text + section;
        if (sum <= 0) throw new CdpException("usage: affinity weights sum to zero");
        return new AffinityWeights(proximity / sum, alignment / sum, role / sum, text / sum, section / sum);
    }

    /** Overlay {@code spec} ({@code proximity=0.3,alignment=0.2}) on {@link #defaults()}. Unknown keys are a usage error. */
    public static AffinityWeights parse(String spec) {
        AffinityWeights base = defaults();
        if (spec == null || spec.isBlank()) return base;
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("proximity", base.proximity());
        values.put("alignment", base.alignment());
        values.put("role", base.role());
        values.put("text", base.text());
        values.put("section", base.section());
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) throw new CdpException("usage: affinity weight " + trimmed);
            String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (!values.containsKey(key)) throw new CdpException("usage: unknown affinity weight: " + key);
            try {
                values.put(key, Double.parseDouble(trimmed.substring(eq + 1).trim()));
            } catch (NumberFormatException failure) {
                throw new CdpException("usage: affinity weight " + key);
            }
        }
        return new AffinityWeights(
                values.get("proximity"), values.get("alignment"), values.get("role"), values.get("text"), values.get("section")
        );
    }

    public static AffinityWeights fromEnv() {
        String raw = System.getenv(ENV);
        return raw == null || raw.isBlank() ? defaults() : parse(raw);
    }

    public static AffinityWeights fromEnv(Map<String, String> env) {
        if (env == null) return defaults();
        String raw = env.get(ENV);
        return raw == null || raw.isBlank() ? defaults() : parse(raw);
    }
}
