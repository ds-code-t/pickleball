package tools.dscode.control.protocol;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One path-prefix rule for Pickleball resource locations.
 *
 * <pre>
 * (none)       resources folder (classpath)
 * file:        resources folder (classpath), same as no prefix
 * classpath:   resources folder (classpath)
 * wd-file:X    working-directory/X
 * wd-file:/X   absolute filesystem path /X
 * </pre>
 *
 * A bare absolute path stays on the filesystem so a private-run copy can be
 * loaded. A relative path, including one that contains a slash, is a classpath
 * resource. {@code wd-file:} is recognized before {@code file:}.
 *
 * <p>Cucumber translation is applied once to the stored user value.
 * {@code file:features} is a classpath resource. The Cucumber URI
 * {@code file:features} is the filesystem form of {@code wd-file:features}.
 * Translating that Cucumber URI again would turn it back into a classpath
 * resource.</p>
 */
public final class PickleballPaths {
    public enum Kind {
        CLASSPATH,
        FILE_SYSTEM
    }

    public record Parsed(
            Kind kind,
            String classpathResource,
            String fileText,
            boolean absoluteFile
    ) {
    }

    private static final String WD_FILE = "wd-file:";
    private static final String CLASSPATH = "classpath:";
    private static final String FILE = "file:";

    private PickleballPaths() {
    }

    public static Parsed parse(String configured) {
        String text = configured == null ? "" : configured.trim();
        if (startsWith(text, WD_FILE)) {
            String remainder = text.substring(WD_FILE.length()).trim();
            return new Parsed(Kind.FILE_SYSTEM, null, remainder, isAbsoluteRemainder(remainder));
        }
        if (startsWith(text, CLASSPATH)) {
            return classpath(text.substring(CLASSPATH.length()));
        }
        if (startsWith(text, FILE)) {
            return classpath(text.substring(FILE.length()));
        }
        if (isBareAbsolute(text)) {
            return new Parsed(Kind.FILE_SYSTEM, null, text, true);
        }
        return classpath(text);
    }

    public static Path toFile(Parsed parsed, Path workingDirectory) {
        if (parsed == null || parsed.kind() != Kind.FILE_SYSTEM) {
            return null;
        }
        String fileText = parsed.fileText() == null ? "" : parsed.fileText();
        if (parsed.absoluteFile()) {
            return fileText.isEmpty()
                    ? base(workingDirectory)
                    : Path.of(fileText).normalize();
        }
        if (fileText.isEmpty()) {
            return base(workingDirectory);
        }
        return base(workingDirectory).resolve(fileText).normalize();
    }

    /**
     * Translates one stored feature location into the URI Cucumber should receive.
     * Call this once. A second pass treats the filesystem result {@code file:features}
     * as a classpath resource.
     */
    public static String toCucumberFeatureUri(String configured) {
        Parsed parsed = parse(configured);
        if (parsed.kind() == Kind.CLASSPATH) {
            String resource = parsed.classpathResource() == null ? "" : parsed.classpathResource();
            return CLASSPATH + resource;
        }
        String fileText = parsed.fileText() == null ? "" : parsed.fileText().replace('\\', '/');
        return FILE + fileText;
    }

    public static String toCucumberFeatures(String commaSeparated) {
        if (commaSeparated == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String part : commaSeparated.split(",", -1)) {
            String trimmed = part == null ? "" : part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            parts.add(toCucumberFeatureUri(trimmed));
        }
        return String.join(",", parts);
    }

    private static Parsed classpath(String remainder) {
        return new Parsed(Kind.CLASSPATH, stripToResource(remainder), null, false);
    }

    public static String stripToResource(String value) {
        String text = value == null ? "" : value.trim().replace('\\', '/');
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static Path base(Path workingDirectory) {
        Path base = workingDirectory == null ? Path.of("") : workingDirectory;
        return base.toAbsolutePath().normalize();
    }

    private static boolean startsWith(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isAbsoluteRemainder(String remainder) {
        if (remainder == null || remainder.isEmpty()) {
            return false;
        }
        char first = remainder.charAt(0);
        if (first == '/' || first == '\\') {
            return true;
        }
        return isBareAbsolute(remainder);
    }

    private static boolean isBareAbsolute(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        if (first == '/' || first == '\\') {
            return true;
        }
        try {
            return Path.of(text).isAbsolute();
        } catch (InvalidPathException ignored) {
            return false;
        }
    }
}
