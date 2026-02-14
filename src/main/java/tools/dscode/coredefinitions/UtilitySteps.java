package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UtilitySteps {

    public class CommonTransforms {

        // -----------------------------
        // Casing / basic string ops
        // -----------------------------

        @Given("^uppercase:(.*)$")
        public static String uppercase(String text) {
            System.out.println("uppercasing: " + text);
            return text.toUpperCase(Locale.ROOT);
        }

        @Given("^lowercase:(.*)$")
        public static String lowercase(String text) {
            System.out.println("lowercasing: " + text);
            return text.toLowerCase(Locale.ROOT);
        }

        @Given("^capitalize:(.*)$")
        public static String capitalize(String text) {
            System.out.println("capitalizing: " + text);
            if (text == null || text.isEmpty()) return "";
            String s = text;
            // keep exactly as-is beyond first char
            return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
        }

        @Given("^titlecase:(.*)$")
        public static String titlecase(String text) {
            System.out.println("titlecasing: " + text);
            if (text == null || text.isBlank()) return "";
            String[] parts = text.trim().split("\\s+");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (!p.isEmpty()) {
                    sb.append(p.substring(0, 1).toUpperCase(Locale.ROOT));
                    sb.append(p.substring(1).toLowerCase(Locale.ROOT));
                }
                if (i < parts.length - 1) sb.append(' ');
            }
            return sb.toString();
        }

        @Given("^trim:(.*)$")
        public static String trim(String text) {
            System.out.println("trimming: " + text);
            return text == null ? "" : text.trim();
        }

        @Given("^strip:(.*)$")
        public static String strip(String text) {
            System.out.println("stripping (unicode): " + text);
            return text == null ? "" : text.strip();
        }

        @Given("^collapseSpaces:(.*)$")
        public static String collapseSpaces(String text) {
            System.out.println("collapsing spaces: " + text);
            if (text == null) return "";
            return text.trim().replaceAll("\\s+", " ");
        }

        @Given("^removeWhitespace:(.*)$")
        public static String removeWhitespace(String text) {
            System.out.println("removing whitespace: " + text);
            if (text == null) return "";
            return text.replaceAll("\\s+", "");
        }

        // -----------------------------
        // Normalization / filtering
        // -----------------------------

        @Given("^normalizeNFC:(.*)$")
        public static String normalizeNFC(String text) {
            System.out.println("normalizing NFC: " + text);
            if (text == null) return "";
            return Normalizer.normalize(text, Normalizer.Form.NFC);
        }

        @Given("^normalizeNFKC:(.*)$")
        public static String normalizeNFKC(String text) {
            System.out.println("normalizing NFKC: " + text);
            if (text == null) return "";
            return Normalizer.normalize(text, Normalizer.Form.NFKC);
        }

        @Given("^removeDiacritics:(.*)$")
        public static String removeDiacritics(String text) {
            System.out.println("removing diacritics: " + text);
            if (text == null) return "";
            String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
            return decomposed.replaceAll("\\p{M}+", "");
        }

        @Given("^digitsOnly:(.*)$")
        public static String digitsOnly(String text) {
            System.out.println("keeping digits only: " + text);
            if (text == null) return "";
            return text.replaceAll("\\D+", "");
        }

        @Given("^alnumOnly:(.*)$")
        public static String alnumOnly(String text) {
            System.out.println("keeping alnum only: " + text);
            if (text == null) return "";
            return text.replaceAll("[^\\p{Alnum}]+", "");
        }

        @Given("^lettersOnly:(.*)$")
        public static String lettersOnly(String text) {
            System.out.println("keeping letters only: " + text);
            if (text == null) return "";
            return text.replaceAll("[^\\p{L}]+", "");
        }

        @Given("^keepByRegex:(.*?);(.*)$")
        public static String keepByRegex(String pattern, String text) {
            System.out.println("keepByRegex pattern=" + pattern + " text=" + text);
            if (text == null) return "";
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                // if there are groups, prefer group(1), else whole match
                sb.append(m.groupCount() >= 1 ? m.group(1) : m.group());
            }
            return sb.toString();
        }

        // -----------------------------
        // Replace / regex replace / regex extract
        // Note: using ; as a delimiter for multiple args.
        // -----------------------------

        @Given("^replace:(.*?);(.*?);(.*)$")
        public static String replace(String target, String replacement, String text) {
            System.out.println("replacing '" + target + "'->'" + replacement + "' in: " + text);
            if (text == null) return "";
            return text.replace(target, replacement);
        }

        @Given("^replaceRegex:(.*?);(.*?);(.*)$")
        public static String replaceRegex(String regex, String replacement, String text) {
            System.out.println("replaceRegex /" + regex + "/ -> '" + replacement + "' in: " + text);
            if (text == null) return "";
            return text.replaceAll(regex, replacement);
        }

        @Given("^extractRegex:(.*?);(\\d+);(.*)$")
        public static String extractRegexGroup(String regex, String groupIndexStr, String text) {
            System.out.println("extractRegex regex=" + regex + " group=" + groupIndexStr + " text=" + text);
            if (text == null) return "";
            int groupIndex = parseIntSafe(groupIndexStr, 0);
            Matcher m = Pattern.compile(regex).matcher(text);
            if (!m.find()) return "";
            if (groupIndex <= m.groupCount()) return nullToEmpty(m.group(groupIndex));
            return "";
        }

        @Given("^extractAllRegex:(.*?);(\\d+);(.*)$")
        public static String extractAllRegexGroup(String regex, String groupIndexStr, String text) {
            System.out.println("extractAllRegex regex=" + regex + " group=" + groupIndexStr + " text=" + text);
            if (text == null) return "";
            int groupIndex = parseIntSafe(groupIndexStr, 0);
            Matcher m = Pattern.compile(regex).matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String part = (groupIndex <= m.groupCount()) ? m.group(groupIndex) : "";
                sb.append(nullToEmpty(part));
            }
            return sb.toString();
        }

        // -----------------------------
        // Numeric parsing helpers (String in / String out)
        // -----------------------------

        private static BigDecimal parseDecimal(String s) {
            if (s == null) return BigDecimal.ZERO;
            String t = s.trim();
            if (t.isEmpty()) return BigDecimal.ZERO;
            // allow commas in inputs like "1,234.50"
            t = t.replace(",", "");
            return new BigDecimal(t);
        }

        private static int parseIntSafe(String s, int fallback) {
            try {
                if (s == null) return fallback;
                String t = s.trim();
                if (t.isEmpty()) return fallback;
                return Integer.parseInt(t);
            } catch (Exception e) {
                return fallback;
            }
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }

        // -----------------------------
        // Numeric transforms (String args -> String output)
        // -----------------------------

        @Given("^add:(.*?);(.*)$")
        public static String add(String a, String b) {
            System.out.println("add: " + a + " + " + b);
            return parseDecimal(a).add(parseDecimal(b)).stripTrailingZeros().toPlainString();
        }

        @Given("^subtract:(.*?);(.*)$")
        public static String subtract(String a, String b) {
            System.out.println("subtract: " + a + " - " + b);
            return parseDecimal(a).subtract(parseDecimal(b)).stripTrailingZeros().toPlainString();
        }

        @Given("^multiply:(.*?);(.*)$")
        public static String multiply(String a, String b) {
            System.out.println("multiply: " + a + " * " + b);
            return parseDecimal(a).multiply(parseDecimal(b)).stripTrailingZeros().toPlainString();
        }

        @Given("^divide:(.*?);(.*?);(\\d+)$")
        public static String divide(String a, String b, String scaleStr) {
            System.out.println("divide: " + a + " / " + b + " scale=" + scaleStr);
            int scale = parseIntSafe(scaleStr, 2);
            BigDecimal divisor = parseDecimal(b);
            if (divisor.compareTo(BigDecimal.ZERO) == 0) return "NaN";
            return parseDecimal(a).divide(divisor, scale, RoundingMode.HALF_UP).toPlainString();
        }

        @Given("^floor:(.*)$")
        public static String floor(String a) {
            System.out.println("floor: " + a);
            BigDecimal d = parseDecimal(a);
            return d.setScale(0, RoundingMode.FLOOR).toPlainString();
        }

        @Given("^ceil:(.*)$")
        public static String ceil(String a) {
            System.out.println("ceil: " + a);
            BigDecimal d = parseDecimal(a);
            return d.setScale(0, RoundingMode.CEILING).toPlainString();
        }

        @Given("^truncate:(.*?);(\\d+)$")
        public static String truncate(String a, String placesStr) {
            System.out.println("truncate: " + a + " places=" + placesStr);
            int places = parseIntSafe(placesStr, 0);
            return parseDecimal(a).setScale(places, RoundingMode.DOWN).toPlainString();
        }

        @Given("^roundHalfUp:(.*?);(\\d+)$")
        public static String roundHalfUp(String a, String placesStr) {
            System.out.println("roundHalfUp: " + a + " places=" + placesStr);
            int places = parseIntSafe(placesStr, 0);
            return parseDecimal(a).setScale(places, RoundingMode.HALF_UP).toPlainString();
        }

        @Given("^min:(.*?);(.*)$")
        public static String min(String a, String b) {
            System.out.println("min: " + a + ", " + b);
            BigDecimal da = parseDecimal(a);
            BigDecimal db = parseDecimal(b);
            return (da.compareTo(db) <= 0 ? da : db).stripTrailingZeros().toPlainString();
        }

        @Given("^max:(.*?);(.*)$")
        public static String max(String a, String b) {
            System.out.println("max: " + a + ", " + b);
            BigDecimal da = parseDecimal(a);
            BigDecimal db = parseDecimal(b);
            return (da.compareTo(db) >= 0 ? da : db).stripTrailingZeros().toPlainString();
        }

        // -----------------------------
        // Zero stuffing / trimming
        // (useful for IDs, fixed-width numbers, etc.)
        // -----------------------------

        @Given("^padLeftZeros:(.*?);(\\d+)$")
        public static String padLeftZeros(String text, String widthStr) {
            System.out.println("padLeftZeros: " + text + " width=" + widthStr);
            int width = parseIntSafe(widthStr, 0);
            String s = nullToEmpty(text);
            if (s.length() >= width) return s;
            return "0".repeat(width - s.length()) + s;
        }

        @Given("^padRightZeros:(.*?);(\\d+)$")
        public static String padRightZeros(String text, String widthStr) {
            System.out.println("padRightZeros: " + text + " width=" + widthStr);
            int width = parseIntSafe(widthStr, 0);
            String s = nullToEmpty(text);
            if (s.length() >= width) return s;
            return s + "0".repeat(width - s.length());
        }

        @Given("^trimLeadingZeros:(.*)$")
        public static String trimLeadingZeros(String text) {
            System.out.println("trimLeadingZeros: " + text);
            String s = nullToEmpty(text);
            // keep a single zero if the string is all zeros
            String t = s.replaceFirst("^0+(?!$)", "");
            return t;
        }

        @Given("^trimTrailingZerosDecimal:(.*)$")
        public static String trimTrailingZerosDecimal(String text) {
            System.out.println("trimTrailingZerosDecimal: " + text);
            // For decimals like "12.3400" -> "12.34", "12.000" -> "12"
            BigDecimal d = parseDecimal(text);
            return d.stripTrailingZeros().toPlainString();
        }

        // -----------------------------
        // Numeric formatting-ish helpers
        // -----------------------------

        @Given("^abs:(.*)$")
        public static String abs(String a) {
            System.out.println("abs: " + a);
            return parseDecimal(a).abs().stripTrailingZeros().toPlainString();
        }

        @Given("^clamp:(.*?);(.*?);(.*)$")
        public static String clamp(String value, String min, String max) {
            System.out.println("clamp value=" + value + " min=" + min + " max=" + max);
            BigDecimal v = parseDecimal(value);
            BigDecimal lo = parseDecimal(min);
            BigDecimal hi = parseDecimal(max);
            if (v.compareTo(lo) < 0) v = lo;
            if (v.compareTo(hi) > 0) v = hi;
            return v.stripTrailingZeros().toPlainString();
        }
    }


}
