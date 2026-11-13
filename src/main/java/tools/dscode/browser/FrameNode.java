package tools.dscode.browser;

import java.util.ArrayList;
import java.util.List;

/**
 * One frame in a {@link BrowserFacade#frameTree()}.
 * {@code id} is the protocol frame or browsing-context id.
 * {@code parentId} is null for the root.
 */
public record FrameNode(String id, String parentId, String url, List<FrameNode> children) {
    public FrameNode {
        id = id == null ? "" : id;
        url = url == null ? "" : url;
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static FrameNode leaf(String id, String parentId, String url) {
        return new FrameNode(id, parentId, url, List.of());
    }

    public FrameNode withChildren(List<FrameNode> next) {
        return new FrameNode(id, parentId, url, next == null ? List.of() : new ArrayList<>(next));
    }
}
