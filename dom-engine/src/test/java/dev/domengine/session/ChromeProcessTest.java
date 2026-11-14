package dev.domengine.session;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChromeProcessTest {
    @Test
    void launcherExitStillPublishesTheDebugPort() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "skipped: /bin/sh is missing");
        Path script = Files.createTempFile("domengine-launcher", ".sh");
        Files.writeString(script, """
                #!/bin/sh
                dir=
                for arg in "$@"; do
                  case "$arg" in
                    --user-data-dir=*) dir=${arg#--user-data-dir=} ;;
                  esac
                done
                test -n "$dir" || exit 2
                printf '%s\\n' 9333 > "$dir/DevToolsActivePort"
                exit 0
                """);
        script.toFile().setExecutable(true);
        Path profile = ChromeProcess.newProfile();
        try {
            ChromeProcess browser = ChromeProcess.start(script.toString(), profile, true, java.util.List.of(), "about:blank");
            try {
                assertEquals(9333, browser.port());
                assertFalse(browser.isAlive());
            } finally {
                browser.destroyTree();
            }
        } finally {
            ChromeProcess.deleteProfile(profile);
            Files.deleteIfExists(script);
        }
    }
}
