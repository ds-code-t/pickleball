package tools.dscode.common.dataelements;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static tools.dscode.common.dataelements.DataElementForm.PLURAL;
import static tools.dscode.common.dataelements.DataElementForm.SINGULAR;

public final class DataElementRegistry {
    private static final Map<String, DataElementRegistration> LOOKUP;
    private static final Map<DataElementGroup, Set<String>> NAMES_BY_GROUP;

    public static final Set<String> CUCUMBER_DATA_ELEMENTS;
    public static final Set<String> JAVA_DATA_ELEMENTS;
    public static final Set<String> FORMAT_DATA_ELEMENTS;
    public static final Set<String> DATA_ELEMENTS;

    static {
        Map<String, DataElementRegistration> lookup = new LinkedHashMap<>();
        Map<DataElementGroup, LinkedHashSet<String>> names =
                new EnumMap<>(DataElementGroup.class);

        for (DataElementGroup group : DataElementGroup.values()) {
            names.put(group, new LinkedHashSet<>());
        }

        for (DataElementKind kind : DataElementKind.values()) {
            register(lookup, names, kind, SINGULAR, kind.singularName(), false);
            register(lookup, names, kind, PLURAL, kind.pluralName(), false);
            for (DataElementKind.Alias alias : kind.aliases()) {
                register(lookup, names, kind, alias.form(), alias.name(), true);
            }
        }

        LOOKUP = Collections.unmodifiableMap(lookup);

        Map<DataElementGroup, Set<String>> immutableNames =
                new EnumMap<>(DataElementGroup.class);
        names.forEach((group, values) ->
                immutableNames.put(
                        group,
                        Collections.unmodifiableSet(new LinkedHashSet<>(values))
                ));
        NAMES_BY_GROUP = Collections.unmodifiableMap(immutableNames);

        CUCUMBER_DATA_ELEMENTS = names(DataElementGroup.CUCUMBER);
        JAVA_DATA_ELEMENTS = names(DataElementGroup.JAVA);
        FORMAT_DATA_ELEMENTS = names(DataElementGroup.FORMAT);

        LinkedHashSet<String> all = new LinkedHashSet<>();
        all.addAll(CUCUMBER_DATA_ELEMENTS);
        all.addAll(JAVA_DATA_ELEMENTS);
        all.addAll(FORMAT_DATA_ELEMENTS);
        DATA_ELEMENTS = Collections.unmodifiableSet(all);
    }

    private DataElementRegistry() {
    }

    public static Optional<DataElementRegistration> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(LOOKUP.get(normalize(name)));
    }

    public static DataElementRegistration require(String name) {
        return find(name).orElseThrow(() ->
                new IllegalArgumentException("Unknown Data Element: " + name)
        );
    }

    public static boolean contains(String name) {
        return find(name).isPresent();
    }

    public static Set<String> names(DataElementGroup group) {
        return NAMES_BY_GROUP.getOrDefault(group, Set.of());
    }

    private static void register(
            Map<String, DataElementRegistration> lookup,
            Map<DataElementGroup, LinkedHashSet<String>> names,
            DataElementKind kind,
            DataElementForm form,
            String name,
            boolean alias
    ) {
        if (name == null || name.isBlank()) {
            return;
        }

        DataElementRegistration registration =
                new DataElementRegistration(kind, form, name, alias);
        DataElementRegistration previous = lookup.putIfAbsent(
                normalize(name),
                registration
        );

        if (previous != null
                && (previous.kind() != kind || previous.form() != form)) {
            throw new IllegalStateException(
                    "Duplicate Data Element registration for '" + name + "'"
            );
        }

        names.get(kind.group()).add(name);
    }

    private static String normalize(String name) {
        return name.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
