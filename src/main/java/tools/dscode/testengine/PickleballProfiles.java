package tools.dscode.testengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.parallelutilities.ParallelCountEstimator;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static io.cucumber.core.options.Constants.FEATURES_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_NAME_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PARALLEL_CONFIG_FIXED_MAX_POOL_SIZE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PARALLEL_CONFIG_FIXED_PARALLELISM_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PARALLEL_CONFIG_STRATEGY_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PARALLEL_EXECUTION_ENABLED_PROPERTY_NAME;
import static tools.dscode.testengine.PKB_props.PKB_CALL_PATH;
import static tools.dscode.testengine.PKB_props.PKB_COMPONENT_PATH;
import static tools.dscode.testengine.PKB_props.PKB_CONFIG_PATH;
import static tools.dscode.testengine.PKB_props.PKB_DATA_PATH;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_GLUE;
import static tools.dscode.testengine.PKB_props.PKB_OPTIONS;
import static tools.dscode.testengine.PKB_props.PKB_PARALLEL;
import static tools.dscode.testengine.PKB_props.PKB_PREFIX;
import static tools.dscode.testengine.PKB_props.PKB_PROFILE;
import static tools.dscode.testengine.PKB_props.PKB_RUN_PROFILE;
import static tools.dscode.testengine.PKB_props.PKB_RUN_VARS;

/** Profile registry, RunVar composition, controlled-run input, and final template resolution. */
final class PickleballProfiles {
    static final String DEFAULT_PROFILE = "default_profile";
    static final String RUN_PROFILE = "run_profile";
    static final String INLINE_PROFILE_PREFIX = PKB_PROFILE + "_";

    private static final String[] PROFILE_RESOURCES = {
            "profiles.yaml",
            "profiles_local.yaml",
            "profiles_local2.yaml"
    };
    private static final Set<String> EXECUTION_CONTEXT_KEYS = Set.of(
            PKB_GLUE,
            PKB_FEATURES,
            PKB_DATA_PATH,
            PKB_CALL_PATH,
            PKB_COMPONENT_PATH,
            PKB_CONFIG_PATH
    );
    private static final Set<String> MANAGED_CUCUMBER_KEYS = Set.of(
            GLUE_PROPERTY_NAME,
            FEATURES_PROPERTY_NAME,
            FILTER_NAME_PROPERTY_NAME,
            FILTER_TAGS_PROPERTY_NAME
    );
    private static final Set<String> MANAGED_JUNIT_KEYS = Set.of(
            PARALLEL_EXECUTION_ENABLED_PROPERTY_NAME,
            PARALLEL_CONFIG_STRATEGY_PROPERTY_NAME,
            PARALLEL_CONFIG_FIXED_PARALLELISM_PROPERTY_NAME,
            PARALLEL_CONFIG_FIXED_MAX_POOL_SIZE_PROPERTY_NAME
    );
    private static final Pattern UNRESOLVED_REFERENCE = Pattern.compile("<([^<>\\s=]+)>");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static volatile ObjectNode profileRegistry = JSON.createObjectNode();
    private static volatile ObjectNode runProfile = JSON.createObjectNode();

    private PickleballProfiles() {
    }

    static Resolution apply(LinkedHashMap<String, String> values) {
        return apply(values, Map.of());
    }

