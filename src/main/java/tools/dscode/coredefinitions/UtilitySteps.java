package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;

public class UtilitySteps {

    public static class CommonTransforms {

        // -----------------------------
        // Casing / basic string ops
        // -----------------------------

        @Given("^unquote:(.*)$")
        public static String unquote(String text) {
            stepInfo("unquoting: " + text);
            if (text == null) {
                return null;
            }

            String trimmed = text.trim();
            if (trimmed.length() < 2) {
                return text;
            }

            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);

            boolean supportedQuote = first == '\'' || first == '"' || first == '`';
            if (supportedQuote && first == last) {
                return trimmed.substring(1, trimmed.length() - 1);
            }

            return text;
        }

        @Given("^safeQuote:(.*)$")
        public static String safeQuote(String text) {
            stepInfo("safe-quoting: " + text);
            if (text == null) {
                return null;
            }

            String trimmed = text.trim();
            if (trimmed.length() < 4) {
                return text;
            }

            char outerFirst = trimmed.charAt(0);
            char outerLast = trimmed.charAt(trimmed.length() - 1);

            if (!isQuoteChar(outerFirst) || outerFirst != outerLast) {
                return text;
            }

            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.length() < 2) {
                return text;
            }

            char innerFirst = inner.charAt(0);
            char innerLast = inner.charAt(inner.length() - 1);

            if (!isQuoteChar(innerFirst) || innerFirst != innerLast) {
                return text;
            }

