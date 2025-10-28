package io.cucumber.gherkin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import io.cucumber.messages.types.Pickle;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.Tag;
import io.cucumber.messages.types.Location;

public aspect OutlineRowsAndTagAugment {

    /* =========================================================
     * (1) Introduce List<String> headerRow/valueRow on Pickle
     * ========================================================= */
    private transient List<String> io.cucumber.messages.types.Pickle.headerRow;
    private transient List<String> io.cucumber.messages.types.Pickle.valueRow;

    public List<String> io.cucumber.messages.types.Pickle.getHeaderRow() {
        return (headerRow == null) ? List.of() : headerRow;
    }
    public List<String> io.cucumber.messages.types.Pickle.getValueRow() {
        return (valueRow == null) ? List.of() : valueRow;
    }
    public void io.cucumber.messages.types.Pickle.setOutlineRows(List<String> hdr, List<String> vals) {
        this.headerRow = (hdr == null || hdr.isEmpty()) ? List.of() : List.copyOf(hdr);
        this.valueRow = (vals == null || vals.isEmpty()) ? List.of() : List.copyOf(vals);
    }

    /* =========================================================
     * (2) Per-thread context for Examples row
     *     Stack entries:
     *       [0] = List<String> headerRow
     *       [1] = List<String> valueRow
     *       [2] = Location (derived from valuesRow if present, else fallback)
     * ========================================================= */
    private static final ThreadLocal<Deque<Object[]>> CTX =
            ThreadLocal.withInitial(ArrayDeque::new);

    /* =========================================================
     * (3) Pointcuts
     * ========================================================= */
    pointcut withinCompileScenarioOutline():
            withincode(* io.cucumber.gherkin.PickleCompiler.compileScenarioOutline(..));

    pointcut callCompilePickleStepsWithCells(
            List<?> bgSteps, List<?> scenarioSteps,
            List<TableCell> variableCells, TableRow valuesRow):
            call(* io.cucumber.gherkin.PickleCompiler.compilePickleSteps(..))
                    && args(bgSteps, scenarioSteps, variableCells, valuesRow);

    pointcut callPickleCtor():
            call(io.cucumber.messages.types.Pickle+.new(..));

    pointcut callCompileTags(List<Tag> a, List<Tag> b):
            call(* io.cucumber.gherkin.PickleCompiler.compileTags(..))
                    && args(a, b);

    /* =========================================================
     * (4) Push header/value rows (and location) for this Examples row
     * ========================================================= */
    Object around(List<?> bgSteps, List<?> scenarioSteps,
                  List<TableCell> variableCells, TableRow valuesRow)
            : callCompilePickleStepsWithCells(bgSteps, scenarioSteps, variableCells, valuesRow)
            && withinCompileScenarioOutline()
            {
                boolean isOutlineRow = (valuesRow != null);
                if (isOutlineRow) {
                    List<String> hdr = variableCells.stream().map(TableCell::getValue).collect(Collectors.toList());
                    List<String> vals = valuesRow.getCells().stream().map(TableCell::getValue).collect(Collectors.toList());
                    Location loc = valuesRow.getLocation();
                    if (loc == null) {
                        loc = new Location(0L, null);
                    }
                    CTX.get().push(new Object[]{hdr, vals, loc});
                }
                return proceed(bgSteps, scenarioSteps, variableCells, valuesRow);
            }

    /* =========================================================
     * (5) Augment tags using header/value rows; use Tag(Location, String, String)
     * ========================================================= */
    List<Tag> around(List<Tag> a, List<Tag> b)
            : callCompileTags(a, b)
            && withinCompileScenarioOutline()
            {
                @SuppressWarnings("unchecked")
                List<Tag> base = (List<Tag>) proceed(a, b);

                List<Tag> out = new ArrayList<>(base);

                Deque<Object[]> stack = CTX.get();
                if (!stack.isEmpty()) {
                    Object[] top = stack.peek();
                    @SuppressWarnings("unchecked")
                    List<String> header = (List<String>) top[0];
                    @SuppressWarnings("unchecked")
                    List<String> values = (List<String>) top[1];
                    Location loc = (Location) top[2];

                    int idxScenarioTags = header.indexOf("Scenario Tags");
                    int idxComponentTags = header.indexOf("Component Tags");

                    // Scenario Tags → ensure leading '@'
                    if (idxScenarioTags >= 0 && idxScenarioTags < values.size()) {
                        String raw = values.get(idxScenarioTags);
                        if (raw != null && !raw.isBlank()) {
                            for (String t : raw.trim().split("\\s+")) {
                                if (t.isBlank()) continue;
                                String withAt = t.startsWith("@") ? t : "@" + t;
                                out.add(new Tag(loc, withAt, withAt));
                            }
                        }
                    }

                    // Component Tags → prefix with @_COMPONENT_TAG_ (strip a leading '@' if present)
                    if (idxComponentTags >= 0 && idxComponentTags < values.size()) {
                        String raw = values.get(idxComponentTags);
                        if (raw != null && !raw.isBlank()) {
                            for (String t : raw.trim().split("\\s+")) {
                                if (t.isBlank()) continue;
                                String token = t.startsWith("@") ? t.substring(1) : t;
                                String comp = "@_COMPONENT_TAG_" + token;
                                out.add(new Tag(loc, comp, comp));
                            }
                        }
                    }
                }

                return out;
            }

    /* =========================================================
     * (6) After Pickle creation: attach rows and pop
     * ========================================================= */
    after() returning(Pickle p)
            : callPickleCtor()
            && withinCompileScenarioOutline()
            {
                Deque<Object[]> stack = CTX.get();
                if (!stack.isEmpty()) {
                    Object[] top = stack.peek();
                    @SuppressWarnings("unchecked")
                    List<String> hdr = (List<String>) top[0];
                    @SuppressWarnings("unchecked")
                    List<String> vals = (List<String>) top[1];
                    p.setOutlineRows(hdr, vals);
                    stack.pop();
                }
            }
}
