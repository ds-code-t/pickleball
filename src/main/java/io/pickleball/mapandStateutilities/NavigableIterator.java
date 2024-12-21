// NavigableIterator.java
package io.pickleball.mapandStateutilities;

import java.util.Map;
import java.util.List;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Enhanced interface for advanced iteration with navigation capabilities.
 * Provides bi-directional movement, filtered navigation, and bulk operations.
 */
public interface NavigableIterator<K, V> extends Iterator<Map.Entry<K, V>> {
    // Basic Iterator methods with overloads
    boolean hasNext();
    boolean hasNext(K key);
    boolean hasNext(int occurrence);
    boolean hasNext(K key, int occurrence);

    Map.Entry<K, V> next();
    Map.Entry<K, V> next(K key);
    Map.Entry<K, V> next(int occurrence);
    Map.Entry<K, V> next(K key, int occurrence);

    boolean hasPrevious();
    boolean hasPrevious(K key);
    boolean hasPrevious(int occurrence);  // Reverse counting from current position
    boolean hasPrevious(K key, int occurrence);  // Reverse counting from current position

    Map.Entry<K, V> previous();
    Map.Entry<K, V> previous(K key);
    Map.Entry<K, V> previous(int occurrence);  // Reverse counting from current position
    Map.Entry<K, V> previous(K key, int occurrence);  // Reverse counting from current position

    // Bulk retrieval methods
    List<Map.Entry<K, V>> nextList();  // All entries after current position
    List<Map.Entry<K, V>> nextList(K key);  // Filtered by key

    List<Map.Entry<K, V>> previousList();  // All entries before current position, reverse order
    List<Map.Entry<K, V>> previousList(K key);  // Filtered by key, reverse order

    // Position seeking
    void reset();
    void toEnd();

    // Position by index
    boolean moveToIndex(int index);
    int getCurrentIndex();

    // Position by key/value
    boolean moveToKey(K key);
    boolean moveToKey(K key, int occurrence);
    boolean moveToValue(V value);
    boolean moveToValue(V value, int occurrence);

    // Peek methods
    Map.Entry<K, V> peekNext();
    Map.Entry<K, V> peekPrevious();

    // Entry information
    K getCurrentKey();
    V getCurrentValue();
    boolean isFirst();
    boolean isLast();
    int size();
}