            String unwrappedInner = inner.substring(1, inner.length() - 1);
            return outerFirst + unwrappedInner + String.valueOf(outerLast);
        }

        @Given("^defaultSafeQuote:(.*)$")
        public static String defaultSafeQuote(String text) {
            stepInfo("default-safe-quoting: " + text);
            if (text == null) {
                return null;
            }

            String trimmed = text.trim();
            if (trimmed.length() < 4) {
                return text;
            }

            char outerFirst = trimmed.charAt(0);
            char outerLast = trimmed.charAt(trimmed.length() - 1);

            if (!isQuoteChar(outerFirst) || outerFirst != outerLast) {
                return text;
            }

            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.length() < 2) {
                return text;
            }

            char innerFirst = inner.charAt(0);
            char innerLast = inner.charAt(inner.length() - 1);

            if (!isQuoteChar(innerFirst) || innerFirst != innerLast) {
                return text;
            }

            return inner;
        }

        private static boolean isQuoteChar(char c) {
            return c == '\'' || c == '"' || c == '`';
        }

        @Given("^uppercase:(.*)$")
        public static String uppercase(String text) {
            stepInfo("uppercasing: " + text);
            return text == null ? "" : text.toUpperCase(Locale.ROOT);
        }

        @Given("^lowercase:(.*)$")
        public static String lowercase(String text) {
            stepInfo("lowercasing: " + text);
            return text == null ? "" : text.toLowerCase(Locale.ROOT);
        }

        @Given("^capitalize:(.*)$")
        public static String capitalize(String text) {
            stepInfo("capitalizing: " + text);
            if (text == null || text.isEmpty()) return "";
            return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
        }

        @Given("^titlecase:(.*)$")
        public static String titlecase(String text) {
            stepInfo("titlecasing: " + text);
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
            stepInfo("trimming: " + text);
            return text == null ? "" : text.trim();
        }

        @Given("^strip:(.*)$")
        public static String strip(String text) {
            stepInfo("stripping (unicode): " + text);
            return text == null ? "" : text.strip();
        }

        @Given("^collapseSpaces:(.*)$")
        public static String collapseSpaces(String text) {
            stepInfo("collapsing spaces: " + text);
            if (text == null) return "";
            return text.trim().replaceAll("\\s+", " ");
        }

        @Given("^removeWhitespace:(.*)$")
        public static String removeWhitespace(String text) {
            stepInfo("removing whitespace: " + text);
            if (text == null) return "";
            return text.replaceAll("\\s+", "");
        }

        // -----------------------------
        // Normalization / filtering
        // -----------------------------

        @Given("^normalizeNFC:(.*)$")
        public static String normalizeNFC(String text) {
            stepInfo("normalizing NFC: " + text);
            if (text == null) return "";
            return Normalizer.normalize(text, Normalizer.Form.NFC);
        }

        @Given("^normalizeNFKC:(.*)$")
        public static String normalizeNFKC(String text) {
            stepInfo("normalizing NFKC: " + text);
            if (text == null) return "";
            return Normalizer.normalize(text, Normalizer.Form.NFKC);
        }

        @Given("^removeDiacritics:(.*)$")
        public static String removeDiacritics(String text) {
            stepInfo("removing diacritics: " + text);
            if (text == null) return "";
            String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
            return decomposed.replaceAll("\\p{M}+", "");
        }

        @Given("^digitsOnly:(.*)$")
        public static String digitsOnly(String text) {
            stepInfo("keeping digits only: " + text);
            if (text == null) return "";
            return text.replaceAll("\\D+", "");
        }

        @Given("^alnumOnly:(.*)$")
        public static String alnumOnly(String text) {
            stepInfo("keeping alnum only: " + text);
            if (text == null) return "";
            return text.replaceAll("[^\\p{Alnum}]+", "");
        }

        @Given("^lettersOnly:(.*)$")
        public static String lettersOnly(String text) {
            stepInfo("keeping letters only: " + text);
            if (text == null) return "";
            return text.replaceAll("[^\\p{L}]+", "");
        }

        @Given("^keepByRegex\\s*(\\S)(.*)$")
        public static String keepByRegex(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 2);
            String pattern = parts[0];
            String text = parts[1];

            stepInfo("keepByRegex delimiter=" + delimiter + " pattern=" + pattern + " text=" + text);
            if (text == null) return "";
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                sb.append(m.groupCount() >= 1 ? nullToEmpty(m.group(1)) : nullToEmpty(m.group()));
            }
            return sb.toString();
        }

        // -----------------------------
        // Replace / regex replace / regex extract
        // Uses caller-chosen single-character non-whitespace delimiter.
        // Example:
        // replace ;old;new;some text
        // replace |old|new|some text
        // -----------------------------

        @Given("^replace\\s*(\\S)(.*)$")
        public static String replace(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 3);
            String target = parts[0];
            String replacement = parts[1];
            String text = parts[2];

            stepInfo("replacing '" + target + "'->'" + replacement + "' in: " + text);
            if (text == null) return "";
            return text.replace(target, replacement);
        }

        @Given("^replaceRegex\\s*(\\S)(.*)$")
        public static String replaceRegex(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 3);
            String regex = parts[0];
            String replacement = parts[1];
            String text = parts[2];

            stepInfo("replaceRegex /" + regex + "/ -> '" + replacement + "' in: " + text);
            if (text == null) return "";
            return text.replaceAll(regex, replacement);
        }

        @Given("^extractRegex\\s*(\\S)(.*)$")
        public static String extractRegexGroup(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 3);
            String regex = parts[0];
            String groupIndexStr = parts[1];
            String text = parts[2];

            stepInfo("extractRegex regex=" + regex + " group=" + groupIndexStr + " text=" + text);
            if (text == null) return "";
            int groupIndex = parseIntSafe(groupIndexStr, 0);
            Matcher m = Pattern.compile(regex).matcher(text);
            if (!m.find()) return "";
            if (groupIndex <= m.groupCount()) return nullToEmpty(m.group(groupIndex));
            return "";
        }

        @Given("^extractAllRegex\\s*(\\S)(.*)$")
        public static String extractAllRegexGroup(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 3);
            String regex = parts[0];
            String groupIndexStr = parts[1];
            String text = parts[2];

            stepInfo("extractAllRegex regex=" + regex + " group=" + groupIndexStr + " text=" + text);
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

        public static String[] splitValues(String delimiter, String values) {
            if (values == null) {
                return new String[0];
            }
            return values.split(Pattern.quote(delimiter), -1);
        }

        private static String[] splitArgs(String delimiter, String values, int expectedParts) {
            String[] raw = splitValues(delimiter, values);
            String[] parts = new String[expectedParts];

            int copyCount = Math.min(expectedParts - 1, raw.length);
            System.arraycopy(raw, 0, parts, 0, copyCount);

            if (raw.length >= expectedParts) {
                StringBuilder tail = new StringBuilder();
                for (int i = expectedParts - 1; i < raw.length; i++) {
                    if (i > expectedParts - 1) {
                        tail.append(delimiter);
                    }
                    tail.append(raw[i]);
                }
                parts[expectedParts - 1] = tail.toString();
            } else if (raw.length == expectedParts - 1) {
                parts[expectedParts - 1] = "";
            }

            for (int i = 0; i < expectedParts; i++) {
                if (parts[i] == null) {
                    parts[i] = "";
                }
            }

            return parts;
        }

        // -----------------------------
        // Numeric transforms (String args -> String output)
        // -----------------------------

        @Given("^add\\s*(\\S)(.*)$")
        public static String add(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 2);
            String a = parts[0];
            String b = parts[1];

            stepInfo("add: " + a + " + " + b);
            return parseDecimal(a).add(parseDecimal(b)).stripTrailingZeros().toPlainString();
        }

        @Given("^subtract\\s*(\\S)(.*)$")
        public static String subtract(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 2);
            String a = parts[0];
            String b = parts[1];

            stepInfo("subtract: " + a + " - " + b);
            return parseDecimal(a).subtract(parseDecimal(b)).stripTrailingZeros().toPlainString();
        }

        @Given("^multiply\\s*(\\S)(.*)$")
        public static String multiply(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 2);
            String a = parts[0];
            String b = parts[1];

            stepInfo("multiply: " + a + " * " + b);
            return parseDecimal(a).multiply(parseDecimal(b)).stripTrailingZeros().toPlainString();
        }

        @Given("^divide\\s*(\\S)(.*)$")
        public static String divide(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 3);
            String a = parts[0];
            String b = parts[1];
            String scaleStr = parts[2];

            stepInfo("divide: " + a + " / " + b + " scale=" + scaleStr);
            int scale = parseIntSafe(scaleStr, 2);
            BigDecimal divisor = parseDecimal(b);
            if (divisor.compareTo(BigDecimal.ZERO) == 0) return "NaN";
            return parseDecimal(a).divide(divisor, scale, RoundingMode.HALF_UP).toPlainString();
        }

        @Given("^floor:(.*)$")
        public static String floor(String a) {
            stepInfo("floor: " + a);
            BigDecimal d = parseDecimal(a);
            return d.setScale(0, RoundingMode.FLOOR).toPlainString();
        }

        @Given("^ceil:(.*)$")
        public static String ceil(String a) {
            stepInfo("ceil: " + a);
            BigDecimal d = parseDecimal(a);
            return d.setScale(0, RoundingMode.CEILING).toPlainString();
        }

        @Given("^truncate\\s*(\\S)(.*)$")
        public static String truncate(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 2);
            String a = parts[0];
            String placesStr = parts[1];

            stepInfo("truncate: " + a + " places=" + placesStr);
            int places = parseIntSafe(placesStr, 0);
            return parseDecimal(a).setScale(places, RoundingMode.DOWN).toPlainString();
        }

        @Given("^roundHalfUp\\s*(\\S)(.*)$")
        public static String roundHalfUp(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 2);
            String a = parts[0];
            String placesStr = parts[1];

            stepInfo("roundHalfUp: " + a + " places=" + placesStr);
            int places = parseIntSafe(placesStr, 0);
            return parseDecimal(a).setScale(places, RoundingMode.HALF_UP).toPlainString();
        }

        @Given("^min\\s*(\\S)(.*)$")
        public static String min(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 2);
            String a = parts[0];
            String b = parts[1];

            stepInfo("min: " + a + ", " + b);
            BigDecimal da = parseDecimal(a);
            BigDecimal db = parseDecimal(b);
            return (da.compareTo(db) <= 0 ? da : db).stripTrailingZeros().toPlainString();
        }

        @Given("^max\\s*(\\S)(.*)$")
        public static String max(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 2);
            String a = parts[0];
            String b = parts[1];

            stepInfo("max: " + a + ", " + b);
            BigDecimal da = parseDecimal(a);
            BigDecimal db = parseDecimal(b);
            return (da.compareTo(db) >= 0 ? da : db).stripTrailingZeros().toPlainString();
        }

        // -----------------------------
        // Zero stuffing / trimming
        // -----------------------------

        @Given("^padLeftZeros\\s*(\\S)(.*)$")
        public static String padLeftZeros(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 2);
            String text = parts[0];
            String widthStr = parts[1];

            stepInfo("padLeftZeros: " + text + " width=" + widthStr);
            int width = parseIntSafe(widthStr, 0);
            String s = nullToEmpty(text);
            if (s.length() >= width) return s;
            return "0".repeat(width - s.length()) + s;
        }

        @Given("^padRightZeros\\s*(\\S)(.*)$")
        public static String padRightZeros(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 2);
            String text = parts[0];
            String widthStr = parts[1];

            stepInfo("padRightZeros: " + text + " width=" + widthStr);
            int width = parseIntSafe(widthStr, 0);
            String s = nullToEmpty(text);
            if (s.length() >= width) return s;
            return s + "0".repeat(width - s.length());
        }

        @Given("^trimLeadingZeros:(.*)$")
        public static String trimLeadingZeros(String text) {
            stepInfo("trimLeadingZeros: " + text);
            String s = nullToEmpty(text);
            return s.replaceFirst("^0+(?!$)", "");
        }

        @Given("^trimTrailingZerosDecimal:(.*)$")
        public static String trimTrailingZerosDecimal(String text) {
            stepInfo("trimTrailingZerosDecimal: " + text);
            BigDecimal d = parseDecimal(text);
            return d.stripTrailingZeros().toPlainString();
        }

        // -----------------------------
        // Numeric formatting-ish helpers
        // -----------------------------

        @Given("^abs:(.*)$")
        public static String abs(String a) {
            stepInfo("abs: " + a);
            return parseDecimal(a).abs().stripTrailingZeros().toPlainString();
        }

        @Given("^clamp\\s*(\\S)(.*)$")
        public static String clamp(String delimiter, String values) {
            String[] parts = splitArgs(delimiter, values, 3);
            String value = parts[0];
            String min = parts[1];
            String max = parts[2];

            stepInfo("clamp value=" + value + " min=" + min + " max=" + max);
            BigDecimal v = parseDecimal(value);
            BigDecimal lo = parseDecimal(min);
            BigDecimal hi = parseDecimal(max);
            if (v.compareTo(lo) < 0) v = lo;
            if (v.compareTo(hi) > 0) v = hi;
            return v.stripTrailingZeros().toPlainString();
        }
    }
}