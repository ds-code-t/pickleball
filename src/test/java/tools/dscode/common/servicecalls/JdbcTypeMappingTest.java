package tools.dscode.common.servicecalls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

/**
 * Pins the JDBC mapping documented in experimental-features.md.
 * A SQL error is a response field. It does not throw.
 */
class JdbcTypeMappingTest {
    @Test
    void sqlErrorStaysOnTheResponseAndJdbcTypesMapAsDocumented() {
        String db = "jdbc:h2:mem:types" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        ObjectNode created = JdbcServiceCall.execute(request(db, """
                create table sample (
                  n int,
                  big bigint,
                  flag boolean,
                  money decimal(10, 2),
                  d_col date,
                  ts_col timestamp,
                  note varchar(80),
                  raw_col binary(2),
                  clob_col clob
                )
                """));
        assertFalse(created.hasNonNull("error"), created.toString());

        ObjectNode inserted = JdbcServiceCall.execute(request(db, """
                insert into sample (n, big, flag, money, d_col, ts_col, note, raw_col, clob_col)
                values (7, 9, true, 1.50, date '2020-01-02', timestamp '2020-01-02 03:04:05',
                        'C:\\Users\\Dan She''ll\\page', X'00FF', 'C:/Program Files/Pickleball/body.xml')
                """));
        assertFalse(inserted.hasNonNull("error"), inserted.toString());
        assertEquals(1, inserted.path("updateCount").asInt());

        ObjectNode selected = JdbcServiceCall.execute(request(db, """
                select n, big, flag, money, d_col, ts_col, note, raw_col, clob_col,
                       cast(null as varchar(10)) as nothing
                from sample
                """));
        assertFalse(selected.hasNonNull("error"), selected.toString());
        assertEquals(1, selected.path("rowCount").asInt());
        JsonNode row = selected.path("rows").get(0);

        JsonNode number = field(row, "n");
        assertTrue(number.isIntegralNumber(), number.toString());
        assertEquals(7, number.asInt());
        JsonNode big = field(row, "big");
        assertTrue(big.isIntegralNumber(), big.toString());
        assertEquals(9, big.asLong());
        JsonNode flag = field(row, "flag");
        assertTrue(flag.isBoolean(), flag.toString());
        assertTrue(flag.booleanValue());
        JsonNode money = field(row, "money");
        assertTrue(money.isNumber(), money.toString());
        assertEquals(1.5d, money.asDouble(), 0.0001d);
        JsonNode day = field(row, "d_col");
        assertTrue(day.isTextual(), day.toString());
        assertEquals("2020-01-02", day.asText());
        JsonNode timestamp = field(row, "ts_col");
        assertTrue(timestamp.isTextual(), timestamp.toString());
        assertEquals("2020-01-02 03:04:05.0", timestamp.asText());
        JsonNode note = field(row, "note");
        assertTrue(note.isTextual(), note.toString());
        assertEquals("C:\\Users\\Dan She'll\\page", note.asText());
        JsonNode raw = field(row, "raw_col");
        assertTrue(raw.isTextual(), raw.toString());
        assertTrue(raw.asText().startsWith("[B@"), raw.asText());
        JsonNode clob = field(row, "clob_col");
        assertTrue(clob.isTextual(), clob.toString());
        assertTrue(clob.asText().startsWith("clob"), clob.asText());
        assertTrue(clob.asText().contains("C:/Program Files/Pickleball/body.xml"), clob.asText());
        assertTrue(field(row, "nothing").isNull());
        assertTrue(field(row, "nothing").isNull());

        ObjectNode bad = JdbcServiceCall.execute(request(db, "select * from missing_table"));
        assertTrue(bad.hasNonNull("error"), bad.toString());
        assertTrue(bad.path("error").asText().toLowerCase().contains("missing_table"), bad.toString());
        assertEquals(0, bad.path("rowCount").asInt());
        assertEquals(0, bad.path("updateCount").asInt());
        assertTrue(bad.path("rows").isArray());
        assertEquals(0, bad.path("rows").size());

        assertThrows(
                IllegalArgumentException.class,
                () -> JdbcServiceCall.execute(request(db, " "))
        );
    }

    private static JsonNode field(JsonNode row, String name) {
        if (row.has(name)) return row.get(name);
        String upper = name.toUpperCase();
        if (row.has(upper)) return row.get(upper);
        return row.path(name);
    }

    private static ObjectNode request(String endpoint, String sql) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("endpoint", endpoint);
        request.put("sql", sql);
        return request;
    }
}
