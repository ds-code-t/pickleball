package tools.dscode.launcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@link PickleballWorkbenchLauncher} JVM. The classpath is a Java argument
 * file. Verb flags travel in a JSON {@code @} file when the caller supplies one.
 */
final class LauncherCli {
    private LauncherCli() {
    }

    static Capture run(Path project, Path argsFile, String... args) throws Exception {
        Path jvm = Files.createTempFile(project, "launcher-", ".args");
        List<String> lines = new ArrayList<>();
        lines.add("-Dfile.encoding=UTF-8");
        lines.add("-Dpkb_journal=off");
        lines.add("-cp");
        lines.add(quote(System.getProperty("java.class.path")));
        lines.add("tools.dscode.launcher.PickleballWorkbenchLauncher");
        Files.write(jvm, lines, StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("@" + jvm.toAbsolutePath().normalize());
        if (args != null) command.addAll(List.of(args));
        if (argsFile != null) command.add("--args-file=" + project.relativize(argsFile.toAbsolutePath().normalize()));
        Process process = new ProcessBuilder(command).directory(project.toFile()).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outThread = new Thread(() -> copy(process.getInputStream(), out), "launcher-cli-out");
        Thread errThread = new Thread(() -> copy(process.getErrorStream(), err), "launcher-cli-err");
        outThread.start();
        errThread.start();
        int code = process.waitFor();
        outThread.join();
        errThread.join();
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static Path projectFile(Path project) throws Exception {
        Path file = project.resolve("cli-args.json");
        String path = project.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
        Files.writeString(file, "{\"project\":\"" + path + "\"}\n", StandardCharsets.UTF_8);
        return file;
    }

    private static void copy(java.io.InputStream input, ByteArrayOutputStream sink) {
        try {
            input.transferTo(sink);
        } catch (java.io.IOException failure) {
            sink.writeBytes(failure.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\\' || ch == '"') quoted.append('\\');
            quoted.append(ch);
        }
        return quoted.append('"').toString();
    }

    record Capture(int code, String out, String err) {
    }
}
