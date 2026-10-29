package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks the Workbench parser sets. Fails when the committed inventory drifts,
 * and fails when any command or major flag has no {@code covers_*} test method.
 */
class WorkbenchCliCoverageGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern COVERS = Pattern.compile("\\b(covers_(?:command|flag)_[A-Za-z0-9_]+)\\s*\\(");

    @Test
    void inventoryMatchesTheParserSets() throws IOException {
        JsonNode root = JSON.readTree(inventoryFile().toFile());
        assertEquals("2.1.18", root.path("version").asText());
        assertEquals(sorted(WorkbenchCommandLine.FORWARDED_COMMANDS), array(root, "commands", "forwarded"));
        assertEquals(sorted(WorkbenchCommandLine.AGENT_CORE_COMMANDS), array(root, "commands", "agentCore"));
        assertEquals(sorted(WorkbenchCommandLine.SESSION_CLIENT_COMMANDS), array(root, "commands", "sessionClient"));
        assertEquals(WorkbenchCommandLine.MAJOR_FLAGS, array(root, "commandLineFlags"));
        assertEquals(WorkbenchSessionCommands.SessionFlags.MAJOR_FLAGS, array(root, "sessionFlags"));
        assertEquals(WorkbenchAgentCommands.MAJOR_FLAGS, array(root, "agentFlags"));
    }

    @Test
    void majorFlagsAppearInTheParserThatRecognizesThem() throws IOException {
        assertFlagsPresent(
                "src/main/java/tools/dscode/launcher/WorkbenchCommandLine.java",
                WorkbenchCommandLine.MAJOR_FLAGS
        );
        assertFlagsPresent(
                "src/main/java/tools/dscode/launcher/WorkbenchSessionCommands.java",
                WorkbenchSessionCommands.SessionFlags.MAJOR_FLAGS
        );
        assertFlagsPresent(
                "src/main/java/tools/dscode/launcher/WorkbenchAgentCommands.java",
                WorkbenchAgentCommands.MAJOR_FLAGS
        );
    }

    @Test
    void everyCommandAndMajorFlagHasACoverageTest() throws IOException {
        Set<String> methods = coverageMethods();
        List<String> missing = new ArrayList<>();
        for (String command : commands()) {
            String method = "covers_command_" + command.replace('-', '_');
            if (!methods.contains(method)) missing.add(method + "  command " + command);
        }
        for (String flag : flags()) {
            String method = "covers_flag_" + flag.substring(2).replace('-', '_');
            if (!methods.contains(method)) missing.add(method + "  flag " + flag);
        }
        assertTrue(
                missing.isEmpty(),
                "Workbench CLI coverage gate is red. Missing " + missing.size() + " test method(s):\n"
                        + String.join("\n", missing)
        );
    }

    private static void assertFlagsPresent(String source, List<String> flags) throws IOException {
        String text = Files.readString(Path.of(source));
        for (String flag : flags) {
            assertTrue(text.contains("\"" + flag + "\""), source + " does not mention " + flag);
        }
    }

    private static List<String> commands() {
        TreeSet<String> commands = new TreeSet<>();
        commands.addAll(WorkbenchCommandLine.FORWARDED_COMMANDS);
        commands.addAll(WorkbenchCommandLine.AGENT_CORE_COMMANDS);
        commands.addAll(WorkbenchCommandLine.SESSION_CLIENT_COMMANDS);
        return List.copyOf(commands);
    }

    private static List<String> flags() {
        List<String> flags = new ArrayList<>();
        flags.addAll(WorkbenchCommandLine.MAJOR_FLAGS);
        flags.addAll(WorkbenchSessionCommands.SessionFlags.MAJOR_FLAGS);
        flags.addAll(WorkbenchAgentCommands.MAJOR_FLAGS);
        return flags;
    }

    private static Set<String> coverageMethods() throws IOException {
        TreeSet<String> methods = new TreeSet<>();
        Path root = Path.of("src/test/java");
        if (!Files.isDirectory(root)) return methods;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = COVERS.matcher(Files.readString(file));
                while (matcher.find()) methods.add(matcher.group(1));
            }
        }
        return methods;
    }

    private static List<String> array(JsonNode root, String... path) {
        JsonNode node = root;
        for (String name : path) node = node.path(name);
        List<String> values = new ArrayList<>();
        node.forEach(child -> values.add(child.asText()));
        return values;
    }

    private static List<String> sorted(Set<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    private static Path inventoryFile() {
        Path relative = Path.of("docs/roadmaps/workbench-cli-inventory.json");
        if (Files.isRegularFile(relative)) return relative;
        return Path.of("..").resolve(relative);
    }
}
