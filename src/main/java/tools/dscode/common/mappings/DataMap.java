package tools.dscode.common.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.datatable.DataTable;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;

import java.util.ArrayList;
import java.util.List;

import static io.cucumber.core.runner.util.DataUtils.TABLE_KEY;
import static tools.dscode.common.treeparsing.parsedComponents.ElementMatch.getElementMatchesFromString;

public class DataMap extends NodeMap {


    public DataMap() {
        super(MapConfigurations.MapType.DATA_MAP);
    }

//    public DataTable getDataTableFromDataMap(Object key) {
//        if (key instanceof String keyString && !keyString.contains("-" + TABLE_KEY))
//            key = getDataElementMatch(keyString);
//        if (key instanceof ElementMatch elementMatch)
//            return (DataTable) get(elementMatch.getKey(TABLE_KEY));
//        if(key instanceof String keyString2){
//            Object returnObj = get(keyString2);
//            if(returnObj == null || returnObj instanceof DataTable)
//                return (DataTable) returnObj;
//            throw new RuntimeException("'" +keyString2 + ", key returns '"  + returnObj.getClass().getName() + "' not a DataTable");
//        }
//        throw new RuntimeException("Invalid key type: " + key.getClass().getName());
//    }
//
//    public JsonNode getData(Object key) {
//        return (JsonNode) get(getDataMapKey(key));
//    }
//
//    public void setData(Object key, Object value) {
//        value = toJsonNodeOrDataTable(value);
//        put(getDataMapKey(key, value), value);
//    }
//
//
//    public static Object toJsonNodeOrDataTable(Object obj) {
//        if (obj == null || obj instanceof JsonNode || obj instanceof DataTable)
//            return obj;
//        while (obj instanceof ValueWrapper valueWrapper) {
//            obj = valueWrapper.getValue();
//        }
//        if (obj == null || obj instanceof JsonNode || obj instanceof DataTable)
//            return obj;
//        return MAPPER.valueToTree(obj);
//    }
//
//    public static String getDataMapKey(Object objKey) {
//        return getDataMapKey(objKey, null);
//    }
//
//    public static String getDataMapKey(Object objKey, Object value) {
//        if (objKey instanceof String key) {
//            if (getDataElementMatch(key) instanceof ElementMatch elementMatch) {
//                return value instanceof DataTable ? elementMatch.getKey(TABLE_KEY) : elementMatch.getKey();
//            }
//            return key;
//        }
//        if (objKey instanceof ElementMatch elementMatch) {
//            return value instanceof DataTable ? elementMatch.getKey(TABLE_KEY) : elementMatch.getKey();
//        }
//        throw new RuntimeException("Invalid key type: " + objKey.getClass().getName());
//    }
//
//    public static ElementMatch getDataElementMatch(String key) {
//        List<ElementMatch> elementMatches;
//        try {
//            elementMatches = new ArrayList<>(getElementMatchesFromString(key));
//        } catch (Exception e) {
//            return null;
//        }
//        if (elementMatches.size() == 1 && elementMatches.getFirst().elementTypes.contains(ElementType.DATA_TYPE))
//            return elementMatches.getFirst();
//        return null;
//    }


}
