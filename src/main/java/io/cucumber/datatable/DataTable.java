/*
 * This file incorporates work covered by the following copyright and permission notice:
 *
 * Copyright (c) Cucumber Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.cucumber.datatable;

import io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument;
import io.cucumber.core.stepexpression.DataTableArgument;
import io.cucumber.messages.types.*;
import io.pickleball.mapandStateutilities.LinkedMultiMap;
import org.apiguardian.api.API;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

import static io.cucumber.datatable.CucumberDataTableException.duplicateKeyException;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

/**
 * A m-by-n table of string values. For example:
 *
 * <pre>
 * |     | firstName   | lastName | birthDate  |
 * | 4a1 | Annie M. G. | Schmidt  | 1911-03-20 |
 * | c92 | Roald       | Dahl     | 1916-09-13 |
 * </pre>
 * <p>
 * A table is either empty or contains one or more values. As such if a table
 * has zero height it must have zero width and vice versa.
 * <p>
 * The first row of the the table may be referred to as the table header. The
 * remaining cells as the table body.
 * <p>
 * A table can be converted into an object of an arbitrary type by a
 * {@link TableConverter}. A table created without a table converter will throw
 * a {@link NoConverterDefined} exception when doing so.
 * <p>
 * Several methods are provided to convert tables to common data structures such
 * as lists, maps, ect. These methods have the form {@code asX} and will use the
 * provided data table converter. A DataTable is immutable and thread safe.
 */
@API(status = API.Status.STABLE)
public class DataTable {

    private final List<List<String>> raw;
    private final TableConverter tableConverter;

    /**
     * Creates a new DataTable.
     * <p>
     * To improve performance this constructor assumes the provided raw table is
     * rectangular, immutable and a safe copy.
     *
     * @param  raw                  the underlying table
     * @param  tableConverter       to transform the table
     * @throws NullPointerException if either raw or tableConverter is null
     */
    protected DataTable(List<List<String>> raw, TableConverter tableConverter) {
        if (raw == null)
            throw new NullPointerException("cells can not be null");
        if (tableConverter == null)
            throw new NullPointerException("tableConverter can not be null");
        this.raw = raw;
        this.tableConverter = tableConverter;
    }


    /**
     * Creates a new DataTable.
     * <p>
     *
     * @param  raw                      the underlying table
     * @return                          a new data table containing the raw
     *                                  values
     * @throws NullPointerException     if raw is null
     * @throws IllegalArgumentException when the table is not rectangular or
     *                                  contains null values.
     */
    public static DataTable create(List<List<String>> raw) {
        return create(raw, new NoConverterDefined());
    }

    /**
     * Creates a new DataTable with a table converter.
     *
     * @param  raw                      the underlying table
     * @param  tableConverter           to transform the table
     * @return                          a new data table containing the raw
     *                                  values
     * @throws NullPointerException     if either raw or tableConverter is null
     * @throws IllegalArgumentException when the table is not rectangular or
     *                                  contains null values
     */
    public static DataTable create(List<List<String>> raw, TableConverter tableConverter) {
        return new DataTable(copy(requireRectangularTable(raw)), tableConverter);
    }

    private static List<List<String>> copy(List<List<String>> balanced) {
        List<List<String>> rawCopy = new ArrayList<>(balanced.size());
        for (List<String> row : balanced) {
            // A table without columns is an empty table and has no rows.
            if (row.isEmpty()) {
                return emptyList();
            }

            List<String> rowCopy = new ArrayList<>(row.size());
            rowCopy.addAll(row);
            rawCopy.add(unmodifiableList(rowCopy));
        }
        return unmodifiableList(rawCopy);
    }

    private static List<List<String>> requireRectangularTable(List<List<String>> table) {
        int columns = table.isEmpty() ? 0 : table.get(0).size();
        for (List<String> row : table) {
            if (columns != row.size()) {
                throw new IllegalArgumentException(String
                        .format("Table is not rectangular: expected %s column(s) but found %s.", columns, row.size()));
            }
        }
        return table;
    }

    /**
     * Creates an empty DataTable.
     *
     * @return an empty DataTable
     */
    public static DataTable emptyDataTable() {
        return new DataTable(Collections.emptyList(), new NoConverterDefined());
    }

    /**
     * Returns the table converter of this data table.
     *
     * @return the tables table converter
     */
    public TableConverter getTableConverter() {
        return tableConverter;
    }

    /**
     * Performs a diff against an other instance.
     *
     * @param  actual             the other table to diff with
     * @throws TableDiffException if the tables are different
     */
    public void diff(DataTable actual) throws TableDiffException {
        TableDiffer tableDiffer = new TableDiffer(this, actual);
        DataTableDiff dataTableDiff = tableDiffer.calculateDiffs();
        if (!dataTableDiff.isEmpty()) {
            throw TableDiffException.diff(dataTableDiff);
        }
    }

