package tools.dscode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.assertions.ValueWrapper.ValueTypes;
import tools.dscode.common.dataelements.DataElementKind;
import tools.dscode.common.dataelements.DataQueryException;
import tools.dscode.common.dataelements.StructuredDataConverter;
import tools.dscode.common.dataoperations.TextOp;
import tools.dscode.common.dataoperations.TextPredicateMatcher;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.GlobalConstants;
import tools.dscode.common.mappings.FileAndDataParsing;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.treeparsing.DefinitionContext;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.mappings.ParsingMap.MappingDirectiveResolver;
import tools.dscode.common.mappings.custommappings.ValConverter;
import tools.dscode.common.servicecalls.JdbcServiceCall;
import tools.dscode.coredefinitions.DocStringDefinitions;
import tools.dscode.coredefinitions.MappingSteps;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
import static tools.dscode.Values.getRunValue;
import static tools.dscode.Values.getSingletonValue;
import static tools.dscode.Values.getValue;
import static tools.dscode.Values.registerPrefix;
import static tools.dscode.Values.saveDefaultValue;
import static tools.dscode.Values.saveOverrideValue;
import static tools.dscode.Values.saveRunValue;
import static tools.dscode.Values.saveSingletonValue;
import static tools.dscode.Values.saveValue;
import static tools.dscode.Values.secret;
import static tools.dscode.Values.throttle;
import static tools.dscode.internal.ValuesSupport.convert;
import static tools.dscode.internal.ValuesSupport.maskLog;
import static tools.dscode.internal.ValuesSupport.unmask;
import static tools.dscode.internal.ValuesSupport.xmlEscape;
import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

class Release216BehaviorTest {
    private static final Set<String> PUBLIC_STATIC_VALUES_METHODS = Set.of(
            "cacheGet(String)",
            "cachePut(String,Object)",
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
            "getSingletonValue(String)",
            "getSingletonValue(String,Class)",
            "getStepValue(String)",
            "getStepValue(String,Class)",
            "getValue(String)",
            "getValue(String,Class)",
            "registerPrefix(String,BiFunction)",
            "saveDefaultValue(String,Object)",
            "saveOverrideValue(String,Object)",
            "saveRootScenarioValue(String,Object)",
            "saveRunValue(String,Object)",
            "saveScenarioValue(String,Object)",
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

    @AfterEach
    void resetMaps() {
        MappingProcessor.resetCommonMaps();
    }

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
    void valueApiReadsAndWritesTheRunMapAndConvertsTypes() {
        MappingProcessor.resetCommonMaps();
        saveValue("total", "42");
        assertEquals("42", getValue("total"));
        assertEquals(42, getValue("total", Integer.class));
        assertEquals("42", getRunValue("total"));
        assertEquals(42, getRunValue("total", Integer.class));

        ArrayNode orders = MAPPER.createArrayNode();
        orders.addObject().put("sku", "A").put("qty", 1);
        orders.addObject().put("sku", "B").put("qty", 2);
        saveValue("orders", orders);
        Object second = getValue("orders #2");
        assertNotNull(second);
        Order order = getValue("orders #2", Order.class);
        assertEquals("B", order.sku);
        assertEquals(2, order.qty);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> getValue("total", Order.class)
        );
        assertTrue(failure.getMessage().contains(Order.class.getName()));
        assertThrows(IllegalArgumentException.class, () -> convert(null, Integer.class));
    }

    @Test
    void mapSpecificPairsStayOnTheirOwnMaps() {
        MappingProcessor.resetCommonMaps();
        saveRunValue("marker", "run");
        saveDefaultValue("marker", "default");
        saveOverrideValue("marker", "override");
        saveSingletonValue("marker", "singleton");
        assertEquals("run", getRunValue("marker"));
        assertEquals("default", getDefaultValue("marker"));
        assertEquals("override", getOverrideValue("marker"));
        assertEquals("singleton", getSingletonValue("marker"));
        assertEquals("default", getDefaultValue("marker", String.class));
        assertEquals("override", getOverrideValue("marker", String.class));
        assertEquals("singleton", getSingletonValue("marker", String.class));
        assertThrows(IllegalStateException.class, () -> Values.getStepValue("marker"));
        assertThrows(IllegalStateException.class, () -> Values.getScenarioValue("marker"));
        assertThrows(IllegalStateException.class, () -> Values.getRootScenarioValue("marker"));
    }

