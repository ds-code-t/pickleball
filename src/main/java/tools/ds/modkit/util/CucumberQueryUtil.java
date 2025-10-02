// src/main/java/tools/ds/modkit/util/CucumberQueryUtil.java
package tools.ds.modkit.util;

import io.cucumber.messages.types.*;
import java.util.*;
import java.util.stream.Collectors;

import static tools.ds.modkit.util.Reflect.getDirectProperty;
import static tools.ds.modkit.util.Reflect.invokeAnyMethod;

/**
 * Read-only bridge into non-public CucumberQuery/GherkinMessagesPickle via reflection.
 * Works with real instances; does not require compile-time access to package-private types.
 */
public final class CucumberQueryUtil {

    private static final String FQ_GMPICKLE = "io.cucumber.core.gherkin.messages.GherkinMessagesPickle";
    private static final String FQ_QUERY    = "io.cucumber.core.gherkin.messages.CucumberQuery";

    // Captured at first use to prove we’re bound to the private query type; not required but helps debugging.
    private static volatile Class<?> QUERY_CLASS;

    private CucumberQueryUtil() {}

    /* ------------------------------------------------------------------------------------
       Public snapshot: gather most things you typically want about a Pickle
       ------------------------------------------------------------------------------------ */

    public static GherkinView describe(Object gherkinMessagesPickle) {
        io.cucumber.messages.types.Pickle mp = messagePickle(gherkinMessagesPickle);
        Object q = query(gherkinMessagesPickle);

        Scenario scenario = scenarioOf(q, mp);
        Optional<Feature> feature = featureOf(q, mp);
        Optional<Rule>    rule    = ruleOf(q, mp);
        Optional<Examples> examples = examplesOf(q, mp);

        List<String> astIds = mp.getAstNodeIds();
        String lastAstId = astIds.isEmpty() ? null : astIds.get(astIds.size() - 1);

        Optional<TableRow> exampleRow = examples.flatMap(ex -> ex.getTableBody().stream()
                .filter(r -> Objects.equals(r.getId(), lastAstId))
                .findFirst());

        Location stepOrRowLocation = locationOf(q, mp);
        Location scenarioLocation  = scenario.getLocation();
        Optional<Location> ruleLocation    = rule.map(Rule::getLocation);
        Optional<Location> featureLocation = feature.map(Feature::getLocation);
        Optional<Location> examplesLocation = examples.map(Examples::getLocation);

        boolean isScenarioOutline = examples.isPresent();

        // Tags broken out by origin, plus the pickle's own compiled tags
        List<String> pickleTags  = mp.getTags().stream().map(t -> t.getName()).collect(Collectors.toList());
        List<Tag> scenarioTags   = scenario.getTags();
        List<Tag> featureTags    = feature.map(Feature::getTags).orElseGet(List::of);
        List<Tag> examplesTags   = examples.map(Examples::getTags).orElseGet(List::of);

        return new GherkinView(
                gherkinMessagesPickle,
                mp,
                scenario,
                feature.orElse(null),
                rule.orElse(null),
                examples.orElse(null),
                exampleRow.orElse(null),
                stepOrRowLocation,
                scenarioLocation,
                ruleLocation.orElse(null),
                featureLocation.orElse(null),
                examplesLocation.orElse(null),
                isScenarioOutline,
                pickleTags,
                scenarioTags, featureTags, examplesTags
        );
    }

    /* ------------------------------------------------------------------------------------
       Targeted helpers (you can use these standalone if you don't need the full snapshot)
       ------------------------------------------------------------------------------------ */

    public static Scenario scenarioOf(Object gmPickle) {
        return scenarioOf(query(gmPickle), messagePickle(gmPickle));
    }

    public static Optional<Feature> featureOf(Object gmPickle) {
        return featureOf(query(gmPickle), messagePickle(gmPickle));
    }

    public static Optional<Rule> ruleOf(Object gmPickle) {
        return ruleOf(query(gmPickle), messagePickle(gmPickle));
    }

    public static Optional<Examples> examplesOf(Object gmPickle) {
        return examplesOf(query(gmPickle), messagePickle(gmPickle));
    }

    public static Location locationOf(Object gmPickle) {
        return locationOf(query(gmPickle), messagePickle(gmPickle));
    }

    public static boolean isScenarioOutline(Object gmPickle) {
        return examplesOf(gmPickle).isPresent();
    }

