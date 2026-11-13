package tools.dscode.browser;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceAssetsTest {
    @Test
    void loadsEachAssetAtItsHash() throws Exception {
        assertEquals("50f2de7998d2f726fe0b1f5c52205989fbec5e5354742e651803201c63b78b07", sha(SurfaceAssets.script("live-helper")));
        assertEquals("5d6b24e641d70c4c252804b3d0bfae48f6170037434b6d43b2b8e2650c0821a7", sha(SurfaceAssets.script("bridge-mark")));
        assertEquals("ab8f90e2e4d04214af67e933ab190a0bfab7c89e57ba6cdfe5e10bd3eec6e29a", sha(SurfaceAssets.script("frame-mark")));
        assertEquals("afc0169aba6b94679a425b8b8868193583b8813e8bcf52fadf089389d7999c88", sha(SurfaceAssets.script("frame-restore")));
        assertEquals("405c93b5a4482851c1a77962c8157aa3564d5cf1eb805a779e624dcff023273f", sha(SurfaceAssets.html("html")));
        assertEquals("6eb22bb91aa747f562d04c88609a3c5750daf70c901063fa4c82efafa528c2ff", sha(SurfaceAssets.css("html")));
        assertEquals("9f3408c537a2e78487851a69fedf000ee4f3f9549d23f2fbc3ce4b5589f023d2", sha(SurfaceAssets.js("html")));
        assertEquals("e0dd572fb8aaf3f0ad821aa7c5887831712983321f9f677f14ba96355a42035f", sha(SurfaceAssets.html("overlay")));
        assertEquals("6e058d1ce20346fd16be1389948cfdc7a0e298f88087f7b7c73ddaae24a72a34", sha(SurfaceAssets.css("overlay")));
        assertEquals("9f3408c537a2e78487851a69fedf000ee4f3f9549d23f2fbc3ce4b5589f023d2", sha(SurfaceAssets.js("overlay")));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha(SurfaceAssets.html("highlight")));
        assertEquals("134ef85a646321fad03b805b81566356724f8a8da26b8e0b9e5be36943ff92cd", sha(SurfaceAssets.css("highlight")));
        assertEquals("085febe43a03b7a1991f3f8f950306413ce85d98c1cffb727b7cafc51117568c", sha(SurfaceAssets.js("highlight")));
    }

    @Test
    void oneCopySharedByPageAndPopupKinds() throws Exception {
        String helper = SurfaceAssets.script("live-helper");
        String events = SurfaceAssets.js("overlay");
        assertTrue(helper.contains(events));
        assertEquals(events, SurfaceAssets.js("html"));
        assertEquals(events, SurfaceAssets.js("instructions"));
        assertEquals(events, SurfaceAssets.js("diagram"));
        assertEquals(SurfaceAssets.html("html"), SurfaceAssets.html("instructions"));
        assertEquals(SurfaceAssets.html("html"), SurfaceAssets.html("HTML"));
        assertEquals(SurfaceAssets.css("html"), SurfaceAssets.css("diagram"));
        assertEquals(SurfaceAssets.html("overlay"), SurfaceAssets.html(" overlay "));

        String css = SurfaceAssets.css("overlay");
        assertTrue(css.contains("position: fixed"));
        assertTrue(css.contains("z-index: 2147483646"));
        assertTrue(css.contains("background: #fffef2"));
        assertTrue(css.contains("border: 2px solid #222"));
        assertTrue(css.contains("padding: 8px"));
        assertTrue(css.contains("max-width: 280px"));
        assertTrue(helper.contains("#fffef2"));
        assertTrue(helper.contains("2147483646"));
        assertTrue(helper.contains("2px solid #222"));
        assertTrue(helper.contains("280px"));
        assertFalse(css.contains("left:"));

        assertEquals("agent-injected HTML; a person did not author this node", SurfaceAssets.sentence("html"));
        assertEquals("agent-injected HTML; a person did not author this node", SurfaceAssets.sentence("diagram"));
        assertEquals("agent-injected overlay; a person did not author this UI", SurfaceAssets.sentence("overlay"));
        assertEquals("agent style; a person did not author this highlight", SurfaceAssets.sentence("highlight"));
        assertEquals(SurfaceAssets.sentence("highlight"), SurfaceAssets.sentence("style"));
        assertSame(helper, SurfaceAssets.script("LIVE-HELPER"));

        assertEquals(helper, page("liveHelperSource"));
        assertEquals(SurfaceAssets.script("bridge-mark"), page("bridgeMarkSource"));
        assertEquals(SurfaceAssets.script("frame-mark"), page("frameMarkSource"));
        assertEquals(SurfaceAssets.script("frame-restore"), page("frameRestoreSource"));
        assertEquals(SurfaceAssets.sentence("overlay"), page("sentence", "overlay"));
        assertEquals(SurfaceAssets.HTML_SENTENCE, page("sentence", "instructions"));

        Path source = Path.of("src/main/java/tools/dscode/common/agentcontrol/PageSurfaces.java");
        assertTrue(Files.isRegularFile(source), "PageSurfaces.java must be readable from the project root");
        String java = Files.readString(source);
        assertFalse(java.contains("var spec = arguments[0]"));
        assertFalse(java.contains("data-pkb-bridge', token"));

        assertThrows(IllegalArgumentException.class, () -> SurfaceAssets.html("chat"));
        assertThrows(IllegalArgumentException.class, () -> SurfaceAssets.html(null));
        assertThrows(IllegalArgumentException.class, () -> SurfaceAssets.script("overlay"));
    }

    private static String page(String method, String... args) throws Exception {
        Class<?> type = Class.forName("tools.dscode.common.agentcontrol.PageSurfaces");
        if (args.length == 0) {
            Method declared = type.getDeclaredMethod(method);
            declared.setAccessible(true);
            return (String) declared.invoke(null);
        }
        Method declared = type.getDeclaredMethod(method, String.class);
        declared.setAccessible(true);
        return (String) declared.invoke(null, args[0]);
    }

    private static String sha(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
