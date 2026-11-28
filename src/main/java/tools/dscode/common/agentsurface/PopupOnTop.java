package tools.dscode.common.agentsurface;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Always-on-top where the OS allows it. Wayland and macOS record the platform limit. */
public final class PopupOnTop {
    private PopupOnTop() {
    }

    public record Attempt(boolean applied, String method, String reason, List<String> command) {
        public Attempt {
            command = command == null ? List.of() : List.copyOf(command);
        }
    }

    public static Attempt plan(String osName, String sessionType, long pid) {
        String os = osName == null ? "" : osName.toLowerCase();
        String session = sessionType == null ? "" : sessionType.toLowerCase();
        if (os.contains("win")) {
            return new Attempt(true, "SetWindowPos", "", List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", windowsScript(pid)));
        }
        if (os.contains("mac")) {
            List<String> raise = List.of("osascript", "-e",
                    "tell application \"System Events\" to set frontmost of (first process whose unix id is " + pid + ") to true");
            return new Attempt(false, "osascript-frontmost", "macOS has no supported API for another process's window level", raise);
        }
        if (session.contains("wayland") && !session.contains("x11")) {
            return new Attempt(false, "none", "Wayland has no supported API for another process's window level", List.of());
        }
        return new Attempt(true, "wmctrl", "", List.of("wmctrl", "-i", "-r", "<wid>", "-b", "add,above"));
    }

    /**
     * The PowerShell for Windows. It has no double quote: Java does not escape an embedded quote on the Windows
     * command line, so the old script reached PowerShell split and exited 1 (HCR-24). The window is the pid's
     * main window or a child process's (Chrome may draw it from a child), waited for up to 10 s.
     * Exit 2 is no window, exit 3 is SetWindowPos returning false.
     */
    static String windowsScript(long pid) {
        return "$q = [char]34; "
                + "Add-Type -TypeDefinition ('using System; using System.Runtime.InteropServices; public class PkbTop { "
                + "[DllImport(' + $q + 'user32.dll' + $q + ')] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags); }'); "
                + "$h = [IntPtr]::Zero; "
                + "for ($i = 0; $i -lt 40 -and $h -eq [IntPtr]::Zero; $i++) { "
                + "$ids = @(" + pid + ") + @(Get-CimInstance Win32_Process -Filter ('ParentProcessId=' + " + pid + ") -ErrorAction SilentlyContinue | ForEach-Object { $_.ProcessId }); "
                + "foreach ($id in $ids) { $p = Get-Process -Id $id -ErrorAction SilentlyContinue; "
                + "if ($p -and $p.MainWindowHandle -ne [IntPtr]::Zero) { $h = $p.MainWindowHandle; break } } "
                + "if ($h -eq [IntPtr]::Zero) { Start-Sleep -Milliseconds 250 } } "
                + "if ($h -eq [IntPtr]::Zero) { exit 2 } "
                + "if (-not [PkbTop]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 3)) { exit 3 } "
                + "exit 0";
    }

    static String exitReason(String method, int exit, List<String> command) {
        if ("SetWindowPos".equals(method) && exit == 2) return "no window found for the popup browser process";
        if ("SetWindowPos".equals(method) && exit == 3) return "SetWindowPos returned false";
        return "exit " + exit;
    }

    public static Attempt apply(long pid) {
        String session = System.getenv("XDG_SESSION_TYPE");
        if (session == null) session = System.getenv("WAYLAND_DISPLAY") != null ? "wayland" : "x11";
        Attempt planned = plan(System.getProperty("os.name", ""), session, pid);
        if (!planned.applied() || planned.command().isEmpty() || planned.command().contains("<wid>")) {
            if (planned.method().equals("wmctrl")) {
                String wid = windowId(pid);
                if (wid.isBlank()) {
                    return new Attempt(false, "wmctrl", "window id not found for pid " + pid, planned.command());
                }
                List<String> command = new ArrayList<>();
                for (String part : planned.command()) command.add("<wid>".equals(part) ? wid : part);
                return run(command, "wmctrl");
            }
            return planned;
        }
        return run(planned.command(), planned.method());
    }

    private static Attempt run(List<String> command, String method) {
        try {
            Process process = new ProcessBuilder(command).start();
            boolean finished = process.waitFor("SetWindowPos".equals(method) ? 20 : 5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Attempt(false, method, "timed out", command);
            }
            if (process.exitValue() != 0) {
                return new Attempt(false, method, exitReason(method, process.exitValue(), command), command);
            }
            return new Attempt(true, method, "", command);
        } catch (Exception failure) {
            return new Attempt(false, method, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), command);
        }
    }

    private static String windowId(long pid) {
        try {
            Process process = new ProcessBuilder("wmctrl", "-lp").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            String out = new String(process.getInputStream().readAllBytes());
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            for (String line : out.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3 && parts[2].equals(Long.toString(pid))) return parts[0];
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }
}
