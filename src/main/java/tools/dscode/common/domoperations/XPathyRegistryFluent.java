package tools.dscode.common.domoperations;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Fluent convenience API for configuring {@link XPathyRegistry}.
 *
 * This class does NOT change any behavior in {@link XPathyRegistry};
 * it only forwards calls to:
 *   - registerOrBuilder
 *   - registerAndBuilder
 *   - registerOrForCategories
 *   - registerAndForCategories
 *   - registerCategoryInheritance
 *   - registerParentOfCategories
 *
 * Example usage:
 *
 * <pre>{@code
 * import static tools.dscode.common.domoperations.XPathyRegistryFluent.*;
 *
 * public class MyXPathConfig {
 *   static {
 *     // Single category
 *     category("Textbox")
 *         .inheritsFrom("baseCategory", "InputLike")
 *         .or(
 *             (cat, v, op) -> /* OR builder 1 *\/ null,
 *             (cat, v, op) -> /* OR builder 2 *\/ null
 *         )
 *         .and(
 *             (cat, v, op) -> /* AND builder *\/ null
 *         );
 *
 *     // Multiple categories sharing same builders
 *     categories("Button", "Link")
 *         .or((cat, v, op) -> /* OR builder *\/ null)
 *         .and((cat, v, op) -> /* AND builder *\/ null);
 *
 *     // Inheritance: parent -> children
 *     parent("Clickable")
 *         .children("Button", "Link", "MenuItem");
 *   }
 * }
 * }</pre>
 */
public final class XPathyRegistryFluent {

    private XPathyRegistryFluent() {
        // utility class
    }

    // ======================================================
    // Entry points
    // ======================================================

    /**
     * Start a fluent definition for a single category.
     */
    public static CategorySpec category(String name) {
        return new CategorySpec(name);
    }

    /**
     * Start a fluent definition for multiple categories at once.
     */
    public static CategoriesSpec categories(String... names) {
        Objects.requireNonNull(names, "names must not be null");
        return new CategoriesSpec(Arrays.asList(names));
    }

    /**
     * Start a fluent definition for a parent category whose children inherit from it.
     */
    public static ParentSpec parent(String parentName) {
        return new ParentSpec(parentName);
    }

    // ======================================================
    // CategorySpec: fluent config for a single category
    // ======================================================

    public static final class CategorySpec {
        private final String category;

        private CategorySpec(String category) {
            this.category = Objects.requireNonNull(category, "category must not be null");
        }

        /**
         * Register OR-based builders for this category.
         * Forwards to {@link XPathyRegistry#registerOrBuilder(String, XPathyRegistry.Builder...)}.
         */
        public CategorySpec or(XPathyRegistry.Builder... builders) {
            XPathyRegistry.registerOrBuilder(category, builders);
            return this;
        }

        /**
         * Register AND-based builders for this category.
         * Forwards to {@link XPathyRegistry#registerAndBuilder(String, XPathyRegistry.Builder...)}.
         */
        public CategorySpec and(XPathyRegistry.Builder... builders) {
            XPathyRegistry.registerAndBuilder(category, builders);
            return this;
        }

        /**
         * Declare that this category inherits from the given parents.
         * Forwards to {@link XPathyRegistry#registerCategoryInheritance(String, String...)}.
         */
        public CategorySpec inheritsFrom(String... parents) {
            XPathyRegistry.registerCategoryInheritance(category, parents);
            return this;
        }

        /**
         * Convenience for HTML type registration on this category.
         * Forwards to {@link XPathyRegistry#addHtmlTypes(String, XPathyRegistry.HtmlType...)}.
         */
        public CategorySpec htmlTypes(XPathyRegistry.HtmlType... htmlTypes) {
            XPathyRegistry.addHtmlTypes(category, htmlTypes);
            return this;
        }

        /**
         * Expose the category name if needed for further manual wiring.
         */
        public String name() {
            return category;
        }
    }

    // ======================================================
    // CategoriesSpec: fluent config for multiple categories
    // ======================================================

    public static final class CategoriesSpec {
        private final List<String> categories;

        private CategoriesSpec(List<String> categories) {
            this.categories = Objects.requireNonNull(categories, "categories must not be null");
        }

        /**
         * Register OR-based builders for all categories in this group.
         * Forwards to {@link XPathyRegistry#registerOrForCategories(List, XPathyRegistry.Builder...)}.
         */
        public CategoriesSpec or(XPathyRegistry.Builder... builders) {
            XPathyRegistry.registerOrForCategories(categories, builders);
            return this;
        }

        /**
         * Register AND-based builders for all categories in this group.
         * Forwards to {@link XPathyRegistry#registerAndForCategories(List, XPathyRegistry.Builder...)}.
         */
        public CategoriesSpec and(XPathyRegistry.Builder... builders) {
            XPathyRegistry.registerAndForCategories(categories, builders);
            return this;
        }

        /**
         * Declare that all categories in this group inherit from the given parents.
         * Forwards to {@link XPathyRegistry#registerCategoryInheritance(String, String...)}
         * for each category.
         */
        public CategoriesSpec inheritFrom(String... parents) {
            for (String cat : categories) {
                if (cat == null || cat.isBlank()) continue;
                XPathyRegistry.registerCategoryInheritance(cat, parents);
            }
            return this;
        }

        /**
         * Convenience for HTML type registration on all categories in this group.
         * Forwards to {@link XPathyRegistry#addHtmlTypes(String, XPathyRegistry.HtmlType...)}.
         */
        public CategoriesSpec htmlTypes(XPathyRegistry.HtmlType... htmlTypes) {
            for (String cat : categories) {
                if (cat == null || cat.isBlank()) continue;
                XPathyRegistry.addHtmlTypes(cat, htmlTypes);
            }
            return this;
        }

        /**
         * Expose the underlying list of category names.
         */
        public List<String> names() {
            return categories;
        }
    }

    // ======================================================
    // ParentSpec: fluent config for parent → children inheritance
    // ======================================================

    public static final class ParentSpec {
        private final String parent;

        private ParentSpec(String parent) {
            this.parent = Objects.requireNonNull(parent, "parent must not be null");
        }

        /**
         * Declare that the given child categories inherit from this parent.
         * Forwards to {@link XPathyRegistry#registerParentOfCategories(String, String...)}.
         */
        public ParentSpec children(String... childCategories) {
            XPathyRegistry.registerParentOfCategories(parent, childCategories);
            return this;
        }

        /**
         * Expose the parent category name.
         */
        public String name() {
            return parent;
        }
    }
}
