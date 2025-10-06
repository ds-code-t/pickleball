package tools.ds.modkit.mappings;


import java.util.List;


public class ParsingMap extends MappingProcessor {

    public enum MapType {OVERRIDE_MAP, PASSED_TABLE, EXAMPLE_TABLE, STEP_TABLE, STEP_MAP, RUN_MAP, GLOBAL_NODE, DEFAULT}

    public List<NodeMap> getNodeMaps(MapType mapType)
    {
        return getMaps().get(mapType);
    }
//    public static final String overrideMapKey = "overrideMap";
//    public static final String stepMapKey = "stepMap";
//    public static final String runMapKey = "runMap";
//    public static final String globalMapKey = "globalMap";
//    public static final String DefaultMapKey = "DefaultMap";


    public ParsingMap(NodeMap primaryRunMap) {
        super(MapType.OVERRIDE_MAP, MapType.PASSED_TABLE, MapType.EXAMPLE_TABLE, MapType.STEP_TABLE, MapType.STEP_MAP, MapType.GLOBAL_NODE, MapType.DEFAULT);
        this.primaryRunMap = primaryRunMap;    }

    public NodeMap getPrimaryRunMap() {
        return primaryRunMap;
    }

    public final NodeMap primaryRunMap;

    public ParsingMap(ParsingMap parsingMap) {
        super(parsingMap);
        this.primaryRunMap = new NodeMap(MapType.RUN_MAP);
    }




}