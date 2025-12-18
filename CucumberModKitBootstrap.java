// src/main/java/tools/ds/modkit/contrib/cucumber/CucumberModKitBootstrap.java
package tools.dscode.modkit.contrib.cucumber;

import io.cucumber.messages.types.*;
import tools.dscode.modkit.extensions.StepExtension;
import tools.dscode.modkit.misc.DummySteps;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.dscode.modkit.blackbox.CtorRegistryDSL.globalRegisterConstructed;
import static tools.dscode.modkit.blackbox.CtorRegistryDSL.threadRegisterConstructed;
import static tools.dscode.modkit.blackbox.Plans.on;
import static tools.dscode.modkit.blackbox.Registry.register;
import static tools.dscode.modkit.state.ScenarioState.getScenarioState;
import static tools.dscode.modkit.util.KeyFunctions.getUniqueKey;

/**
 * Consolidated Byte Buddy plan registration for Cucumber:
 * - Combines the behaviors from CucumberModKitBootstrap + PickleStepRunPatch
 * - Uses new utility paths (Plans / Registry / CtorRegistryDSL)
 * - Keeps the same DSL, tags/Examples rewriting, step skipping, etc.
 *
 * Usage:
 *   CucumberModKitBootstrap.registerAll();
 *   tools.ds.modkit.api.ModKit.attach();  // optional: one-liner agent attach
 */
public final class CucumberModKitBootstrap {

    // Constants moved here from CucumberModKitBootstrap
    public static final String K_RUNTIME        = "io.cucumber.core.runtime.Runtime";
    public static final String K_FEATUREPARSER  = "io.cucumber.core.feature.FeatureParser";
    public static final String K_FEATURESUPPLIER= "io.cucumber.core.runtime.FeaturePathFeatureSupplier";
    public static final String K_TEST_CASE      = "io.cucumber.core.runner.TestCase";
    public static final String K_PICKLE         = "io.cucumber.core.gherkin.messages.GherkinMessagesPickle";
    public static final String K_SCENARIO       = "io.cucumber.messages.types.Scenario";
    public static final String K_RUNNER         = "io.cucumber.core.runner.Runner";
    public static final String K_JAVABACKEND    = "io.cucumber.java.JavaBackend";

    // Tag mangling helpers
    private static final String TAG_PREFIX          = "@__TAG_";
    public  static final String COMPONENT_TAG_PREFIX= TAG_PREFIX + "COMPONENT_";
    private static final String SCENARIO_TAG_PREFIX = TAG_PREFIX + "SCENARIO_";

    // Invisible meta flag injected into EncodingParser output then stripped at PickleStep#getText
    public static final String META_FLAG = "\u206AMETA";
    public static final String PARSER_FLAG = ",,,\u207A,,,";



    // Swap “leading tags/colon blob” with the step text, inserting META_FLAG between
    private static final Pattern LINE_SWAP_PATTERN = Pattern.compile(
            "^((?:(?:\\s*:)|(?:\\s*@\\[[^\\[\\]]*\\]))+)(\\s*[A-Z*].*$)",
            Pattern.MULTILINE
    );

    // Runtime skip handle copied from original
    public static final UUID SKIP_LOGGING = new UUID(0L, 0xFFL);

    private CucumberModKitBootstrap() {}

