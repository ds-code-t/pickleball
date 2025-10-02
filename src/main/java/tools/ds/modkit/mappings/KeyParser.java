//package tools.ds.modkit.mappings;
//
//import java.util.*;
//import java.util.regex.*;
//
//public final class KeyParser {
//
//
//    public enum Kind {LIST, MAP, SINGLE}
//
//    public static record KeyParse(String base, Kind kind, int[] intList) {
//        @Override
//        public String toString() {
//            return "KeyParse[base:" + base + " , kind:" + kind + " , intList:" + Arrays.toString(intList);
//        }
//    }
//
//    // base  | optional "as-LIST/MAP" | optional "#<ints and/or ranges>"
//    private static final Pattern KEY_PATTERN = Pattern.compile(
//            "^\\s*(.*?)" +                                  // (1) base (always)
//                    "(?:\\s+(?i:as-(LIST|MAP)))?" +                 // (2) kind (optional)
//                    "(?:\\s*#\\s*(" +                               // (3) numbers (optional)
//                    "\\d+(?:\\s*-\\s*\\d+)?(?:\\s*,\\s*\\d+(?:\\s*-\\s*\\d+)?)*" +
//                    "))?\\s*$"
//    );
//
//    private static final Pattern INDEX_PATTERN = Pattern.compile("#\\s*(\\d+)"); // optional #, then digits
//
//    public static KeyParse parseKey(String input) {
//        if (input == null) return null;
//
//        input = toZeroBasedSeries(input);
//        System.out.println("@@input: " + input);
//
//        Matcher m = KEY_PATTERN.matcher(input.strip());
//        if (!m.matches()) return null;
//
//        String base = m.group(1).strip();
//
//        System.out.println("@@base1: " + base);
////        base = INDEX_PATTERN.matcher(base).replaceAll(mr -> {
////                    int num = Integer.parseInt(mr.group(1));
////                    if (num == 0) throw new RuntimeException("Index cannot be 0 when using '#' syntax");
////                    return "[" + (num - 1) + "]";
////                }).replaceAll("\\s+\\[", "[")   // remove whitespace before '['
////                .replaceAll("]\\s+", "]")// remove whitespace after ']'
////                .replaceAll("\\s*\\.\\s*", ".");
////        System.out.println("@@base2: " + base);
//
//        Kind explicit = null;
//        String g2 = m.group(2); // LIST or MAP if present
//        if (g2 != null) explicit = Kind.valueOf(g2.toUpperCase(Locale.ROOT));
//
//        int[] ints = parseInts(m.group(3)); // preserve order & duplicates
//
//        // Default [-1] only when no ints and no explicit kind
//        if ((ints == null || ints.length == 0) && explicit == null) {
//            ints = new int[]{-1};
//        }
//
//        ints = Arrays.stream(ints).map(i -> {
//            if (i == 0) throw new RuntimeException("Index cannot be 0 when using '#' syntax");
//            if (i > 0) i = i - 1;
//            return i;
//        }).toArray();
//
//        Kind kind = (explicit != null)
//                ? explicit
//                : (ints.length > 1 ? Kind.LIST : Kind.SINGLE);
//
//        return new KeyParse(base, kind, ints);
//    }
//
//
//
//    private KeyParser() {
//    } // no instances
//
//
//
//
//
//}
