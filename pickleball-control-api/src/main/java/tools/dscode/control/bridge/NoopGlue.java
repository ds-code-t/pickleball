package tools.dscode.control.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects consumer glue whose body does nothing. Framework classes
 * ({@code tools.dscode.*}) are never flagged. A class file that cannot be
 * parsed is not flagged unless the source body is visibly empty.
 * {@code pkb_noop_glue=fail} (or {@code PKB_NOOP_GLUE=fail}) turns the flag
 * into a failure. The default stamps {@code noop:true} and the CLI warns.
 */
public final class NoopGlue {
    public static final String MARK = "noop:true";
    public static final String WARNING =
            "WARNING: noop consumer glue (noop:true). The step reported SUCCESS without acting. Set pkb_noop_glue=fail to fail the step.";

    private NoopGlue() {
    }

    public static boolean failMode() {
        String raw = System.getProperty("pkb_noop_glue");
        if (raw == null || raw.isBlank()) raw = System.getenv("PKB_NOOP_GLUE");
        return raw != null && "fail".equalsIgnoreCase(raw.trim());
    }

    public static String stamp(String valueText) {
        if (valueText == null || valueText.isBlank()) return MARK;
        if (valueText.contains(MARK)) return valueText;
        return valueText + " " + MARK;
    }

    public static boolean consumerEmpty(Method method) {
        if (method == null) return false;
        String className = method.getDeclaringClass().getName();
        if (ControlBridgeStepResolver.isFrameworkClass(className)) return false;
        Boolean fromClass = classFileEmpty(method);
        if (Boolean.TRUE.equals(fromClass)) return true;
        if (Boolean.FALSE.equals(fromClass)) return false;
        return Boolean.TRUE.equals(sourceEmpty(method));
    }

    /**
     * Stamps or fails a protocol result when {@code method} is empty consumer glue.
     * Non-SUCCESS results and real methods are returned unchanged.
     */
    public static tools.dscode.control.protocol.ControlBridgeCallResult decorate(
            Method method,
            tools.dscode.control.protocol.ControlBridgeCallResult result
    ) {
        if (result == null || !"SUCCESS".equals(result.status()) || !consumerEmpty(method)) return result;
        if (failMode()) {
            return new tools.dscode.control.protocol.ControlBridgeCallResult(
                    "FAILED",
                    null,
                    MARK,
                    new tools.dscode.control.protocol.ControlBridgeError(
                            "NOOP_GLUE",
                            "noop consumer glue (noop:true). The step reported SUCCESS without acting.",
                            ""
                    ),
                    result.runtime()
            );
        }
        return new tools.dscode.control.protocol.ControlBridgeCallResult(
                result.status(),
                result.valueType(),
                stamp(result.valueText()),
                result.error(),
                result.runtime()
        );
    }

    public static void warnIfPresent(String rendered, Appendable err) {
        if (rendered == null || err == null || !rendered.contains(MARK)) return;
        try {
            err.append(WARNING).append(System.lineSeparator());
        } catch (IOException ignored) {
            // The result line is already printed.
        }
    }

