package tools.dscode.common.reporting;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class WorkBook extends WorkSheet {

    public enum ColumnType { STRING, BOOLEAN, INTEGER, DECIMAL, DATE, DATETIME }
    public enum SortKind { ALPHABETIC, NUMERIC }
    public enum SortDirection { ASC, DESC }

    public final Path outputFile;
    private final String defaultSheetName;

    private final LinkedHashMap<String, SheetData> sheets = new LinkedHashMap<>();
    private final SheetDefaults globalDefaults = new SheetDefaults();
    private final DateTimeFormatter isoFallback = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    // ---------------- constructors ----------------

    public WorkBook(Path outputFile) {
        this(outputFile, "Sheet1");
    }

    public WorkBook(Path outputFile, String defaultSheetName) {
        super(null, (defaultSheetName == null || defaultSheetName.isBlank()) ? "Sheet1" : defaultSheetName.trim());
        this.outputFile = Objects.requireNonNull(outputFile, "outputFile");
        this.defaultSheetName = this.sheetName; // inherited sheetName is the default
        this.book = this;
    }

    public WorkBook(String relativePath) {
        this(Path.of(relativePath), "Sheet1");
    }

    public WorkBook(String relativePath, String defaultSheetName) {
        this(Path.of(relativePath), defaultSheetName);
    }

    private String normalizeSheetName(String sheetName) {
        return (sheetName == null || sheetName.isBlank()) ? defaultSheetName : sheetName.trim();
    }

    // ---------------- navigation ----------------

    @Override
    public WorkBook workBook() {
        return this;
    }

    /**
     * IMPORTANT: This does NOT create the sheet. The sheet is created lazily when you:
     * - put data into it (putInternal / putRowInternal), or
     * - apply sheet-scoped config (apply*Sheet helpers)
     */
    @Override
    public synchronized WorkSheet sheet(String sheetName) {
        String name = normalizeSheetName(sheetName);
        return new WorkSheet(this, name);
    }

    // =========================================================
    // Workbook-level config methods apply GLOBALLY
    // =========================================================

    @Override
    public synchronized WorkBook setHeaderOrder(List<String> headerOrder) {
        globalDefaults.explicitHeaderOrder = (headerOrder == null) ? null : List.copyOf(headerOrder);
        for (SheetData s : sheets.values()) s.explicitHeaderOrder = globalDefaults.explicitHeaderOrder;
        return this;
    }

    @Override
    public synchronized WorkBook sortHeaders(SortKind kind, SortDirection direction) {
        globalDefaults.headerSort = new SortSpec(
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(direction, "direction")
        );
        for (SheetData s : sheets.values()) s.headerSort = globalDefaults.headerSort;
        return this;
    }

    @Override
    public synchronized WorkBook setRowKeyOrder(List<String> rowKeyOrder) {
        globalDefaults.rowKeyOrder = (rowKeyOrder == null) ? null : List.copyOf(rowKeyOrder);
        for (SheetData s : sheets.values()) s.rowKeyOrder = globalDefaults.rowKeyOrder;
        return this;
    }

    @Override
    public synchronized WorkBook sortRowsByRowKey(SortKind kind, SortDirection direction) {
        globalDefaults.rowSort = RowSortSpec.byRowKey(
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(direction, "direction")
        );
        for (SheetData s : sheets.values()) s.rowSort = globalDefaults.rowSort;
        return this;
    }

    @Override
    public synchronized WorkBook sortRowsByColumn(String header, SortKind kind, SortDirection direction) {
        Objects.requireNonNull(header, "header");
        globalDefaults.rowSort = RowSortSpec.byColumn(
                header,
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(direction, "direction")
        );
        for (SheetData s : sheets.values()) s.rowSort = globalDefaults.rowSort;
        return this;
    }

    @Override
    public synchronized WorkBook setColumnType(String header, ColumnType type) {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(type, "type");
        globalDefaults.declaredTypes.put(header, type);
        for (SheetData s : sheets.values()) {
            s.declaredTypes.put(header, type);
            s.headersInOrder.add(header);
        }
        return this;
    }

    @Override
    public synchronized WorkBook setFreezeHeaderRow(boolean freezeHeaderRow) {
        globalDefaults.freezeHeaderRow = freezeHeaderRow;
        for (SheetData s : sheets.values()) s.freezeHeaderRow = freezeHeaderRow;
        return this;
    }

    @Override
    public synchronized WorkBook setAutoSizeColumns(boolean autoSizeColumns) {
        globalDefaults.autoSizeColumns = autoSizeColumns;
        for (SheetData s : sheets.values()) s.autoSizeColumns = autoSizeColumns;
        return this;
    }

    @Override
    public synchronized WorkBook setDateFormat(String excelDateFormat) {
        if (excelDateFormat != null && !excelDateFormat.isBlank()) {
            globalDefaults.dateFormat = excelDateFormat;
            for (SheetData s : sheets.values()) s.dateFormat = excelDateFormat;
        }
        return this;
    }

    @Override
    public synchronized WorkBook setDateTimeFormat(String excelDateTimeFormat) {
        if (excelDateTimeFormat != null && !excelDateTimeFormat.isBlank()) {
            globalDefaults.dateTimeFormat = excelDateTimeFormat;
            for (SheetData s : sheets.values()) s.dateTimeFormat = excelDateTimeFormat;
        }
        return this;
    }

    @Override
    public synchronized WorkBook includeRowKeyColumn(boolean include, String headerName) {
        globalDefaults.includeRowKeyColumn = include;
        globalDefaults.rowKeyHeaderName = (headerName == null || headerName.isBlank()) ? "RowKey" : headerName.trim();
        for (SheetData s : sheets.values()) {
            s.includeRowKeyColumn = include;
            s.rowKeyHeaderName = globalDefaults.rowKeyHeaderName;
        }
        return this;
    }

    // =========================================================
    // Sheet-level helpers (used by WorkSheet)
    // =========================================================

    synchronized void applyHeaderOrderSheet(String sheet, List<String> headerOrder) {
        ensureSheet(sheet).explicitHeaderOrder = (headerOrder == null) ? null : List.copyOf(headerOrder);
    }

    synchronized void applyHeaderSortSheet(String sheet, SortKind kind, SortDirection direction) {
        ensureSheet(sheet).headerSort = new SortSpec(
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(direction, "direction")
        );
    }

    synchronized void applyRowKeyOrderSheet(String sheet, List<String> rowKeyOrder) {
        ensureSheet(sheet).rowKeyOrder = (rowKeyOrder == null) ? null : List.copyOf(rowKeyOrder);
    }

    synchronized void applyRowSortByRowKeySheet(String sheet, SortKind kind, SortDirection direction) {
        ensureSheet(sheet).rowSort = RowSortSpec.byRowKey(
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(direction, "direction")
        );
    }

    synchronized void applyRowSortByColumnSheet(String sheet, String header, SortKind kind, SortDirection direction) {
        Objects.requireNonNull(header, "header");
        ensureSheet(sheet).rowSort = RowSortSpec.byColumn(
                header,
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(direction, "direction")
        );
    }

    synchronized void applyColumnTypeSheet(String sheet, String header, ColumnType type) {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(type, "type");
        SheetData s = ensureSheet(sheet);
        s.declaredTypes.put(header, type);
        s.headersInOrder.add(header);
    }

    synchronized void applyFreezeHeaderRowSheet(String sheet, boolean freezeHeaderRow) {
        ensureSheet(sheet).freezeHeaderRow = freezeHeaderRow;
    }

    synchronized void applyAutoSizeColumnsSheet(String sheet, boolean autoSizeColumns) {
        ensureSheet(sheet).autoSizeColumns = autoSizeColumns;
    }

    synchronized void applyDateFormatSheet(String sheet, String excelDateFormat) {
        if (excelDateFormat != null && !excelDateFormat.isBlank()) {
            ensureSheet(sheet).dateFormat = excelDateFormat;
        }
    }

    synchronized void applyDateTimeFormatSheet(String sheet, String excelDateTimeFormat) {
        if (excelDateTimeFormat != null && !excelDateTimeFormat.isBlank()) {
            ensureSheet(sheet).dateTimeFormat = excelDateTimeFormat;
        }
    }

    synchronized void applyIncludeRowKeyColumnSheet(String sheet, boolean include, String headerName) {
        SheetData s = ensureSheet(sheet);
        s.includeRowKeyColumn = include;
        s.rowKeyHeaderName = (headerName == null || headerName.isBlank()) ? "RowKey" : headerName.trim();
    }

    // =========================================================
    // Data insertion (used by WorkSheet)
    // =========================================================

    synchronized void putInternal(String sheet, String rowKey, String header, Object value) {
        Objects.requireNonNull(rowKey, "rowKey");
        Objects.requireNonNull(header, "header");

        SheetData s = ensureSheet(sheet);

        s.headersInOrder.add(header);
        RowData row = s.rowsByKey.computeIfAbsent(rowKey, k -> new RowData(rowKey));
        row.values.put(header, value);

        if (!s.declaredTypes.containsKey(header)) {
            ColumnType current = s.inferredTypes.get(header);
            ColumnType next = inferType(value);

            if (current == null) {
                if (next != null) s.inferredTypes.put(header, next);
            } else if (next != null && current != next) {
                s.inferredTypes.put(header, ColumnType.STRING);
            }
        }
    }

    synchronized void putRowInternal(String sheet, String rowKey, Map<String, ?> valuesByHeader) {
        Objects.requireNonNull(rowKey, "rowKey");
        if (valuesByHeader == null) return;
        for (Map.Entry<String, ?> e : valuesByHeader.entrySet()) {
            putInternal(sheet, rowKey, e.getKey(), e.getValue());
        }
    }

    // =========================================================
    // Write
    // =========================================================

    public void write() throws IOException {
        final List<SheetSnapshot> snapshots;
        synchronized (this) {
            // Only create Sheet1 if literally nothing exists.
            if (sheets.isEmpty()) {
                ensureSheet(defaultSheetName);
            }
            snapshots = snapshotAllSheets();
        }

        Path absoluteFile = outputFile.toAbsolutePath();
        Path parent = absoluteFile.getParent();
        if (parent != null) Files.createDirectories(parent);

        Files.deleteIfExists(absoluteFile);

        try (Workbook wb = new XSSFWorkbook()) {
            for (SheetSnapshot s : snapshots) writeOneSheet(wb, s);
            try (OutputStream out = Files.newOutputStream(absoluteFile)) {
                wb.write(out);
            }
        }
    }

    // ---------------- Sheet storage types + ensureSheet ----------------

    private static final class RowData {
        final String rowKey;
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        RowData(String rowKey) { this.rowKey = rowKey; }
    }

    private static final class SortSpec {
        final SortKind kind;
        final SortDirection direction;
        SortSpec(SortKind kind, SortDirection direction) {
            this.kind = kind;
            this.direction = direction;
        }
    }

    private static final class RowSortSpec {
        final boolean byRowKey;
        final String columnHeader;
        final SortKind kind;
        final SortDirection direction;

        private RowSortSpec(boolean byRowKey, String columnHeader, SortKind kind, SortDirection direction) {
            this.byRowKey = byRowKey;
            this.columnHeader = columnHeader;
            this.kind = kind;
            this.direction = direction;
        }

        static RowSortSpec byRowKey(SortKind kind, SortDirection direction) {
            return new RowSortSpec(true, null, kind, direction);
        }

        static RowSortSpec byColumn(String header, SortKind kind, SortDirection direction) {
            return new RowSortSpec(false, header, kind, direction);
        }
    }

    private static final class SheetDefaults {
        List<String> explicitHeaderOrder = null;
        SortSpec headerSort = null;

        List<String> rowKeyOrder = null;
        RowSortSpec rowSort = null;

        boolean freezeHeaderRow = true;
        boolean autoSizeColumns = true;

        String dateFormat = "yyyy-mm-dd";
        String dateTimeFormat = "yyyy-mm-dd hh:mm:ss";

        boolean includeRowKeyColumn = false;
        String rowKeyHeaderName = "RowKey";

        final Map<String, ColumnType> declaredTypes = new HashMap<>();
    }

    private static final class SheetData {
        final String name;

        final LinkedHashMap<String, RowData> rowsByKey = new LinkedHashMap<>();
        final LinkedHashSet<String> headersInOrder = new LinkedHashSet<>();

        List<String> explicitHeaderOrder = null;
        SortSpec headerSort = null;

        List<String> rowKeyOrder = null;
        RowSortSpec rowSort = null;

        final Map<String, ColumnType> declaredTypes = new HashMap<>();
        final Map<String, ColumnType> inferredTypes = new HashMap<>();

        boolean freezeHeaderRow = true;
        boolean autoSizeColumns = true;

        String dateFormat = "yyyy-mm-dd";
        String dateTimeFormat = "yyyy-mm-dd hh:mm:ss";

        boolean includeRowKeyColumn = false;
        String rowKeyHeaderName = "RowKey";

        SheetData(String name) { this.name = name; }
    }

    private static final class SheetSnapshot {
        final String sheetName;
        final List<String> headers;
        final List<RowData> rows;
        final Map<String, ColumnType> types;

        final boolean freezeHeaderRow;
        final boolean autoSizeColumns;

        final String dateFormat;
        final String dateTimeFormat;

        final boolean includeRowKeyColumn;
        final String rowKeyHeaderName;

        SheetSnapshot(
                String sheetName,
                List<String> headers,
                List<RowData> rows,
                Map<String, ColumnType> types,
                boolean freezeHeaderRow,
                boolean autoSizeColumns,
                String dateFormat,
                String dateTimeFormat,
                boolean includeRowKeyColumn,
                String rowKeyHeaderName
        ) {
            this.sheetName = sheetName;
            this.headers = headers;
            this.rows = rows;
            this.types = types;
            this.freezeHeaderRow = freezeHeaderRow;
            this.autoSizeColumns = autoSizeColumns;
            this.dateFormat = dateFormat;
            this.dateTimeFormat = dateTimeFormat;
            this.includeRowKeyColumn = includeRowKeyColumn;
            this.rowKeyHeaderName = rowKeyHeaderName;
        }
    }

    private synchronized SheetData ensureSheet(String name) {
        String finalName = normalizeSheetName(name);
        return sheets.computeIfAbsent(finalName, n -> {
            SheetData s = new SheetData(n);

            s.explicitHeaderOrder = globalDefaults.explicitHeaderOrder;
            s.headerSort = globalDefaults.headerSort;

            s.rowKeyOrder = globalDefaults.rowKeyOrder;
            s.rowSort = globalDefaults.rowSort;

            s.freezeHeaderRow = globalDefaults.freezeHeaderRow;
            s.autoSizeColumns = globalDefaults.autoSizeColumns;

            s.dateFormat = globalDefaults.dateFormat;
            s.dateTimeFormat = globalDefaults.dateTimeFormat;

            s.includeRowKeyColumn = globalDefaults.includeRowKeyColumn;
            s.rowKeyHeaderName = globalDefaults.rowKeyHeaderName;

            s.declaredTypes.putAll(globalDefaults.declaredTypes);

            return s;
        });
    }

    // ---------------- Snapshot & write implementation ----------------

    private List<SheetSnapshot> snapshotAllSheets() {
        List<SheetSnapshot> out = new ArrayList<>(sheets.size());
        for (SheetData s : sheets.values()) {
            List<String> headers = getFinalHeaderOrder(s);
            Map<String, ColumnType> types = computeFinalTypes(s, headers);
            List<RowData> orderedRows = getFinalRowOrder(s, headers, types);

            boolean includeRowKeyColumn = s.includeRowKeyColumn;
            String rowKeyHeaderName = s.rowKeyHeaderName;

            List<String> effectiveHeaders = headers;
            if (includeRowKeyColumn) {
                LinkedHashSet<String> tmp = new LinkedHashSet<>();
                tmp.add(rowKeyHeaderName);
                tmp.addAll(headers);
                effectiveHeaders = List.copyOf(tmp);

                types = new HashMap<>(types);
                types.put(rowKeyHeaderName, ColumnType.STRING);
            }

            out.add(new SheetSnapshot(
                    s.name,
                    effectiveHeaders,
                    orderedRows,
                    types,
                    s.freezeHeaderRow,
                    s.autoSizeColumns,
                    s.dateFormat,
                    s.dateTimeFormat,
                    includeRowKeyColumn,
                    rowKeyHeaderName
            ));
        }
        return out;
    }

    private void writeOneSheet(Workbook wb, SheetSnapshot snap) {
        DataFormat dataFormat = wb.createDataFormat();
        Sheet sheet = wb.createSheet(snap.sheetName);

        // Styles
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        // Row key cells bold by default *if* row key column is included
        CellStyle rowKeyStyle = wb.createCellStyle();
        Font rowKeyFont = wb.createFont();
        rowKeyFont.setBold(true);
        rowKeyStyle.setFont(rowKeyFont);

        CellStyle dateStyle = wb.createCellStyle();
        dateStyle.setDataFormat(dataFormat.getFormat(snap.dateFormat));

        CellStyle dateTimeStyle = wb.createCellStyle();
        dateTimeStyle.setDataFormat(dataFormat.getFormat(snap.dateTimeFormat));

        // Header row
        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < snap.headers.size(); c++) {
            Cell cell = headerRow.createCell(c, CellType.STRING);
            cell.setCellValue(snap.headers.get(c));
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        int r = 1;
        for (RowData rowData : snap.rows) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < snap.headers.size(); c++) {
                String header = snap.headers.get(c);
                Cell cell = row.createCell(c);

                if (snap.includeRowKeyColumn && header.equals(snap.rowKeyHeaderName)) {
                    cell.setCellType(CellType.STRING);
                    cell.setCellValue(rowData.rowKey);
                    cell.setCellStyle(rowKeyStyle);
                    continue;
                }

                Object raw = rowData.values.get(header);
                ColumnType type = snap.types.getOrDefault(header, ColumnType.STRING);
                writeCellValue(cell, raw, type, dateStyle, dateTimeStyle);
            }
        }

        if (snap.freezeHeaderRow) sheet.createFreezePane(0, 1);

        if (snap.autoSizeColumns) {
            for (int c = 0; c < snap.headers.size(); c++) sheet.autoSizeColumn(c);
        }
    }

    // ---------------- Ordering and sorting ----------------

    private List<String> getFinalHeaderOrder(SheetData s) {
        if (s.explicitHeaderOrder != null) {
            LinkedHashSet<String> finalOrder = new LinkedHashSet<>();
            for (String h : s.explicitHeaderOrder) {
                if (h != null && !h.isBlank()) finalOrder.add(h.trim());
            }
            for (String h : s.headersInOrder) {
                if (h != null && !h.isBlank()) finalOrder.add(h.trim());
            }
            return List.copyOf(finalOrder);
        }

        List<String> headers = new ArrayList<>();
        for (String h : s.headersInOrder) {
            if (h != null && !h.isBlank()) headers.add(h.trim());
        }

        if (s.headerSort != null) {
            Comparator<String> cmp = (a, b) -> compareByKind(s.headerSort.kind, s.headerSort.direction, a, b);
            headers.sort(cmp);
        }

        return List.copyOf(headers);
    }

    private List<RowData> getFinalRowOrder(SheetData s, List<String> headers, Map<String, ColumnType> types) {
        List<RowData> rows = new ArrayList<>(s.rowsByKey.values());

        if (s.rowKeyOrder != null) {
            Map<String, RowData> map = new HashMap<>();
            for (RowData rd : rows) map.put(rd.rowKey, rd);

            List<RowData> ordered = new ArrayList<>(rows.size());
            Set<String> seen = new HashSet<>();

            for (String key : s.rowKeyOrder) {
                if (key == null) continue;
                RowData rd = map.get(key);
                if (rd != null) {
                    ordered.add(rd);
                    seen.add(key);
                }
            }

            for (RowData rd : rows) {
                if (!seen.contains(rd.rowKey)) ordered.add(rd);
            }

            return ordered;
        }

        if (s.rowSort != null) {
            Comparator<RowData> cmp = rowComparator(s.rowSort, types);
            rows.sort(cmp);
        }

        return rows;
    }

    private Comparator<RowData> rowComparator(RowSortSpec spec, Map<String, ColumnType> types) {
        Comparator<RowData> base;

        if (spec.byRowKey) {
            base = (a, b) -> compareByKind(spec.kind, spec.direction, a.rowKey, b.rowKey);
        } else {
            String col = spec.columnHeader;
            ColumnType type = types.getOrDefault(col, ColumnType.STRING);
            base = (a, b) -> compareCellValues(spec.kind, spec.direction, type, a.values.get(col), b.values.get(col));
        }

        return base.thenComparing(a -> a.rowKey, Comparator.nullsFirst(String::compareToIgnoreCase));
    }

    private int compareByKind(SortKind kind, SortDirection direction, Object a, Object b) {
        int out;
        if (kind == SortKind.NUMERIC) {
            BigDecimal da = coerceBigDecimal(a);
            BigDecimal db = coerceBigDecimal(b);
            out = compareNullable(da, db, Comparator.naturalOrder());
        } else {
            String sa = (a == null) ? null : String.valueOf(a);
            String sb = (b == null) ? null : String.valueOf(b);
            out = compareNullable(sa, sb, String.CASE_INSENSITIVE_ORDER);
        }
        return (direction == SortDirection.DESC) ? -out : out;
    }

    private int compareCellValues(SortKind kind, SortDirection direction, ColumnType type, Object a, Object b) {
        if (kind == SortKind.NUMERIC) {
            BigDecimal da = coerceBigDecimal(a);
            BigDecimal db = coerceBigDecimal(b);
            int out = compareNullable(da, db, Comparator.naturalOrder());
            return (direction == SortDirection.DESC) ? -out : out;
        }

        String sa = (a == null) ? null : stringifyForSort(type, a);
        String sb = (b == null) ? null : stringifyForSort(type, b);

        int out = compareNullable(sa, sb, String.CASE_INSENSITIVE_ORDER);
        return (direction == SortDirection.DESC) ? -out : out;
    }

    private String stringifyForSort(ColumnType type, Object o) {
        if (o == null) return null;
        if (type == ColumnType.DATE) {
            LocalDate d = coerceLocalDate(o);
            return (d != null) ? d.toString() : String.valueOf(o);
        }
        if (type == ColumnType.DATETIME) {
            Date dt = coerceDate(o);
            if (dt != null) return dt.toInstant().toString();
        }
        return String.valueOf(o);
    }

    private static <T> int compareNullable(T a, T b, Comparator<T> cmp) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;      // nulls last
        if (b == null) return -1;
        return cmp.compare(a, b);
    }

    // ---------------- Type resolution ----------------

    private Map<String, ColumnType> computeFinalTypes(SheetData s, List<String> headers) {
        Map<String, ColumnType> out = new HashMap<>();
        for (String h : headers) {
            ColumnType declared = s.declaredTypes.get(h);
            if (declared != null) {
                out.put(h, declared);
                continue;
            }
            ColumnType inferred = s.inferredTypes.get(h);
            out.put(h, inferred != null ? inferred : ColumnType.STRING);
        }
        return out;
    }

    // ---------------- Cell writing & coercion ----------------

    private void writeCellValue(
            Cell cell,
            Object raw,
            ColumnType type,
            CellStyle dateStyle,
            CellStyle dateTimeStyle
    ) {
        if (raw == null) {
            cell.setBlank();
            return;
        }

        if (type == ColumnType.STRING) {
            cell.setCellType(CellType.STRING);
            cell.setCellValue(stringify(raw));
            return;
        }

        try {
            switch (type) {
                case BOOLEAN -> {
                    cell.setCellType(CellType.BOOLEAN);
                    cell.setCellValue(coerceBoolean(raw));
                }
                case INTEGER -> {
                    BigInteger bi = coerceBigInteger(raw);
                    if (bi == null) {
                        cell.setCellType(CellType.STRING);
                        cell.setCellValue(stringify(raw));
                    } else if (bi.bitLength() > 53) {
                        cell.setCellType(CellType.STRING);
                        cell.setCellValue(bi.toString());
                    } else {
                        cell.setCellType(CellType.NUMERIC);
                        cell.setCellValue(bi.doubleValue());
                    }
                }
                case DECIMAL -> {
                    BigDecimal bd = coerceBigDecimal(raw);
                    if (bd == null) {
                        cell.setCellType(CellType.STRING);
                        cell.setCellValue(stringify(raw));
                    } else {
                        cell.setCellType(CellType.NUMERIC);
                        cell.setCellValue(bd.doubleValue());
                    }
                }
                case DATE -> {
                    LocalDate d = coerceLocalDate(raw);
                    if (d == null) {
                        cell.setCellType(CellType.STRING);
                        cell.setCellValue(stringify(raw));
                    } else {
                        cell.setCellType(CellType.NUMERIC);
                        cell.setCellValue(Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()));
                        cell.setCellStyle(dateStyle);
                    }
                }
                case DATETIME -> {
                    Date dt = coerceDate(raw);
                    if (dt == null) {
                        cell.setCellType(CellType.STRING);
                        cell.setCellValue(stringify(raw));
                    } else {
                        cell.setCellType(CellType.NUMERIC);
                        cell.setCellValue(dt);
                        cell.setCellStyle(dateTimeStyle);
                    }
                }
                default -> {
                    cell.setCellType(CellType.STRING);
                    cell.setCellValue(stringify(raw));
                }
            }
        } catch (RuntimeException ex) {
            cell.setCellType(CellType.STRING);
            cell.setCellValue(stringify(raw));
        }
    }

    private String stringify(Object o) {
        if (o == null) return "";
        if (o instanceof OffsetDateTime odt) return odt.format(isoFallback);
        if (o instanceof ZonedDateTime zdt) return zdt.toOffsetDateTime().format(isoFallback);
        if (o instanceof Instant i) return isoFallback.format(i.atOffset(ZoneOffset.UTC));
        return String.valueOf(o);
    }

    private boolean coerceBoolean(Object raw) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private BigInteger coerceBigInteger(Object raw) {
        if (raw instanceof BigInteger bi) return bi;
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            return BigInteger.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof BigDecimal bd) {
            try { return bd.toBigIntegerExact(); } catch (ArithmeticException ignore) { return null; }
        }
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (Math.floor(d) != d) return null;
            return BigInteger.valueOf((long) d);
        }
        try {
            return new BigInteger(String.valueOf(raw).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal coerceBigDecimal(Object raw) {
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof BigInteger bi) return new BigDecimal(bi);
        if (raw instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate coerceLocalDate(Object raw) {
        if (raw instanceof LocalDate d) return d;
        if (raw instanceof LocalDateTime dt) return dt.toLocalDate();
        if (raw instanceof Instant i) return i.atZone(ZoneId.systemDefault()).toLocalDate();
        if (raw instanceof Date d) return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return null;
    }

    private Date coerceDate(Object raw) {
        if (raw instanceof Date d) return d;
        if (raw instanceof Instant i) return Date.from(i);
        if (raw instanceof LocalDateTime ldt) return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        if (raw instanceof OffsetDateTime odt) return Date.from(odt.toInstant());
        if (raw instanceof ZonedDateTime zdt) return Date.from(zdt.toInstant());
        return null;
    }

    private ColumnType inferType(Object value) {
        if (value == null) return null;

        if (value instanceof Boolean) return ColumnType.BOOLEAN;

        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger)
            return ColumnType.INTEGER;

        if (value instanceof Float || value instanceof Double || value instanceof BigDecimal)
            return ColumnType.DECIMAL;

        if (value instanceof LocalDate) return ColumnType.DATE;

        if (value instanceof LocalDateTime || value instanceof Instant || value instanceof OffsetDateTime || value instanceof ZonedDateTime)
            return ColumnType.DATETIME;

        return ColumnType.STRING;
    }
}