    /* ------------------------------------------------------------------------------------
       Internal reflection plumbing (kept small & robust)
       ------------------------------------------------------------------------------------ */

    // Pull the private field `cucumberQuery` off GherkinMessagesPickle
    private static Object query(Object gmPickle) {
        requireType(gmPickle, FQ_GMPICKLE, "gmPickle");
        Object q = getDirectProperty(gmPickle, "cucumberQuery"); // private field
        if (q == null) throw new IllegalStateException("cucumberQuery not found on GherkinMessagesPickle");
        if (QUERY_CLASS == null) QUERY_CLASS = q.getClass();
        return q;
    }

    // Pull the private field `pickle` (messages Pickle) off GherkinMessagesPickle
    static io.cucumber.messages.types.Pickle messagePickle(Object gmPickle) {
        requireType(gmPickle, FQ_GMPICKLE, "gmPickle");
        Object p = getDirectProperty(gmPickle, "pickle"); // private field
        if (!(p instanceof io.cucumber.messages.types.Pickle)) {
            throw new IllegalStateException("Expected messages.types.Pickle on GherkinMessagesPickle");
        }
        return (io.cucumber.messages.types.Pickle) p;
    }

    // ---- Delegate into CucumberQuery (non-public) via reflection ------------------------

    private static Scenario scenarioOf(Object q, io.cucumber.messages.types.Pickle mp) {
        Object s = invokeAnyMethod(q, "getScenarioBy", mp);
        if (!(s instanceof Scenario)) {
            throw new IllegalStateException("CucumberQuery#getScenarioBy returned unexpected type: " + (s == null ? "null" : s.getClass()));
        }
        return (Scenario) s;
    }

    private static Optional<Feature> featureOf(Object q, io.cucumber.messages.types.Pickle mp) {
        Object f = invokeAnyMethod(q, "findFeatureBy", mp); // Optional<Feature>
        return (f instanceof Optional) ? (Optional<Feature>) f : Optional.empty();
    }

    private static Optional<Rule> ruleOf(Object q, io.cucumber.messages.types.Pickle mp) {
        Object r = invokeAnyMethod(q, "findRuleBy", mp); // Optional<Rule>
        return (r instanceof Optional) ? (Optional<Rule>) r : Optional.empty();
    }

    private static Optional<Examples> examplesOf(Object q, io.cucumber.messages.types.Pickle mp) {
        Object ex = invokeAnyMethod(q, "findExamplesBy", mp); // Optional<Examples>
        return (ex instanceof Optional) ? (Optional<Examples>) ex : Optional.empty();
    }

    private static Location locationOf(Object q, io.cucumber.messages.types.Pickle mp) {
        Object loc = invokeAnyMethod(q, "getLocationBy", mp);
        if (!(loc instanceof Location)) {
            throw new IllegalStateException("CucumberQuery#getLocationBy returned unexpected type: " + (loc == null ? "null" : loc.getClass()));
        }
        return (Location) loc;
    }

    private static void requireType(Object o, String fqcn, String param) {
        if (o == null) throw new IllegalArgumentException(param + " must not be null");
        // Soft check to help catch misuse; not strictly required
        if (!o.getClass().getName().equals(fqcn)) {
            // Allow subclasses/wrappers from same package if any
            if (!o.getClass().getName().startsWith("io.cucumber.core.gherkin.messages.")) {
                throw new IllegalArgumentException(param + " is not a " + fqcn + " (was " + o.getClass().getName() + ")");
            }
        }
    }

    /* ------------------------------------------------------------------------------------
       Snapshot DTO
       ------------------------------------------------------------------------------------ */

    public static final class GherkinView {
        public final Object gherkinMessagesPickle;            // the non-public wrapper instance
        public final io.cucumber.messages.types.Pickle pickle;// underlying messages Pickle

        public final Scenario scenario;
        public final Feature  feature;   // may be null
        public final Rule     rule;      // may be null

        public final Examples examples;  // may be null
        public final TableRow exampleRow;// row that produced this pickle (for outlines), may be null

        public final Location location;         // location of step/row used for this pickle
        public final Location scenarioLocation; // scenario-level location
        public final Location ruleLocation;     // may be null
        public final Location featureLocation;  // may be null
        public final Location examplesLocation; // may be null

        public final boolean scenarioOutline;

