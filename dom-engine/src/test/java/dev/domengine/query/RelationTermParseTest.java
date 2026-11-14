package dev.domengine.query;

import dev.domengine.cdp.CdpException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationTermParseTest {
    @Test
    void filterForms() {
        RelationTerm near = RelationTerm.parse("near(role=button)");
        assertEquals(RelationTerm.Relation.NEAR, near.relation());
        assertFalse(near.negated());
        assertEquals("role=button", near.anchor());
        assertEquals(1.0, near.weight(), 0.0);

        RelationTerm notNear = RelationTerm.parse("not-near(text=\"Cancel\")");
        assertEquals(RelationTerm.Relation.NEAR, notNear.relation());
        assertTrue(notNear.negated());
        assertEquals("text=\"Cancel\"", notNear.anchor());

        RelationTerm notAbove = RelationTerm.parse("NOT above(css=a)");
        assertEquals(RelationTerm.Relation.ABOVE, notAbove.relation());
        assertTrue(notAbove.negated());
        assertEquals("css=a", notAbove.anchor());
        assertEquals("not-above(css=a)", notAbove.canonical());

        RelationTerm inside = RelationTerm.parse("inside@0.5(role=dialog)");
        assertEquals(RelationTerm.Relation.INSIDE, inside.relation());
        assertFalse(inside.negated());
        assertEquals(0.5, inside.weight(), 0.0);
        assertEquals("role=dialog", inside.anchor());

        RelationTerm left = RelationTerm.parse("left(css=#x)");
        assertEquals(RelationTerm.Relation.LEFT_OF, left.relation());
        assertEquals("css=#x", left.anchor());
        assertEquals("left-of(css=#x)", left.canonical());

        RelationTerm complex = RelationTerm.parse(
                "before(css=#a >> (role=button AND text=\"Save (now)\") >> css=span)");
        assertEquals(RelationTerm.Relation.BEFORE, complex.relation());
        assertEquals("css=#a >> (role=button AND text=\"Save (now)\") >> css=span", complex.anchor());
        assertEquals(complex, RelationTerm.parse(complex.canonical()));

        RelationTerm folded = RelationTerm.parse("near(role=button and text=\"Save\")");
        assertEquals("role=button AND text=\"Save\"", folded.anchor());
    }

    @Test
    void stageFormsThroughParseAll() {
        List<RelationTerm> near = RelationTerm.parseAll("css=#a >> near=input");
        assertEquals(1, near.size());
        assertEquals(RelationTerm.Relation.NEAR, near.get(0).relation());
        assertFalse(near.get(0).negated());
        assertEquals("css=#a", near.get(0).anchor());
        assertEquals(1.0, near.get(0).weight(), 0.0);

        List<RelationTerm> negated = RelationTerm.parseAll("css=#a >> not-near=div");
        assertEquals(1, negated.size());
        assertTrue(negated.get(0).negated());
        assertEquals(RelationTerm.Relation.NEAR, negated.get(0).relation());
        assertEquals("css=#a", negated.get(0).anchor());

        List<RelationTerm> grouped = RelationTerm.parseAll("role=form >> (near=input AND NOT below=label)");
        assertEquals(2, grouped.size());
        assertEquals(RelationTerm.Relation.NEAR, grouped.get(0).relation());
        assertFalse(grouped.get(0).negated());
        assertEquals("role=form", grouped.get(0).anchor());
        assertEquals(RelationTerm.Relation.BELOW, grouped.get(1).relation());
        assertTrue(grouped.get(1).negated());
        assertEquals("role=form", grouped.get(1).anchor());

        List<RelationTerm> first = RelationTerm.parseAll("near=50");
        assertEquals(1, first.size());
        assertEquals("", first.get(0).anchor());
        assertEquals(RelationTerm.Relation.NEAR, first.get(0).relation());

        List<RelationTerm> nested = RelationTerm.parseAll("css=#a >> (css=#b >> near=input)");
        assertEquals(1, nested.size());
        assertEquals("css=#a >> css=#b", nested.get(0).anchor());

        assertTrue(RelationTerm.parseAll("css=button AND text=Save").isEmpty());

        List<RelationTerm> root = RelationTerm.parseAll("near()");
        assertEquals(1, root.size());
        assertEquals(RelationTerm.Relation.NEAR, root.get(0).relation());
        assertEquals("", root.get(0).anchor());
        assertFalse(root.get(0).negated());
    }

    @Test
    void invalidRelations() {
        assertInvalid("near(");
        assertInvalid("NOT not-near(x)");
        assertInvalid("near@-1(x)");
        assertInvalid("nearby(x)");
        assertInvalid("near(x) trailing");
    }

    @Test
    void productionParseUnchanged() {
        String[] pipelines = {
                "css=button AND text=\"Submit\"",
                "css=button OR text=\"Submit\"",
                "NOT css=button",
                "css=#panel >> NOT css=button",
                "(css=button OR css=span) AND text=\"Submit\"",
                "css=button OR (css=span AND text=\"Submit\")",
                "css=#panel >> (css=button AND text=\"Submit\")",
                "(css=button AND text=\"Submit\") >> near=50",
                "css=#go",
                "text=\"AND OR NOT\"",
                "css=#above >> below=#under",
                "css=#above >> near=#under",
                "(css=button AND text=\"Submit\")",
                "css=#panel >> (css=button OR css=span)",
                "css=#anchor >> above=div",
                "css=#anchor >> left-of=div",
                "css=#anchor >> right-of=div",
                "css=#anchor >> below=#down",
                "css=#anchor >> above=*",
                "css=#anchor >> above=#up-near,#up-far",
                "css=#anchor >> above=#up-near",
                "css=#anchor >> left-of=#left"
        };
        for (String pipeline : pipelines) {
            String once = Expressions.render(Expressions.parse(pipeline));
            String twice = Expressions.render(Expressions.parse(once));
            assertEquals(once, twice, pipeline);
            assertEquals(pipeline, once, pipeline);
        }
        String alias = "css=#anchor >> right=#right";
        String canonical = Expressions.render(Expressions.parse(alias));
        assertEquals("css=#anchor >> right-of=#right", canonical);
        assertEquals(canonical, Expressions.render(Expressions.parse(canonical)));

        CdpException mixed = assertThrows(CdpException.class,
                () -> Expressions.parse("css=button AND text=\"Submit\" OR css=span"));
        assertTrue(mixed.getMessage().contains("parentheses"), mixed.getMessage());

        assertEquals("css=#a >> not-near=div", Expressions.render(Expressions.parse("css=#a >> not-near=div")));
        assertEquals("near(role=button)", Expressions.render(Expressions.parse("near(role=button)")));
    }

    private static void assertInvalid(String text) {
        CdpException parsed = assertThrows(CdpException.class, () -> RelationTerm.parse(text));
        assertEquals("invalid relation: " + text, parsed.getMessage());
        CdpException all = assertThrows(CdpException.class, () -> RelationTerm.parseAll(text));
        assertEquals("invalid relation: " + text, all.getMessage());
    }
}
