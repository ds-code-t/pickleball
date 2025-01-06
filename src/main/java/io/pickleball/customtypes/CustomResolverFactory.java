//package io.pickleball.customtypes;
//
//import org.mvel2.integration.VariableResolver;
//import org.mvel2.integration.VariableResolverFactory;
//
//import java.util.*;
//
//public class CustomResolverFactory implements VariableResolverFactory {
//    private final List<Map<String, Object>> resolutionMaps;
//    private final Object defaultValue;
//    private final boolean useDefault;
//    private VariableResolverFactory nextFactory;
//    private boolean tiltFlag;
//    private final Map<String, VariableResolver> indexedVariables = new HashMap<>();
//
//    @SafeVarargs
//    public CustomResolverFactory(Object defaultValue, Map<String, Object>... maps) {
//        this.resolutionMaps = Arrays.asList(maps);
//        this.defaultValue = defaultValue;
//        this.useDefault = true;
//    }
//
//    @SafeVarargs
//    public CustomResolverFactory(Map<String, Object>... maps) {
//        this.resolutionMaps = Arrays.asList(maps);
//        this.defaultValue = null;
//        this.useDefault = false;
//    }
//
//    @Override
//    public VariableResolver createVariable(String name, Object value) {
//        // Create in first resolution map
//        if (!resolutionMaps.isEmpty()) {
//            resolutionMaps.get(0).put(name, value);
//        }
//        return new SimpleVariableResolver(name, value);
//    }
//
//    @Override
//    public VariableResolver createIndexedVariable(int index, String name, Object value) {
//        VariableResolver resolver = new SimpleVariableResolver(name, value);
//        indexedVariables.put(name, resolver);
//        return resolver;
//    }
//
//    @Override
//    public VariableResolver createVariable(String name, Object value, Class<?> type) {
//        return createVariable(name, value);
//    }
//
//    @Override
//    public VariableResolver createIndexedVariable(int index, String name, Object value, Class<?> type) {
//        return createIndexedVariable(index, name, value);
//    }
//
//    @Override
//    public VariableResolver setIndexedVariableResolver(int index, VariableResolver resolver) {
//        indexedVariables.put(resolver.getName(), resolver);
//        return resolver;
//    }
//
//    @Override
//    public VariableResolver getVariableResolver(String name) {
//        // Try each resolution map in order
//        for (Map<String, Object> map : resolutionMaps) {
//            if (map.containsKey(name)) {
//                return new SimpleVariableResolver(name, map.get(name));
//            }
//        }
//
//        // Check next factory if available
//        if (nextFactory != null && nextFactory.isResolveable(name)) {
//            return nextFactory.getVariableResolver(name);
//        }
//
//        // If we get here, no resolution was found
//        if (useDefault) {
//            return new SimpleVariableResolver(name, defaultValue);
//        } else {
//            throw new RuntimeException("Unable to resolve reference '" + name + "' in expression. No matching key found in any resolution map.");
//        }
//    }
//
//    @Override
//    public VariableResolver getIndexedVariableResolver(int index) {
//        return indexedVariables.get(index);
//    }
//
//    @Override
//    public boolean isTarget(String name) {
//        for (Map<String, Object> map : resolutionMaps) {
//            if (map.containsKey(name)) {
//                return true;
//            }
//        }
//        return false;
//    }
//
//    @Override
//    public boolean isResolveable(String name) {
//        return isTarget(name) || (nextFactory != null && nextFactory.isResolveable(name)) || useDefault;
//    }
//
//    @Override
//    public Set<String> getKnownVariables() {
//        Set<String> variables = new HashSet<>();
//        for (Map<String, Object> map : resolutionMaps) {
//            variables.addAll(map.keySet());
//        }
//        if (nextFactory != null) {
//            variables.addAll(nextFactory.getKnownVariables());
//        }
//        return variables;
//    }
//
//    @Override
//    public int variableIndexOf(String name) {
//        if (isTarget(name)) {
//            return 0;
//        }
//        return -1;
//    }
//
//    @Override
//    public boolean isIndexedFactory() {
//        return false;
//    }
//
//    @Override
//    public boolean tiltFlag() {
//        return tiltFlag;
//    }
//
//    @Override
//    public void setTiltFlag(boolean tiltFlag) {
//        this.tiltFlag = tiltFlag;
//    }
//
//    @Override
//    public VariableResolverFactory getNextFactory() {
//        return nextFactory;
//    }
//
//    @Override
//    public VariableResolverFactory setNextFactory(VariableResolverFactory factory) {
//        this.nextFactory = factory;
//        return factory;
//    }
//}
//
//class SimpleVariableResolver implements VariableResolver {
//    private final String name;
//    private Object value;
//    private Class<?> type;
//
//    public SimpleVariableResolver(String name, Object value) {
//        this.name = name;
//        this.value = value;
//        this.type = value != null ? value.getClass() : Object.class;
//    }
//
//    @Override
//    public String getName() {
//        return name;
//    }
//
//    @Override
//    public Object getValue() {
//        return value;
//    }
//
//    @Override
//    public void setValue(Object value) {
//        this.value = value;
//    }
//
//    @Override
//    public Class getType() {
//        return type;
//    }
//
//    @Override
//    public void setStaticType(Class type) {
//        this.type = type;
//    }
//
//    @Override
//    public int getFlags() {
//        return 0;
//    }
//}