    static Resolution apply(
            LinkedHashMap<String, String> values,
            Map<String, String> runtimeRunVarOverrides
    ) {
        rejectExternalRunProfile(values);
        String selectedProfiles = trimToNull(values.get(PKB_PROFILE));
        DirectInput topLevelDirect = directInputFromValues(values);

        ObjectNode registry = JSON.createObjectNode();
        loadProfileResources(registry);
        loadInlineProfiles(registry, values);

        ObjectNode defaultProfile = profileFromValues(values);
        ObjectNode runtimeOverrides = profileFromValues(runtimeRunVarOverrides);
        registry.set(DEFAULT_PROFILE, defaultProfile.deepCopy());

        ObjectNode composed;
        ObjectNode directReferenceContext = JSON.createObjectNode();
        boolean direct = topLevelDirect != null;

        if (topLevelDirect != null) {
            composed = JSON.createObjectNode();
            inheritExecutionContext(composed, defaultProfile);
            mergeDeep(composed, runtimeOverrides);
            mergeDeep(composed, topLevelDirect.profile());
        } else if (selectedProfiles == null) {
            composed = defaultProfile.deepCopy();
        } else {
            composed = composeSelectedProfiles(registry, selectedProfiles);
            JsonNode runVarsControl = composed.remove(PKB_RUN_VARS);
            if (runVarsControl != null && !runVarsControl.isNull()) {
                directReferenceContext = composed.deepCopy();
                ObjectNode controlledRunVars = directObject(runVarsControl, PKB_RUN_VARS);
                composed = JSON.createObjectNode();
                inheritExecutionContext(composed, defaultProfile);
                mergeDeep(composed, runtimeOverrides);
                mergeDeep(composed, controlledRunVars);
                direct = true;
            } else {
                inheritExecutionContext(composed, defaultProfile);
                mergeDeep(composed, runtimeOverrides);
            }
        }

        restoreProtectedReferences(composed, defaultProfile);
        ObjectNode resolved = resolveProfile(composed, registry, directReferenceContext);
        stampResolvedParallel(resolved);
        Map<String, String> finalRunVars = toRunVarMap(resolved);

        clearManagedValues(values);
        values.putAll(finalRunVars);
        if (!direct && selectedProfiles != null) {
            values.put(PKB_PROFILE, selectedProfiles);
        }

        profileRegistry = registry.deepCopy();
        runProfile = resolved.deepCopy();
        return new Resolution(direct, selectedProfiles, finalRunVars);
    }

    static ObjectNode profileRegistry() {
        return profileRegistry.deepCopy();
    }

    static ObjectNode runProfile() {
        return runProfile.deepCopy();
    }

    static LinkedHashMap<String, String> parseAssignments(String input) {
        return parseAssignments(input, false);
    }

