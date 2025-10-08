package tools.ds.modkit.blackbox;

import io.cucumber.messages.types.*;
import io.cucumber.plugin.event.Node;
import tools.ds.modkit.extensions.StepExtension;
import tools.ds.modkit.misc.DummySteps;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.ds.modkit.blackbox.Plans.*;
import static tools.ds.modkit.state.ScenarioState.*;
import static tools.ds.modkit.util.KeyFunctions.getUniqueKey;

import io.cucumber.messages.types.Tag;
import io.cucumber.messages.types.PickleTag;
import io.cucumber.messages.types.Examples;

import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.TableCell;

public final class BlackBoxBootstrap {

//\u206A – INHIBIT SYMMETRIC SWAPPING (deprecated)
//\u206B – ACTIVATE SYMMETRIC SWAPPING (deprecated)
//\u206C – INHIBIT ARABIC FORM SHAPING (deprecated)
//\u206D – ACTIVATE ARABIC FORM SHAPING (deprecated)
//\u206E – NATIONAL DIGIT SHAPES (deprecated)
//\u206F – NOMINAL DIGIT SHAPES (deprecated)

    //    public static final String K_OPTIONS = "io.cucumber.core.options.RuntimeOptions";
    public static final String K_RUNTIME = "io.cucumber.core.runtime.Runtime";
    public static final String K_FEATUREPARSER = "io.cucumber.core.feature.FeatureParser";
    public static final String K_FEATURESUPPLIER = "io.cucumber.core.runtime.FeaturePathFeatureSupplier";
    public static final String K_TEST_CASE = "io.cucumber.core.runner.TestCase";
    //    private static final String K_PICKLE = "io.cucumber.messages.types.Pickle";
    public static final String K_PICKLE = "io.cucumber.core.gherkin.messages.GherkinMessagesPickle";
    public static final String K_SCENARIO = "io.cucumber.messages.types.Scenario";
    public static final String K_RUNNER = "io.cucumber.core.runner.Runner";
    public static final String K_JAVABACKEND = "io.cucumber.java.JavaBackend";

    public static final String metaFlag = "\u206AMETA";


    private static final Pattern LINE_SWAP_PATTERN = Pattern.compile(
            "^((?:(?:\\s*:)|(?:\\s*@\\[[^\\[\\]]*\\]))+)(\\s*[A-Z*].*$)",
            Pattern.MULTILINE

//            "^((?:\\s+(?::|@\\[)\\S*)+)(\\s+[A-Z*].*$)",
//            Pattern.MULTILINE
    );

    public final static UUID skipLogging = new java.util.UUID(0L, 0xFFL);

    private static final String TagPrefix = "@__TAG_";
    public static final String ComponentTagPrefix = TagPrefix + "COMPONENT_";
    private static final String ScenarioTagPrefix = TagPrefix + "SCENARIO_";

