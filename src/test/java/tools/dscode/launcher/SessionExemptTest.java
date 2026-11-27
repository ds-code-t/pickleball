package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dan, 2026-11-26: {@code engine help}, {@code domengine version}, family help,
 * and plain {@code export-guidance} run with no spine session. {@code export-guidance --warm}
 * and every other verb still need one. Each command is
 * {@link PickleballWorkbenchLauncher} in its own JVM.
 */
class SessionExemptTest {
    @TempDir
    Path project;

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void exemptCommandsExitZeroWithNoSession() throws Exception {
        Path args = LauncherCli.projectFile(project);
        List<String> problems = new ArrayList<>();
        expectOk(problems, "engine help", "Usage: engine help",
                LauncherCli.run(project, args, "engine", "help"));
        expectOk(problems, "engine --help", "Usage: engine help",
                LauncherCli.run(project, args, "engine", "--help"));
        expectOk(problems, "engine -h", "Usage: engine help",
                LauncherCli.run(project, args, "engine", "-h"));
        expectOk(problems, "domengine version", "\"version\"",
                LauncherCli.run(project, args, "domengine", "version"));
        expectOk(problems, "domengine help", "version",
                LauncherCli.run(project, args, "domengine", "help"));
        expectOk(problems, "domengine --help", "version",
                LauncherCli.run(project, args, "domengine", "--help"));
        expectOk(problems, "store help", "Usage: store",
                LauncherCli.run(project, args, "store", "help"));
        expectOk(problems, "store --help", "Usage: store",
                LauncherCli.run(project, args, "store", "--help"));
        expectOk(problems, "bidi help", "Usage: bidi",
                LauncherCli.run(project, args, "bidi", "help"));
        expectOk(problems, "bidi --help", "Usage: bidi",
                LauncherCli.run(project, args, "bidi", "--help"));
        expectOk(problems, "spine help", "Usage: spine",
                LauncherCli.run(project, args, "spine", "help"));
        expectOk(problems, "spine --help", "Usage: spine",
                LauncherCli.run(project, args, "spine", "--help"));
        expectOk(problems, "browser help", "Usage: browser",
                LauncherCli.run(project, args, "browser", "help"));
        expectOk(problems, "browser --help", "Usage: browser",
                LauncherCli.run(project, args, "browser", "--help"));
        expectOk(problems, "dashboard help", "Usage: dashboard",
                LauncherCli.run(project, args, "dashboard", "help"));
        expectOk(problems, "cross help", "Usage: cross",
                LauncherCli.run(project, args, "cross", "help"));
        expectOk(problems, "log help", "log",
                LauncherCli.run(project, args, "log", "help"));
        expectOk(problems, "help spine", "Usage: spine",
                LauncherCli.run(project, args, "help", "spine"));
        expectOk(problems, "help bidi", "Usage: bidi",
                LauncherCli.run(project, args, "help", "bidi"));
        expectOk(problems, "export-guidance", "Pickleball guidance exported to",
                LauncherCli.run(project, args, "export-guidance", "guidance-out"));
        stray(problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void otherVerbsStillNeedASession() throws Exception {
        Path args = LauncherCli.projectFile(project);
        List<String> problems = new ArrayList<>();
        expectMissing(problems, "store status", LauncherCli.run(project, args, "store", "status"));
        expectMissing(problems, "chat list", LauncherCli.run(project, args, "chat", "list"));
        expectMissing(problems, "inject on", LauncherCli.run(project, args, "inject", "on"));
        expectMissing(problems, "spine start", LauncherCli.run(project, args, "spine", "start"));
        expectMissing(problems, "bidi script", LauncherCli.run(project, args, "bidi", "script", "--source=1"));
        expectMissing(problems, "export-guidance --warm",
                LauncherCli.run(project, args, "export-guidance", "guidance-warm", "--warm"));
        expectMissing(problems, "inject --help", LauncherCli.run(project, args, "inject", "--help"));
        expectMissing(problems, "chat help", LauncherCli.run(project, args, "chat", "help"));
        stray(problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private void stray(List<String> problems) throws Exception {
        Path sessions = project.resolve(".pickleball").resolve("workbench-ui").resolve("sessions");
        if (Files.isDirectory(sessions)) {
            try (var listed = Files.list(sessions)) {
                long count = listed.count();
                if (count > 0) problems.add("session files left behind: " + count);
            }
        }
        if (Files.exists(project.resolve(".pickleball").resolve("agent-controller.json"))) {
            problems.add("controller file left behind");
        }
    }

    private static void expectOk(List<String> problems, String label, String marker, LauncherCli.Capture capture) {
        String text = capture.out() + capture.err();
        if (capture.code() != 0 || !text.contains(marker)) {
            problems.add(label + " code=" + capture.code() + " marker=" + text.contains(marker) + "\n" + trim(text));
        }
    }

    private static void expectMissing(List<String> problems, String label, LauncherCli.Capture capture) {
        String text = capture.out() + capture.err();
        if (capture.code() != 2 || !text.contains("error: missing session") || !text.contains("fix: spine session new")) {
            problems.add(label + " code=" + capture.code() + "\n" + trim(text));
        }
    }

    private static String trim(String text) {
        String flat = text.replace("\r", "");
        return flat.length() > 500 ? flat.substring(0, 500) : flat;
    }
}
