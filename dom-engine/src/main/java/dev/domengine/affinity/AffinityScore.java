package dev.domengine.affinity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explainable affinity. {@code parts} are the contributions that sum to {@code total} before clamping. */
public record AffinityScore(double total, Map<String, Double> parts, List<String> reasons) {
    public AffinityScore {
        Map<String, Double> copy = new LinkedHashMap<>();
        if (parts != null) copy.putAll(parts);
        parts = copy;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public double sumOfParts() {
        double sum = 0;
        for (double value : parts.values()) sum += value;
        return sum;
    }
}
