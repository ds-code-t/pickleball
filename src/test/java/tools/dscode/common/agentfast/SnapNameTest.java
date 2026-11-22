package tools.dscode.common.agentfast;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Snap and find share one name. No browser. */
class SnapNameTest {
    @Test
    void snapRowsUseVisibleLabelAltAndAccessibleNames() {
        DomNode link = element("a");
        DomNode docs = text("Docs");
        link.children.add(docs);
        docs.parent = link;
        assertEquals("link", TargetVerb.role(link));
        assertEquals("Docs", TargetVerb.name(link));

        DomNode button = element("button");
        button.accessibleName = "Save";
        assertEquals("button", TargetVerb.role(button));
        assertEquals("Save", TargetVerb.name(button));

        DomNode logo = element("img");
        logo.attributes.put("alt", "Logo");
        assertEquals("Logo", TargetVerb.name(logo));

        DomNode titled = element("a");
        titled.attributes.put("title", "Account");
        assertEquals("Account", TargetVerb.name(titled));

        DomNode city = element("input");
        city.attributes.put("id", "city");
        city.attributes.put("placeholder", "Town");
        DomNode root = element("form");
        DomNode label = element("label");
        label.attributes.put("for", "city");
        DomNode labelText = text("City");
        label.children.add(labelText);
        labelText.parent = label;
        root.children.add(label);
        root.children.add(city);
        label.parent = root;
        city.parent = root;
        assertEquals("City", TargetVerb.name(city));
        assertEquals("textbox", TargetVerb.role(city));

        DomNode search = element("input");
        search.attributes.put("placeholder", "Find");
        assertEquals("Find", TargetVerb.name(search));

        DomNode submit = element("input");
        submit.attributes.put("type", "submit");
        submit.attributes.put("value", "Go");
        assertEquals("Go", TargetVerb.name(submit));
        assertEquals("button", TargetVerb.role(submit));

        DomNode named = element("button");
        named.attributes.put("aria-labelledby", "save-label");
        DomNode host = element("div");
        DomNode saveLabel = element("span");
        saveLabel.attributes.put("id", "save-label");
        DomNode saveText = text("Save label");
        saveLabel.children.add(saveText);
        saveText.parent = saveLabel;
        host.children.add(saveLabel);
        host.children.add(named);
        saveLabel.parent = host;
        named.parent = host;
        assertEquals("Save label", TargetVerb.name(named));

        String longText = "N".repeat(120);
        DomNode clipped = element("button");
        DomNode longChild = text(longText);
        clipped.children.add(longChild);
        longChild.parent = clipped;
        assertEquals(80, TargetVerb.name(clipped).length());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", TargetVerb.role(link));
        row.put("name", TargetVerb.name(link));
        row.put("id", "s1.h9");
        row.put("ref", "ref:1:9");
        row.put("visible", true);
        row.put("enabled", true);
        row.put("interactive", true);
        row.put("frame", "top");
        String line = TargetVerb.snapLines(List.of(row), Map.of()).get(1);
        assertTrue(line.startsWith("- link \"Docs\""), line);
        assertTrue(line.contains("id=s1.h9"), line);

        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("role", TargetVerb.role(button));
        saved.put("name", TargetVerb.name(button));
        String savedLine = TargetVerb.snapLines(List.of(saved), Map.of()).get(1);
        assertTrue(savedLine.contains("- button \"Save\""), savedLine);
    }

    private static DomNode element(String tag) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.nodeName = tag.toUpperCase();
        node.localName = tag;
        return node;
    }

    private static DomNode text(String value) {
        DomNode node = new DomNode();
        node.nodeType = 3;
        node.nodeValue = value;
        return node;
    }
}