    /**
     * Performs an unordered diff against an other instance.
     *
     * @param  actual             the other table to diff with
     * @throws TableDiffException if the tables are different
     */
    public void unorderedDiff(DataTable actual) throws TableDiffException {
        TableDiffer tableDiffer = new TableDiffer(this, actual);
        DataTableDiff dataTableDiff = tableDiffer.calculateUnorderedDiffs();
        if (!dataTableDiff.isEmpty()) {
            throw TableDiffException.diff(dataTableDiff);
        }
    }

    /**
     * Returns the values in the table as a single list. Contains the cells
     * ordered from left to right, top to bottom, starting at the top left.
     *
     * @return the values of the table
     */
    public List<String> values() {
        return new ListView();
    }

    /**
     * Converts the table to a list of {@code String}s.
     *
     * @return a list of strings
     * @see    TableConverter#toList(DataTable, Type)
     */
    public List<String> asList() {
        return asList(String.class);
    }

    /**
     * Converts the table to a list of {@code itemType}.
     *
     * @param  itemType the type of the list items
     * @param  <T>      the type of the list items
     * @return          a list of objects
     * @see             TableConverter#toList(DataTable, Type)
     */
    public <T> List<T> asList(Class<T> itemType) {
        return tableConverter.toList(this, itemType);
    }

    /**
     * Converts the table to a list of {@code itemType}.
     *
     * @param  itemType the type of the list items
     * @param  <T>      the type of the list items
     * @return          a list of objects
     * @see             TableConverter#toList(DataTable, Type)
     */
    public <T> List<T> asList(Type itemType) {
        return tableConverter.toList(this, itemType);
    }

    /**
     * Converts the table to a list of lists of {@code String}s.
     *
     * @return a list of list of strings
     * @see    TableConverter#toLists(DataTable, Type)
     */
    public List<List<String>> asLists() {
        return asLists(String.class);
    }

    /**
     * Converts the table to a list of lists of {@code itemType}.
     *
     * @param  itemType the type of the list items
     * @param  <T>      the type of the list items
     * @return          a list of list of objects
     * @see             TableConverter#toLists(DataTable, Type)
     */
    public <T> List<List<T>> asLists(Class<T> itemType) {
        return tableConverter.toLists(this, itemType);
    }

    /**
     * Converts the table to a list of lists of {@code itemType}.
     *
     * @param  itemType the type of the list items
     * @param  <T>      the type of the list items
     * @return          a list of list of objects
     * @see             TableConverter#toLists(DataTable, Type)
     */
    public <T> List<List<T>> asLists(Type itemType) {
        return tableConverter.toLists(this, itemType);
    }

    /**
     * Converts the table to a single map of {@code String} to {@code String}.
     * <p>
     * For each row the first cell is used to create the key value. The
     * remaining cells are used to create the value. If the table only has a
     * single column that value is null.
     *
     * @return a map
     * @see    TableConverter#toMap(DataTable, Type, Type)
     */
    public Map<String, String> asMap() {
        return asMap(String.class, String.class);
    }

    /**
     * Converts the table to a single map of {@code keyType} to
     * {@code valueType}.
     * <p>
     * For each row the first cell is used to create the key value. The
     * remaining cells are used to create the value. If the table only has a
     * single column that value is null.
     *
     * @param  <K>       key type
     * @param  <V>       value type
     * @param  keyType   key type
     * @param  valueType value type
     * @return           a map
     * @see              TableConverter#toMap(DataTable, Type, Type)
     */
    public <K, V> Map<K, V> asMap(Class<K> keyType, Class<V> valueType) {
        return tableConverter.toMap(this, keyType, valueType);
    }

    /**
     * Converts the table to a single map of {@code keyType} to
     * {@code valueType}.
     * <p>
     * For each row the first cell is used to create the key value. The
     * remaining cells are used to create the value. If the table only has a
     * single column that value is null.
     *
     * @param  <K>       key type
     * @param  <V>       value type
     * @param  keyType   key type
     * @param  valueType value type
     * @return           a map
     * @see              TableConverter#toMap(DataTable, Type, Type)
     */
    public <K, V> Map<K, V> asMap(Type keyType, Type valueType) {
        return tableConverter.toMap(this, keyType, valueType);
    }

    /**
     * Returns a view of the entries in a table. An entry is a map of the header
     * values to the corresponding values in a row in the body of the table.
     *
     * @return a view of the entries in a table.
     */
    public List<Map<String, String>> entries() {
        if (raw.isEmpty())
            return emptyList();

        List<String> headers = raw.get(0);
        List<Map<String, String>> headersAndRows = new ArrayList<>();

        for (int i = 1; i < raw.size(); i++) {
            List<String> row = raw.get(i);
            LinkedHashMap<String, String> headersAndRow = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) {
                String key = headers.get(j);
                String value = row.get(j);
                if (headersAndRow.containsKey(key)) {
                    String wouldBeReplaced = headersAndRow.get(key);
                    throw duplicateKeyException(String.class, String.class, key, value, wouldBeReplaced);
                }
                headersAndRow.put(key, value);
            }
            headersAndRows.add(unmodifiableMap(headersAndRow));
        }

