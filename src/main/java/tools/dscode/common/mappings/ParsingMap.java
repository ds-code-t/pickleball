package tools.dscode.common.mappings;

import java.util.List;

import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;

public class ParsingMap extends MappingProcessor {

    public List<NodeMap> getNodeMaps(MapConfigurations.MapType mapType) {
        return getMaps().get(mapType);
    }

    public ParsingMap() {
    }
    private ParsingMap(NodeMap nodeMap) {
        super(nodeMap);
    }

    public final static ParsingMap GLOBALS_PARSINGMAP = new ParsingMap(GLOBALS);

}
