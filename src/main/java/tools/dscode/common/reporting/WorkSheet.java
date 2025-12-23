package tools.dscode.common.reporting;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class WorkSheet extends Report {

    protected WorkBook book;            // NOT final (lets WorkBook set book=this)
    protected final String sheetName;   // final is fine

    WorkSheet(WorkBook book, String sheetName) {
        this.book = book; // may be null temporarily for WorkBook ctor
        this.sheetName = Objects.requireNonNull(sheetName, "sheetName");
    }

    public String name() {
        return sheetName;
    }

    @Override
    public WorkBook workBook() {
        return book;
    }

    @Override
    public WorkSheet sheet(String sheetName) {
        return book.sheet(sheetName);
    }

    // -------- data (per-sheet) --------
    @Override
    public WorkSheet put(String rowKey, String header, Object value) {
        book.putInternal(sheetName, rowKey, header, value);
        return this;
    }

    @Override
    public WorkSheet putRow(String rowKey, Map<String, ?> valuesByHeader) {
        book.putRowInternal(sheetName, rowKey, valuesByHeader);
        return this;
    }

    // -------- column ordering/sorting (per-sheet) --------
    @Override
    public WorkSheet setHeaderOrder(List<String> headerOrder) {
        book.applyHeaderOrderSheet(sheetName, headerOrder);
        return this;
    }

    @Override
    public WorkSheet sortHeaders(WorkBook.SortKind kind, WorkBook.SortDirection direction) {
        book.applyHeaderSortSheet(sheetName, kind, direction);
        return this;
    }

    // -------- row ordering/sorting (per-sheet) --------
    @Override
    public WorkSheet setRowKeyOrder(List<String> rowKeyOrder) {
        book.applyRowKeyOrderSheet(sheetName, rowKeyOrder);
        return this;
    }

    @Override
    public WorkSheet sortRowsByRowKey(WorkBook.SortKind kind, WorkBook.SortDirection direction) {
        book.applyRowSortByRowKeySheet(sheetName, kind, direction);
        return this;
    }

    @Override
    public WorkSheet sortRowsByColumn(String header, WorkBook.SortKind kind, WorkBook.SortDirection direction) {
        book.applyRowSortByColumnSheet(sheetName, header, kind, direction);
        return this;
    }

    // -------- typing (per-sheet) --------
    @Override
    public WorkSheet setColumnType(String header, WorkBook.ColumnType type) {
        book.applyColumnTypeSheet(sheetName, header, type);
        return this;
    }

    // -------- formatting/layout (per-sheet) --------
    @Override
    public WorkSheet setFreezeHeaderRow(boolean freezeHeaderRow) {
        book.applyFreezeHeaderRowSheet(sheetName, freezeHeaderRow);
        return this;
    }

    @Override
    public WorkSheet setAutoSizeColumns(boolean autoSizeColumns) {
        book.applyAutoSizeColumnsSheet(sheetName, autoSizeColumns);
        return this;
    }

    @Override
    public WorkSheet setDateFormat(String excelDateFormat) {
        book.applyDateFormatSheet(sheetName, excelDateFormat);
        return this;
    }

    @Override
    public WorkSheet setDateTimeFormat(String excelDateTimeFormat) {
        book.applyDateTimeFormatSheet(sheetName, excelDateTimeFormat);
        return this;
    }

    // -------- row-key column (per-sheet) --------
    @Override
    public WorkSheet includeRowKeyColumn(boolean include, String headerName) {
        book.applyIncludeRowKeyColumnSheet(sheetName, include, headerName);
        return this;
    }
}