    public static void register() {
        System.out.println("@@register DSL");


        CtorRegistryDSL.threadRegisterConstructed(
                List.of(
                        K_TEST_CASE,
//                        K_PICKLE,
//                        K_SCENARIO,
                        K_RUNNER
                )
//                ,                "current-scenario" // optional extra key (same value stored under multiple keys)
        );

// Or global:
        CtorRegistryDSL.globalRegisterConstructed(
                List.of(
                        K_RUNTIME,
                        K_FEATUREPARSER,
                        K_FEATURESUPPLIER,
                        K_JAVABACKEND
                )
        );


//        Registry.register(
//                on("io.cucumber.java.JavaBackend", "loadGlue", 2)
//                        .before(args -> {
//
////                            System.out.println("@@GLue paths1: " +args[1]);
////                            List<URI> gluePaths = new ArrayList<>();
////
////                            gluePaths.add(toGluePath(MetaSteps.class));
////                            gluePaths.addAll( (List<URI>) args[1]);
////                            args[1] = gluePaths;
////                            System.out.println("@@GLue paths2: " +args[1]);
//                        })
//                        .build()
//        );


        // Add these imports if you don't already have them:

// (Optional, if you want to inspect cells)
// import io.cucumber.messages.types.TableCell;

// You can also add a constant if you prefer (mirrors K_SCENARIO):
// public static final String K_EXAMPLES = "io.cucumber.messages.types.Examples";




        // Add if needed:






// Modify the input *Token* before match_StepLine executes
//        Registry.register(
//                on("io.cucumber.gherkin.PickleCompiler", "pickleTags", 1)
//                        .before(args -> {
//                            List<PickleTag> tags = (List<PickleTag>) args[0];
//                            System.out.println("@@pickleTags:: " + tags);
//
//                        })
//                        .build()
//        );








//        Registry.register(
//                on("io.cucumber.messages.types.Examples", "getTags", 0)
//                        .returns("java.util.List")
//                        // <-- needs a DSL hook that passes the receiver `self`
//                        .afterSelf((self, args, ret, thr) -> {
//                            Examples ex = (Examples) self;
//
//                            @SuppressWarnings("unchecked")
//                            java.util.List<Tag> base = (ret == null)
//                                    ? java.util.List.of()
//                                    : (java.util.List<Tag>) ret;
//
//                            java.util.List<Tag> derived = deriveTagsFromExamplesInstance(ex);
//
//                            if (derived.isEmpty()) return ret; // keep original
//
//                            // Merge + dedupe by tag name
//                            java.util.ArrayList<Tag> merged = new java.util.ArrayList<>(base);
//                            java.util.HashSet<String> have = new java.util.HashSet<>();
//                            for (Tag t : base) have.add(t.getName());
//                            for (Tag t : derived) if (t != null && have.add(t.getName())) merged.add(t);
//
//                            // Preserve “unmodifiable” semantics of the generated types
//                            return java.util.Collections.unmodifiableList(merged);
//                        })
//                        .build()
//        );




        Registry.register(
                on("io.cucumber.messages.types.Pickle", "getTags", 0)
                        .returns("java.util.List")
                        .afterSelf((self, args, ret, thr) -> {
                            Pickle p = (Pickle) self;

                            @SuppressWarnings("unchecked")
                            List<PickleTag> base = (ret == null)
                                    ? List.of()
                                    : (List<PickleTag>) ret;
                            if (base.isEmpty()) return ret;

                            List<String> ids = p.getAstNodeIds();
                            if (ids == null || ids.isEmpty()) return ret;
                            String myId = ids.get(ids.size() - 1);
                            System.out.println("@@myId: " + myId);
                            var out = new ArrayList<PickleTag>(base.size());
                            for (PickleTag t : base) {
                                String n = t.getName();
                                if (n != null && n.startsWith(TagPrefix)) {
                                    System.out.println("@@.getAstNodeId(): "+ t.getAstNodeId());
                                    if (!Objects.equals(t.getAstNodeId(), myId)) {
                                        // drop ELT_ tags that don't belong to this pickle
                                        continue;
                                    }
                                    if (n.startsWith(ScenarioTagPrefix)) {
                                        System.out.println("@@start ScenarioTagPrefix: " + new PickleTag("@" + n.substring(ScenarioTagPrefix.length()), t.getAstNodeId()));
                                        out.add(new PickleTag("@" + n.substring(ScenarioTagPrefix.length()), t.getAstNodeId()));
                                    } else if (n.startsWith(ComponentTagPrefix)) {
                                        out.add(t); // keep as-is
                                    } else {
                                        out.add(t);
                                    }
                                } else {
                                    out.add(t); // keep non-ELT tags
                                }
                            }

//                            if (out.size() == base.size()) return ret; // unchanged
                            System.out.println("@@out: " + out);
                            return Collections.unmodifiableList(out);
                        })
                        .build()
        );




        Registry.register(
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
                                            if (!t.isBlank()) newTags.add(new Tag(loc, ScenarioTagPrefix + t.replace("@",""), row.getId())); // drop "1" if your Tag ctor is (Location,String)
                                        }
                                    }
                                }
                                if (iCt >= 0) {
                                    var v = row.getCells().get(iCt).getValue();
                                    if (v != null && !v.isBlank()) {
                                        for (String t : v.trim().split("\\s+")) {
                                            if (!t.isBlank()) newTags.add(new Tag(loc, ComponentTagPrefix + t.replace("@",""),  row.getId()));
                                        }
                                    }
                                }
                            }

                            if (newTags.isEmpty()) return ret;

                            var merged = new ArrayList<Tag>(base);
                            merged.addAll(newTags);
                            System.out.println("@@merged: " + merged);
                            return Collections.unmodifiableList(merged);
                        })
                        .build()
        );






