package io.cucumber.gherkin;

import io.cucumber.messages.types.Location;
import io.cucumber.messages.types.Pickle;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import static tools.dscode.common.GlobalConstants.COMPONENT_TAG_META_CHAR;
//import static tools.dscode.common.GlobalConstants.COMPONENT_TAG_PREFIX;

public aspect OutlineRowsAndTagAugment {

    /* (1) Introduced outline row snapshots on Pickle */
    private transient List<String> io.cucumber.messages.types.Pickle.headerRow;
    private transient List<String> io.cucumber.messages.types.Pickle.valueRow;

    public List<String> io.cucumber.messages.types.Pickle.getHeaderRow() {
        return (this.headerRow == null) ? List.of() : this.headerRow;
    }
    public List<String> io.cucumber.messages.types.Pickle.getValueRow() {
        return (this.valueRow == null) ? List.of() : this.valueRow;
    }
    public void io.cucumber.messages.types.Pickle.setOutlineRows(List<String> hdr, List<String> vals) {
        this.headerRow = (hdr == null || hdr.isEmpty()) ? List.of() : List.copyOf(hdr);
        this.valueRow = (vals == null || vals.isEmpty()) ? List.of() : List.copyOf(vals);
    }

    /* (2) Per-thread context for Examples row: [hdr, vals, loc] */
    private static final ThreadLocal<Deque<Object[]>> CTX =
            ThreadLocal.withInitial(ArrayDeque::new);

    /* (3) Pointcuts */
    pointcut withinCompileScenarioOutline():
            withincode(* io.cucumber.gherkin.PickleCompiler.compileScenarioOutline(..));

    pointcut callCompilePickleStepsWithCells(
            List<?> bgSteps, List<?> scenarioSteps,
            List<TableCell> variableCells, TableRow valuesRow):
            call(* io.cucumber.gherkin.PickleCompiler.compilePickleSteps(..))
                    && args(bgSteps, scenarioSteps, variableCells, valuesRow, ..);

    pointcut callCompileTags(List<Tag> a, List<Tag> b):
            call(* io.cucumber.gherkin.PickleCompiler.compileTags(..))
                    && args(a, b, ..);

    pointcut callPickleCtor():
            call(io.cucumber.messages.types.Pickle+.new(..));

    /* (4) Push header/value rows + location for the current Examples row */
    Object around(List<?> bgSteps, List<?> scenarioSteps,
                  List<TableCell> variableCells, TableRow valuesRow)
            : callCompilePickleStepsWithCells(bgSteps, scenarioSteps, variableCells, valuesRow)
            && withinCompileScenarioOutline()
            {
                if (valuesRow != null) {
                    final List<String> hdr = (variableCells == null)
                            ? List.of()
                            : variableCells.stream().map(TableCell::getValue).collect(Collectors.toList());
                    final List<String> vals = (valuesRow.getCells() == null)
                            ? List.of()
                            : valuesRow.getCells().stream().map(TableCell::getValue).collect(Collectors.toList());
                    Location loc = valuesRow.getLocation();
                    if (loc == null) loc = new Location(0L, null);

                    CTX.get().push(new Object[]{hdr, vals, loc});
                }
                return proceed(bgSteps, scenarioSteps, variableCells, valuesRow);
            }

    /* (5) Augment tags using header/value rows */
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
                    @SuppressWarnings("unchecked") List<String> header = (List<String>) top[0];
                    @SuppressWarnings("unchecked") List<String> values = (List<String>) top[1];
                    Location loc = (Location) top[2];

                    int idxScenarioTags = header.indexOf("Scenario Tags");
//                    int idxComponentTags = header.indexOf("Component Tags");

                    if (idxScenarioTags >= 0 && idxScenarioTags < values.size()) {
                        String raw = values.get(idxScenarioTags);
                        if (raw != null && !raw.isBlank()) {
                            for (String t : raw.trim().split("\\s+")) {
                                if (t.isBlank()) continue;
                                if (t.startsWith("@")) {
                                    out.add(new Tag(loc, t, t));
                                } else if (t.startsWith(COMPONENT_TAG_META_CHAR)) {
                                    String comp  = "@" + t;
                                    out.add(new Tag(loc, comp, comp));
                                }
                            }
                        }
                    }
                }
                return out;
            }

    /* (6) After Pickle creation: attach rows and pop */
    after() returning(Pickle p)
            : callPickleCtor()
            && withinCompileScenarioOutline()
            {
                Deque<Object[]> stack = CTX.get();
                if (!stack.isEmpty()) {
                    Object[] top = stack.peek();
                    @SuppressWarnings("unchecked") List<String> hdr = (List<String>) top[0];
                    @SuppressWarnings("unchecked") List<String> vals = (List<String>) top[1];
                    p.setOutlineRows(hdr, vals);
                    stack.pop();
                }
            }

    /* (7) Guard against leaks if an exception aborts before Pickle construction */
    after() throwing(Throwable t)
            : withinCompileScenarioOutline()
            && call(* io.cucumber.gherkin.PickleCompiler.compilePickleSteps(..))
            {
                Deque<Object[]> stack = CTX.get();
                if (!stack.isEmpty()) stack.pop();
            }
}
