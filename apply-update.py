#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def replace_once(relative_path: str, old: str, new: str) -> None:
    path = ROOT / relative_path
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Expected update context was not found in {relative_path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "src/main/java/tools/dscode/common/mappings/MappingProcessor.java",
    "import static tools.dscode.coredefinitions.GeneralSteps.getReturnValue;\n",
    "import static tools.dscode.coredefinitions.GeneralSteps.getReturnValue;\n"
    "import static tools.dscode.coredefinitions.ModularScenarios.getScenarioMarkerData;\n",
)
replace_once(
    "src/main/java/tools/dscode/common/mappings/MappingProcessor.java",
    '    private static final String FILE_REFERENCE_PREFIX = "file:";\n',
    '    private static final String FILE_REFERENCE_PREFIX = "file:";\n'
    '    private static final String DATA_REFERENCE_PREFIX = "data:";\n',
)
replace_once(
    "src/main/java/tools/dscode/common/mappings/MappingProcessor.java",
    '''                if (key.startsWith("&")) {
                    key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
                    replacement = getReturnValue(key.substring(1));
                    break;
                }
''',
    '''                if (key.startsWith("&")) {
                    key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
                    String reference = key.substring(1);
                    replacement = getReturnValue(reference);
                    break;
                }
''',
)

replace_once(
    "src/main/java/tools/dscode/common/mappings/MappingProcessor.java",
    '''            if (key.startsWith(FILE_REFERENCE_PREFIX)) {
                return buildJsonFromPath(key.substring(FILE_REFERENCE_PREFIX.length()));
            }
            if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
''',
    '''            if (key.startsWith(FILE_REFERENCE_PREFIX)) {
                return buildJsonFromPath(key.substring(FILE_REFERENCE_PREFIX.length()));
            }
            if (key.startsWith(DATA_REFERENCE_PREFIX)) {
                return getScenarioMarkerData(key.substring(DATA_REFERENCE_PREFIX.length()));
            }
            if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
''',
)
replace_once(
    "src/main/java/tools/dscode/coredefinitions/ServiceCallSteps.java",
    '''    @Given("^CALL:(.*)$")
    public static Object inlineCall(String inlineArgs) {
''',
    '''    @Given("^CALL:(.*)$")
    public static Object inlineCall(
            String inlineArgs,
            DataTable dataTable
    ) {
''',
)
replace_once(
    "src/main/java/tools/dscode/coredefinitions/ServiceCallSteps.java",
    '''                inlineArgs,
                null,
                callsPath(),
''',
    '''                inlineArgs,
                dataTable,
                callsPath(),
''',
)

replace_once(
    "src/main/java/tools/dscode/testengine/PKB_props.java",
    '    public static final String PKB_FEATURE_NAME = PKB_PREFIX + "featurename";\n',
    '    public static final String PKB_FEATURE_NAME = PKB_PREFIX + "featurename";\n'
    '    public static final String PKB_DATA_PATH = PKB_PREFIX + "datapath";\n',
)
replace_once(
    "src/main/java/tools/dscode/testengine/PKB_props.java",
    '''    public static void features(String featurePaths) {
        put(PKB_FEATURES, featurePaths);
    }

''',
    '''    public static void features(String featurePaths) {
        put(PKB_FEATURES, featurePaths);
    }

    public static String dataPath() {
        return get(PKB_DATA_PATH);
    }

    public static void dataPath(String dataPath) {
        put(PKB_DATA_PATH, dataPath);
    }

''',
)

replace_once(
    "src/main/java/io/cucumber/core/runner/modularexecutions/CucumberScanUtil.java",
    '''    public static void clearCache() {
        FEATURE_CACHE.clear();
    }
''',
    '''    public static void clearCache() {
        FEATURE_CACHE.clear();
    }

    public static String getFeatureName(Pickle pickle) {
        Objects.requireNonNull(pickle, "pickle");
        if (pickle.getUri() == null) {
            return "";
        }

        Map<String, String> properties = new HashMap<>();
        properties.put(FEATURES_PROPERTY_NAME, pickle.getUri().toString());
        RuntimeOptions options = new CucumberPropertiesParser()
                .parse(properties)
                .build();

        return parseFeatures(options).stream()
                .map(CucumberScanUtil::getFeatureName)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse("");
    }
''',
)

replace_once(
    "maven-consumer-project/src/test/resources/pickleball_local.properties",
    "#pkb_tags=@all\n",
    "#pkb_tags=@all\n\n"
    "# Optional feature path for <data:...> scenario-marker lookups.\n"
    "#pkb_datapath=src/test/resources/data\n",
)

replace_once(
    "docs/configuration.md",
    "| `pkb_features` | `classpath:features` | feature-file location |\n",
    "| `pkb_features` | `classpath:features` | feature-file location |\n"
    "| `pkb_datapath` | `src/test/resources/data` | scenario-marker data feature location |\n",
)

replace_once(
    "docs/service-call-scenarios.md",
    "Each labelled value continues until the next `FEATURE:`, `SCENARIO:`, or `START:` label. Unlabelled non-tag text is rejected rather than guessed as a feature or scenario name.\n",
    "Each labelled value continues until the next `FEATURE:`, `SCENARIO:`, or `START:` label. Unlabelled non-tag text is rejected rather than guessed as a feature or scenario name.\n\n"
    "The return-value `CALL:` form accepts the same optional invocation DataTable, so selection options and passed values can be supplied when the call is evaluated dynamically.\n",
)

replace_once(
    "AGENTS.md",
    "- Framework tests under `src/test`\n",
    "- Consumer-hosted internal Java checks under `maven-consumer-project/src/test/java`\n",
)
replace_once(
    "AGENTS.md",
    "- `src/test` — focused framework tests\n",
    "- `src/test` — reserved for tests that must run inside the framework build\n",
)
replace_once(
    "AGENTS.md",
    "- `maven-consumer-project/src/test/java` — runner, local server, and support code\n",
    "- `maven-consumer-project/src/test/java` — runner, local server, support code, and internal framework checks compiled against the locally published dependency\n",
)
replace_once(
    "AGENTS.md",
    "- Focused framework tests\n",
    "- Consumer-hosted internal Java checks\n",
)
replace_once(
    "AGENTS.md",
    "For consumer-visible behavior, add or update an executable scenario in `maven-consumer-project` whenever practical. A consumer scenario is preferred over a prose-only example.\n",
    "For consumer-visible behavior, add or update an executable scenario in `maven-consumer-project` whenever practical. A consumer scenario is preferred over a prose-only example.\n\n"
    "Internal Java checks should normally live in `maven-consumer-project` and be exercised by the dedicated Cucumber feature so they compile and run against the locally published Pickleball dependency. Keep a test under root `src/test` only when it must execute inside the framework build itself.\n",
)
replace_once(
    "AGENTS.md",
    "- Relevant framework tests exist and pass.\n",
    "- Relevant consumer-hosted internal Java checks exist and pass.\n",
)

for relative_path in [
    "src/test/java/io/cucumber/core/runner/ScenarioStepTest.java",
    "src/test/java/io/cucumber/core/runner/ScenarioStepDataTest.java",
    "src/test/java/tools/dscode/coredefinitions/ModularScenariosTest.java",
    "src/test/java/tools/dscode/common/util/datetime/BusinessTemporalDeltaTest.java",
    "src/test/java/tools/dscode/common/util/datetime/BusinessTimePostModifierTest.java",
]:
    path = ROOT / relative_path
    if path.exists():
        path.unlink()

print("Applied source patches and removed relocated root test classes.")
print("Run: python scripts/refresh_agent_index.py")
