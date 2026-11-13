package dev.domengine.premap;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;

import java.util.List;

/** One nested region. {@code id} is stable for a single {@link SectionDetector#detect} call. */
public record Section(
        String id,
        String kind,
        String title,
        String handle,
        Box box,
        int depth,
        String parentId,
        List<String> childIds,
        int memberCount
) {
    public Section {
        childIds = childIds == null ? List.of() : List.copyOf(childIds);
        id = id == null ? "" : id;
        kind = kind == null ? "" : kind;
        title = title == null ? "" : title;
        handle = handle == null ? "" : handle;
        parentId = parentId == null ? "" : parentId;
        if (box == null) box = new Box(0, 0, 0, 0);
    }

    public ObjectNode toJson() {
        ObjectNode node = Jsons.object();
        node.put("id", id);
        node.put("kind", kind);
        node.put("title", title);
        node.put("handle", handle);
        node.set("box", box.toJson());
        node.put("depth", depth);
        node.put("parent_id", parentId);
        ArrayNode children = node.putArray("child_ids");
        for (String child : childIds) children.add(child);
        node.put("member_count", memberCount);
        return node;
    }

    public static Section fromJson(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.ArrayList<String> children = new java.util.ArrayList<>();
        node.path("child_ids").forEach(child -> children.add(child.asText("")));
        return new Section(
                node.path("id").asText(""),
                node.path("kind").asText(""),
                node.path("title").asText(""),
                node.path("handle").asText(""),
                Box.fromJson(node.get("box")),
                node.path("depth").asInt(0),
                node.path("parent_id").asText(""),
                children,
                node.path("member_count").asInt(0)
        );
    }
}
