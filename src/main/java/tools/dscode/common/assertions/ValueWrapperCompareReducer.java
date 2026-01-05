package tools.dscode.common.assertions;

import org.openqa.selenium.remote.tracing.opentelemetry.SeleniumSpanExporter;
import tools.dscode.common.seleniumextensions.ElementWrapper;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Applies a ValueWrapper comparison function across single values and/or collections,
 * then reduces the results using ALL / ANY / NONE with optional per-comparison inversion.
 * <p>
 * Rules:
 * - If an input is a ValueWrapper -> use it directly.
 * - If an input is a Collection -> compare item-by-item (cartesian product).
 * - Otherwise wrap via ValueWrapper.createValueWrapper(obj).
 * <p>
 * Reduction:
 * - ANY  => true if any comparison result is true
 * - NONE => true if all comparison results are false
 * - ALL  => true if all comparison results are true
 * - Default (no NONE/ANY) => ALL semantics
 * <p>
 * Inversion:
 * - NOT and UN both invert EACH individual comparison result (not the final result).
 * - If both NOT and UN are present, they cancel out (double invert => no change).
 */
public final class ValueWrapperCompareReducer {

    private ValueWrapperCompareReducer() {
    }

    public enum Mode {
        NONE, ALL, ANY,
        NOT, // per-comparison invert
        UN   // per-comparison invert
    }

    /* -------------------- Public API -------------------- */

    public static boolean evalElements(
            Predicate<ElementWrapper> predicate,
            List<ElementWrapper> elements,
            Set<Mode> modeSet
    ) {

        Objects.requireNonNull(predicate, "predicate");

//        EnumSet<Mode> modeSet = toModeSet(modes);
        Aggregation agg = aggregation(modeSet);

        boolean invertEach = perComparisonInvert(modeSet);

        if (elements.isEmpty())
            return agg == Aggregation.ALL || agg == Aggregation.NONE;

        boolean allTrue = true;
        boolean anyTrue = false;

        for (ElementWrapper e : elements) {
            boolean r = predicate.test(e);
            if (invertEach) r = !r;

            anyTrue |= r;
            allTrue &= r;


            // Short-circuit
            if (agg == Aggregation.ANY && anyTrue) return true;
            if (agg == Aggregation.ALL && !allTrue) return false;
            if (agg == Aggregation.NONE && anyTrue) return false;
        }

        return reduce(agg, allTrue, anyTrue);
    }

    public static boolean eval(
            BiPredicate<ValueWrapper, ValueWrapper> comparator,
            Object left,
            Object right,
            Set<Mode> modeSet
    ) {

        Objects.requireNonNull(comparator, "comparator");

//        EnumSet<Mode> modeSet = toModeSet(modes);
        Aggregation agg = aggregation(modeSet);
        boolean invertEach = perComparisonInvert(modeSet);

        Iterable<?> leftItems = expandToIterable(left);
        Iterable<?> rightItems = expandToIterable(right);

        boolean sawAny = false;
        boolean allTrue = true;
        boolean anyTrue = false;

        for (Object li : leftItems) {
            for (Object ri : rightItems) {


                sawAny = true;

                ValueWrapper lw = toValueWrapper(li);
                ValueWrapper rw = toValueWrapper(ri);

                boolean r = comparator.test(lw, rw);


                if (invertEach) r = !r;


                anyTrue |= r;
                allTrue &= r;

                // Short-circuit
                if (agg == Aggregation.ANY && anyTrue) return true;
                if (agg == Aggregation.ALL && !allTrue) return false;
                if (agg == Aggregation.NONE && anyTrue) return false;
            }
        }


        if (!sawAny)
            return agg == Aggregation.ALL || agg == Aggregation.NONE;;


        return reduce(agg, allTrue, anyTrue);
    }

    /* -------------------- Internal reduction logic -------------------- */

    private enum Aggregation {ANY, ALL, NONE, DEFAULT}

//    private static EnumSet<Mode> toModeSet(Mode... modes) {
//        EnumSet<Mode> set = EnumSet.noneOf(Mode.class);
//        if (modes != null) {
//            for (Mode m : modes) {
//                if (m != null) set.add(m);
//            }
//        }
//        return set;
//    }

    /**
     * Priority: ANY > NONE > ALL(default)
     */
    private static Aggregation aggregation(Set<Mode> modeSet) {
        if (modeSet.contains(Mode.ANY)) return Aggregation.ANY;
        if (modeSet.contains(Mode.NONE)) return Aggregation.NONE;
        if (modeSet.contains(Mode.ALL)) return Aggregation.ALL;
        return Aggregation.DEFAULT;
    }

    /**
     * NOT and UN both invert each comparison; if both present they cancel out.
     */
    private static boolean perComparisonInvert(Set<Mode> modeSet) {

        boolean not = modeSet.contains(Mode.NOT);
        boolean un = modeSet.contains(Mode.UN);



        return not ^ un; // XOR => true if exactly one is present
    }

//    private static boolean emptyBaseResult(Aggregation agg) {
//        return switch (agg) {
//            case ANY, ALL -> true;
//            case NONE -> false;
//        };
//    }

    private static boolean reduce(Aggregation agg, boolean allTrue, boolean anyTrue) {
        return switch (agg) {
            case DEFAULT, ALL -> allTrue;
            case ANY -> anyTrue;
            case NONE -> !anyTrue;
        };
    }

    /* -------------------- Input expansion / wrapping -------------------- */

    private static Iterable<?> expandToIterable(Object o) {
        if (o == null) {
            // treat null as a single item, will be wrapped by createValueWrapper(null)
            return java.util.List.of((Object) null);
        }
        if (o instanceof Collection<?> c) {
            return c;
        }
        return java.util.List.of(o);
    }

    private static ValueWrapper toValueWrapper(Object o) {
        if (o instanceof ValueWrapper vw) return vw;
        return ValueWrapper.createValueWrapper(o);
    }


    public static boolean evalValues(
            Predicate<ValueWrapper> predicate,
            List<ValueWrapper> values,
            Set<Mode> modeSet
    ) {
        Objects.requireNonNull(predicate, "predicate");

//        EnumSet<Mode> modeSet = toModeSet(modes);
        Aggregation agg = aggregation(modeSet);
        boolean invertEach = perComparisonInvert(modeSet);

        if (values.isEmpty())
            return agg == Aggregation.ALL || agg == Aggregation.NONE;


    boolean allTrue = true;
    boolean anyTrue = false;

        for(
    ValueWrapper v :values)

    {
        boolean r = predicate.test(v);

        if (invertEach) r = !r;


        anyTrue |= r;
        allTrue &= r;

        // Short-circuit
        if (agg == Aggregation.ANY && anyTrue) return true;
        if (agg == Aggregation.ALL && !allTrue) return false;
        if (agg == Aggregation.NONE && anyTrue) return false;
    }

        return

    reduce(agg, allTrue, anyTrue);
}

}
