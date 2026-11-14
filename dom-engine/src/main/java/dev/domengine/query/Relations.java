package dev.domengine.query;

import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Relative locators. Stage forms of {@code near}, {@code below}, {@code above},
 * {@code left-of} and {@code right-of} stay in {@link Engines}. This type owns
 * {@code inside}, {@code after}, {@code before}, and the filter form of every relation.
 */
final class Relations {
    private Relations() {
    }

    static Query.Hit inside(String expr, List<DomNode> anchors, DomNode root) {
        String selector = expr == null || expr.isBlank() ? "*" : expr;
        List<DomNode> candidates = Engines.css(selector, List.of(root), false).nodes;
        return geometric("inside", candidates, anchors, root, null, null);
    }

    static Query.Hit after(String expr, List<DomNode> anchors, DomNode root, String pierce) {
        return document("after", expr, anchors, root, pierce, null);
    }

    static Query.Hit before(String expr, List<DomNode> anchors, DomNode root, String pierce) {
        return document("before", expr, anchors, root, pierce, null);
    }

    static Query.Hit filter(RelationTerm term, List<DomNode> scope, DomNode root, String pierce) {
        List<DomNode> anchors = anchorNodes(term, root, pierce);
        QueryHooks.note(anchors);
        String label = label(term);
        List<String> anchorIds = ids(anchors);
        List<DomNode> candidates = new ArrayList<>();
        for (DomNode candidate : Engines.scopeElements(scope, root, pierce)) {
            if (!anchorIds.contains(candidate.id())) candidates.add(candidate);
        }
        if (anchors.isEmpty()) {
            if (term.negated()) {
                List<DomNode> kept = inDocumentOrder(candidates, root, pierce);
                log(label, 0, candidates.size(), kept.size(), space(term.relation()));
                return new Query.Hit(kept, "anchor matched 0");
            }
            log(label, 0, candidates.size(), 0, space(term.relation()));
            return new Query.Hit(List.of(), label + "(" + term.anchor() + ") anchors=0 matched 0");
        }
        List<DomNode> positive = keep(term.relation(), candidates, anchors, root, pierce);
        if (!term.negated()) {
            log(label, anchors.size(), candidates.size(), positive.size(), space(term.relation()));
            return new Query.Hit(positive, label + "(" + term.anchor() + ") anchors=" + anchors.size() + " matched " + positive.size());
        }
        List<DomNode> kept = subtract(candidates, positive, root, pierce);
        log(label, anchors.size(), candidates.size(), kept.size(), space(term.relation()));
        return new Query.Hit(kept, label + "(" + term.anchor() + ") anchors=" + anchors.size() + " matched " + kept.size());
    }

    static Query.Hit negativeStage(RelationTerm term, String expr, List<DomNode> anchors, DomNode root, String pierce) {
        List<DomNode> pool = without(stagePool(term.relation(), expr, root, pierce), anchors);
        String label = label(term);
        if (anchors == null || anchors.isEmpty()) {
            List<DomNode> kept = inDocumentOrder(pool, root, pierce);
            log(label, 0, pool.size(), kept.size(), space(term.relation()));
            return new Query.Hit(kept, "anchor matched 0");
        }
        List<DomNode> positive = positiveInPool(term.relation(), expr, pool, anchors, root, pierce);
        List<DomNode> kept = subtract(pool, positive, root, pierce);
        log(label, anchors.size(), pool.size(), kept.size(), space(term.relation()));
        return new Query.Hit(kept, label + " matched " + kept.size());
    }

    private static Query.Hit geometric(String name, List<DomNode> candidates, List<DomNode> anchors, DomNode root, String pierce, RelationTerm term) {
        List<DomNode> pool = without(candidates, anchors);
        List<DomNode> kept = keep(RelationTerm.Relation.fromEngine(name).orElseThrow(), pool, anchors, root, pierce);
        String space = Engines.spaceName();
        log(name, anchors.size(), pool.size(), kept.size(), space);
        return new Query.Hit(kept, name + " matched " + kept.size() + " space=" + space);
    }

