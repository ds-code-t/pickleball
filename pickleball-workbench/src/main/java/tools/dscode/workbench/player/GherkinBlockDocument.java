package tools.dscode.workbench.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Nested block view of a live Gherkin buffer.
 *
 * <p>Blocks <em>are</em> Gherkin text. Nesting is the existing Pickleball
 * leading-colon grammar; this class does not compile to another language and
 * does not strip {@code Given}/{@code When}/{@code Then}.</p>
 */
public final class GherkinBlockDocument {
    public record Block(long id, String text, int nestLevel, List<Block> children) {
        public Block {
            Objects.requireNonNull(text, "text");
            children = List.copyOf(children == null ? List.of() : children);
        }

        public boolean structural() {
            String trimmed = stripNestPrefix(text);
            return startsWithAny(trimmed,
                    "Feature:", "Rule:", "Background:", "Scenario:", "Scenario Outline:", "Examples:");
        }

        public boolean nestable() {
            return !structural() && !text.strip().startsWith("#") && !text.isBlank();
        }
    }

    private final List<Block> roots;

    public GherkinBlockDocument(List<Block> roots) {
        this.roots = List.copyOf(roots == null ? List.of() : roots);
    }

    public static GherkinBlockDocument fromPlayer(LiveScenarioPlayer player) {
        return fromLines(player.lines());
    }

    public static GherkinBlockDocument fromLines(List<LiveScenarioPlayer.Line> lines) {
        List<Parsed> parsed = new ArrayList<>();
        for (LiveScenarioPlayer.Line line : lines) {
            parsed.add(new Parsed(line.id(), line.text(), nestLevel(line.text()), stripNestPrefix(line.text())));
        }
        return new GherkinBlockDocument(buildTree(parsed));
    }

    public static GherkinBlockDocument fromTexts(List<String> texts) {
        List<LiveScenarioPlayer.Line> lines = new ArrayList<>();
        long id = 1;
        for (String text : texts) {
            String value = text == null ? "" : text;
            lines.add(new LiveScenarioPlayer.Line(id++, value, LiveScenarioPlayer.LineType.TEXT));
        }
        return fromLines(lines);
    }

    public List<Block> roots() {
        return roots;
    }

    public List<String> toLines() {
        List<String> lines = new ArrayList<>();
        write(roots, 0, lines);
        return List.copyOf(lines);
    }

    public void applyTo(LiveScenarioPlayer player) {
        player.replaceDocument(toLines());
    }

    public Optional<Block> find(long id) {
        return find(roots, id);
    }

    public GherkinBlockDocument updateText(long id, String text) {
        String value = text == null ? "" : text;
        return new GherkinBlockDocument(map(roots, id, block ->
                new Block(block.id(), value, block.nestLevel(), block.children())));
    }

    /**
     * Moves {@code id} so it becomes a child of {@code parentId} (or a root
     * when {@code parentId} is empty) at {@code index}. Nested IF/ELSE and
     * nested steps snap as parent/child this way.
     */
    public GherkinBlockDocument move(long id, OptionalLong parentId, int index) {
        Block moving = find(id).orElseThrow(() -> new IllegalArgumentException("Unknown block id: " + id));
        if (parentId.isPresent() && contains(moving, parentId.getAsLong())) {
            throw new IllegalArgumentException("Cannot nest a block inside itself.");
        }
        List<Block> without = remove(roots, id);
        Block relocated = new Block(moving.id(), moving.text(), 0, moving.children());
        List<Block> inserted = insert(without, parentId, Math.max(0, index), relocated);
        return new GherkinBlockDocument(inserted);
    }

    public Optional<Block> playheadBlock(LiveScenarioPlayer player) {
        if (player.playheadId().isEmpty()) return Optional.empty();
        return find(player.playheadId().getAsLong());
    }

    private static void write(List<Block> blocks, int level, List<String> lines) {
        for (Block block : blocks) {
            lines.add(applyNest(block.text(), level));
            write(block.children(), level + 1, lines);
        }
    }

