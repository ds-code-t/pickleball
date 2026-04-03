//package tools.dscode.common.dataoperations;
//
//import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
//
//import static io.cucumber.core.runner.util.TableUtils.VALUE_KEY;
//import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;
//
//public class DataQueries {
//
//    public static Object getDataValue(ElementMatch dataElement) {
//        String categoryName = dataElement.category.replaceFirst("(?i:s)$", "");
//        String text = dataElement.defaultText == null  || dataElement.defaultText.getValue() == null ? "" : dataElement.defaultText.getValue().toString();
//        if(categoryName.equals(VALUE_KEY)) {
//            getRunningParsingMap().get(text)
//
//        }
//
//
//    }
//
//
//}
