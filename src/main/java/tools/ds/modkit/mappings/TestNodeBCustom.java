//package tools.ds.modkit.mappings;
//
//
//import java.util.List;
//import java.util.Objects;
//
//public class TestNodeBCustom {
//
//    // Simple POJO you can actually use in code
//    public static class Widget {
//        public String id;
//        public int qty;
//        public Meta meta; // nested object example
//
//        public Widget() {} // Jackson needs no-args
//        public Widget(String id, int qty) { this.id = id; this.qty = qty; }
//
//        @Override public String toString() { return "Widget{id='%s', qty=%d, meta=%s}".formatted(id, qty, meta); }
//        @Override public boolean equals(Object o) {
//            if (this == o) return true;
//            if (!(o instanceof Widget w)) return false;
//            return qty == w.qty && Objects.equals(id, w.id) && Objects.equals(meta, w.meta);
//        }
//        @Override public int hashCode() { return Objects.hash(id, qty, meta); }
//    }
//
//    public static class Meta {
//        public String color;
//        public Meta() {}
//        public Meta(String color) { this.color = color; }
//        @Override public String toString() { return "Meta{color='%s'}".formatted(color); }
//        @Override public boolean equals(Object o) {
//            if (this == o) return true;
//            if (!(o instanceof Meta m)) return false;
//            return Objects.equals(color, m.color);
//        }
//        @Override public int hashCode() { return Objects.hash(color); }
//    }
//
//    private static void expect(String label, Object expected, Object actual) {
//        boolean pass = Objects.equals(expected, actual);
//        System.out.println(label + " -> " + (pass ? "✅ PASS" : "❌ FAIL"));
//        if (!pass) {
//            System.out.println("  expected: " + expected);
//            System.out.println("  actual  : " + actual);
//        }
//    }
//
//    public static void main(String[] args) {
//        NodeB nm = new NodeB();
//
//        // Store two widgets under top-level ArrayNode "items"
////        nm.put("items", new Widget("w1", 5));
////        nm.put("items", new Widget("w2", 7));
//        nm.put("items", 5);
//        nm.put("items", 7);
//        // Add nested meta to second widget
//        nm.put("$.items[1].meta", new Meta("blue"));
//
//        // Retrieve the whole collection as Widgets (typed)
//        Object items =  nm.get("$.items[*]");
//        System.out.println("items (typed): " + items);
//
////        expect("items size", 2, items.size());
////        expect("items[0].id", "w1", items.get(0).id);
////        expect("items[0].qty", 5, items.get(0).qty);
////        expect("items[1].id", "w2", items.get(1).id);
////        expect("items[1].qty", 7, items.get(1).qty);
////        expect("items[1].meta.color", "blue", items.get(1).meta.color);
////
////        // Retrieve a single element typed
////        Widget second = nm.getOneAs("$.items[1]", Widget.class);
////        System.out.println("second (typed): " + second);
////        expect("second.id", "w2", second.id);
////        expect("second.meta.color", "blue", second.meta.color);
////
////        // Retrieve just the nested object as typed
////        Meta meta = nm.getOneAs("$.items[1].meta", Meta.class);
////        System.out.println("meta (typed): " + meta);
////        expect("meta.color", "blue", meta.color);
////
////        // Demonstrate overwrite: change qty and meta via typed put
////        nm.put("$.items[1].qty", 9);
////        nm.put("$.items[1].meta", new Meta("green"));
////        Widget secondAfter = nm.getOneAs("$.items[1]", Widget.class);
////        System.out.println("secondAfter (typed): " + secondAfter);
////        expect("secondAfter.qty", 9, secondAfter.qty);
////        expect("secondAfter.meta.color", "green", secondAfter.meta.color);
////
////        System.out.println("Done.");
//    }
//}