    private static Query.Hit document(String name, String expr, List<DomNode> anchors, DomNode root, String pierce, RelationTerm ignored) {
        boolean shadows = pierce != null && (pierce.equals("open") || pierce.equals("all"));
        String selector = expr == null || expr.isBlank() ? "*" : expr;
        List<DomNode> candidates = Engines.css(selector, List.of(root), shadows).nodes;
        List<DomNode> pool = without(candidates, anchors);
        List<DomNode> kept = keep(RelationTerm.Relation.fromEngine(name).orElseThrow(), pool, anchors, root, pierce);
        log(name, anchors.size(), pool.size(), kept.size(), "document");
        return new Query.Hit(kept, name + " matched " + kept.size());
    }

    private static List<DomNode> anchorNodes(RelationTerm term, DomNode root, String pierce) {
        if (term.anchor() == null || term.anchor().isBlank()) return List.of(root);
        return Expressions.eval(Expressions.parse(term.anchor()), List.of(root), root, pierce).nodes;
    }

    private static List<DomNode> keep(RelationTerm.Relation relation, List<DomNode> candidates, List<DomNode> anchors, DomNode root, String pierce) {
        IdentityHashMap<DomNode, Integer> order = documentOrder(root, pierce);
        List<Scored> scored = new ArrayList<>();
        for (DomNode candidate : candidates) {
            Score score = score(relation, candidate, anchors, order);
            if (score != null) scored.add(new Scored(candidate, score));
        }
        scored.sort(comparator(relation));
        List<DomNode> kept = new ArrayList<>();
        for (Scored one : scored) kept.add(one.node);
        return Query.distinct(kept);
    }

    private static Score score(RelationTerm.Relation relation, DomNode candidate, List<DomNode> anchors, IdentityHashMap<DomNode, Integer> order) {
        return switch (relation) {
            case NEAR -> near(candidate, anchors);
            case BELOW -> edge(candidate, anchors, "below", order);
            case ABOVE, LEFT_OF, RIGHT_OF -> edge(candidate, anchors, relation.engine(), order);
            case INSIDE -> insideScore(candidate, anchors, order);
            case AFTER -> orderScore(candidate, anchors, order, true);
            case BEFORE -> orderScore(candidate, anchors, order, false);
        };
    }

    private static Score near(DomNode candidate, List<DomNode> anchors) {
        if (!candidate.hasBox) return null;
        double best = Double.MAX_VALUE;
        boolean hit = false;
        for (DomNode anchor : anchors) {
            if (anchor == candidate || !anchor.hasBox) continue;
            double distance = Engines.centerDistance(candidate, anchor);
            if (distance <= 150) {
                hit = true;
                best = Math.min(best, distance);
            }
        }
        return hit ? new Score(best, 0) : null;
    }

    private static Score edge(DomNode candidate, List<DomNode> anchors, String name, IdentityHashMap<DomNode, Integer> order) {
        boolean visual = !"below".equals(name);
        if (visual && (!candidate.displayed || !candidate.hasBox)) return null;
        if (!visual && !candidate.hasBox) return null;
        double best = Double.MAX_VALUE;
        boolean hit = false;
        for (DomNode anchor : anchors) {
            if (anchor == candidate) continue;
            if (!anchor.hasBox) continue;
            if (visual && !anchor.displayed) continue;
            if (!placed(name, candidate, anchor)) continue;
            hit = true;
            double rank = "below".equals(name) ? candidate.y : Engines.minDistanceInSpace(candidate, List.of(anchor));
            best = Math.min(best, rank);
        }
        if (!hit) return null;
        int index = order.getOrDefault(candidate, Integer.MAX_VALUE);
        return new Score(best, index);
    }

