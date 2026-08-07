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
    private static final Set<DataElementKind> JAVA_KINDS =
            EnumSet.of(
                    DataElementKind.MAP,
                    DataElementKind.LIST,
                    DataElementKind.SET,
                    DataElementKind.MULTIMAP
            );
    private static final Set<DataElementKind> FORMAT_KINDS =
            EnumSet.of(
                    DataElementKind.STRUCTURED_DATA,
                    DataElementKind.JSON_DATA,
                    DataElementKind.YAML_DATA,
                    DataElementKind.XML_DATA,
                    DataElementKind.DATA_STRING,
                    DataElementKind.JSON_STRING,
                    DataElementKind.YAML_STRING,
                    DataElementKind.XML_STRING
            );

    private final DataQueryEngine tabularQueryEngine;
    private final CollectionQueryEngine collectionQueryEngine;
    private final FormatQueryEngine formatQueryEngine;
    private final DataResultPolicy resultPolicy;

    public DataElementRuntime() {
        this(
                new DataQueryEngine(),
                new CollectionQueryEngine(),
                new FormatQueryEngine(),
                new DataResultPolicy()
        );
    }

    DataElementRuntime(
            DataQueryEngine queryEngine,
            DataResultPolicy resultPolicy
    ) {
        this(
                queryEngine,
                new CollectionQueryEngine(),
                new FormatQueryEngine(),
                resultPolicy
        );
    }

    DataElementRuntime(
            DataQueryEngine tabularQueryEngine,
            CollectionQueryEngine collectionQueryEngine,
            FormatQueryEngine formatQueryEngine,
            DataResultPolicy resultPolicy
    ) {
        this.tabularQueryEngine = tabularQueryEngine;
        this.collectionQueryEngine = collectionQueryEngine;
        this.formatQueryEngine = formatQueryEngine;
        this.resultPolicy = resultPolicy;
    }

    public static boolean supports(DataElementKind kind) {
        return TABULAR_KINDS.contains(kind)
                || JAVA_KINDS.contains(kind)
                || FORMAT_KINDS.contains(kind);
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

        DataSelection selection;
        if (TABULAR_KINDS.contains(query.kind())) {
            selection = tabularQueryEngine.query(
                    DataContextFactory.table(source),
                    query
            );
        } else if (JAVA_KINDS.contains(query.kind())) {
            selection = collectionQueryEngine.query(
                    DataContextFactory.create(source, query.kind()),
                    query
            );
        } else {
            selection = formatQueryEngine.query(
                    DataContextFactory.create(source, query.kind()),
                    query
            );
        }
        return resultPolicy.apply(selection);
    }
}