// --- io.cucumber.messages.types.Examples <ctor>(8 args) ---
// arg indices:
// 0: Location location
// 1: java.util.List<Tag> tags
// 2: String keyword
// 3: String name
// 4: String description
// 5: TableRow tableHeader (nullable)
// 6: java.util.List<TableRow> tableBody
// 7: String id
//        Registry.register(
//                onCtor(/*K_EXAMPLES*/ "io.cucumber.messages.types.Examples", 8)
//                        .before(args -> {
//                            TableRow tableHeader = (TableRow) args[5];
//                            List<String> headers = tableHeader.getCells().stream().map(TableCell::getValue).toList();
//                            int indexScenarioTags = tableHeader.getCells().stream().map(TableCell::getValue).toList().indexOf("Scenario Tags");
//                            int indexComponentTags = tableHeader.getCells().stream().map(TableCell::getValue).toList().indexOf("Component Tags");
//                            if (Math.max(indexScenarioTags, indexComponentTags) >= 0) {
//                                String newTagString = "";
//                                List<TableRow> tableBody = (List<TableRow>) args[6];
//                                for (final TableRow valuesRow : tableBody) {
//                                    Long line = valuesRow.getLocation().getLine();
//                                    if (indexScenarioTags != -1) {
//                                        String val = valuesRow.getCells().get(indexScenarioTags).getValue();
//                                        if (val != null && !val.isBlank()) {
//                                            newTagString += " @ELT_ST_" + line + "_" + val.strip().replaceAll("\\s+", "____").replaceAll("@","");
//                                        }
//                                    }
//                                }
//                                for (final TableRow valuesRow : tableBody) {
//                                    Long line = valuesRow.getLocation().getLine();
//                                    if (indexComponentTags != -1) {
//                                        String val = valuesRow.getCells().get(indexComponentTags).getValue();
//                                        if (val != null && !val.isBlank()) {
//                                            newTagString += " @ELT_CT_" + line + "_" + val.strip().replaceAll("\\s+", "____").replaceAll("@","");
//                                        }
//                                    }
//                                }
//                                Location location = (Location) args[0];
//                                List<Tag> tags = new ArrayList<>((List<Tag>) args[1]);
//                                List<Tag> newTags = Arrays.stream(newTagString.split("\\s+")).filter(s -> !s.isBlank()).map(s -> new Tag(location, s, "1")).toList();
//                                System.out.println("@@newTags: " + newTags);
//                                tags.addAll(newTags);
//                                System.out.println("@@modified: " + tags);
//                                args[1] = tags;
//                            }
//                        })
//                        .build()
//        );




        Registry.register(
                on("io.cucumber.java.MethodScanner", "scan", 3)
                        .around(
                                args -> {
                                    Class<?> aClass = (Class<?>) args[1];
//                                    return aClass.getSimpleName().toLowerCase().contains("dummysteps");
                                    return aClass.equals(DummySteps.class);
                                },
                                args -> null // void method → return null when bypassing
                        )
                        .build()
        );


        // Skip TestCase.emitTestCaseStarted(..) when executionId matches a configured UUID

        Registry.register(
                on("io.cucumber.core.runner.TestStep", "emitTestStepStarted", 4)
                        .around(
                                args -> {
                                    StepExtension step = getScenarioState().getCurrentStep();

//                                    System.out.println("@@$$step: " + step.getId());
                                    return step != null && step.getId().equals(skipLogging); // bypass original

                                },
                                args -> null // void method → return null when bypassing
                        )
                        .build()
        );

// Skip TestCase.emitTestCaseFinished(..) when executionId matches a configured UUID
        Registry.register(
                on("io.cucumber.core.runner.TestStep", "emitTestStepFinished", 6)
                        .around(
                                args -> {
                                    StepExtension step = getScenarioState().getCurrentStep();

                                    return step != null && step.getId().equals(skipLogging); // bypass original

                                },
                                args -> null // void
                        )
                        .build()
        );