    /** Call this once (before attaching the agent or early in test/app startup). */
    public static void registerAll() {

        // 1) Auto-register constructed instances (thread-local & global) using the new CtorRegistryDSL
        threadRegisterConstructed(
                List.of(
                        K_TEST_CASE,
                        K_RUNNER
                )
        );

        globalRegisterConstructed(
                List.of(
                        K_RUNTIME,
                        K_FEATUREPARSER,
                        K_FEATURESUPPLIER,
                        K_JAVABACKEND
                )
        );

        // 2) Rewrites & tag plumbing from CucumberModKitBootstrap

        // io.cucumber.messages.types.Pickle#getTags() — filter/tag normalization
        register(
                on("io.cucumber.messages.types.Pickle", "getTags", 0)
                        .returns("java.util.List")
                        .afterSelf((self, args, ret, thr) -> {
                            Pickle p = (Pickle) self;
                            @SuppressWarnings("unchecked")
                            List<PickleTag> base = (ret == null) ? List.of() : (List<PickleTag>) ret;
                            if (base.isEmpty()) return ret;

                            List<String> ids = p.getAstNodeIds();
                            if (ids == null || ids.isEmpty()) return ret;
                            String myId = ids.get(ids.size() - 1);

                            var out = new ArrayList<PickleTag>(base.size());
                            for (PickleTag t : base) {
                                String n = t.getName();
                                if (n != null && n.startsWith(TAG_PREFIX)) {
                                    if (!Objects.equals(t.getAstNodeId(), myId)) {
                                        // drop tags that refer to a different AST node
                                        continue;
                                    }
                                    if (n.startsWith(SCENARIO_TAG_PREFIX)) {
                                        out.add(new PickleTag("@" + n.substring(SCENARIO_TAG_PREFIX.length()), t.getAstNodeId()));
                                    } else if (n.startsWith(COMPONENT_TAG_PREFIX)) {
                                        out.add(t); // keep as-is
                                    } else {
                                        out.add(t);
                                    }
                                } else {
                                    out.add(t);
                                }
                            }
                            return Collections.unmodifiableList(out);
                        })
                        .build()
        );

        // io.cucumber.messages.types.Examples#getTags() — derive tags from header columns
        register(
                on("io.cucumber.messages.types.Examples", "getTags", 0)
                        .returns("java.util.List")
                        .afterSelf((self, args, ret, thr) -> {
                            Examples ex = (Examples) self;
                            var headerOpt = ex.getTableHeader();
                            if (headerOpt.isEmpty()) return ret;

                            var headers = headerOpt.get().getCells().stream().map(TableCell::getValue).toList();
                            int iSt = headers.indexOf("Scenario Tags");
                            int iCt = headers.indexOf("Component Tags");
                            if (iSt < 0 && iCt < 0) return ret;

                            @SuppressWarnings("unchecked")
                            var base = ret == null ? List.<Tag>of() : (List<Tag>) ret;
                            var newTags = new ArrayList<Tag>();
                            var loc = ex.getLocation();

                            for (TableRow row : ex.getTableBody()) {
                                if (iSt >= 0) {
                                    var v = row.getCells().get(iSt).getValue();
                                    if (v != null && !v.isBlank()) {
                                        for (String t : v.trim().split("\\s+")) {
                                            if (!t.isBlank()) newTags.add(new Tag(loc, SCENARIO_TAG_PREFIX + t.replace("@",""), row.getId()));
                                        }
                                    }
                                }
                                if (iCt >= 0) {
                                    var v = row.getCells().get(iCt).getValue();
                                    if (v != null && !v.isBlank()) {
                                        for (String t : v.trim().split("\\s+")) {
                                            if (!t.isBlank()) newTags.add(new Tag(loc, COMPONENT_TAG_PREFIX + t.replace("@",""), row.getId()));
                                        }
                                    }
                                }
                            }

                            if (newTags.isEmpty()) return ret;
                            var merged = new ArrayList<Tag>(base);
                            merged.addAll(newTags);
                            return Collections.unmodifiableList(merged);
                        })
                        .build()
        );

        // io.cucumber.java.MethodScanner#scan(...) — bypass for DummySteps
        register(
                on("io.cucumber.java.MethodScanner", "scan", 3)
                        .around(
                                args -> {
                                    Class<?> aClass = (Class<?>) args[1];
                                    return aClass.equals(DummySteps.class);
                                },
                                args -> null // void method
                        )
                        .build()
        );

        // Skip TestStep.emitTestStepStarted/Finished when the current step id == SKIP_LOGGING
        register(
                on("io.cucumber.core.runner.TestStep", "emitTestStepStarted", 4)
                        .around(
                                args -> {
                                    StepExtension step = getScenarioState().getCurrentStep();
                                    return step != null && SKIP_LOGGING.equals(step.getId());
                                },
                                args -> null
                        )
                        .build()
        );

        register(
                on("io.cucumber.core.runner.TestStep", "emitTestStepFinished", 6)
                        .around(
                                args -> {
                                    StepExtension step = getScenarioState().getCurrentStep();
                                    return step != null && SKIP_LOGGING.equals(step.getId());
                                },
                                args -> null
                        )
                        .build()
        );

        // io.cucumber.gherkin.PickleCompiler#interpolate(...) — return the original name
        register(
                on("io.cucumber.gherkin.PickleCompiler", "interpolate", 3)
                        .returns("java.lang.String")
                        .around(args -> true, args -> (String) args[0])
                        .build()
        );

        // io.cucumber.gherkin.EncodingParser#readWithEncodingFromSource(...) — line swap w/ META_FLAG
        register(
                on("io.cucumber.gherkin.EncodingParser", "readWithEncodingFromSource", 1)
                        .returns("java.lang.String")
                        .after((args, ret, thr) -> {
                            String original = (ret == null) ? "" : (String) ret;
                            Matcher matcher = LINE_SWAP_PATTERN.matcher(original);
                            return matcher.replaceAll("$2" + META_FLAG + "$1");
                        })
                        .build()
        );

        // io.cucumber.messages.types.PickleStep#getText() — strip META_FLAG and trailing data
        register(
                on("io.cucumber.messages.types.PickleStep", "getText", 0)
                        .returns("java.lang.String")
                        .after((args, ret, thr) -> {
                            if (!(ret instanceof String s)) return ret;
                            int i = s.indexOf(META_FLAG);
                            if (i >= 0) return s.substring(0, i);
                            return s;
                        })
                        .build()
        );

        // plugin event TestStepStarted/Finished#getTestStep() — map to ScenarioState instance by unique key
        register(
                on("io.cucumber.plugin.event.TestStepStarted", "getTestStep", 0)
                        .returns("io.cucumber.plugin.event.TestStep")
                        .after((args, ret, thr) ->
                                getScenarioState().getInstance(getUniqueKey(((io.cucumber.plugin.event.PickleStepTestStep) ret)))
                        )
                        .build()
        );

        register(
                on("io.cucumber.plugin.event.TestStepFinished", "getTestStep", 0)
                        .returns("io.cucumber.plugin.event.TestStep")
                        .after((args, ret, thr) ->
                                getScenarioState().getInstance(getUniqueKey(((io.cucumber.plugin.event.PickleStepTestStep) ret)))
                        )
                        .build()
        );

        // 3) PickleStepRunPatch (consolidated here)
        //
        // Patch: io.cucumber.core.runner.PickleStepTestStep#run(TestCase, EventBus, TestCaseState, ExecutionMode)
        // Behavior: demonstrate bypass hook and keep the same return (incoming ExecutionMode) when bypassing.
        // If your original PickleStepRunPatch had extra gating logic, fold it into the 'around' decider below.
        register(
                on("io.cucumber.core.runner.PickleStepTestStep", "run", 4)
                        .returns("io.cucumber.core.runner.ExecutionMode")
                        // BEFORE (optional) — left out to keep it minimal & identical to prior observable behavior
                        .around(
                                args -> {
                                    // Place your real bypass condition here if needed.
                                    // For parity with the original sample, keep it disabled by default:
                                    return false;
                                },
                                args -> args[3] // return incoming ExecutionMode when bypassing
                        )
                        .after((args, ret, thr) -> ret)
                        .build()
        );
    }
}
