package tools.dscode.modkit.mappings;


import java.util.List;


public class ParsingMap extends MappingProcessor {



    public List<NodeMap> getNodeMaps(MapConfigurations.MapType mapType) {
        return getMaps().get(mapType);
    }

    public ParsingMap() {
    }



    public ParsingMap(ParsingMap parsingMap) {
        super(parsingMap);
    }




}