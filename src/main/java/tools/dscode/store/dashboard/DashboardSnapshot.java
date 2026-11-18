package tools.dscode.store.dashboard;

import java.util.List;

/**
 * One passive page of store history. Secret values are not carried:
 * {@code valueRef} is a reference or {@code ~MASK;}.
 */
public record DashboardSnapshot(
        String generatedAt,
        List<RunView> runs,
        List<Marker> markers,
        List<Count> entities,
        List<Count> cases,
        List<Count> errors,
        List<Count> caseTypes
) {
    public DashboardSnapshot {
        runs = runs == null ? List.of() : List.copyOf(runs);
        markers = markers == null ? List.of() : List.copyOf(markers);
        entities = entities == null ? List.of() : List.copyOf(entities);
        cases = cases == null ? List.of() : List.copyOf(cases);
        errors = errors == null ? List.of() : List.copyOf(errors);
        caseTypes = caseTypes == null ? List.of() : List.copyOf(caseTypes);
        generatedAt = generatedAt == null ? "" : generatedAt;
    }

    public record RunView(
            String id,
            String startedAt,
            String finishedAt,
            String status,
            String scenario,
            int passed,
            int failed,
            String note,
            String gitCommit,
            String featurePath,
            String commitUrl,
            List<String> features,
            List<String> dataFiles,
            List<String> artifacts,
            List<String> scenarios
    ) {
        public RunView {
            features = features == null ? List.of() : List.copyOf(features);
            dataFiles = dataFiles == null ? List.of() : List.copyOf(dataFiles);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        }
    }

    public record Marker(String at, String kind, String id, String label, String runId) {
    }

    public record Count(String key, int count) {
    }
}
