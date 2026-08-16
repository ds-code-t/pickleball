package tools.dscode.studio.workspace;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class WorkspaceFileService {
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "build", "target", "out", "node_modules"
    );
    private static final long MAX_SEARCH_FILE_BYTES = 2 * 1024 * 1024;

    private final Path root;
    private final Path realRoot;

    public WorkspaceFileService(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        try {
            this.realRoot = normalizedRoot.toRealPath();
            this.root = this.realRoot;
        } catch (IOException error) {
            throw new IllegalArgumentException("Workspace directory does not exist: " + normalizedRoot, error);
        }
    }

    public List<WorkspaceEntry> tree(String relativePath, int maxDepth, int maxEntries) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be zero or greater");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be greater than zero");
        }

        Path start = resolveExisting(relativePath);
        if (!Files.isDirectory(start)) {
            throw new IllegalArgumentException("Workspace path is not a directory: " + display(start));
        }

        List<WorkspaceEntry> entries = new ArrayList<>();
        collectTree(start, 0, maxDepth, maxEntries, entries);
        return List.copyOf(entries);
    }

    public WorkspaceTextFile readText(String relativePath) {
        Path file = resolveExisting(relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Workspace path is not a file: " + display(file));
        }
        try {
            return new WorkspaceTextFile(display(file), Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read workspace file: " + display(file), error);
        }
    }

    public WorkspaceWriteResult writeText(String relativePath, String content) {
        Path file = resolveForWrite(relativePath);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return new WorkspaceWriteResult(display(file), content.length());
        } catch (IOException error) {
            throw new IllegalStateException("Unable to write workspace file: " + display(file), error);
        }
    }

    public List<TextSearchMatch> searchText(
            String query,
            String relativePath,
            boolean caseSensitive,
            int maxResults
    ) {
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("Search query must not be empty");
        }
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be greater than zero");
        }

        Path start = resolveExisting(relativePath);
        List<TextSearchMatch> matches = new ArrayList<>();
        collectSearchFiles(start, query, caseSensitive, maxResults, matches);
        return List.copyOf(matches);
    }

    public List<String> findFilesBySuffix(
            String relativePath,
            List<String> suffixes,
            int maxFiles
    ) {
        if (suffixes == null || suffixes.isEmpty()) {
            throw new IllegalArgumentException("File suffixes must not be empty");
        }
        if (suffixes.stream().anyMatch(suffix -> suffix == null || suffix.isEmpty())) {
            throw new IllegalArgumentException("File suffixes must not contain blank values");
        }
        if (maxFiles < 1) {
            throw new IllegalArgumentException("maxFiles must be greater than zero");
        }

        Path start = resolveExisting(relativePath);
        List<String> files = new ArrayList<>();
        collectFilesBySuffix(start, List.copyOf(suffixes), maxFiles, files);
        return List.copyOf(files);
    }

    private void collectTree(
            Path directory,
            int depth,
            int maxDepth,
            int maxEntries,
            List<WorkspaceEntry> entries
    ) {
        if (depth >= maxDepth || entries.size() >= maxEntries) {
            return;
        }

        for (Path child : children(directory)) {
            if (entries.size() >= maxEntries) {
                return;
            }
            if (Files.isSymbolicLink(child)) {
                continue;
            }
            if (Files.isDirectory(child) && shouldSkipDirectory(child)) {
                continue;
            }

            boolean directoryChild = Files.isDirectory(child);
            entries.add(new WorkspaceEntry(
                    display(child),
                    directoryChild,
                    directoryChild ? 0L : size(child)
            ));

            if (directoryChild) {
                collectTree(child, depth + 1, maxDepth, maxEntries, entries);
            }
        }
    }

    private void collectSearchFiles(
            Path path,
            String query,
            boolean caseSensitive,
            int maxResults,
            List<TextSearchMatch> matches
    ) {
        if (matches.size() >= maxResults || Files.isSymbolicLink(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            if (shouldSkipDirectory(path) && !path.equals(realRoot)) {
                return;
            }
            for (Path child : children(path)) {
                collectSearchFiles(child, query, caseSensitive, maxResults, matches);
                if (matches.size() >= maxResults) {
                    return;
                }
            }
            return;
        }

        if (!Files.isRegularFile(path) || size(path) > MAX_SEARCH_FILE_BYTES) {
            return;
        }

        String expected = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null && matches.size() < maxResults) {
                lineNumber++;
                String candidate = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
                if (candidate.contains(expected)) {
                    matches.add(new TextSearchMatch(display(path), lineNumber, line));
                }
            }
        } catch (MalformedInputException ignored) {
            // Binary/non-UTF-8 file: not a text-search candidate.
        } catch (IOException error) {
            throw new IllegalStateException("Unable to search workspace file: " + display(path), error);
        }
    }

    private void collectFilesBySuffix(
            Path path,
            List<String> suffixes,
            int maxFiles,
            List<String> files
    ) {
        if (files.size() >= maxFiles || Files.isSymbolicLink(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            if (shouldSkipDirectory(path) && !path.equals(realRoot)) {
                return;
            }
            for (Path child : children(path)) {
                collectFilesBySuffix(child, suffixes, maxFiles, files);
                if (files.size() >= maxFiles) {
                    return;
                }
            }
            return;
        }

        if (!Files.isRegularFile(path)) {
            return;
        }

        String name = path.getFileName().toString();
        if (suffixes.stream().anyMatch(name::endsWith)) {
            files.add(display(path));
        }
    }

    private List<Path> children(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to list workspace directory: " + display(directory), error);
        }
    }

    private boolean shouldSkipDirectory(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && SKIPPED_DIRECTORIES.contains(fileName.toString());
    }

    private long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to inspect workspace file: " + display(file), error);
        }
    }

    private Path resolveExisting(String relativePath) {
        Path candidate = resolveNormalized(relativePath);
        try {
            Path real = candidate.toRealPath();
            ensureInsideWorkspace(real, relativePath);
            return real;
        } catch (IOException error) {
            throw new IllegalArgumentException("Workspace path does not exist: " + relativePath, error);
        }
    }

    private Path resolveForWrite(String relativePath) {
        Path candidate = resolveNormalized(relativePath);
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException("Unable to resolve workspace path: " + relativePath);
        }
        try {
            ensureInsideWorkspace(existing.toRealPath(), relativePath);
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to resolve workspace path: " + relativePath, error);
        }
        return candidate;
    }

    private Path resolveNormalized(String relativePath) {
        String requested = relativePath == null || relativePath.isBlank() ? "." : relativePath;
        Path candidate = root.resolve(requested).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace: " + relativePath);
        }
        return candidate;
    }

    private void ensureInsideWorkspace(Path path, String requested) {
        if (!path.startsWith(realRoot)) {
            throw new IllegalArgumentException("Path escapes workspace: " + requested);
        }
    }

    private String display(Path path) {
        Path relative = realRoot.relativize(path.toAbsolutePath().normalize());
        return relative.toString().replace(path.getFileSystem().getSeparator(), "/");
    }
}
