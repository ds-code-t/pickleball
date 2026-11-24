package dev.domengine.desktop;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** D10: accelerator on and off give identical results on recorded page sources. */
class DesktopXPathAccelTest {
    @Test
    void acceleratorOnAndOffAgreeOnRecordedSources() throws Exception {
        for (String[] fixture : new String[][]{{"flaui-notepad.xml", "flaui"}, {"mac2-textedit.xml", "mac2"}}) {
            String xml = Files.readString(Path.of("src/test/resources/desktop-drivers/" + fixture[0]));
            DomNode root = PageSourceTree.parse(xml, fixture[1]);
            Function<String, List<String>> driver = recorded(xml);
            for (String[] q : new String[][]{{"button", "Save"}, {"", "Save"}, {"window", "Untitled - Notepad"}, {"button", "Close"}, {"button", "Missing"}, {"textbox", "Save"}}) {
                List<DomNode> off = DesktopXPathAccel.canonical(root, q[0], q[1]);
                List<DomNode> on = DesktopXPathAccel.find(root, fixture[1], q[0], q[1], driver);
                assertEquals(off, on, fixture[0] + " " + q[0] + "/" + q[1]);
            }
        }
    }

    @Test
    void aDriverThatMissesOrFailsDoesNotChangeTheAnswer() throws Exception {
        DomNode root = PageSourceTree.parse(Files.readString(Path.of("src/test/resources/desktop-drivers/flaui-notepad.xml")), "flaui");
        List<DomNode> expected = DesktopXPathAccel.canonical(root, "button", "Save");
        assertEquals(1, expected.size());
        assertEquals(expected, DesktopXPathAccel.find(root, "flaui", "button", "Save", x -> List.of()));
        assertEquals(expected, DesktopXPathAccel.find(root, "flaui", "button", "Save", x -> List.of("/Window[1]/Document[1]")),
                "an unconfirmed candidate is dropped and the canonical match still comes back");
        assertEquals(expected, DesktopXPathAccel.find(root, "flaui", "button", "Save", x -> {
            throw new IllegalStateException("driver down");
        }));
        assertEquals("//*[@Name='Save']", DesktopXPathAccel.driverXPath("flaui", "Save"));
        assertEquals("//*[@title=\"it's\" or @label=\"it's\"]", DesktopXPathAccel.driverXPath("mac2", "it's"));
        assertNull(DesktopXPathAccel.driverXPath("flaui", "a'b\"c"));
        assertFalse(DesktopXPathAccel.enabled(Map.of()), "default off");
        assertTrue(DesktopXPathAccel.enabled(Map.of("pkb_desktop_xpath_accel", "on")));
    }

    @Test
    void liveAcceleratorOnlyWithARunningDriver() {
        GridDrivers.Choice choice = GridDrivers.select(Map.of("pkb_desktop_driver", "auto"), NativeDesktop.family(), GridDrivers.httpProbe());
        assumeTrue(choice.grid(), "skipped: no desktop driver is running on this machine");
    }

    /** Evaluates the driver XPath over the recorded source and returns positional paths, as a driver would find them. */
    private static Function<String, List<String>> recorded(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml)));
        return expression -> {
            try {
                NodeList hits = (NodeList) XPathFactory.newInstance().newXPath().evaluate(expression, document, XPathConstants.NODESET);
                List<String> out = new ArrayList<>();
                for (int i = 0; i < hits.getLength(); i++) out.add(path((Element) hits.item(i)));
                return out;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
    }

    private static String path(Element element) {
        StringBuilder path = new StringBuilder();
        for (Node at = element; at instanceof Element e; at = e.getParentNode()) {
            int index = 1;
            for (Node sib = e.getPreviousSibling(); sib != null; sib = sib.getPreviousSibling()) {
                if (sib instanceof Element s && s.getTagName().equals(e.getTagName())) index++;
            }
            path.insert(0, "/" + e.getTagName() + "[" + index + "]");
        }
        return path.toString();
    }
}
