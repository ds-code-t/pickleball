package tools.dscode.engine.source;

import tools.dscode.dalton.DaltonQuery;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.parse.GherkinEngineParser;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/** Feature text stored in a Dalton database row. The Cucumber parser is not used. */
public final class DaltonScenarioSource implements ScenarioSource {
    private final Path project;
    private final String featurePath;

    public DaltonScenarioSource(Path project, String featurePath) {
        this.project = project;
        this.featurePath = featurePath == null || featurePath.isBlank() ? null : featurePath;
    }

    @Override
    public String id() {
        return "dalton:" + (featurePath == null ? "*" : featurePath);
    }

    @Override
    public List<EngineFeature> load() {
        try {
            List<EngineFeature> features = new ArrayList<>();
            for (DaltonQuery.FeatureSummary summary : DaltonQuery.features(project)) {
                if (featurePath != null && !featurePath.equals(summary.path())) continue;
                DaltonQuery.FileDetail detail = DaltonQuery.file(project, summary.path());
                if (detail == null || detail.text() == null) continue;
                features.add(GherkinEngineParser.parse("dalton:" + summary.path(), detail.text()));
            }
            return List.copyOf(features);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
