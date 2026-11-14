package dev.domengine.session;

import dev.domengine.cdp.CdpException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void portTimeoutRelaunchesOnceIntoTheSameProfile() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "skipped: /bin/sh is missing");
        Path script = Files.createTempFile("domengine-retry", ".sh");
        Path count = script.resolveSibling(script.getFileName() + ".count");
        Path dbus = script.resolveSibling(script.getFileName() + ".dbus");
        Files.writeString(script, """
                #!/bin/sh
                dir=
                for arg in "$@"; do
                  case "$arg" in
                    --user-data-dir=*) dir=${arg#--user-data-dir=} ;;
                  esac
                done
                test -n "$dir" || exit 2
                count="%s"
                dbus="%s"
                n=0
                if [ -f "$count" ]; then
                  n=$(cat "$count" 2>/dev/null || echo 0)
                fi
                n=$((n + 1))
                printf '%%s\\n' "$n" > "$count"
                printf '%%s\\n' "${DBUS_SESSION_BUS_ADDRESS-unset}" > "$dbus"
                if [ "$n" -eq 1 ]; then
                  echo "first launch writes no port"
                  exit 0
                fi
                printf '%%s\\n' 9444 > "$dir/DevToolsActivePort"
                exit 0
                """.formatted(count, dbus));
        script.toFile().setExecutable(true);
        Path profile = ChromeProcess.newProfile();
        try {
            ChromeProcess browser = ChromeProcess.start(script.toString(), profile, true, java.util.List.of(), "about:blank", 1);
            try {
                assertEquals(9444, browser.port());
                assertEquals(profile, browser.profile());
                assertEquals("2", Files.readString(count).trim());
                String seen = Files.readString(dbus).trim();
                assertTrue(seen.equals("unset") || seen.isEmpty()
                        || seen.startsWith("unix:") || seen.startsWith("tcp:") || seen.equals("disabled:"), seen);
            } finally {
                browser.destroyTree();
            }
        } finally {
            ChromeProcess.deleteProfile(profile);
            Files.deleteIfExists(script);
            Files.deleteIfExists(count);
            Files.deleteIfExists(dbus);
        }
    }

    @Test
    void portTimeoutKeepsOneCopyOfARepeatedDbusLine() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "skipped: /bin/sh is missing");
        Path script = Files.createTempFile("domengine-dbus", ".sh");
        Files.writeString(script, """
                #!/bin/sh
                i=0
                while [ "$i" -lt 40 ]; do
                  echo 'ERROR:dbus/bus.cc:405 Failed to connect to the bus: Could not parse server address: Unknown address type'
                  i=$((i + 1))
                done
                exit 0
                """);
        script.toFile().setExecutable(true);
        Path profile = ChromeProcess.newProfile();
        try {
            CdpException failure = assertThrows(CdpException.class, () ->
                    ChromeProcess.start(script.toString(), profile, true, java.util.List.of(), "about:blank", 1));
            String message = failure.getMessage();
            assertTrue(message.contains("timed out waiting for DevToolsActivePort"));
            assertTrue(message.contains("Unknown address type (x40)"));
            assertTrue(message.contains("relaunch=1"));
            assertEquals(1, countOf(message, "Unknown address type"));
        } finally {
            ChromeProcess.deleteProfile(profile);
            Files.deleteIfExists(script);
        }
    }

    @Test
    void sessionBusAddressIsDisabledUnlessUnixOrTcp() {
        Map<String, String> autolaunch = new HashMap<>();
        autolaunch.put("DBUS_SESSION_BUS_ADDRESS", "autolaunch:");
        ChromeProcess.sanitizeSessionBus(autolaunch);
        assertEquals("disabled:", autolaunch.get("DBUS_SESSION_BUS_ADDRESS"));

        Map<String, String> unix = new HashMap<>();
        unix.put("DBUS_SESSION_BUS_ADDRESS", " unix:path=/tmp/bus");
        ChromeProcess.sanitizeSessionBus(unix);
        assertEquals(" unix:path=/tmp/bus", unix.get("DBUS_SESSION_BUS_ADDRESS"));

        Map<String, String> tcp = new HashMap<>();
        tcp.put("DBUS_SESSION_BUS_ADDRESS", "tcp:host=127.0.0.1,port=1");
        ChromeProcess.sanitizeSessionBus(tcp);
        assertEquals("tcp:host=127.0.0.1,port=1", tcp.get("DBUS_SESSION_BUS_ADDRESS"));

        Map<String, String> absent = new HashMap<>();
        ChromeProcess.sanitizeSessionBus(absent);
        assertFalse(absent.containsKey("DBUS_SESSION_BUS_ADDRESS"));
    }

    private static int countOf(String text, String needle) {
        int hits = 0;
        int from = 0;
        while (from < text.length()) {
            int at = text.indexOf(needle, from);
            if (at < 0) return hits;
            hits++;
            from = at + needle.length();
        }
        return hits;
    }
}
