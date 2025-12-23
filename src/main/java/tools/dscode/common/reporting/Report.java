package tools.dscode.common.reporting;

import java.util.List;
import java.util.Map;

public abstract class Report {

    // navigation
    public abstract WorkSheet sheet(String sheetName);
    public abstract WorkBook workBook();

    // data
    public abstract Report put(String rowKey, String header, Object value);
    public abstract Report putRow(String rowKey, Map<String, ?> valuesByHeader);

    // column ordering/sorting
    public abstract Report setHeaderOrder(List<String> headerOrder);
    public abstract Report sortHeaders(WorkBook.SortKind kind, WorkBook.SortDirection direction);

    // row ordering/sorting
    public abstract Report setRowKeyOrder(List<String> rowKeyOrder);
    public abstract Report sortRowsByRowKey(WorkBook.SortKind kind, WorkBook.SortDirection direction);
    public abstract Report sortRowsByColumn(String header, WorkBook.SortKind kind, WorkBook.SortDirection direction);

    // typing
    public abstract Report setColumnType(String header, WorkBook.ColumnType type);

    // formatting/layout
    public abstract Report setFreezeHeaderRow(boolean freezeHeaderRow);
    public abstract Report setAutoSizeColumns(boolean autoSizeColumns);
    public abstract Report setDateFormat(String excelDateFormat);
    public abstract Report setDateTimeFormat(String excelDateTimeFormat);

    // optional row-key column
    public abstract Report includeRowKeyColumn(boolean include, String headerName);
}
