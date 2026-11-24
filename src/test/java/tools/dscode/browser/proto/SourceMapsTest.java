package tools.dscode.browser.proto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourceMapsTest {
    @Test
    void vlqDecodesSignedValues() {
        assertArrayEquals(new int[]{0, 0, 9, 0, 0}, SourceMaps.vlq("AASAA"));
        assertArrayEquals(new int[]{-1}, SourceMaps.vlq("D"));
        assertArrayEquals(new int[]{16}, SourceMaps.vlq("gB"));
    }

    @Test
    void resolvesAGeneratedPositionThroughAFetchedMap() {
        Map<String, String> files = Map.of(
                "http://x/a.js", "f();\n//# sourceMappingURL=a.js.map",
                "http://x/a.js.map", "{\"version\":3,\"sources\":[\"a.ts\"],\"names\":[\"f\",\"g\"],\"mappings\":\"AAAAA,MACCC;AAEA\"}");
        SourceMaps maps = new SourceMaps(files::get);
        SourceMaps.Position first = maps.resolve("http://x/a.js", 1, 2);
        assertEquals("http://x/a.ts", first.source());
        assertEquals(1, first.line());
        assertEquals("f", first.name());
        SourceMaps.Position later = maps.resolve("http://x/a.js", 1, 8);
        assertEquals(2, later.line());
        assertEquals(2, later.column());
        assertEquals("g", later.name());
        assertEquals(4, maps.resolve("http://x/a.js", 2, 1).line());
        assertNull(new SourceMaps(u -> null).resolve("http://x/none.js", 1, 1));
    }
}
