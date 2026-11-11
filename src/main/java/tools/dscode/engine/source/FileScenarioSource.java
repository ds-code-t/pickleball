package tools.dscode.engine.source;

import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.parse.GherkinEngineParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Feature files on disk. */
public final class FileScenarioSource implements ScenarioSource {
    private final Path path;

    public FileScenarioSource(Path path) {
        this.path = path;
    }

    @Override
    public String id() {
        return path == null ? "file" : path.toString();
    }

    @Override
    public List<EngineFeature> load() {
        if (path == null || !Files.exists(path)) return List.of();
        try {
            List<Path> files = new ArrayList<>();
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(file -> Files.isRegularFile(file) && file.toString().endsWith(".feature")).sorted().forEach(files::add);
                }
            } else {
                files.add(path);
            }
            List<EngineFeature> features = new ArrayList<>();
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                features.add(GherkinEngineParser.parse(file.toUri().toString(), text));
            }
            return List.copyOf(features);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
