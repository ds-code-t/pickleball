package tools.dscode.engine.source;

import tools.dscode.store.StoreQuery;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.parse.GherkinEngineParser;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/** Feature text stored in a Store database row. The Cucumber parser is not used. */
public final class StoreScenarioSource implements ScenarioSource {
    private final Path project;
    private final String featurePath;

    public StoreScenarioSource(Path project, String featurePath) {
        this.project = project;
        this.featurePath = featurePath == null || featurePath.isBlank() ? null : featurePath;
    }

    @Override
    public String id() {
        return "store:" + (featurePath == null ? "*" : featurePath);
    }

    @Override
    public List<EngineFeature> load() {
        try {
            List<EngineFeature> features = new ArrayList<>();
            for (StoreQuery.FeatureSummary summary : StoreQuery.features(project)) {
                if (featurePath != null && !featurePath.equals(summary.path())) continue;
                StoreQuery.FileDetail detail = StoreQuery.file(project, summary.path());
                if (detail == null || detail.text() == null) continue;
                features.add(GherkinEngineParser.parse("store:" + summary.path(), detail.text()));
            }
            return List.copyOf(features);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