    private static boolean placed(String name, DomNode candidate, DomNode anchor) {
        return switch (name) {
            case "below" -> candidate.y >= anchor.y + anchor.height;
            case "above" -> Engines.originY(candidate) + candidate.height <= Engines.originY(anchor);
            case "left-of" -> Engines.originX(candidate) + candidate.width <= Engines.originX(anchor);
            case "right-of" -> Engines.originX(candidate) >= Engines.originX(anchor) + anchor.width;
            default -> false;
        };
    }

    private static Score insideScore(DomNode candidate, List<DomNode> anchors, IdentityHashMap<DomNode, Integer> order) {
        if (!candidate.displayed || !candidate.hasBox) return null;
        double best = Double.MAX_VALUE;
        boolean hit = false;
        for (DomNode anchor : anchors) {
            if (anchor == candidate || !anchor.displayed || !anchor.hasBox) continue;
            if (!fullyInside(candidate, anchor)) continue;
            hit = true;
            best = Math.min(best, anchor.width * anchor.height);
        }
        if (!hit) return null;
        return new Score(best, order.getOrDefault(candidate, Integer.MAX_VALUE));
    }

    private static boolean fullyInside(DomNode candidate, DomNode anchor) {
        double left = Engines.originX(candidate);
        double top = Engines.originY(candidate);
        double anchorLeft = Engines.originX(anchor);
        double anchorTop = Engines.originY(anchor);
        return left >= anchorLeft
                && top >= anchorTop
                && left + candidate.width <= anchorLeft + anchor.width
                && top + candidate.height <= anchorTop + anchor.height;
    }

    private static Score orderScore(DomNode candidate, List<DomNode> anchors, IdentityHashMap<DomNode, Integer> order, boolean after) {
        Integer index = order.get(candidate);
        if (index == null) return null;
        int best = Integer.MAX_VALUE;
        boolean hit = false;
        for (DomNode anchor : anchors) {
            Integer anchorIndex = order.get(anchor);
            if (anchorIndex == null || anchor == candidate) continue;
            if (after) {
                if (index > anchorIndex && !anchor.containsNode(candidate)) {
                    hit = true;
                    best = Math.min(best, index - anchorIndex);
                }
            } else if (index < anchorIndex && !candidate.containsNode(anchor)) {
                hit = true;
                best = Math.min(best, anchorIndex - index);
            }
        }
        if (!hit) return null;
        return new Score(after ? index : best, after ? index : -index);
    }

    private static Comparator<Scored> comparator(RelationTerm.Relation relation) {
        return (left, right) -> {
            int primary = Double.compare(left.score.primary, right.score.primary);
            if (primary != 0) return primary;
            return Integer.compare(left.score.secondary, right.score.secondary);
        };
    }

    private static IdentityHashMap<DomNode, Integer> documentOrder(DomNode root, String pierce) {
        IdentityHashMap<DomNode, Integer> order = new IdentityHashMap<>();
        List<DomNode> nodes = new ArrayList<>();
        boolean shadows = pierce != null && (pierce.equals("open") || pierce.equals("all"));
        walk(root, nodes, shadows, pierce, false);
        for (int i = 0; i < nodes.size(); i++) order.put(nodes.get(i), i);
        return order;
    }

    private static void walk(DomNode node, List<DomNode> into, boolean shadows, String pierce, boolean inFrame) {
        if (node == null) return;
        if (node.nodeType == 1 || node.nodeType == 9) into.add(node);
        for (DomNode child : node.children) {
            if (child.iframe && !inFrame) {
                into.add(child);
                continue;
            }
            walk(child, into, shadows, pierce, inFrame || node.iframe);
        }
        if (!shadows) return;
        for (DomNode shadow : node.shadowRoots) {
            if ("open".equals(pierce) && "closed".equalsIgnoreCase(shadow.shadowMode)) continue;
            walk(shadow, into, true, pierce, inFrame);
        }
    }

