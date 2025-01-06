//package io.pickleball.valueresolution;
//
//import org.mvel2.ConversionHandler;
//
//// Custom conversion handler for UniversalValue to Number
//public class UniversalValueToNumberConverter implements ConversionHandler {
//    @Override
//    public Object convertFrom(Object in) {
//        if (!(in instanceof UniversalValue)) {
//            return null;
//        }
//
//        UniversalValue uv = (UniversalValue) in;
//        Object value = uv.getValue();
//
//        if (value instanceof Number) {
//            return value;
//        } else if (value instanceof Boolean) {
//            return ((Boolean) value) ? 1 : 0;
//        } else if (value instanceof String) {
//            try {
//                return Integer.parseInt((String) value);
//            } catch (NumberFormatException e) {
//                String str = ((String) value).toLowerCase();
//                return switch (str) {
//                    case "true", "yes", "on" -> 1;
//                    case "false", "no", "off" -> 0;
//                    default -> 0;
//                };
//            }
//        }
//        return 0;
//    }
//
//    @Override
//    public boolean canConvertFrom(Class cls) {
//        return UniversalValue.class.isAssignableFrom(cls);
//    }
//}