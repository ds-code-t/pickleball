//package io.cucumber.core.runner;
//
//import java.lang.reflect.*;
//import java.util.*;
//import io.cucumber.datatable.DataTable;
//import io.cucumber.docstring.DocString;
//
//import static io.cucumber.core.runner.util.ArgumentUtility.emptyDataTable;
//import static io.cucumber.core.runner.util.ArgumentUtility.emptyDocString;
//
//public final class TypeSwitch {
//
//    public static io.cucumber.core.stepexpression.Argument classify(Type type) {
//        Class<?> raw = toRawClass(type);
//        String name = raw.getName();
//
//        return switch (name) {
//            case "io.cucumber.datatable.DataTable" -> emptyDataTable();
//            case "io.cucumber.docstring.DocString" -> emptyDocString();
//            case "java.lang.String"                -> "String";
//
//            default -> {
//                if (List.class.isAssignableFrom(raw))  yield "List";
//                if (Set.class.isAssignableFrom(raw))   yield "Set";
//                if (Map.class.isAssignableFrom(raw))   yield "Map";
//                yield "Other: " + name;
//            }
//        };
//    }
//
//    // Resolve Type → raw Class
//    private static Class<?> toRawClass(Type type) {
//        return switch (type) {
//            case Class<?> c -> c;
//
//            case ParameterizedType pt ->
//                    (Class<?>) pt.getRawType();
//
//            case GenericArrayType gat -> {
//                Class<?> component = toRawClass(gat.getGenericComponentType());
//                yield java.lang.reflect.Array
//                        .newInstance(component, 0)
//                        .getClass();
//            }
//
//            case WildcardType wc ->
//                    toRawClass(wc.getUpperBounds()[0]);
//
//            case TypeVariable<?> tv ->
//                    Object.class; // erased type unknown
//
//            default ->
//                    throw new IllegalArgumentException("Unknown Type: " + type);
//        };
//    }
//}
