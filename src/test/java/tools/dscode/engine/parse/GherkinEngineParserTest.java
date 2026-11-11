package tools.dscode.engine.parse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.GlobalConstants;
import tools.dscode.dalton.DaltonHydrator;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.StepArgument;
import tools.dscode.engine.source.DaltonScenarioSource;
import tools.dscode.engine.source.FileScenarioSource;
import tools.dscode.engine.source.MemoryScenarioSource;
import tools.dscode.engine.source.StepListScenarioSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GherkinEngineParserTest {
    private static final String FEATURE = """
            @feat
            Feature: Box
              Background:
                Given background step
              Scenario: Plain
                Given a table
                  | a |
                  | b |
                And a doc
                  \"\"\"
                  hello
                  \"\"\"
              Rule: Group
                Scenario: Inside
                  Given inside
              Scenario Outline: Outline
                Given hello <Name>
                Examples:
                  | Name | Scenario Tags |
                  | Ada  | @one %comp    |
            """;

    @Test
    void parseKeepsPlaceholdersBackgroundRulesTablesAndDocStrings() {
        EngineFeature parsed = GherkinEngineParser.parse("memory:box", FEATURE);
        EngineFeature expanded = GherkinEngineParser.expand(parsed);
        EngineScenario plain = expanded.scenarios().getFirst();
        assertEquals("Plain", plain.name());
        assertTrue(plain.steps().getFirst().fromBackground());
        assertEquals("background step", plain.steps().getFirst().text());
        EngineStep table = plain.steps().get(1);
        assertInstanceOf(StepArgument.TableArg.class, table.argument());
        assertEquals(List.of(List.of("a"), List.of("b")), ((StepArgument.TableArg) table.argument()).table().rows());
        EngineStep doc = plain.steps().get(2);
        assertEquals("hello", ((StepArgument.DocArg) doc.argument()).doc().content());
        EngineScenario inside = expanded.scenarios().get(1);
        assertEquals("Inside", inside.name());
        assertEquals("Group", inside.metadata().get("rule"));
        EngineScenario outline = expanded.scenarios().get(2);
        assertEquals("hello <Name>", outline.steps().get(1).text());
        assertTrue(outline.tags().contains("@feat"));
        assertTrue(outline.tags().contains("@one"));
        assertTrue(outline.tags().contains("@%comp"));
        assertEquals("Ada", outline.metadata().get("exampleValues").split("\t")[0]);
    }

    @Test
    void encodingRewriteMatchesTheParserAspect() {
        String rewritten = EncodingRewrite.apply("* [DEBUG] Given ready");
        assertTrue(rewritten.contains(GlobalConstants.PARSER_FLAG));
        assertTrue(rewritten.contains("@[DEBUG]"));
    }

    @Test
    void memoryFileStepListAndDaltonSourcesLoad(@TempDir Path project) throws Exception {
        EngineFeature memory = new MemoryScenarioSource(FEATURE).load().getFirst();
        assertEquals("Box", memory.name());
        Path file = project.resolve("features/box.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, FEATURE);
        assertEquals("Box", new FileScenarioSource(file).load().getFirst().name());
        EngineFeature steps = new StepListScenarioSource("made", List.of("Given runtime step", "just text")).load().getFirst();
        assertEquals("", steps.scenarios().getFirst().steps().getFirst().location().uri());
        assertEquals("runtime step", steps.scenarios().getFirst().steps().getFirst().text());
        assertEquals("just text", steps.scenarios().getFirst().steps().get(1).text());
        DaltonHydrator.hydrate(project, DaltonHydrator.Options.full());
        List<EngineFeature> dalton = new DaltonScenarioSource(project, "features/box.feature").load();
        assertFalse(dalton.isEmpty());
        assertEquals("Box", dalton.getFirst().name());
        assertEquals(3, dalton.getFirst().scenarios().size());
    }
}
