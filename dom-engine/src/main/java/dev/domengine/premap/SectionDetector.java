package dev.domengine.premap;

import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Nested sections for a browser document or a desktop accessibility tree. */
public final class SectionDetector {
    private SectionDetector() {
    }

    public static List<Section> detect(DomNode root, Platform platform) {
        if (root == null) return List.of();
        root.linkParents();
        Platform used = platform == null ? Platform.BROWSER : platform;
        Builder builder = new Builder(root, used);
        return builder.build();
    }

    private static final class Builder {
        private final DomNode root;
        private final Platform platform;
        private final List<Mutable> sections = new ArrayList<>();
        private int nextId;

        private Builder(DomNode root, Platform platform) {
            this.root = root;
            this.platform = platform;
        }

        private List<Section> build() {
            Mutable top = add(root, null, rootKind(), title(root, rootKind()));
            assign(root, top);
            fill(root, top);
            for (Mutable section : sections) {
                String path = path(section);
                count(root, path, section);
            }
            List<Section> frozen = new ArrayList<>();
            for (Mutable section : sections) frozen.add(section.freeze());
            return frozen;
        }

        private void fill(DomNode container, Mutable parent) {
            if (container == null || parent == null) return;
            for (DomNode child : container.children) {
                if (child.nodeType != 1 && (child.sectionPath == null || child.sectionPath.isBlank())) {
                    child.sectionPath = parent.path;
                }
            }
            List<DomNode> nodes = children(container);
            fillRange(nodes, 0, nodes.size(), parent, 0);
            for (DomNode shadow : container.shadowRoots) {
                List<DomNode> nested = children(shadow);
                fillRange(nested, 0, nested.size(), parent, 0);
            }
        }

        private void fillRange(List<DomNode> nodes, int from, int to, Mutable parent, int minLevel) {
            int index = from;
            while (index < to) {
                DomNode node = nodes.get(index);
                if (isHeading(node) && level(node) > minLevel) {
                    int level = level(node);
                    int end = index + 1;
                    while (end < to) {
                        DomNode next = nodes.get(end);
                        if (isHeading(next) && level(next) <= level) break;
                        end++;
                    }
                    Mutable section = add(node, parent, "heading", text(node));
                    assign(node, section);
                    fill(node, section);
                    fillRange(nodes, index + 1, end, section, level);
                    index = end;
                    continue;
                }
                String kind = kind(node);
                if (kind != null && node != root) {
                    Mutable section = add(node, parent, kind, title(node, kind));
                    assign(node, section);
                    fill(node, section);
                    index++;
                    continue;
                }
                assign(node, parent);
                fill(node, parent);
                index++;
            }
        }

        private Mutable add(DomNode node, Mutable parent, String kind, String title) {
            Mutable section = new Mutable();
            section.id = "s" + nextId++;
            section.kind = kind;
            section.title = title == null ? "" : title.trim();
            section.handle = node == null ? "" : node.id();
            section.box = Box.of(node);
            section.depth = parent == null ? 0 : parent.depth + 1;
            section.parentId = parent == null ? "" : parent.id;
            section.path = parent == null ? section.id : parent.path + "/" + section.id;
            sections.add(section);
            if (parent != null) parent.childIds.add(section.id);
            return section;
        }

        private void assign(DomNode node, Mutable section) {
            if (node == null || section == null) return;
            node.sectionPath = section.path;
        }

        private void count(DomNode node, String path, Mutable section) {
            if (node == null) return;
            if (path.equals(node.sectionPath)) section.memberCount++;
            for (DomNode child : node.children) count(child, path, section);
            for (DomNode shadow : node.shadowRoots) count(shadow, path, section);
        }

        private String path(Mutable section) {
            return section.path;
        }

        private String rootKind() {
            if (platform == Platform.DESKTOP) {
                String kind = desktopKind(root);
                return kind == null ? "window" : kind;
            }
            return "document";
        }

        private String kind(DomNode node) {
            if (node == null || node.nodeType == 3 || node.nodeType == 9) return null;
            if (platform == Platform.DESKTOP) return desktopKind(node);
            return browserKind(node);
        }

        private String browserKind(DomNode node) {
            String tag = tag(node);
            String role = Query.roleOf(node);
            if ("header".equals(tag) || "banner".equals(role)) return "landmark";
            if ("nav".equals(tag) || "navigation".equals(role)) return "landmark";
            if ("main".equals(tag) || "main".equals(role)) return "landmark";
            if ("aside".equals(tag) || "complementary".equals(role)) return "landmark";
            if ("footer".equals(tag) || "contentinfo".equals(role)) return "landmark";
            if ("search".equals(role)) return "landmark";
            if (("section".equals(tag) || "region".equals(role) || "section".equals(role)) && named(node)) return "landmark";
            if ("form".equals(tag) || "form".equals(role)) return "form";
            if ("fieldset".equals(tag)) return "fieldset";
            if ("dialog".equals(tag) || "dialog".equals(role) || "alertdialog".equals(role) || "true".equals(attr(node, "aria-modal"))) {
                return "dialog";
            }
            if ("table".equals(tag) || "table".equals(role) || "grid".equals(role) || "treegrid".equals(role)) return "table";
            if ("tabpanel".equals(role)) return "tabpanel";
            if ("details".equals(tag)) return "details";
            if (node.iframe || "iframe".equals(tag) || "frame".equals(tag)) return "iframe";
            return null;
        }

