package tools.ds.modkit.trace;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public final class ObjDataRegistry {

    public enum ObjFlags { NOT_SET, INITIALIZING, RUNNING, COMPLETE, LAST }

    private ObjDataRegistry() {}

    /** Top-level: weak keys so entries vanish when owner is GC’d. */
    private static final Map<Object, Map<Object, Object>> DATA =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Key used inside each bucket to store the flags list. */
    private static final Object FLAGS_KEY = new Object();

    /* -------------------- generic per-owner data -------------------- */

    /** Put arbitrary data into the owner's bucket under the given key. */
    public static void set(Object owner, Object key, Object value) {
        if (owner == null) throw new IllegalArgumentException("set: owner must not be null");
        if (key == null)   throw new IllegalArgumentException("set: key must not be null");
        // ConcurrentHashMap does not allow null values
        if (value == null) throw new IllegalArgumentException("set: value must not be null");
        bucket(owner).put(key, value);
    }

    /** Get arbitrary data from the owner's bucket (or null if absent). */
    public static Object get(Object owner, Object key) {
        if (owner == null) throw new IllegalArgumentException("get: owner must not be null");
        if (key == null)   throw new IllegalArgumentException("get: key must not be null");
        Map<Object, Object> m = bucketIfPresent(owner);
        return (m == null) ? null : m.get(key);
    }

    /* -------------------- flags API (unchanged semantics) -------------------- */

    /** Append flags (in order). Creates the list if missing. */
    public static void setFlag(Object owner, ObjFlags... flags) {
        if (owner == null) throw new IllegalArgumentException("setFlag: owner must not be null");
        if (flags == null || flags.length == 0) return; // no-op
        Map<Object, Object> m = bucket(owner);
        @SuppressWarnings("unchecked")
        List<ObjFlags> list = (List<ObjFlags>) m.computeIfAbsent(
                FLAGS_KEY, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            Collections.addAll(list, flags);
        }
    }

    /** Return the last flag added, or NOT_SET if none. */
    public static ObjFlags getFlag(Object owner) {
        if (owner == null) throw new IllegalArgumentException("getFlag: owner must not be null");
        Map<Object, Object> m = bucketIfPresent(owner);
        if (m == null) return ObjFlags.NOT_SET;
        @SuppressWarnings("unchecked")
        List<ObjFlags> list = (List<ObjFlags>) m.get(FLAGS_KEY);
        if (list == null || list.isEmpty()) return ObjFlags.NOT_SET;
        synchronized (list) {
            return list.isEmpty() ? ObjFlags.NOT_SET : list.get(list.size() - 1);
        }
    }

    /** True iff the owner's flag list contains ALL provided flags. */
    public static boolean containsFlags(Object owner, ObjFlags... flags) {
        if (owner == null) throw new IllegalArgumentException("containsFlags: owner must not be null");
        if (flags == null || flags.length == 0)
            throw new IllegalArgumentException("containsFlags: at least one flag is required");
        Map<Object, Object> m = bucketIfPresent(owner);
        if (m == null) return false;
        @SuppressWarnings("unchecked")
        List<ObjFlags> list = (List<ObjFlags>) m.get(FLAGS_KEY);
        if (list == null || list.isEmpty()) return false;
        synchronized (list) {
            for (ObjFlags f : flags) {
                if (!list.contains(f)) return false;
            }
            return true;
        }
    }

    /** Remove only the flags entry for this owner. */
    public static void clearFlags(Object owner) {
        if (owner == null) throw new IllegalArgumentException("clearFlags: owner must not be null");
        Map<Object, Object> m = bucketIfPresent(owner);
        if (m != null) m.remove(FLAGS_KEY);
    }

    /* -------------------- internals -------------------- */

    private static Map<Object, Object> bucket(Object owner) {
        synchronized (DATA) {
            return DATA.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        }
    }

    private static Map<Object, Object> bucketIfPresent(Object owner) {
        synchronized (DATA) {
            return DATA.get(owner);
        }
    }
}
