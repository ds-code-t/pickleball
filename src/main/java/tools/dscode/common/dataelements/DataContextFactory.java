package tools.dscode.common.dataelements;

import java.util.Objects;

public final class DataContextFactory {
    private DataContextFactory() {
    }

    public static DataContext create(
            Object source,
            DataElementKind declaredKind
    ) {
        Objects.requireNonNull(declaredKind, "declaredKind");
        return new DataContext(
                source,
                declaredKind,
                TabularDataAdapter.adapt(source),
                DataSourceMetadata.of(source)
        );
    }

    public static DataContext table(Object source) {
        return create(source, DataElementKind.DATA_TABLE);
    }
}
