package tools.dscode.store;

import tools.dscode.engine.EngineLog;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.runtime.WarmClassLoader;
import tools.dscode.engine.runtime.WarmFlags;
import tools.dscode.engine.runtime.WarmPackages;
import tools.dscode.engine.runtime.WarmProcessFile;
import tools.dscode.engine.runtime.WarmSpine;
import tools.dscode.store.sql.Binding;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Compiles database-edited {@code .java} glue with {@code javax.tools} into the
 * sandbox classes directory. That directory is prepended on the worker classpath.
 * JS and TS rows are stored only. There is no JS/TS step runtime.
 */
public final class StoreGlueCompiler {
    public static final String DELETE_REFUSED =
            "deleting a class cannot be hidden by a prepended classes directory; use a Git worktree";
    static final String NO_JDK =
            "error: glue compile needs a JDK (javax.tools.JavaCompiler is absent)\n"
                    + "fix: run pickleball store glue compile with a JDK, not a JRE";

    /** Tests set this to force a missing compiler. Production leaves it null. */
    static volatile Supplier<JavaCompiler> compilerOverride;

    private static final Map<String, Map<String, String>> WARM_HASHES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> CHANGED_HASHES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> WARM_STARTED = new ConcurrentHashMap<>();

    private StoreGlueCompiler() {
    }

    /**
     * One warm compile. The first call for a project may name the wrapper and does not spawn it
     * unless {@link #warmStart} is asked to run it. A later changed file uses {@code JavaCompiler}.
     */
    public static IncrementalDecision warmCompile(Path project, Path javaFile) throws java.io.IOException {
        Path root = root(project);
        String projectKey = root.toString();
        String fileKey = javaFile.toAbsolutePath().normalize().toString();
        String hash = sha256(Files.readAllBytes(javaFile));
        Map<String, String> hashes = WARM_HASHES.computeIfAbsent(projectKey, ignored -> new ConcurrentHashMap<>());
        String previous = hashes.put(fileKey, hash);
        boolean first = WARM_STARTED.putIfAbsent(projectKey, Boolean.TRUE) == null;
        if (first) {
            IncrementalDecision planned = planWarm(root, true);
            if (!"JavaCompiler".equals(planned.tool())) return planned;
            return compileIncremental(root, javaFile);
        }
        if (previous != null && previous.equals(hash)) return planWarm(root, false);
        return compileIncremental(root, javaFile);
    }

