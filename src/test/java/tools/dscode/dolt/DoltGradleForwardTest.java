package tools.dscode.dolt;

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
 * Realizes every Gradle {@code dolt*} task and reads the arguments Gradle would pass.
 * A task name is a project property, so {@code project.hasProperty} would forward
 * {@code task ':doltHistory'} when {@code -P} is absent.
 */
class DoltGradleForwardTest {
    private static final List<String> TASKS = List.of(
            "doltStatus", "doltHydrate", "doltExtract", "doltFeature", "doltHistory", "doltQuery",
            "doltRun", "doltRunClasspath", "doltEdit", "doltSandbox", "doltMigrate"
    );

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void everyDoltTaskResolvesArgumentsWithAndWithoutProperties() throws Exception {
        Map<String, List<String>> plain = resolve(List.of());
        assertEquals(List.of("dolt", "status"), plain.get("doltStatus"));
        assertEquals(List.of("dolt", "hydrate"), plain.get("doltHydrate"));
        assertEquals(List.of("dolt", "extract"), plain.get("doltExtract"));
        assertEquals(List.of("dolt", "feature", "put"), plain.get("doltFeature"));
        assertEquals(List.of("dolt", "category", "list"), plain.get("doltEdit"));
        assertEquals(List.of("dolt", "files", "list"), plain.get("doltQuery"));
        assertEquals(List.of("dolt", "history", "list"), plain.get("doltHistory"));
        assertEquals(List.of("dolt", "run"), plain.get("doltRun"));
        assertEquals(List.of("dolt", "sandbox", "list"), plain.get("doltSandbox"));
        assertEquals(List.of("dolt", "migrate"), plain.get("doltMigrate"));
        assertOut(plain.get("doltRunClasspath"), null);

        Map<String, List<String>> set = resolve(List.of(
                "-PdoltSandbox=alpha",
                "-PdoltPath=features/pay.feature",
                "-PdoltForce=1",
                "-PdoltFeature=put",
                "-PdoltText=Feature:+Pay",
                "-PdoltFile=/tmp/pay.feature",
                "-PdoltHistory=show",
                "-PdoltRun=run-42",
                "-PdoltDays=4",
                "-PdoltMaxBytes=100",
                "-PdoltJson=1",
                "-PdoltArgs=diff,alpha",
                "-PdoltId=ada",
                "-PdoltConfirm=1",
                "-PdoltDryRun=1",
                "-PdoltCheck=1",
                "-PdoltDir=/tmp/bins",
                "-PdoltRemoveLegacy=1",
                "-PdoltTags=@pay",
                "-PdoltName=Ada",
                "-PdoltGlue=tools.dscode.coredefinitions",
                "-PdoltFeaturePath=features/pay.feature",
                "-PdoltScenario=Ada"
        ));
        assertEquals(List.of("dolt", "status", "--sandbox=alpha"), set.get("doltStatus"));
        assertEquals(List.of("dolt", "hydrate", "--path=features/pay.feature", "--force", "--sandbox=alpha"), set.get("doltHydrate"));
        assertEquals(List.of("dolt", "extract", "--path=features/pay.feature", "--force", "--sandbox=alpha"), set.get("doltExtract"));
        assertEquals(List.of("dolt", "feature", "put", "features/pay.feature", "--text=Feature:+Pay", "--file=/tmp/pay.feature", "--sandbox=alpha"), set.get("doltFeature"));
        assertEquals(List.of("dolt", "diff", "alpha", "--json", "--path=features/pay.feature", "--id=ada", "--text=Feature:+Pay", "--file=/tmp/pay.feature", "--confirm", "--force", "--sandbox=alpha"), set.get("doltEdit"));
        assertEquals(List.of("dolt", "diff", "alpha", "--json", "--feature=features/pay.feature", "--scenario=Ada", "--sandbox=alpha"), set.get("doltQuery"));
        assertEquals(List.of("dolt", "history", "show", "run-42", "--days=4", "--max-bytes=100", "--sandbox=alpha"), set.get("doltHistory"));
        assertEquals(List.of("dolt", "run", "--tags=@pay", "--name=Ada", "--glue=tools.dscode.coredefinitions", "--dry-run", "--sandbox=alpha"), set.get("doltRun"));
        assertEquals(List.of("dolt", "sandbox", "diff", "alpha", "--json", "--force", "--sandbox=alpha"), set.get("doltSandbox"));
        assertEquals(List.of("dolt", "migrate", "--dry-run", "--remove-legacy", "--sandbox=alpha"), set.get("doltMigrate"));
        assertOut(set.get("doltRunClasspath"), "--sandbox=alpha");
    }

    private static void assertOut(List<String> args, String sandbox) {
        assertEquals("dolt", args.get(0));
        assertEquals("run-classpath", args.get(1));
        assertTrue(args.get(2).startsWith("--out="), args.toString());
        assertTrue(args.get(2).endsWith("dolt-run-classpath.txt"), args.toString());
        if (sandbox == null) {
            assertEquals(3, args.size(), args.toString());
        } else {
            assertEquals(List.of(sandbox), args.subList(3, args.size()), args.toString());
        }
    }

    private static Map<String, List<String>> resolve(List<String> properties) throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        int start = gradle.indexOf("def doltForwardClasspath");
        int end = gradle.indexOf("tasks.register('verifySqlitePackaging')");
        assertTrue(start > 0 && end > start, "dolt task block missing");
        Path dir = Files.createTempDirectory("dolt-gradle-forward");
        String script = """
                plugins { id 'java' }
                repositories { mavenCentral() }
                """ + gradle.substring(start, end);
        Files.writeString(dir.resolve("build.gradle"), script, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'dolt-forward'\n", StandardCharsets.UTF_8);
        Path init = dir.resolve("print-dolt-args.init.gradle");
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
                assertEquals("tools.dscode.dolt.DoltCli", line.substring("DOLT_MAIN ".length()).trim(), current);
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
