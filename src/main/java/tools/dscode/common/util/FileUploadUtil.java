package tools.dscode.common.util;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Objects;
import java.util.UUID;

public final class FileUploadUtil {

    private static final Path TEST_RESOURCES = Paths.get("src", "test", "resources");
    private static final Path MAIN_RESOURCES = Paths.get("src", "main", "resources");

    // Build output dirs (typically cleaned + gitignored by default)
    private static final Path GRADLE_BUILD = Paths.get("build");
    private static final Path MAVEN_TARGET = Paths.get("target");

    private static final String TEMP_DIR_NAME = "selenium-upload-tmp";
    private static final String GITIGNORE_NAME = ".gitignore";

    private FileUploadUtil() {}

    /**
     * Upload a file reference via an {@code <input type="file">} element.
     *
     * Resolution rules:
     *  - absolute path: use as-is
     *  - relative path: try src/test/resources/<path>, then src/main/resources/<path>
     *  - filename only: try {build|target}/selenium-upload-tmp/<name>, then
     *                   src/test/resources/files/<name>, then src/main/resources/files/<name>
     */
    public static void upload(WebDriver driver, WebElement fileInput, String fileRef) {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(fileInput, "fileInput");
        Objects.requireNonNull(fileRef, "fileRef");

        maybeEnableRemoteFileDetector(driver);

        Path file = resolve(fileRef);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Upload file not found: " + file);
        }

        fileInput.sendKeys(file.toString());
    }

    // -----------------------
    // Temp file creation APIs
    // -----------------------

    /** Create an empty temp file under {build|target}/selenium-upload-tmp. */
    public static Path createEmptyTempFile(String fileName) {
        return createTempFileInternal(fileName, null);
    }

    /** Create a temp file with content under {build|target}/selenium-upload-tmp. */
    public static Path createEmptyTempFile(String fileName, byte[] content) {
        return createTempFileInternal(fileName, content);
    }

    /** Create an empty temp file, then upload it (upload resolves temp dir first for filename-only). */
    public static Path createTempAndUpload(WebDriver driver, WebElement fileInput, String fileName) {
        Path created = createTempFileInternal(fileName, null);
        upload(driver, fileInput, created.getFileName().toString());
        return created;
    }

    /** Create a temp file with content, then upload it (upload resolves temp dir first for filename-only). */
    public static Path createTempAndUpload(WebDriver driver, WebElement fileInput, String fileName, byte[] content) {
        Path created = createTempFileInternal(fileName, content);
        upload(driver, fileInput, created.getFileName().toString());
        return created;
    }

    // -----------------
    // Internal helpers
    // -----------------

    private static Path resolve(String fileRef) {
        Path p = Paths.get(fileRef);

        // absolute path
        if (p.isAbsolute()) return p.normalize();

        boolean filenameOnly = (p.getParent() == null && !fileRef.contains("/") && !fileRef.contains("\\"));

        if (filenameOnly) {
            // 1) temp upload dir
            Path tmp = getTempUploadDir().resolve(p).toAbsolutePath().normalize();
            if (Files.exists(tmp)) return tmp;

            // 2) src/test/resources/files/<name>
            Path test = TEST_RESOURCES.resolve("files").resolve(p).toAbsolutePath().normalize();
            if (Files.exists(test)) return test;

            // 3) src/main/resources/files/<name>
            Path main = MAIN_RESOURCES.resolve("files").resolve(p).toAbsolutePath().normalize();
            if (Files.exists(main)) return main;

            return tmp; // fail later with clear error
        }

        // relative path: try src/test/resources then src/main/resources
        Path test = TEST_RESOURCES.resolve(p).toAbsolutePath().normalize();
        if (Files.exists(test)) return test;

        Path main = MAIN_RESOURCES.resolve(p).toAbsolutePath().normalize();
        if (Files.exists(main)) return main;

        return test; // fail later with clear error
    }

    private static Path createTempFileInternal(String fileName, byte[] content) {
        Objects.requireNonNull(fileName, "fileName");

        Path dir = getOrCreateTempUploadDir();
        Path file = dir.resolve(safeFilenameOnly(fileName)).toAbsolutePath().normalize();

        try {
            Files.createDirectories(file.getParent());

            if (content == null) {
                Files.write(file, new byte[0],
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.write(file, content,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create temp upload file: " + file, e);
        }
    }

    private static Path getTempUploadDir() {
        return pickBuildBaseDir().resolve(TEMP_DIR_NAME);
    }

    private static Path getOrCreateTempUploadDir() {
        Path dir = getTempUploadDir();

        try {
            Files.createDirectories(dir);
            ensureGitignore(dir);
            return dir.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create temp upload directory: " + dir, e);
        }
    }

    private static Path pickBuildBaseDir() {
        // Prefer whichever exists; if neither exists yet, pick based on build files.
        if (Files.isDirectory(GRADLE_BUILD)) return GRADLE_BUILD;
        if (Files.isDirectory(MAVEN_TARGET)) return MAVEN_TARGET;

        if (Files.isRegularFile(Paths.get("pom.xml"))) return MAVEN_TARGET;
        if (Files.isRegularFile(Paths.get("build.gradle")) || Files.isRegularFile(Paths.get("build.gradle.kts")))
            return GRADLE_BUILD;

        // Default
        return GRADLE_BUILD;
    }

    private static void ensureGitignore(Path dir) throws IOException {
        // build/ and target/ are usually gitignored already, but this makes the temp folder safe in odd repos.
        Path gi = dir.resolve(GITIGNORE_NAME);
        if (!Files.exists(gi)) {
            Files.writeString(gi, "*\n!.gitignore\n", StandardOpenOption.CREATE_NEW);
        }
    }

    private static void maybeEnableRemoteFileDetector(WebDriver driver) {
        if (driver instanceof RemoteWebDriver rwd) {
            try {
                rwd.setFileDetector(new LocalFileDetector());
            } catch (WebDriverException ignored) {
                // local driver subclass/wrapper; safe to ignore
            }
        }
    }

    private static String safeFilenameOnly(String maybePath) {
        Path p = Paths.get(maybePath);
        String name = (p.getFileName() == null) ? ("file-" + UUID.randomUUID()) : p.getFileName().toString();
        if (name.isBlank()) name = "file-" + UUID.randomUUID();
        return name;
    }
}
