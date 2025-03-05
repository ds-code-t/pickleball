//package io.pickleball.datafunctions;
//
//import java.util.Collection;
//import java.util.List;
//import java.util.Map;
//import java.util.Set;
//import java.util.stream.Collectors;
//
//public class DataNodeWrapper implements Map<String, String> {
//
//    private final MultiFormatDataNode multiFormatDataNode;
//
//
//    public DataNodeWrapper(MultiFormatDataNode multiFormatDataNode)
//    {
//        this.multiFormatDataNode = multiFormatDataNode;
//    }
//
//    @Override
//    public int size() {
//        return multiFormatDataNode.size();
//    }
//
//    @Override
//    public boolean isEmpty() {
//        return multiFormatDataNode.isEmpty();
//    }
//
//    @Override
//    public boolean containsKey(Object key) {
//        return multiFormatDataNode.containsKey(key);
//    }
//
//    @Override
//    public boolean containsValue(Object value) {
//        return multiFormatDataNode.containsValue(value);
//    }
//
//    @Override
//    public String get(Object key) {
//        return String.valueOf(multiFormatDataNode.get(key));
//    }
//
//    @Override
//    public String put(String key, String value) {
//        return null;
//    }
//
//    @Override
//    public String remove(Object key) {
//        return null;
//    }
//
//    @Override
//    public void putAll(Map<? extends String, ? extends String> m) {
//
//    }
//
//    @Override
//    public void clear() {
//
//    }
//
//    @Override
//    public Set<String> keySet() {
//        return null;
//    }
//
//    @Override
//    public Collection<String> values() {
//        return null;
//    }
//
//    @Override
//    public Set<Entry<String, String>> entrySet() {
//        return null;
//    }
//}
