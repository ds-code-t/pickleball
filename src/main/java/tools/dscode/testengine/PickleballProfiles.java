package tools.dscode.testengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;

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
import static tools.dscode.testengine.PKB_props.PKB_OPTIONS;
import static tools.dscode.testengine.PKB_props.PKB_PREFIX;
import static tools.dscode.testengine.PKB_props.PKB_PROFILE;
import static tools.dscode.testengine.PKB_props.PKB_RUN_PROFILE;

/** Profile registry, composition, direct-run override, and final template resolution. */
final class PickleballProfiles {
    static final String DEFAULT_PROFILE = "default_profile";
    static final String RUN_PROFILE = "run_profile";
    static final String INLINE_PROFILE_PREFIX = PKB_PROFILE + "_";

    private static final String[] PROFILE_RESOURCES = {
            "profiles.yaml",
            "profiles_local.yaml",
            "profiles_local2.yaml"
    };
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
        String selectedProfiles = trimToNull(values.get(PKB_PROFILE));
        String directRunProfile = trimToNull(values.get(PKB_RUN_PROFILE));

        ObjectNode registry = JSON.createObjectNode();
        loadProfileResources(registry);
        loadInlineProfiles(registry, values);

        ObjectNode defaultProfile = profileFromValues(values);
        registry.set(DEFAULT_PROFILE, defaultProfile.deepCopy());

        ObjectNode composed;
        boolean direct = directRunProfile != null;
        if (direct) {
            composed = objectFromAssignments(directRunProfile, PKB_RUN_PROFILE, false);
        } else if (selectedProfiles == null) {
            composed = defaultProfile.deepCopy();
        } else {
            composed = composeSelectedProfiles(registry, selectedProfiles);
            JsonNode profileDirect = composed.remove(PKB_RUN_PROFILE);
            if (profileDirect != null && !profileDirect.isNull()) {
                if (!profileDirect.isTextual()) {
                    throw new IllegalArgumentException(
                            "Profile control '" + PKB_RUN_PROFILE + "' must be a scalar assignment string.");
                }
                String rawAssignments = trimToNull(profileDirect.textValue());
                if (rawAssignments != null) {
                    String assignments = resolveRunProfileControl(rawAssignments, registry, composed);
                    composed = objectFromAssignments(assignments, PKB_RUN_PROFILE, false);
                    direct = true;
                }
            }
        }

        restoreProtectedReferences(composed, defaultProfile);
        ObjectNode resolved = resolveProfile(composed, registry);
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

