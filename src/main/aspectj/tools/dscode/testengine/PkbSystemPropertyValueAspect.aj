package tools.dscode.testengine;

import java.util.Properties;

/** Normalizes command-line wrapping quotes on pkb_* JVM system-property values. */
public privileged aspect PkbSystemPropertyValueAspect {
    String around(Properties properties, String key):
            call(String java.util.Properties.getProperty(String))
            && target(properties)
            && args(key)
            && withincode(private void PickleballRunner.mergeAllSystemProperties()) {
        return PkbPropertyValueNormalizer.normalizeSystemProperty(
                key,
                proceed(properties, key)
        );
    }
}
