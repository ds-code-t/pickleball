package tools.dscode.common.mappings;

import com.google.common.collect.LinkedListMultimap;

public class ScenarioMapping {

    private ParsingMap parsingMap = new ParsingMap();
    private final NodeMap runMap = parsingMap.getPrimaryRunMap();

    public ParsingMap getParsingMap() {
        System.out.println("@@getParsingMap()1");
        System.out.println("@@getParsingMap()2: " + parsingMap);
        return parsingMap;
    }

    public NodeMap getRunMap() {
        return runMap;
    }

    public void mergeToRunMap(LinkedListMultimap<?, ?> obj) {
        runMap.merge(obj);
    }

    public void put(Object key, Object value) {
        if (key == null || (key instanceof String && ((String) key).isBlank()))
            throw new RuntimeException("key cannot be null or blank");
           runMap.put(String.valueOf(key), value);
    }

    public Object get(Object key) {
        return parsingMap.get(key);
    }

    public Object resolve(String key) {
        return parsingMap.resolveWholeText(key);
    }

//    public void setParsingMap(ParsingMap parsingMap) {
//        this.parsingMap = parsingMap;
//    }


}
