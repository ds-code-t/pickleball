package tools.dscode.testengine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.testengine.PKB_props.PKB_BROWSER;
import static tools.dscode.testengine.PKB_props.PKB_CALL_PATH;
import static tools.dscode.testengine.PKB_props.PKB_COMPONENT_PATH;
import static tools.dscode.testengine.PKB_props.PKB_CONFIG_PATH;
import static tools.dscode.testengine.PKB_props.PKB_DATA_PATH;
import static tools.dscode.testengine.PKB_props.PKB_ENVIRONMENT;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_GLUE;
import static tools.dscode.testengine.PKB_props.PKB_INVESTIGATION_ID;
import static tools.dscode.testengine.PKB_props.PKB_OPTIONS;
import static tools.dscode.testengine.PKB_props.PKB_OVERRIDE_RUN_VARS;
import static tools.dscode.testengine.PKB_props.PKB_OVERRIDE_RUN_VARS_PREFIX;
import static tools.dscode.testengine.PKB_props.PKB_PARALLEL;
import static tools.dscode.testengine.PKB_props.PKB_PROFILE;
import static tools.dscode.testengine.PKB_props.PKB_RP_API_KEY;
import static tools.dscode.testengine.PKB_props.PKB_RUN_PROFILE;
import static tools.dscode.testengine.PKB_props.PKB_RUN_PROFILE_PREFIX;
import static tools.dscode.testengine.PKB_props.PKB_RUN_VARS;
import static tools.dscode.testengine.PKB_props.PKB_RUN_VARS_PREFIX;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

public final class RunVarOverrideChecks {

