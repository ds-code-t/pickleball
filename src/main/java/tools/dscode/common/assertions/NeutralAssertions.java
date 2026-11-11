package tools.dscode.common.assertions;

import tools.dscode.common.dataoperations.NeutralRows;
import tools.dscode.engine.model.Table;

/** Table equality on the neutral model. Cucumber DataTable is not required. */
public final class NeutralAssertions {
    private NeutralAssertions() {
    }

    public static void sameTable(Table actual, Table expected) {
        if (!NeutralRows.same(actual, expected)) {
            throw new AssertionError("tables differ: " + actual + " vs " + expected);
        }
    }
}
