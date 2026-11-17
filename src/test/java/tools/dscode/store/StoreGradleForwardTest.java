package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Realizes every Gradle {@code store*} task and reads the arguments Gradle would pass.
 * A task name is a project property, so {@code project.hasProperty} would forward
 * {@code task ':storeHistory'} when {@code -P} is absent.
 */
class StoreGradleForwardTest {
    private static final List<String> TASKS = List.of(
            "storeStatus", "storeHydrate", "storeExtract", "storeFeature", "storeHistory", "storeQuery",
            "storeRun", "storeRunClasspath", "storeEdit", "storeSandbox", "storeMigrate"
    );

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void everyDoltTaskResolvesArgumentsWithAndWithoutProperties() throws Exception {
        Map<String, List<String>> plain = resolve(List.of());
        assertEquals(List.of("store", "status"), plain.get("storeStatus"));
        assertEquals(List.of("store", "hydrate"), plain.get("storeHydrate"));
        assertEquals(List.of("store", "extract"), plain.get("storeExtract"));
        assertEquals(List.of("store", "feature", "put"), plain.get("storeFeature"));
        assertEquals(List.of("store", "category", "list"), plain.get("storeEdit"));
        assertEquals(List.of("store", "files", "list"), plain.get("storeQuery"));
        assertEquals(List.of("store", "history", "list"), plain.get("storeHistory"));
        assertEquals(List.of("store", "run"), plain.get("storeRun"));
        assertEquals(List.of("store", "sandbox", "list"), plain.get("storeSandbox"));
        assertEquals(List.of("store", "migrate"), plain.get("storeMigrate"));
        assertOut(plain.get("storeRunClasspath"), null);

        Map<String, List<String>> set = resolve(List.of(
                "-PstoreSandbox=alpha",
                "-PstorePath=features/pay.feature",
                "-PstoreForce=1",
                "-PstoreFeature=put",
                "-PstoreText=Feature:+Pay",
                "-PstoreFile=/tmp/pay.feature",
                "-PstoreHistory=show",
                "-PstoreRun=run-42",
                "-PstoreDays=4",
                "-PstoreMaxBytes=100",
                "-PstoreJson=1",
                "-PstoreArgs=diff,alpha",
                "-PstoreId=ada",
                "-PstoreConfirm=1",
                "-PstoreDryRun=1",
                "-PstoreCheck=1",
                "-PstoreDir=/tmp/bins",
                "-PstoreRemoveLegacy=1",
                "-PstoreTags=@pay",
                "-PstoreName=Ada",
                "-PstoreGlue=tools.dscode.coredefinitions",
                "-PstoreFeaturePath=features/pay.feature",
                "-PstoreScenario=Ada",
                "-PstorePolicy=branch-wins",
                "-PstoreDrop=1",
                "-PstoreSince=4",
                "-PstoreTable=files",
                "-PstoreLimit=10"
        ));
        assertEquals(List.of("store", "status", "--sandbox=alpha"), set.get("storeStatus"));
        assertEquals(List.of("store", "hydrate", "--path=features/pay.feature", "--force", "--sandbox=alpha"), set.get("storeHydrate"));
        assertEquals(List.of("store", "extract", "--path=features/pay.feature", "--force", "--sandbox=alpha"), set.get("storeExtract"));
        assertEquals(List.of("store", "feature", "put", "features/pay.feature", "--text=Feature:+Pay", "--file=/tmp/pay.feature", "--sandbox=alpha"), set.get("storeFeature"));
        assertEquals(List.of("store", "diff", "alpha", "--json", "--path=features/pay.feature", "--id=ada", "--text=Feature:+Pay", "--file=/tmp/pay.feature", "--confirm", "--force", "--sandbox=alpha"), set.get("storeEdit"));
        assertEquals(List.of("store", "diff", "alpha", "--json", "--feature=features/pay.feature", "--scenario=Ada", "--since=4", "--table=files", "--limit=10", "--sandbox=alpha"), set.get("storeQuery"));
        assertEquals(List.of("store", "history", "show", "run-42", "--days=4", "--max-bytes=100", "--sandbox=alpha"), set.get("storeHistory"));
        assertEquals(List.of("store", "run", "--tags=@pay", "--name=Ada", "--glue=tools.dscode.coredefinitions", "--dry-run", "--sandbox=alpha"), set.get("storeRun"));
        assertEquals(List.of("store", "sandbox", "diff", "alpha", "--policy=branch-wins", "--drop", "--json", "--force", "--sandbox=alpha"), set.get("storeSandbox"));
        assertEquals(List.of("store", "migrate", "--dry-run", "--remove-legacy", "--sandbox=alpha"), set.get("storeMigrate"));
        assertOut(set.get("storeRunClasspath"), "--sandbox=alpha");
    }

