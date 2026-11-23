package tools.dscode.store;

import tools.dscode.testsupport.PortableProcess;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The store-run timing check, in Java. {@code scripts/time_store_run.py} is not
 * launched. The printed lines stay the ones the tests already assert.
 */
final class StoreRunTiming {
    private StoreRunTiming() {
    }

    static Result selfCheck() {
        String text = """
                cold Gradle or Maven test is the CI source of truth
                warm store run uses pickleball-test-engine via WorkbenchDirectRun
                two warm runs must exit with the same code as the cold run
                mvnw is maven-consumer-project/mvnw; this script does not add one
                self-check does not launch a suite
                """;
        return new Result(0, text);
    }

    static Result compare(Path project, String tags, String name, String glue, List<String> defines) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        if (!Files.isDirectory(project)) {
            return new Result(2, "error: project is not a directory: " + project + "\nfix: pass a project that has mvnw or gradlew\n");
        }
        List<String> extra = selection(tags, name, glue, defines);
        List<String> cold = new ArrayList<>(coldCommand(project));
        if (cold.isEmpty()) {
            return new Result(2, "error: project has no mvnw, gradlew, pom.xml, or build.gradle\nfix: run this check from a wrapper project\n");
        }
        cold.addAll(extra);
        Path classpath = ensureClasspath(root);
        String text = Files.readString(classpath).strip();
        writeCache(project.resolve(".pickleball/store/classpath.cache"), text);
        writeCache(project.resolve(".pickleball/store/sandboxes/main/worker/classpath.cache"), text);
        Run coldRun = timed(cold, project);
        List<String> warm = new ArrayList<>();
        warm.add(PortableProcess.javaBin().toString());
        warm.add("-cp");
        warm.add("@" + classpath.toAbsolutePath());
        warm.add("tools.dscode.store.StoreCli");
        warm.add("store");
        warm.add("run");
        warm.add("--project=" + project.toAbsolutePath());
        if (tags != null) warm.add("--tags=" + tags);
        if (name != null) warm.add("--name=" + name);
        if (glue != null) warm.add("--glue=" + glue);
        for (String item : defines) warm.add("-D" + item);
        Run first = timed(warm, root);
        Run second = timed(warm, root);
        Boolean firstReused = reusedFlag(first.out);
        Boolean secondReused = reusedFlag(second.out);
        StringBuilder report = new StringBuilder();
        report.append("coldMs=").append(coldRun.millis).append(" coldExit=").append(coldRun.code).append('\n');
        report.append("warm1Ms=").append(first.millis).append(" warm1Exit=").append(first.code)
                .append(" warm1Reused=").append(flag(firstReused)).append('\n');
        report.append("warm2Ms=").append(second.millis).append(" warm2Exit=").append(second.code)
                .append(" warm2Reused=").append(flag(secondReused)).append('\n');
        List<String> coldLines = scenarioLines(coldRun.out);
        List<String> firstLines = scenarioLines(first.out);
        List<String> secondLines = scenarioLines(second.out);
        boolean failed = false;
        if (first.code != coldRun.code || second.code != coldRun.code) {
            report.append("error: store run exit does not match the cold wrapper\n");
            report.append("fix: the fast path must use the same engine\n");
            failed = true;
        }
        if (!coldLines.equals(firstLines) || !firstLines.equals(secondLines)) {
            report.append("error: scenario lines do not match\n");
            report.append("cold ").append(String.join(" | ", coldLines)).append('\n');
            report.append("warm1 ").append(String.join(" | ", firstLines)).append('\n');
            report.append("warm2 ").append(String.join(" | ", secondLines)).append('\n');
            report.append("fix: pass the same --tags, --glue, and --define to both sides\n");
            failed = true;
        }
        if (!Boolean.TRUE.equals(secondReused)) {
            report.append("error: the second warm run did not reuse the worker\n");
            report.append("fix: seed .pickleball/store/sandboxes/main/worker/glue.fingerprint so the run does not restart\n");
            failed = true;
        }
        if (failed) {
            report.append(tail("cold stdout", coldRun.out));
            report.append(tail("cold stderr", coldRun.err));
            report.append(tail("warm1 stderr", first.err));
            report.append(tail("warm2 stderr", second.err));
            return new Result(1, report.toString());
        }
        report.append("results match\n");
        return new Result(0, report.toString());
    }

    private static List<String> selection(String tags, String name, String glue, List<String> defines) {
        List<String> extra = new ArrayList<>();
        if (tags != null) extra.add("-Dpkb_tags=" + tags);
        if (name != null) extra.add("-Dpkb_name=" + name);
        if (glue != null) extra.add("-Dpkb_glue=" + glue);
        for (String item : defines) extra.add("-D" + item);
        return extra;
    }

    private static List<String> coldCommand(Path project) {
        boolean windows = PortableProcess.windows();
        if (Files.isRegularFile(project.resolve("pom.xml"))) {
            Path wrapper = project.resolve(windows ? "mvnw.cmd" : "mvnw");
            String tool = Files.isRegularFile(wrapper) ? wrapper.toString() : (windows ? "mvn.cmd" : "mvn");
            return new ArrayList<>(List.of(tool, "-q", "test"));
        }
        if (Files.isRegularFile(project.resolve("gradlew"))
                || Files.isRegularFile(project.resolve("gradlew.bat"))
                || Files.isRegularFile(project.resolve("build.gradle"))
                || Files.isRegularFile(project.resolve("build.gradle.kts"))) {
            Path wrapper = project.resolve(windows ? "gradlew.bat" : "gradlew");
            String tool = Files.isRegularFile(wrapper) ? wrapper.toString() : (windows ? "gradle.bat" : "gradle");
            return new ArrayList<>(List.of(tool, "test", "--no-daemon"));
        }
        return new ArrayList<>();
    }

    private static Path ensureClasspath(Path root) throws Exception {
        Path destination = root.resolve("build").resolve("store-run-classpath.txt");
        if (Files.isRegularFile(destination) && Files.size(destination) > 0) return destination;
        Path gradle = root.resolve(PortableProcess.windows() ? "gradlew.bat" : "gradlew");
        Process process = new ProcessBuilder(gradle.toString(), "storeRunClasspath", "--no-daemon")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0 || !Files.isRegularFile(destination)) {
            throw new IllegalStateException("storeRunClasspath did not write a classpath\n" + output);
        }
        return destination;
    }

    private static void writeCache(Path cache, String text) throws Exception {
        if (cache.getParent() != null) Files.createDirectories(cache.getParent());
        Files.writeString(cache, text + "\n");
    }

    /**
     * A Windows batch file is started through one quoted {@code call}.
     * cmd splits {@code %1} on {@code =}, which would drop {@code -Dname=value}.
     */
    private static List<String> launch(List<String> command) {
        if (!PortableProcess.windows() || command.isEmpty()) return command;
        String file = command.get(0).toLowerCase(java.util.Locale.ROOT);
        if (!file.endsWith(".bat") && !file.endsWith(".cmd")) return command;
        StringBuilder call = new StringBuilder("call");
        for (String argument : command) {
            String body = argument == null ? "" : argument;
            call.append(' ').append('"').append(body.replace("\"", "\"\"")).append('"');
        }
        return List.of("cmd.exe", "/d", "/c", call.toString());
    }

    private static Run timed(List<String> command, Path directory) throws Exception {
        long started = System.nanoTime();
        Process process = new ProcessBuilder(launch(command)).directory(directory.toFile()).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = pump(process.getInputStream(), out);
        Thread stderr = pump(process.getErrorStream(), err);
        boolean finished = process.waitFor(4, TimeUnit.MINUTES);
        if (!finished) process.destroyForcibly();
        stdout.join(5_000);
        stderr.join(5_000);
        int code = finished ? process.exitValue() : -1;
        int millis = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new Run(code, millis, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Thread pump(java.io.InputStream input, ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> {
            try (input) {
                input.transferTo(target);
            } catch (Exception ignored) {
                // The process ending closes the pipe.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static List<String> scenarioLines(String text) {
        List<String> kept = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("scenario ") || trimmed.startsWith("step ")) kept.add(trimmed);
        }
        kept.sort(String::compareTo);
        return kept;
    }

    private static Boolean reusedFlag(String text) {
        for (String line : text.split("\\R")) {
            int marker = line.indexOf("reused=");
            if (marker < 0) continue;
            String value = line.substring(marker + "reused=".length()).split("\\s+")[0].strip();
            if ("true".equals(value)) return Boolean.TRUE;
            if ("false".equals(value)) return Boolean.FALSE;
        }
        return null;
    }

    private static String flag(Boolean value) {
        if (Boolean.TRUE.equals(value)) return "true";
        if (Boolean.FALSE.equals(value)) return "false";
        return "missing";
    }

    private static String tail(String label, String text) {
        String body = text == null ? "" : text;
        if (body.length() > 2000) body = body.substring(body.length() - 2000);
        return label + ":\n" + body + "\n";
    }

    record Result(int exit, String text) {
    }

    private record Run(int code, int millis, String out, String err) {
    }
}
