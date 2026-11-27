package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import tools.dscode.store.StoreTestProcesses;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R8: on a classpath with slf4j-api and no provider (the consumer classpath in the retest),
 * the CLI prints no "No SLF4J providers" lines. The child JVM gets slf4j-api, the main and
 * test classes, and nothing else, so the plain run reproduces the lines.
 */
class QuietSlf4jTest {
    /** Child entry point: {@code quiet} runs the launcher's startup bind first, then logs once. */
    public static void main(String[] args) {
        if (args.length > 0 && "quiet".equals(args[0])) QuietSlf4j.init();
        org.slf4j.LoggerFactory.getLogger("pkb").info("ignored");
        System.out.println("done");
    }

    @Test
    void theStartupBindDropsTheNoProviderLines() throws Exception {
        String plain = child("plain");
        assertTrue(plain.contains("No SLF4J providers"), "the child reproduces the warning: " + plain);
        assertTrue(plain.contains("SLF4J:") || plain.contains("SLF4J("), plain);
        String quiet = child("quiet");
        assertTrue(quiet.contains("done"), quiet);
        assertFalse(quiet.contains("SLF4J"), quiet);
    }

    @Test
    void onlyTheNoProviderLinesAreDropped() {
        assertTrue(QuietSlf4j.noProviderLine("SLF4J: No SLF4J providers were found."));
        assertTrue(QuietSlf4j.noProviderLine("SLF4J: See https://www.slf4j.org/codes.html#noProviders for further details."));
        assertTrue(QuietSlf4j.noProviderLine("SLF4J(W): No SLF4J providers were found."));
        assertTrue(QuietSlf4j.noProviderLine("SLF4J(W): Defaulting to no-operation (NOP) logger implementation"));
        assertTrue(QuietSlf4j.noProviderLine("SLF4J(W): See https://www.slf4j.org/codes.html#noProviders for further details."));
        assertFalse(QuietSlf4j.noProviderLine("SLF4J: Class path contains multiple SLF4J providers."));
        assertFalse(QuietSlf4j.noProviderLine("SLF4J(W): Class path contains multiple SLF4J providers."));
    }

    private static String child(String mode) throws Exception {
        List<String> keep = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path path = Path.of(entry);
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            boolean ownClasses = Files.isDirectory(path) && (
                    Files.isRegularFile(path.resolve("tools/dscode/launcher/QuietSlf4j.class"))
                            || Files.isRegularFile(path.resolve("tools/dscode/launcher/QuietSlf4jTest.class")));
            if (name.startsWith("slf4j-api") || ownClasses) keep.add(entry);
        }
        String classpath = keep.stream().collect(Collectors.joining(File.pathSeparator));
        assertTrue(classpath.contains("slf4j-api"), Arrays.toString(keep.toArray()));
        assertTrue(keep.stream().anyMatch(entry -> Files.isRegularFile(
                Path.of(entry).resolve("tools/dscode/launcher/QuietSlf4jTest.class"))), keep.toString());
        Process process = new ProcessBuilder(StoreTestProcesses.java(List.of(
                "-cp", classpath, QuietSlf4jTest.class.getName(), mode)))
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "child finishes");
        String all = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), all);
        return all;
    }
}
