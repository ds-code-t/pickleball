//package io.pickleball.mapandStateutilities;
//
//import java.util.*;
//
//public class LinkedMultiMapIterator<K, V> implements NavigableIterator<K, V> {
//    private final LinkedMultiMap<K, V> map;
//    private int currentIndex;
//    private final int maxIndex;
//
//    public LinkedMultiMapIterator(LinkedMultiMap<K, V> map) {
//        this.map = Objects.requireNonNull(map, "Map cannot be null");
//        this.currentIndex = -1;
//        this.maxIndex = map.size() - 1;
//    }
//
//    @Override
//    public boolean hasNext() {
//        return currentIndex < maxIndex;
//    }
//
//    @Override
//    public boolean hasNext(K key) {
//        for (int i = currentIndex + 1; i <= maxIndex; i++) {
//            if (Objects.equals(map.keys.get(i), key)) {
//                return true;
//            }
//        }
//        return false;
//    }
//
//    @Override
//    public boolean hasNext(int occurrence) {
//        return (currentIndex + occurrence) <= maxIndex;
//    }
//
//    @Override
//    public boolean hasNext(K key, int occurrence) {
//        int count = 0;
//        for (int i = currentIndex + 1; i <= maxIndex; i++) {
//            if (Objects.equals(map.keys.get(i), key)) {
//                count++;
//                if (count == occurrence) {
//                    return true;
//                }
//            }
//        }
//        return false;
//    }
//
//    @Override
//    public Map.Entry<K, V> next() {
//        if (!hasNext()) {
//            throw new NoSuchElementException("No more elements");
//        }
//        currentIndex++;
//        return createEntry(currentIndex);
//    }
//
//    @Override
//    public Map.Entry<K, V> next(K key) {
//        int savedIndex = currentIndex;
//        while (hasNext()) {
//            Map.Entry<K, V> entry = next();
//            if (Objects.equals(entry.getKey(), key)) {
//                return entry;
//            }
//        }
//        currentIndex = savedIndex;
//        throw new NoSuchElementException("No more elements with key: " + key);
//    }
//
//    @Override
//    public Map.Entry<K, V> next(int occurrence) {
//        if (!hasNext(occurrence)) {
//            throw new NoSuchElementException("No more elements at occurrence: " + occurrence);
//        }
//        currentIndex += occurrence;
//        return createEntry(currentIndex);
//    }
//
//    @Override
//    public Map.Entry<K, V> next(K key, int occurrence) {
//        if (!hasNext(key, occurrence)) {
//            throw new NoSuchElementException("No more elements with key: " + key + " at occurrence: " + occurrence);
//        }
//        int savedIndex = currentIndex;
//        int count = 0;
//        while (hasNext()) {
//            Map.Entry<K, V> entry = next();
//            if (Objects.equals(entry.getKey(), key)) {
//                count++;
//                if (count == occurrence) {
//                    return entry;
//                }
//            }
//        }
//        currentIndex = savedIndex;
//        throw new NoSuchElementException("No more elements with key: " + key + " at occurrence: " + occurrence);
//    }
//
//    @Override
//    public boolean hasPrevious() {
//        return currentIndex >= 0;
//    }
//
//    @Override
//    public boolean hasPrevious(K key) {
//        for (int i = currentIndex; i >= 0; i--) {
//            if (Objects.equals(map.keys.get(i), key)) {
//                return true;
//            }
//        }
//        return false;
//    }
//
//    @Override
//    public boolean hasPrevious(int occurrence) {
//        return (currentIndex - occurrence + 1) >= 0;
//    }
//
//    @Override
//    public boolean hasPrevious(K key, int occurrence) {
//        int count = 0;
//        for (int i = currentIndex; i >= 0; i--) {
//            if (Objects.equals(map.keys.get(i), key)) {
//                count++;
//                if (count == occurrence) {
//                    return true;
//                }
//            }
//        }
//        return false;
//    }
//
//    @Override
//    public Map.Entry<K, V> previous() {
//        if (!hasPrevious()) {
//            throw new NoSuchElementException("No previous element");
//        }
//        Map.Entry<K, V> entry = createEntry(currentIndex);
//        currentIndex--;
//        return entry;
//    }
//
//    @Override
//    public Map.Entry<K, V> previous(K key) {
//        if (!hasPrevious(key)) {
//            throw new NoSuchElementException("No previous elements with key: " + key);
//        }
//        for (int i = currentIndex; i >= 0; i--) {
//            if (Objects.equals(map.keys.get(i), key)) {
//                currentIndex = i - 1;
//                return createEntry(i);
//            }
//        }
//        throw new NoSuchElementException("No previous elements with key: " + key);
//    }
//
//    @Override
//    public Map.Entry<K, V> previous(int occurrence) {
//        if (!hasPrevious(occurrence)) {
//            throw new NoSuchElementException("No previous elements at occurrence: " + occurrence);
//        }
//        int targetIndex = currentIndex - occurrence + 1;
//        currentIndex = targetIndex - 1;
//        return createEntry(targetIndex);
//    }
//
//    @Override
//    public Map.Entry<K, V> previous(K key, int occurrence) {
//        if (!hasPrevious(key, occurrence)) {
//            throw new NoSuchElementException("No previous elements with key: " + key + " at occurrence: " + occurrence);
//        }
//        int count = 0;
//        for (int i = currentIndex; i >= 0; i--) {
//            if (Objects.equals(map.keys.get(i), key)) {
//                count++;
//                if (count == occurrence) {
//                    currentIndex = i - 1;
//                    return createEntry(i);
//                }
//            }
//        }
//        throw new NoSuchElementException("No previous elements with key: " + key + " at occurrence: " + occurrence);
//    }
//
//    @Override
//    public List<Map.Entry<K, V>> nextList() {
//        List<Map.Entry<K, V>> result = new ArrayList<>();
//        for (int i = currentIndex + 1; i <= maxIndex; i++) {
//            result.add(createEntry(i));
//        }
//        return result;
//    }
//
//    @Override
//    public List<Map.Entry<K, V>> nextList(K key) {
//        List<Map.Entry<K, V>> result = new ArrayList<>();
//        for (int i = currentIndex + 1; i <= maxIndex; i++) {
//            if (Objects.equals(map.keys.get(i), key)) {
//                result.add(createEntry(i));
//            }
//        }
//        return result;
//    }
//
//    @Override
//    public List<Map.Entry<K, V>> previousList() {
//        List<Map.Entry<K, V>> result = new ArrayList<>();
//        // Change from currentIndex to currentIndex - 1 to exclude current element
//        for (int i = currentIndex - 1; i >= 0; i--) {
//            result.add(createEntry(i));
//        }
//        return result;
//    }
//
//    @Override
//    public List<Map.Entry<K, V>> previousList(K key) {
//        List<Map.Entry<K, V>> result = new ArrayList<>();
//        // Change from currentIndex to currentIndex - 1 to exclude current element
//        for (int i = currentIndex - 1; i >= 0; i--) {
//            if (Objects.equals(map.keys.get(i), key)) {
//                result.add(createEntry(i));
//            }
//        }
//        return result;
//    }
//
//    @Override
//    public void reset() {
//        currentIndex = -1;
//    }
//
//    @Override
//    public void toEnd() {
//        currentIndex = maxIndex;
//    }
//
//    @Override
//    public boolean moveToIndex(int index) {
//        if (index < 0 || index > maxIndex) {
//            return false;
//        }
//        currentIndex = index;
//        return true;
//    }
//
//    @Override
//    public int getCurrentIndex() {
//        return currentIndex;
//    }
//
//    @Override
//    public boolean moveToKey(K key) {
//        return moveToKey(key, 1);
//    }
//
//    @Override
//    public boolean moveToKey(K key, int occurrence) {
//        if (occurrence < 1) return false;
//
//        int count = 0;
//        for (int i = 0; i <= maxIndex; i++) {
//            if (Objects.equals(map.keys.get(i), key)) {
//                count++;
//                if (count == occurrence) {
//                    currentIndex = i;
//                    return true;
//                }
//            }
//        }
//        return false;
//    }
//
//    @Override
//    public boolean moveToValue(V value) {
//        return moveToValue(value, 1);
//    }
//
//    @Override
//    public boolean moveToValue(V value, int occurrence) {
//        if (occurrence < 1) return false;
//
//        int count = 0;
//        for (int i = 0; i <= maxIndex; i++) {
//            if (Objects.equals(map.values.get(i), value)) {
//                count++;
//                if (count == occurrence) {
//                    currentIndex = i;
//                    return true;
//                }
//            }
//        }
//        return false;
//    }
//
//    @Override
//    public Map.Entry<K, V> peekNext() {
//        if (!hasNext()) {
//            return null;
//        }
//        return createEntry(currentIndex + 1);
//    }
//
//    @Override
//    public Map.Entry<K, V> peekPrevious() {
//        if (!hasPrevious()) {
//            return null;
//        }
//        // Return the entry before the current index, not at the current index
//        return createEntry(currentIndex - 1);
//    }
//
//    @Override
//    public K getCurrentKey() {
//        if (currentIndex == -1 || currentIndex > maxIndex) {
//            throw new NoSuchElementException("No current element");
//        }
//        return map.keys.get(currentIndex);
//    }
//
//    @Override
//    public V getCurrentValue() {
//        if (currentIndex == -1 || currentIndex > maxIndex) {
//            throw new NoSuchElementException("No current element");
//        }
//        return map.values.get(currentIndex);
//    }
//
//    @Override
//    public boolean isFirst() {
//        return currentIndex == 0;
//    }
//
//    @Override
//    public boolean isLast() {
//        return currentIndex == maxIndex;
//    }
//
//    @Override
//    public int size() {
//        return maxIndex + 1;
//    }
//
//    private Map.Entry<K, V> createEntry(final int index) {
//        return new AbstractMap.SimpleImmutableEntry<>(
//                map.keys.get(index),
//                map.values.get(index)
//        );
//    }
//}