package tools.dscode.store;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Git provenance for a Store project. Each call is the {@code git} CLI with a
 * 5 second timeout. Missing git or a non-repository leaves the fields empty
 * and warns once per process.
 */
public final class StoreGit {
    private static final ConcurrentHashMap<String, Boolean> WARNED = new ConcurrentHashMap<>();

    private StoreGit() {
    }

    public record Snapshot(
            String commit,
            String branch,
            String remote,
            String githubRepo,
            boolean dirty,
            boolean available,
            String warning
    ) {
        public static Snapshot empty(String warning) {
            return new Snapshot("", "", "", "", false, false, warning == null ? "" : warning);
        }
    }

    public record Command(int code, String out, String err, boolean timedOut) {
    }

    public static Snapshot capture(Path project) {
        Path root = project == null ? Path.of("").toAbsolutePath() : project.toAbsolutePath().normalize();
        Command version = run(root, 5, "git", "--version");
        if (version.timedOut || version.code != 0) {
            String reason = version.timedOut ? "git timed out" : firstLine(version.err, "git binary not found");
            warn(reason);
            StoreLog.log(root, "git", "provenance", "unavailable " + reason);
            return Snapshot.empty(reason);
        }
        Command commit = run(root, 5, "git", "rev-parse", "HEAD");
        if (commit.code != 0) {
            String reason = firstLine(commit.err, "not a git repository");
            warn(reason);
            StoreLog.log(root, "git", "provenance", "unavailable " + reason);
            return Snapshot.empty(reason);
        }
        String branch = trim(run(root, 5, "git", "rev-parse", "--abbrev-ref", "HEAD").out);
        String remoteRaw = trim(run(root, 5, "git", "remote", "get-url", "origin").out);
        String remote = stripUserInfo(remoteRaw);
        String repo = githubRepo(remoteRaw);
        Command porcelain = run(root, 5, "git", "status", "--porcelain");
        boolean dirty = porcelain.code == 0 && !trim(porcelain.out).isEmpty();
        StoreLog.log(root, "git", "provenance", "commit=" + trim(commit.out) + " dirty=" + dirty + " repo=" + repo);
        return new Snapshot(trim(commit.out), branch, remote, repo, dirty, true, "");
    }

    public static Map<String, String> indexBlobs(Path project) {
        Path root = project == null ? Path.of("").toAbsolutePath() : project.toAbsolutePath().normalize();
        Command listed = run(root, 5, "git", "ls-files", "-s");
        Map<String, String> blobs = new LinkedHashMap<>();
        if (listed.code != 0) return blobs;
        for (String line : listed.out.split("\n")) {
            if (line.isBlank()) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String meta = line.substring(0, tab).trim();
            String path = line.substring(tab + 1).trim();
            String[] parts = meta.split("\\s+");
            if (parts.length >= 2 && !path.isEmpty()) blobs.put(path, parts[1]);
        }
        return blobs;
    }

    /** Blob ids at {@code HEAD}. Empty when this directory is not a repository. */
    public record Head(String commit, Map<String, String> blobs) {
        public Head {
            commit = commit == null ? "" : commit;
            blobs = blobs == null ? Map.of() : Map.copyOf(blobs);
        }

        public static Head empty() {
            return new Head("", Map.of());
        }

        public String blob(String path) {
            if (path == null || blobs.isEmpty()) return null;
            return blobs.get(path.replace('\\', '/'));
        }
    }

    public static Head head(Path project) {
        Path root = project == null ? Path.of("").toAbsolutePath() : project.toAbsolutePath().normalize();
        Command commit = run(root, 5, "git", "rev-parse", "HEAD");
        if (commit.code != 0) return Head.empty();
        Command listed = run(root, 5, "git", "ls-tree", "-r", "HEAD");
        Map<String, String> blobs = new LinkedHashMap<>();
        if (listed.code == 0) {
            for (String line : listed.out.split("\n")) {
                if (line.isBlank()) continue;
                int tab = line.indexOf('\t');
                if (tab < 0) continue;
                String meta = line.substring(0, tab).trim();
                String path = line.substring(tab + 1).trim().replace('\\', '/');
                String[] parts = meta.split("\\s+");
                if (parts.length >= 3 && !path.isEmpty()) blobs.put(path, parts[2]);
            }
        }
        return new Head(trim(commit.out), blobs);
    }

    /** {@code git cat-file blob}. Null when git or the blob is missing. Bytes are not decoded. */
    public static byte[] catFile(Path project, String blob) {
        if (blob == null || !blob.matches("[0-9a-fA-F]{4,64}")) return null;
        Path root = project == null ? Path.of("").toAbsolutePath() : project.toAbsolutePath().normalize();
        Bytes command = runBytes(root, 5, "git", "cat-file", "blob", blob);
        if (command.timedOut || command.code != 0 || command.out == null) return null;
        return command.out;
    }