    private static void assertOut(List<String> args, String sandbox) {
        assertEquals("store", args.get(0));
        assertEquals("run-classpath", args.get(1));
        assertTrue(args.get(2).startsWith("--out="), args.toString());
        assertTrue(args.get(2).endsWith("store-run-classpath.txt"), args.toString());
        if (sandbox == null) {
            assertEquals(3, args.size(), args.toString());
        } else {
            assertEquals(List.of(sandbox), args.subList(3, args.size()), args.toString());
        }
    }

    private static Map<String, List<String>> resolve(List<String> properties) throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        int start = gradle.indexOf("def storeForwardClasspath");
        int end = gradle.indexOf("tasks.register('verifySqlitePackaging')");
        assertTrue(start > 0 && end > start, "store task block missing");
        Path dir = Files.createTempDirectory("store-gradle-forward");
        String script = """
                plugins { id 'java' }
                repositories { mavenCentral() }
                """ + gradle.substring(start, end);
        Files.writeString(dir.resolve("build.gradle"), script, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'store-forward'\n", StandardCharsets.UTF_8);
        Path init = dir.resolve("print-store-args.init.gradle");
        Files.writeString(init, """
                gradle.taskGraph.whenReady {
                    def names = [%s]
                    names.each { name ->
                        def task = gradle.rootProject.tasks.named(name).get()
                        println 'DOLT_TASK ' + name
                        println 'DOLT_MAIN ' + String.valueOf(task.mainClass.get())
                        println 'DOLT_KIND ' + task.class.name
                        task.args.each { println 'DOLT_ARG ' + String.valueOf(it) }
                        println 'DOLT_END'
                    }
                }
                """.formatted(TASKS.stream().map(name -> "'" + name + "'").reduce((a, b) -> a + ", " + b).orElse("")),
                StandardCharsets.UTF_8);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path wrapper = Path.of(windows ? "gradlew.bat" : "gradlew").toAbsolutePath();
        List<String> command = new ArrayList<>();
        command.add(wrapper.toString());
        command.add("--no-daemon");
        command.add("--project-dir");
        command.add(dir.toAbsolutePath().toString());
        command.add("-I");
        command.add(init.toAbsolutePath().toString());
        command.add("--dry-run");
        command.addAll(properties);
        command.addAll(TASKS);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(4, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        String text = new String(output, StandardCharsets.UTF_8);
        assertTrue(finished, text);
        assertEquals(0, process.exitValue(), text);
        Map<String, List<String>> resolved = new LinkedHashMap<>();
        String current = null;
        for (String line : text.split("\\R")) {
            if (line.startsWith("DOLT_TASK ")) {
                current = line.substring("DOLT_TASK ".length()).trim();
                resolved.put(current, new ArrayList<>());
            } else if (line.startsWith("DOLT_MAIN ")) {
                assertEquals("tools.dscode.store.StoreCli", line.substring("DOLT_MAIN ".length()).trim(), current);
            } else if (line.startsWith("DOLT_KIND ")) {
                assertTrue(line.contains("JavaExec"), line);
            } else if (line.startsWith("DOLT_ARG ")) {
                String arg = line.substring("DOLT_ARG ".length());
                assertFalse(arg.contains("task ':"), arg);
                resolved.get(current).add(arg);
            }
        }
        assertEquals(TASKS.size(), resolved.size(), text);
        return resolved;
    }
}
