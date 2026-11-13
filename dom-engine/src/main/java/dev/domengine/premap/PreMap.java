package dev.domengine.premap;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pre-computed indexes for one snapshot. Phase 1 holds the model; later phases fill it. */
public final class PreMap {
    private final String sessionId;
    private final String docVersion;
    private final List<Section> sections;
    private final List<LabelRef> labels;
    private final List<String> unlabeled;
    private final Map<String, String> boxes;
    private final Map<String, ObjectNode> viewportMap;
    private final Map<String, List<String>> byRole;
    private final Map<String, List<String>> byRoleName;
    private final Map<String, List<String>> byTextToken;
    private final Map<String, List<String>> byTag;
    private final Map<String, List<String>> byId;
    private final Map<String, List<String>> byClass;
    private final Map<String, List<String>> byXpath;
    private final Map<String, List<String>> bySectionTitle;
    private final List<String> interactables;
    private final List<String> inViewport;
    private final ObjectNode image;
    private final Map<String, ObjectNode> colours;

    private PreMap(
            String sessionId,
            String docVersion,
            List<Section> sections,
            List<LabelRef> labels,
            List<String> unlabeled,
            Map<String, String> boxes,
            Map<String, ObjectNode> viewportMap,
            Map<String, List<String>> byRole,
            Map<String, List<String>> byRoleName,
            Map<String, List<String>> byTextToken,
            Map<String, List<String>> byTag,
            Map<String, List<String>> byId,
            Map<String, List<String>> byClass,
            Map<String, List<String>> byXpath,
            Map<String, List<String>> bySectionTitle,
            List<String> interactables,
            List<String> inViewport,
            ObjectNode image,
            Map<String, ObjectNode> colours
    ) {
        this.sessionId = sessionId == null ? "" : sessionId;
        this.docVersion = docVersion == null ? "" : docVersion;
        this.sections = sections == null ? List.of() : List.copyOf(sections);
        this.labels = labels == null ? List.of() : List.copyOf(labels);
        this.unlabeled = unlabeled == null ? List.of() : List.copyOf(unlabeled);
        this.boxes = boxes == null ? Map.of() : Map.copyOf(boxes);
        this.viewportMap = viewportMap == null ? Map.of() : viewportMap;
        this.byRole = copyIndex(byRole);
        this.byRoleName = copyIndex(byRoleName);
        this.byTextToken = copyIndex(byTextToken);
        this.byTag = copyIndex(byTag);
        this.byId = copyIndex(byId);
        this.byClass = copyIndex(byClass);
        this.byXpath = copyIndex(byXpath);
        this.bySectionTitle = copyIndex(bySectionTitle);
        this.interactables = interactables == null ? List.of() : List.copyOf(interactables);
        this.inViewport = inViewport == null ? List.of() : List.copyOf(inViewport);
        this.image = image == null ? Jsons.object() : image;
        this.colours = colours == null ? Map.of() : colours;
    }

