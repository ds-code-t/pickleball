package tools.dscode.common.dataelements;

import io.cucumber.datatable.DataTable;

import java.util.List;
import java.util.Objects;

public final class DataContext {
    private final Object nativeSource;
    private final DataElementKind declaredKind;
    private final TabularMatrix privateWorkingValue;
    private final DataSourceMetadata sourceMetadata;
    private final boolean sourceNull;
    private boolean modified;

    DataContext(
            Object nativeSource,
            DataElementKind declaredKind,
            TabularMatrix privateWorkingValue,
            DataSourceMetadata sourceMetadata
    ) {
        this.nativeSource = nativeSource;
        this.declaredKind = Objects.requireNonNull(declaredKind);
        this.privateWorkingValue = Objects.requireNonNull(privateWorkingValue);
        this.sourceMetadata = Objects.requireNonNull(sourceMetadata);
        this.sourceNull = nativeSource == null;
    }

    public Object nativeSource() {
        return nativeSource;
    }

    public DataElementKind declaredKind() {
        return declaredKind;
    }

    public TabularMatrix workingMatrix() {
        return privateWorkingValue;
    }

    public DataSourceMetadata sourceMetadata() {
        return sourceMetadata;
    }

    public boolean sourceNull() {
        return sourceNull;
    }

    public boolean modified() {
        return modified;
    }

    void markModified() {
        modified = true;
    }

    public Object materializeDeclaredType() {
        if (!modified && nativeSource != null) {
            if (declaredKind == DataElementKind.DATA_TABLE
                    && nativeSource instanceof DataTable) {
                return nativeSource;
            }
            if (declaredKind == DataElementKind.DATA_LIST
                    && nativeSource instanceof List<?>) {
                return nativeSource;
            }
        }

        return switch (declaredKind) {
            case DATA_TABLE -> DataMaterializer.toDataTable(privateWorkingValue);
            case DATA_LIST -> privateWorkingValue.isEmpty()
                    ? List.of()
                    : privateWorkingValue.physicalValues(0);
            default -> privateWorkingValue;
        };
    }

    public Object convertTo(DataElementKind requestedKind) {
        return switch (requestedKind) {
            case DATA_TABLE -> DataMaterializer.toDataTable(privateWorkingValue);
            case DATA_LIST -> privateWorkingValue.isEmpty()
                    ? List.of()
                    : privateWorkingValue.physicalValues(0);
            default -> throw new IllegalArgumentException(
                    "Tabular DataContext conversion is not implemented for "
                            + requestedKind.singularName()
            );
        };
    }
}
