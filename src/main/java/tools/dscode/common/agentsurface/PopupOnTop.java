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
            String script = "Add-Type -TypeDefinition '"
                    + "using System; using System.Runtime.InteropServices; public class PkbTop { "
                    + "[DllImport(\"user32.dll\")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags); }'; "
                    + "$p = Get-Process -Id " + pid + "; "
                    + "[PkbTop]::SetWindowPos($p.MainWindowHandle, [IntPtr](-1), 0, 0, 0, 0, 0x0001 -bor 0x0002)";
            return new Attempt(true, "SetWindowPos", "", List.of("powershell", "-NoProfile", "-Command", script));
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
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Attempt(false, method, "timed out", command);
            }
            if (process.exitValue() != 0) {
                return new Attempt(false, method, "exit " + process.exitValue(), command);
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
