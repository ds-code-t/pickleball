package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.tree.DomNode;

import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

/** macOS Accessibility through System Events. Off-platform calls fail before osascript. */
final class MacAccessibility {
    private MacAccessibility() {
    }

    static void requireMac() {
        if (!"macos".equals(NativeDesktop.family())) throw new CdpException("requires macOS");
    }

    static String captureScript() {
        return """
                on clean(value)
                  set value to value as text
                  set AppleScript's text item delimiters to {tab, return, linefeed}
                  set parts to text items of value
                  set AppleScript's text item delimiters to " "
                  set value to parts as text
                  set AppleScript's text item delimiters to ""
                  return value
                end clean

                on dump(el, depth, pathText)
                  if depth > 12 then
                    return "TRUNCATED" & linefeed
                  end if
                  set r to "unknown"
                  set n to ""
                  set x to 0
                  set y to 0
                  set w to 0
                  set h to 0
                  try
                    set r to role of el as text
                  end try
                  try
                    set n to name of el as text
                  end try
                  try
                    set p to position of el
                    set s to size of el
                    set x to item 1 of p
                    set y to item 2 of p
                    set w to item 1 of s
                    set h to item 2 of s
                  end try
                  set lineText to "NODE" & tab & depth & tab & r & tab & x & tab & y & tab & w & tab & h & tab & pathText & tab & my clean(n) & linefeed
                  set more to ""
                  try
                    set kids to UI elements of el
                    set indexNum to 1
                    repeat with kid in kids
                      set childPath to pathText & "/" & indexNum
                      set more to more & my dump(kid, depth + 1, childPath)
                      set indexNum to indexNum + 1
                    end repeat
                  end try
                  return lineText & more
                end dump

                set output to "NODE" & tab & "0" & tab & "desktop" & tab & "0" & tab & "0" & tab & "0" & tab & "0" & tab & "" & tab & "Desktop" & linefeed
                tell application "System Events"
                  set indexNum to 1
                  repeat with proc in processes
                    set output to output & my dump(proc, 1, indexNum as text)
                    set indexNum to indexNum + 1
                  end repeat
                end tell
                if output does not contain "NODE" then
                  error "AX unavailable: no processes"
                end if
                return output
                """;
    }

    static String clickScript() {
        return """
                on walk(el, pathText)
                  if pathText is "" then return el
                  set AppleScript's text item delimiters to "/"
                  set indexes to text items of pathText
                  set AppleScript's text item delimiters to ""
                  set currentEl to el
                  repeat with indexText in indexes
                    set n to indexText as integer
                    set kids to UI elements of currentEl
                    set currentEl to item n of kids
                  end repeat
                  return currentEl
                end walk

                set pathText to system attribute "DESKTOP_AX_PATH"
                tell application "System Events"
                  set AppleScript's text item delimiters to "/"
                  set indexes to text items of pathText
                  set AppleScript's text item delimiters to ""
                  if (count of indexes) is 0 then error "click failed: missing ax-path"
                  set proc to item ((item 1 of indexes) as integer) of processes
                  if (count of indexes) is 1 then
                    set targetEl to proc
                  else
                    set rest to ""
                    repeat with i from 2 to (count of indexes)
                      if rest is not "" then set rest to rest & "/"
                      set rest to rest & (item i of indexes)
                    end repeat
                    set targetEl to my walk(proc, rest)
                  end if
                  perform action "AXPress" of targetEl
                end tell
                return "pressed"
                """;
    }

    static String typeScript() {
        return """
                on walk(el, pathText)
                  if pathText is "" then return el
                  set AppleScript's text item delimiters to "/"
                  set indexes to text items of pathText
                  set AppleScript's text item delimiters to ""
                  set currentEl to el
                  repeat with indexText in indexes
                    set n to indexText as integer
                    set kids to UI elements of currentEl
                    set currentEl to item n of kids
                  end repeat
                  return currentEl
                end walk

                set pathText to system attribute "DESKTOP_AX_PATH"
                set payload to system attribute "DESKTOP_TEXT"
                set appendFlag to system attribute "DESKTOP_APPEND"
                tell application "System Events"
                  set AppleScript's text item delimiters to "/"
                  set indexes to text items of pathText
                  set AppleScript's text item delimiters to ""
                  if (count of indexes) is 0 then error "type failed: missing ax-path"
                  set proc to item ((item 1 of indexes) as integer) of processes
                  if (count of indexes) is 1 then
                    set targetEl to proc
                  else
                    set rest to ""
                    repeat with i from 2 to (count of indexes)
                      if rest is not "" then set rest to rest & "/"
                      set rest to rest & (item i of indexes)
                    end repeat
                    set targetEl to my walk(proc, rest)
                  end if
                  try
                    set nextValue to payload
                    if appendFlag is "true" then set nextValue to (value of targetEl as text) & payload
                    set value of targetEl to nextValue
                    return "valued"
                  end try
                  try
                    set focused of targetEl to true
                    keystroke payload
                    return "keyed"
                  end try
                end tell
                error "type failed: AX value and keystroke both failed"
                """;
    }

    static DomNode capture(String windowName) {
        requireMac();
        NativeDesktop.CommandResult result = osascript(captureScript(), Map.of());
        if (result.timedOut()) throw new CdpException("AX unavailable: osascript timed out");
        failIfDenied(result, "AX unavailable: ");
        return NativeDesktop.parseLines(result.output(), "ax");
    }

    static void click(DomNode node) {
        requireMac();
        String path = node.attributes.getOrDefault("ax-path", "");
        if (path.isBlank()) throw new CdpException("click failed: live desktop node is missing ax-path");
        NativeDesktop.CommandResult result = osascript(clickScript(), Map.of("DESKTOP_AX_PATH", path));
        if (result.timedOut()) throw new CdpException("click failed: osascript timed out");
        failIfDenied(result, "click failed: ");
    }

    static void type(DomNode node, String text, boolean append) {
        requireMac();
        String path = node.attributes.getOrDefault("ax-path", "");
        if (path.isBlank()) throw new CdpException("type failed: live desktop node is missing ax-path");
        NativeDesktop.CommandResult result = osascript(typeScript(), Map.of(
                "DESKTOP_AX_PATH", path,
                "DESKTOP_TEXT", text == null ? "" : text,
                "DESKTOP_APPEND", append ? "true" : "false"
        ));
        if (result.timedOut()) throw new CdpException("type failed: osascript timed out");
        failIfDenied(result, "type failed: ");
    }

    private static void failIfDenied(NativeDesktop.CommandResult result, String prefix) {
        if (!result.timedOut() && result.exit() == 0) return;
        String text = result.output() == null ? "" : result.output().toLowerCase(Locale.ROOT);
        if (text.contains("not allowed") || text.contains("assistive") || text.contains("-25211") || text.contains("1002")) {
            throw new CdpException("AX unavailable: accessibility permission denied (" + NativeDesktop.oneLine(result.output()) + ")");
        }
        throw new CdpException(prefix + NativeDesktop.oneLine(result.output()));
    }

    private static NativeDesktop.CommandResult osascript(String source, Map<String, String> env) {
        try {
            java.nio.file.Path file = Files.createTempFile("domengine-ax", ".applescript");
            Files.writeString(file, source);
            try {
                return NativeDesktop.run(java.util.List.of("osascript", file.toString()), env);
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (CdpException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CdpException("AX unavailable: " + failure.getMessage());
        }
    }
}