//         io.cucumber.gherkin.PickleCompiler#interpolate(String, List, List)
// Bypass original and just return the first arg (name)
        Registry.register(
                on("io.cucumber.gherkin.PickleCompiler", "interpolate", 3)
                        .returns("java.lang.String")
                        .around(
                                args -> true,                 // always skip original
                                args -> (String) args[0]      // return `name`
                        )
                        .build()
        );

        Registry.register(
                on("io.cucumber.gherkin.EncodingParser", "readWithEncodingFromSource", 1)
                        .returns("java.lang.String")
                        .after((args, ret, thr) -> {
                            String original = (ret == null) ? "" : (String) ret;
                            Matcher matcher = LINE_SWAP_PATTERN.matcher(original);
                            String newStringReturn = matcher.replaceAll("$2" + metaFlag + "$1");
                            return newStringReturn;
                        })
                        .build()
        );


        Registry.register(
                on("io.cucumber.messages.types.PickleStep", "getText", 0)
                        .returns("java.lang.String")
                        .after((args, ret, thr) -> {
                            if (!(ret instanceof String s)) return ret;
                            int i = s.indexOf(metaFlag);
                            if (i >= 0) {
                                String out = s.substring(0, i);
                                return out;
                            }
                            return s;
                        })
                        .build()
        );

        // Intercept: TestStepStarted#getTestStep()
        Registry.register(
                on("io.cucumber.plugin.event.TestStepStarted", "getTestStep", 0)
                        .returns("io.cucumber.plugin.event.TestStep")
                        .after((args, ret, thr) -> getScenarioState().getInstance(getUniqueKey(((io.cucumber.plugin.event.PickleStepTestStep) ret))))
                        .build()
        );

// Intercept: TestStepFinished#getTestStep()
        Registry.register(
                on("io.cucumber.plugin.event.TestStepFinished", "getTestStep", 0)
                        .returns("io.cucumber.plugin.event.TestStep")
                        .after((args, ret, thr) -> getScenarioState().getInstance(getUniqueKey(((io.cucumber.plugin.event.PickleStepTestStep) ret))))
                        .build()
        );


        // CHANGE Scneario NAME KEEP ME
//        // --- io.cucumber.messages.types.Scenario#getName() ---
//        Registry.register(
//                on("io.cucumber.messages.types.Scenario", "getName", 0)
//                        .returns("java.lang.String")
//                        .after((args, ret, thr) -> {
//                            String in = (ret == null ? "<null>" : String.valueOf(ret));
//                            System.err.println("[modkit] messages.types.Scenario#getName BEFORE: " + in
//                                    + (thr != null ? " (threw: " + thr + ")" : ""));
//                            String out = "ZZ9" + (ret == null ? "" : in);
//                            System.err.println("[modkit] messages.types.Scenario#getName AFTER : " + out);
//                            return out;
//                        })
//                        .build()
//        );


//        // --- io.cucumber.core.gherkin.messages.GherkinMessagesPickle#getName() ---
//
//        Registry.register(
//                on("io.cucumber.core.gherkin.messages.GherkinMessagesPickle", "getName", 0)
//                        .returns("java.lang.String")
//                        .after((args, ret, thr) -> {
//                            String in = (ret == null ? "<null>" : String.valueOf(ret));
//                            System.err.println("[modkit] GherkinMessagesPickle#getName BEFORE: " + in
//                                    + (thr != null ? " (threw: " + thr + ")" : ""));
//                            String out = (ret == null ? "" : in.replace("ZZ9", ""));
//                            System.err.println("[modkit] GherkinMessagesPickle#getName AFTER : " + out);
//                            return out;
//                        })
//                        .build()
//        );


        /// OLD


// Modify the input *Token* before match_StepLine executes
//        Registry.register(
//                on("io.cucumber.gherkin.GherkinTokenMatcher", "match_StepLine", 1)
//                        .before(args -> {
//                            // Log raw args
//                            System.out.println("[modkit] match_StepLine args=" + Arrays.toString(args));
//
//                            // Grab token + line (quick reflection; adjust field/method names as needed)
//                            Object token = args[0];
//                            Object line = getProperty(token, "line");
//                            String matchedText = (String) getProperty(token, "matchedText");
//                            String matchedKeyword = (String) getProperty(token, "matchedKeyword");
//                            String keywordType = (String) getProperty(token, "keywordType");
//                            System.out.println("@@matchedText: " + matchedText);
//                            System.out.println("@@matchedKeyword: " + matchedKeyword);
//
//                            String text = (String) getProperty(line, "text");
//                            System.out.println("@@line: " + line);
//                            System.out.println("@@text: " + text);
//
//
//                        })
//                        .build()
//        );