    @Test
    void parallelThreadsDoNotShareRunValues() throws Exception {
        MappingProcessor.resetCommonMaps();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Object> first = new AtomicReference<>();
        AtomicReference<Object> second = new AtomicReference<>();
        Thread one = new Thread(() -> {
            try {
                start.await();
                MappingProcessor.resetCommonMaps();
                saveRunValue("lane", "one");
                Thread.sleep(50);
                first.set(getRunValue("lane"));
            } catch (Exception exception) {
                first.set(exception);
            } finally {
                done.countDown();
            }
        });
        Thread two = new Thread(() -> {
            try {
                start.await();
                MappingProcessor.resetCommonMaps();
                saveRunValue("lane", "two");
                Thread.sleep(50);
                second.set(getRunValue("lane"));
            } catch (Exception exception) {
                second.set(exception);
            } finally {
                done.countDown();
            }
        });
        one.start();
        two.start();
        start.countDown();
        done.await();
        assertEquals("one", first.get());
        assertEquals("two", second.get());
    }

    @Test
    void softFailAndThrottleRejectBadCallsAndTheFirstThrottleDoesNotWait() {
        assertThrows(IllegalStateException.class, () -> Values.softFail("outside"));
        assertThrows(IllegalArgumentException.class, () -> throttle("  ", 1));
        assertThrows(IllegalArgumentException.class, () -> throttle("release-216-negative", -1));
        long started = System.nanoTime();
        throttle("release-216-first", 2);
        assertTrue(System.nanoTime() - started < 500_000_000L);
    }

    @Test
    void throttleWaitsForTheSameKeyAcrossThreads() throws Exception {
        String key = "release-216-wait";
        throttle(key, 1);
        long started = System.nanoTime();
        Thread waiter = new Thread(() -> throttle(key, 1));
        waiter.start();
        waiter.join();
        assertTrue(System.nanoTime() - started >= 900_000_000L);
    }

