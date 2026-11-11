package tools.dscode.engine.model;

/**
 * A step's optional argument: nothing, a table, or a doc text.
 */
public sealed interface StepArgument permits StepArgument.None, StepArgument.TableArg, StepArgument.DocArg {
    StepArgument NONE = new None();

    record None() implements StepArgument {
    }

    record TableArg(Table table) implements StepArgument {
        public TableArg {
            table = table == null ? Table.empty() : table;
        }
    }

    record DocArg(DocText doc) implements StepArgument {
        public DocArg {
            doc = doc == null ? DocText.of("") : doc;
        }
    }

    static StepArgument none() {
        return NONE;
    }

    static StepArgument table(Table table) {
        return new TableArg(table);
    }

    static StepArgument doc(DocText doc) {
        return new DocArg(doc);
    }
}
