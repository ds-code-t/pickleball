package tools.dscode.workbench.catalog;

import tools.dscode.control.protocol.WorkbenchFeatureIndex;
import tools.dscode.workbench.sync.WorkbenchManifest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Workbench catalog entry point for the hash-validated feature index.
 * Discover and confirm in the consumer JVM call {@link WorkbenchFeatureIndex}
 * directly so the controller JAR is not loaded. Both paths share one index.
 */
public final class LaunchSelection {
    private LaunchSelection() {
    }

    public static WorkbenchFeatureIndex.Selection select(
            Path projectRoot,
            WorkbenchManifest manifest,
            String tags,
            String name,
            String example,
            List<String> selects,
            boolean refresh
    ) {
        return WorkbenchFeatureIndex.select(new WorkbenchFeatureIndex.Request(
                projectRoot,
                extraRoots(manifest),
                tags,
                name,
                example,
                selects,
                refresh
        ));
    }

    static List<Path> extraRoots(WorkbenchManifest manifest) {
        if (manifest == null) return List.of();
        List<Path> roots = new ArrayList<>();
        for (String source : manifest.sourceRoots()) {
            if (source == null || source.isBlank()) continue;
            roots.add(Path.of(source));
        }
        if (manifest.liveOutput() != null && !manifest.liveOutput().isBlank()) {
            roots.add(Path.of(manifest.liveOutput()).resolve("features"));
        }
        return List.copyOf(roots);
    }
}
