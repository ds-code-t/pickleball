package tools.ds.modkit.mappings;


import java.util.List;


public class ParsingMap extends MappingProcessor {

    //    public enum MapType {OVERRIDE_MAP, PASSED_TABLE, EXAMPLE_TABLE, STEP_TABLE, STEP_MAP, RUN_MAP, GLOBAL_NODE, DEFAULT}
    public enum MapType {OVERRIDE_MAP, STEP_MAP, RUN_MAP, GLOBAL_NODE, DEFAULT}

    public List<NodeMap> getNodeMaps(MapType mapType) {
        return getMaps().get(mapType);
    }


    public ParsingMap() {
        super(MapType.OVERRIDE_MAP, MapType.STEP_MAP, MapType.GLOBAL_NODE, MapType.DEFAULT);
        this.primaryRunMap = new NodeMap(MapType.RUN_MAP);
        addMaps(primaryRunMap);
    }

    public NodeMap getPrimaryRunMap() {
        return primaryRunMap;
    }

    public final NodeMap primaryRunMap;

    public ParsingMap(ParsingMap parsingMap) {
        super(parsingMap);
        this.primaryRunMap = parsingMap.primaryRunMap;
    }




}