        public final List<String> pickleTags; // names on the compiled pickle
        public final List<Tag> scenarioTags;
        public final List<Tag> featureTags;
        public final List<Tag> examplesTags;

        GherkinView(Object gmPickle,
                    io.cucumber.messages.types.Pickle pickle,
                    Scenario scenario,
                    Feature feature,
                    Rule rule,
                    Examples examples,
                    TableRow exampleRow,
                    Location location,
                    Location scenarioLocation,
                    Location ruleLocation,
                    Location featureLocation,
                    Location examplesLocation,
                    boolean scenarioOutline,
                    List<String> pickleTags,
                    List<Tag> scenarioTags,
                    List<Tag> featureTags,
                    List<Tag> examplesTags) {
            this.gherkinMessagesPickle = gmPickle;
            this.pickle = pickle;
            this.scenario = scenario;
            this.feature = feature;
            this.rule = rule;
            this.examples = examples;
            this.exampleRow = exampleRow;
            this.location = location;
            this.scenarioLocation = scenarioLocation;
            this.ruleLocation = ruleLocation;
            this.featureLocation = featureLocation;
            this.examplesLocation = examplesLocation;
            this.scenarioOutline = scenarioOutline;
            this.pickleTags = unmodifiableCopy(pickleTags);
            this.scenarioTags = unmodifiableCopy(scenarioTags);
            this.featureTags = unmodifiableCopy(featureTags);
            this.examplesTags = unmodifiableCopy(examplesTags);
        }

        private static <T> List<T> unmodifiableCopy(List<T> in) {
            return (in == null || in.isEmpty()) ? List.of() : Collections.unmodifiableList(new ArrayList<>(in));
        }


        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(512);
            sb.append("GherkinView\n");
            sb.append("  pickle   : id=").append(safe(pickle.getId()))
                    .append(", name=").append(safe(pickle.getName()))
                    .append(", lang=").append(safe(pickle.getLanguage()))
                    .append('\n');

            sb.append("  scenario : ")
                    .append(safe(scenario.getKeyword())).append(' ')
                    .append(safe(scenario.getName()))
                    .append(" @ ").append(loc(scenarioLocation))
                    .append('\n');

            if (feature != null) {
                sb.append("  feature  : ")
                        .append(safe(feature.getName()))
                        .append(" @ ").append(loc(featureLocation))
                        .append('\n');
            }
            if (rule != null) {
                sb.append("  rule     : ")
                        .append(safe(rule.getName()))
                        .append(" @ ").append(loc(ruleLocation))
                        .append('\n');
            }

            sb.append("  outline? : ").append(scenarioOutline).append('\n');
            if (examples != null) {
                sb.append("  examples : ")
                        .append(loc(examplesLocation))
                        .append('\n');
                if (exampleRow != null) {
                    sb.append("  row      : ")
                            .append(loc(exampleRow.getLocation()))
                            .append(" -> ")
                            .append(rowValues(exampleRow))
                            .append('\n');
                }
            }

            sb.append("  location : stepOrRow=").append(loc(location)).append('\n');

            sb.append("  tags     : ")
                    .append("pickle=").append(pickleTags)
                    .append(", scenario=").append(tagNames(scenarioTags))
                    .append(", feature=").append(tagNames(featureTags))
                    .append(", examples=").append(tagNames(examplesTags))
                    .append('\n');

            return sb.toString();
        }

        private static String safe(Object o) {
            return (o == null) ? "" : String.valueOf(o);
        }

        private static String loc(io.cucumber.messages.types.Location l) {
            if (l == null) return "n/a";
            Long line = l.getLine();
            Long col  = l.getColumn().get();
            String s = (line == null ? "?" : String.valueOf(line));
            if (col != null) s += ":" + col;
            return s;
        }

        private static String rowValues(io.cucumber.messages.types.TableRow r) {
            if (r == null || r.getCells() == null) return "[]";
            return r.getCells().stream()
                    .map(io.cucumber.messages.types.TableCell::getValue)
                    .collect(java.util.stream.Collectors.joining(" | ", "[", "]"));
        }

        private static java.util.List<String> tagNames(java.util.List<io.cucumber.messages.types.Tag> tags) {
            if (tags == null || tags.isEmpty()) return java.util.List.of();
            return tags.stream().map(io.cucumber.messages.types.Tag::getName).toList();
        }

    }
}