    private static LinkedHashMap<String, String> parseAssignments(String input, boolean allowDirectControl) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (input == null || input.isBlank()) {
            return out;
        }
        for (String assignment : splitAssignments(input)) {
            int equals = assignment.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException(
                        "Invalid profile assignment '" + assignment + "'. Expected key=value.");
            }
            String key = normalizeProfileProperty(assignment.substring(0, equals).trim());
            String value = unquote(assignment.substring(equals + 1).trim());
            if (PKB_props.isRunMetadataKey(key)) {
                throw new IllegalArgumentException(
                        "Run metadata '" + key + "' must be supplied separately from Pickleball profiles.");
            }
            if (PKB_RUN_PROFILE.equals(key) || PKB_props.isRunProfileMemberKey(key)) {
                throw internalRunProfileInputError(key);
            }
            boolean supported = PKB_props.isRunVariableKey(key)
                    || allowDirectControl && PKB_RUN_VARS.equals(key);
            if (!supported) {
                throw new IllegalArgumentException(
                        "Profile assignment '" + key + "' is not a supported profile property.");
            }
            out.put(key, value);
        }
        return out;
    }

    static String serializeRunProfile(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.entrySet().stream()
                .filter(entry -> PKB_props.isRunVariableKey(entry.getKey()))
                .filter(entry -> entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (!out.isEmpty()) {
                        out.append(", ");
                    }
                    String serializedValue = entry.getValue().isEmpty()
                            ? ""
                            : SensitiveConfiguration.isSensitive(entry.getKey())
                                    ? SensitiveConfiguration.protectedReference(entry.getKey())
                                    : entry.getValue();
                    out.append(entry.getKey()).append('=').append(quoteIfNeeded(serializedValue));
                });
        return out.toString();
    }

    static String reportPortalAliasKey(String canonicalKey) {
        if (canonicalKey == null) {
            return null;
        }
        String normalized = canonicalKey.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("rp.")) {
            return null;
        }
        return PKB_PREFIX + "rp_" + normalized.substring(3).replace('.', '_');
    }

    static String reportPortalCanonicalKey(String aliasKey) {
        if (aliasKey == null) {
            return null;
        }
        String normalized = aliasKey.trim().toLowerCase(Locale.ROOT);
        String prefix = PKB_PREFIX + "rp_";
        if (!normalized.startsWith(prefix) || normalized.length() == prefix.length()) {
            return null;
        }
        return "rp." + normalized.substring(prefix.length()).replace('_', '.');
    }

    private static DirectInput directInputFromValues(Map<String, String> values) {
        String compactRunVars = trimToNull(values.get(PKB_RUN_VARS));
        ObjectNode expandedRunVars = expandedDirectFromValues(values);

        if (compactRunVars != null && !expandedRunVars.isEmpty()) {
            throw directFormConflict(PKB_RUN_VARS, PKB_props.PKB_RUN_VARS_PREFIX);
        }
        if (compactRunVars != null) {
            return new DirectInput(objectFromAssignments(compactRunVars, PKB_RUN_VARS, false));
        }
        return expandedRunVars.isEmpty() ? null : new DirectInput(expandedRunVars);
    }

    private static void rejectExternalRunProfile(Map<String, String> values) {
        for (String key : values.keySet()) {
            String normalized = PickleballRunner.normalizePkbKey(key);
            if (PKB_RUN_PROFILE.equals(normalized) || PKB_props.isRunProfileMemberKey(normalized)) {
                throw internalRunProfileInputError(key);
            }
        }
    }

    private static IllegalArgumentException internalRunProfileInputError(String key) {
        return new IllegalArgumentException(
                "'" + key + "' is an internal Pickleball property and cannot be supplied. "
                        + "Use '" + PKB_RUN_VARS + "' or '" + PKB_RUN_VARS
                        + ".<pkb_var>' to configure controlled RunVars.");
    }

    private static IllegalArgumentException directFormConflict(String compact, String expandedPrefix) {
        return new IllegalArgumentException(
                "Cannot combine compact '" + compact + "' with expanded '" + expandedPrefix
                        + "*' assignments. Use one direct RunVar form.");
    }

    private static ObjectNode expandedDirectFromValues(Map<String, String> values) {
        ObjectNode profile = JSON.createObjectNode();
        values.forEach((key, value) -> {
            String normalizedKey = PickleballRunner.normalizePkbKey(key);
            if (!PKB_props.isRunVarsMemberKey(normalizedKey)) {
                return;
            }
            String runVar = normalizeProfileProperty(
                    normalizedKey.substring(PKB_props.PKB_RUN_VARS_PREFIX.length()));
            if (PKB_props.isRunMetadataKey(runVar)) {
                throw new IllegalArgumentException(
                        "Run metadata '" + runVar + "' must be supplied separately from Pickleball profiles.");
            }
            if (!PKB_props.isRunVariableKey(runVar)) {
                throw new IllegalArgumentException(
                        "Expanded direct property '" + key + "' is not a Pickleball run variable.");
            }
            if (profile.has(runVar)) {
                throw new IllegalArgumentException(
                        "Expanded direct RunVars define '" + runVar + "' more than once.");
            }
            profile.put(runVar, value == null ? "" : value);
        });
        return profile;
    }

    private static ObjectNode directObject(JsonNode control, String source) {
        if (control.isTextual()) {
            String raw = trimToNull(control.textValue());
            return raw == null
                    ? JSON.createObjectNode()
                    : objectFromAssignments(raw, source, false);
        }
        if (control instanceof ObjectNode object) {
            return normalizeDirectRunObject(object, source);
        }
        throw new IllegalArgumentException(
                "Profile control '" + source + "' must be an assignment string or a map of RunVars.");
    }

    private static void inheritExecutionContext(ObjectNode composed, ObjectNode defaultProfile) {
        for (String key : EXECUTION_CONTEXT_KEYS) {
            if (composed.has(key)) {
                continue;
            }
            JsonNode inherited = defaultProfile.get(key);
            if (inherited != null) {
                composed.set(key, inherited.deepCopy());
            }
        }
    }


    private static void loadProfileResources(ObjectNode registry) {
        for (String resourceName : PROFILE_RESOURCES) {
            try {
                ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                if (classLoader == null) {
                    classLoader = PickleballProfiles.class.getClassLoader();
                }

                Enumeration<URL> resources = classLoader.getResources(resourceName);
                while (resources.hasMoreElements()) {
                    URL url = resources.nextElement();
                    try (InputStream input = url.openStream()) {
                        JsonNode parsed = YAML.readTree(input);

                        if (parsed == null || parsed.isNull() || parsed.isMissingNode()) {
                            continue;
                        }

                        if (!(parsed instanceof ObjectNode profiles)) {
                            throw new IllegalArgumentException(
                                    resourceName + " must contain a top-level map of profile names.");
                        }

                        mergeProfileDocument(registry, profiles, resourceName);
                    }
                }
            } catch (Exception exception) {
                throw new IllegalArgumentException(
                        "Failed loading Pickleball profile resource '" + resourceName + "'.", exception);
            }
        }
    }

    private static void mergeProfileDocument(ObjectNode registry, ObjectNode document, String source) {
        Iterator<Map.Entry<String, JsonNode>> fields = document.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = normalizeProfileName(field.getKey());
            if (DEFAULT_PROFILE.equals(name) || RUN_PROFILE.equals(name)) {
                throw new IllegalArgumentException(
                        "Profile name '" + name + "' is reserved and cannot be defined in " + source + ".");
            }
            if (!(field.getValue() instanceof ObjectNode profile)) {
                throw new IllegalArgumentException(
                        "Profile '" + name + "' in " + source + " must be a map of run variables.");
            }
            ObjectNode normalized = normalizeProfileObject(profile, name);
            ObjectNode target = registry.has(name) && registry.get(name).isObject()
                    ? (ObjectNode) registry.get(name)
                    : JSON.createObjectNode();
            mergeDeep(target, normalized);
            registry.set(name, target);
        }
    }

    private static void loadInlineProfiles(ObjectNode registry, Map<String, String> values) {
        values.forEach((key, value) -> {
            String normalizedKey = PickleballRunner.normalizePkbKey(key);
            if (normalizedKey == null || !normalizedKey.startsWith(INLINE_PROFILE_PREFIX)) {
                return;
            }
            String name = normalizeProfileName(normalizedKey.substring(INLINE_PROFILE_PREFIX.length()));
            if (name.isBlank()) {
                throw new IllegalArgumentException(
                        "Inline profile property requires a profile name after '" + INLINE_PROFILE_PREFIX + "'.");
            }
            if (DEFAULT_PROFILE.equals(name) || RUN_PROFILE.equals(name)) {
                throw new IllegalArgumentException("Inline profile name '" + name + "' is reserved.");
            }
            ObjectNode parsed = objectFromAssignments(value, normalizedKey, true);
            ObjectNode target = registry.has(name) && registry.get(name).isObject()
                    ? (ObjectNode) registry.get(name)
                    : JSON.createObjectNode();
            mergeDeep(target, parsed);
            registry.set(name, target);
        });
    }

    private static ObjectNode profileFromValues(Map<String, String> values) {
        ObjectNode profile = JSON.createObjectNode();
        values.forEach((key, value) -> {
            String normalized = PickleballRunner.normalizePkbKey(key);
            if (PKB_props.isRunVariableKey(normalized) && value != null) {
                profile.put(normalized, value);
            }
        });
        return profile;
    }

    private static ObjectNode composeSelectedProfiles(ObjectNode registry, String selectedProfiles) {
        ObjectNode composed = JSON.createObjectNode();
        List<String> names = splitProfileNames(selectedProfiles);
        if (names.isEmpty()) {
            return ((ObjectNode) registry.get(DEFAULT_PROFILE)).deepCopy();
        }
        for (String name : names) {
            JsonNode profile = registry.get(name);
            if (!(profile instanceof ObjectNode objectProfile)) {
                List<String> available = new ArrayList<>();
                registry.fieldNames().forEachRemaining(available::add);
                Collections.sort(available);
                throw new IllegalArgumentException(
                        "Unknown Pickleball profile '" + name + "'. Available profiles: " + available);
            }
            mergeDeep(composed, objectProfile);
        }
        return composed;
    }

    private static ObjectNode resolveProfile(
            ObjectNode profile, ObjectNode registry, ObjectNode directReferenceContext
    ) {
        ObjectNode current = profile.deepCopy();
        Set<String> seen = new java.util.HashSet<>();
        for (int pass = 0; pass < 64; pass++) {
            String signature = current.toString();
            if (!seen.add(signature)) {
                throw new IllegalArgumentException(
                        "Cyclic Pickleball profile template resolution detected while resolving " + signature);
            }

            ObjectNode resolutionRoot = registry.deepCopy();
            if (directReferenceContext.isEmpty()) {
                resolutionRoot.set(RUN_PROFILE, current.deepCopy());
                current.fields().forEachRemaining(
                        entry -> resolutionRoot.set(entry.getKey(), entry.getValue().deepCopy()));
            } else {
                resolutionRoot.set(RUN_PROFILE, directReferenceContext.deepCopy());
                directReferenceContext.fields().forEachRemaining(
                        entry -> resolutionRoot.set(entry.getKey(), entry.getValue().deepCopy()));
            }

            ProfileTemplateResolver resolver = new ProfileTemplateResolver(resolutionRoot);
            ObjectNode next = resolveObject(current, resolver);
            if (next.equals(current)) {
                ensureNoUnresolvedReferences(next);
                return next;
            }
            current = next;
        }
        throw new IllegalArgumentException("Pickleball profile template resolution exceeded 64 passes.");
    }

    private static ObjectNode resolveObject(ObjectNode input, ProfileTemplateResolver resolver) {
        ObjectNode output = JSON.createObjectNode();
        input.fields().forEachRemaining(entry -> output.set(entry.getKey(), resolveNode(entry.getValue(), resolver)));
        return output;
    }

    private static JsonNode resolveNode(JsonNode node, ProfileTemplateResolver resolver) {
        if (node == null || node.isNull()) {
            return JSON.getNodeFactory().nullNode();
        }
        if (node.isTextual()) {
            Object resolved = resolver.resolveWholeValue(node.textValue());
            return JSON.valueToTree(resolved);
        }
        if (node.isObject()) {
            return resolveObject((ObjectNode) node, resolver);
        }
        if (node.isArray()) {
            var array = JSON.createArrayNode();
            node.forEach(value -> array.add(resolveNode(value, resolver)));
            return array;
        }
        return node.deepCopy();
    }

    private static void restoreProtectedReferences(ObjectNode profile, ObjectNode defaultProfile) {
        List<String> keys = new ArrayList<>();
        profile.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            JsonNode node = profile.get(key);
            if (node == null || !node.isTextual()) {
                continue;
            }
            String protectedKey = SensitiveConfiguration.protectedKey(node.textValue());
            if (protectedKey == null) {
                continue;
            }
            JsonNode protectedValue = defaultProfile.get(protectedKey);
            if (protectedValue == null || protectedValue.isNull() || protectedValue.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "Controlled RunVars require protected value '" + protectedKey
                                + "'. Supply it through normal secure configuration or replace the protected reference explicitly.");
            }
            profile.set(key, protectedValue.deepCopy());
        }
    }

    private static void ensureNoUnresolvedReferences(ObjectNode profile) {
        profile.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isTextual()) {
                return;
            }
            String unresolved = unresolvedPkbReference(value.textValue());
            if (unresolved != null) {
                throw new IllegalArgumentException(
                        "Unresolved Pickleball profile reference '" + unresolved
                                + "' in '" + entry.getKey() + "': " + value.textValue());
            }
        });
    }

    private static String unresolvedPkbReference(String value) {
        var matcher = UNRESOLVED_REFERENCE.matcher(value == null ? "" : value);
        while (matcher.find()) {
            String reference = matcher.group(1);
            String normalized = reference.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("configs.") || normalized.startsWith("config:")) {
                throw new IllegalArgumentException(
                        "Runtime config mappings cannot resolve Pickleball profiles or pkb_runvars: <"
                                + reference + ">");
            }
            if (normalized.startsWith(PKB_PREFIX)
                    || normalized.contains("." + PKB_PREFIX)
                    || normalized.startsWith(DEFAULT_PROFILE + ".")
                    || normalized.startsWith(RUN_PROFILE + ".")) {
                return reference;
            }
        }
        return null;
    }

    private static void stampResolvedParallel(ObjectNode resolved) {
        if (resolved == null || !resolved.has(PKB_PARALLEL)) {
            return;
        }
        JsonNode configured = resolved.get(PKB_PARALLEL);
        if (configured == null || configured.isNull()) {
            return;
        }
        String text = configured.asText();
        if (text == null || text.isBlank()) {
            return;
        }
        resolved.put(PKB_PARALLEL, Integer.toString(ParallelCountEstimator.resolve(text)));
    }

    private static Map<String, String> toRunVarMap(ObjectNode profile) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        profile.fields().forEachRemaining(entry -> {
            String key = normalizeProfileProperty(entry.getKey());
            if (!PKB_props.isRunVariableKey(key)) {
                throw new IllegalArgumentException("Profile property '" + key + "' is not a Pickleball run variable.");
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                out.put(key, "");
                return;
            }
            if (value.isContainerNode()) {
                throw new IllegalArgumentException(
                        "Profile run variable '" + key + "' must resolve to a scalar value, not " + value.getNodeType() + ".");
            }
            out.put(key, value.asText());
        });
        return out;
    }

    private static ObjectNode objectFromAssignments(String input, String source, boolean allowDirectControl) {
        ObjectNode out = JSON.createObjectNode();
        try {
            parseAssignments(input, allowDirectControl).forEach(out::put);
            return out;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Invalid profile assignments in '" + source + "': " + exception.getMessage(), exception);
        }
    }

    private static ObjectNode normalizeProfileObject(ObjectNode profile, String profileName) {
        ObjectNode normalized = JSON.createObjectNode();
        profile.fields().forEachRemaining(entry -> {
            String key = normalizeProfileProperty(entry.getKey());
            if (PKB_props.isRunMetadataKey(key)) {
                throw new IllegalArgumentException(
                        "Run metadata '" + key + "' must be supplied separately from Pickleball profiles.");
            }
            if (PKB_RUN_PROFILE.equals(key) || PKB_props.isRunProfileMemberKey(key)) {
                throw internalRunProfileInputError(entry.getKey());
            }
            if (PKB_RUN_VARS.equals(key)) {
                JsonNode control = entry.getValue();
                if (control instanceof ObjectNode objectControl) {
                    normalized.set(key, normalizeDirectRunObject(objectControl, profileName));
                } else if (control == null || control.isNull() || control.isTextual()) {
                    normalized.set(key, control == null ? JSON.getNodeFactory().nullNode() : control.deepCopy());
                } else {
                    throw new IllegalArgumentException(
                            "Profile '" + profileName + "' control '" + key
                                    + "' must be an assignment string or a map of RunVars.");
                }
                return;
            }
            if (!PKB_props.isRunVariableKey(key)) {
                throw new IllegalArgumentException(
                        "Profile '" + profileName + "' contains unsupported property '" + entry.getKey() + "'.");
            }
            normalized.set(key, entry.getValue().deepCopy());
        });
        return normalized;
    }

    private static ObjectNode normalizeDirectRunObject(ObjectNode profile, String source) {
        ObjectNode normalized = JSON.createObjectNode();
        profile.fields().forEachRemaining(entry -> {
            String key = normalizeProfileProperty(entry.getKey());
            if (PKB_props.isRunMetadataKey(key)) {
                throw new IllegalArgumentException(
                        "Run metadata '" + key + "' must be supplied separately from Pickleball profiles.");
            }
            if (!PKB_props.isRunVariableKey(key)) {
                throw new IllegalArgumentException(
                        "Direct RunVars in '" + source + "' contain unsupported property '" + entry.getKey() + "'.");
            }
            if (entry.getValue() != null && entry.getValue().isContainerNode()) {
                throw new IllegalArgumentException(
                        "Direct RunVar '" + key + "' must be a scalar value.");
            }
            normalized.set(key, entry.getValue() == null ? JSON.getNodeFactory().nullNode() : entry.getValue().deepCopy());
        });
        return normalized;
    }

    private static String normalizeProfileProperty(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Profile run variable name cannot be blank.");
        }
        String trimmed = key.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("rp.")) {
            return reportPortalAliasKey(trimmed);
        }
        return PickleballRunner.normalizePkbKey(trimmed);
    }

    private static String normalizeProfileName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> splitProfileNames(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String name = normalizeProfileName(part);
            if (!name.isBlank()) {
                out.add(name);
            }
        }
        return out;
    }

    private static List<String> splitAssignments(String input) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean seenEquals = false;
        boolean valueStarted = false;
        boolean quotedValueClosed = false;
        char quote = 0;
        boolean escaped = false;
        int templateDepth = 0;

        for (int index = 0; index < input.length(); index++) {
            char ch = input.charAt(index);
            if (quote != 0) {
                current.append(ch);
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == quote) { quote = 0; quotedValueClosed = true; }
                continue;
            }
            if (quotedValueClosed) {
                if (ch == ',' || ch == ';') {
                    addAssignment(out, current);
                    seenEquals = false; valueStarted = false; quotedValueClosed = false; templateDepth = 0;
                    continue;
                }
                if (!Character.isWhitespace(ch)) {
                    throw new IllegalArgumentException(
                            "Unexpected text after quoted profile value near '" + current + ch + "'.");
                }
                current.append(ch);
                continue;
            }
            if (!seenEquals) {
                if (ch == ',' || ch == ';') {
                    addAssignment(out, current);
                    seenEquals = false; valueStarted = false; templateDepth = 0;
                    continue;
                }
                current.append(ch);
                if (ch == '=') seenEquals = true;
                continue;
            }
            if (!valueStarted) {
                if (ch == ',' || ch == ';') {
                    addAssignment(out, current);
                    seenEquals = false;
                    valueStarted = false;
                    templateDepth = 0;
                    continue;
                }
                current.append(ch);
                if (Character.isWhitespace(ch)) continue;
                valueStarted = true;
                if (ch == '\'' || ch == '"') quote = ch;
                else if (ch == '<' && hasClosingAngle(input, index + 1)) templateDepth = 1;
                continue;
            }
            if (ch == '<' && hasClosingAngle(input, index + 1)) {
                templateDepth++; current.append(ch); continue;
            }
            if (ch == '>' && templateDepth > 0) {
                templateDepth--; current.append(ch); continue;
            }
            if ((ch == ',' || ch == ';') && templateDepth == 0) {
                addAssignment(out, current);
                seenEquals = false; valueStarted = false; templateDepth = 0;
                continue;
            }
            current.append(ch);
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Unterminated quoted profile value in '" + current + "'.");
        }
        addAssignment(out, current);
        return out;
    }

    private static boolean hasClosingAngle(String input, int fromIndex) {
        return input.indexOf('>', fromIndex) >= 0;
    }

    private static void addAssignment(List<String> out, StringBuilder current) {
        String value = current.toString().trim();
        current.setLength(0);
        if (!value.isEmpty()) out.add(value);
    }

    private static String unquote(String value) {
        if (value.length() < 2) return value;
        char quote = value.charAt(0);
        if ((quote != '"' && quote != '\'') || value.charAt(value.length() - 1) != quote) return value;
        String inner = value.substring(1, value.length() - 1);
        StringBuilder out = new StringBuilder(inner.length());
        for (int index = 0; index < inner.length(); index++) {
            char ch = inner.charAt(index);
            if (ch == '\\' && index + 1 < inner.length()) {
                char next = inner.charAt(index + 1);
                if (next == quote || next == '\\') {
                    out.append(next); index++; continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static String quoteIfNeeded(String value) {
        if (value == null) return "";
        boolean startsWithQuote = !value.isEmpty() && (value.charAt(0) == '"' || value.charAt(0) == '\'');
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf(';') >= 0
                || value.indexOf('"') >= 0
                || startsWithQuote
                || !value.equals(value.trim());
        if (!quote) return value;
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static void mergeDeep(ObjectNode target, ObjectNode source) {
        source.fields().forEachRemaining(entry -> {
            JsonNode existing = target.get(entry.getKey());
            JsonNode incoming = entry.getValue();
            if (PKB_RUN_VARS.equals(entry.getKey())) {
                target.set(entry.getKey(), incoming.deepCopy());
            } else if (existing instanceof ObjectNode existingObject && incoming instanceof ObjectNode incomingObject) {
                mergeDeep(existingObject, incomingObject);
            } else {
                target.set(entry.getKey(), incoming.deepCopy());
            }
        });
    }

    private static void clearManagedValues(LinkedHashMap<String, String> values) {
        values.entrySet().removeIf(entry -> {
            String key = entry.getKey();
            if (key == null) return false;
            String normalized = PickleballRunner.normalizePkbKey(key);
            if (PKB_props.isRunMetadataKey(normalized)) return false;
            return normalized.startsWith(PKB_PREFIX)
                    || MANAGED_CUCUMBER_KEYS.contains(key)
                    || MANAGED_JUNIT_KEYS.contains(key)
                    || key.toLowerCase(Locale.ROOT).startsWith("rp.");
        });
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record Resolution(boolean direct, String selectedProfiles, Map<String, String> runVars) {
    }

    private record DirectInput(ObjectNode profile) {
    }

    private static final class ProfileTemplateResolver extends MappingProcessor {
        private final NodeMap nodeMap;

        private ProfileTemplateResolver(ObjectNode root) {
            this(new NodeMap(MapConfigurations.MapType.DEFAULT, root.deepCopy()));
        }

        private ProfileTemplateResolver(NodeMap nodeMap) {
            super(nodeMap);
            this.nodeMap = nodeMap;
        }

        @Override
        public Object get(String key) {
            String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (normalizedKey.startsWith("config:") || normalizedKey.startsWith("configs.")) {
                return null;
            }
            Object direct = nodeMap.get(key);
            if (direct == null && key != null) {
                int dot = key.indexOf('.');
                if (dot > 0) {
                    String normalizedProfile = key.substring(0, dot).toLowerCase(Locale.ROOT) + key.substring(dot);
                    direct = nodeMap.get(normalizedProfile);
                }
            }
            return direct != null ? direct : super.get(key);
        }
    }
}
