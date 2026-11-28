package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PKB-HCR 2026-11-28 item 17: {@code --exe} names are found on PATH, and launch ids work in the next CLI call. */
class DesktopExeAndIdsTest {
    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        System.clearProperty("domengine.desktop.sessions");
    }

    @Test
    void aBareProgramNameIsFoundOnPathNotUnderTheProject() throws Exception {
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Path project = Files.createDirectories(temp.resolve("project"));
        Path tool = Files.writeString(bin.resolve("tool.exe"), "");
        assertEquals(tool, DesktopLaunch.resolveExe("tool.exe", bin.toString(), null, project));
        assertEquals(tool, DesktopLaunch.resolveExe("tool", bin.toString(), ".EXE;.CMD", project), "PATHEXT is tried");
    }

    @Test
    void aProjectFileAndAPathWithADirectoryStayProjectRelative() throws Exception {
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(bin.resolve("tool.exe"), "");
        Path local = Files.writeString(project.resolve("tool.exe"), "");
        assertEquals(local, DesktopLaunch.resolveExe("tool.exe", bin.toString(), null, project));
        assertEquals(project.resolve("sub/tool.exe"), DesktopLaunch.resolveExe("sub/tool.exe", bin.toString(), null, project));
        assertEquals(project.resolve("missing.exe"), DesktopLaunch.resolveExe("missing.exe", bin.toString(), null, project),
                "no match keeps the project path so the error names it");
    }

    @Test
    void aLaunchIdIsKeptForTheNextProcessAndCloseDropsIt() {
        System.setProperty("domengine.desktop.sessions", temp.resolve("sessions.properties").toString());
        DesktopSessionStore.remember("launch-123", "Notepad");
        assertEquals("Notepad", DesktopSessionStore.windowOf("launch-123"));
        assertTrue(DesktopTree.close("launch-123").path("closed").asBoolean(), "close of a kept id from another process");
        assertNull(DesktopSessionStore.windowOf("launch-123"));
        assertThrows(CdpException.class, () -> DesktopTree.close("launch-123"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void notepadResolvesOnTheWindowsPath() {
        Path found = DesktopLaunch.resolveExe("notepad.exe", System.getenv("PATH"), System.getenv("PATHEXT"), temp);
        assertTrue(Files.isRegularFile(found), "notepad.exe should be on PATH: " + found);
    }
}
