package dev.domengine.query;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationTermTest {
    @Test
    void enumOrderEnginesAndGeometry() {
        RelationTerm.Relation[] order = RelationTerm.Relation.values();
        assertArrayEquals(new RelationTerm.Relation[]{
                RelationTerm.Relation.NEAR,
                RelationTerm.Relation.ABOVE,
                RelationTerm.Relation.BELOW,
                RelationTerm.Relation.LEFT_OF,
                RelationTerm.Relation.RIGHT_OF,
                RelationTerm.Relation.INSIDE,
                RelationTerm.Relation.AFTER,
                RelationTerm.Relation.BEFORE
        }, order);
        assertEquals("near", RelationTerm.Relation.NEAR.engine());
        assertEquals("above", RelationTerm.Relation.ABOVE.engine());
        assertEquals("below", RelationTerm.Relation.BELOW.engine());
        assertEquals("left-of", RelationTerm.Relation.LEFT_OF.engine());
        assertEquals("right-of", RelationTerm.Relation.RIGHT_OF.engine());
        assertEquals("inside", RelationTerm.Relation.INSIDE.engine());
        assertEquals("after", RelationTerm.Relation.AFTER.engine());
        assertEquals("before", RelationTerm.Relation.BEFORE.engine());
        for (RelationTerm.Relation relation : order) {
            boolean geometric = relation != RelationTerm.Relation.AFTER && relation != RelationTerm.Relation.BEFORE;
            assertEquals(geometric, relation.geometric(), relation.name());
        }
    }

    @Test
    void fromEngineAcceptsAliasesAndCaseButNotANegatedPrefix() {
        assertEquals(Optional.of(RelationTerm.Relation.LEFT_OF), RelationTerm.Relation.fromEngine("left"));
        assertEquals(Optional.of(RelationTerm.Relation.LEFT_OF), RelationTerm.Relation.fromEngine("LEFT-OF"));
        assertEquals(Optional.of(RelationTerm.Relation.RIGHT_OF), RelationTerm.Relation.fromEngine("right"));
        assertEquals(Optional.of(RelationTerm.Relation.RIGHT_OF), RelationTerm.Relation.fromEngine("Right-Of"));
        assertEquals(Optional.of(RelationTerm.Relation.NEAR), RelationTerm.Relation.fromEngine("NeAr"));
        assertEquals(Optional.of(RelationTerm.Relation.INSIDE), RelationTerm.Relation.fromEngine(" inside "));
        assertEquals(Optional.empty(), RelationTerm.Relation.fromEngine("not-near"));
        assertEquals(Optional.empty(), RelationTerm.Relation.fromEngine("NOT-LEFT"));
        assertEquals(Optional.empty(), RelationTerm.Relation.fromEngine("nearby"));
        assertEquals(Optional.empty(), RelationTerm.Relation.fromEngine(null));
        assertEquals(Optional.empty(), RelationTerm.Relation.fromEngine(""));
    }

    @Test
    void compactConstructorValidatesRelationAnchorAndWeight() {
        NullPointerException npe = assertThrows(NullPointerException.class,
                () -> new RelationTerm(null, false, "css=#x"));
        assertEquals("relation", npe.getMessage());

        assertEquals("", new RelationTerm(RelationTerm.Relation.NEAR, false, null).anchor());
        assertEquals("css=#x", new RelationTerm(RelationTerm.Relation.NEAR, false, "  css=#x  ").anchor());
        assertEquals(RelationTerm.DEFAULT_WEIGHT, new RelationTerm(RelationTerm.Relation.BELOW, true, "css=#x").weight(), 0.0);
        assertEquals(1.0, RelationTerm.DEFAULT_WEIGHT, 0.0);

        assertWeightRejected(Double.NaN, "invalid relation weight: NaN");
        assertWeightRejected(Double.POSITIVE_INFINITY, "invalid relation weight: Infinity");
        assertWeightRejected(Double.NEGATIVE_INFINITY, "invalid relation weight: -Infinity");
        assertWeightRejected(-0.1, "invalid relation weight: -0.1");
        assertWeightRejected(-1.0, "invalid relation weight: -1.0");
    }

    @Test
    void negateFlipsOnlyTheFlagAndCanonicalOmitsUnitWeight() {
        RelationTerm term = new RelationTerm(RelationTerm.Relation.ABOVE, false, "css=#x", 2.0);
        RelationTerm negated = term.negate();
        assertTrue(negated.negated());
        assertEquals(term.relation(), negated.relation());
        assertEquals(term.anchor(), negated.anchor());
        assertEquals(term.weight(), negated.weight(), 0.0);
        assertFalse(negated.negate().negated());
        assertEquals(term, negated.negate());

        assertEquals("near(css=#x)", new RelationTerm(RelationTerm.Relation.NEAR, false, "css=#x").canonical());
        assertEquals("not-left-of@0.5(css=#x)", new RelationTerm(RelationTerm.Relation.LEFT_OF, true, "css=#x", 0.5).canonical());
        assertEquals("inside@2(role=dialog)", new RelationTerm(RelationTerm.Relation.INSIDE, false, "role=dialog", 2.0).canonical());
    }

    @Test
    void parseCanonicalRoundTripsEveryRelationSignAndWeight() {
        double[] weights = {1.0, 0.5, 2.0};
        for (RelationTerm.Relation relation : RelationTerm.Relation.values()) {
            for (boolean negated : new boolean[]{false, true}) {
                for (double weight : weights) {
                    RelationTerm term = new RelationTerm(relation, negated, "role=button AND text=\"Save\"", weight);
                    assertEquals(term, RelationTerm.parse(term.canonical()), term.canonical());
                }
            }
        }
        RelationTerm rooted = new RelationTerm(RelationTerm.Relation.NEAR, false, "");
        assertEquals("near()", rooted.canonical());
        assertEquals(rooted, RelationTerm.parse(rooted.canonical()));
    }

    private static void assertWeightRejected(double weight, String message) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new RelationTerm(RelationTerm.Relation.NEAR, false, "css=#x", weight));
        assertEquals(message, ex.getMessage());
    }
}
