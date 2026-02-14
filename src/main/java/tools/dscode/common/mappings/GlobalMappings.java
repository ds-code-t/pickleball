package tools.dscode.common.mappings;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static tools.dscode.common.variables.SysEnv.getPickleBallProperty;

/**
 * GlobalMappings Identical to NodeMap, but the public getters/setters are made
 * thread-safe: - put(...): write-locked - get(...), getPojo(...): read-locked
 * All other behavior (wildcards, direct path auto-creation, POJO sidecar, Guava
 * support, etc.) is inherited unchanged from NodeMap.
 */
public class GlobalMappings extends NodeMap {

    public static final String rootDirectory = "configs";

    public final static GlobalMappings GLOBALS = new GlobalMappings();

    private GlobalMappings() {
        super(MapConfigurations.MapType.GLOBAL_NODE);
        root.set(rootDirectory, FileAndDataParsing.buildJsonFromPath(rootDirectory));
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
        Object returnObj = getPickleBallProperty(key);
        if (returnObj != null) return returnObj;
        r.lock();
        try {
            return super.get(key);
        } finally {
            r.unlock();
        }
    }
}