        private String desktopKind(DomNode node) {
            return switch (Query.roleOf(node)) {
                case "window", "dialog", "pane", "group", "toolbar", "menu", "menubar", "list", "table", "tree", "form", "tab", "tabpanel" ->
                        Query.roleOf(node);
                default -> null;
            };
        }

        private String title(DomNode node, String kind) {
            if (node == null) return "";
            String labelled = labelledBy(node);
            if (!labelled.isBlank()) return labelled;
            String aria = attr(node, "aria-label");
            if (!aria.isBlank()) return aria;
            if (!"heading".equals(kind)) {
                String heading = firstHeading(node);
                if (!heading.isBlank()) return heading;
            }
            if ("fieldset".equals(kind)) {
                String legend = childText(node, "legend");
                if (!legend.isBlank()) return legend;
            }
            if ("table".equals(kind)) {
                String caption = childText(node, "caption");
                if (!caption.isBlank()) return caption;
            }
            if ("details".equals(kind)) {
                String summary = childText(node, "summary");
                if (!summary.isBlank()) return summary;
            }
            if (!node.accessibleName.isBlank()) return node.accessibleName;
            return "";
        }

        private String labelledBy(DomNode node) {
            String raw = attr(node, "aria-labelledby");
            if (raw.isBlank()) return "";
            StringBuilder builder = new StringBuilder();
            for (String id : raw.split("\\s+")) {
                DomNode found = findId(root, id);
                if (found == null) continue;
                if (!builder.isEmpty()) builder.append(' ');
                builder.append(text(found));
            }
            return builder.toString();
        }

        private String firstHeading(DomNode node) {
            for (DomNode child : node.children) {
                if (isHeading(child)) return text(child);
                if (kind(child) != null) continue;
                String nested = firstHeading(child);
                if (!nested.isBlank()) return nested;
            }
            return "";
        }

        private boolean named(DomNode node) {
            return !attr(node, "aria-label").isBlank() || !attr(node, "aria-labelledby").isBlank() || !node.accessibleName.isBlank();
        }

        private static boolean isHeading(DomNode node) {
            if (node == null || node.nodeType != 1) return false;
            String tag = tag(node);
            if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') return true;
            return "heading".equals(Query.roleOf(node));
        }

        private static int level(DomNode node) {
            String tag = tag(node);
            if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
                return tag.charAt(1) - '0';
            }
            String raw = attr(node, "aria-level");
            if (!raw.isBlank()) {
                try {
                    return Integer.parseInt(raw.trim());
                } catch (NumberFormatException ignored) {
                    return 2;
                }
            }
            return 2;
        }

        private static List<DomNode> children(DomNode node) {
            List<DomNode> list = new ArrayList<>();
            if (node == null) return list;
            for (DomNode child : node.children) {
                if (child.nodeType == 1) list.add(child);
            }
            return list;
        }

        private static String childText(DomNode node, String tag) {
            for (DomNode child : node.children) {
                if (tag.equals(child.localName)) return text(child);
            }
            return "";
        }

        private static DomNode findId(DomNode node, String id) {
            if (node == null) return null;
            if (id.equals(node.attributes.get("id"))) return node;
            for (DomNode child : node.children) {
                DomNode found = findId(child, id);
                if (found != null) return found;
            }
            for (DomNode shadow : node.shadowRoots) {
                DomNode found = findId(shadow, id);
                if (found != null) return found;
            }
            return null;
        }

        private static String text(DomNode node) {
            String value = HtmlTree.textOf(node);
            if (value.isBlank() && node.accessibleName != null) value = node.accessibleName;
            return value == null ? "" : value.trim();
        }

        private static String tag(DomNode node) {
            return node.localName == null ? "" : node.localName.toLowerCase(Locale.ROOT);
        }

        private static String attr(DomNode node, String name) {
            if (node == null) return "";
            String value = node.attributes.get(name);
            return value == null ? "" : value;
        }
    }

    private static final class Mutable {
        private String id;
        private String kind;
        private String title;
        private String handle;
        private Box box;
        private int depth;
        private String parentId;
        private String path;
        private final List<String> childIds = new ArrayList<>();
        private int memberCount;

        private Section freeze() {
            return new Section(id, kind, title, handle, box, depth, parentId, childIds, memberCount);
        }
    }
}
