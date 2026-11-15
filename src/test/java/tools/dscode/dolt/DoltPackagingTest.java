package tools.dscode.dolt;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

class DoltPackagingTest {
    @Test
    void mariadbDriverIsRuntimeAndNotShaded() throws Exception {
        Path pom = Path.of("build/publications/maven/pom-default.xml");
        if (!Files.isRegularFile(pom)) fail("missing " + pom + " (verifySqlitePackaging should have generated it)");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(pom.toFile());
        var nodes = document.getElementsByTagName("dependency");
        String version = null;
        String scope = null;
        int hits = 0;
        for (int index = 0; index < nodes.getLength(); index++) {
            var node = nodes.item(index);
            String group = child(node, "groupId");
            String artifact = child(node, "artifactId");
            if ("org.mariadb.jdbc".equals(group) && "mariadb-java-client".equals(artifact)) {
                hits++;
                version = child(node, "version");
                scope = child(node, "scope");
            }
        }
        assertEquals(1, hits);
        assertEquals("3.5.11", version);
        assertEquals("runtime", scope);
        Path jar = Path.of("build/libs/pickleball-3.0.0.jar");
        if (!Files.isRegularFile(jar)) fail("missing " + jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                assertFalse(entry.getName().startsWith("org/mariadb/"), entry.getName());
                assertFalse(entry.getName().contains("mariadb-java-client"), entry.getName());
            }
        }
    }

    private static String child(org.w3c.dom.Node parent, String name) {
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            var child = children.item(index);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }
}