    @Test
    void sealedOffLeavesCurrentInheritanceUnchanged() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_RUN_VARS, "pkb_tags=@direct, pkb_browser=firefox");

        PickleballProfiles.Resolution resolution = PickleballProfiles.apply(values);

        assertFalse(resolution.sealed());
        assertTrue(resolution.direct());
        assertEquals("@direct", values.get(PKB_TAGS));
        assertEquals("firefox", values.get(PKB_BROWSER));
        assertEquals("com.example.pickleball", values.get(PKB_GLUE));
        assertEquals("classpath:features", values.get(PKB_FEATURES));
        assertFalse(values.containsKey(PKB_ENVIRONMENT));
    }

    @Test
    void sealedCompleteMapIgnoresAmbientFileAndProfileValues() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_ENVIRONMENT, "QA");
        values.put(PKB_TAGS, "@all");
        values.put(PKB_BROWSER, "CHROME_HEADLESS");
        LinkedHashMap<String, String> bag = sealedBag();
        bag.put(PKB_BROWSER, "firefox");
        bag.put(PKB_TAGS, "@sealed");
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(bag));

        PickleballProfiles.Resolution resolution = PickleballProfiles.apply(
                values,
                Map.of(PKB_BROWSER, "chrome", PKB_ENVIRONMENT, "PROD")
        );

        assertTrue(resolution.sealed());
        assertFalse(resolution.direct());
        assertEquals("firefox", values.get(PKB_BROWSER));
        assertEquals("@sealed", values.get(PKB_TAGS));
        assertEquals("com.example.pickleball", values.get(PKB_GLUE));
        assertFalse(values.containsKey(PKB_ENVIRONMENT));
        assertEquals(PickleballProfiles.serializeRunProfile(bag), PickleballProfiles.serializeRunProfile(values));
        resolution.provenance().values().forEach(source -> assertEquals("override", source));
        assertFalse(PickleballProfiles.serializeRunProfile(values).contains(PKB_OVERRIDE_RUN_VARS));
    }

    @Test
    void sealedMissingContextKeyIsAnError() {
        LinkedHashMap<String, String> bag = sealedBag();
        bag.remove(PKB_GLUE);
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(bag));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values)
        );
        assertTrue(exception.getMessage().contains(PKB_GLUE));
        assertTrue(exception.getMessage().contains("missing required execution-context"));
    }

    @Test
    void sealedBlankFeaturesTombstoneIsPreserved() {
        LinkedHashMap<String, String> bag = sealedBag();
        bag.put(PKB_FEATURES, "");
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(bag));

        PickleballProfiles.apply(values);

        assertEquals("", values.get(PKB_FEATURES));
        assertTrue(PickleballProfiles.serializeRunProfile(values).contains("pkb_features="));
        assertEquals("classpath:features", baseValues().get(PKB_FEATURES));
    }

    @Test
    void sealedPlusRunVarsIsAnError() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(sealedBag()));
        values.put(PKB_RUN_VARS, "pkb_tags=@direct");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values)
        );
        assertTrue(exception.getMessage().contains(PKB_OVERRIDE_RUN_VARS));
        assertTrue(exception.getMessage().contains(PKB_RUN_VARS));
    }

    @Test
    void sealedPlusProfileIsAnError() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(sealedBag()));
        values.put(PKB_PROFILE, "qa");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values)
        );
        assertTrue(exception.getMessage().contains(PKB_OVERRIDE_RUN_VARS));
        assertTrue(exception.getMessage().contains(PKB_PROFILE));
    }

    @Test
    void compactAndExpandedOverrideCannotBeMixed() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(sealedBag()));
        values.put(PKB_OVERRIDE_RUN_VARS_PREFIX + PKB_BROWSER, "firefox");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values)
        );
        assertTrue(exception.getMessage().contains("Cannot combine compact"));
        assertTrue(exception.getMessage().contains(PKB_OVERRIDE_RUN_VARS));
    }

    @Test
    void externalRunProfileStillRejectedWithSealedPresent() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(sealedBag()));
        values.put(PKB_RUN_PROFILE, "pkb_tags=@legacy");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values)
        );
        assertTrue(exception.getMessage().contains("internal Pickleball property"));
    }

    @Test
    void expandedExternalRunProfileStillRejected() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_RUN_PROFILE_PREFIX + PKB_BROWSER, "firefox");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values)
        );
        assertTrue(exception.getMessage().contains("internal Pickleball property"));
    }

    @Test
    void dryRunDoesNotStartScenariosAndMatchesSealedSerializeExceptAutoParallelInput() {
        LinkedHashMap<String, String> bag = sealedBag();
        bag.put(PKB_PARALLEL, "auto");
        LinkedHashMap<String, String> previewValues = new LinkedHashMap<>();
        previewValues.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(bag));

        PKB_props.ResolvedRunVars preview = PKB_props.resolveRunVars(previewValues);
        assertTrue(preview.sealed());
        assertFalse("auto".equalsIgnoreCase(preview.runVars().get(PKB_PARALLEL)));

        LinkedHashMap<String, String> applyValues = new LinkedHashMap<>();
        applyValues.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(bag));
        PickleballProfiles.apply(applyValues);

        assertEquals(preview.runProfile(), PickleballProfiles.serializeRunProfile(applyValues));
        assertEquals(preview.fingerprint(), PKB_props.runProfileFingerprint(applyValues));
        assertTrue(Integer.parseInt(preview.runVars().get(PKB_PARALLEL)) > 0);
    }

    @Test
    void sealedSecretsStayProtectedInSerializedProfile() {
        LinkedHashMap<String, String> bag = sealedBag();
        bag.put(PKB_RP_API_KEY, "${protected:pkb_rp_api_key}");
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_RP_API_KEY, "runtime-secret");
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(bag));

        PickleballProfiles.apply(values);
        String serialized = PickleballProfiles.serializeRunProfile(values);
        assertTrue(serialized.contains("pkb_rp_api_key=${protected:pkb_rp_api_key}"));
        assertFalse(serialized.contains("runtime-secret"));
        assertEquals("runtime-secret", values.get(PKB_RP_API_KEY));
    }

    @Test
    void sealedExpandedMembersActivateWithoutCompact() {
        LinkedHashMap<String, String> bag = sealedBag();
        LinkedHashMap<String, String> values = baseValues();
        bag.forEach((key, value) -> values.put(PKB_OVERRIDE_RUN_VARS_PREFIX + key, value));
        values.put(PKB_BROWSER, "CHROME_HEADLESS");

        PickleballProfiles.Resolution resolution = PickleballProfiles.apply(values);

        assertTrue(resolution.sealed());
        assertEquals("firefox", values.get(PKB_BROWSER));
        assertFalse(values.keySet().stream().anyMatch(PKB_props::isOverrideRunVarsMemberKey));
    }

    @Test
    void sealedRejectsProfileTemplates() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, "pkb_glue=com.example.pickleball, pkb_features=classpath:features, "
                + "pkb_datapath=src/test/resources/data, pkb_callpath=src/test/resources/calls, "
                + "pkb_componentpath=src/test/resources/component, pkb_configpath=configs, "
                + "pkb_tags=<default_profile.pkb_tags>");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values)
        );
        assertTrue(exception.getMessage().contains("default_profile.pkb_tags")
                || exception.getMessage().contains("Unresolved"));
    }

    @Test
    void overrideControlsAreNotRunVars() {
        assertFalse(PKB_props.isRunVariableKey(PKB_OVERRIDE_RUN_VARS));
        assertFalse(PKB_props.isRunVariableKey(PKB_OVERRIDE_RUN_VARS_PREFIX + PKB_BROWSER));
        assertTrue(PKB_props.isOverrideRunVarsMemberKey(PKB_OVERRIDE_RUN_VARS_PREFIX + PKB_BROWSER));
    }

    @Test
    void omittedOptionalRunVarsStayAbsent() {
        LinkedHashMap<String, String> bag = sealedBag();
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(bag));

        PickleballProfiles.apply(values);

        assertFalse(values.containsKey(PKB_PARALLEL));
        assertFalse(values.containsKey(PKB_ENVIRONMENT));
    }

    @Test
    void dryRunProvenanceUsesPropertiesAndJvmTokens() {
        LinkedHashMap<String, String> values = baseValues();
        PKB_props.ResolvedRunVars preview = PKB_props.resolveRunVars(
                values,
                Map.of(PKB_BROWSER, "firefox")
        );

        assertFalse(preview.sealed());
        assertEquals("properties", preview.provenance().get(PKB_GLUE));
        assertEquals("properties", preview.provenance().get(PKB_TAGS));
        assertEquals("jvm", preview.provenance().get(PKB_BROWSER));
        assertEquals("firefox", preview.runVars().get(PKB_BROWSER));
    }

    @Test
    void applyDistinguishesDefaultFromPropertiesProvenance() {
        LinkedHashMap<String, String> values = baseValues();
        Map<String, String> defaults = Map.of(PKB_GLUE, "com.example.defaults", PKB_ENVIRONMENT, "DEV");
        Map<String, String> properties = Map.of(PKB_TAGS, "@all", PKB_BROWSER, "CHROME_HEADLESS");

        PickleballProfiles.Resolution resolution = PickleballProfiles.apply(
                values,
                Map.of(PKB_BROWSER, "firefox"),
                defaults,
                properties
        );

        assertEquals("default", resolution.provenance().get(PKB_GLUE));
        assertEquals("properties", resolution.provenance().get(PKB_TAGS));
        assertEquals("jvm", resolution.provenance().get(PKB_BROWSER));
        assertEquals("firefox", values.get(PKB_BROWSER));
    }

    @Test
    void dryRunDirectRunVarsProvenanceOverwritesProperties() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_RUN_VARS, "pkb_tags=@direct");

        PKB_props.ResolvedRunVars preview = PKB_props.resolveRunVars(
                values,
                Map.of(PKB_BROWSER, "firefox")
        );

        assertEquals("runvars", preview.provenance().get(PKB_TAGS));
        assertEquals("inherited-context", preview.provenance().get(PKB_GLUE));
        assertEquals("jvm", preview.provenance().get(PKB_BROWSER));
        assertEquals("@direct", preview.runVars().get(PKB_TAGS));
    }

    @Test
    void cucumberCliProjectionIsSuppressedWhenSealedOrDirect() {
        assertTrue(PickleballRunner.suppressCucumberCliProjection(true, false));
        assertTrue(PickleballRunner.suppressCucumberCliProjection(false, true));
        assertTrue(PickleballRunner.suppressCucumberCliProjection(true, true));
        assertFalse(PickleballRunner.suppressCucumberCliProjection(false, false));
    }

    @Test
    void sealedPlusExpandedRunVarsIsAnError() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(sealedBag()));
        values.put(PKB_RUN_VARS_PREFIX + PKB_TAGS, "@direct");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values)
        );
        assertTrue(exception.getMessage().contains(PKB_OVERRIDE_RUN_VARS));
        assertTrue(exception.getMessage().contains(PKB_RUN_VARS));
    }

    @Test
    void sealedRejectsLineageAndOptionsInPayload() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, "pkb_glue=com.example.pickleball, pkb_features=classpath:features, "
                + "pkb_datapath=src/test/resources/data, pkb_callpath=src/test/resources/calls, "
                + "pkb_componentpath=src/test/resources/component, pkb_configpath=configs, "
                + PKB_INVESTIGATION_ID + "=inv-1");

        IllegalArgumentException lineage = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(values)
        );
        assertTrue(lineage.getMessage().contains(PKB_INVESTIGATION_ID)
                || lineage.getMessage().contains("Run metadata"));

        LinkedHashMap<String, String> optionsValues = baseValues();
        optionsValues.put(PKB_OVERRIDE_RUN_VARS, "pkb_glue=com.example.pickleball, pkb_features=classpath:features, "
                + "pkb_datapath=src/test/resources/data, pkb_callpath=src/test/resources/calls, "
                + "pkb_componentpath=src/test/resources/component, pkb_configpath=configs, "
                + PKB_OPTIONS + "=--tags @all");
        IllegalArgumentException options = assertThrows(
                IllegalArgumentException.class,
                () -> PickleballProfiles.apply(optionsValues)
        );
        assertTrue(options.getMessage().contains(PKB_OPTIONS)
                || options.getMessage().contains("not a supported profile property")
                || options.getMessage().contains("cannot contain"));
    }

    @Test
    void blankCompactOverrideLeavesSealedOff() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, "   ");
        values.put(PKB_RUN_VARS, "pkb_tags=@direct");

        PickleballProfiles.Resolution resolution = PickleballProfiles.apply(values);

        assertFalse(resolution.sealed());
        assertTrue(resolution.direct());
        assertEquals("@direct", values.get(PKB_TAGS));
    }

    @Test
    void fileSourcedProfileAndRunVarsAreStrippedWhenSealedIsPresent() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(sealedBag()));
        values.put(PKB_PROFILE, "qa");
        values.put(PKB_RUN_VARS, "pkb_tags=@direct");

        PickleballRunner.stripNonInvocationControlsWhenSealed(values, false, false);

        assertFalse(values.containsKey(PKB_PROFILE));
        assertFalse(values.containsKey(PKB_RUN_VARS));
        PickleballProfiles.Resolution resolution = PickleballProfiles.apply(values);
        assertTrue(resolution.sealed());
        assertEquals("@sealed", values.get(PKB_TAGS));
    }

    @Test
    void invocationLevelProfileStaysSoApplyCanFailClosed() {
        LinkedHashMap<String, String> values = baseValues();
        values.put(PKB_OVERRIDE_RUN_VARS, PickleballProfiles.serializeRunProfile(sealedBag()));
        values.put(PKB_PROFILE, "qa");

        PickleballRunner.stripNonInvocationControlsWhenSealed(values, true, false);

        assertEquals("qa", values.get(PKB_PROFILE));
        assertThrows(IllegalArgumentException.class, () -> PickleballProfiles.apply(values));
    }

    private static LinkedHashMap<String, String> sealedBag() {
        LinkedHashMap<String, String> bag = new LinkedHashMap<>();
        bag.put(PKB_GLUE, "com.example.pickleball");
        bag.put(PKB_FEATURES, "classpath:features");
        bag.put(PKB_DATA_PATH, "src/test/resources/data");
        bag.put(PKB_CALL_PATH, "src/test/resources/calls");
        bag.put(PKB_COMPONENT_PATH, "src/test/resources/component");
        bag.put(PKB_CONFIG_PATH, "configs");
        bag.put(PKB_BROWSER, "firefox");
        bag.put(PKB_TAGS, "@sealed");
        return bag;
    }

    private static LinkedHashMap<String, String> baseValues() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(PKB_GLUE, "com.example.pickleball");
        values.put(PKB_FEATURES, "classpath:features");
        values.put(PKB_DATA_PATH, "src/test/resources/data");
        values.put(PKB_CALL_PATH, "src/test/resources/calls");
        values.put(PKB_COMPONENT_PATH, "src/test/resources/component");
        values.put(PKB_CONFIG_PATH, "configs");
        values.put(PKB_TAGS, "@all");
        values.put(PKB_BROWSER, "CHROME_HEADLESS");
        values.put(PKB_ENVIRONMENT, "QA");
        return values;
    }
}
