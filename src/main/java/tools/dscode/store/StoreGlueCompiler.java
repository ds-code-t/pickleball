package tools.dscode.store;

import tools.dscode.store.sql.Binding;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    private StoreGlueCompiler() {
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

    public static String compile(Path project) throws Exception {
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