        return unmodifiableList(headersAndRows);
    }

    /**
     * Converts the table to a list of maps of strings. For each row in the body
     * of the table a map is created containing a mapping of column headers to
     * the column cell of that row.
     *
     * @return a list of maps
     * @see    TableConverter#toMaps(DataTable, Type, Type)
     */
    public List<Map<String, String>> asMaps() {
        return asMaps(String.class, String.class);
    }

    /**
     * Converts the table to a list of maps of {@code keyType} to
     * {@code valueType}. For each row in the body of the table a map is created
     * containing a mapping of column headers to the column cell of that row.
     *
     * @param  <K>       key type
     * @param  <V>       value type
     * @param  keyType   key type
     * @param  valueType value type
     * @return           a list of maps
     * @see              TableConverter#toMaps(DataTable, Type, Type)
     */
    public <K, V> List<Map<K, V>> asMaps(Type keyType, Type valueType) {
        return tableConverter.toMaps(this, keyType, valueType);
    }

    /**
     * Converts the table to a list of maps of {@code keyType} to
     * {@code valueType}. For each row in the body of the table a map is created
     * containing a mapping of column headers to the column cell of that row.
     *
     * @param  <K>       key type
     * @param  <V>       value type
     * @param  keyType   key type
     * @param  valueType value type
     * @return           a list of maps
     * @see              TableConverter#toMaps(DataTable, Type, Type)
     */
    public <K, V> List<Map<K, V>> asMaps(Class<K> keyType, Class<V> valueType) {
        return tableConverter.toMaps(this, keyType, valueType);
    }

    /**
     * Returns the cells of the table.
     *
     * @return the cells of the table
     */
    public List<List<String>> cells() {
        return raw;
    }

    /**
     * Returns a single table cell.
     *
     * @param  row                       row index of the cell
     * @param  column                    column index of the cell
     * @return                           a single table cell
     * @throws IndexOutOfBoundsException when either {@code row} or
     *                                   {@code column} is outside the table.
     */
    public String cell(int row, int column) {
        rangeCheckRow(row, height());
        rangeCheckColumn(column, width());
        return raw.get(row).get(column);
    }

    private static void rangeCheck(int index, int size) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("index: " + index + ", Size: " + size);
    }

    private static void rangeCheckRow(int row, int height) {
        if (row < 0 || row >= height)
            throw new IndexOutOfBoundsException("row: " + row + ", Height: " + height);
    }

    private static void rangeCheckColumn(int column, int width) {
        if (column < 0 || column >= width)
            throw new IndexOutOfBoundsException("column: " + column + ", Width: " + width);
    }

    /**
     * Returns a single column.
     *
     * @param  column                    column index the column
     * @return                           a single column
     * @throws IndexOutOfBoundsException when {@code column} is outside the
     *                                   table.
     */
    public List<String> column(final int column) {
        return new ColumnView(column);
    }

    /**
     * Returns a table that is a view on a portion of this table. The sub table
     * begins at {@code fromColumn} inclusive and extends to the end of that
     * table.
     *
     * @param  fromColumn                the beginning column index, inclusive
     * @return                           the specified sub table
     * @throws IndexOutOfBoundsException when any endpoint is outside the table.
     * @throws IllegalArgumentException  when a from endpoint comes after an to
     *                                   endpoint
     */
    public DataTable columns(final int fromColumn) {
        return columns(fromColumn, width());
    }

    /**
     * Returns a table that is a view on a portion of this table. The sub table
     * begins at {@code fromColumn} inclusive and extends to {@code toColumn}
     * exclusive.
     *
     * @param  fromColumn                the beginning column index, inclusive
     * @param  toColumn                  the end column index, exclusive
     * @return                           the specified sub table
     * @throws IndexOutOfBoundsException when any endpoint is outside the table.
     * @throws IllegalArgumentException  when a from endpoint comes after an to
     *                                   endpoint
     */
    public DataTable columns(final int fromColumn, final int toColumn) {
        return subTable(0, fromColumn, height(), toColumn);
    }

    /**
     * Converts a table to {@code type}.
     *
     * @param  type       the desired type
     * @param  transposed transpose the table before transformation
     * @param  <T>        the desired type
     * @return            an instance of {@code type}
     */
    public <T> T convert(Class<T> type, boolean transposed) {
        return tableConverter.convert(this, type, transposed);
    }

    /**
     * Converts a table to {@code type}.
     *
     * @param  type       the desired type
     * @param  transposed transpose the table before transformation
     * @param  <T>        the desired type
     * @return            an instance of {@code type}
     */
    public <T> T convert(Type type, boolean transposed) {
        return tableConverter.convert(this, type, transposed);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        DataTable dataTable = (DataTable) o;

        return raw.equals(dataTable.raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /**
     * Returns true iff this table has no cells.
     *
     * @return true iff this table has no cells
     */
    public boolean isEmpty() {
        return raw.isEmpty();
    }

    /**
     * Returns a single row.
     *
     * @param  row                       row index the column
     * @return                           a single row
     * @throws IndexOutOfBoundsException when {@code row} is outside the table.
     */
    public List<String> row(int row) {
        rangeCheckRow(row, height());
        return raw.get(row);
    }

    /**
     * Returns a table that is a view on a portion of this table. The sub table
     * begins at {@code fromRow} inclusive and extends to the end of that table.
     *
     * @param  fromRow                   the beginning row index, inclusive
     * @return                           the specified sub table
     * @throws IndexOutOfBoundsException when any endpoint is outside the table.
     * @throws IllegalArgumentException  when a from endpoint comes after an to
     *                                   endpoint
     */
    public DataTable rows(int fromRow) {
        return rows(fromRow, height());
    }

    /**
     * Returns a table that is a view on a portion of this table. The sub table
     * begins at {@code fromRow} inclusive and extends to {@code toRow}
     * exclusive.
     *
     * @param  fromRow                   the beginning row index, inclusive
     * @param  toRow                     the end row index, exclusive
     * @return                           the specified sub table
     * @throws IndexOutOfBoundsException when any endpoint is outside the table.
     * @throws IllegalArgumentException  when a from endpoint comes after an to
     *                                   endpoint
     */
    public DataTable rows(int fromRow, int toRow) {
        return subTable(fromRow, 0, toRow, width());
    }

    /**
     * Returns a table that is a view on a portion of this table. The sub table
     * begins at {@code fromRow} inclusive and {@code fromColumn} inclusive and
     * extends to the last column and row.
     *
     * @param  fromRow                   the beginning row index, inclusive
     * @param  fromColumn                the beginning column index, inclusive
     * @return                           the specified sub table
     * @throws IndexOutOfBoundsException when any endpoint is outside the table.
     */
    public DataTable subTable(int fromRow, int fromColumn) {
        return subTable(fromRow, fromColumn, height(), width());
    }

    /**
     * Returns a table that is a view on a portion of this table. The sub table
     * begins at {@code fromRow} inclusive and {@code fromColumn} inclusive and
     * extends to {@code toRow} exclusive and {@code toColumn} exclusive.
     *
     * @param  fromRow                   the beginning row index, inclusive
     * @param  fromColumn                the beginning column index, inclusive
     * @param  toRow                     the end row index, exclusive
     * @param  toColumn                  the end column index, exclusive
     * @return                           the specified sub table
     * @throws IndexOutOfBoundsException when any endpoint is outside the table.
     * @throws IllegalArgumentException  when a from endpoint comes after an to
     *                                   endpoint
     */
    public DataTable subTable(int fromRow, int fromColumn, int toRow, int toColumn) {
        return new DataTable(new RawDataTableView(fromRow, fromColumn, toColumn, toRow), tableConverter);
    }

    /**
     * Returns the number of rows in the table.
     *
     * @return the number of rows in the table
     */
    public int height() {
        return raw.size();
    }

    /**
     * Returns the number of columns in the table.
     *
     * @return the number of columns in the table
     */
    public int width() {
        return raw.isEmpty() ? 0 : raw.get(0).size();
    }

    /**
     * Returns a string representation of the this table.
     */
    @Override
    public String toString() {
        return DataTableFormatter.builder()
                .build()
                .format(this);
    }

    /**
     * Prints a string representation of this table to the {@code appendable}.
     *
     * @deprecated             superseded by
     *                         {@link DataTableFormatter#formatTo(DataTable, Appendable)}
     * @param      appendable  to append the string representation of this table
     *                         to.
     * @throws     IOException If an I/O error occurs
     */
    @Deprecated
    public void print(Appendable appendable) throws IOException {
        DataTableFormatter.builder()
                .prefixRow("      ")
                .build()
                .formatTo(this, appendable);
    }

    /**
     * Prints a string representation of this table to the {@code appendable}.
     *
     * @deprecated            superseded by
     *                        {@link DataTableFormatter#formatTo(DataTable, StringBuilder)}
     * @param      appendable to append the string representation of this table
     *                        to.
     */
    @Deprecated
    public void print(StringBuilder appendable) {
        DataTableFormatter.builder()
                .prefixRow("      ")
                .build()
                .formatTo(this, appendable);
    }

    /**
     * Returns a transposed view on this table. Example:
     *
     * <pre>
     *    | a | 7 | 4 |
     *    | b | 9 | 2 |
     * </pre>
     * <p>
     * becomes:
     *
     * <pre>
     * | a | b |
     * | 7 | 9 |
     * | 4 | 2 |
     * </pre>
     *
     * @return a transposed view of the table
     */
    public DataTable transpose() {
        if (raw instanceof TransposedRawDataTableView) {
            TransposedRawDataTableView transposed = (TransposedRawDataTableView) this.raw;
            return transposed.dataTable();
        }
        return new DataTable(new TransposedRawDataTableView(), tableConverter);
    }

    /**
     * Converts a {@link DataTable} to another type.
     * <p>
     * There are three ways in which a table might be mapped to a certain type.
     * The table converter considers the possible conversions in this order:
     * <ol>
     * <li>Using the whole table to create a single instance.</li>
     * <li>Using individual rows to create a collection of instances. The first
     * row may be used as header.</li>
     * <li>Using individual cells to a create a collection of instances.</li>
     * </ol>
     */
    public interface TableConverter {

        /**
         * Converts a {@link DataTable} to another type.
         * <p>
         * Delegates to <code>toList</code>, <code>toLists</code>,
         * <code>toMap</code> and <code>toMaps</code> for
         * <code>List&lt;T&gt;</code>, <code>List&lt;List&lt;T&gt;&gt;</code>,
         * <code>Map&lt;K,V&gt;</code> and
         * <code>List&lt;Map&lt;K,V&gt;&gt;</code> respectively.
         *
         * @param  dataTable the table to convert
         * @param  type      the type to convert to
         * @param  <T>       the type to convert to
         * @return           an object of type
         */
        <T> T convert(DataTable dataTable, Type type);

        /**
         * Converts a {@link DataTable} to another type.
         * <p>
         * Delegates to <code>toList</code>, <code>toLists</code>,
         * <code>toMap</code> and <code>toMaps</code> for
         * <code>List&lt;T&gt;</code>, <code>List&lt;List&lt;T&gt;&gt;</code>,
         * <code>Map&lt;K,V&gt;</code> and
         * <code>List&lt;Map&lt;K,V&gt;&gt;</code> respectively.
         *
         * @param  dataTable  the table to convert
         * @param  type       the type to convert to
         * @param  <T>        the type to convert to
         * @param  transposed whether the table should be transposed first.
         * @return            an object of type
         */
        <T> T convert(DataTable dataTable, Type type, boolean transposed);

        /**
         * Converts a {@link DataTable} to a list.
         * <p>
         * A table converter may either map each row or each individual cell to
         * a list element.
         * <p>
         * For example:
         *
         * <pre>
         * | Annie M. G. Schmidt | 1911-03-20 |
         * | Roald Dahl          | 1916-09-13 |
         *
         * convert.toList(table, String.class);
         * </pre>
         *
         * can become
         *
         * <pre>
         *  [ "Annie M. G. Schmidt", "1911-03-20", "Roald Dahl", "1916-09-13" ]
         * </pre>
         * <p>
         * While:
         *
         * <pre>
         * convert.toList(table, Author.class);
         * </pre>
         * <p>
         * can become:
         *
         * <pre>
         * [
         *   Author[ name: Annie M. G. Schmidt, birthDate: 1911-03-20 ],
         *   Author[ name: Roald Dahl,          birthDate: 1916-09-13 ]
         * ]
         * </pre>
         * <p>
         * Likewise:
         *
         * <pre>
         *  | firstName   | lastName | birthDate  |
         *  | Annie M. G. | Schmidt  | 1911-03-20 |
         *  | Roald       | Dahl     | 1916-09-13 |
         *
         * convert.toList(table, Authors.class);
         * </pre>
         *
         * can become:
         *
         * <pre>
         *  [
         *   Author[ firstName: Annie M. G., lastName: Schmidt,  birthDate: 1911-03-20 ],
         *   Author[ firstName: Roald,       lastName: Dahl,     birthDate: 1916-09-13 ]
         *  ]
         * </pre>
         *
         * @param  dataTable the table to convert
         * @param  itemType  the list item type to convert to
         * @param  <T>       the type to convert to
         * @return           a list of objects of <code>itemType</code>
         */
        <T> List<T> toList(DataTable dataTable, Type itemType);

        /**
         * Converts a {@link DataTable} to a list of lists.
         * <p>
         * Each row maps to a list, each table cell a list entry.
         * <p>
         * For example:
         *
         * <pre>
         * | Annie M. G. Schmidt | 1911-03-20 |
         * | Roald Dahl          | 1916-09-13 |
         *
         * convert.toLists(table, String.class);
         * </pre>
         *
         * can become
         *
         * <pre>
         *  [
         *    [ "Annie M. G. Schmidt", "1911-03-20" ],
         *    [ "Roald Dahl",          "1916-09-13" ]
         *  ]
         * </pre>
         * <p>
         *
         * @param  dataTable the table to convert
         * @param  itemType  the list item type to convert to
         * @param  <T>       the type to convert to
         * @return           a list of lists of objects of <code>itemType</code>
         */
        <T> List<List<T>> toLists(DataTable dataTable, Type itemType);

        /**
         * Converts a {@link DataTable} to a map.
         * <p>
         * The left column of the table is used to instantiate the key values.
         * The other columns are used to instantiate the values.
         * <p>
         * For example:
         *
         * <pre>
         * | 4a1 | Annie M. G. Schmidt | 1911-03-20 |
         * | c92 | Roald Dahl          | 1916-09-13 |
         *
         * convert.toMap(table, Id.class, Authors.class);
         * </pre>
         *
         * can become:
         *
         * <pre>
         *  {
         *   Id[ 4a1 ]: Author[ name: Annie M. G. Schmidt, birthDate: 1911-03-20 ],
         *   Id[ c92 ]: Author[ name: Roald Dahl,          birthDate: 1916-09-13 ]
         *  }
         * </pre>
         * <p>
         * The header cells may be used to map values into the types. When doing
         * so the first header cell may be left blank.
         * <p>
         * For example:
         *
         * <pre>
         * |     | firstName   | lastName | birthDate  |
         * | 4a1 | Annie M. G. | Schmidt  | 1911-03-20 |
         * | c92 | Roald       | Dahl     | 1916-09-13 |
         *
         * convert.toMap(table, Id.class, Authors.class);
         * </pre>
         *
         * can becomes:
         *
         * <pre>
         *  {
         *   Id[ 4a1 ]: Author[ firstName: Annie M. G., lastName: Schmidt, birthDate: 1911-03-20 ],
         *   Id[ c92 ]: Author[ firstName: Roald,       lastName: Dahl,    birthDate: 1916-09-13 ]
         *  }
         * </pre>
         *
         * @param  dataTable the table to convert
         * @param  keyType   the key type to convert to
         * @param  valueType the value to convert to
         * @param  <K>       the key type to convert to
         * @param  <V>       the value type to convert to
         * @return           a map of <code>keyType</code>
         *                   <code>valueType</code>
         */

        <K, V> Map<K, V> toMap(DataTable dataTable, Type keyType, Type valueType);

        /**
         * Converts a {@link DataTable} to a list of maps.
         * <p>
         * Each map represents a row in the table. The map keys are the column
         * headers.
         * <p>
         * For example:
         *
         * <pre>
         * | firstName   | lastName | birthDate  |
         * | Annie M. G. | Schmidt  | 1911-03-20 |
         * | Roald       | Dahl     | 1916-09-13 |
         * </pre>
         *
         * can become:
         *
         * <pre>
         *  [
         *   {firstName: Annie M. G., lastName: Schmidt, birthDate: 1911-03-20 }
         *   {firstName: Roald,       lastName: Dahl,    birthDate: 1916-09-13 }
         *  ]
         * </pre>
         *
         * @param  dataTable the table to convert
         * @param  keyType   the key type to convert to
         * @param  valueType the value to convert to
         * @param  <K>       the key type to convert to
         * @param  <V>       the value type to convert to
         * @return           a list of maps of <code>keyType</code>
         *                   <code>valueType</code>
         */
        <K, V> List<Map<K, V>> toMaps(DataTable dataTable, Type keyType, Type valueType);

    }

    public static final class NoConverterDefined implements TableConverter {

        public NoConverterDefined() {

        }

        @Override
        public <T> T convert(DataTable dataTable, Type type) {
            return convert(dataTable, type, false);
        }

        @Override
        public <T> T convert(DataTable dataTable, Type type, boolean transposed) {
            throw new CucumberDataTableException(
                String.format("Can't convert DataTable to %s. DataTable was created without a converter", type));
        }

        @Override
        public <T> List<T> toList(DataTable dataTable, Type itemType) {
            throw new CucumberDataTableException(String.format(
                "Can't convert DataTable to List<%s>. DataTable was created without a converter", itemType));
        }

        @Override
        public <T> List<List<T>> toLists(DataTable dataTable, Type itemType) {
            throw new CucumberDataTableException(String.format(
                "Can't convert DataTable to List<List<%s>>. DataTable was created without a converter", itemType));
        }

        @Override
        public <K, V> Map<K, V> toMap(DataTable dataTable, Type keyType, Type valueType) {
            throw new CucumberDataTableException(
                String.format("Can't convert DataTable to Map<%s,%s>. DataTable was created without a converter",
                    keyType, valueType));
        }

        @Override
        public <K, V> List<Map<K, V>> toMaps(DataTable dataTable, Type keyType, Type valueType) {
            throw new CucumberDataTableException(
                String.format("Can't convert DataTable to List<Map<%s,%s>>. DataTable was created without a converter",
                    keyType, valueType));
        }

    }

    private final class RawDataTableView extends AbstractList<List<String>> implements RandomAccess {
        private final int fromRow;
        private final int fromColumn;
        private final int toColumn;
        private final int toRow;

        RawDataTableView(int fromRow, int fromColumn, int toColumn, int toRow) {
            if (fromRow < 0)
                throw new IndexOutOfBoundsException("fromRow: " + fromRow);
            if (fromColumn < 0)
                throw new IndexOutOfBoundsException("fromColumn: " + fromColumn);
            if (toRow > height())
                throw new IndexOutOfBoundsException("toRow: " + toRow + ", Height: " + height());
            if (toColumn > width())
                throw new IndexOutOfBoundsException("toColumn: " + toColumn + ", Width: " + width());
            if (fromRow > toRow)
                throw new IllegalArgumentException("fromRow(" + fromRow + ") > toRow(" + toRow + ")");
            if (fromColumn > toColumn)
                throw new IllegalArgumentException("fromColumn(" + fromColumn + ") > toColumn(" + toColumn + ")");

            this.fromRow = fromRow;
            this.fromColumn = fromColumn;
            this.toColumn = toColumn;
            this.toRow = toRow;
        }

        @Override
        public List<String> get(final int row) {
            rangeCheckRow(row, size());
            return new AbstractList<String>() {
                @Override
                public String get(final int column) {
                    rangeCheckColumn(column, size());
                    return raw.get(fromRow + row).get(fromColumn + column);
                }

                @Override
                public int size() {
                    return toColumn - fromColumn;
                }
            };
        }

        @Override
        public int size() {
            // If there are no columns this is an empty table. An empty table
            // has no rows.
            return fromColumn == toColumn ? 0 : toRow - fromRow;
        }
    }

    private final class ListView extends AbstractList<String> {
        int width = width();
        int height = height();

        @Override
        public String get(int index) {
            rangeCheck(index, size());
            return raw.get(index / width).get(index % width);
        }

        @Override
        public int size() {
            return height * width;
        }
    }

    private final class ColumnView extends AbstractList<String> implements RandomAccess {
        private final int column;

        ColumnView(int column) {
            rangeCheckColumn(column, width());
            this.column = column;
        }

        @Override
        public String get(final int row) {
            rangeCheckRow(row, size());
            return raw.get(row).get(column);
        }

        @Override
        public int size() {
            return height();
        }
    }

    private final class TransposedRawDataTableView extends AbstractList<List<String>> implements RandomAccess {

        DataTable dataTable() {
            return DataTable.this;
        }

        @Override
        public List<String> get(final int row) {
            rangeCheckRow(row, size());
            return new AbstractList<String>() {
                @Override
                public String get(final int column) {
                    rangeCheckColumn(column, size());
                    return raw.get(column).get(row);
                }

                @Override
                public int size() {
                    return height();
                }
            };
        }

        @Override
        public int size() {
            return width();
        }
    }
    /** Factory method: Create DataTable from GherkinMessagesDataTableArgument.
            */
    public static DataTable from(GherkinMessagesDataTableArgument gherkinTable) {
        return new DataTable(gherkinTable.cells(), new NoConverterDefined());
    }

    /**
     * Factory method: Create DataTable from DataTableArgument.
     */
    public static DataTable from(DataTableArgument dataTableArgument) {
        List<List<String>> raw = ((DataTable) dataTableArgument.getValue()).cells();
        return new DataTable(raw, new NoConverterDefined());
    }

    /**
     * Factory method: Create DataTable from PickleTable.
     */
    public static DataTable from(PickleTable pickleTable) {
        List<List<String>> raw = new ArrayList<>();
        pickleTable.getRows().forEach(row -> {
            List<String> rowValues = new ArrayList<>();
            row.getCells().forEach(cell -> rowValues.add(cell.getValue()));
            raw.add(rowValues);
        });
        return new DataTable(raw, new NoConverterDefined());
    }

    /**
     * Factory method: Create DataTable from a string representation of a table.
     */
    public static DataTable from(String tableSource) {
        List<List<String>> raw = parseTableSource(tableSource);
        return new DataTable(raw, new NoConverterDefined());
    }

    /**
     * Factory method: Create DataTable from Examples.
     */
    public static DataTable from(Examples examples) {
        List<List<String>> raw = new ArrayList<>();
        examples.getTableHeader().ifPresent(header -> raw.add(getRowValues(header)));
        examples.getTableBody().forEach(body -> raw.add(getRowValues(body)));
        return new DataTable(raw, new NoConverterDefined());
    }

    /**
     * Convert to GherkinMessagesDataTableArgument.
     */
    public GherkinMessagesDataTableArgument toGherkinMessagesDataTableArgument() {
        PickleTable pickleTable = toPickleTable();
        return new GherkinMessagesDataTableArgument(pickleTable, 1); // Mocked line number
    }

    /**
     * Convert to DataTableArgument.
     */
    public DataTableArgument toDataTableArgument() {
        return new DataTableArgument(argument -> this, this.cells());
    }

    /**
     * Convert to PickleTable.
     */
    public PickleTable toPickleTable() {
        List<PickleTableRow> pickleRows = new ArrayList<>();
        this.cells().forEach(row -> {
            List<PickleTableCell> cells = new ArrayList<>();
            row.forEach(value -> cells.add(new PickleTableCell(value))); // Use PickleTableCell for PickleTable
            pickleRows.add(new PickleTableRow(cells));
        });
        return new PickleTable(pickleRows);
    }

    /**
     * Convert to Examples.
     */
    public Examples toExamples(String keyword, String name, String description) {
        List<TableRow> tableRows = new ArrayList<>();
        if (!this.cells().isEmpty()) {
            tableRows.add(toTableRow(this.cells().get(0))); // Header
        }
        for (int i = 1; i < this.cells().size(); i++) {
            tableRows.add(toTableRow(this.cells().get(i))); // Body rows
        }
        return new Examples(
                new Location(1L, 1L),
                new ArrayList<>(),
                keyword,
                name,
                description,
                tableRows.get(0),
                tableRows.subList(1, tableRows.size()),
                UUID.randomUUID().toString()
        );
    }

    // SECTION: HELPER METHODS
    // =======================

    /**
     * Parse a table source string into a raw 2D list of strings.
     */
    private static List<List<String>> parseTableSource(String tableSource) {
        String[] rows = tableSource.split("\n");
        List<List<String>> table = new ArrayList<>();
        for (String row : rows) {
            String[] cells = row.split("\\|");
            List<String> rowList = new ArrayList<>();
            for (String cell : cells) {
                String trimmed = cell.trim();
                if (!trimmed.isEmpty()) {
                    rowList.add(trimmed);
                }
            }
            if (!rowList.isEmpty()) {
                table.add(rowList);
            }
        }
        return table;
    }

    /**
     * Helper: Convert a row into a TableRow.
     */
    private static TableRow toTableRow(List<String> rowValues) {
        List<TableCell> cells = new ArrayList<>();
        rowValues.forEach(value -> cells.add(new TableCell(new Location(1L, 1L), value))); // Provide Location instance
        return new TableRow(new Location(1L, 1L), cells, UUID.randomUUID().toString());
    }

    /**
     * Helper: Extract cell values from TableRow.
     */
    private static List<String> getRowValues(TableRow row) {
        List<String> values = new ArrayList<>();
        row.getCells().forEach(cell -> values.add(cell.getValue()));
        return values;
    }

    public <K, V> LinkedMultiMap<K, V> asLinkedMultiMap(Class<K> keyType, Class<V> valueType) {
        List<List<String>> rows = this.cells(); // Use existing cells() method
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            return new LinkedMultiMap<>(); // Return an empty LinkedMultiMap for empty data
        }

        List<K> keys = new ArrayList<>();
        List<V> values = new ArrayList<>();
        for (List<String> row : rows) {
            K key = convertValue(row.get(0), keyType); // Convert the first column to keys
            V value = row.size() > 1 ? convertValue(row.get(1), valueType) : null; // Convert the second column to values
            keys.add(key);
            values.add(value);
        }

        return new LinkedMultiMap<>(keys, values);
    }

    public List<LinkedMultiMap<String , Object>> asLinkedMultiMaps() {
        return asLinkedMultiMaps(String.class, Object.class);
    }

    public <K, V> List<LinkedMultiMap<K, V>> asLinkedMultiMaps(Class<K> keyType, Class<V> valueType) {
        List<List<String>> rows = this.cells(); // Use existing cells() method

        if (rows.size()<2) {
            return Collections.emptyList(); // Return an empty list for empty data
        }

        List<K> keys = (List<K>) rows.get(0);

        List<LinkedMultiMap<K, V>> linkedMultiMaps = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row =  rows.get(r);
            List<V> values = new ArrayList<>();
            for (int i = 0; i < row.size(); i++) {
                values.add(convertValue((String) row.get(i), valueType));
            }
            linkedMultiMaps.add(new LinkedMultiMap<>(keys, values));
        }

        return linkedMultiMaps;
    }

    @SuppressWarnings("unchecked")
    private <T> T convertValue(String value, Class<T> targetType) {
        if (targetType == String.class) {
            return (T) value;
        } else if (targetType == Integer.class) {
            return (T) Integer.valueOf(value);
        } else if (targetType == Double.class) {
            return (T) Double.valueOf(value);
        } else if (targetType == Boolean.class) {
            return (T) Boolean.valueOf(value);
        } else {
            throw new IllegalArgumentException("Unsupported conversion to type: " + targetType);
        }
    }

}