    public static String stripUserInfo(String remote) {
        if (remote == null || remote.isBlank()) return "";
        String https = remote.replaceFirst("(?i)^(https?://)[^@\\s]+@", "$1");
        if (!https.equals(remote)) return https;
        return remote.replaceFirst("://[^/@\\s]+@", "://");
    }

    public static String githubRepo(String remote) {
        if (remote == null || remote.isBlank()) return "";
        String value = stripUserInfo(remote.trim());
        String tail = null;
        if (value.startsWith("https://github.com/") || value.startsWith("http://github.com/")) {
            tail = value.substring(value.indexOf("github.com/") + "github.com/".length());
        } else if (value.startsWith("ssh://git@github.com/")) {
            tail = value.substring("ssh://git@github.com/".length());
        } else if (value.startsWith("git@github.com:")) {
            tail = value.substring("git@github.com:".length());
        } else if (value.contains("github.com:")) {
            tail = value.substring(value.indexOf("github.com:") + "github.com:".length());
        } else if (value.contains("github.com/")) {
            tail = value.substring(value.indexOf("github.com/") + "github.com/".length());
        }
        if (tail == null || tail.isBlank()) return "";
        if (tail.endsWith(".git")) tail = tail.substring(0, tail.length() - 4);
        int slash = tail.indexOf('/');
        if (slash <= 0 || slash == tail.length() - 1) return "";
        String owner = tail.substring(0, slash);
        String name = tail.substring(slash + 1);
        int cut = name.indexOf('/');
        if (cut >= 0) name = name.substring(0, cut);
        if (owner.isBlank() || name.isBlank()) return "";
        return owner + "/" + name;
    }

    public static int changed(Path project, Iterable<StoreFiles.Row> rows) {
        Map<String, String> index = indexBlobs(project);
        int changed = 0;
        if (rows == null) return 0;
        for (StoreFiles.Row row : rows) {
            if (row == null || row.path() == null) continue;
            String indexed = index.get(row.path());
            if (indexed == null || indexed.isBlank()) continue;
            Path file = project.resolve(row.path());
            String live;
            try {
                if (!java.nio.file.Files.isRegularFile(file)) {
                    live = row.gitBlob() == null ? "" : row.gitBlob();
                } else {
                    live = StoreHashes.gitBlob(java.nio.file.Files.readAllBytes(file));
                }
            } catch (java.io.IOException failure) {
                live = row.gitBlob() == null ? "" : row.gitBlob();
            }
            if (!indexed.equalsIgnoreCase(live)) changed++;
        }
        return changed;
    }

    static Command run(Path project, int seconds, String... command) {
        Bytes bytes = runBytes(project, seconds, command);
        String out = bytes.out == null ? "" : new String(bytes.out, StandardCharsets.UTF_8);
        String err = bytes.err == null ? "" : new String(bytes.err, StandardCharsets.UTF_8);
        return new Command(bytes.code, out, err, bytes.timedOut);
    }

    private record Bytes(int code, byte[] out, byte[] err, boolean timedOut) {
    }

    private static Bytes runBytes(Path project, int seconds, String... command) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(List.of(command));
            if (project != null) builder.directory(project.toFile());
            builder.redirectErrorStream(false);
            process = builder.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Process live = process;
            Thread outThread = Thread.ofVirtual().unstarted(() -> drain(live.getInputStream(), out));
            Thread errThread = Thread.ofVirtual().unstarted(() -> drain(live.getErrorStream(), err));
            try {
                outThread.start();
                errThread.start();
            } catch (UnsupportedOperationException failure) {
                outThread = new Thread(() -> drain(live.getInputStream(), out));
                errThread = new Thread(() -> drain(live.getErrorStream(), err));
                outThread.start();
                errThread.start();
            }
            boolean finished = process.waitFor(seconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return new Bytes(-1, new byte[0], "timed out".getBytes(StandardCharsets.UTF_8), true);
            }
            outThread.join(1000);
            errThread.join(1000);
            return new Bytes(process.exitValue(), out.toByteArray(), err.toByteArray(), false);
        } catch (Exception failure) {
            if (process != null) process.destroyForcibly();
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new Bytes(-1, new byte[0], message.getBytes(StandardCharsets.UTF_8), false);
        }
    }

    private static void drain(java.io.InputStream input, ByteArrayOutputStream sink) {
        try (input) {
            input.transferTo(sink);
        } catch (Exception ignored) {
            // The process end closes the stream.
        }
    }

    private static void warn(String reason) {
        String key = reason == null ? "" : reason;
        if (WARNED.putIfAbsent(key, Boolean.TRUE) != null) return;
        System.err.println("store git unavailable: " + key);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstLine(String value, String fallback) {
        String text = trim(value);
        if (text.isEmpty()) return fallback;
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline).trim();
    }
}
