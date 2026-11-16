package tools.dscode.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Large-binary references. A reference stores the path, hash, Git blob, commit,
 * and size, and no bytes. Reading checks the file, then {@code git cat-file}.
 */
public final class StoreRefs {
    private StoreRefs() {
    }

    /** Disk and Git both failed to return the referenced bytes. */
    public static final class Unavailable extends IOException {
        private final String fix;

        public Unavailable(String path, String hash, String commit) {
            super("large file " + path + " unavailable (hash " + (hash == null ? "" : hash)
                    + ", commit " + (commit == null ? "" : commit) + ")");
            this.fix = "fix: restore " + path + " from Git or copy the bytes with store file put --path=" + path;
        }

        public String fix() {
            return fix;
        }
    }

    public record Decision(boolean skip, String skipReason, StoreFiles.Row row) {
        static Decision skipped(String reason) {
            return new Decision(true, reason == null ? "" : reason, null);
        }

        static Decision kept(StoreFiles.Row row) {
            return new Decision(false, "", row);
        }
    }

    /**
     * A large binary whose bytes equal the Git blob at {@code HEAD} is a reference.
     * Anything else that is still under {@code max-file-size} is stored.
     * {@code bypassLimits} is {@code --path --force}.
     */
    public static Decision decide(
            Path project,
            StoreDiscovery discovery,
            String relative,
            String textSourceType,
            byte[] body,
            String hash,
            long mtime,
            String now,
            StoreGit.Head head,
            boolean bypassLimits
    ) {
        byte[] bytes = body == null ? new byte[0] : body;
        String path = relative == null ? "" : relative.replace('\\', '/');
        String kind = discovery.binaryKind(project.resolve(path));
        boolean binary = kind != null;
        boolean large = binary && bytes.length >= discovery.largeFileBytes();
        String blob = StoreHashes.gitBlob(bytes);
        String indexed = head == null ? null : head.blob(path);
        boolean matches = indexed != null && !indexed.isBlank() && indexed.equalsIgnoreCase(blob);
        String commit = matches && head.commit() != null ? head.commit() : "";
        if (large && matches) {
            return Decision.kept(new StoreFiles.Row(
                    path, hash, new byte[0], mtime, "binary-ref", now, hash, false,
                    blob, true, bytes.length, path, commit));
        }
        if (!bypassLimits && bytes.length > discovery.maxBytes()) {
            return Decision.skipped("size " + bytes.length + " > " + discovery.maxBytes());
        }
        String type = binary ? "binary" : textSourceType;
        return Decision.kept(new StoreFiles.Row(
                path, hash, bytes, mtime, type, now, hash, false,
                blob, false, bytes.length, "db", commit));
    }

    /** Owned rows return their bytes. A reference checks the file, then Git. */
    public static byte[] read(Path project, StoreFiles.Row row) throws IOException {
        if (row == null) return new byte[0];
        if (!row.binaryRef()) return row.raw() == null ? new byte[0] : row.raw();
        String location = row.location();
        String relative = location == null || location.isBlank() || "db".equals(location) ? row.path() : location;
        Path disk = project.resolve(relative);
        if (Files.isRegularFile(disk)) {
            byte[] live = Files.readAllBytes(disk);
            if (StoreHashes.sha256(live).equals(row.contentHash())) {
                StoreLog.log(project, "refs", row.path(), "read disk bytes=" + live.length);
                return live;
            }
        }
        byte[] fromGit = StoreGit.catFile(project, row.gitBlob());
        if (fromGit != null && StoreHashes.sha256(fromGit).equals(row.contentHash())) {
            StoreLog.log(project, "refs", row.path(), "read git-blob bytes=" + fromGit.length);
            return fromGit;
        }
        StoreLog.log(project, "refs", row.path(), "unavailable");
        throw new Unavailable(row.path(), row.contentHash(), row.gitCommit());
    }
}
