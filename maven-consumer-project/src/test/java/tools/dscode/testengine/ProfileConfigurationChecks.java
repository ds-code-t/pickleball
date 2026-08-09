package tools.dscode.testengine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tools.dscode.testengine.PKB_props.PKB_BASELINE_RUN_ID;
import static tools.dscode.testengine.PKB_props.PKB_BROWSER;
import static tools.dscode.testengine.PKB_props.PKB_CHANGED_VARIABLES;
import static tools.dscode.testengine.PKB_props.PKB_ENVIRONMENT;
import static tools.dscode.testengine.PKB_props.PKB_GLUE;
import static tools.dscode.testengine.PKB_props.PKB_INVESTIGATION_ID;
import static tools.dscode.testengine.PKB_props.PKB_NAME;
import static tools.dscode.testengine.PKB_props.PKB_PARENT_RUN_ID;
import static tools.dscode.testengine.PKB_props.PKB_PROFILE;
import static tools.dscode.testengine.PKB_props.PKB_RP_API_KEY;
import static tools.dscode.testengine.PKB_props.PKB_RUN_PROFILE;
import static tools.dscode.testengine.PKB_props.PKB_RUN_PURPOSE;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

public final class ProfileConfigurationChecks {

    @Test
    void directRunProfileOverridesOtherRunVars() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_PROFILE, "qa");
        values.put(PKB_RUN_PROFILE, "pkb_tags=@direct, pkb_browser=firefox");

        PickleballProfiles.Resolution resolution = PickleballProfiles.apply(values);

        assertTrue(resolution.direct());
        assertEquals("@direct", values.get(PKB_TAGS));
        assertEquals("firefox", values.get(PKB_BROWSER));
        assertFalse(values.containsKey(PKB_ENVIRONMENT));
        assertFalse(values.containsKey(PKB_PROFILE));
    }

    @Test
    void directRunProfilePreservesSeparateDiagnosticLineage() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_INVESTIGATION_ID, "diag-214");
        values.put(PKB_RUN_PURPOSE, "controlled-rerun");
        values.put(PKB_PARENT_RUN_ID, "run-parent");
        values.put(PKB_BASELINE_RUN_ID, "run-baseline");
        values.put(PKB_CHANGED_VARIABLES, "pkb_browser");
        values.put(PKB_RUN_PROFILE, "pkb_tags=@direct, pkb_browser=firefox");

        PickleballProfiles.apply(values);

        assertEquals("diag-214", values.get(PKB_INVESTIGATION_ID));
        assertEquals("controlled-rerun", values.get(PKB_RUN_PURPOSE));
        assertEquals("run-parent", values.get(PKB_PARENT_RUN_ID));
        assertEquals("run-baseline", values.get(PKB_BASELINE_RUN_ID));
        assertEquals("pkb_browser", values.get(PKB_CHANGED_VARIABLES));
        String serialized = PickleballProfiles.serializeRunProfile(values);
        assertFalse(serialized.contains("pkb_investigation_id"));
        assertFalse(serialized.contains("pkb_run_purpose"));
        assertFalse(serialized.contains("pkb_parent_run_id"));
        assertFalse(serialized.contains("pkb_baseline_run_id"));
        assertFalse(serialized.contains("pkb_changed_variables"));
    }

    @Test
    void inlineProfileCanBeSelected() {
        LinkedHashMap<String, String> values = baseValues();
        values.put("pkb_profile_smoke", "pkb_tags=@smoke; pkb_browser=chrome");
        values.put(PKB_PROFILE, "smoke");

        PickleballProfiles.apply(values);

        assertEquals("@smoke", values.get(PKB_TAGS));
        assertEquals("chrome", values.get(PKB_BROWSER));
        assertFalse(values.containsKey(PKB_GLUE));
    }

    @Test
    void selectedYamlProfilesComposeLeftToRightAndResolveReferences() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_PROFILE, "qa,browser_chrome");

        PickleballProfiles.apply(values);

        assertEquals("QA", values.get(PKB_ENVIRONMENT));
        assertEquals("chrome", values.get(PKB_BROWSER));
        assertEquals("@all and @qa", values.get(PKB_TAGS));
        assertEquals("com.example.pickleball", values.get(PKB_GLUE));
    }

    @Test
    void selectedProfileCanSupplyDirectRunProfile() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_PROFILE, "agent_direct");

        PickleballProfiles.Resolution resolution = PickleballProfiles.apply(values);

        assertTrue(resolution.direct());
        assertEquals("@all and @agent", values.get(PKB_TAGS));
        assertEquals("firefox", values.get(PKB_BROWSER));
        assertFalse(values.containsKey(PKB_GLUE));
        assertFalse(values.containsKey(PKB_PROFILE));
    }

    @Test
    void absentProfileUsesDeepCopiedDefaultProfile() {
        LinkedHashMap<String, String> values = baseValues();

        PickleballProfiles.apply(values);

        assertEquals("@all", values.get(PKB_TAGS));
        assertEquals("CHROME_HEADLESS", values.get(PKB_BROWSER));
        assertEquals("com.example.pickleball", values.get(PKB_GLUE));
    }

    @Test
    void literalAngleSyntaxThatIsNotAPkbReferenceIsPreserved() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_NAME, "(?<checkout>Checkout.*)");

        PickleballProfiles.apply(values);

        assertEquals("(?<checkout>Checkout.*)", values.get(PKB_NAME));
    }

    @Test
    void reportPortalAliasesAreGenericAndBidirectional() {
        assertEquals("pkb_rp_api_key", PickleballProfiles.reportPortalAliasKey("rp.api.key"));
        assertEquals("rp.api.key", PickleballProfiles.reportPortalCanonicalKey("pkb_rp_api_key"));
        assertEquals("pkb_rp_reporting_async", PickleballProfiles.reportPortalAliasKey("rp.reporting.async"));
        assertEquals("rp.reporting.async", PickleballProfiles.reportPortalCanonicalKey("pkb_rp_reporting_async"));
    }

    @Test
    void sensitiveValuesAreProtectedInSerializedRunProfile() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PKB_TAGS, "@smoke");
        values.put(PKB_RP_API_KEY, "do-not-print-me");

        String serialized = PickleballProfiles.serializeRunProfile(values);

        assertTrue(serialized.contains("pkb_tags=@smoke"));
        assertTrue(serialized.contains("pkb_rp_api_key=${protected:pkb_rp_api_key}"));
        assertFalse(serialized.contains("do-not-print-me"));
        assertTrue(SensitiveConfiguration.isSensitive("rp.api.key"));
        assertTrue(SensitiveConfiguration.isSensitive("pkb_rp_api_key"));
    }

    @Test
    void newSecretLikeRunVarsCannotBypassSerializedProfileProtection() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("pkb_service_token", "do-not-print-me-either");
        values.put(PKB_TAGS, "@smoke");

        String serialized = PickleballProfiles.serializeRunProfile(values);

        assertEquals("pkb_service_token=${protected:pkb_service_token}, pkb_tags=@smoke", serialized);
        assertFalse(serialized.contains("do-not-print-me-either"));
        assertTrue(SensitiveConfiguration.isSensitive("pkb_service_token"));
    }

    @Test
    void runProfileSerializationIsDeterministic() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put(PKB_TAGS, "@smoke");
        first.put(PKB_BROWSER, "chrome");
        Map<String, String> second = new LinkedHashMap<>();
        second.put(PKB_BROWSER, "chrome");
        second.put(PKB_TAGS, "@smoke");

        assertEquals(PickleballProfiles.serializeRunProfile(first), PickleballProfiles.serializeRunProfile(second));
        assertEquals("pkb_browser=chrome, pkb_tags=@smoke", PickleballProfiles.serializeRunProfile(first));
    }

    @Test
    void protectedDirectValueCanBeRestoredFromDefaultConfiguration() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_RP_API_KEY, "runtime-secret");
        values.put(PKB_RUN_PROFILE,
                "pkb_tags=@direct, pkb_rp_api_key=${protected:pkb_rp_api_key}");

        PickleballProfiles.apply(values);

        assertEquals("@direct", values.get(PKB_TAGS));
        assertEquals("runtime-secret", values.get(PKB_RP_API_KEY));
    }

    @Test
    void profileControlAndLineagePropertiesAreNotRunVars() {
        assertFalse(PKB_props.isRunVariableKey(PKB_PROFILE));
        assertFalse(PKB_props.isRunVariableKey(PKB_RUN_PROFILE));
        assertFalse(PKB_props.isRunVariableKey("pkb_options"));
        assertFalse(PKB_props.isRunVariableKey("pkb_profile_smoke"));
        assertFalse(PKB_props.isRunVariableKey(PKB_INVESTIGATION_ID));
        assertFalse(PKB_props.isRunVariableKey(PKB_RUN_PURPOSE));
        assertFalse(PKB_props.isRunVariableKey(PKB_PARENT_RUN_ID));
        assertFalse(PKB_props.isRunVariableKey(PKB_BASELINE_RUN_ID));
        assertFalse(PKB_props.isRunVariableKey(PKB_CHANGED_VARIABLES));
        assertTrue(PKB_props.isRunMetadataKey(PKB_INVESTIGATION_ID));
        assertTrue(PKB_props.isRunVariableKey(PKB_TAGS));
    }

    @Test
    void directRunProfileRejectsDiagnosticLineageAssignments() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_RUN_PROFILE, "pkb_tags=@direct, pkb_investigation_id=diag-214");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values));

        assertTrue(exception.getMessage().contains("pkb_investigation_id"));
        assertTrue(exception.getMessage().contains("separately"));
    }

    @Test
    void unknownSelectedProfileFailsClearly() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_PROFILE, "does_not_exist");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values));

        assertTrue(exception.getMessage().contains("Unknown Pickleball profile"));
        assertTrue(exception.getMessage().contains("default_profile"));
    }

    @Test
    void unresolvedPkbProfileReferenceFailsClearly() {
        LinkedHashMap<String, String> values = baseValues();
        values.put("pkb_profile_broken", "pkb_browser=<missing.pkb_browser>");
        values.put(PKB_PROFILE, "broken");

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> PickleballProfiles.apply(values));

        assertTrue(exception.getMessage().contains("missing.pkb_browser")
                || exception.getCause() != null);
    }

    @Test
    void assignmentParserSupportsQuotedSeparators() {
        Map<String, String> values = PickleballProfiles.parseAssignments(
                "pkb_tags=\"@a, @b\", pkb_browser=chrome; pkb_environment=QA");

        assertEquals("@a, @b", values.get(PKB_TAGS));
        assertEquals("chrome", values.get(PKB_BROWSER));
        assertEquals("QA", values.get(PKB_ENVIRONMENT));
    }

    private static LinkedHashMap<String, String> baseValues() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(PKB_GLUE, "com.example.pickleball");
        values.put("pkb_features", "classpath:features");
        values.put(PKB_TAGS, "@all");
        values.put(PKB_BROWSER, "CHROME_HEADLESS");
        return values;
    }
}
