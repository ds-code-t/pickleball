//package io.pickleball.mapandStateutilities;
//
//import org.testng.annotations.BeforeMethod;
//import org.testng.annotations.Test;
//import static org.testng.Assert.*;
//
//import java.util.List;
//import java.util.Map;
//import java.util.NoSuchElementException;
//
//public class NavigableIteratorTest {
//    private NavigableIterator<String, Integer> iterator;
//
//    @BeforeMethod
//    public void setUp() {
//        LinkedMultiMap<String, Integer> map = new LinkedMultiMap<>();
//        // Create a test dataset with repeated keys and values
//        map.put("A", 1);  // index 0
//        map.put("B", 2);  // index 1
//        map.put("A", 3);  // index 2
//        map.put("C", 4);  // index 3
//        map.put("B", 5);  // index 4
//        map.put("A", 6);  // index 5
//        iterator = map.navigator();
//    }
//
//    @Test
//    public void testBasicNavigation() {
//        assertTrue(iterator.hasNext());
//        assertFalse(iterator.hasPrevious());
//        Map.Entry<String, Integer> entry = iterator.next();
//        assertEquals(entry.getKey(), "A");
//        assertEquals(entry.getValue(), Integer.valueOf(1));
//        assertTrue(iterator.hasPrevious());
//    }
//
//    @Test
//    public void testNavigationByKey() {
//        assertTrue(iterator.hasNext("A"));
//        assertTrue(iterator.hasNext("A", 2));
//        assertFalse(iterator.hasNext("A", 4));
//        Map.Entry<String, Integer> entry = iterator.next("A");
//        assertEquals(entry.getValue(), Integer.valueOf(1));
//        entry = iterator.next("A");
//        assertEquals(entry.getValue(), Integer.valueOf(3));
//    }
//
//    @Test
//    public void testNavigationByOccurrence() {
//        assertTrue(iterator.hasNext(3));
//        assertFalse(iterator.hasNext(7));
//
//        Map.Entry<String, Integer> entry = iterator.next(2);
//        assertEquals(entry.getKey(), "B");
//        assertEquals(entry.getValue(), Integer.valueOf(2));
//    }
//
//    @Test
//    public void testReverseNavigation() {
//        iterator.toEnd();  // Move to last element (index 5)
//
//        assertTrue(iterator.hasPrevious());
//        assertTrue(iterator.hasPrevious("A"));
//        assertTrue(iterator.hasPrevious("A", 2));
//
//        Map.Entry<String, Integer> entry = iterator.previous("A");
//        assertEquals(entry.getValue(), Integer.valueOf(6));
//        assertEquals(iterator.getCurrentIndex(), 4);
//
//        entry = iterator.previous("A");
//        assertEquals(entry.getValue(), Integer.valueOf(3));
//        assertEquals(iterator.getCurrentIndex(), 1);
//    }
//
//    @Test
//    public void testBulkRetrieval() {
//        iterator.moveToIndex(2);  // Move to middle of list
//
//        List<Map.Entry<String, Integer>> nextEntries = iterator.nextList();
//        assertEquals(nextEntries.size(), 3);
//
//        List<Map.Entry<String, Integer>> prevEntries = iterator.previousList();
//        assertEquals(prevEntries.size(), 2);
//
//        List<Map.Entry<String, Integer>> nextAEntries = iterator.nextList("A");
//        assertEquals(nextAEntries.size(), 1);
//        assertEquals(nextAEntries.get(0).getValue(), Integer.valueOf(6));
//
//        List<Map.Entry<String, Integer>> prevAEntries = iterator.previousList("A");
//        assertEquals(prevAEntries.size(), 1);
//        assertEquals(prevAEntries.get(0).getValue(), Integer.valueOf(1));
//    }
//
//    @Test
//    public void testPositionManagement() {
//        assertTrue(iterator.moveToIndex(2));
//        assertEquals(iterator.getCurrentIndex(), 2);
//
//        assertTrue(iterator.moveToKey("B", 2));
//        assertEquals(iterator.getCurrentValue(), Integer.valueOf(5));
//
//        iterator.reset();
//        assertEquals(iterator.getCurrentIndex(), -1);
//
//        iterator.toEnd();
//        assertEquals(iterator.getCurrentIndex(), 5);
//    }
//
//    @Test
//    public void testPeekOperations() {
//        Map.Entry<String, Integer> nextEntry = iterator.peekNext();
//        assertEquals(nextEntry.getValue(), Integer.valueOf(1));
//        assertEquals(iterator.getCurrentIndex(), -1);
//
//        iterator.moveToIndex(2);
//        Map.Entry<String, Integer> prevEntry = iterator.peekPrevious();
//        assertEquals(prevEntry.getValue(), Integer.valueOf(2));
//        assertEquals(iterator.getCurrentIndex(), 2);
//    }
//
//    @Test
//    public void testPositionQueries() {
//        assertFalse(iterator.isFirst());
//        assertFalse(iterator.isLast());
//
//        iterator.next();
//        assertTrue(iterator.isFirst());
//
//        iterator.toEnd();
//        assertTrue(iterator.isLast());
//
//        assertEquals(iterator.size(), 6);
//    }
//
//    @Test(expectedExceptions = NoSuchElementException.class)
//    public void testBoundaryNext() {
//        iterator.toEnd();
//        iterator.next();
//    }
//
//    @Test(expectedExceptions = NoSuchElementException.class)
//    public void testBoundaryPrevious() {
//        iterator.reset();
//        iterator.previous();
//    }
//
//    @Test
//    public void testKeyValueMovement() {
//        assertTrue(iterator.moveToKey("A", 2));
//        assertEquals(iterator.getCurrentValue(), Integer.valueOf(3));
//
//        assertTrue(iterator.moveToValue(5));
//        assertEquals(iterator.getCurrentKey(), "B");
//
//        assertFalse(iterator.moveToKey("Z"));
//        assertFalse(iterator.moveToValue(999));
//    }
//
//    @Test
//    public void testStatePreservation() {
//        iterator.moveToIndex(2);
//        int originalIndex = iterator.getCurrentIndex();
//
//        iterator.nextList();
//        assertEquals(iterator.getCurrentIndex(), originalIndex);
//
//        iterator.previousList("A");
//        assertEquals(iterator.getCurrentIndex(), originalIndex);
//    }
//
//    @Test
//    public void testEdgeCases() {
//        LinkedMultiMap<String, Integer> emptyMap = new LinkedMultiMap<>();
//        NavigableIterator<String, Integer> emptyIterator = emptyMap.navigator();
//
//        assertFalse(emptyIterator.hasNext());
//        assertFalse(emptyIterator.hasPrevious());
//        assertTrue(emptyIterator.nextList().isEmpty());
//        assertTrue(emptyIterator.previousList().isEmpty());
//
//        LinkedMultiMap<String, Integer> singleMap = new LinkedMultiMap<>();
//        singleMap.put("X", 1);
//        NavigableIterator<String, Integer> singleIterator = singleMap.navigator();
//
//        assertTrue(singleIterator.hasNext());
//        assertFalse(singleIterator.hasPrevious());
//        assertEquals(singleIterator.nextList().size(), 1);
//        assertTrue(singleIterator.previousList().isEmpty());
//    }
//}