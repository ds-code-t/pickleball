//package io.pickleball.customtypes;
//
//import org.mvel2.integration.VariableResolver;
//import org.mvel2.integration.VariableResolverFactory;
//
//import java.util.Arrays;
//import java.util.HashSet;
//import java.util.Map;
//import java.util.Set;
//
//import static io.pickleball.configs.Constants.flag3;
//import static io.pickleball.stringutilities.QuotedSubstringExtractor.extractQuotedSubstrings;
//import static io.pickleball.stringutilities.QuotedSubstringExtractor.restoreQuotedSubstrings;
//
//public class BooleanCoercingResolverFactory implements VariableResolverFactory {
//    private final Set<String> truthyStrings = new HashSet<>(Arrays.asList(
//            "YES", "TRUE", "1"
//    ));
//    private VariableResolverFactory nextFactory;
//    private boolean tiltFlag;
//
//    public BooleanCoercingResolverFactory() {
//        System.out.println("@@BooleanCoercingResolverFactory constructor");
//        // This factory doesn't store any values, it just coerces them
//    }
//
//    private Boolean coerceToBoolean(Object value) {
//        System.out.println("@@coerceToBoolean: " + value);
//        if (value == null) return false;
//
//        if (value instanceof Number) {
//            double numValue = ((Number) value).doubleValue();
//            return numValue != 0;
//        }
//
//        if (value instanceof String) {
//            String str = ((String) value).trim();
//
//            // First try parsing as a number
//            try {
//                double numValue = Double.parseDouble(str);
//                return numValue != 0;
//            } catch (NumberFormatException e) {
//                // If not a number, then check if it's in our truthy strings list
//                return truthyStrings.contains(str.toUpperCase());
//            }
//        }
//
//        return false;
//    }
//
//    @Override
//    public VariableResolver getVariableResolver(String name) {
//        System.out.println("@@BooleanCoercingFactory.getVariableResolver called for: " + name);
//        if (nextFactory != null && nextFactory.isResolveable(name)) {
//            System.out.println("@@Resolving through next factory");
//            VariableResolver originalResolver = nextFactory.getVariableResolver(name);
//            System.out.println("@@Got original resolver for: " + name + " with value: " + originalResolver.getValue());
//            return new BooleanCoercingResolver(originalResolver);
//        }
//        throw new RuntimeException("No next factory configured for boolean coercion");
//    }
//
//    // Implement other required methods...
//    @Override
//    public VariableResolver createVariable(String name, Object value) {
//        return nextFactory.createVariable(name, value);
//    }
//
//    @Override
//    public VariableResolver createIndexedVariable(int index, String name, Object value) {
//        return nextFactory.createIndexedVariable(index, name, value);
//    }
//
//    @Override
//    public VariableResolver createVariable(String name, Object value, Class<?> type) {
//        return nextFactory.createVariable(name, value, type);
//    }
//
//    @Override
//    public VariableResolver createIndexedVariable(int index, String name, Object value, Class<?> type) {
//        return nextFactory.createIndexedVariable(index, name, value, type);
//    }
//
//    @Override
//    public VariableResolver setIndexedVariableResolver(int index, VariableResolver resolver) {
//        return nextFactory.setIndexedVariableResolver(index, resolver);
//    }
//
//    @Override
//    public VariableResolver getIndexedVariableResolver(int index) {
//        return nextFactory.getIndexedVariableResolver(index);
//    }
//
//    @Override
//    public boolean isTarget(String name) {
//        return nextFactory.isTarget(name);
//    }
//
//    @Override
//    public boolean isResolveable(String name) {
//        return nextFactory.isResolveable(name);
//    }
//
//    @Override
//    public Set<String> getKnownVariables() {
//        return nextFactory.getKnownVariables();
//    }
//
//    @Override
//    public int variableIndexOf(String name) {
//        return nextFactory.variableIndexOf(name);
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
//
//    private class BooleanCoercingResolver implements VariableResolver {
//        private final VariableResolver delegate;
//
//        public BooleanCoercingResolver(VariableResolver delegate) {
//            this.delegate = delegate;
//        }
//
//        @Override
//        public String getName() {
//            return delegate.getName();
//        }
//
//        @Override
//        public Class getType() {
//            return Boolean.class;
//        }
//
//        @Override
//        public void setStaticType(Class type) {
//            delegate.setStaticType(type);
//        }
//
//        @Override
//        public int getFlags() {
//            return delegate.getFlags();
//        }
//
//        @Override
//        public Object getValue() {
//            Object originalValue = delegate.getValue();
//            System.out.println("@@BooleanCoercingResolver.getValue called for: " + getName() + " with original value: " + originalValue);
//            Boolean result = coerceToBoolean(originalValue);
//            System.out.println("@@Coerced value to: " + result);
//            return result;
//        }
//
//        @Override
//        public void setValue(Object value) {
//            delegate.setValue(value);
//        }
//    }
//
//
//}