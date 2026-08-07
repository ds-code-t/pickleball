package tools.dscode.common.dataelements;

import tools.dscode.common.treeparsing.parsedComponents.DataElementMatch;

import java.util.EnumSet;
import java.util.Set;

public final class DataElementRuntime {
    private static final Set<DataElementKind> TABULAR_KINDS =
            EnumSet.of(
                    DataElementKind.DATA_TABLE,
                    DataElementKind.DATA_ROW,
                    DataElementKind.DATA_COLUMN,
                    DataElementKind.DATA_LIST,
                    DataElementKind.DATA_COLUMN_LIST,
                    DataElementKind.DATA_CELL,
                    DataElementKind.DATA_ENTRY,
                    DataElementKind.DATA_HEADER,
                    DataElementKind.DATA_VALUE
            );

    private final DataQueryEngine queryEngine;
    private final DataResultPolicy resultPolicy;

    public DataElementRuntime() {
        this(new DataQueryEngine(), new DataResultPolicy());
    }

    DataElementRuntime(
            DataQueryEngine queryEngine,
            DataResultPolicy resultPolicy
    ) {
        this.queryEngine = queryEngine;
        this.resultPolicy = resultPolicy;
    }

    public static boolean supports(DataElementKind kind) {
        return TABULAR_KINDS.contains(kind);
    }

    public DataExecutionResult execute(
            Object source,
            DataElementMatch elementMatch
    ) {
        return execute(source, elementMatch.dataQuery());
    }

    public DataExecutionResult execute(
            Object source,
            DataQuery query
    ) {
        if (!supports(query.kind())) {
            throw new DataQueryException(
                    "Runtime integration is not implemented for "
                            + query.kind().singularName() + "."
            );
        }

        DataContext context = DataContextFactory.table(source);
        return resultPolicy.apply(queryEngine.query(context, query));
    }
}