    /**
     * First compile on {@code spine start}. A project with a wrapper records that command.
     * {@code execute} runs the wrapper once. Later calls do not.
     */
    public static IncrementalDecision warmStart(Path project, boolean execute) throws Exception {
        Path root = root(project);
        Path marker = root.resolve(".pickleball").resolve("spine").resolve("wrapper-compiled");
        if (Files.isRegularFile(marker)) return planWarm(root, false);
        if (!hasJavaSources(root)) return planWarm(root, false);
        IncrementalDecision planned = planWarm(root, true);
        if (!"wrapper".equals(planned.tool())) return planned;
        if (execute) {
            ProcessBuilder builder = new ProcessBuilder(planned.command());
            builder.directory(root.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException("error: wrapper compile failed\nfix: run the project wrapper and read its log");
            }
        }
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, planned.commandLine(), StandardCharsets.UTF_8);
        WARM_STARTED.put(root.toString(), Boolean.TRUE);
        return planned;
    }

    /** Command the next warm compile would use. {@code first} may be the project wrapper. */
    public static IncrementalDecision planWarm(Path project, boolean first) {
        Path root = root(project);
        if (first) {
            List<String> wrapper = StoreRun.compileCommand(root);
            if (!wrapper.isEmpty()) {
                return new IncrementalDecision("wrapper", wrapper, false, "first compile");
            }
        }
        return new IncrementalDecision("JavaCompiler", List.of("JavaCompiler"), false, "incremental");
    }

    /**
     * Compiles one file with {@code javax.tools.JavaCompiler}. A diagnostic that says
     * {@code cannot find symbol} or {@code package ... does not exist} is a rebuild decision.
     * The wrapper command is returned and is not executed.
     */
    public static IncrementalDecision compileIncremental(Path project, Path javaFile) throws java.io.IOException {
        Path root = root(project);
        JavaCompiler compiler = compiler();
        if (compiler == null) throw new IllegalStateException(NO_JDK);
        Path classes = warmClasses(root);
        Files.createDirectories(classes);
        List<String> options = new ArrayList<>();
        options.add("-d");
        options.add(classes.toAbsolutePath().normalize().toString());
        String classpath = String.join(java.io.File.pathSeparator, StoreRun.classpathEntries(root, false));
        if (!classpath.isBlank()) {
            options.add("-classpath");
            options.add(classpath + java.io.File.pathSeparator + classes);
        }
        javax.tools.StandardJavaFileManager filesManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        try (filesManager) {
            Iterable<? extends javax.tools.JavaFileObject> units = filesManager.getJavaFileObjectsFromPaths(List.of(javaFile));
            javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> collector = new javax.tools.DiagnosticCollector<>();
            Boolean ok = compiler.getTask(null, filesManager, collector, options, null, units).call();
            StringBuilder diagnostics = new StringBuilder();
            boolean missing = false;
            for (var diagnostic : collector.getDiagnostics()) {
                String message = diagnostic.getMessage(Locale.ROOT);
                diagnostics.append(message).append('\n');
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("cannot find symbol") || lower.contains("does not exist")) missing = true;
            }
            if (missing) {
                List<String> wrapper = StoreRun.compileCommand(root);
                EngineLog.log(root, "host", "warm", "rebuild");
                String detail = "rebuild: stop the warm JVM, run the wrapper compile, start again";
                if (!diagnostics.isEmpty()) detail = detail + "\n" + diagnostics.toString().trim();
                return new IncrementalDecision("wrapper", wrapper, true, detail);
            }
            if (!Boolean.TRUE.equals(ok)) {
                throw new IllegalStateException("glue compile failed\nfix: " + diagnostics.toString().trim());
            }
        }
        EngineLog.log(root, "host", "warm", "reload");
        return new IncrementalDecision("JavaCompiler", List.of("JavaCompiler"), false, "incremental");
    }

    public static Path warmClasses(Path project) {
        return root(project).resolve(".pickleball").resolve("spine").resolve("classes");
    }

    /** Tests drop the in-memory warm hashes. */
    public static void resetWarmState() {
        WARM_HASHES.clear();
        CHANGED_HASHES.clear();
        WARM_STARTED.clear();
    }

    public record IncrementalDecision(String tool, List<String> command, boolean rebuild, String detail) {
        public IncrementalDecision {
            command = command == null ? List.of() : List.copyOf(command);
        }

        public String commandLine() {
            return String.join(" ", command);
        }
    }

    public static int cli(Path project, PrintStream out, PrintStream err) {
        try {
            String line = compile(project);
            out.println(line);
            return 0;
        } catch (IllegalStateException failure) {
            String message = failure.getMessage() == null ? NO_JDK : failure.getMessage();
            for (String line : message.split("\\R")) {
                if (line.startsWith("error:") || line.startsWith("fix:")) err.println(line);
                else err.println("error: " + line);
            }
            if (!message.contains("fix:")) err.println("fix: store glue compile");
            return 1;
        } catch (Exception failure) {
            err.println("error: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            err.println("fix: store glue compile");
            return 1;
        }
    }

    /**
     * Compiles Java sources whose hash changed into {@code .pickleball/spine/classes} and reloads the warm loader.
     * A missing symbol returns the wrapper command and does not run it. {@link StoreRun#compileGlue} runs that command.
     */
    public static IncrementalDecision compileChanged(Path project) throws java.io.IOException {
        Path root = root(project);
        return compileHashed(root, javaSources(root));
    }

    public static String compile(Path project) throws Exception {
        Path root = root(project);
        if (WarmFlags.enabled() && WarmProcessFile.alive(root)) {
            List<Row> rows = editedJava(project);
            if (rows.isEmpty()) {
                StoreLog.log(project, "glue", "compile", StoreScope.label(StoreScope.current()), "no edited java");
                return "glue compile: no edited java";
            }
            List<Path> files = new ArrayList<>();
            Path scratch = root.resolve(".pickleball").resolve("spine").resolve("sources");
            for (Row row : rows) {
                Path file = scratch.resolve(row.path());
                Files.createDirectories(file.getParent());
                Files.write(file, row.body());
                files.add(file);
            }
            IncrementalDecision edited = compileHashed(root, files);
            if (edited.rebuild()) throw new IllegalStateException(edited.detail());
            StoreLog.log(project, "glue", "compile", StoreScope.label(StoreScope.current()), "classes=" + rows.size());
            return "glue compile: " + rows.size();
        }
        Binding binding = StoreScope.current();
        String box = StoreScope.label(binding);
        List<Row> rows = editedJava(project);
        if (rows.isEmpty()) {
            StoreLog.log(project, "glue", "compile", box, "no edited java");
            return "glue compile: no edited java";
        }
        JavaCompiler compiler = compiler();
        if (compiler == null) throw new IllegalStateException(NO_JDK);
        Path sources = StorePaths.glueSources(project, box);
        Path classes = StorePaths.classes(project, box);
        Files.createDirectories(sources);
        clearClasses(classes);
        Files.createDirectories(classes);
        List<Path> files = new ArrayList<>();
        for (Row row : rows) {
            Path file = sources.resolve(row.path());
            Files.createDirectories(file.getParent());
            Files.write(file, row.body());
            files.add(file);
        }
        List<String> options = new ArrayList<>();
        String classpath = String.join(java.io.File.pathSeparator, StoreRun.classpathEntries(project, false));
        if (!classpath.isBlank()) {
            options.add("-classpath");
            options.add(classpath);
        }
        options.add("-d");
        options.add(classes.toAbsolutePath().normalize().toString());
        javax.tools.StandardJavaFileManager filesManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        try (filesManager) {
            Iterable<? extends javax.tools.JavaFileObject> units = filesManager.getJavaFileObjectsFromPaths(files);
            StringBuilder diagnostics = new StringBuilder();
            javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> collector = new javax.tools.DiagnosticCollector<>();
            Boolean ok = compiler.getTask(null, filesManager, collector, options, null, units).call();
            for (var diagnostic : collector.getDiagnostics()) {
                diagnostics.append(diagnostic.getMessage(Locale.ROOT)).append('\n');
            }
            if (!Boolean.TRUE.equals(ok)) {
                throw new IllegalStateException("glue compile failed\nfix: " + diagnostics.toString().trim());
            }
        }
        StoreLog.log(project, "glue", "compile", box, "classes=" + rows.size());
        return "glue compile: " + rows.size();
    }

    private static void clearClasses(Path classes) throws java.io.IOException {
        if (!Files.isDirectory(classes)) return;
        List<Path> paths;
        try (var walk = Files.walk(classes)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            if (!path.equals(classes)) Files.deleteIfExists(path);
        }
    }

    static boolean hasEditedJava(Path project) {
        try {
            return !editedJava(project).isEmpty();
        } catch (Exception failure) {
            return false;
        }
    }

    private static JavaCompiler compiler() {
        Supplier<JavaCompiler> override = compilerOverride;
        if (override != null) return override.get();
        return ToolProvider.getSystemJavaCompiler();
    }

    private static Path root(Path project) {
        return project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
    }

    private static IncrementalDecision compileHashed(Path root, List<Path> files) throws java.io.IOException {
        Map<String, String> hashes = CHANGED_HASHES.computeIfAbsent(root.toString(), ignored -> loadHashes(root));
        IncrementalDecision last = new IncrementalDecision("JavaCompiler", List.of("JavaCompiler"), false, "unchanged");
        boolean any = false;
        for (Path file : files) {
            String key = file.toAbsolutePath().normalize().toString();
            String hash = sha256(Files.readAllBytes(file));
            String previous = hashes.get(key);
            if (previous != null && previous.equals(hash)) continue;
            IncrementalDecision decision = compileIncremental(root, file);
            if (decision.rebuild()) {
                if (any) saveHashes(root, hashes);
                return decision;
            }
            hashes.put(key, hash);
            any = true;
            last = decision;
        }
        if (any) {
            saveHashes(root, hashes);
            reloadWarm(root);
        }
        return last;
    }

    private static Path hashFile(Path root) {
        return root.resolve(".pickleball").resolve("spine").resolve("source-hashes.txt");
    }

    /** Lines are {@code hash<TAB>absolutePath}. A missing file compiles every source. */
    private static Map<String, String> loadHashes(Path root) {
        Map<String, String> hashes = new ConcurrentHashMap<>();
        Path file = hashFile(root);
        if (!Files.isRegularFile(file)) return hashes;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab >= line.length() - 1) continue;
                hashes.put(line.substring(tab + 1), line.substring(0, tab));
            }
        } catch (java.io.IOException ignored) {
            return hashes;
        }
        return hashes;
    }

    private static void saveHashes(Path root, Map<String, String> hashes) throws java.io.IOException {
        Path file = hashFile(root);
        Files.createDirectories(file.getParent());
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            body.append(entry.getValue()).append('\t').append(entry.getKey()).append('\n');
        }
        Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
    }

    private static void reloadWarm(Path project) {
        Path classes = warmClasses(project);
        if (!Files.isDirectory(classes)) return;
        java.net.URL url;
        try {
            url = classes.toUri().toURL();
        } catch (java.net.MalformedURLException failure) {
            return;
        }
        WarmClassLoader next = new WarmClassLoader(
                new java.net.URL[] {url}, WarmClassLoader.spineLoader(), WarmPackages.resolve(project));
        String snapshot = "live";
        ExecutionContext context = ExecutionContext.current();
        if (context != null && context.id() != null && !context.id().isBlank()) snapshot = context.id();
        WarmSpine.shared().replace(snapshot, next);
    }

    private static List<Path> javaSources(Path root) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        for (String dir : List.of("src/main/java", "src/test/java")) {
            Path source = root.resolve(dir);
            if (!Files.isDirectory(source)) continue;
            try (var walk = Files.walk(source)) {
                walk.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static boolean hasJavaSources(Path root) throws java.io.IOException {
        for (String dir : List.of("src/main/java", "src/test/java")) {
            Path source = root.resolve(dir);
            if (!Files.isDirectory(source)) continue;
            try (var walk = Files.walk(source)) {
                if (walk.anyMatch(file -> Files.isRegularFile(file) && file.toString().endsWith(".java"))) return true;
            }
        }
        return false;
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception failure) {
            throw new IllegalStateException("error: glue hash failed", failure);
        }
    }

    private static List<Row> editedJava(Path project) throws Exception {
        if (!StorePaths.ready(project)) return List.of();
        List<Row> rows = new ArrayList<>();
        try (StoreDatabase database = StoreDatabase.open(project);
             var statement = database.connection().createStatement();
             var result = statement.executeQuery(
                     "SELECT path, raw_content FROM files WHERE db_edited = 1 AND path LIKE '%.java' ORDER BY path")) {
            while (result.next()) {
                String path = result.getString(1);
                byte[] body = result.getBytes(2);
                if (path == null || body == null) continue;
                if (!StoreEdit.isGluePath(path)) continue;
                rows.add(new Row(path, body));
            }
        }
        return rows;
    }

    private record Row(String path, byte[] body) {
    }
}
