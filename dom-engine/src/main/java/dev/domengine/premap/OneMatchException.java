package dev.domengine.premap;

import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.domengine.json.Jsons;

import java.util.List;

/** Thrown when one target cannot be chosen. The message starts with {@code ambiguous: N candidates} or {@code not found}. */
public final class OneMatchException extends RuntimeException {
    private final List<Candidate> candidates;

    public OneMatchException(String message, List<Candidate> candidates) {
        super(message == null ? "not found" : message);
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public List<Candidate> candidates() {
        return candidates;
    }

    public ArrayNode toJson() {
        ArrayNode array = Jsons.MAPPER.createArrayNode();
        for (Candidate candidate : candidates) array.add(candidate.toJson());
        return array;
    }
}
