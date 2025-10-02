package tools.ds.modkit.mappings;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * GlobalMappings
 *
 * Identical to NodeMap, but the public getters/setters are made thread-safe:
 * - put(...): write-locked
 * - get(...), getPojo(...): read-locked
 *
 * All other behavior (wildcards, direct path auto-creation, POJO sidecar, Guava support, etc.)
 * is inherited unchanged from NodeMap.
 */
public class GlobalMappings extends NodeMap {

    public final static GlobalMappings GLOBALS = new GlobalMappings();

    public GlobalMappings(){
        setMapType(ParsingMap.MapType.GLOBAL_NODE);
    }

    private final ReadWriteLock rw = new ReentrantReadWriteLock();
    private final Lock r = rw.readLock();
    private final Lock w = rw.writeLock();

    @Override
    public void put(String key, Object value) {
        w.lock();
        try {
            super.put(key, value);
        } finally {
            w.unlock();
        }
    }

    @Override
    public Object get(String key) {
        r.lock();
        try {
            return super.get(key);
        } finally {
            r.unlock();
        }
    }



}
