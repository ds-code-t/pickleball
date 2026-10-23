package com.example.pickleball;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import org.junit.jupiter.api.Test;
import tools.dscode.PrefixValue;
import tools.dscode.Values;
import tools.dscode.common.dataelements.DataElementKind;
import tools.dscode.common.dataelements.DataQueryException;
import tools.dscode.common.dataelements.StructuredDataConverter;
import tools.dscode.common.mappings.FileAndDataParsing;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.mappings.custommappings.ValConverter;
import tools.dscode.common.servicecalls.JdbcServiceCall;
import tools.dscode.coredefinitions.MappingSteps;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.Values.cacheGet;
import static tools.dscode.Values.cachePut;
import static tools.dscode.Values.getDefaultValue;
import static tools.dscode.Values.getOverrideValue;
import static tools.dscode.Values.getRootScenarioValue;
import static tools.dscode.Values.getRunValue;
import static tools.dscode.Values.getScenarioValue;
import static tools.dscode.Values.getSingletonValue;
import static tools.dscode.Values.getStepValue;
import static tools.dscode.Values.getValue;
import static tools.dscode.Values.registerPrefix;
import static tools.dscode.Values.saveDefaultValue;
import static tools.dscode.Values.saveOverrideValue;
import static tools.dscode.Values.saveRootScenarioValue;
import static tools.dscode.Values.saveRunValue;
import static tools.dscode.Values.saveScenarioValue;
import static tools.dscode.Values.saveSingletonValue;
import static tools.dscode.Values.saveStepValue;
import static tools.dscode.Values.saveValue;
import static tools.dscode.Values.secret;
import static tools.dscode.Values.throttle;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.mappings.ValueFormatting.toSafeJsonNode;

/**
 * Consumer checks for 2.1.16. They run inside a scenario, so they do not reset maps.
 */
public final class Release216Checks {
    private static final Set<String> PUBLIC_STATIC_VALUES_METHODS = Set.of(
            "cacheGet(String)",
            "cachePut(String,Object)",
            "clearScenarioObjects()",
            "getDefaultValue(String)",
            "getDefaultValue(String,Class)",
            "getOverrideValue(String)",
            "getOverrideValue(String,Class)",
            "getRootScenarioValue(String)",
            "getRootScenarioValue(String,Class)",
            "getRunValue(String)",
            "getRunValue(String,Class)",
            "getScenarioValue(String)",
            "getScenarioValue(String,Class)",
            "getScenarioObject(String)",
            "getSingletonValue(String)",
            "getSingletonValue(String,Class)",
            "getStepValue(String)",
            "getStepValue(String,Class)",
            "getValue(String)",
            "getValue(String,Class)",
            "isBlank(Object)",
            "registerPrefix(String,BiFunction)",
            "saveDefaultValue(String,Object)",
            "saveOverrideValue(String,Object)",
            "saveRootScenarioValue(String,Object)",
            "saveRunValue(String,Object)",
            "saveScenarioValue(String,Object)",
            "saveScenarioObject(String,Object)",
            "saveSingletonValue(String,Object)",
            "saveStepValue(String,Object)",
            "saveValue(String,Object)",
            "secret(Object)",
            "softFail(String)",
            "throttle(String,int)"
    );

    private static final String ORDER_XML = """
            <order id="A7" status="open"><customer type="vip"><name>Dan</name></customer><item sku="X1" qty="2">Pen</item><item sku="Y9" qty="1">Pad</item></order>
            """.trim();
    private static final String ORDER_JSON = "{\"id\":\"A7\",\"status\":\"open\",\"customer\":{\"type\":\"vip\",\"name\":\"Dan\"},\"item\":[{\"sku\":\"X1\",\"qty\":\"2\",\"\":\"Pen\"},{\"sku\":\"Y9\",\"qty\":\"1\",\"\":\"Pad\"}]}";

