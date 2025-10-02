package tools.ds.modkit.blackbox;

import io.cucumber.plugin.event.PickleStepTestStep;
import tools.ds.modkit.coredefinitions.GeneralSteps;
import tools.ds.modkit.coredefinitions.MetaSteps;
import tools.ds.modkit.extensions.StepExtension;
import tools.ds.modkit.misc.DummySteps;
import tools.ds.modkit.util.CallScope;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.ds.modkit.blackbox.Plans.*;
import static tools.ds.modkit.state.ScenarioState.*;
import static tools.ds.modkit.util.KeyFunctions.getUniqueKey;
import static tools.ds.modkit.util.Reflect.invokeAnyMethod;


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

    public static void register() {
        System.out.println("@@register DSL");


        CtorRegistryDSL.threadRegisterConstructed(
                List.of(
                        K_TEST_CASE,
                        K_PICKLE,
                        K_SCENARIO,
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

        Registry.register(
                on("io.cucumber.java.MethodScanner", "scan", 3)
                        .around(
                                args -> {
                                    System.out.println("@@scanner "+ Arrays.stream(args).toList());
                                    Class<?> aClass = (Class<?>) args[1];
                                    return aClass.getSimpleName().toLowerCase().contains("dummysteps");

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
                                    return step!=null && step.getId().equals(skipLogging); // bypass original

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

                                    return step!=null &&  step.getId().equals(skipLogging); // bypass original

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
                                System.out.println("[modkit] PickleStep#getText keep-before-flag → " + out);
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
