//package tools.ds.modkit.mappings;
//
//import com.google.common.collect.LinkedListMultimap;
//import io.cucumber.datatable.DataTable;
//import java.util.List;
//
//public class StepMap extends ParsingMap {
//
//
//
//
//    private static final String rowMap = "rowMap";
//    private static final String tableMap = "tableMap";
//
//
//    public StepMap(List<Object> executionArguments) {
//        maps.put(runMap, new NodeMap());
//        mapDataTable(executionArguments.stream()
//                .filter(DataTable.class::isInstance)
//                .map(DataTable.class::cast)
//                .findFirst()
//                .orElse(null));
//    }
//
//    public void mapDataTable(DataTable table) {
//        if(table == null)
//            return;
//
//        NodeMap rowNode = new NodeMap();
//
//        // If your Cucumber version requires a typed call, use: table.asLists(String.class)
//        List<List<String>> rows = table.asLists();
//        if (rows.isEmpty()) return;
//
//        List<String> headers = rows.get(0);
////        List<LinkedListMultimap<String, String>> result = new ArrayList<>(rows.size() - 1);
//
//        LinkedListMultimap<String, String> tableLinkedListMultimap = LinkedListMultimap.create();
//
//        for (int r = 1; r < rows.size(); r++) {
//            List<String> row = rows.get(r);
//            LinkedListMultimap<String, String> rowLinkedListMultimap = LinkedListMultimap.create();
//
//            // Use max to also handle extra cells or missing cells per row
//            int cols = Math.max(headers.size(), row.size());
//            for (int c = 0; c < cols; c++) {
//                String key = (c < headers.size()) ? headers.get(c) : "_col" + c;
//                String val = (c < row.size()) ? row.get(c) : "";
//                // Put retains duplicates under the same key (the point of LinkedListMultimap)
//                rowLinkedListMultimap.put(key, val);
//                tableLinkedListMultimap.put(key, val);
//            }
//            rowNode.put("ROW", rowLinkedListMultimap);
//        }
//
//        NodeMap tableNode = new NodeMap(tableLinkedListMultimap);
//
//        maps.put(rowMap, rowNode);
//        maps.put(tableMap, tableNode);
//    }
//
//
//}
