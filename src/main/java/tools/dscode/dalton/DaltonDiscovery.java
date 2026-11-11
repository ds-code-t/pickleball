package tools.dscode.dalton;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Include, exclude, size, and binary rules for Dalton discovery.
 * Defaults keep the fixed directories. Size and binary skips are extra.
 * {@code --path --force} is the only bypass.
 */
public final class DaltonDiscovery {
    public static final long DEFAULT_MAX_BYTES = 1024L * 1024L;
    public static final String BINARY_SKIP = "skip";
    static final List<String> DEFAULT_EXCLUDES = List.of(
            "**/.git/**",
            "**/target/**",
            "**/build/**",
            "**/.pickleball/**",
            "**/*.png", "**/*.jpg", "**/*.jpeg", "**/*.gif", "**/*.webp", "**/*.ico",
            "**/*.pdf", "**/*.zip", "**/*.jar", "**/*.class", "**/*.exe", "**/*.dll",
            "**/*.so", "**/*.dylib", "**/*.bin", "**/*.gz", "**/*.7z", "**/*.tar",
            "**/*.woff", "**/*.woff2", "**/*.mp3", "**/*.mp4", "**/*.wasm"
    );
    private static final List<String> BINARY_EXTENSIONS = List.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".jar",
            ".class", ".exe", ".dll", ".so", ".dylib", ".bin", ".gz", ".7z", ".tar",
            ".woff", ".woff2", ".mp3", ".mp4", ".wasm", ".bz2", ".xz"
    );

    private final List<String> includes;
    private final List<String> excludes;
    private final long maxBytes;
    private final String binaryPolicy;

    private DaltonDiscovery(List<String> includes, List<String> excludes, long maxBytes, String binaryPolicy) {
        this.includes = includes;
        this.excludes = excludes;
        this.maxBytes = maxBytes;
        this.binaryPolicy = binaryPolicy;
    }

    public static DaltonDiscovery load(Path project) {
        Props file = readFile(project);
        List<String> includes = split(first(setting("pkb_dalton_include", "PKB_DALTON_INCLUDE"), file.get("include")));
        List<String> extraExcludes = split(first(setting("pkb_dalton_exclude", "PKB_DALTON_EXCLUDE"), file.get("exclude")));
        List<String> excludes = new ArrayList<>(DEFAULT_EXCLUDES);
        excludes.addAll(extraExcludes);
        long max = parseSize(first(setting("pkb_dalton_max_file_size", "PKB_DALTON_MAX_FILE_SIZE"), file.get("max-file-size")));
        String policy = first(setting("pkb_dalton_binary_policy", "PKB_DALTON_BINARY_POLICY"), file.get("binary-policy"));
        if (policy == null || policy.isBlank()) policy = BINARY_SKIP;
        return new DaltonDiscovery(includes, excludes, max, policy.trim().toLowerCase(Locale.ROOT));
    }

    public boolean defaultIncludes() {
        return includes.isEmpty();
    }

    public List<String> includes() {
        return includes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** Null means keep the file. {@code relativePath} uses {@code /}. */
    public String skipReason(String relativePath, Path file) {
        String relative = relativePath.replace('\\', '/');
        for (String exclude : excludes) {
            if (glob(exclude, relative) || directoryHit(relative, exclude)) {
                return "exclude " + exclude;
            }
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException failure) {
            return "unreadable";
        }
        if (size > maxBytes) return "size " + size + " > " + maxBytes;
        String extension = extension(file.getFileName().toString());
        if (BINARY_EXTENSIONS.contains(extension)) return "binary extension " + extension;
        if (!BINARY_SKIP.equals(binaryPolicy) && !"hash-only".equals(binaryPolicy)) {
            return "binary-policy " + binaryPolicy + " is not supported; skipped";
        }
        String sniffed = sniff(file);
        if (sniffed != null) {
            if ("hash-only".equals(binaryPolicy)) return "binary " + sniffed + "; hash-only is not implemented";
            return "binary " + sniffed;
        }
        return null;
    }

    static boolean glob(String glob, String path) {
        StringBuilder regex = new StringBuilder("^");
        int index = 0;
        while (index < glob.length()) {
            char c = glob.charAt(index);
            if (c == '*') {
                boolean wide = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                if (wide) {
                    regex.append(".*");
                    index += 2;
                    if (index < glob.length() && glob.charAt(index) == '/') index++;
                } else {
                    regex.append("[^/]*");
                    index++;
                }
                continue;
            }
            if (c == '?') {
                regex.append("[^/]");
                index++;
                continue;
            }
            if ("\\.[]{}()+^$|".indexOf(c) >= 0) regex.append('\\');
            regex.append(c);
            index++;
        }
        regex.append('$');
        return Pattern.compile(regex.toString()).matcher(path).matches();
    }

    private static boolean directoryHit(String relative, String exclude) {
        String token = switch (exclude) {
            case "**/.git/**" -> ".git";
            case "**/target/**" -> "target";
            case "**/build/**" -> "build";
            case "**/.pickleball/**" -> ".pickleball";
            default -> null;
        };
        if (token == null) return false;
        for (String part : relative.split("/")) {
            if (token.equals(part)) return true;
        }
        return false;
    }

    private static String sniff(Path file) {
        byte[] sample;
        try {
            byte[] all = Files.readAllBytes(file);
            int length = Math.min(all.length, 8192);
            sample = length == all.length ? all : java.util.Arrays.copyOf(all, length);
        } catch (IOException failure) {
            return "unreadable";
        }
        int controls = 0;
        for (byte value : sample) {
            int b = value & 0xFF;
            if (b == 0) return "null byte";
            if (b < 9 || (b > 13 && b < 32) || b == 127) controls++;
        }
        if (sample.length >= 32 && controls * 1.0 / sample.length > 0.30) return "content";
        return null;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static long parseSize(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_MAX_BYTES;
        String value = raw.trim().toUpperCase(Locale.ROOT);
        long factor = 1;
        if (value.endsWith("MIB")) {
            factor = 1024L * 1024L;
            value = value.substring(0, value.length() - 3).trim();
        } else if (value.endsWith("KB")) {
            factor = 1024L;
            value = value.substring(0, value.length() - 2).trim();
        } else if (value.endsWith("M")) {
            factor = 1024L * 1024L;
            value = value.substring(0, value.length() - 1).trim();
        } else if (value.endsWith("K")) {
            factor = 1024L;
            value = value.substring(0, value.length() - 1).trim();
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) return DEFAULT_MAX_BYTES;
            return parsed * factor;
        } catch (NumberFormatException failure) {
            return DEFAULT_MAX_BYTES;
        }
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim().replace('\\', '/');
            if (!trimmed.isEmpty()) values.add(trimmed);
        }
        return values;
    }

    private static String first(String property, String file) {
        if (property != null && !property.isBlank()) return property.trim();
        return file;
    }

    private static String setting(String property, String env) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null && !propertyValue.isBlank()) return propertyValue;
        return System.getenv(env);
    }

    private static Props readFile(Path project) {
        Props props = new Props();
        if (project == null) return props;
        Path file = project.resolve("dalton.properties");
        if (!Files.isRegularFile(file)) return props;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int split = trimmed.indexOf('=');
                if (split < 0) continue;
                String key = trimmed.substring(0, split).trim().toLowerCase(Locale.ROOT);
                String value = trimmed.substring(split + 1).trim();
                if ("exclude".equals(key) || "include".equals(key)) {
                    String previous = props.get(key);
                    props.put(key, previous == null || previous.isBlank() ? value : previous + "," + value);
                } else {
                    props.put(key, value);
                }
            }
        } catch (IOException ignored) {
            return props;
        }
        return props;
    }

    private static final class Props {
        private final java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();

        void put(String key, String value) {
            values.put(key, value);
        }

        String get(String key) {
            return values.get(key);
        }
    }
}
