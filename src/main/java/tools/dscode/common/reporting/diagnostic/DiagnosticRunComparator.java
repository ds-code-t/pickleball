package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lightweight run-index comparison; never opens scenario JSONL or screenshots. */
public final class DiagnosticRunComparator {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private DiagnosticRunComparator() {
    }

    public static Map<String, Object> compare(Path leftRunIndex, Path rightRunIndex) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> left = JSON.readValue(leftRunIndex.toFile(), LinkedHashMap.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> right = JSON.readValue(rightRunIndex.toFile(), LinkedHashMap.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("leftRunId", left.get("runId"));
        result.put("rightRunId", right.get("runId"));
        result.put("leftOutcome", left.get("outcome"));
        result.put("rightOutcome", right.get("outcome"));
        Map<String, Object> leftMetadata = asMap(left.get("comparisonMetadata"));
        Map<String, Object> rightMetadata = asMap(right.get("comparisonMetadata"));
        result.put("metadataDifferences", mapDifferences(leftMetadata, rightMetadata));
        result.put("sourceComparison", sourceComparison(leftMetadata, rightMetadata));
        List<Map<String, Object>> leftScenarios = asList(left.get("scenarios"));
        List<Map<String, Object>> rightScenarios = asList(right.get("scenarios"));
        List<Map<String, Object>> transitions = scenarioTransitions(leftScenarios, rightScenarios);
        result.put("scenarioTransitions", transitions);
        result.put("failureClusterTransitions", failureClusterTransitions(leftScenarios, rightScenarios));
        result.put("representativeVisualTransitions", representativeVisualTransitions(
                leftRunIndex.getParent(), rightRunIndex.getParent(), transitions
        ));
        return result;
    }

    public static void write(Path leftRunIndex, Path rightRunIndex, Path output) throws IOException {
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        JSON.writeValue(output.toFile(), compare(leftRunIndex, rightRunIndex));
    }

    private static List<Map<String, Object>> scenarioTransitions(
            List<Map<String, Object>> left,
            List<Map<String, Object>> right
    ) {
        List<Map<String, Object>> remaining = new ArrayList<>(right);
        remaining.sort(Comparator.comparingLong(DiagnosticRunComparator::sourceOrder));

        List<Map<String, Object>> transitions = new ArrayList<>();
        left.stream()
                .sorted(Comparator.comparingLong(DiagnosticRunComparator::sourceOrder))
                .forEach(leftScenario -> {
                    Match match = bestMatch(leftScenario, remaining);
                    Map<String, Object> rightScenario = match == null ? null : match.scenario;
                    if (rightScenario != null) remaining.remove(rightScenario);

                    Map<String, Object> transition = new LinkedHashMap<>();
                    transition.put("matchBasis", match == null ? "UNMATCHED" : match.basis);
                    transition.put("matchScore", match == null ? 0 : match.score);
                    transition.put("left", compactScenario(leftScenario));
                    transition.put("right", compactScenario(rightScenario));
                    transition.put("transition", transitionType(leftScenario, rightScenario));
                    transitions.add(transition);
                });

        remaining.forEach(rightScenario -> {
            Map<String, Object> transition = new LinkedHashMap<>();
            transition.put("matchBasis", "UNMATCHED");
            transition.put("matchScore", 0);
            transition.put("left", null);
            transition.put("right", compactScenario(rightScenario));
            transition.put("transition", "NEW");
            transitions.add(transition);
        });
        return transitions;
    }


    private static String transitionType(Map<String, Object> left, Map<String, Object> right) {
        if (left == null) return "NEW";
        if (right == null) return "MISSING";
        String a = text(left.get("outcome"));
        String b = text(right.get("outcome"));
        if ("PASSED".equals(a) && "PASSED".equals(b)) return "PERSISTENT_PASS";
        if ("PASSED".equals(a) && "FAILED".equals(b)) return "NEW_FAILURE";
        if ("FAILED".equals(a) && "PASSED".equals(b)) return "RESOLVED";
        if ("FAILED".equals(a) && "FAILED".equals(b)) {
            String leftSignature = text(left.get("failureSignature"));
            String rightSignature = text(right.get("failureSignature"));
            return !leftSignature.isBlank() && !rightSignature.isBlank() && !leftSignature.equals(rightSignature)
                    ? "CHANGED_FAILURE_SIGNATURE"
                    : "PERSISTENT_FAILURE";
        }
        if ("UNKNOWN".equals(a) || "UNKNOWN".equals(b)) return "INTERRUPTED_OR_UNKNOWN";
        return a + "_TO_" + b;
    }

    private static List<Map<String, Object>> failureClusterTransitions(
            List<Map<String, Object>> left,
            List<Map<String, Object>> right
    ) {
        Map<String, Integer> a = failureCounts(left);
        Map<String, Integer> b = failureCounts(right);
        Set<String> signatures = new LinkedHashSet<>();
        signatures.addAll(a.keySet());
        signatures.addAll(b.keySet());
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (String signature : signatures) {
            int leftCount = a.getOrDefault(signature, 0);
            int rightCount = b.getOrDefault(signature, 0);
            String transition = leftCount == 0 ? "NEW"
                    : rightCount == 0 ? "RESOLVED"
                    : leftCount == rightCount ? "PERSISTENT"
                    : "COUNT_CHANGED";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("failureSignature", signature);
            item.put("leftCount", leftCount);
            item.put("rightCount", rightCount);
            item.put("transition", transition);
            transitions.add(item);
        }
        return transitions;
    }

    private static Map<String, Integer> failureCounts(List<Map<String, Object>> scenarios) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> scenario : scenarios) {
            String signature = text(scenario.get("failureSignature"));
            if (!signature.isBlank()) result.merge(signature, 1, Integer::sum);
        }
        return result;
    }

    private static Match bestMatch(Map<String, Object> left, List<Map<String, Object>> candidates) {
        Match best = null;
        for (Map<String, Object> candidate : candidates) {
            Match scored = score(left, candidate);
            if (scored.score <= 0) continue;
            if (best == null || scored.score > best.score
                    || scored.score == best.score && scored.lineDistance < best.lineDistance) {
                best = scored;
            }
        }
        return best;
    }

    private static Match score(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> a = asMap(left.get("identity"));
        Map<String, Object> b = asMap(right.get("identity"));
        int score;
        String basis;
        if (same(a, b, "exactSourceKey")) {
            score = 100;
            basis = "EXACT_SOURCE";
        } else if (same(a, b, "semanticKey")) {
            score = 80;
            basis = "SEMANTIC";
        } else if (same(a, b, "nameKey")) {
            score = 50;
            basis = "NAME";
        } else if (sameNonBlank(a, b, "featureUri") && sameNonBlank(a, b, "exampleValuesHash")) {
            score = 35;
            basis = "FEATURE_EXAMPLE";
        } else if (sameNonBlank(a, b, "featureUri") && sameNonBlank(a, b, "tagKey")) {
            score = 30;
            basis = "FEATURE_TAGS";
        } else {
            return new Match(right, 0, Integer.MAX_VALUE, "NONE");
        }

        if (sameNonBlank(a, b, "exampleValuesHash")) score += 20;
        if (sameNonBlank(a, b, "tagKey")) score += 10;
        int distance = (int) Math.min(Integer.MAX_VALUE, Math.abs(sourceOrder(left) - sourceOrder(right)));
        if (distance == 0) score += 6;
        else if (distance <= 5) score += 4;
        else if (distance <= 20) score += 2;
        return new Match(right, score, distance, basis);
    }

    private static boolean same(Map<String, Object> left, Map<String, Object> right, String key) {
        String a = text(left.get(key));
        String b = text(right.get(key));
        return !a.isBlank() && a.equals(b);
    }

    private static boolean sameNonBlank(Map<String, Object> left, Map<String, Object> right, String key) {
        return same(left, right, key);
    }

    private static long sourceOrder(Map<String, Object> scenario) {
        Map<String, Object> identity = asMap(scenario.get("identity"));
        Object value = identity.get("sourceOrderHint");
        return value instanceof Number number ? number.longValue() : Long.MAX_VALUE;
    }

    private static Map<String, Object> compactScenario(Map<String, Object> scenario) {
        if (scenario == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenarioExecutionId", scenario.get("scenarioExecutionId"));
        result.put("identity", scenario.get("identity"));
        result.put("outcome", scenario.get("outcome"));
        result.put("durationMillis", scenario.get("durationMillis"));
        result.put("failureSignature", scenario.get("failureSignature"));
        result.put("steps", scenario.get("steps"));
        result.put("nativeCapabilitiesObserved", scenario.get("nativeCapabilitiesObserved"));
        result.put("representativeScreenshots", scenario.get("representativeScreenshots"));
        result.put("summary", scenario.get("summary"));
        return result;
    }

    private static List<Map<String, Object>> representativeVisualTransitions(
            Path leftRunRoot,
            Path rightRunRoot,
            List<Map<String, Object>> scenarioTransitions
    ) {
        if (leftRunRoot == null || rightRunRoot == null) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> transition : scenarioTransitions) {
            Map<String, Object> left = asMap(transition.get("left"));
            Map<String, Object> right = asMap(transition.get("right"));
            if (left.isEmpty() || right.isEmpty()) continue;
            List<Map<String, Object>> pairs = representativePairs(
                    asList(left.get("representativeScreenshots")),
                    asList(right.get("representativeScreenshots"))
            );
            for (Map<String, Object> pair : pairs) {
                Map<String, Object> a = asMap(pair.get("left"));
                Map<String, Object> b = asMap(pair.get("right"));
                String leftRef = text(a.get("fingerprint"));
                String rightRef = text(b.get("fingerprint"));
                if (leftRef.isBlank() || rightRef.isBlank()) continue;
                try {
                    Path leftFingerprint = safeResolve(leftRunRoot, leftRef);
                    Path rightFingerprint = safeResolve(rightRunRoot, rightRef);
                    if (!Files.isRegularFile(leftFingerprint) || !Files.isRegularFile(rightFingerprint)) continue;
                    VisualFingerprintComparator.Result comparison = VisualFingerprintComparator.compare(
                            VisualFingerprint.fromBytes(Files.readAllBytes(leftFingerprint)),
                            VisualFingerprint.fromBytes(Files.readAllBytes(rightFingerprint))
                    );
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("leftScenarioExecutionId", left.get("scenarioExecutionId"));
                    item.put("rightScenarioExecutionId", right.get("scenarioExecutionId"));
                    item.put("reason", pair.get("reason"));
                    item.put("leftScreenshotId", a.get("screenshotId"));
                    item.put("rightScreenshotId", b.get("screenshotId"));
                    item.put("comparison", comparison.asMap());
                    results.add(item);
                } catch (Throwable ignored) {
                }
            }
        }
        return results;
    }

    private static List<Map<String, Object>> representativePairs(
            List<Map<String, Object>> left,
            List<Map<String, Object>> right
    ) {
        Map<String, Map<String, Object>> a = byReason(left);
        Map<String, Map<String, Object>> b = byReason(right);
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (String reason : List.of("FAILURE", "FINAL_VISUAL_STATE", "FIRST_VISUAL_STATE")) {
            if (a.containsKey(reason) && b.containsKey(reason)) {
                pairs.add(Map.of("reason", reason, "left", a.get(reason), "right", b.get(reason)));
            }
        }
        if (pairs.size() < 4) {
            Set<String> reasons = new LinkedHashSet<>(a.keySet());
            reasons.retainAll(b.keySet());
            for (String reason : reasons) {
                if (pairs.stream().anyMatch(pair -> reason.equals(pair.get("reason")))) continue;
                pairs.add(Map.of("reason", reason, "left", a.get(reason), "right", b.get(reason)));
                if (pairs.size() == 4) break;
            }
        }
        return pairs;
    }

    private static Map<String, Map<String, Object>> byReason(List<Map<String, Object>> screenshots) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> screenshot : screenshots) {
            String reason = text(screenshot.get("reason"));
            if (!reason.isBlank()) result.putIfAbsent(reason, screenshot);
        }
        return result;
    }

    private static Path safeResolve(Path root, String relative) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) throw new IllegalArgumentException("Evidence path escapes run root");
        return resolved;
    }

    private static Map<String, Object> sourceComparison(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("sameConsumerCommit", sameValue(left, right, "consumerCommit"));
        comparison.put("samePickleballCommit", sameValue(left, right, "pickleballCommit"));
        comparison.put("samePickleballVersion", sameValue(left, right, "pickleballVersion"));
        comparison.put("leftConsumerDirty", left.get("consumerDirty"));
        comparison.put("rightConsumerDirty", right.get("consumerDirty"));
        comparison.put("leftPickleballDirty", left.get("pickleballDirty"));
        comparison.put("rightPickleballDirty", right.get("pickleballDirty"));
        return comparison;
    }

    private static Boolean sameValue(Map<String, Object> left, Map<String, Object> right, String key) {
        String a = text(left.get(key));
        String b = text(right.get(key));
        return a.isBlank() || b.isBlank() ? null : a.equals(b);
    }

    private static List<Map<String, Object>> mapDifferences(Map<String, Object> left, Map<String, Object> right) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(left.keySet());
        keys.addAll(right.keySet());
        List<Map<String, Object>> differences = new ArrayList<>();
        for (String key : keys) {
            Object a = left.get(key);
            Object b = right.get(key);
            if (java.util.Objects.equals(a, b)) continue;
            Map<String, Object> difference = new LinkedHashMap<>();
            difference.put("key", key);
            difference.put("left", a);
            difference.put("right", b);
            differences.add(difference);
        }
        return differences;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private record Match(Map<String, Object> scenario, int score, int lineDistance, String basis) {}
}
