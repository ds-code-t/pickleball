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
                property nodeBudget : 250

                on clean(value)
                  set value to value as text
                  set AppleScript's text item delimiters to {tab, return, linefeed}
                  set parts to text items of value
                  set AppleScript's text item delimiters to " "
                  set value to parts as text
                  set AppleScript's text item delimiters to ""
                  return value
                end clean

                on parentOf(pathText)
                  set AppleScript's text item delimiters to "/"
                  set parts to text items of pathText
                  if (count of parts) < 2 then
                    set AppleScript's text item delimiters to ""
                    return ""
                  end if
                  set parentPath to (items 1 thru -2 of parts) as text
                  set AppleScript's text item delimiters to ""
                  return parentPath
                end parentOf

                on dump(el, depth, pathText)
                  if depth > 12 or nodeBudget < 1 then
                    return "TRUNCATED" & linefeed
                  end if
                  set nodeBudget to nodeBudget - 1
                  set r to "unknown"
                  set n to ""
                  set x to 0
                  set y to 0
                  set w to 0
                  set h to 0
                  tell application "System Events"
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
                    set states to ""
                    try
                      set enabledFlag to enabled of el
                      if enabledFlag is false then set states to "disabled"
                    end try
                    try
                      if focused of el is true then
                        if states is "" then
                          set states to "focused"
                        else
                          set states to states & ",focused"
                        end if
                      end if
                    end try
                    try
                      set titleEl to value of attribute "AXTitleUIElement" of el
                      set titlePath to ""
                      try
                        set ownerEl to value of attribute "AXParent" of el
                        set sibs to UI elements of ownerEl
                        set sibIndex to 1
                        repeat with sib in sibs
                          if sib is titleEl then
                            set base to my parentOf(pathText)
                            if base is "" then
                              set titlePath to sibIndex as text
                            else
                              set titlePath to base & "/" & sibIndex
                            end if
                          end if
                          set sibIndex to sibIndex + 1
                        end repeat
                      end try
                      if titlePath is not "" then set lineText to lineText & "REL" & tab & pathText & tab & "labeled-by" & tab & titlePath & linefeed
                    end try
                    try
                      set checkedFlag to value of attribute "AXValue" of el
                      if r is "AXCheckBox" or r is "AXRadioButton" then
                        if checkedFlag is 1 or checkedFlag is true then set states to states & ",checked"
                      end if
                    end try
                    try
                      if value of attribute "AXExpanded" of el is true then set states to states & ",expanded"
                    end try
                    try
                      if value of attribute "AXOffScreen" of el is true then set states to states & ",offscreen"
                    end try
                    set lineText to lineText & "STATE" & tab & pathText & tab & states & linefeed
                    set moreText to ""
                    -- A closed menu has no size. Its items are not on screen, and walking every
                    -- menu item of every menu bar costs thousands of Apple Events.
                    set walkKids to true
                    if (r is "AXMenu" or r is "AXMenuItem") and (w = 0 or h = 0) then set walkKids to false
                    if walkKids then
                      try
                        set kids to UI elements of el
                        set indexNum to 1
                        repeat with kid in kids
                          set childPath to pathText & "/" & indexNum
                          set moreText to moreText & my dump(kid, depth + 1, childPath)
                          set indexNum to indexNum + 1
                        end repeat
                      end try
                    end if
                  end tell
                  return lineText & moreText
                end dump

                try
                  set nodeBudget to (system attribute "DESKTOP_AX_MAX_NODES") as integer
                end try
                set output to "NODE" & tab & "0" & tab & "desktop" & tab & "0" & tab & "0" & tab & "0" & tab & "0" & tab & "" & tab & "Desktop" & linefeed
                tell application "System Events"
                  set indexNum to 1
                  repeat with proc in (processes whose background only is false)
                    try
                      set output to output & my dump(proc, 1, indexNum as text)
                    end try
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
                  tell application "System Events"
                    repeat with indexText in indexes
                      set n to indexText as integer
                      set kids to UI elements of currentEl
                      set currentEl to item n of kids
                    end repeat
                  end tell
                  return currentEl
                end walk

                set pathText to system attribute "DESKTOP_AX_PATH"
                tell application "System Events"
                  set AppleScript's text item delimiters to "/"
                  set indexes to text items of pathText
                  set AppleScript's text item delimiters to ""
                  if (count of indexes) is 0 then error "click failed: missing ax-path"
                  set proc to item ((item 1 of indexes) as integer) of (processes whose background only is false)
                  if (count of indexes) is 1 then
                    set targetEl to proc
                  else
                    set restPath to ""
                    repeat with i from 2 to (count of indexes)
                      if restPath is not "" then set restPath to restPath & "/"
                      set restPath to restPath & (item i of indexes)
                    end repeat
                    set targetEl to my walk(proc, restPath)
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
                  tell application "System Events"
                    repeat with indexText in indexes
                      set n to indexText as integer
                      set kids to UI elements of currentEl
                      set currentEl to item n of kids
                    end repeat
                  end tell
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
                  set proc to item ((item 1 of indexes) as integer) of (processes whose background only is false)
                  if (count of indexes) is 1 then
                    set targetEl to proc
                  else
                    set restPath to ""
                    repeat with i from 2 to (count of indexes)
                      if restPath is not "" then set restPath to restPath & "/"
                      set restPath to restPath & (item i of indexes)
                    end repeat
                    set targetEl to my walk(proc, restPath)
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
        NativeDesktop.CommandResult result = osascript(captureScript(),
                Map.of("DESKTOP_AX_MAX_NODES", Integer.toString(MAX_NODES)), CAPTURE_TIMEOUT_SECONDS);
        // Only a denied Accessibility permission means AX is unavailable. A script error or a
        // timeout is a capture failure, so it is not mistaken for a missing platform.
        if (result.timedOut()) throw new CdpException("AX capture failed: osascript timed out after " + CAPTURE_TIMEOUT_SECONDS + " s");
        failIfDenied(result, "AX capture failed: ");
        DomNode root = NativeDesktop.parseLines(result.output(), "ax");
        DesktopAnnotations.apply(root, result.output(), "ax-path");
        return root;
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

    /** A full System Events walk of every foreground app takes longer than one click or type. */
    static final int CAPTURE_TIMEOUT_SECONDS = 60;
    /**
     * Every AX attribute read is one Apple Event, so a full walk of every app does not finish in
     * time. The walk stops after this many nodes and marks the tree {@code walk-truncated}.
     */
    static final int MAX_NODES = 250;

    private static NativeDesktop.CommandResult osascript(String source, Map<String, String> env) {
        return osascript(source, env, NativeDesktop.DEFAULT_TIMEOUT_SECONDS);
    }

    private static NativeDesktop.CommandResult osascript(String source, Map<String, String> env, int timeoutSeconds) {
        try {
            java.nio.file.Path file = Files.createTempFile("domengine-ax", ".applescript");
            Files.writeString(file, source);
            try {
                return NativeDesktop.run(java.util.List.of("osascript", file.toString()), env, timeoutSeconds);
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
