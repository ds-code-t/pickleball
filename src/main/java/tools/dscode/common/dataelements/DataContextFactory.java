package tools.dscode.common.dataelements;

import java.util.Objects;

import tools.dscode.engine.context.ExecutionContext;

public final class DataContextFactory {
    private DataContextFactory() {
    }

    public static DataContext create(
            Object source,
            DataElementKind declaredKind
    ) {
        return create(source, declaredKind, ExecutionContext.current());
    }

    public static DataContext create(
            Object source,
            DataElementKind declaredKind,
            ExecutionContext context
    ) {
        Objects.requireNonNull(declaredKind, "declaredKind");
        return new DataContext(
                source,
                declaredKind,
                TabularDataAdapter.adapt(source),
                DataSourceMetadata.of(source)
        ).executionContext(context);
    }

    public static DataContext table(Object source) {
        return create(source, DataElementKind.DATA_TABLE);
    }
}
