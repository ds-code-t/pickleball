// LinkedMultiMapTest.java
package io.pickleball.mapandStateutilities;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class LinkedMultiMapTest {
    private LinkedMultiMap<String, Integer> stringMap;
    private LinkedMultiMap<Integer, String> integerMap;


    public static void main(String[] args) {
        LinkedMultiMap<String, Integer> map = new LinkedMultiMap<>();
        map.put("A", 1);
        map.put("B", 2);
        map.put("A", 3);
        map.put("C", 4);
        map.put("B", 5);
        map.put("A", 6);

        NavigableIterator<String, Integer> nav = map.navigator();

// Move to middle of list
        nav.moveToIndex(2);

// Get all entries with key "A" after current position
        List<Map.Entry<String, Integer>> nextAEntries = nav.nextList("A");
        System.out.println("Next 'A' entries: " + nextAEntries);

// Get all previous entries
        List<Map.Entry<String, Integer>> prevEntries = nav.previousList();
        System.out.println("Previous entries: " + prevEntries);

// Navigate to second occurrence of "B" from current position
        if (nav.hasNext("B", 2)) {
            Map.Entry<String, Integer> entry = nav.next("B", 2);
            System.out.println("Second 'B' entry: " + entry.getKey() + "=" + entry.getValue());
        }

// Navigate to first occurrence of "A" looking backward
        if (nav.hasPrevious("A", 1)) {
            Map.Entry<String, Integer> entry = nav.previous("A", 1);
            System.out.println("First previous 'A' entry: " + entry.getKey() + "=" + entry.getValue());
        }
    }


    @BeforeMethod
    public void setUp() {
        stringMap = new LinkedMultiMap<>();
        integerMap = new LinkedMultiMap<>();

        // Setup test data
        stringMap.put("A", 1);
        stringMap.put("A", 2);
        stringMap.put("B", 3);
        stringMap.put("A", 4);

        integerMap.put(1, "X");
        integerMap.put(1, "Y");
        integerMap.put(2, "Z");
        integerMap.put(1, "W");
    }

    @Test
    public void testOriginalFunctionality() {
        // Test getValues
        List<Integer> aValues = stringMap.getValues("A");
        assertEquals(aValues, Arrays.asList(1, 2, 4), "Values for key 'A' should match");

        // Test getKeys
        List<Integer> keysForY = integerMap.getKeys("Y");
        assertEquals(keysForY, Arrays.asList(1), "Keys for value 'Y' should match");
    }

    @Test
    public void testIndexBasedAccess() {
        // Test getValueByKeyIndex
        assertEquals(stringMap.getValueByKeyIndex("A", 1), (Integer)2,
                "Second value for key 'A' should be 2");
        assertEquals(integerMap.getValueByKeyIndex(1, 1), "Y",
                "Second value for key 1 should be 'Y'");

        // Test safe versions
        assertNull(stringMap.getValueByKeyIndexSafe("A", 10),
                "Out of bounds index should return null");
        assertNull(integerMap.getValueByKeyIndexSafe(1, -5),
                "Negative index should return null");
    }

    @Test
    public void testFirstLastAccess() {
        // Test first/last value
        assertEquals(stringMap.getFirstValue("A"), (Integer)1,
                "First value for 'A' should be 1");
        assertEquals(stringMap.getLastValue("A"), (Integer)4,
                "Last value for 'A' should be 4");

        // Test first/last key
        assertEquals(integerMap.getFirstKey("Y"), (Integer)1,
                "First key for 'Y' should be 1");
        assertEquals(integerMap.getLastKey("Y"), (Integer)1,
                "Last key for 'Y' should be 1");

        // Test with defaults
        assertEquals(stringMap.getFirstValueOrDefault("NonExistent", 999), (Integer)999,
                "Default value should be returned for non-existent key");
        assertEquals(integerMap.getLastValueOrDefault(999, "default"), "default",
                "Default value should be returned for non-existent key");
    }

    @Test
    public void testStringBasedAccess() {
        // Test string-based value retrieval
        assertEquals(stringMap.getValueByString("A#1"), (Integer)1,
                "First occurrence should return 1");
        assertEquals(stringMap.getValueByString("A#2"), (Integer)2,
                "Second occurrence should return 2");
        assertEquals(stringMap.getValueByString("A#3"), (Integer)4,
                "Third occurrence should return 4");

        // Test with defaults
        assertEquals(stringMap.getValueByStringOrDefault("A#5", 999), (Integer)999,
                "Out of bounds index should return default value");
        assertNull(stringMap.getValueByString("NonExistent#1"),
                "Non-existent key should return null");
    }

    @Test
    public void testMapInterfaceIntegration() {
        LinkedMultiMap<String, Integer> map = new LinkedMultiMap<>();

        // Test Map.put
        Integer previous = map.put("test", 1);
        assertNull(previous, "First put should return null");
        previous = map.put("test", 2);
        assertEquals(previous, (Integer)1, "Subsequent put should return previous value");

        // Test Map.get behavior for String keys
        map = new LinkedMultiMap<>();
        map.put("key", 1);
        map.put("key", 2);
        assertEquals(map.get("key"), (Integer)2, "get() should return last value");

        // Test Map.getOrDefault
        assertEquals(map.getOrDefault("nonexistent", 999), (Integer)999,
                "getOrDefault should return default for missing key");
    }

    @Test
    public void testBackwardCompatibility() {
        LinkedMultiMap<String, Integer> map = new LinkedMultiMap<>();

        // Test original put behavior
        map.put("key", 1);
        map.put("key", 2);
        List<Integer> values = map.getValues("key");
        assertEquals(values, Arrays.asList(1, 2),
                "Multiple values should be maintained for same key");

        // Test that string indexing still works
        assertEquals(map.getValueByString("key#1"), (Integer)1,
                "String indexing should work for first value");
        assertEquals(map.getValueByString("key#2"), (Integer)2,
                "String indexing should work for second value");

        // Test original indexing behavior
        assertEquals(map.getValueByKeyIndex("key", 0), (Integer)1,
                "Index 0 should return first value");
        assertEquals(map.getValueByKeyIndex("key", 1), (Integer)2,
                "Index 1 should return second value");
    }

    @Test
    public void testListConstructor() {
        List<String> keys = Arrays.asList("A", "B", "A");
        List<Integer> values = Arrays.asList(1, 2, 3);
        LinkedMultiMap<String, Integer> map = new LinkedMultiMap<>(keys, values);

        // Verify original functionality
        assertEquals(map.getValues("A"), Arrays.asList(1, 3),
                "Values for key 'A' should be preserved in order");
        assertEquals(map.getValues("B"), Arrays.asList(2),
                "Values for key 'B' should be preserved");

        // Verify Map interface
        assertEquals(map.get("A"), (Integer)3,
                "get() should return last value for key");
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void testNullKeyHandling() {
        stringMap.getValues(null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testInvalidConstructorArgs() {
        new LinkedMultiMap<>(Arrays.asList("A"), Arrays.asList(1, 2));
    }

    @Test
    public void testEdgeCases() {
        LinkedMultiMap<String, Integer> map = new LinkedMultiMap<>();

        // Test empty map behavior
        assertNull(map.get("any"), "get on empty map should return null");
        assertTrue(map.getValues("any").isEmpty(), "getValues on empty map should return empty list");
        assertEquals(map.getOrDefault("any", 999), (Integer)999,
                "getOrDefault on empty map should return default");
    }

    @Test
    public void testMapConformance() {
        // Test that it behaves like a Map when used as one
        Map<String, Integer> map = new LinkedMultiMap<>();
        map.put("A", 1);
        map.put("A", 2);

        // Standard Map operations
        assertTrue(map.containsKey("A"), "containsKey should return true for existing key");
        assertTrue(map.containsValue(2), "containsValue should return true for existing value");
        assertEquals(map.size(), 2, "size should reflect number of entries");
        assertFalse(map.isEmpty(), "isEmpty should return false for non-empty map");

        // Test entrySet
        assertEquals(map.entrySet().size(), 2, "entrySet size should match map size");
        assertTrue(map.keySet().contains("A"), "keySet should contain all keys");
        assertTrue(map.values().contains(2), "values should contain all values");
    }

    @Test
    public void testStringKeyBehavior() {
        LinkedMultiMap<String, String> strMap = new LinkedMultiMap<>();
        strMap.put("test", "value1");
        strMap.put("test", "value2");

        // Verify string-based access when key type is String
        assertEquals(strMap.get("test"), "value2",
                "get() should use string-based access for String keys");
        assertEquals(strMap.getOrDefault("test#1", "default"), "value1",
                "getOrDefault should support string indexing for String keys");
    }
}