    private static List<Block> buildTree(List<Parsed> parsed) {
        class Mutable {
            final long id;
            final String text;
            final int level;
            final List<Mutable> children = new ArrayList<>();

            Mutable(long id, String text, int level) {
                this.id = id;
                this.text = text;
                this.level = level;
            }

            Block freeze() {
                return new Block(id, text, level, children.stream().map(Mutable::freeze).toList());
            }
        }

        List<Mutable> roots = new ArrayList<>();
        List<Mutable> stack = new ArrayList<>();
        for (Parsed item : parsed) {
            Mutable created = new Mutable(item.id(), item.body(), item.level());
            while (!stack.isEmpty() && stack.getLast().level >= item.level()) {
                stack.removeLast();
            }
            if (stack.isEmpty()) {
                roots.add(created);
            } else {
                stack.getLast().children.add(created);
            }
            stack.add(created);
        }
        return roots.stream().map(Mutable::freeze).toList();
    }

    private static List<Block> map(List<Block> blocks, long id, java.util.function.Function<Block, Block> mapper) {
        List<Block> updated = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            List<Block> children = map(block.children(), id, mapper);
            Block current = new Block(block.id(), block.text(), block.nestLevel(), children);
            updated.add(current.id() == id ? mapper.apply(current) : current);
        }
        return updated;
    }

    private static List<Block> remove(List<Block> blocks, long id) {
        List<Block> updated = new ArrayList<>();
        for (Block block : blocks) {
            if (block.id() == id) continue;
            updated.add(new Block(block.id(), block.text(), block.nestLevel(), remove(block.children(), id)));
        }
        return updated;
    }

    private static List<Block> insert(List<Block> blocks, OptionalLong parentId, int index, Block moving) {
        if (parentId.isEmpty()) {
            List<Block> roots = new ArrayList<>(blocks);
            roots.add(Math.min(index, roots.size()), moving);
            return roots;
        }
        List<Block> updated = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            if (block.id() == parentId.getAsLong()) {
                List<Block> children = new ArrayList<>(block.children());
                children.add(Math.min(index, children.size()), moving);
                updated.add(new Block(block.id(), block.text(), block.nestLevel(), children));
            } else {
                updated.add(new Block(
                        block.id(),
                        block.text(),
                        block.nestLevel(),
                        insert(block.children(), parentId, index, moving)
                ));
            }
        }
        return updated;
    }

    private static Optional<Block> find(List<Block> blocks, long id) {
        for (Block block : blocks) {
            if (block.id() == id) return Optional.of(block);
            Optional<Block> nested = find(block.children(), id);
            if (nested.isPresent()) return nested;
        }
        return Optional.empty();
    }

    private static boolean contains(Block block, long id) {
        if (block.id() == id) return true;
        for (Block child : block.children()) {
            if (contains(child, id)) return true;
        }
        return false;
    }

    static int nestLevel(String text) {
        String trimmed = text == null ? "" : text.stripLeading();
        int level = 0;
        while (trimmed.startsWith(":")) {
            level++;
            trimmed = trimmed.substring(1).stripLeading();
        }
        return level;
    }

    static String stripNestPrefix(String text) {
        if (text == null) return "";
        String indent = leadingWhitespace(text);
        String trimmed = text.stripLeading();
        while (trimmed.startsWith(":")) {
            trimmed = trimmed.substring(1).stripLeading();
        }
        if (text.isBlank()) return text;
        if (indent.isEmpty()) return trimmed;
        return trimmed;
    }

    static String applyNest(String body, int level) {
        String content = stripNestPrefix(body);
        if (content.isBlank() || content.startsWith("#")) {
            return body == null ? "" : body;
        }
        if (level <= 0) {
            return body != null && body.startsWith(" ") ? preserveIndent(body, content) : content;
        }
        return "  " + ":".repeat(level) + " " + content;
    }

    private static String preserveIndent(String original, String content) {
        return leadingWhitespace(original) + content;
    }

    private static String leadingWhitespace(String text) {
        if (text == null) return "";
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return text.substring(0, i);
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    private record Parsed(long id, String original, int level, String body) { }
}