    @Test
    void valuesPublicStaticMembersAreTheConsumerApi() {
        Set<String> actual = new TreeSet<>();
        for (Method method : Values.class.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers)) {
                continue;
            }
            assertTrue(Modifier.isStatic(modifiers), method.toString());
            assertFalse(method.isSynthetic(), method.toString());
            assertFalse(method.isBridge(), method.toString());
            actual.add(method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                    .map(Class::getSimpleName)
                    .collect(Collectors.joining(",")) + ")");
        }
        assertEquals(PUBLIC_STATIC_VALUES_METHODS, actual);

        for (Field field : Values.class.getDeclaredFields()) {
            assertFalse(Modifier.isPublic(field.getModifiers()), field.getName());
        }
        for (java.lang.reflect.Constructor<?> constructor : Values.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()), constructor.toString());
        }
        Set<String> nested = new TreeSet<>();
        for (Class<?> type : Values.class.getDeclaredClasses()) {
            if (Modifier.isPublic(type.getModifiers())) {
                assertTrue(Modifier.isStatic(type.getModifiers()), type.getName());
                nested.add(type.getSimpleName());
            }
        }
        assertEquals(Set.of("Context"), nested);
    }

    @Test
    void valueApiRoundTripTypesAndMapPairs() {
        assertEquals("v1", getValue("x"));
        assertEquals("v1", getValue("x", String.class));
        assertEquals(42, getValue("total", Integer.class));
        assertEquals("42", getRunValue("total"));
        assertEquals(42, getRunValue("total", Integer.class));

        com.fasterxml.jackson.databind.node.ArrayNode rows = MAPPER.createArrayNode();
        rows.addObject().put("sku", "A").put("qty", 1);
        rows.addObject().put("sku", "B").put("qty", 2);
        saveValue("orders", rows);
        Order order = getValue("orders #2", Order.class);
        assertEquals("B", order.sku);
        assertEquals(2, order.qty);
        IllegalArgumentException conversion = assertThrows(
                IllegalArgumentException.class,
                () -> getValue("total", Order.class)
        );
        assertTrue(conversion.getMessage().contains("Cannot convert"));

        saveValue("javaName", "from-java");
        saveScenarioValue("scenarioMark", "scenario");
        saveStepValue("stepMark", "step");
        saveRootScenarioValue("rootMark", "root");
        saveDefaultValue("defaultMark", "default");
        saveOverrideValue("overrideMark", "override");
        saveSingletonValue("singletonMark", "singleton");
        assertEquals("scenario", getScenarioValue("scenarioMark"));
        assertEquals("scenario", getScenarioValue("scenarioMark", String.class));
        assertEquals("step", getStepValue("stepMark"));
        assertEquals("step", getStepValue("stepMark", String.class));
        assertEquals("root", getRootScenarioValue("rootMark"));
        assertEquals("root", getRootScenarioValue("rootMark", String.class));
        assertEquals("default", getDefaultValue("defaultMark"));
        assertEquals("default", getDefaultValue("defaultMark", String.class));
        assertEquals("override", getOverrideValue("overrideMark"));
        assertEquals("override", getOverrideValue("overrideMark", String.class));
        assertEquals("singleton", getSingletonValue("singletonMark"));
        assertEquals("singleton", getSingletonValue("singletonMark", String.class));
        assertEquals("from-java", getValue("javaName", String.class));
    }

    @Test
    void prefixContextCacheAndSecretReuse() {
        registerPrefix("ctx216", (ctx, arg) -> {
            ctx.saveRunValue("ctxWrote", arg);
            return PrefixValue.of(ctx.getValue("x"));
        });
        assertEquals("v1", String.valueOf(getValue("ctx216:marker")));
        assertEquals("marker", String.valueOf(getRunValue("ctxWrote")));
        assertTrue(String.valueOf(getValue("missing216:abc")).contains("missing216:abc"));
        assertThrows(IllegalArgumentException.class, () -> registerPrefix("file", (ctx, arg) -> PrefixValue.of(arg)));
        assertThrows(IllegalArgumentException.class, () -> registerPrefix("ctx216", (ctx, arg) -> PrefixValue.of(arg)));

        cachePut("release-216-consumer-cache", "kept");
        cachePut("release-216-consumer-null", null);
        assertEquals("kept", cacheGet("release-216-consumer-cache"));
        assertNull(cacheGet("release-216-consumer-null"));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Object> seen = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                start.await();
                cachePut("release-216-consumer-parallel", "from-a");
            } catch (InterruptedException exception) {
                seen.set(exception);
            } finally {
                done.countDown();
            }
        });
        Thread reader = new Thread(() -> {
            try {
                start.await();
                Thread.sleep(40);
                seen.set(cacheGet("release-216-consumer-parallel"));
            } catch (Exception exception) {
                seen.set(exception);
            } finally {
                done.countDown();
            }
        });
        writer.start();
        reader.start();
        start.countDown();
        try {
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
        assertEquals("from-a", seen.get());

        PrefixValue hidden = secret("pkb-secret-216-reused");
        saveValue("reusedSecret", hidden);
        assertEquals(PrefixValue.MASK, String.valueOf(getValue("reusedSecret")));
        assertEquals("pkb-secret-216-reused", getValue("reusedSecret", String.class));
    }

    @Test
    void xmlEscapePlacementsAndCapturedXmlJson() throws Exception {
        assertEquals("Tom &amp; Jerry", String.valueOf(getValue("name~XML-ESCAPE;")));
        assertEquals(
                "Tom &amp; Jerry",
                String.valueOf(getValue("payload~JSON;::title~XML-ESCAPE;"))
        );
        assertEquals(
                "Tom &amp; Jerry",
                String.valueOf(ParsingMap.getRunningParsingMap().resolveWholeValue("~[~name~XML-ESCAPE;~]~"))
        );
        assertEquals("Tom & Jerry", String.valueOf(getValue("name")));
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) ValConverter.convertSpecialValues(
                Map.of("body.title~XML-ESCAPE;", "Tom & Jerry")
        );
        assertEquals("Tom &amp; Jerry", structured.get("body.title"));
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> MappingSteps.mapDocString(
                        "body~XML-ESCAPE;",
                        "TEXT",
                        "RUN",
                        DocString.create("Tom & Jerry", "text")
                )
        );
        assertTrue(rejected.getMessage().contains("destination key"));
        MappingSteps.mapValues(
                null,
                null,
                "RUN",
                DataTable.create(List.of(List.of("checked.title~XML-ESCAPE;", "Tom & Jerry")))
        );
        assertEquals("Tom &amp; Jerry", String.valueOf(getRunValue("checked.title")));

        Object converted = StructuredDataConverter.convert(ORDER_XML, DataElementKind.XML_DATA);
        assertEquals(ORDER_JSON, MAPPER.writeValueAsString(converted));
        String unsafe = "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><foo>&xxe;</foo>";
        DataQueryException unsafeXml = assertThrows(
                DataQueryException.class,
                () -> StructuredDataConverter.convert(unsafe, DataElementKind.XML_DATA)
        );
        assertTrue(unsafeXml.getMessage().contains("DOCTYPE"));
    }

    @Test
    void pathHintNamesWdFile() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> FileAndDataParsing.buildJsonFromPath("file:/opt/pickleball-missing-216/configs")
        );
        assertTrue(missing.getMessage().contains("use wd-file:/... for a filesystem path"));
        assertNull(FileAndDataParsing.buildJsonFromPath("file:pickleball-missing-relative-216"));
    }

    @Test
    void throttleFirstCallDoesNotWaitAndTheNextCallDoes() throws Exception {
        String key = "release-216-consumer-throttle";
        long started = System.nanoTime();
        throttle(key, 1);
        assertTrue(System.nanoTime() - started < 500_000_000L);
        AtomicBoolean waited = new AtomicBoolean();
        Thread other = new Thread(() -> {
            long began = System.nanoTime();
            throttle(key, 1);
            waited.set(System.nanoTime() - began >= 900_000_000L);
        });
        other.start();
        other.join(5000);
        assertFalse(other.isAlive());
        assertTrue(waited.get());
    }

    @Test
    void jdbcSelectUpdateParamsNullsNumbersErrorsAndMaskedPassword() {
        String db = "jdbc:h2:mem:release216chk" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        ObjectNode created = JdbcServiceCall.execute(request(db, "create table items (sku varchar(20), qty int, note varchar(40))", null));
        assertFalse(created.has("error"), created.path("error").asText(""));
        ObjectNode insert = request(db, "insert into items (sku, qty, note) values (?, ?, ?)", null);
        insert.set("params", MAPPER.createArrayNode().add("X1").add(2).addNull());
        ObjectNode inserted = JdbcServiceCall.execute(insert);
        assertEquals(1, inserted.path("updateCount").asInt());
        ObjectNode selected = JdbcServiceCall.execute(request(db, "select sku, qty, note from items", null));
        assertEquals(1, selected.path("rowCount").asInt());
        JsonNode row = selected.path("rows").get(0);
        assertEquals("X1", row.path("SKU").asText(row.path("sku").asText()));
        JsonNode qty = row.has("QTY") ? row.get("QTY") : row.get("qty");
        assertNotNull(qty);
        assertTrue(qty.isNumber());
        assertEquals(2, qty.asInt());
        JsonNode note = row.has("NOTE") ? row.get("NOTE") : row.get("note");
        assertNotNull(note);
        assertTrue(note.isNull());
        ObjectNode bad = JdbcServiceCall.execute(request(db, "select * from missing_table", null));
        assertTrue(bad.hasNonNull("error"));
        assertEquals(0, bad.path("rowCount").asInt());
        assertThrows(
                IllegalArgumentException.class,
                () -> JdbcServiceCall.execute(request(db, " ", null))
        );

        String secure = "jdbc:h2:mem:release216sec" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        ObjectNode masked = MAPPER.createObjectNode();
        masked.put("endpoint", secure);
        masked.put("user", "sa");
        masked.set("password", toSafeJsonNode(secret(Release216Steps.DB_PASSWORD)));
        masked.put("sql", "select 1 as n");
        ObjectNode response = JdbcServiceCall.execute(masked);
        assertFalse(response.has("error"), response.path("error").asText(""));
        assertEquals(1, response.path("rowCount").asInt());
    }

    private static ObjectNode request(String endpoint, String sql, String password) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("endpoint", endpoint);
        request.put("sql", sql);
        if (password != null) {
            request.put("password", password);
        }
        return request;
    }

    public static class Order {
        public String sku;
        public int qty;
    }
}
