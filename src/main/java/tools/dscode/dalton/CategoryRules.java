package tools.dscode.dalton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.DefinitionContext;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Built-in categories are immutable. A project file may overlay one. Reserved names are rejected. */
final class CategoryRules {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> BUILTINS = DefinitionContext.DEFAULT_EXECUTION_DICTIONARY.registeredCategoryNames();

    private CategoryRules() {
    }

    static boolean builtin(String name) {
        return name != null && BUILTINS.contains(name);
    }

    static void validate(Path project, String path, byte[] body) {
        if (!isCategory(path)) return;
        Document document = parse(path, body);
        String reserved = ElementType.reservationWarning(document.name);
        if (reserved != null) {
            throw new IllegalArgumentException(reserved);
        }
        if ("ALL_CONTEXTS".equals(document.name)) {
            throw new IllegalArgumentException("ALL_CONTEXTS is a category flag, not a category name");
        }
        if ("Loading".equals(document.name) && document.flags != null && !document.flags.isEmpty()
                && document.flags.stream().noneMatch(flag -> "ALL_CONTEXTS".equals(flag))) {
            throw new IllegalArgumentException("Loading keeps the ALL_CONTEXTS flag");
        }
        if (builtin(document.name) && !document.overlay) {
            throw new IllegalArgumentException("built-in category is immutable: " + document.name
                    + ". Set overlay: true to add a project locator without replacing it.");
        }
        if (!document.overlay && (document.base == null || document.base.isBlank())) {
            throw new IllegalArgumentException("category needs a base locator");
        }
        if (document.flags != null) {
            for (String flag : document.flags) {
                try {
                    ExecutionDictionary.CategoryFlags.valueOf(flag);
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("unknown category flag: " + flag);
                }
            }
        }
    }

    static void beforeDelete(String path) {
        if (!isCategory(path)) return;
        String name = nameOf(path);
        if ("Loading".equals(name) || "ALL_CONTEXTS".equals(name) || builtin(name)) {
            throw new IllegalArgumentException("built-in category cannot be removed: " + name);
        }
    }

    static Document parse(String path, byte[] body) {
        Document document;
        try {
            document = YAML.readValue(body, Document.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException("category YAML is not valid: " + failure.getMessage());
        }
        if (document == null) document = new Document();
        String id = nameOf(path);
        if (document.name == null || document.name.isBlank()) document.name = id;
        if (!id.equals(document.name)) {
            throw new IllegalArgumentException("category name must match the file id: " + id);
        }
        return document;
    }

    static boolean isCategory(String path) {
        return path != null && path.startsWith(DaltonEdit.CATEGORY_DIR + "/") && path.endsWith(".yaml");
    }

    static String nameOf(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(".yaml") ? file.substring(0, file.length() - 5) : file;
    }

    static final class Document {
        public String name;
        public String base;
        public String inherits;
        public boolean overlay;
        public List<String> flags;
        public String alternate;
        public List<String> children;

        String text() {
            return new String(write(this), StandardCharsets.UTF_8);
        }

        static byte[] write(Document document) {
            try {
                return YAML.writeValueAsBytes(document);
            } catch (Exception failure) {
                throw new IllegalArgumentException("category YAML is not valid: " + failure.getMessage());
            }
        }
    }
}
