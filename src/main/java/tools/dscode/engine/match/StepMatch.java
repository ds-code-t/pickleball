package tools.dscode.engine.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One match of step text. Captures are the expression groups, in order, as text. */
public record StepMatch(
        StepKind kind,
        String text,
        GlueStep step,
        List<String> captures,
        String overrideId
) {
    public StepMatch {
        kind = kind == null ? StepKind.UNDEFINED : kind;
        text = text == null ? "" : text;
        if (captures == null) captures = List.of();
        else captures = Collections.unmodifiableList(new ArrayList<>(captures));
        overrideId = overrideId == null ? "" : overrideId;
    }

    public static StepMatch undefined(String text) {
        return new StepMatch(StepKind.UNDEFINED, text, null, List.of(), "");
    }

    public boolean matched() {
        return kind != StepKind.UNDEFINED && kind != StepKind.AMBIGUOUS;
    }
}
