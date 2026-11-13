package dev.domengine.affinity;

import dev.domengine.query.HtmlTree;
import dev.domengine.query.RelationTerm;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AffinityScoreTest {
    @Test
    void fixtureBindsEveryControlToItsLabel() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/fixtures/affinity-form.html"));
        DomNode root = HtmlTree.parse(html);
        AffinityScorer.Bindings bindings = AffinityScorer.bind(root);
        assertLabel(bindings, "left", "First");
        assertLabel(bindings, "above", "Above");
        assertLabel(bindings, "wrapped", "Wrapped");
        assertLabel(bindings, "coded", "Code");
        assertLabel(bindings, "notes", "Notes");
        assertLabel(bindings, "city", "City");
        assertLabel(bindings, "state", "State");
        assertTrue(bindings.unlabeled().isEmpty(), bindings.unlabeled().toString());
    }

    @Test
    void explicitEvidenceScoresOneAndPartsSumToTotal() {
        DomNode form = parent();
        DomNode label = box(form, "label", "Code", 0, 0, 40, 16);
        label.attributes.put("for", "coded");
        DomNode input = box(form, "input", "", 400, 200, 80, 20);
        input.localName = "input";
        input.attributes.put("id", "coded");
        form.linkParents();
        AffinityScore score = AffinityScorer.score(label, input, List.of());
        assertEquals(1.0, score.total(), 1e-9);
        assertEquals(1.0, score.parts().get("explicit"), 1e-9);
        assertEquals(score.total(), score.sumOfParts(), 1e-9);
    }

    @Test
    void negatedInsideDialogLowersTheScoreByHalfTheWeight() {
        DomNode form = parent();
        DomNode label = box(form, "label", "Name", 0, 0, 40, 16);
        DomNode input = box(form, "input", "", 50, 0, 80, 16);
        input.localName = "input";
        input.attributes.put("id", "name");
        form.linkParents();
        double base = AffinityScorer.scoreValue(label, input, List.of());
        AffinityScore penalized = AffinityScorer.score(label, input, List.of(
                new RelationTerm(RelationTerm.Relation.INSIDE, true, "dialog", 1.0)
        ));
        assertTrue(base >= 0.5, "base " + base);
        assertEquals(0.5, base - penalized.total(), 1e-9);
        assertTrue(penalized.reasons().contains("negated inside satisfied"), penalized.reasons().toString());
        assertEquals(penalized.total(), penalized.sumOfParts(), 1e-9);
    }

    @Test
    void labelRightOfACheckboxScoresFullAlignment() {
        DomNode form = parent();
        DomNode box = box(form, "input", "", 10, 10, 16, 16);
        box.localName = "input";
        box.attributes.put("type", "checkbox");
        DomNode label = box(form, "label", "Agree", 32, 8, 60, 18);
        form.linkParents();
        AffinityScore score = AffinityScorer.score(label, box, List.of(), AffinityWeights.defaults());
        double expected = AffinityWeights.defaults().normalized().alignment();
        assertEquals(expected, score.parts().get("alignment"), 1e-9);
    }

    private static void assertLabel(AffinityScorer.Bindings bindings, String id, String labelText) {
        AffinityScorer.Binding binding = bindings.forControl(id);
        assertNotNull(binding, id);
        String text = AffinityScorer.textOf(binding.label());
        assertTrue(text.toLowerCase().contains(labelText.toLowerCase()), id + " -> " + text);
    }

    private static DomNode parent() {
        DomNode form = new DomNode();
        form.nodeType = 1;
        form.localName = "form";
        form.nodeName = "FORM";
        form.backendNodeId = 1;
        return form;
    }

    private static DomNode box(DomNode parent, String tag, String text, double x, double y, double w, double h) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.localName = tag;
        node.nodeName = tag.toUpperCase();
        node.backendNodeId = parent.children.size() + 2L;
        node.x = x;
        node.y = y;
        node.width = w;
        node.height = h;
        node.hasBox = true;
        if (!text.isBlank()) {
            DomNode word = new DomNode();
            word.nodeType = 3;
            word.nodeName = "#text";
            word.nodeValue = text;
            word.backendNodeId = 100 + parent.children.size();
            node.children.add(word);
        }
        parent.children.add(node);
        return node;
    }
}
