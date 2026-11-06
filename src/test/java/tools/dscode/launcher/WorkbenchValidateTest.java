package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchValidateTest {
    @TempDir
    Path project;

    @Test
    void reportsUndefinedAmbiguousAndUnresolvedWithoutRunning() throws Exception {
        Path feature = project.resolve("src/test/resources/features/shop.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                @shop
                Feature: Shop
                  Scenario: Buy
                    Given buy "milk"
                    Given missing step
                    Then RUN SCENARIO: Nowhere
                    Then TRACE: kept
                """);
        Path glue = project.resolve("src/test/java/ShopSteps.java");
        Files.createDirectories(glue.getParent());
        Files.writeString(glue, """
                public class ShopSteps {
                  @Given("buy {string}") void a(String item) {}
                  @When("buy {string}") void b(String item) {}
                }
                """);
        long before = Files.size(feature);
        WorkbenchFeatureIndex.Selection selection = WorkbenchFeatureIndex.select(
                new WorkbenchFeatureIndex.Request(project, List.of(), null, null, null, List.of(), true)
        );
        List<WorkbenchValidate.Finding> findings = WorkbenchValidate.scan(
                project, feature, 1, Integer.MAX_VALUE, List.of("buy {string}", "buy {string}"), selection
        );
        assertTrue(findings.stream().anyMatch(found -> "ambiguous".equals(found.kind()) && found.text().contains("buy")));
        assertTrue(findings.stream().anyMatch(found -> "undefined".equals(found.kind()) && found.text().contains("missing")));
        assertTrue(findings.stream().anyMatch(found -> "unresolved".equals(found.kind()) && found.text().contains("Nowhere")));
        assertFalse(findings.stream().anyMatch(found -> found.text().startsWith("TRACE")));
        assertEquals(before, Files.size(feature));
        assertTrue(WorkbenchValidate.framework("TRACE: hello"));
        assertTrue(WorkbenchValidate.patternMatches("buy {string}", "buy \"milk\""));
        assertFalse(WorkbenchValidate.patternMatches("buy {string}", "missing step"));
        assertEquals("the price is 2", WorkbenchValidate.expand("the price is <price>", List.of("price"), List.of("2")));
    }

    @Test
    void outlineRowsAndCoreStepsAreDefined() throws Exception {
        Path feature = project.resolve("src/test/resources/features/outline.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Outline
                  Scenario Outline: Buy
                    Given buy "<item>"
                    Given the price is <price>
                    Given non-blank: kept
                    Given missing step
                  Examples:
                    | item | price |
                    | milk | 2 |
                """);
        Path glue = project.resolve("src/test/java/ShopSteps.java");
        Files.createDirectories(glue.getParent());
        Files.writeString(glue, """
                import io.cucumber.java.en.Given;
                public class ShopSteps {
                  @Given("buy {string}") void a(String item) {}
                  @Given("the price is {int}") void b(int price) {}
                }
                """);
        compileGlue(project);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchValidate.run(project, null, null, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, out);
        assertTrue(out.contains("validate progress stage=glue"), out);
        assertTrue(out.contains("capped=false"), out);
        assertTrue(out.contains("undefined:"), out);
        assertTrue(out.contains("missing step"), out);
        assertFalse(out.lines().anyMatch(line -> line.startsWith("undefined:") && (line.contains("price") || line.contains("non-blank") || line.contains("buy"))), out);
    }

    @Test
    void wholeSuitePrintsProgressAndStopsAtTheCap() throws Exception {
        Path feature = project.resolve("src/test/resources/features/many.feature");
        Files.createDirectories(feature.getParent());
        StringBuilder body = new StringBuilder("Feature: Many\n  Scenario: Lots\n");
        int steps = WorkbenchValidate.STEP_CAP + 25;
        for (int i = 0; i < steps; i++) body.append("    Given missing ").append(i).append('\n');
        Files.writeString(feature, body.toString());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchValidate.run(project, null, null, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, out);
        assertTrue(out.contains("validate progress stage=steps"), out);
        assertTrue(out.contains("capped=true"), out);
        assertTrue(out.contains("fix: narrow validate with --tags or --name"), out);
        assertFalse(out.contains("missing " + (steps - 1)), out);
        long undefined = out.lines().filter(line -> line.startsWith("undefined:")).count();
        assertEquals(WorkbenchValidate.STEP_CAP, undefined, out);
        assertTrue(out.contains("skipped=0"), out);
    }

    @Test
    void regexGlueWithQuotesAndDigitsIsDefined() throws Exception {
        Path feature = project.resolve("src/test/resources/features/regex.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Regex
                  Scenario: Rows
                    Given VERIFY DATA ADDRESS "addr" HAS DATA TABLE WITH 2 DATA ROWS
                """);
        Path glue = project.resolve("src/test/java/com/example/DataSteps.java");
        Files.createDirectories(glue.getParent());
        String annotation = "@Given(\"^VERIFY DATA ADDRESS \\\"([^\\\"]+)\\\" HAS DATA TABLE WITH (\\\\d+) DATA ROWS$\")";
        Files.writeString(glue, "package com.example;\nimport io.cucumber.java.en.Given;\npublic class DataSteps {\n  " + annotation + "\n  void rows(String address, int count) {}\n}\n");
        compileGlue(project);
        String source = Files.readString(glue);
        assertTrue(source.contains("(\\\\d+)"), source);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchValidate.run(project, null, null, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out);
        assertTrue(out.contains("undefined=0"), out);
        assertFalse(out.contains("undefined:"), out);
    }

    @Test
    void runIdUsesThatWorkspacesGlueAndBlankGlueDefinesNothing() throws Exception {
        Path feature = project.resolve("src/test/resources/features/report7-regex.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                @report7-regex
                Feature: Report
                  Scenario: Both
                    Given report seven cucumber 3
                    Given VERIFY DATA ADDRESS "addr" HAS DATA TABLE WITH 2 DATA ROWS
                """);
        Path glue = project.resolve("src/test/java/com/example/report7glue/Report7Steps.java");
        Files.createDirectories(glue.getParent());
        String annotation = "@Given(\"^VERIFY DATA ADDRESS \\\"([^\\\"]+)\\\" HAS DATA TABLE WITH (\\\\d+) DATA ROWS$\")";
        Files.writeString(glue, """
                package com.example.report7glue;
                import io.cucumber.java.en.Given;
                public class Report7Steps {
                  @Given("report seven cucumber {int}")
                  void cucumber(int count) {}
                """ + "  " + annotation + "\n  void rows(String address, int count) {}\n}\n");
        Path other = project.resolve("src/test/java/com/example/pickleball/OtherSteps.java");
        Files.createDirectories(other.getParent());
        Files.writeString(other, """
                package com.example.pickleball;
                import io.cucumber.java.en.Given;
                public class OtherSteps {
                  @Given("report seven cucumber {int}")
                  void cucumber(int count) {}
                }
                """);
        compileGlue(project);
        tools.dscode.control.protocol.Workspace.writeRunVars(
                tools.dscode.control.protocol.Workspace.directory(project, "run-a"),
                java.util.Map.of("pkb_glue", "com.example.report7glue")
        );
        tools.dscode.control.protocol.Workspace.writeRunVars(
                tools.dscode.control.protocol.Workspace.directory(project, "run-c"),
                java.util.Map.of("pkb_glue", "")
        );
        ByteArrayOutputStream definedOut = new ByteArrayOutputStream();
        int defined = WorkbenchValidate.run(
                project, "@report7-regex", null, "run-a", null, false,
                new PrintStream(definedOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream())
        );
        String definedText = definedOut.toString(StandardCharsets.UTF_8);
        assertEquals(0, defined, definedText);
        assertTrue(definedText.contains("runs/run-a/workspace") || definedText.replace('\\', '/').contains("runs/run-a/workspace"), definedText);
        assertTrue(definedText.contains("undefined=0"), definedText);

        ByteArrayOutputStream blankOut = new ByteArrayOutputStream();
        int blank = WorkbenchValidate.run(
                project, "@report7-regex", null, "run-c", null, false,
                new PrintStream(blankOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream())
        );
        String blankText = blankOut.toString(StandardCharsets.UTF_8);
        assertEquals(1, blank, blankText);
        assertTrue(blankText.contains("VERIFY DATA ADDRESS"), blankText);
        assertTrue(blankText.contains("report seven cucumber"), blankText);
    }

    @Test
    void maxStepsReportsSkippedScenariosAndAllWins() throws Exception {
        Path feature = project.resolve("src/test/resources/features/cap.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                @cap
                Feature: Cap
                  Scenario: Lots
                    Given missing one
                    Given missing two
                    Given missing three
                  Scenario: Later one
                    Given missing later-1
                  Scenario: Later two
                    Given missing later-2
                """);
        ByteArrayOutputStream cappedOut = new ByteArrayOutputStream();
        WorkbenchValidate.run(project, "@cap", null, null, 1, false,
                new PrintStream(cappedOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        String capped = cappedOut.toString(StandardCharsets.UTF_8);
        assertTrue(capped.contains("capped=true"), capped);
        assertTrue(capped.contains("skipped=2"), capped);
        assertFalse(capped.contains("later-2"), capped);

        ByteArrayOutputStream allOut = new ByteArrayOutputStream();
        WorkbenchValidate.run(project, "@cap", null, null, 1, true,
                new PrintStream(allOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        String all = allOut.toString(StandardCharsets.UTF_8);
        assertTrue(all.contains("capped=false"), all);
        assertTrue(all.contains("skipped=0"), all);
        assertTrue(all.contains("later-2"), all);
    }

    @Test
    void commentedAnnotationIsNotAmbiguousAndTheClashNamesEachDefinition() throws Exception {
        Path feature = project.resolve("src/test/resources/features/report7-ambiguous.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                @report7-ambiguous
                Feature: Ambiguous
                  Scenario: Active and clash
                    Given report seven active
                    Given report seven clash
                """);
        Path glue = project.resolve("src/test/java/com/example/report7glue/Report7Ambiguous.java");
        Files.createDirectories(glue.getParent());
        Files.writeString(glue, """
                package com.example.report7glue;
                import io.cucumber.java.en.And;
                public final class Report7Ambiguous {
                  @And("^report seven active$")
                  public void active() {}
                  @And("^report seven clash$")
                  public void clashLeft() {}
                }
                class Report7Commented {
                  // @And("^report seven active$")
                  // public void commentedOut() {}
                }
                class Report7ClashOther {
                  @And("^report seven clash$")
                  public void clashRight() {}
                }
                """);
        compileGlue(project);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchValidate.run(project, "@report7-ambiguous", null,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, out);
        assertTrue(out.contains("ambiguous=1"), out);
        assertFalse(out.lines().anyMatch(line -> line.startsWith("ambiguous:") && line.contains("report seven active")), out);
        assertFalse(out.contains("commentedOut"), out);
        String clash = out.lines().filter(line -> line.startsWith("ambiguous:")).findFirst().orElse("");
        assertTrue(clash.contains("Report7Ambiguous"), clash);
        assertTrue(clash.contains("clashLeft"), clash);
        assertTrue(clash.contains("Report7ClashOther"), clash);
        assertTrue(clash.contains("clashRight"), clash);
        assertTrue(clash.contains("^report seven clash$"), clash);
        assertEquals("report7 bulk pattern 0 ", WorkbenchValidate.literalPrefix("^report7 bulk pattern 0 (\\d+)$"));
        assertEquals("buy ", WorkbenchValidate.literalPrefix("buy {string}"));
        assertEquals("report seven clash", WorkbenchValidate.literalPrefix("^report seven clash$"));
        assertEquals("item", WorkbenchValidate.literalPrefix("^items? are shown$"));
        assertEquals("colo", WorkbenchValidate.literalPrefix("^colou?r"));
        assertEquals("cat", WorkbenchValidate.literalPrefix("^cats* are here$"));
        assertEquals("", WorkbenchValidate.literalPrefix("^a|^b"));
        assertEquals("probe item", WorkbenchValidate.literalPrefix("^probe items? are shown$"));
        assertEquals("trieq box", WorkbenchValidate.literalPrefix("^trieq boxe{0,1}s shown$"));
        assertEquals("trieq a ", WorkbenchValidate.literalPrefix("trieq a cat/dog walks"));
        assertEquals("trieq box", WorkbenchValidate.literalPrefix("trieq box(es) shown"));
        assertEquals("", WorkbenchValidate.literalPrefix("^(?i)trieq shown$"));
        assertEquals("item", WorkbenchValidate.literalPrefix("^item(s)? shown$"));
        assertEquals("colou?r", WorkbenchValidate.literalPrefix("^colou\\?r$"));
        assertEquals("cat/dog", WorkbenchValidate.literalPrefix("cat\\/dog"));
        assertEquals("", WorkbenchValidate.literalPrefix("hello$"));
        assertEquals("", WorkbenchValidate.literalPrefix("/a[0-9]/"));
    }

    @Test
    void repeatedI18nDependencyGlueAndStaleSource() throws Exception {
        Path feature = project.resolve("src/test/resources/features/report8.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                @report8
                Feature: Report 8
                  Scenario: Glue
                    Given report eight first alias
                    Given report eight second alias
                    Given report eight french step
                    Given report eight typed step
                    Given report eight stale step
                """);
        Path glue = project.resolve("src/test/java/com/example/report8glue/Report8Typed.java");
        Files.createDirectories(glue.getParent());
        Files.writeString(glue, """
                package com.example.report8glue;
                import io.cucumber.java.en.Given;
                import io.cucumber.java.fr.Et;
                public final class Report8Typed {
                  @Given("^report eight first alias$")
                  @Given("^report eight second alias$")
                  public void aliases() {}
                  @Et("^report eight french step$")
                  public void french() {}
                }
                """);
        Path stale = project.resolve("src/test/java/com/example/report8glue/Report8Stale.java");
        Files.writeString(stale, """
                package com.example.report8glue;
                import io.cucumber.java.en.Given;
                final class Report8Stale {
                  @Given("^report eight stale step$")
                  public void stale() {}
                }
                """);
        compileGlue(project);
        Path markerRoot = project.resolve("marker-classes");
        Path markerSource = project.resolve("marker-src/com/example/report8dep/Marker.java");
        Files.createDirectories(markerSource.getParent());
        Files.writeString(markerSource, """
                package com.example.report8dep;
                public class Marker { public Marker() {} }
                """);
        compileSources(List.of(markerSource), markerRoot, System.getProperty("java.class.path"));
        Path external = project.resolve("external-src/com/example/report8glue/Report8External.java");
        Files.createDirectories(external.getParent());
        Files.writeString(external, """
                package com.example.report8glue;
                import com.example.report8dep.Marker;
                import io.cucumber.java.en.Given;
                public class Report8External extends Marker {
                  @Given("^report eight typed step$")
                  public void typed(String unused) {}
                }
                """);
        compileSources(
                List.of(external),
                project.resolve("target/test-classes"),
                markerRoot + java.io.File.pathSeparator + System.getProperty("java.class.path")
        );
        Path harness = project.resolve(".pickleball/harness-classpath.txt");
        Files.createDirectories(harness.getParent());
        Files.writeString(harness, markerRoot.toAbsolutePath().normalize().toString());
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 120_000));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchValidate.run(project, "@report8", null,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out);
        assertFalse(out.contains("undefined:"), out);
        assertTrue(out.contains("compiled glue is older than its source:"), out);
        assertTrue(out.contains("Report8Stale.java"), out);
    }

    @Test
    void braceQuantifierAndSlashAlternationAreDefined() throws Exception {
        Path feature = project.resolve("src/test/resources/features/trieq.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                @trieq
                Feature: Trie quantifiers
                  Scenario: Matcher valid steps
                    Given trieq boxs shown
                    Given trieq a cat walks
                    Given trieq a dog walks
                    Given trieq box shown
                    Given TRIEQ SHOWN
                """);
        Path glue = project.resolve("src/test/java/com/example/trieq/TrieqSteps.java");
        Files.createDirectories(glue.getParent());
        Files.writeString(glue, """
                package com.example.trieq;
                import io.cucumber.java.en.Given;
                public final class TrieqSteps {
                  @Given("^trieq boxe{0,1}s shown$")
                  public void brace() {}
                  @Given("trieq a cat/dog walks")
                  public void slash() {}
                  @Given("trieq box(es) shown")
                  public void optionalText() {}
                  @Given("^(?i)trieq shown$")
                  public void insensitive() {}
                }
                """);
        compileGlue(project);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchValidate.run(project, "@trieq", null,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out);
        assertFalse(out.contains("undefined:"), out);
    }

    private static void compileGlue(Path project) throws Exception {
        Path sourceRoot = project.resolve("src/test/java");
        List<Path> sources = new ArrayList<>();
        try (var walk = Files.walk(sourceRoot)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
        }
        Path output = Files.createDirectories(project.resolve("target/test-classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "JDK compiler is required to compile glue fixtures");
        StringWriter errors = new StringWriter();
        var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        try (files) {
            boolean compiled = compiler.getTask(
                    errors,
                    files,
                    null,
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                    null,
                    files.getJavaFileObjectsFromPaths(sources)
            ).call();
            assertTrue(compiled, errors.toString());
        }
    }

    private static void compileSources(List<Path> sources, Path output, String classpath) throws Exception {
        Files.createDirectories(output);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "JDK compiler is required to compile glue fixtures");
        StringWriter errors = new StringWriter();
        var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        try (files) {
            boolean compiled = compiler.getTask(
                    errors,
                    files,
                    null,
                    List.of("-classpath", classpath, "-d", output.toString()),
                    null,
                    files.getJavaFileObjectsFromPaths(sources)
            ).call();
            assertTrue(compiled, errors.toString());
        }
    }
}