    public static PreMap empty(String sessionId, String docVersion) {
        return new PreMap(sessionId, docVersion, List.of(), List.of(), List.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), Jsons.object(), Map.of());
    }

    public String sessionId() {
        return sessionId;
    }

    public String docVersion() {
        return docVersion;
    }

    public List<Section> sections() {
        return sections;
    }

    public List<LabelRef> labels() {
        return labels;
    }

    public List<String> unlabeled() {
        return unlabeled;
    }

    public Map<String, List<String>> byRole() {
        return byRole;
    }

    public Map<String, List<String>> byRoleName() {
        return byRoleName;
    }

    public Map<String, List<String>> byTextToken() {
        return byTextToken;
    }

    public Map<String, List<String>> byTag() {
        return byTag;
    }

    public Map<String, List<String>> byId() {
        return byId;
    }

    public Map<String, List<String>> byClassName() {
        return byClass;
    }

    public Map<String, List<String>> byXpath() {
        return byXpath;
    }

    public Map<String, List<String>> bySectionTitle() {
        return bySectionTitle;
    }

    public List<String> interactables() {
        return interactables;
    }

    public List<String> inViewport() {
        return inViewport;
    }

    public ObjectNode image() {
        return image;
    }

    public Map<String, ObjectNode> colours() {
        return colours;
    }

    public ObjectNode toJson() {
        ObjectNode node = Jsons.object();
        ArrayNode sectionsNode = node.putArray("sections");
        for (Section section : sections) sectionsNode.add(section.toJson());
        ArrayNode labelsNode = node.putArray("labels");
        for (LabelRef label : labels) labelsNode.add(label.toJson());
        ArrayNode unlabeledNode = node.putArray("unlabeled");
        for (String handle : unlabeled) unlabeledNode.add(handle);
        ObjectNode boxNode = node.putObject("boxes");
        boxes.forEach(boxNode::put);
        ObjectNode view = node.putObject("viewport_map");
        viewportMap.forEach(view::set);
        ObjectNode indexes = node.putObject("indexes");
        putIndex(indexes, "by_role", byRole);
        putIndex(indexes, "by_role_name", byRoleName);
        putIndex(indexes, "by_text_token", byTextToken);
        putIndex(indexes, "by_tag", byTag);
        putIndex(indexes, "by_id", byId);
        putIndex(indexes, "by_class", byClass);
        putIndex(indexes, "by_xpath", byXpath);
        putIndex(indexes, "by_section_title", bySectionTitle);
        ArrayNode interactableNode = indexes.putArray("interactables");
        for (String handle : interactables) interactableNode.add(handle);
        ArrayNode viewportNode = indexes.putArray("in_viewport");
        for (String handle : inViewport) viewportNode.add(handle);
        node.set("image", image);
        ObjectNode colourNode = node.putObject("colours");
        colours.forEach(colourNode::set);
        return node;
    }

    public static Builder builder(String sessionId, String docVersion) {
        return new Builder(sessionId, docVersion);
    }

    private static void putIndex(ObjectNode parent, String name, Map<String, List<String>> index) {
        ObjectNode node = parent.putObject(name);
        for (Map.Entry<String, List<String>> entry : index.entrySet()) {
            ArrayNode values = node.putArray(entry.getKey());
            for (String handle : entry.getValue()) values.add(handle);
        }
    }

    private static Map<String, List<String>> copyIndex(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (source == null) return copy;
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
        }
        return copy;
    }

    public record LabelRef(String label, String target, double score, Map<String, Double> parts, List<String> reasons) {
        public ObjectNode toJson() {
            ObjectNode node = Jsons.object();
            node.put("label", label == null ? "" : label);
            node.put("target", target == null ? "" : target);
            node.put("score", score);
            ObjectNode partNode = node.putObject("parts");
            if (parts != null) parts.forEach(partNode::put);
            ArrayNode reasonNode = node.putArray("reasons");
            if (reasons != null) for (String reason : reasons) reasonNode.add(reason);
            return node;
        }
    }

    public static final class Builder {
        private final String sessionId;
        private final String docVersion;
        private List<Section> sections = List.of();
        private List<LabelRef> labels = List.of();
        private List<String> unlabeled = List.of();
        private Map<String, String> boxes = Map.of();
        private final Map<String, ObjectNode> viewportMap = new LinkedHashMap<>();
        private Map<String, List<String>> byRole = Map.of();
        private Map<String, List<String>> byRoleName = Map.of();
        private Map<String, List<String>> byTextToken = Map.of();
        private Map<String, List<String>> byTag = Map.of();
        private Map<String, List<String>> byId = Map.of();
        private Map<String, List<String>> byClass = Map.of();
        private Map<String, List<String>> byXpath = Map.of();
        private Map<String, List<String>> bySectionTitle = Map.of();
        private List<String> interactables = List.of();
        private List<String> inViewport = List.of();
        private ObjectNode image = Jsons.object();
        private final Map<String, ObjectNode> colours = new LinkedHashMap<>();

        private Builder(String sessionId, String docVersion) {
            this.sessionId = sessionId;
            this.docVersion = docVersion;
        }

        public Builder sections(List<Section> sections) {
            this.sections = sections;
            return this;
        }

        public Builder labels(List<LabelRef> labels) {
            this.labels = labels;
            return this;
        }

        public Builder unlabeled(List<String> unlabeled) {
            this.unlabeled = unlabeled;
            return this;
        }

        public Builder boxes(Map<String, String> boxes) {
            this.boxes = boxes;
            return this;
        }

        public Builder viewport(String handle, ObjectNode box) {
            if (handle != null && box != null) viewportMap.put(handle, box);
            return this;
        }

        public Builder indexes(
                Map<String, List<String>> byRole,
                Map<String, List<String>> byRoleName,
                Map<String, List<String>> byTextToken,
                Map<String, List<String>> byTag,
                Map<String, List<String>> byId,
                Map<String, List<String>> byClass,
                Map<String, List<String>> byXpath,
                Map<String, List<String>> bySectionTitle,
                List<String> interactables,
                List<String> inViewport
        ) {
            this.byRole = byRole;
            this.byRoleName = byRoleName;
            this.byTextToken = byTextToken;
            this.byTag = byTag;
            this.byId = byId;
            this.byClass = byClass;
            this.byXpath = byXpath;
            this.bySectionTitle = bySectionTitle;
            this.interactables = interactables;
            this.inViewport = inViewport;
            return this;
        }

        public Builder image(ObjectNode image) {
            this.image = image;
            return this;
        }

        public Builder colour(String handle, ObjectNode colour) {
            if (handle != null && colour != null) colours.put(handle, colour);
            return this;
        }

        public PreMap build() {
            return new PreMap(sessionId, docVersion, sections, labels, unlabeled, boxes, viewportMap,
                    byRole, byRoleName, byTextToken, byTag, byId, byClass, byXpath, bySectionTitle,
                    interactables, inViewport, image, colours);
        }
    }
}
