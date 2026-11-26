package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packaged workbench UI is monaco-editor and typescript, plus transitives whose
 * license is on the allow-list. The nested workbench jar does not contain node_modules.
 */
class WorkbenchUiLicenseTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED = Set.of(
            "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC");
    private static final Set<String> BANNED = Set.of(
            "react", "react-dom", "electron", "tsx", "axe-core", "express");
    private static final String PREFIX = "META-INF/pickleball/workbench-ui/";
    private static final String MONACO = PREFIX + "node_modules/monaco-editor/package.json";
    private static final String NESTED = "META-INF/pickleball/workbench/pickleball-workbench.jar";

    @Test
    void packagedUiKeepsMonacoAndTypescriptAndKeepsNodeModulesOutOfTheWorkbenchJar() throws Exception {
        String lockText = Files.readString(Path.of("workbench-ui/package-lock.json"), StandardCharsets.UTF_8);
        assertTrue(lockText.contains("node_modules/"), lockText);
        JsonNode lock = JSON.readTree(lockText);
        Set<String> direct = new HashSet<>();
        lock.path("packages").path("").path("dependencies").fieldNames().forEachRemaining(direct::add);
        assertEquals(Set.of("monaco-editor", "typescript"), direct);
        assertFalse(WorkbenchUiPackager.allowed("Unlicense"));
        assertFalse(WorkbenchUiPackager.allowed("CC0-1.0"));
        assertTrue(WorkbenchUiPackager.allowed("public domain"));
        assertTrue(WorkbenchUiPackager.allowed("MIT"));
        assertTrue(WorkbenchUiPackager.allowed("Apache-2.0"));
        assertTrue(WorkbenchUiPackager.allowed("BSD-2-Clause"));
        assertTrue(WorkbenchUiPackager.allowed("BSD-3-Clause"));
        assertTrue(WorkbenchUiPackager.allowed("ISC"));

        JsonNode packages = lock.path("packages");
        Iterator<String> keys = packages.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.isEmpty()) continue;
            assertTrue(key.contains("node_modules/"), key);
            String name = packageName(key);
            assertFalse(BANNED.contains(name), name);
            String license = packages.path(key).path("license").asText("");
            if (!license.isBlank()) assertTrue(allowed(license), license + " for " + key);
        }

        Path jar = Path.of("build/libs/pickleball-3.0.0.jar");
        boolean unpacked = false;
        if (Files.isRegularFile(jar)) {
            try (JarFile file = new JarFile(jar.toFile())) {
                assertNotNull(file.getJarEntry(MONACO), MONACO + " missing from " + jar);
                checkPackagedLicenses(file);
                JarEntry nested = file.getJarEntry(NESTED);
                assertNotNull(nested, NESTED);
                try (InputStream in = file.getInputStream(nested); JarInputStream inner = new JarInputStream(in)) {
                    JarEntry entry;
                    while ((entry = inner.getNextJarEntry()) != null) {
                        assertFalse(entry.getName().contains("node_modules"), entry.getName());
                    }
                }
                unpacked = true;
            }
        }
        if (!unpacked) {
            URL url = WorkbenchUiLicenseTest.class.getClassLoader().getResource(MONACO);
            assertNotNull(url, MONACO);
            assertTrue(url.toString().contains("monaco-editor"), url.toString());
        }
    }

    private static void checkPackagedLicenses(JarFile file) throws Exception {
        Enumeration<JarEntry> entries = file.entries();
        boolean monaco = false;
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!isPackageManifest(entry.getName())) continue;
            try (InputStream in = file.getInputStream(entry)) {
                JsonNode node = JSON.readTree(in);
                String name = node.path("name").asText("");
                assertFalse(BANNED.contains(name), name);
                assertTrue(allowed(licenseOf(node)), entry.getName() + " license " + licenseOf(node));
                if ("monaco-editor".equals(name)) monaco = true;
            }
        }
        assertTrue(monaco, "monaco-editor package.json was not read from the jar");
    }

    private static boolean isPackageManifest(String name) {
        int at = name.lastIndexOf("/node_modules/");
        if (at < 0 || !name.endsWith("/package.json")) return false;
        if (!name.startsWith(PREFIX)) return false;
        String body = name.substring(at + "/node_modules/".length(), name.length() - "/package.json".length());
        if (body.startsWith("@")) {
            int slash = body.indexOf('/');
            return slash > 1 && body.indexOf('/', slash + 1) < 0;
        }
        return !body.isEmpty() && !body.contains("/");
    }

    private static String packageName(String key) {
        int at = key.lastIndexOf("node_modules/");
        String rest = at < 0 ? key : key.substring(at + "node_modules/".length());
        if (rest.startsWith("@")) {
            int slash = rest.indexOf('/');
            if (slash < 0) return rest;
            int next = rest.indexOf('/', slash + 1);
            return next < 0 ? rest : rest.substring(0, next);
        }
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static String licenseOf(JsonNode node) {
        JsonNode license = node.get("license");
        if (license != null && license.isTextual()) return license.asText("").trim();
        if (license != null && license.isObject()) return license.path("type").asText("").trim();
        JsonNode licenses = node.get("licenses");
        if (licenses != null && licenses.isArray() && !licenses.isEmpty()) {
            JsonNode first = licenses.get(0);
            if (first.isTextual()) return first.asText("").trim();
            return first.path("type").asText("").trim();
        }
        return "";
    }

    private static boolean allowed(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String text = raw.trim();
        if (text.toLowerCase(Locale.ROOT).contains(" and ")) {
            for (String part : text.split("(?i)\\s+AND\\s+")) {
                if (!allowed(part)) return false;
            }
            return true;
        }
        if (text.toLowerCase(Locale.ROOT).contains(" or ")) {
            for (String part : text.split("(?i)\\s+OR\\s+")) {
                if (allowed(part)) return true;
            }
            return false;
        }
        String token = text.replace("(", "").replace(")", "").trim();
        if ("public domain".equalsIgnoreCase(token)) return true;
        return ALLOWED.contains(token);
    }
}
