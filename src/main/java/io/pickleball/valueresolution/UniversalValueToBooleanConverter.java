//package io.pickleball.valueresolution;
//
//import org.mvel2.ConversionHandler;
//
//// Custom conversion handler for UniversalValue to Boolean
//public class UniversalValueToBooleanConverter implements ConversionHandler {
//    @Override
//    public Object convertFrom(Object in) {
//        if (!(in instanceof UniversalValue)) {
//            return null;
//        }
//
//        UniversalValue uv = (UniversalValue) in;
//        Object value = uv.getValue();
//
//        if (value instanceof Boolean) {
//            return value;
//        } else if (value instanceof Number) {
//            return ((Number) value).intValue() != 0;
//        } else if (value instanceof String) {
//            String str = ((String) value).toLowerCase();
//            return switch (str) {
//                case "true", "yes", "1", "on" -> true;
//                case "false", "no", "0", "off" -> false;
//                default -> false;
//            };
//        }
//        return false;
//    }
//
//    @Override
//    public boolean canConvertFrom(Class cls) {
//        return UniversalValue.class.isAssignableFrom(cls);
//    }
//}