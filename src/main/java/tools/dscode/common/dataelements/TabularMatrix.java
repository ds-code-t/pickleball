package tools.dscode.common.dataelements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class TabularMatrix {
    private final List<List<TabularCell>> rows;
    private final int width;

    private TabularMatrix(List<List<TabularCell>> rows) {
        List<List<TabularCell>> copiedRows =
                new ArrayList<>(rows.size());
        int maxWidth = 0;
        for (List<TabularCell> row : rows) {
            List<TabularCell> copiedRow = List.copyOf(
                    row == null ? List.of() : row
            );
            copiedRows.add(copiedRow);
            maxWidth = Math.max(maxWidth, copiedRow.size());
        }

        this.rows = Collections.unmodifiableList(copiedRows);
        this.width = maxWidth;
    }

    public static TabularMatrix empty() {
        return new TabularMatrix(List.of());
    }

    public static TabularMatrix fromRows(
            List<? extends List<?>> sourceRows
    ) {
        if (sourceRows == null || sourceRows.isEmpty()) {
            return empty();
        }
        List<List<TabularCell>> rows =
                new ArrayList<>(sourceRows.size());
        for (List<?> sourceRow : sourceRows) {
            List<TabularCell> row = new ArrayList<>();
            if (sourceRow != null) {
                for (Object value : sourceRow) {
                    row.add(TabularCell.of(value));
                }
            }
            rows.add(row);
        }
        return new TabularMatrix(rows);
    }

    static TabularMatrix fromCells(
            List<List<TabularCell>> sourceRows
    ) {
        return new TabularMatrix(
                sourceRows == null ? List.of() : sourceRows
        );
    }

    public int rowCount() {
        return rows.size();
    }

    public int width() {
        return width;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int physicalRowLength(int row) {
        return rows.get(row).size();
    }

    public List<TabularCell> physicalRow(int row) {
        return rows.get(row);
    }

    public TabularCell cell(int row, int column) {
        if (row < 0 || row >= rowCount() || column < 0) {
            return TabularCell.missingCell();
        }
        List<TabularCell> oneRow = rows.get(row);
        return column < oneRow.size()
                ? oneRow.get(column)
                : TabularCell.missingCell();
    }

    public boolean hasPhysicalCell(int row, int column) {
        return row >= 0
                && row < rowCount()
                && column >= 0
                && column < rows.get(row).size();
    }

    public List<Object> physicalValues(int row) {
        List<Object> values =
                new ArrayList<>(physicalRowLength(row));
        for (TabularCell cell : physicalRow(row)) {
            values.add(cell.externalValue());
        }
        return Collections.unmodifiableList(values);
    }

    public List<Object> rectangularRowValues(int row) {
        List<Object> values = new ArrayList<>(width);
        for (int column = 0; column < width; column++) {
            values.add(cell(row, column).externalValue());
        }
        return Collections.unmodifiableList(values);
    }

    public TabularMatrix withCell(
            int row,
            int column,
            Object value
    ) {
        if (!hasPhysicalCell(row, column)) {
            throw new DataQueryException(
                    "Cannot replace missing table cell at row "
                            + row + ", column " + column + "."
            );
        }
        List<List<TabularCell>> copiedRows =
                new ArrayList<>(rows.size());
        for (int rowIndex = 0;
             rowIndex < rows.size();
             rowIndex++) {
            List<TabularCell> copiedRow =
                    new ArrayList<>(rows.get(rowIndex));
            if (rowIndex == row) {
                copiedRow.set(column, TabularCell.of(value));
            }
            copiedRows.add(copiedRow);
        }
        return fromCells(copiedRows);
    }

    public TabularMatrix transpose() {
        if (isEmpty() || width == 0) {
            return empty();
        }
        List<List<TabularCell>> transposed =
                new ArrayList<>(width);
        for (int column = 0; column < width; column++) {
            List<TabularCell> row =
                    new ArrayList<>(rowCount());
            for (int sourceRow = 0;
                 sourceRow < rowCount();
                 sourceRow++) {
                row.add(cell(sourceRow, column));
            }
            transposed.add(row);
        }
        return fromCells(transposed);
    }

    public List<List<String>> toStringRows() {
        if (isEmpty()) {
            return List.of();
        }
        int outputWidth = Math.max(width, 1);
        List<List<String>> output =
                new ArrayList<>(rowCount());
        for (int row = 0; row < rowCount(); row++) {
            List<String> values =
                    new ArrayList<>(outputWidth);
            for (int column = 0;
                 column < outputWidth;
                 column++) {
                TabularCell cell = cell(row, column);
                values.add(DataStringFormatter.tableCell(cell));
            }
            output.add(values);
        }
        return Collections.unmodifiableList(output);
    }

    public TabularMatrix copy() {
        return new TabularMatrix(rows);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TabularMatrix matrix
                && rows.equals(matrix.rows);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rows);
    }
}