    @Test
    void prefixRegistrationCacheAndMasking() {
        assertThrows(IllegalArgumentException.class, () -> registerPrefix("file", (ctx, arg) -> PrefixValue.of(arg)));
        assertThrows(IllegalArgumentException.class, () -> registerPrefix("classpath", (ctx, arg) -> PrefixValue.of(arg)));
        assertThrows(IllegalArgumentException.class, () -> registerPrefix("wd-file", (ctx, arg) -> PrefixValue.of(arg)));
        assertThrows(IllegalArgumentException.class, () -> registerPrefix("data", (ctx, arg) -> PrefixValue.of(arg)));
        assertThrows(IllegalArgumentException.class, () -> registerPrefix("config", (ctx, arg) -> PrefixValue.of(arg)));
        assertThrows(IllegalArgumentException.class, () -> registerPrefix("value", (ctx, arg) -> PrefixValue.of(arg)));
        assertThrows(IllegalArgumentException.class, () -> registerPrefix("datatable", (ctx, arg) -> PrefixValue.of(arg)));
        assertThrows(IllegalArgumentException.class, () -> registerPrefix("", (ctx, arg) -> PrefixValue.of(arg)));
        String prefix = "account216";
        try {
            registerPrefix(prefix, (ctx, arg) -> secret("pkb-secret-216-alpha"));
        } catch (IllegalArgumentException already) {
            assertTrue(already.getMessage().contains("already registered"));
        }
        assertThrows(IllegalArgumentException.class, () -> registerPrefix(prefix, (ctx, arg) -> PrefixValue.of("other")));
        Object secretValue = getValue("account216:qa");
        assertEquals(PrefixValue.MASK, String.valueOf(secretValue));
        assertEquals("pkb-secret-216-alpha", String.valueOf(unmask(secretValue)));
        assertEquals(
                "Bearer pkb-secret-216-alpha",
                new ParsingMap().resolveWholeText("Bearer <account216:qa>")
        );
        assertEquals(
                "Bearer " + PrefixValue.MASK,
                maskLog(new ParsingMap().resolveWholeText("Bearer <account216:qa>"))
        );
        try {
            registerPrefix("plain216", (ctx, arg) -> PrefixValue.of("seen-" + arg));
        } catch (IllegalArgumentException already) {
            assertTrue(already.getMessage().contains("already registered"));
        }
        assertEquals("seen-marker", String.valueOf(getValue("plain216:marker")));
        assertEquals(
                "seen-marker",
                new ParsingMap().resolveWholeValue("~[~plain216:marker~]~").toString()
        );
        Object unknown = getValue("missing216:abc");
        assertTrue(String.valueOf(unknown).contains("missing216:abc"));

        cachePut("release-216-cache", "kept");
        cachePut("release-216-null", null);
        assertEquals("kept", cacheGet("release-216-cache"));
        assertNull(cacheGet("release-216-null"));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Object> seen = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                start.await();
                cachePut("release-216-parallel", "from-a");
            } catch (InterruptedException exception) {
                seen.set(exception);
            } finally {
                done.countDown();
            }
        });
        Thread reader = new Thread(() -> {
            try {
                start.await();
                Thread.sleep(30);
                seen.set(cacheGet("release-216-parallel"));
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
            done.await();
        } catch (InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
        assertEquals("from-a", seen.get());

        PrefixValue masked = secret("pkb-secret-216-alpha");
        assertEquals(PrefixValue.MASK, masked.toString());
        assertEquals(
                "token " + PrefixValue.MASK + " token",
                maskLog("token pkb-secret-216-alpha token")
        );
        assertFalse(maskLog("token pkb-secret-216-alpha token").contains("pkb-secret-216-alpha"));
    }

    @Test
    void xmlEscapeMaskAndUnmaskDirectives() {
        MappingProcessor.resetCommonMaps();
        assertEquals("Tom &amp; Jerry", xmlEscape("Tom & Jerry"));
        assertEquals("&lt;a&gt;", xmlEscape("<a>"));
        Object escaped = MappingDirectiveResolver.applyDirectives(
                "Tom & Jerry",
                List.of("XML-ESCAPE")
        );
        assertEquals("Tom &amp; Jerry", escaped);
        saveRunValue("name~MASK;", "pkb-secret-216-beta");
        Object stored = getRunValue("name");
        assertTrue(stored instanceof PrefixValue);
        assertEquals(PrefixValue.MASK, stored.toString());
        assertEquals("pkb-secret-216-beta", getRunValue("name~UNMASK;"));
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
        saveValue("name", "Tom & Jerry");
        saveValue("payload", "{\"title\":\"Tom & Jerry\"}");
        assertEquals("Tom &amp; Jerry", String.valueOf(getValue("name~XML-ESCAPE;")));
        assertEquals(
                "Tom &amp; Jerry",
                String.valueOf(getValue("payload~JSON;::title~XML-ESCAPE;"))
        );
        assertEquals(
                "Tom &amp; Jerry",
                String.valueOf(new ParsingMap().resolveWholeValue("~[~name~XML-ESCAPE;~]~"))
        );
        assertEquals("Tom & Jerry", String.valueOf(getValue("name")));
        MappingSteps.mapValues(
                null,
                null,
                "RUN",
                DataTable.create(List.of(List.of("body.title~XML-ESCAPE;", "Tom & Jerry")))
        );
        assertEquals("Tom &amp; Jerry", String.valueOf(getRunValue("body.title")));
        assertEquals(
                "seen " + PrefixValue.MASK,
                maskLog("seen pkb-secret-216-beta")
        );
        assertFalse(maskLog("seen pkb-secret-216-beta").contains("pkb-secret-216-beta"));
    }

    @Test
    void textMatchingIsAFullMatchAndHonorsQuoteTypes() {
        assertTrue(matches("Save", "Save", ValueTypes.DOUBLE_QUOTED));
        assertFalse(matches("Save All", "Save", ValueTypes.DOUBLE_QUOTED));
        assertFalse(matches("Autosave", "Save", ValueTypes.DOUBLE_QUOTED));
        assertTrue(matches("Save", "Save.*", ValueTypes.DOUBLE_QUOTED));
        assertTrue(matches("Save All", "Save.*", ValueTypes.DOUBLE_QUOTED));
        assertFalse(matches("Autosave", "Save.*", ValueTypes.DOUBLE_QUOTED));
        assertTrue(matches("Save All", "save all", ValueTypes.SINGLE_QUOTED));
        assertFalse(matches("Save All", "save all", ValueTypes.DOUBLE_QUOTED));
        assertTrue(matches("Save All", "Save All", ValueTypes.BACK_TICKED));
        assertFalse(matches("save all", "Save All", ValueTypes.BACK_TICKED));
        assertTrue(TextPredicateMatcher.matches(
                "ABC-123",
                TextOp.of("[A-Z]+-\\d+", ExecutionDictionary.Op.MATCHES)
        ));
        assertFalse(TextPredicateMatcher.matches(
                "ABC-123 extra",
                TextOp.of("[A-Z]+-\\d+", ExecutionDictionary.Op.MATCHES)
        ));
    }

    @Test
    void parsedButtonMatchingKeepsTheFullPattern() {
        String input = " click the 2nd Button matching \"Save.*\".";
        QuoteParser quotes = new QuoteParser(input);
        String masked = quotes.restoreFromWithOuterBookend(quotes.masked(), GlobalConstants.BOOK_END);
        MatchNode phrase = DefinitionContext.getNodeDictionary().parse(masked).getChild("phrase");
        assertNotNull(phrase);
        assertEquals(ExecutionDictionary.Op.MATCHES, ExecutionDictionary.Op.getOpFromString("matching"));
        assertEquals(ExecutionDictionary.Op.CONTAINS, ExecutionDictionary.Op.getOpFromString("containing"));
        MatchNode elementNode = phrase.getOrderedChildren("elementMatch").getFirst();
        ElementMatch element = new ElementMatch(null, elementNode);
        assertEquals("2", element.elementPosition);
        assertEquals(1, element.textOps.size());
        assertEquals(ExecutionDictionary.Op.MATCHES, element.textOps.getFirst().op());
        assertEquals("Save.*", element.textOps.getFirst().text().getValue());
        assertTrue(TextPredicateMatcher.matches(
                "Save All",
                new TextOp(element.textOps.getFirst().text(), element.textOps.getFirst().op())
        ));
        assertFalse(matches("Autosave", "Save.*", ValueTypes.DOUBLE_QUOTED));
        assertFalse(element.xPathy.getXpath().contains("Save"));
    }

    @Test
    void unsafeXmlIsRejectedAndNormalXmlStillParses() throws Exception {
        String unsafe = "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><foo>&xxe;</foo>";
        assertThrows(DataQueryException.class, () -> new DocStringDefinitions().xmlNode(unsafe));
        assertThrows(DataQueryException.class, () -> StructuredDataConverter.convert(unsafe, DataElementKind.XML_DATA));
        Path file = Files.createTempFile("pickleball-xml-", ".xml");
        Files.writeString(file, unsafe);
        assertThrows(
                DataQueryException.class,
                () -> FileAndDataParsing.buildJsonFromPath("wd-file:" + file.toAbsolutePath())
        );
        assertNotNull(new DocStringDefinitions().xmlNode("<order id=\"A7\">ok</order>"));
        Object directive = StructuredDataConverter.convert(ORDER_XML, DataElementKind.XML_DATA);
        String xmlDataJson = MAPPER.writeValueAsString(directive);
        assertEquals(
                "{\"id\":\"A7\",\"status\":\"open\",\"customer\":{\"type\":\"vip\",\"name\":\"Dan\"},\"item\":[{\"sku\":\"X1\",\"qty\":\"2\",\"\":\"Pen\"},{\"sku\":\"Y9\",\"qty\":\"1\",\"\":\"Pad\"}]}",
                xmlDataJson
        );
        JsonNode fileJson = FileAndDataParsing.parseSingleFile(ORDER_XML, "order.xml");
        assertEquals(xmlDataJson, MAPPER.writeValueAsString(fileJson));
        MappingProcessor.resetCommonMaps();
        saveValue("order", directive);
        assertEquals("vip", String.valueOf(getValue("order.customer.type")));
        assertEquals("Y9", String.valueOf(getValue("order.item#2.sku")));
        assertEquals("Y9", String.valueOf(getValue("order.item #2.sku")));
        assertEquals("[\"X1\",\"Y9\"]", String.valueOf(getValue("order.item.sku")));
    }

    @Test
    void missingAbsoluteClasspathPathNamesWdFileAndRelativeMissesStayNull() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> FileAndDataParsing.buildJsonFromPath("file:/opt/pickleball-missing-216/configs")
        );
        assertTrue(missing.getMessage().contains("use wd-file:/... for a filesystem path"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ParsingMap.loadConfigs("/opt/pickleball-missing-216/configs", Path.of("").toAbsolutePath())
        );
        assertNull(FileAndDataParsing.buildJsonFromPath("file:pickleball-missing-relative-216"));
        assertNull(ParsingMap.loadConfigs("wd-file:/opt/pickleball-missing-216/configs", Path.of("").toAbsolutePath()));
    }

    @Test
    void jdbcSelectUpdateParamsNullsNumbersErrorsAndMissingSql() {
        String db = "jdbc:h2:mem:release216" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        ObjectNode create = request(db, "create table items (sku varchar(20), qty int, note varchar(40))", null);
        ObjectNode created = JdbcServiceCall.execute(create);
        assertFalse(created.has("error"), created.toString());

        ObjectNode insert = request(db, "insert into items (sku, qty, note) values (?, ?, ?)", null);
        insert.set("params", MAPPER.createArrayNode().add("X1").add(2).addNull());
        ObjectNode inserted = JdbcServiceCall.execute(insert);
        assertEquals(1, inserted.path("updateCount").asInt());
        assertFalse(inserted.has("error"));

        ObjectNode select = request(db, "select sku, qty, note from items", null);
        ObjectNode selected = JdbcServiceCall.execute(select);
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

        ObjectNode missingSql = request(db, "", null);
        assertThrows(IllegalArgumentException.class, () -> JdbcServiceCall.execute(missingSql));

        String secure = "jdbc:h2:mem:release216pw" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        ObjectNode masked = request(secure, "select 1 as n", "pkb-secret-216-db");
        masked.put("user", "sa");
        rememberAndExecute(masked);
        assertEquals(
                "password " + PrefixValue.MASK,
                maskLog("password pkb-secret-216-db")
        );
        assertFalse(maskLog("password pkb-secret-216-db").contains("pkb-secret-216-db"));
    }

    private static void rememberAndExecute(ObjectNode request) {
        tools.dscode.internal.ValuesSupport.rememberSecret("pkb-secret-216-db");
        ObjectNode response = JdbcServiceCall.execute(request);
        assertFalse(response.has("error"), response.toString());
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

    private static boolean matches(String value, String pattern, ValueTypes quote) {
        return TextPredicateMatcher.matches(
                createValueWrapper(value),
                new TextOp(createValueWrapper(pattern, quote), ExecutionDictionary.Op.MATCHES)
        );
    }

    public static class Order {
        public String sku;
        public int qty;
    }
}
