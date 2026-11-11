package tools.dscode.engine;

import java.nio.file.Path;
import java.util.List;

/** Run options. Workbench, MCP, and Gradle only forward these through the CLI. */
public record EngineOptions(Path project, List<Class<?>> glue, String scenarioName, boolean continueOnFailure) {
    public EngineOptions {
        glue = glue == null ? List.of() : List.copyOf(glue);
        scenarioName = scenarioName == null ? "" : scenarioName;
    }

    public static EngineOptions of(Class<?>... glue) {
        return new EngineOptions(Path.of("").toAbsolutePath().normalize(), List.of(glue), "", false);
    }

    public EngineOptions project(Path path) {
        return new EngineOptions(path, glue, scenarioName, continueOnFailure);
    }

    public EngineOptions scenario(String name) {
        return new EngineOptions(project, glue, name == null ? "" : name, continueOnFailure);
    }
}