    private static List<DomNode> stagePool(RelationTerm.Relation relation, String expr, DomNode root, String pierce) {
        String selector = expr == null || expr.isBlank() ? "*" : expr;
        if (relation == RelationTerm.Relation.NEAR && selector.matches("\\d+(\\.\\d+)?")) selector = "*";
        boolean shadows = (relation == RelationTerm.Relation.AFTER || relation == RelationTerm.Relation.BEFORE)
                && pierce != null && (pierce.equals("open") || pierce.equals("all"));
        if (relation != RelationTerm.Relation.AFTER && relation != RelationTerm.Relation.BEFORE) shadows = false;
        return Engines.css(selector, List.of(root), shadows).nodes;
    }

    private static List<DomNode> positiveInPool(RelationTerm.Relation relation, String expr, List<DomNode> pool, List<DomNode> anchors, DomNode root, String pierce) {
        if (relation == RelationTerm.Relation.NEAR && expr != null && expr.matches("\\d+(\\.\\d+)?")) {
            return nearLimited(pool, anchors, Double.parseDouble(expr));
        }
        return keep(relation, pool, anchors, root, pierce);
    }

    private static List<DomNode> nearLimited(List<DomNode> candidates, List<DomNode> anchors, double limit) {
        List<Scored> scored = new ArrayList<>();
        for (DomNode candidate : candidates) {
            if (!candidate.hasBox) continue;
            double best = Double.MAX_VALUE;
            boolean hit = false;
            for (DomNode anchor : anchors) {
                if (anchor == candidate || !anchor.hasBox) continue;
                double distance = Engines.centerDistance(candidate, anchor);
                if (distance <= limit) {
                    hit = true;
                    best = Math.min(best, distance);
                }
            }
            if (hit) scored.add(new Scored(candidate, new Score(best, 0)));
        }
        scored.sort((left, right) -> Double.compare(left.score.primary, right.score.primary));
        List<DomNode> kept = new ArrayList<>();
        for (Scored one : scored) kept.add(one.node);
        return kept;
    }

    private static List<DomNode> subtract(List<DomNode> candidates, List<DomNode> positive, DomNode root, String pierce) {
        List<String> drop = ids(positive);
        List<DomNode> kept = new ArrayList<>();
        for (DomNode candidate : inDocumentOrder(candidates, root, pierce)) {
            if (!drop.contains(candidate.id())) kept.add(candidate);
        }
        return kept;
    }

    private static List<DomNode> inDocumentOrder(List<DomNode> nodes, DomNode root, String pierce) {
        IdentityHashMap<DomNode, Integer> order = documentOrder(root, pierce);
        List<DomNode> copy = new ArrayList<>(nodes);
        copy.sort(Comparator.comparingInt(node -> order.getOrDefault(node, Integer.MAX_VALUE)));
        return copy;
    }

    private static String label(RelationTerm term) {
        return (term.negated() ? "not-" : "") + term.relation().engine();
    }

    private static List<DomNode> without(List<DomNode> candidates, List<DomNode> anchors) {
        List<String> anchorIds = ids(anchors);
        List<DomNode> pool = new ArrayList<>();
        for (DomNode candidate : candidates) {
            if (!anchorIds.contains(candidate.id())) pool.add(candidate);
        }
        return pool;
    }

    private static List<String> ids(List<DomNode> nodes) {
        List<String> ids = new ArrayList<>();
        for (DomNode node : nodes) ids.add(node.id());
        return ids;
    }

    private static String space(RelationTerm.Relation relation) {
        return relation == RelationTerm.Relation.AFTER || relation == RelationTerm.Relation.BEFORE
                ? "document"
                : Engines.spaceName();
    }

    private static void log(String name, int anchors, int candidates, int kept, String space) {
        EngineLog.info("relation", name + " anchors=" + anchors + " candidates=" + candidates + " kept=" + kept + " space=" + space);
    }

    private record Score(double primary, int secondary) {
    }

    private record Scored(DomNode node, Score score) {
    }
}