// Mutate the `data` (2nd arg) of io.cucumber.messages.types.Source(String uri, String data, SourceMediaType mediaType)
//        Registry.register(
//                onCtor("io.cucumber.messages.types.Source", 3)
//                        .before(args -> {
//                            if (args != null && args.length == 3 && args[1] instanceof String data) {
//                                Matcher matcher = LINE_SWAP_PATTERN.matcher(data);
//                                args[1]  = matcher.replaceAll("$2->$1");
//                                System.out.println("@@ args[1]: " +  args[1]);
//                            }
//                        })
//                        .build()
//        );


//        Registry.register(
//                onCtor("io.cucumber.gherkin.Line", 1)  // <-- CtorPlan.Builder
//                        .before(args -> {
//                            if (args != null && args.length == 1 && args[0] instanceof String s && startsWithColonOrAtBracket(s)) {
//                                System.out.println("@@s: " + s);
//                                args[0] = s.replaceAll("^(.*?\\s)([A-Z*].*)$", "$2 $1");
//                                System.out.println("@@ args[0]: " + args[0]);
//                            }
//                        })
//                        .build()
//        );


// --- io.cucumber.gherkin.Line#getText() ---
//        Registry.register(
//                on("io.cucumber.gherkin.Line", "getText", 0)
//                        .returns("java.lang.String")
//                        .before(args -> System.out.println("[modkit] Line#getText <BEFORE>"))
//                        .after((args, ret, thr) -> {
//                            System.out.println("[modkit] Line#getText <AFTER> ret=" + ret + (thr != null ? " thr=" + thr : ""));
//                            // return ret;            // no-op
//                            // or mutate to prove it sticks:
//                            return (ret == null) ? null : ret + " <<patched>>";
//                        })
//                        .build()
//        );

// (optional) also peek at the raw text used to build `text`
//        Registry.register(
//                on("io.cucumber.gherkin.Line", "getRawText", 0)
//                        .returns("java.lang.String")
//                        .after((args, ret, thr) -> {
//                            System.out.println("[modkit] Line#getRawText ret=" + ret);
//                            return ret;
//                        })
//                        .build()
//        );


//        // In BlackBoxBootstrap.register()
//// PickleStepTestStep#run — decide to bypass or run; and post-process return
//        Registry.register(
//                on("io.cucumber.core.runner.PickleStepTestStep", "run", 4)
//                        .returns("io.cucumber.core.runner.ExecutionMode")
//
//                        // BEFORE: inspect/mutate args if you want
//                        .before(args -> {
//                            System.out.println("[modkit] PkStep#run BEFORE args=" + java.util.Arrays.toString(args));
//                            // args[0]=TestCase, args[1]=EventBus, args[2]=TestCaseState, args[3]=ExecutionMode
//                            // (Optional) you can tweak args[3] (the incoming ExecutionMode) here if desired.
//                        })
////                io.cucumber.core.runner.ExecutionMode.SKIP;
//                        // AROUND: if true => skip original and return value from supplier
//                        .around(
//                                args -> {
//                                    // ----- your skip logic here (based only on inputs) -----
//                                    Object testCase       = args[0];
//                                    Object eventBus       = args[1];
//                                    Object testCaseState  = args[2];
//                                    Object incomingMode   = args[3];
//
//                                    // Example placeholder: skip when a sysprop is set
////                                    boolean skip = Boolean.getBoolean("modkit.skip.this.step");
//                                    boolean skip = true;
//
//                                    if (skip) {
//                                        System.out.println("[modkit] PkStep#run BYPASS (return incoming mode)");
//                                    }
//                                    return skip; // true => bypass original
//                                },
//                                args -> {
//                                    // Value to return when bypassing (must be an ExecutionMode)
//                                    return args[3]; // e.g., keep the incoming mode
//                                }
//                        )
//
//                        // AFTER: see the original return and optionally change it
//                        .after((args, ret, thr) -> {
//                            System.out.println("[modkit] PkStep#run AFTER ret=" + ret + " thr=" + thr);
//
//                            // ----- your return-munging logic here -----
//                            // Example: force return to the incoming mode if a flag is set
//                            if (Boolean.getBoolean("modkit.force.return.incoming.mode")) {
//                                return args[3];
//                            }
//                            return ret; // keep original
//                        })
//
//                        .build()
//        );


    }


    private BlackBoxBootstrap() {
    }
}
