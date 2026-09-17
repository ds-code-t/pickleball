package tools.dscode.workbench.nav;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves Explorer/Report/MCP navigation to a peek target.
 * Prefers pack-local {@code source/files/} copies and never checks out git.
 */
public final class WorkbenchGoResolver {
    private final Path projectRoot;
    private final Path diagnosticRoot;

    public WorkbenchGoResolver(Path projectRoot) {
        this(projectRoot, projectRoot == null
                ? null
                : projectRoot.resolve("reports").resolve("diagnostic-runs"));
    }

    public WorkbenchGoResolver(Path projectRoot, Path diagnosticRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        this.diagnosticRoot = diagnosticRoot == null ? null : diagnosticRoot.toAbsolutePath().normalize();
    }

    public WorkbenchGoResult resolve(WorkbenchGoLink link) {
        WorkbenchGoLink request = link == null ? WorkbenchGoLink.fromMap(java.util.Map.of()) : link;
        if ("mapping".equals(request.to()) || "report".equals(request.to()) || "terminal".equals(request.to())) {
            return WorkbenchGoResult.panel(request.to(), request.label());
        }
        String relative = sanitizeRelative(request.path());
        if (relative.isBlank()) {
            if ("explorer".equals(request.to())) {
                return WorkbenchGoResult.panel("explorer", request.label().isBlank() ? "Open explorer" : request.label());
            }
            return WorkbenchGoResult.missing(request, "No file path on this target.");
        }
        if (isFrameworkPath(relative, request.kind())) {
            return WorkbenchGoResult.missing(request, "Framework sources are not opened from the JAR.");
        }
        Path packCopy = packCopy(request.runId(), relative);
        if (packCopy != null) {
            return WorkbenchGoResult.peek(request, packCopy, relative, "Opened pack copy " + relative);
        }
        Path projectFile = projectFile(relative);
        if (projectFile == null) {
            if (outsideProject(relative)) {
                return WorkbenchGoResult.outside(request, "Path is outside the consumer project.");
            }
            return WorkbenchGoResult.missing(request, "Missing file: " + relative);
        }
        return WorkbenchGoResult.peek(request, projectFile, relative, "Opened " + relative);
    }

    private Path packCopy(String runId, String relative) {
        if (diagnosticRoot == null || runId == null || runId.isBlank() || runId.contains("..")
                || runId.contains("/") || runId.contains("\\")) {
            return null;
        }
        Path copy = diagnosticRoot.resolve(runId).resolve("source").resolve("files").resolve(relative).normalize();
        Path filesRoot = diagnosticRoot.resolve(runId).resolve("source").resolve("files").normalize();
        if (!copy.startsWith(filesRoot)) return null;
        return Files.isRegularFile(copy) ? copy : null;
    }

    private Path projectFile(String relative) {
        if (projectRoot == null) return null;
        Path resolved = projectRoot.resolve(relative).normalize();
        if (!resolved.startsWith(projectRoot)) return null;
        return Files.isRegularFile(resolved) ? resolved : null;
    }

    private boolean outsideProject(String relative) {
        if (projectRoot == null) return true;
        Path resolved = projectRoot.resolve(relative).normalize();
        return !resolved.startsWith(projectRoot);
    }

    private static String sanitizeRelative(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith("/")) return "";
        if (normalized.contains("..")) return "";
        return normalized;
    }

    static boolean isFrameworkPath(String relative, String kind) {
        String value = relative == null ? "" : relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        if ("java".equalsIgnoreCase(kind) && value.contains("tools/dscode/") && value.startsWith("src/main/java/")) {
            return true;
        }
        return value.contains("/pickleball-") && value.endsWith(".jar");
    }

    public record WorkbenchGoResult(
            String to,
            Path file,
            String relativePath,
            int line,
            String kind,
            String label,
            boolean peek,
            boolean readOnly,
            boolean missing,
            boolean outsideProject,
            boolean movesUi,
            String message
    ) {
        public static WorkbenchGoResult peek(WorkbenchGoLink link, Path file, String relative, String message) {
            String to = "explorer".equals(link.to()) ? "explorer" : "editor";
            return new WorkbenchGoResult(
                    to, file, relative, link.line(), link.kind(),
                    link.label().isBlank() ? relative : link.label(),
                    true, true, false, false, true, message
            );
        }

        public static WorkbenchGoResult missing(WorkbenchGoLink link, String message) {
            return new WorkbenchGoResult(
                    link.to(), null, link.path(), link.line(), link.kind(),
                    link.label(), true, true, true, false, false, message
            );
        }

        public static WorkbenchGoResult outside(WorkbenchGoLink link, String message) {
            return new WorkbenchGoResult(
                    link.to(), null, link.path(), link.line(), link.kind(),
                    link.label(), true, true, true, true, false, message
            );
        }

        public static WorkbenchGoResult panel(String to, String label) {
            return new WorkbenchGoResult(
                    to, null, "", 0, "", label, false, false, false, false, true,
                    label.isBlank() ? ("Open " + to) : label
            );
        }
    }
}
