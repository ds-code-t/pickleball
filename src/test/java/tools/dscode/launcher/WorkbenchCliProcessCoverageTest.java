package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.SessionDescriptor;
import tools.dscode.control.protocol.SessionDescriptorWriter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real launcher processes. The classpath is this test JVM, so agent commands
 * run the same main class a consumer {@code java -jar} hops into.
 */
class WorkbenchCliProcessCoverageTest {
    @TempDir
    Path temp;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void hintResolveAndQuotingRunAsProcesses() throws Exception {
        Path resources = temp.resolve("src/test/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("pickleball.properties"),
                "pkb_browser=CHROME_HEADLESS\npkb_name=caf\u00e9\npkb_configpath=wd-file:configs\n",
                StandardCharsets.UTF_8);

        Run hint = launch("hint", temp.toString());
        assertEquals(0, hint.exit, hint.err);
        assertTrue(hint.out.contains("NEXT: run discover"), hint.out);
        assertTrue(hint.out.contains("caf\u00e9") || hint.out.contains("pkb_name"), hint.out);
        assertTrue(hint.out.contains("Dry-run resolve"));

        Run resolved = launch("resolve-runvars", temp.toString());
        assertEquals(0, resolved.exit, resolved.err);
        assertTrue(resolved.out.contains("sealed=false"), resolved.out);
        assertTrue(resolved.out.contains("wd-file:configs"), resolved.out);
        assertFalse(resolved.out.contains("NEXT: run discover"));

        Run quoting = launch("discover", temp.toString(), "--tags=@a", "and", "@b");
        assertEquals(2, quoting.exit, quoting.err);
        assertTrue(quoting.err.contains("quoting"), quoting.err);

        Path guidance = temp.resolve("guidance");
        Run exported = launch("export-guidance", guidance.toString());
        assertEquals(0, exported.exit, exported.err);
        assertTrue(Files.isRegularFile(guidance.resolve("AGENT-GUIDE.md")));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void forwardedSyncFailsClosedWithoutTheEmbeddedController() throws Exception {
        Run sync = launch("sync", temp.toString());
        assertEquals(1, sync.exit, sync.err);
        assertTrue(sync.err.contains(ControlProtocol.EMBEDDED_WORKBENCH_RESOURCE)
                || sync.err.contains("Could not launch"), sync.err);
        assertFalse(Files.isDirectory(temp.resolve("target")));
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void concurrentHintsDoNotShareStdout() throws Exception {
        Path one = temp.resolve("one");
        Path two = temp.resolve("two");
        Files.createDirectories(one.resolve("src/test/resources"));
        Files.createDirectories(two.resolve("src/test/resources"));
        Files.writeString(one.resolve("src/test/resources/pickleball.properties"), "pkb_browser=EDGE\n");
        Files.writeString(two.resolve("src/test/resources/pickleball.properties"), "pkb_browser=CHROME\n");
        AtomicReference<Run> left = new AtomicReference<>();
        AtomicReference<Run> right = new AtomicReference<>();
        Thread first = new Thread(() -> left.set(launchUnchecked("hint", one.toString())));
        Thread second = new Thread(() -> right.set(launchUnchecked("hint", two.toString())));
        first.start();
        second.start();
        first.join(60_000);
        second.join(60_000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(0, left.get().exit, left.get().err);
        assertEquals(0, right.get().exit, right.get().err);
        assertTrue(left.get().out.contains("pkb_browser=EDGE"), left.get().out);
        assertTrue(right.get().out.contains("pkb_browser=CHROME"), right.get().out);
        assertFalse(left.get().out.contains("pkb_browser=CHROME"), left.get().out);
        assertFalse(right.get().out.contains("pkb_browser=EDGE"), right.get().out);
    }

    @Test
    void corruptWorkspaceIsReportedInsteadOfAPassedRun() throws Exception {
        Path workspace = temp.resolve(".pickleball/runs/stale-1/workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("runvars.properties"), "pkb_glue=\\uZZZZ\n", StandardCharsets.UTF_8);
        Run show = launch("workspace-show", temp.toString(), "--run-id=stale-1");
        assertEquals(1, show.exit, show.out + show.err);
        assertTrue((show.err + show.out).contains("failed") || (show.err + show.out).contains("present=false")
                || (show.err + show.out).contains("Could not read"), show.err + show.out);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void consumerProjectCellsRunAsProcesses() throws Exception {
        Path consumer = Path.of("maven-consumer-project").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(consumer.resolve("mvnw")), consumer.toString());
        assertTrue(Files.isRegularFile(consumer.resolve("src/test/resources/pickleball.properties")));

        Run hint = launch("hint", consumer.toString());
        assertEquals(0, hint.exit, hint.err);
        assertTrue(hint.out.contains("Dry-run resolve"), hint.out);
        assertTrue(hint.out.contains("NEXT: run discover"), hint.out);

        Run resolved = launch("resolve-runvars", consumer.toString());
        assertEquals(0, resolved.exit, resolved.err);
        assertTrue(resolved.out.contains("sealed=false"), resolved.out);
        assertFalse(resolved.out.contains("NEXT: run discover"), resolved.out);

        Run quoting = launch("discover", consumer.toString(), "--tags=@a", "and", "@b");
        assertEquals(2, quoting.exit, quoting.err);
        assertTrue(quoting.err.contains("quoting"), quoting.err);

        Run listed = launch("session", "list", "--json", consumer.toString());
        assertEquals(0, listed.exit, listed.err);
        assertTrue(listed.out.trim().startsWith("["), listed.out);

        Run missingInfo = launch("session", "info", "--json", consumer.toString());
        assertEquals(2, missingInfo.exit, missingInfo.err);
        assertTrue(missingInfo.err.contains("Usage: session info"), missingInfo.err);

        for (String verb : List.of("play", "from-here", "pause")) {
            Run player = launch(verb, consumer.toString());
            assertEquals(1, player.exit, verb + " " + player.err);
            assertTrue(player.err.contains("No healthy Workbench session"), player.err);
        }

        AtomicReference<Run> left = new AtomicReference<>();
        AtomicReference<Run> right = new AtomicReference<>();
        Thread first = new Thread(() -> left.set(launchUnchecked("hint", consumer.toString())));
        Thread second = new Thread(() -> right.set(launchUnchecked("resolve-runvars", consumer.toString())));
        first.start();
        second.start();
        first.join(60_000);
        second.join(60_000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(0, left.get().exit, left.get().err);
        assertEquals(0, right.get().exit, right.get().err);
        assertTrue(left.get().out.contains("NEXT: run discover"), left.get().out);
        assertFalse(right.get().out.contains("NEXT: run discover"), right.get().out);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void staleExpectAndProviderFlagsRunAsProcesses() throws Exception {
        Path workspace = temp.resolve(".pickleball/runs/stale-2/workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("runvars.properties"), "pkb_browser=CHROME_HEADLESS\n", StandardCharsets.UTF_8);
        String before = Files.readString(workspace.resolve("runvars.properties"));
        Run stale = launch("workspace-edit", temp.toString(), "--run-id=stale-2", "--expect=not-the-fingerprint", "--runvar=pkb_browser=EDGE");
        assertEquals(3, stale.exit, stale.err);
        assertTrue(stale.err.contains("Nothing was written"), stale.err);
        assertEquals(before, Files.readString(workspace.resolve("runvars.properties")));

        Instant started = Instant.parse("2026-10-29T18:00:00Z");
        SessionDescriptor off = new SessionDescriptor(
                "proc-off", "run-off", temp.resolve("workspace").toString(), "chrome",
                null, null, List.of(), SessionDescriptor.State.RUNNING, started, started, null, null
        );
        SessionDescriptor on = new SessionDescriptor(
                "proc-on", "run-on", temp.resolve("workspace").toString(), "chrome",
                "127.0.0.1:9222", null, List.of(), SessionDescriptor.State.RUNNING, started, started,
                temp.resolve("composite").toString(), "comp-proc"
        );
        SessionDescriptorWriter.publish(temp, off, true);
        SessionDescriptorWriter.publish(temp, on, true);
        Run offInfo = launch("session", "info", "proc-off", temp.toString());
        Run onInfo = launch("session", "info", "proc-on", "--json", temp.toString());
        assertEquals(0, offInfo.exit, offInfo.err);
        assertEquals(0, onInfo.exit, onInfo.err);
        assertFalse(offInfo.out.contains("comp-proc"), offInfo.out);
        assertTrue(onInfo.out.contains("compositeSessionDir"), onInfo.out);
        assertTrue(onInfo.out.contains("comp-proc"), onInfo.out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void failureIgnoreWrapperExitZeroStillFailsTheProcess() throws Exception {
        Path features = temp.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("one.feature"), """
                @boom
                Feature: One
                  Scenario: It fails
                    Given one
                """, StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("pom.xml"), "<project/>\n");
        Path script = temp.resolve("mvnw");
        Files.writeString(script, """
                #!/bin/sh
                run_id=""
                for arg in "$@"; do
                  case "$arg" in
                    -Dpkb_run_id=*) run_id="${arg#-Dpkb_run_id=}" ;;
                  esac
                done
                if [ -z "$run_id" ]; then
                  echo "missing pkb_run_id" >&2
                  exit 1
                fi
                dir=".pickleball/runs/$run_id/diagnostic/$run_id"
                mkdir -p "$dir"
                printf '%s\\n' "{\\"runId\\":\\"$run_id\\",\\"outcome\\":\\"FAILED\\",\\"counts\\":{\\"failed\\":1,\\"errors\\":0}}" > "$dir/run-index.json"
                exit 0
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        ));

        Run discover = launch("discover", temp.toString(), "--tags=@boom", "--run-id=ignore-proc");
        assertEquals(1, discover.exit, discover.out + discover.err);
        assertTrue(discover.out.contains("summary status=FAILED"), discover.out + discover.err);
        String record = Files.readString(temp.resolve(".pickleball/runs/ignore-proc/record.json"));
        assertTrue(record.contains("FAILED"), record);
        assertFalse(record.contains("\"status\" : \"PASSED\"") || record.contains("\"status\": \"PASSED\""), record);
    }

    private static Run launchUnchecked(String... args) {
        try {
            return launch(args);
        } catch (Exception failure) {
            return new Run(-1, "", failure.toString());
        }
    }

    private static Run launch(String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("tools.dscode.launcher.PickleballWorkbenchLauncher");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = pump(process.getInputStream(), out);
        Thread stderr = pump(process.getErrorStream(), err);
        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        stdout.join(5_000);
        stderr.join(5_000);
        return new Run(
                finished ? process.exitValue() : -1,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)
        );
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

    private record Run(int exit, String out, String err) {
    }
}
