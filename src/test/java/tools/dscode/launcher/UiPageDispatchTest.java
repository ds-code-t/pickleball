package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ui page} goes through {@link PickleballWorkbenchLauncher#dispatch}.
 * Bare {@code ui} still opens the renderer. {@code ui --ops} stays on the catalog batch.
 */
class UiPageDispatchTest {
    @TempDir
    Path project;

    @Test
    void uiPageWritesThePageThroughLauncherDispatch() throws Exception {
        String id = SpineSession.create(project, "agent-1");
        Path html = project.resolve("page.html");
        Files.writeString(html, "<!doctype html><title>page</title><p>hello</p>\n", StandardCharsets.UTF_8);
        String[] args = new String[] {
                "ui", "page",
                "--session=" + id,
                "--name=hello",
                "--html-file=" + html.toAbsolutePath(),
                project.toAbsolutePath().toString()
        };
        assertTrue(PickleballWorkbenchLauncher.controllerUiWithoutOps(
                "ui", new String[] {"ui", project.toAbsolutePath().toString()}));
        assertFalse(PickleballWorkbenchLauncher.controllerUiWithoutOps("ui", new String[] {"ui", "--ops"}));
        assertFalse(
                PickleballWorkbenchLauncher.controllerUiWithoutOps("ui", args),
                "ui page must not open the renderer");
        PickleballWorkbenchLauncher.dispatch(args);
        Path written = project.resolve(".pickleball").resolve("workbench-ui").resolve("generated")
                .resolve(id).resolve("hello.html");
        assertTrue(Files.isRegularFile(written), "launcher dispatch did not write the page");
        assertTrue(Files.readString(written, StandardCharsets.UTF_8).contains("hello"));
        assertFalse(
                Files.isRegularFile(project.resolve(".pickleball").resolve("workbench-ui").resolve("host.json")),
                "ui page started the renderer");
    }

    @Test
    void launcherOrdersSpineThenBidiThenGenericDispatch() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/tools/dscode/launcher/PickleballWorkbenchLauncher.java"),
                StandardCharsets.UTF_8);
        int main = source.indexOf("public static void main");
        int next = source.indexOf("static int dispatchEngineIfAny", main);
        String body = source.substring(main, next);
        int spine = body.indexOf("dispatchSpineIfAny");
        int bidi = body.indexOf("dispatchBidiIfAny");
        int generic = body.lastIndexOf("dispatch(args)");
        assertTrue(spine >= 0 && bidi > spine && generic > bidi, body);
        assertTrue(PickleballWorkbenchLauncher.controllerUiWithoutOps(
                "ui", new String[] {"ui"}));
        assertFalse(PickleballWorkbenchLauncher.controllerUiWithoutOps(
                "ui", new String[] {"ui", "page"}));
        assertFalse(PickleballWorkbenchLauncher.controllerUiWithoutOps(
                "ui", new String[] {"ui", "--ops"}));
    }
}
