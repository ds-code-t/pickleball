package tools.dscode.common.composite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dictionary layer. Flags the Java binding forwards. No matching of its own. */
public record CompositeSelector(String xpath, String css, String pipeline, String source, Integer ordinal, String phrase) {
    public Map<String, Object> body(List<String> frames, List<String> shadows) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (pipeline != null && !pipeline.isBlank()) body.put("pipeline", pipeline);
        if (css != null && !css.isBlank()) body.put("css", css);
        if (xpath != null && !xpath.isBlank()) body.put("xpath", xpath);
        if (frames != null && !frames.isEmpty()) body.put("frame", String.join(",", frames));
        if (shadows != null && !shadows.isEmpty()) body.put("shadow", String.join(",", shadows));
        return body;
    }

    public String describe() {
        List<String> parts = new ArrayList<>();
        if (phrase != null) parts.add(phrase);
        if (pipeline != null) parts.add("pipeline=" + pipeline);
        if (css != null) parts.add("css=" + css);
        if (xpath != null) parts.add("xpath=" + xpath);
        if (source != null) parts.add("source=" + source);
        if (ordinal != null) parts.add("ordinal=" + ordinal);
        return String.join(" ", parts);
    }
}
