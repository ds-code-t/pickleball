package tools.ds.modkit.mappings;

import java.util.Map;

public class QuotedSegmentsDemo {
    static final class External {
        String transform(String value, int index, String tag) {
            return ("[" + tag + ":" + index + "] " + value.toUpperCase().replace("\"","*"));
        }
    }

    public static void main(String[] args) {
        String input = "Hello, \"a,\\\"b\" and 'c:\\'drive' and \"x\\`y`\". and `s:\\'drive`  and `w:\\\"drive`";
//        String input = "Hello \"'\"   \"`\" end";
        QuoteParser qs = new QuoteParser(input);

        // modify OUTER text freely
        qs.setMasked(qs.masked().replace("Hello", "Hi"));

        // modify only DOUBLE-quoted values, passing extra args (no lambda needed)
        External ep = new External();
        int i = 0;

        for (Map.Entry<String,String> e : qs.entriesDouble()) {
//            String updated = ep.transform(e.getValue(), i++, "DOUBLE");
            String updated = ep.transform(e.getValue(), i++, "DOUBLE");
            qs.put(e.getKey(), updated); // the map IS the store
        }

        // likewise for singles / backticks if you want:
        // for (Map.Entry<String,String> e : qs.entriesSingle()) { ... }
        // for (Map.Entry<String,String> e : qs.entriesBacktick()) { ... }

        String finalText = qs.restore();
        System.out.println("Original : " + qs.original());
        System.out.println("Masked   : " + qs.masked());
        System.out.println("Map      : " + qs); // placeholder -> inner value
        System.out.println("Restored : " + finalText);
    }
}
