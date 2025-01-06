//package io.pickleball.valueresolution;
//
//import org.mvel2.ConversionException;
//import org.mvel2.ConversionHandler;
//import org.mvel2.conversion.BooleanCH;
//import org.mvel2.conversion.Converter;
//
//import java.math.BigDecimal;
//import java.util.HashMap;
//import java.util.Map;
//
//import static io.pickleball.valueresolution.BooleanResolver.resolveObjectToBoolean;
//
///// Custom conversion handler for MVEL
//public class UniversalValueConverter implements ConversionHandler {
//    private static final Map<Class, Converter> CNV = new HashMap();
//
//
//    public Object convertFrom(Object in) {
//        if (!CNV.containsKey(in.getClass())) {
//            throw new ConversionException("cannot convert type: " + in.getClass().getName() + " to: " + Boolean.class.getName());
//        } else {
//            return ((Converter) CNV.get(in.getClass())).convert(in);
//        }
//    }
//
//    public boolean canConvertFrom(Class cls) {
//        return CNV.containsKey(cls);
//    }
//
//    static {
//        CNV.put(String.class, new Converter() {
//            public Object convert(Object o) {
//                return resolveObjectToBoolean(o);
//            }
//        });
//
//        CNV.put(Object.class, new Converter() {
//            public Object convert(Object o) {
//                return resolveObjectToBoolean(o);
//            }
//        });
//        CNV.put(Boolean.class, new Converter() {
//            public Object convert(Object o) {
//                return o;
//            }
//        });
//        CNV.put(Integer.class, new Converter() {
//            public Boolean convert(Object o) {
//                return (Integer) o > 0;
//            }
//        });
//        CNV.put(Float.class, new Converter() {
//            public Boolean convert(Object o) {
//                return (Float) o > 0.0F;
//            }
//        });
//        CNV.put(Double.class, new Converter() {
//            public Boolean convert(Object o) {
//                return (Double) o > 0.0;
//            }
//        });
//        CNV.put(Short.class, new Converter() {
//            public Boolean convert(Object o) {
//                return (Short) o > 0;
//            }
//        });
//        CNV.put(Long.class, new Converter() {
//            public Boolean convert(Object o) {
//                return (Long) o > 0L;
//            }
//        });
//        CNV.put(Boolean.TYPE, new Converter() {
//            public Boolean convert(Object o) {
//                return (Boolean) o;
//            }
//        });
//        CNV.put(BigDecimal.class, new Converter() {
//            public Boolean convert(Object o) {
//                return ((BigDecimal) o).doubleValue() > 0.0;
//            }
//        });
//    }
//}
//
//
//
////    @Override
////    public Object convertFrom(Object in) {
////        if (!(in instanceof UniversalValue)) {
////            return null;
////        }
////
////        UniversalValue uv = (UniversalValue) in;
////        Object value = uv.getValue();
////
////        // Convert to appropriate type based on the value
////        if (value instanceof Boolean) {
////            return value;
////        } else if (value instanceof Number) {
////            Number num = (Number) value;
////            // For boolean context, treat 0 as false, non-zero as true
////            return num.intValue() != 0;
////        } else if (value instanceof String) {
////            String str = ((String) value).toLowerCase();
////            // Handle string conversions
////            return switch (str) {
////                case "true", "yes", "1", "on" -> true;
////                case "false", "no", "0", "off" -> false;
////                default -> {
////                    // Try parsing as number
////                    try {
////                        int numValue = Integer.parseInt(str);
////                        yield numValue != 0;
////                    } catch (NumberFormatException e) {
////                        yield false;
////                    }
////                }
////            };
////        }
////
////        return false;
////    }
////
////    @Override
////    public boolean canConvertFrom(Class cls) {
////        return UniversalValue.class.isAssignableFrom(cls);
////    }
////}