    private static LinkedHashMap<String, String> parseAssignments(String input, boolean allowRunProfileControl) {
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
            boolean supported = PKB_props.isRunVariableKey(key)
                    || allowRunProfileControl && PKB_RUN_PROFILE.equals(key);
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
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!PKB_props.isRunVariableKey(key) || value == null || value.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(", ");
            }
            String serializedValue = SensitiveConfiguration.isSensitive(key)
                    ? SensitiveConfiguration.protectedReference(key)
                    : value.trim();
            out.append(key).append('=').append(quoteIfNeeded(serializedValue));
        }
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
                        if (parsed == null || parsed.isNull()) {
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
                        "Failed loading Pickleball profile resource '" + resourceName + "'.",
                        exception);
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
                throw new IllegalArgumentException("Inline profile property requires a profile name after '"
                        + INLINE_PROFILE_PREFIX + "'.");
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

    private static String resolveRunProfileControl(
            String value, ObjectNode registry, ObjectNode composedProfile
    ) {
        ObjectNode resolutionRoot = registry.deepCopy();
        resolutionRoot.set(RUN_PROFILE, composedProfile.deepCopy());
        composedProfile.fields().forEachRemaining(
                entry -> resolutionRoot.set(entry.getKey(), entry.getValue().deepCopy()));
        Object resolved = new ProfileTemplateResolver(resolutionRoot).resolveWholeValue(value);
        if (!(resolved instanceof String text)) {
            throw new IllegalArgumentException(
                    "Profile control '" + PKB_RUN_PROFILE + "' must resolve to an assignment string.");
        }
        String unresolved = unresolvedPkbReference(text);
        if (unresolved != null) {
            throw new IllegalArgumentException(
                    "Unresolved Pickleball profile reference '" + unresolved
                            + "' in '" + PKB_RUN_PROFILE + "': " + text);
        }
        return text;
    }

    private static ObjectNode resolveProfile(ObjectNode profile, ObjectNode registry) {
        ObjectNode current = profile.deepCopy();
        Set<String> seen = new java.util.HashSet<>();
        for (int pass = 0; pass < 64; pass++) {
            String signature = current.toString();
            if (!seen.add(signature)) {
                throw new IllegalArgumentException(
                        "Cyclic Pickleball profile template resolution detected while resolving " + signature);
            }

            ObjectNode resolutionRoot = registry.deepCopy();
            resolutionRoot.set(RUN_PROFILE, current.deepCopy());
            current.fields().forEachRemaining(entry -> resolutionRoot.set(entry.getKey(), entry.getValue().deepCopy()));

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
                        "Direct run profile requires protected value '" + protectedKey
                                + "'. Supply it through normal secure configuration (for example a JVM property) "
                                + "or replace the protected reference explicitly.");
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
            if (normalized.startsWith(PKB_PREFIX)
                    || normalized.contains("." + PKB_PREFIX)
                    || normalized.startsWith(DEFAULT_PROFILE + ".")
                    || normalized.startsWith(RUN_PROFILE + ".")) {
                return reference;
            }
        }
        return null;
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

    private static ObjectNode objectFromAssignments(
            String input, String source, boolean allowRunProfileControl
    ) {
        ObjectNode out = JSON.createObjectNode();
        try {
            parseAssignments(input, allowRunProfileControl).forEach(out::put);
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
            if (!PKB_props.isRunVariableKey(key) && !PKB_RUN_PROFILE.equals(key)) {
                throw new IllegalArgumentException(
                        "Profile '" + profileName + "' contains unsupported property '" + entry.getKey() + "'.");
            }
            normalized.set(key, entry.getValue().deepCopy());
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
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT);
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
        char quote = 0;
        boolean escaped = false;
        int templateDepth = 0;

        for (int index = 0; index < input.length(); index++) {
            char ch = input.charAt(index);
            if (escaped) {
                current.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                current.append(ch);
                escaped = true;
                continue;
            }
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                current.append(ch);
                continue;
            }
            if (ch == '<') {
                templateDepth++;
                current.append(ch);
                continue;
            }
            if (ch == '>' && templateDepth > 0) {
                templateDepth--;
                current.append(ch);
                continue;
            }
            if ((ch == ',' || ch == ';') && templateDepth == 0) {
                addAssignment(out, current);
                continue;
            }
            current.append(ch);
        }
        addAssignment(out, current);
        return out;
    }

    private static void addAssignment(List<String> out, StringBuilder current) {
        String value = current.toString().trim();
        current.setLength(0);
        if (!value.isEmpty()) {
            out.add(value);
        }
    }

    private static String unquote(String value) {
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first != '"' && first != '\'') || first != last) {
            return value;
        }
        String inner = value.substring(1, value.length() - 1);
        return inner.replace("\\" + first, String.valueOf(first)).replace("\\\\", "\\");
    }

    private static String quoteIfNeeded(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf(';') >= 0
                || value.indexOf('"') >= 0
                || !value.equals(value.trim());
        if (!quote) {
            return value;
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static void mergeDeep(ObjectNode target, ObjectNode source) {
        source.fields().forEachRemaining(entry -> {
            JsonNode existing = target.get(entry.getKey());
            JsonNode incoming = entry.getValue();
            if (existing instanceof ObjectNode existingObject && incoming instanceof ObjectNode incomingObject) {
                mergeDeep(existingObject, incomingObject);
            } else {
                target.set(entry.getKey(), incoming.deepCopy());
            }
        });
    }

    private static void clearManagedValues(LinkedHashMap<String, String> values) {
        values.entrySet().removeIf(entry -> {
            String key = entry.getKey();
            if (key == null) {
                return false;
            }
            String normalized = PickleballRunner.normalizePkbKey(key);
            return normalized.startsWith(PKB_PREFIX)
                    || MANAGED_CUCUMBER_KEYS.contains(key)
                    || MANAGED_JUNIT_KEYS.contains(key)
                    || key.toLowerCase(Locale.ROOT).startsWith("rp.");
        });
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record Resolution(boolean direct, String selectedProfiles, Map<String, String> runVars) {
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
            Object direct = nodeMap.get(key);
            if (direct == null && key != null) {
                int dot = key.indexOf('.');
                if (dot > 0) {
                    String normalizedProfile = key.substring(0, dot).toLowerCase(Locale.ROOT)
                            + key.substring(dot);
                    direct = nodeMap.get(normalizedProfile);
                }
            }
            return direct != null ? direct : super.get(key);
        }
    }
}