    static Boolean classFileEmpty(Method method) {
        String resource = method.getDeclaringClass().getName().replace('.', '/') + ".class";
        ClassLoader loader = method.getDeclaringClass().getClassLoader();
        InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource);
        if (in == null) return null;
        try (in) {
            return codeEmpty(in.readAllBytes(), method.getName(), descriptor(method));
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    static Boolean sourceEmpty(Method method) {
        try {
            String path = ControlBridgeStepResolver.findSourcePath(method.getDeclaringClass());
            if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path))) return null;
            String text = Files.readString(Path.of(path));
            String stripped = text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
            int idx = indexOfMethod(stripped, method.getName());
            if (idx < 0) return null;
            int brace = stripped.indexOf('{', idx);
            if (brace < 0) return null;
            int end = matchingBrace(stripped, brace);
            if (end < 0) return null;
            String body = stripped.substring(brace + 1, end);
            body = body.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "");
            body = body.replaceAll("\\s+", "");
            return body.isEmpty()
                    || body.equals("return;")
                    || body.equals("returnnull;")
                    || body.equals("return0;")
                    || body.equals("returnfalse;");
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static int indexOfMethod(String text, String name) {
        String token = name + "(";
        int from = 0;
        while (from < text.length()) {
            int found = text.indexOf(token, from);
            if (found < 0) return -1;
            if (found == 0 || !Character.isJavaIdentifierPart(text.charAt(found - 1))) return found;
            from = found + token.length();
        }
        return -1;
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    static String descriptor(Method method) {
        StringBuilder desc = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) desc.append(field(parameter));
        desc.append(')');
        desc.append(field(method.getReturnType()));
        return desc.toString();
    }

    private static String field(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == void.class) return "V";
            if (type == boolean.class) return "Z";
            if (type == byte.class) return "B";
            if (type == char.class) return "C";
            if (type == short.class) return "S";
            if (type == int.class) return "I";
            if (type == long.class) return "J";
            if (type == float.class) return "F";
            if (type == double.class) return "D";
        }
        if (type.isArray()) return "[" + field(type.getComponentType());
        return "L" + type.getName().replace('.', '/') + ";";
    }

    /**
     * @return TRUE when the method body is only a return, FALSE when real code
     *         is present, null when the class file cannot be read confidently
     */
    static Boolean codeEmpty(byte[] bytes, String methodName, String methodDescriptor) {
        Cursor cursor = new Cursor(bytes);
        if (cursor.u4() != 0xCAFEBABE) return null;
        cursor.u2();
        cursor.u2();
        int count = cursor.u2();
        String[] utf8 = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = cursor.u1();
            switch (tag) {
                case 1 -> {
                    int length = cursor.u2();
                    utf8[i] = new String(cursor.bytes(length), StandardCharsets.UTF_8);
                }
                case 7, 8, 16, 19, 20 -> cursor.skip(2);
                case 15 -> cursor.skip(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> cursor.skip(4);
                case 5, 6 -> {
                    cursor.skip(8);
                    i++;
                }
                default -> {
                    return null;
                }
            }
        }
        cursor.u2();
        cursor.u2();
        cursor.u2();
        int interfaces = cursor.u2();
        cursor.skip(interfaces * 2);
        int fields = cursor.u2();
        for (int i = 0; i < fields; i++) skipMember(cursor);
        int methods = cursor.u2();
        for (int i = 0; i < methods; i++) {
            int access = cursor.u2();
            int nameIndex = cursor.u2();
            int descIndex = cursor.u2();
            int attributes = cursor.u2();
            String name = nameIndex > 0 && nameIndex < utf8.length ? utf8[nameIndex] : null;
            String desc = descIndex > 0 && descIndex < utf8.length ? utf8[descIndex] : null;
            boolean match = methodName.equals(name) && methodDescriptor.equals(desc);
            if (match && ((access & 0x0400) != 0 || (access & 0x0100) != 0)) return Boolean.FALSE;
            Boolean empty = null;
            for (int a = 0; a < attributes; a++) {
                int attrName = cursor.u2();
                int length = cursor.u4();
                byte[] body = cursor.bytes(length);
                if (!match) continue;
                String attr = attrName > 0 && attrName < utf8.length ? utf8[attrName] : "";
                if (!"Code".equals(attr) || body.length < 8) continue;
                Cursor code = new Cursor(body);
                code.u2();
                code.u2();
                int codeLength = code.u4();
                if (codeLength < 0 || codeLength > body.length - 8) return null;
                byte[] opcodes = code.bytes(codeLength);
                empty = isEmpty(opcodes);
            }
            if (match) return empty == null ? Boolean.FALSE : empty;
        }
        return null;
    }

    private static void skipMember(Cursor cursor) {
        cursor.u2();
        cursor.u2();
        cursor.u2();
        int attributes = cursor.u2();
        for (int i = 0; i < attributes; i++) {
            cursor.u2();
            int length = cursor.u4();
            cursor.skip(length);
        }
    }

    private static Boolean isEmpty(byte[] opcodes) {
        if (opcodes.length == 1 && (opcodes[0] & 0xFF) == 0xB1) return Boolean.TRUE;
        if (opcodes.length == 2) {
            int a = opcodes[0] & 0xFF;
            int b = opcodes[1] & 0xFF;
            if (a == 0x01 && b == 0xB0) return Boolean.TRUE;
            if (a == 0x03 && b == 0xAC) return Boolean.TRUE;
            if (a == 0x09 && b == 0xAD) return Boolean.TRUE;
            if (a == 0x0B && b == 0xAE) return Boolean.TRUE;
            if (a == 0x0E && b == 0xAF) return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    private static final class Cursor {
        private final byte[] data;
        private int at;

        private Cursor(byte[] data) {
            this.data = data;
        }

        private int u1() {
            if (at >= data.length) throw new IllegalArgumentException("class file truncated");
            return data[at++] & 0xFF;
        }

        private int u2() {
            return (u1() << 8) | u1();
        }

        private int u4() {
            return (u1() << 24) | (u1() << 16) | (u1() << 8) | u1();
        }

        private void skip(int n) {
            if (n < 0 || at + n > data.length) throw new IllegalArgumentException("class file truncated");
            at += n;
        }

        private byte[] bytes(int n) {
            if (n < 0 || at + n > data.length) throw new IllegalArgumentException("class file truncated");
            byte[] slice = new byte[n];
            System.arraycopy(data, at, slice, 0, n);
            at += n;
            return slice;
        }
    }
}
