package tools.dscode.untilpass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UntilPassChromeLookupTest {
    @TempDir
    Path temp;

    @Test
    void windowsExeIsAnAcceptedChromeBinary() throws Exception {
        Path dir = temp.resolve("Program Files\\Google\\Chrome\\Application");
        Files.createDirectories(dir);
        Path exe = dir.resolve("chrome.exe");
        Files.writeString(exe, "not-a-real-browser");
        exe.toFile().setExecutable(true);
        String previous = System.getProperty("pickleball.chrome.binary");
        String previousWeb = System.getProperty("webdriver.chrome.binary");
        System.setProperty("pickleball.chrome.binary", exe.toString());
        System.clearProperty("webdriver.chrome.binary");
        try {
            String found = UntilPassBrowserSteps.chromeBinary(null);
            assertTrue(found.endsWith("chrome.exe"), found);
            assertEquals(exe.toString(), found);
        } finally {
            if (previous == null) System.clearProperty("pickleball.chrome.binary");
            else System.setProperty("pickleball.chrome.binary", previous);
            if (previousWeb == null) System.clearProperty("webdriver.chrome.binary");
            else System.setProperty("webdriver.chrome.binary", previousWeb);
        }
    }
}
