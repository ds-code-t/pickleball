//package tools.dscode.common.domoperations;
//
//import java.util.Arrays;
//import java.util.List;
//import java.util.Objects;
//
//
//public final class XPathyRegistryFluent {
//
//    private XPathyRegistryFluent() {
//        // utility class
//    }
//
//    // ======================================================
//    // Entry points
//    // ======================================================
//
//    /**
//     * Start a fluent definition for a single category.
//     */
//    public static CategorySpec category(String name) {
//        return new CategorySpec(name);
//    }
//
//    /**
//     * Start a fluent definition for multiple categories at once.
//     */
//    public static CategoriesSpec categories(String... names) {
//        Objects.requireNonNull(names, "names must not be null");
//        return new CategoriesSpec(Arrays.asList(names));
//    }
//
//    /**
//     * Start a fluent definition for a parent category whose children inherit from it.
//     */
//    public static ParentSpec parent(String parentName) {
//        return new ParentSpec(parentName);
//    }
//
//    // ======================================================
//    // CategorySpec: fluent config for a single category
//    // ======================================================
//
//    public static final class CategorySpec {
//        private final String category;
//
//        private CategorySpec(String category) {
//            this.category = Objects.requireNonNull(category, "category must not be null");
//        }
//
//        /**
//         * Register OR-based builders for this category.
//         * Forwards to {@link ExecutionDictionary#registerOrBuilder(String, ExecutionDictionary.Builder...)}.
//         */
//        public CategorySpec or(ExecutionDictionary.Builder... builders) {
//            ExecutionDictionary.registerOrBuilder(category, builders);
//            return this;
//        }
//
//        /**
//         * Register AND-based builders for this category.
//         * Forwards to {@link ExecutionDictionary#registerAndBuilder(String, ExecutionDictionary.Builder...)}.
//         */
//        public CategorySpec and(ExecutionDictionary.Builder... builders) {
//            ExecutionDictionary.registerAndBuilder(category, builders);
//            return this;
//        }
//
//        /**
//         * Declare that this category inherits from the given parents.
//         * Forwards to {@link ExecutionDictionary#registerCategoryInheritance(String, String...)}.
//         */
//        public CategorySpec inheritsFrom(String... parents) {
//            ExecutionDictionary.registerCategoryInheritance(category, parents);
//            return this;
//        }
//
//        /**
//         * Convenience for HTML type registration on this category.
//         * Forwards to {@link ExecutionDictionary#addHtmlTypes(String, ExecutionDictionary.HtmlType...)}.
//         */
//        public CategorySpec htmlTypes(ExecutionDictionary.HtmlType... htmlTypes) {
//            ExecutionDictionary.addHtmlTypes(category, htmlTypes);
//            return this;
//        }
//
//        /**
//         * Expose the category name if needed for further manual wiring.
//         */
//        public String name() {
//            return category;
//        }
//    }
//
//    // ======================================================
//    // CategoriesSpec: fluent config for multiple categories
//    // ======================================================
//
//    public static final class CategoriesSpec {
//        private final List<String> categories;
//
//        private CategoriesSpec(List<String> categories) {
//            this.categories = Objects.requireNonNull(categories, "categories must not be null");
//        }
//
//        /**
//         * Register OR-based builders for all categories in this group.
//         * Forwards to {@link ExecutionDictionary#registerOrForCategories(List, ExecutionDictionary.Builder...)}.
//         */
//        public CategoriesSpec or(ExecutionDictionary.Builder... builders) {
//            ExecutionDictionary.registerOrForCategories(categories, builders);
//            return this;
//        }
//
//        /**
//         * Register AND-based builders for all categories in this group.
//         * Forwards to {@link ExecutionDictionary#registerAndForCategories(List, ExecutionDictionary.Builder...)}.
//         */
//        public CategoriesSpec and(ExecutionDictionary.Builder... builders) {
//            ExecutionDictionary.registerAndForCategories(categories, builders);
//            return this;
//        }
//
//        /**
//         * Declare that all categories in this group inherit from the given parents.
//         * Forwards to {@link ExecutionDictionary#registerCategoryInheritance(String, String...)}
//         * for each category.
//         */
//        public CategoriesSpec inheritFrom(String... parents) {
//            for (String cat : categories) {
//                if (cat == null || cat.isBlank()) continue;
//                ExecutionDictionary.registerCategoryInheritance(cat, parents);
//            }
//            return this;
//        }
//
//        /**
//         * Convenience for HTML type registration on all categories in this group.
//         * Forwards to {@link ExecutionDictionary#addHtmlTypes(String, ExecutionDictionary.HtmlType...)}.
//         */
//        public CategoriesSpec htmlTypes(ExecutionDictionary.HtmlType... htmlTypes) {
//            for (String cat : categories) {
//                if (cat == null || cat.isBlank()) continue;
//                ExecutionDictionary.addHtmlTypes(cat, htmlTypes);
//            }
//            return this;
//        }
//
//        /**
//         * Expose the underlying list of category names.
//         */
//        public List<String> names() {
//            return categories;
//        }
//    }
//
//    // ======================================================
//    // ParentSpec: fluent config for parent → children inheritance
//    // ======================================================
//
//    public static final class ParentSpec {
//        private final String parent;
//
//        private ParentSpec(String parent) {
//            this.parent = Objects.requireNonNull(parent, "parent must not be null");
//        }
//
//        /**
//         * Declare that the given child categories inherit from this parent.
//         * Forwards to {@link ExecutionDictionary#registerParentOfCategories(String, String...)}.
//         */
//        public ParentSpec children(String... childCategories) {
//            ExecutionDictionary.registerParentOfCategories(parent, childCategories);
//            return this;
//        }
//
//        /**
//         * Expose the parent category name.
//         */
//        public String name() {
//            return parent;
//        }
//    }
//}
