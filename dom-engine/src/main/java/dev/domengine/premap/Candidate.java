package dev.domengine.premap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.affinity.AffinityScorer;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.List;

/** One node considered by {@link OneMatch}. */
public record Candidate(
        String handle,
        String role,
        String name,
        String text,
        Box box,
        Double score,
        boolean capped,
        String capReason,
        boolean hidden,
        boolean interactable,
        boolean excluded,
        List<String> satisfiedNegatives,
        String reason
) {
    public Candidate {
        satisfiedNegatives = satisfiedNegatives == null ? List.of() : List.copyOf(satisfiedNegatives);
        reason = reason == null ? "" : reason;
        capReason = capReason == null ? "" : capReason;
        handle = handle == null ? "" : handle;
        role = role == null ? "" : role;
        name = name == null ? "" : name;
        text = text == null ? "" : text;
    }

    public Candidate withReason(String next) {
        return new Candidate(handle, role, name, text, box, score, capped, capReason, hidden, interactable, excluded,
                satisfiedNegatives, next);
    }

    public static Candidate of(
            DomNode node, Double score, List<String> negatives, double viewportWidth, double viewportHeight, BoxCaps caps
    ) {
        BoxCaps used = caps == null ? BoxCaps.defaults() : caps;
        BoxCaps.Decision decision = used.assess(node, viewportWidth, viewportHeight, false);
        String shown = text(node);
        if (shown.length() > 40) shown = shown.substring(0, 40);
        String accessible = node.accessibleName == null || node.accessibleName.isBlank()
                ? HtmlTree.textOf(node) : node.accessibleName;
        boolean hidden = !node.displayed || node.states.contains("hidden");
        return new Candidate(
                node.id(),
                Query.roleOf(node),
                accessible == null ? "" : accessible,
                shown,
                Box.of(node),
                score,
                decision.capped(),
                decision.reason(),
                hidden,
                AffinityScorer.interactable(node),
                BoxCaps.alwaysExcluded(node),
                negatives,
                ""
        );
    }

    public ObjectNode toJson() {
        ObjectNode node = Jsons.object();
        node.put("handle", handle);
        node.put("role", role);
        node.put("name", name);
        node.put("text", text.length() > 40 ? text.substring(0, 40) : text);
        node.set("box", box == null ? Jsons.object() : box.toJson());
        if (score == null) node.putNull("score");
        else node.put("score", score);
        node.put("reason", reason);
        return node;
    }

    private static String text(DomNode node) {
        String value = HtmlTree.textOf(node);
        if (value.isBlank() && node.accessibleName != null) value = node.accessibleName;
        return value == null ? "" : value;
    }

    public static List<Candidate> copy(List<Candidate> candidates) {
        return new ArrayList<>(candidates);
    }
}
